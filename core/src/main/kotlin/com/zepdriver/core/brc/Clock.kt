package com.zepdriver.core.brc

import com.zepdriver.core.data.Layout
import com.zepdriver.core.geo.Polygon
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.bearingOf
import com.zepdriver.core.geo.normalizeDeg

/**
 * Black Rock City clock geometry. A "clock" is minutes on a 12-hour dial
 * (0 = 12:00, 270 = 4:30); the dial is rotated so 12:00 points along
 * [Layout.twelveOClockBearingDeg].
 */
object Clock {
    /** Parses "4:30", "4.30", "430", "10", "10:00". Null if not a clock time. */
    fun parse(text: String): Int? {
        val t = text.trim()
        val m = Regex("""^(\d{1,2})(?:[:.]?(\d{2}))?$""").matchEntire(t) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h !in 1..12 || min !in 0..59) return null
        return (h % 12) * 60 + min
    }

    /** "4:30" for 270. */
    fun format(minutes: Int): String {
        val m = ((minutes % 720) + 720) % 720
        val h = m / 60
        return "${if (h == 0) 12 else h}:${(m % 60).toString().padStart(2, '0')}"
    }

    fun toBearing(layout: Layout, minutes: Double): Double =
        normalizeDeg(layout.twelveOClockBearingDeg + minutes * 0.5)

    /** Clock minutes (0 until 720) of the direction from the Man to [p]. */
    fun ofPoint(layout: Layout, p: Xy): Double =
        normalizeDeg(bearingOf(p) - layout.twelveOClockBearingDeg) * 2.0

    /** Point at [minutes] on the dial, [distanceFt] from the Man. */
    fun point(layout: Layout, minutes: Double, distanceFt: Double): Xy =
        Xy.fromBearing(toBearing(layout, minutes)) * distanceFt

    /** True if [minutes] lies on the clockwise arc from [from] to [to]. */
    fun inArc(minutes: Double, from: Double, to: Double): Boolean {
        val span = ((to - from) % 720 + 720) % 720
        val off = ((minutes - from) % 720 + 720) % 720
        return off <= span
    }

    /** Annular sector between two clock times (clockwise) and two radii. */
    fun sector(layout: Layout, fromMinutes: Double, toMinutes: Double, innerFt: Double, outerFt: Double, steps: Int = 64): Polygon {
        val span = ((toMinutes - fromMinutes) % 720 + 720) % 720
        val outer = (0..steps).map { point(layout, fromMinutes + span * it / steps, outerFt) }
        val inner = (0..steps).map { point(layout, fromMinutes + span * (steps - it) / steps, innerFt) }
        return Polygon(outer + inner)
    }
}
