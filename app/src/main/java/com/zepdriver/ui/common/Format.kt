package com.zepdriver.ui.common

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Display formatting shared by the screens. Pure Kotlin so it can be unit tested. */
object Fmt {
    private const val FEET_PER_MILE = 5280.0

    /** Speedometer digits: one decimal under 10 mph, whole numbers above. */
    fun speed(mph: Double?): String {
        if (mph == null || mph.isNaN()) return "--"
        val v = mph.coerceAtLeast(0.0)
        return if (v < 9.95) String.format(Locale.US, "%.1f", v) else String.format(Locale.US, "%.0f", v)
    }

    /** "40 ft", "850 ft", "1.2 mi". Feet under 1,000 ft, miles with one decimal above. */
    fun distance(ft: Double?): String {
        if (ft == null || ft.isNaN()) return "--"
        val v = ft.coerceAtLeast(0.0)
        return when {
            v < 100 -> "${(v / 5).roundToInt() * 5} ft"
            v < 1000 -> "${(v / 10).roundToInt() * 10} ft"
            else -> String.format(Locale.US, "%.1f mi", v / FEET_PER_MILE)
        }
    }

    /** Whole feet with a thousands separator, e.g. "1,250 ft". */
    fun feet(ft: Double): String = String.format(Locale.US, "%,d ft", ft.roundToLong())

    /** "now", "3 min", "2 h", "1 d". */
    fun age(nowMillis: Long, thenMillis: Long): String {
        val s = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60} min"
            s < 86_400 -> "${s / 3600} h"
            else -> "${s / 86_400} d"
        }
    }

    /** Trip time: "<1 min", "12 min", "1 h 05 min". */
    fun duration(minutes: Double): String {
        val m = ceil(minutes.coerceAtLeast(0.0)).toLong()
        return when {
            minutes < 1.0 -> "<1 min"
            m < 60 -> "$m min"
            else -> String.format(Locale.US, "%d h %02d min", m / 60, m % 60)
        }
    }

    /** Local clock time [minutes] from [nowMillis], e.g. "9:42 PM". */
    fun clockIn(nowMillis: Long, minutes: Double): String =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Locale.US)
            .format(java.util.Date(nowMillis + (minutes * 60_000).roundToLong()))

    /** "3 min ago" / "just now". */
    fun ago(nowMillis: Long, thenMillis: Long): String {
        val a = age(nowMillis, thenMillis)
        return if (a == "now") "just now" else "$a ago"
    }

    /** Number for an editable text field: "30" rather than "30.0". */
    fun plainNumber(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e9) v.toLong().toString()
        else String.format(Locale.US, "%.1f", v)

    /** Parses a user-typed number ("30", "12.5", "12,5"); null when invalid. */
    fun parseNumber(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

    /** Short text for lists: first [max] characters on a word boundary. */
    fun snippet(text: String?, max: Int = 140): String? {
        val t = text?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (t.isEmpty()) return null
        if (t.length <= max) return t
        val cut = t.substring(0, max)
        val space = cut.lastIndexOf(' ')
        return (if (space > max / 2) cut.substring(0, space) else cut).trimEnd('.', ',', ';', ' ') + "…"
    }

    /** Label for a trail-window choice in hours. */
    fun hours(h: Int): String = when {
        h >= 24 * 7 -> "Whole week"
        h % 24 == 0 -> if (h == 24) "1 day" else "${h / 24} days"
        else -> "$h h"
    }
}
