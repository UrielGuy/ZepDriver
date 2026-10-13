package com.zepdriver.core.nav

import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.closestPointOnSegment
import kotlin.math.max
import kotlin.math.roundToInt

data class NavState(
    val plan: RoutePlan,
    val legIndex: Int,
    val nextInstruction: Instruction?,
    val distanceToNextFt: Double,
    val remainingFt: Double,
    val offRoute: Boolean,
    /** Arrived at the final stop. */
    val finished: Boolean,
    /** Something to say now (turn prompt, "Arrived at X", "Rerouting"), or null. */
    val speech: String?,
    /** Distance left to the current stop (this leg only); [remainingFt] covers all stops. */
    val toStopFt: Double = remainingFt,
) {
    /** Minutes to the current stop when driving the speed limit the whole way. */
    fun minutesToStop(mph: Double = ETA_SPEED_MPH): Double = toStopFt / (mph * FEET_PER_MILE / 60.0)

    companion object {
        /** ETAs assume the 5 mph speed limit. */
        const val ETA_SPEED_MPH = 5.0
        private const val FEET_PER_MILE = 5280.0
    }
}

/**
 * Tracks progress along a multi-stop plan, advances legs on arrival, and
 * reroutes when the vehicle strays more than ~100 ft from the path.
 *
 * Not thread-safe: call [update] / [insertNext] from one thread (the
 * location callback). Replanning runs synchronously inside those calls.
 */
class NavigationSession(
    private val router: Router,
    start: LatLon,
    stops: List<Destination>,
) {
    private val remaining = stops.toMutableList()
    private var plan: RoutePlan? = null
    private var legIndex = 0
    private var geom: LegGeom? = null
    private var progress = 0.0
    private var offCount = 0
    private var finished = false
    private val spokenFar = HashSet<Int>()
    private val spokenNear = HashSet<Int>()
    private var current: NavState? = null

    init {
        replan(start)
        current = plan?.let { snapshot(router.playa.xy(start), emptyList()) }
    }

    /** Remaining stops, current target first. */
    val stops: List<Destination> get() = remaining.toList()

    val state: NavState? get() = current

    fun update(fix: Fix): NavState? {
        if (plan == null) {
            // Nothing planned yet (e.g. started off the playa): try again from here.
            if (remaining.isEmpty() || !replan(fix.position)) return null
        }
        val p = plan!!
        if (finished) return current?.copy(speech = null).also { current = it }
        val xy = router.playa.xy(fix.position)
        val speech = ArrayList<String>()

        // Arrival at the current stop.
        var g = legGeom()
        if (xy.distanceTo(g.end) <= ARRIVE_FT) {
            val leg = p.legs[legIndex]
            speech += arrivalSpeech(leg)
            if (remaining.isNotEmpty()) remaining.removeAt(0)
            if (legIndex + 1 >= p.legs.size) {
                finished = true
                progress = g.length
                current = NavState(p, legIndex, null, 0.0, 0.0, offRoute = false, finished = true, speech = speech.joinToString(". "))
                return current
            }
            legIndex++
            resetLeg()
            g = legGeom()
        }

        // Progress / off-route.
        val hit = g.nearest(xy, progress)
        if (hit.second > OFF_ROUTE_FT) offCount++ else offCount = 0
        if (offCount >= OFF_ROUTE_UPDATES) {
            offCount = 0
            if (replan(fix.position)) {
                speech += REROUTING
                current = snapshot(xy, speech)
                return current
            }
            current = snapshot(xy, speech, offRoute = true)
            return current
        }
        progress = hit.first
        current = snapshot(xy, speech, offRoute = hit.second > OFF_ROUTE_FT)
        return current
    }

    /**
     * Inserts [dest] as the very next stop (the Potties button) and replans from
     * [from]. If [from] can't be routed from, nothing changes.
     */
    fun insertNext(dest: Destination, from: LatLon): NavState? {
        remaining.add(0, dest)
        if (!replan(from)) {
            remaining.removeAt(0)
            return current
        }
        current = snapshot(router.playa.xy(from), emptyList())
        return current
    }

    private fun replan(from: LatLon): Boolean {
        val np = router.plan(from, remaining) ?: return false
        plan = np
        legIndex = 0
        finished = np.legs.isEmpty()
        resetLeg()
        return true
    }

    private fun resetLeg() {
        geom = null
        progress = 0.0
        offCount = 0
        spokenFar.clear()
        spokenNear.clear()
    }

    private fun legGeom(): LegGeom =
        geom ?: LegGeom(plan!!.legs[legIndex], router).also { geom = it }

    /** Builds the state at [xy] after [progress] was updated, adding due turn prompts. */
    private fun snapshot(xy: Xy, speech: List<String>, offRoute: Boolean = false): NavState {
        val p = plan!!
        if (finished || p.legs.isEmpty()) {
            return NavState(p, legIndex, null, 0.0, 0.0, offRoute, true, speech.joinToString(". ").ifEmpty { null })
        }
        val g = legGeom()
        val out = speech.toMutableList()
        var next: Instruction? = null
        var toNext = 0.0
        val lastIdx = g.leg.instructions.lastIndex
        for ((i, ins) in g.leg.instructions.withIndex()) {
            val a = g.instructionAlong[i]
            if (a > progress + 1.0 || i == lastIdx) {
                next = ins
                toNext = max(0.0, a - progress)
                if (!offRoute) {
                    if (toNext <= NEAR_FT && i != lastIdx && i !in spokenNear) {
                        spokenNear += i; spokenFar += i
                        out += ins.speech
                    } else if (toNext <= FAR_FT && i !in spokenFar && i !in spokenNear) {
                        spokenFar += i
                        out += "In ${roundFeet(toNext)} feet, ${lowerFirst(ins.speech)}"
                    }
                }
                break
            }
        }
        val toStop = max(0.0, g.length - progress)
        var rem = toStop
        for (k in legIndex + 1 until p.legs.size) rem += p.legs[k].distanceFt
        return NavState(p, legIndex, next, toNext, rem, offRoute, false, out.joinToString(". ").ifEmpty { null }, toStop)
    }

    private fun arrivalSpeech(leg: RouteLeg): String = leg.walkFt?.let {
        "Arrived. Park here and walk about ${InstructionBuilder.roundWalk(it)} feet to ${leg.destination.name}"
    } ?: "Arrived at ${leg.destination.name}"

    /** A leg's path in feet, with cumulative distances. */
    private class LegGeom(val leg: RouteLeg, router: Router) {
        val pts: List<Xy> = leg.path.map { router.playa.xy(it) }
        val al = DoubleArray(pts.size).also { for (k in 1 until pts.size) it[k] = it[k - 1] + pts[k - 1].distanceTo(pts[k]) }
        val length = al.last()
        val end: Xy = router.playa.xy(leg.parkAt)
        val instructionAlong = DoubleArray(leg.instructions.size) { al[leg.instructions[it].pathIndex.coerceIn(0, pts.lastIndex)] }

        /** (along, distance) of the closest path point, preferring points not far behind [hint]. */
        fun nearest(p: Xy, hint: Double): Pair<Double, Double> {
            var bestScore = Double.MAX_VALUE
            var bestAlong = 0.0
            var bestD = Double.MAX_VALUE
            if (pts.size == 1) return 0.0 to p.distanceTo(pts[0])
            for (k in 0 until pts.size - 1) {
                val c = closestPointOnSegment(p, pts[k], pts[k + 1])
                val d = p.distanceTo(c)
                val a = al[k] + pts[k].distanceTo(c)
                val score = d + if (a < hint - BACKTRACK_FT) BACKTRACK_PENALTY else 0.0
                if (score < bestScore) { bestScore = score; bestAlong = a; bestD = d }
            }
            return bestAlong to bestD
        }
    }

    companion object {
        const val ARRIVE_FT = 60.0
        const val OFF_ROUTE_FT = 100.0
        const val OFF_ROUTE_UPDATES = 3
        const val FAR_FT = 300.0
        const val NEAR_FT = 80.0
        const val REROUTING = "Rerouting"
        private const val BACKTRACK_FT = 150.0
        private const val BACKTRACK_PENALTY = 500.0

        private fun roundFeet(ft: Double): Int = max(50, (ft / 50).roundToInt() * 50)
        private fun lowerFirst(s: String) = if (s.isEmpty()) s else s[0].lowercaseChar() + s.substring(1)
    }
}
