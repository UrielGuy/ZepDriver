package com.zepdriver.ui.theme

/**
 * Day/night decision from ambient light with hysteresis and dwell: it goes
 * dark below [darkLux], bright above [brightLux], and a change must persist
 * for [dwellMillis] before it is applied (a passing headlight or a hand over
 * the sensor doesn't flip the theme).
 */
class LightClassifier(
    private val darkLux: Float = 30f,
    private val brightLux: Float = 150f,
    private val dwellMillis: Long = 4_000L,
) {
    /** Current decision, null until the first reading. */
    var night: Boolean? = null
        private set

    private var pending: Boolean? = null
    private var pendingSince = 0L

    /** Feeds the latest lux reading at [nowMillis]; returns the current decision. */
    fun feed(lux: Float, nowMillis: Long): Boolean {
        val current = night
        if (current == null) {
            // First reading decides immediately, using the midpoint.
            val first = lux < (darkLux + brightLux) / 2f
            night = first
            return first
        }
        val wanted = when {
            lux < darkLux -> true
            lux > brightLux -> false
            else -> current
        }
        if (wanted == current) {
            pending = null
            return current
        }
        if (pending != wanted) {
            pending = wanted
            pendingSince = nowMillis
        } else if (nowMillis - pendingSince >= dwellMillis) {
            night = wanted
            pending = null
            return wanted
        }
        return current
    }
}
