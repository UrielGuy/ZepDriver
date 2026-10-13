package com.zepdriver.core.sim

import kotlin.math.max

/**
 * The simulator's clock: starts at [startMillis] and runs [warp] times faster
 * than the wall clock, so a test drive doesn't take real playa time. The
 * simulated car moves, and its fixes are stamped, in this time, so dwell
 * times, hysteresis, arrival and rerouting behave as they would at ×1.
 * Strictly increasing. Not thread-safe.
 */
class SimClock(startMillis: Long, warp: Int = 1) {
    var warp: Int = clampWarp(warp)
        set(value) { field = clampWarp(value) }

    var nowMillis: Long = startMillis
        private set

    /** Advances by [wallDtMillis] of real time; returns the simulated time that passed (at least 1 ms). */
    fun advance(wallDtMillis: Long): Long {
        val dt = max(1L, max(0L, wallDtMillis) * warp)
        nowMillis += dt
        return dt
    }

    companion object {
        /** The choices offered in the app. */
        val WARPS = listOf(1, 2, 5, 10, 20)
        const val MAX_WARP = 20

        fun clampWarp(warp: Int) = warp.coerceIn(1, MAX_WARP)

        /** Wall time between simulator ticks: faster at high warp so motion stays smooth. */
        fun tickMillis(warp: Int): Long = when {
            warp >= 5 -> 250L
            warp >= 2 -> 500L
            else -> 1_000L
        }
    }
}
