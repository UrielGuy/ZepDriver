package com.zepdriver.core.sim

import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.bearingOf
import com.zepdriver.core.geo.closestPointOnSegment
import com.zepdriver.core.geo.signedTurn
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Drives a [VehicleSim] along a polyline (local feet) with pure-pursuit
 * steering: aim at the point [lookaheadFt] further along the path and turn on
 * the arc that reaches it.
 *
 * With [stopAtEnd] it brakes so it comes to rest at the last point; otherwise
 * it reaches the end at speed (for chaining segments). Either way [finished]
 * becomes true within [arriveFt] of the end, after which it holds the wheel
 * straight and, with [stopAtEnd], the target speed at 0.
 */
class Autopilot(
    path: List<Xy>,
    /** Cruise speed; may be changed while driving. */
    var speedMph: Double,
    val stopAtEnd: Boolean = true,
    val lookaheadFt: Double = 40.0,
    val arriveFt: Double = 15.0,
) : VehicleSim.Driver {
    private val pts: List<Xy> = path.fold(ArrayList<Xy>()) { acc, p ->
        if (acc.isEmpty() || acc.last().distanceTo(p) > 0.01) acc += p
        acc
    }
    private val along = DoubleArray(pts.size).also { a ->
        for (i in 1 until pts.size) a[i] = a[i - 1] + pts[i - 1].distanceTo(pts[i])
    }

    init {
        require(pts.isNotEmpty()) { "empty path" }
    }

    val length: Double = along.last()

    /** Distance along the path to the vehicle's projection on it. */
    var progressFt: Double = 0.0
        private set

    var finished: Boolean = false
        private set

    private var located = false

    val remainingFt: Double get() = max(0.0, length - progressFt)

    /** 0..1 */
    val fraction: Double get() = if (length <= 0) 1.0 else (progressFt / length).coerceIn(0.0, 1.0)

    val start: Xy get() = pts.first()
    val end: Xy get() = pts.last()

    /** Compass bearing of the first path segment (for placing a vehicle at the start). */
    val startBearing: Double get() = if (pts.size < 2) 0.0 else bearingOf(pts[1] - pts[0])

    override fun control(sim: VehicleSim, nowMillis: Long) {
        val pos = sim.position
        if (!finished) {
            progressFt = locate(pos)
            val remaining = length - progressFt
            if (remaining <= arriveFt || remaining <= lookaheadFt && pos.distanceTo(end) <= arriveFt) finished = true
        }
        if (finished) {
            sim.steerRateDegPerSec = 0.0
            if (stopAtEnd) sim.targetSpeedMph = 0.0
            return
        }

        // Speed: cruise, braking to stop on the end point when asked.
        var target = speedMph
        if (stopAtEnd) {
            val brakeFps2 = sim.accelMphPerSec * FPS_PER_MPH
            val distance = max(0.0, remainingFt - STOP_SHORT_FT)
            target = min(target, sqrt(2 * brakeFps2 * distance) / FPS_PER_MPH)
        }

        // Pure pursuit.
        val goal = pointAt(progressFt + lookaheadFt)
        val toGoal = goal - pos
        val ld = toGoal.length()
        if (ld < 0.5) {
            sim.targetSpeedMph = target
            sim.steerRateDegPerSec = 0.0
            return
        }
        val alpha = Math.toRadians(signedTurn(sim.headingDeg, bearingOf(toGoal)))
        // Slow down for a sharp turn (or turning around), like a driver would.
        if (abs(alpha) > SHARP_TURN_RAD) target = min(target, SHARP_TURN_MPH)
        sim.targetSpeedMph = target
        // Beyond 90° off, turn as hard as the arc allows rather than easing off.
        val curvature = if (abs(alpha) > Math.PI / 2) 2.0 / ld * Math.signum(alpha) else 2.0 * sin(alpha) / ld
        val fps = max(sim.speedMph, VehicleSim.FULL_STEER_MPH) * FPS_PER_MPH
        sim.steerRateDegPerSec = Math.toDegrees(fps * curvature).coerceIn(-MAX_YAW_DEG_PER_SEC, MAX_YAW_DEG_PER_SEC)
    }

    /** The point [distance] along the path (clamped to its ends). */
    fun pointAt(distance: Double): Xy {
        if (pts.size == 1 || distance <= 0) return pts.first()
        if (distance >= length) return pts.last()
        var i = 1
        while (i < pts.lastIndex && along[i] < distance) i++
        val a = pts[i - 1]
        val b = pts[i]
        val seg = along[i] - along[i - 1]
        val t = if (seg <= 0) 0.0 else (distance - along[i - 1]) / seg
        return a + (b - a) * t
    }

    /**
     * Progress along the path for [p]. The first call searches the whole path;
     * later calls only look a little behind and ahead of the last progress, so a
     * path that doubles back (a U-turn) isn't skipped.
     */
    private fun locate(p: Xy): Double {
        if (pts.size == 1) return 0.0
        val lo = if (located) progressFt - BACK_WINDOW_FT else -1.0
        val hi = if (located) progressFt + AHEAD_WINDOW_FT else Double.MAX_VALUE
        var best = Double.MAX_VALUE
        var bestAlong = progressFt
        for (k in 0 until pts.lastIndex) {
            if (along[k + 1] < lo || along[k] > hi) continue
            val c = closestPointOnSegment(p, pts[k], pts[k + 1])
            val d = p.distanceTo(c)
            if (d < best) {
                best = d
                bestAlong = (along[k] + pts[k].distanceTo(c)).coerceIn(lo, hi)
            }
        }
        val result = if (located) max(progressFt, bestAlong) else bestAlong
        located = true
        return result.coerceIn(0.0, length)
    }

    companion object {
        const val MAX_YAW_DEG_PER_SEC = 45.0
        const val SHARP_TURN_MPH = 2.5
        private val SHARP_TURN_RAD = Math.toRadians(60.0)
        private const val BACK_WINDOW_FT = 20.0
        private const val AHEAD_WINDOW_FT = 120.0
        /** Aim to stop this far before the end so the stop lands on it. */
        private const val STOP_SHORT_FT = 2.0
    }
}
