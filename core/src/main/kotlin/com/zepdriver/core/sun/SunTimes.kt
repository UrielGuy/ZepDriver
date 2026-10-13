package com.zepdriver.core.sun

import com.zepdriver.core.data.PlayaData
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Sunrise and sunset on one local date. Null fields mean the sun never rises / never sets. */
data class SunDay(val date: LocalDate, val sunrise: Instant?, val sunset: Instant?)

/**
 * Sunrise / sunset using NOAA's general solar position equations
 * (gml.noaa.gov/grad/solcalc/solareqns.PDF); accurate to about a minute at
 * mid latitudes, which is plenty for day / night license hours and themes.
 */
class SunTimes(val latDeg: Double, val lonDeg: Double, val zone: ZoneId) {
    private var cached: SunDay? = null

    fun day(date: LocalDate): SunDay {
        cached?.let { if (it.date == date) return it }
        return SunDay(date, event(date, rising = true), event(date, rising = false)).also { cached = it }
    }

    /** True between sunset and the next sunrise. */
    fun isNight(epochMillis: Long): Boolean {
        val t = Instant.ofEpochMilli(epochMillis)
        val d = day(t.atZone(zone).toLocalDate())
        if (d.sunrise == null || d.sunset == null) return d.sunrise == null && d.sunset == null && polarNight(d.date)
        return t < d.sunrise || t >= d.sunset
    }

    private fun polarNight(date: LocalDate): Boolean {
        val decl = declination(gamma(date, 12.0))
        return latDeg * decl < 0 // sun stays on the other hemisphere's side
    }

    /** UTC instant of sunrise / sunset on [date] (local), refined once at the event time. */
    private fun event(date: LocalDate, rising: Boolean): Instant? {
        // Start from local noon expressed in UTC hours, then refine with the first estimate.
        var utcHours = 12.0 - lonDeg / 15.0
        repeat(2) {
            val g = gamma(date, utcHours)
            val ha = hourAngleDeg(declination(g)) ?: return null
            val minutes = 720 - 4 * (lonDeg + if (rising) ha else -ha) - eqTime(g)
            utcHours = minutes / 60.0
        }
        val start = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val instant = start.plusMillis((utcHours * 3_600_000).toLong())
        // The UTC day may differ from the local day; shift so the event lands on the local date.
        val local = instant.atZone(zone).toLocalDate()
        return instant.plusSeconds(86_400L * (date.toEpochDay() - local.toEpochDay()))
    }

    private fun gamma(date: LocalDate, utcHours: Double): Double {
        val daysInYear = if (date.isLeapYear) 366 else 365
        return 2 * Math.PI / daysInYear * (date.dayOfYear - 1 + (utcHours - 12) / 24)
    }

    private fun eqTime(g: Double) = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) -
        0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))

    private fun declination(g: Double) = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) -
        0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)

    private fun hourAngleDeg(decl: Double): Double? {
        val lat = Math.toRadians(latDeg)
        val c = cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        if (c < -1 || c > 1) return null
        return Math.toDegrees(acos(c))
    }

    companion object {
        /** Sun times at the Man for this year's city. */
        fun forCity(data: PlayaData) =
            SunTimes(data.layout.man.lat, data.layout.man.lon, ZoneId.of(data.layout.timeZone))
    }
}
