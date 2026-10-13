package com.zepdriver.core.rules

/**
 * PARKED / MOVING with hysteresis so stop-and-go traffic doesn't flap.
 * Starts PARKED; an active route forces MOVING.
 */
class DrivingModeDetector(
    private val parkBelowMph: Double = 0.5,
    private val parkAfterMillis: Long = 10_000,
    private val moveAboveMph: Double = 1.5,
    private val moveAfterMillis: Long = 2_000,
) {
    var mode: DrivingMode = DrivingMode.PARKED
        private set

    private var slowSince: Long? = null
    private var fastSince: Long? = null

    fun update(timeMillis: Long, speedMph: Double, navigating: Boolean): DrivingMode {
        slowSince = if (speedMph < parkBelowMph) slowSince ?: timeMillis else null
        fastSince = if (speedMph > moveAboveMph) fastSince ?: timeMillis else null
        mode = when {
            navigating -> DrivingMode.MOVING
            mode == DrivingMode.PARKED ->
                if (fastSince.let { it != null && timeMillis - it >= moveAfterMillis - SLACK }) DrivingMode.MOVING
                else DrivingMode.PARKED
            else ->
                if (slowSince.let { it != null && timeMillis - it >= parkAfterMillis - SLACK }) DrivingMode.PARKED
                else DrivingMode.MOVING
        }
        return mode
    }

    fun reset() {
        mode = DrivingMode.PARKED
        slowSince = null
        fastSince = null
    }

    private companion object { const val SLACK = 150L }
}
