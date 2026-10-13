package com.zepdriver.core.nav

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.data.Street
import com.zepdriver.core.data.StreetKind
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.bearingOf
import com.zepdriver.core.geo.signedTurn
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Turn-by-turn text for one leg's smoothed path. */
internal class InstructionBuilder(private val data: PlayaData, private val grid: CostGrid) {
    private val esp = data.layout.esplanade

    /** Streets a vehicle may drive along (no Esplanade, paths or promenades). */
    private val drivable: List<Street> = data.streets.filter { s ->
        s.kind != StreetKind.PATH && s.name != "ESP" &&
            !(s.kind == StreetKind.AVENUE && s.line.points.all { it.length() <= esp.radiusFt + esp.widthFt / 2 })
    }

    class Result(val path: List<Xy>, val instructions: List<Instruction>)

    private class Event(
        val along: Double,
        /** Existing vertex index, or -1 to insert a vertex at [along]. */
        val vertex: Int,
        val text: String,
        val speech: String,
        val turnDeg: Double,
    )

    fun build(pts: List<Xy>, dest: Destination, walkFt: Double?): Result {
        val n = pts.size
        val al = DoubleArray(n)
        for (k in 1 until n) al[k] = al[k - 1] + pts[k - 1].distanceTo(pts[k])
        val total = al[n - 1]
        val events = ArrayList<Event>()

        // Crossing and city-entry events, by sampling the path.
        class Crossing(val along: Double, val exitAlong: Double, val zone: Int)
        val crossings = ArrayList<Crossing>()
        val cityEntries = ArrayList<Double>()
        run {
            var prevCross = 0
            var prevCity = -1
            var openAt = 0.0
            var a = 0.0
            while (true) {
                val t = grid.tagAt(grid.cellAt(pointAt(pts, al, a)))
                val cross = t and CostGrid.TAG_CROSS_MASK
                val city = if (t and CostGrid.TAG_CITY != 0) 1 else 0
                if (cross != prevCross) {
                    if (prevCross != 0) crossings += Crossing(openAt, a, prevCross - 1)
                    if (cross != 0) openAt = a
                    prevCross = cross
                }
                if (prevCity == 0 && city == 1) cityEntries += a
                prevCity = city
                if (a >= total) break
                a = min(total, a + SAMPLE_FT)
            }
            // A path that starts inside a corridor (e.g. parked on it) doesn't "cross" it.
            if (prevCross != 0 && openAt > 0) crossings += Crossing(openAt, total, prevCross - 1)
        }
        crossings.removeAll { it.along == 0.0 }
        val used = BooleanArray(crossings.size)

        // Turns: cluster vertices that are close together into one maneuver.
        var k = 1
        while (k < n - 1) {
            var end = k
            var turn = turnAt(pts, k)
            while (end + 1 < n - 1 && al[end + 1] - al[end] < CLUSTER_FT) {
                end++
                turn += turnAt(pts, end)
            }
            if (abs(turn) >= MIN_TURN_DEG) {
                val a = al[k]
                val ci = crossings.indices.firstOrNull { !used[it] && crossings[it].along in (a - 20)..(a + 120) }
                val crossName = ci?.let { used[it] = true; crossName(crossings[it].zone) }
                val lookFrom = ci?.let { crossings[it].exitAlong } ?: al[end]
                val street = streetAhead(pts, al, lookFrom)
                // A bend or a jog around plaza art while staying on the same street isn't a maneuver.
                if (crossName == null && street != null && abs(turn) < SAME_STREET_MAX_TURN &&
                    al[k] >= LOOKAHEAD_FT && streetAt(pts, al, al[k] - LOOKAHEAD_FT) == street
                ) {
                    k = end + 1
                    continue
                }
                val verb = verb(turn)
                val text = buildString {
                    append(verb)
                    if (crossName != null) append(" and cross ").append(crossName)
                    if (street != null) append(" onto ").append(street.first)
                }
                val speech = buildString {
                    append(verb)
                    if (crossName != null) append(" and cross ").append(crossName)
                    if (street != null) append(" onto ").append(street.second)
                }
                events += Event(a, k, text, speech, turn)
            }
            k = end + 1
        }

        // Crossings not folded into a turn get their own instruction.
        crossings.forEachIndexed { i, c ->
            if (used[i]) return@forEachIndexed
            val name = crossName(c.zone)
            val street = streetAhead(pts, al, c.exitAlong)?.takeIf { cityAt(pts, al, c.exitAlong + 40) }
            val text = "Cross $name" + (street?.let { " onto ${it.first}" } ?: "")
            val speech = "Cross $name" + (street?.let { " onto ${it.second}" } ?: "")
            events += Event(c.along, -1, text, speech, 0.0)
        }

        // Entering the city, when nothing else announced it.
        for (a in cityEntries) {
            if (events.any { a - it.along in -30.0..200.0 }) continue
            val street = streetAhead(pts, al, a)
            events += Event(
                a, -1,
                "Enter the city" + (street?.let { " on ${it.first}" } ?: ""),
                "Enter the city" + (street?.let { " on ${it.second}" } ?: ""),
                0.0,
            )
        }

        // Arrival.
        val walk = walkFt?.let { roundWalk(it) }
        events += if (walk != null) {
            Event(total, n - 1, "Park here and walk ${"%,d".format(walk)} ft to ${dest.name}",
                "Park here and walk about $walk feet to ${dest.name}", 0.0)
        } else {
            Event(total, n - 1, "Arrive at ${dest.name}", "Arrive at ${dest.name}", 0.0)
        }

        events.sortBy { it.along }

        // Merge inserted vertices into the path.
        val out = ArrayList<Xy>(n + events.size)
        val instructions = ArrayList<Instruction>(events.size)
        var e = 0
        for (v in 0 until n) {
            // Inserted events strictly before vertex v.
            while (e < events.size && events[e].vertex < 0 && events[e].along < al[v] - 0.5) {
                val ev = events[e++]
                val p = pointAt(pts, al, ev.along)
                val idx = if (out.isNotEmpty() && out.last().distanceTo(p) < 0.5) out.size - 1 else { out += p; out.size - 1 }
                instructions += instr(idx, out[idx], ev)
            }
            out += pts[v]
            while (e < events.size && (events[e].vertex == v || (events[e].vertex < 0 && events[e].along <= al[v] + 0.5))) {
                instructions += instr(out.size - 1, pts[v], events[e++])
            }
        }
        while (e < events.size) instructions += instr(out.size - 1, out.last(), events[e++])
        return Result(out, instructions)
    }

    private fun instr(idx: Int, p: Xy, ev: Event) =
        Instruction(idx, data.latLon(p), ev.text, ev.speech, ev.turnDeg)

    private fun crossName(zone: Int): String = grid.crossZones.getOrNull(zone)?.spokenName ?: "the road"

    private fun cityAt(pts: List<Xy>, al: DoubleArray, a: Double): Boolean =
        grid.tagAt(grid.cellAt(pointAt(pts, al, min(a, al.last())))) and CostGrid.TAG_CITY != 0

    /** (display, spoken) name of the street the path runs along shortly after [from], if any. */
    private fun streetAhead(pts: List<Xy>, al: DoubleArray, from: Double): Pair<String, String>? {
        val total = al.last()
        if (total - from < 20) return null
        return streetAt(pts, al, min(total - 10, from + LOOKAHEAD_FT))
    }

    /** (display, spoken) name of the drivable street the path runs along at distance [a], if any. */
    private fun streetAt(pts: List<Xy>, al: DoubleArray, a: Double): Pair<String, String>? {
        val p = pointAt(pts, al, a)
        val seg = segmentAt(al, a)
        val heading = bearingOf(pts[seg + 1] - pts[seg])
        var best: Street? = null
        var bestD = Double.MAX_VALUE
        for (s in drivable) {
            val b = s.line.bounds
            val r = s.widthFt / 2 + STREET_SLACK_FT
            if (p.x < b.minX - r || p.x > b.maxX + r || p.y < b.minY - r || p.y > b.maxY + r) continue
            val hit = s.line.nearest(p)
            if (hit.distance > r) continue
            if (axisDiff(heading, hit.segmentBearing) > 30) continue
            if (hit.distance < bestD) { bestD = hit.distance; best = s }
        }
        return best?.let { streetNames(it) }
    }

    private fun streetNames(s: Street): Pair<String, String> = when {
        s.kind == StreetKind.ANNULAR -> {
            val ring = data.layout.ring(s.name)
            if (ring?.letter == "ESP") "Esplanade" to "the Esplanade" else s.name to s.name
        }
        else -> s.name to s.name
    }

    companion object {
        const val MIN_TURN_DEG = 25.0
        private const val CLUSTER_FT = 45.0
        private const val SAMPLE_FT = 5.0
        private const val LOOKAHEAD_FT = 60.0
        private const val STREET_SLACK_FT = 20.0
        private const val SAME_STREET_MAX_TURN = 60.0

        fun verb(turn: Double): String {
            val a = abs(turn)
            val side = if (turn > 0) "right" else "left"
            return when {
                a >= 160 -> "Make a U-turn"
                a >= 120 -> "Sharp $side"
                a >= 45 -> "Turn $side"
                else -> "Bear $side"
            }
        }

        fun roundWalk(ft: Double): Int =
            if (ft < 1000) ((ft / 10).roundToInt() * 10) else ((ft / 50).roundToInt() * 50)

        fun turnAt(pts: List<Xy>, k: Int): Double =
            signedTurn(bearingOf(pts[k] - pts[k - 1]), bearingOf(pts[k + 1] - pts[k]))

        fun segmentAt(al: DoubleArray, a: Double): Int {
            var s = 0
            while (s < al.size - 2 && al[s + 1] <= a) s++
            return s
        }

        fun pointAt(pts: List<Xy>, al: DoubleArray, a: Double): Xy {
            if (pts.size == 1) return pts[0]
            val s = segmentAt(al, a)
            val len = al[s + 1] - al[s]
            if (len <= 0) return pts[s]
            val t = ((a - al[s]) / len).coerceIn(0.0, 1.0)
            return pts[s] + (pts[s + 1] - pts[s]) * t
        }
    }
}
