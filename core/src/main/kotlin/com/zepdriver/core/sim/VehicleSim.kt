package com.zepdriver.core.sim

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.MPH_PER_MPS
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.normalizeDeg
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Feet per second for one mph. */
internal const val FPS_PER_MPH = 5280.0 / 3600.0

/**
 * A simple kinematic vehicle on the local playa plane (feet, see
 * [PlayaData.xy]), for testing the app away from Black Rock City.
 *
 * Speed moves toward [targetSpeedMph] at [accelMphPerSec]; heading turns at
 * [steerRateDegPerSec] (scaled down below 1 mph: cars don't turn in place).
 * A [driver] (e.g. [Autopilot]) may set both before every internal sub-step.
 *
 * [step] returns what a phone GPS would report: Doppler speed, a bearing only
 * while moving, ~3 m accuracy and, unless disabled, a little position, speed
 * and bearing noise from a seeded [Random] so runs are reproducible.
 */
class VehicleSim(
    private val data: PlayaData,
    start: Xy,
    headingDeg: Double,
    val accelMphPerSec: Double = 0.7,
    /** Max position noise per axis, feet (0 = none). */
    private val jitterFt: Double = 0.0,
    /** Max speed noise, mph (0 = none). */
    private val jitterMph: Double = 0.0,
    /** Max bearing noise, degrees (0 = none). */
    private val jitterDeg: Double = 0.0,
    seed: Long = 1L,
) {
    /** Sets the controls before each sub-step; [nowMillis] is the sub-step's end time. */
    fun interface Driver {
        fun control(sim: VehicleSim, nowMillis: Long)
    }

    private val random = Random(seed)

    /** True position (no GPS noise). */
    var position: Xy = start
        private set

    /** True heading, compass degrees. */
    var headingDeg: Double = normalizeDeg(headingDeg)
        private set

    /** True speed, mph. */
    var speedMph: Double = 0.0
        private set

    var targetSpeedMph: Double = 0.0
        set(value) { field = value.coerceIn(0.0, MAX_SPEED_MPH) }

    /** Yaw rate while moving, degrees per second; positive turns right (clockwise). */
    var steerRateDegPerSec: Double = 0.0
        set(value) { field = value.coerceIn(-MAX_STEER_DEG_PER_SEC, MAX_STEER_DEG_PER_SEC) }

    var driver: Driver? = null

    val latLon: LatLon get() = data.latLon(position)

    /** Moves the vehicle instantly (e.g. to a start point). */
    fun teleport(to: Xy, headingDeg: Double, speedMph: Double = 0.0) {
        position = to
        this.headingDeg = normalizeDeg(headingDeg)
        this.speedMph = speedMph.coerceIn(0.0, MAX_SPEED_MPH)
    }

    /** Advances [dtMillis] and returns the GPS fix at [nowMillis]. */
    fun step(dtMillis: Long, nowMillis: Long): Fix {
        var left = max(0L, dtMillis)
        while (left > 0) {
            val dt = min(left, SUBSTEP_MILLIS)
            left -= dt
            driver?.control(this, nowMillis - left)
            advance(dt / 1000.0)
        }
        if (dtMillis <= 0) driver?.control(this, nowMillis)
        return fix(nowMillis)
    }

    private fun advance(dtSec: Double) {
        val v0 = speedMph
        val dv = accelMphPerSec * dtSec
        val target = targetSpeedMph
        speedMph = if (target > v0) min(target, v0 + dv) else max(target, v0 - dv)
        val avgMph = (v0 + speedMph) / 2
        val turn = steerRateDegPerSec * dtSec * min(1.0, avgMph / FULL_STEER_MPH)
        // Midpoint integration: half the turn, move, the other half.
        headingDeg = normalizeDeg(headingDeg + turn / 2)
        position += Xy.fromBearing(headingDeg) * (avgMph * FPS_PER_MPH * dtSec)
        headingDeg = normalizeDeg(headingDeg + turn / 2)
    }

    /** What the phone's GPS reports for the current state. */
    fun fix(nowMillis: Long): Fix {
        val p = if (jitterFt > 0) position + Xy(noise(jitterFt), noise(jitterFt)) else position
        val moving = speedMph >= BEARING_MIN_MPH
        val mph = if (speedMph > 0 && jitterMph > 0) max(0.0, speedMph + noise(jitterMph)) else speedMph
        val bearing = if (moving) normalizeDeg(headingDeg + if (jitterDeg > 0) noise(jitterDeg) else 0.0) else null
        return Fix(
            timeMillis = nowMillis,
            position = data.latLon(p),
            speedMps = mph / MPH_PER_MPS,
            bearingDeg = bearing,
            accuracyM = ACCURACY_M,
        )
    }

    private fun noise(max: Double) = (random.nextDouble() * 2 - 1) * max

    companion object {
        const val MAX_SPEED_MPH = 10.0
        const val MAX_STEER_DEG_PER_SEC = 60.0
        /** Below this the platform reports no bearing. */
        const val BEARING_MIN_MPH = 0.3
        /** Steering reaches full effect at this speed. */
        const val FULL_STEER_MPH = 1.0
        const val ACCURACY_M = 3.0
        const val SUBSTEP_MILLIS = 100L

        /** A vehicle with phone-like GPS noise (±1.5 ft, ±0.05 mph, ±1°). */
        fun withGpsNoise(data: PlayaData, start: Xy, headingDeg: Double, seed: Long) =
            VehicleSim(data, start, headingDeg, jitterFt = 1.5, jitterMph = 0.05, jitterDeg = 1.0, seed = seed)
    }
}
