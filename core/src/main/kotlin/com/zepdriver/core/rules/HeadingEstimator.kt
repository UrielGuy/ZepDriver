package com.zepdriver.core.rules

import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.bearingOf

/**
 * Course over ground: the platform bearing when there is one, otherwise the
 * direction of recent travel. Null when the vehicle hasn't moved enough to tell.
 */
class HeadingEstimator(
    private val minMoveFt: Double = 8.0,
    private val windowMillis: Long = 6_000,
) {
    private val history = ArrayDeque<Pair<Long, Xy>>()

    var headingDeg: Double? = null
        private set

    fun update(timeMillis: Long, position: Xy, bearingDeg: Double?): Double? {
        history.addLast(timeMillis to position)
        while (history.first().first < timeMillis - windowMillis) history.removeFirst()
        headingDeg = bearingDeg ?: run {
            // Most recent earlier point far enough away to give a stable direction.
            for (i in history.size - 2 downTo 0) {
                val p = history[i].second
                if (p.distanceTo(position) >= minMoveFt) return@run bearingOf(position - p)
            }
            null
        }
        return headingDeg
    }

    fun reset() {
        history.clear()
        headingDeg = null
    }
}
