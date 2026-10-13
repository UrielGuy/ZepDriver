package com.zepdriver.core

import com.zepdriver.core.brc.Clock
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.MPH_PER_MPS
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.bearingOf
import java.time.ZoneId
import java.time.ZonedDateTime

/** Builds synthetic 1 Hz fix streams over the real 2026 city. */
object DriveSim {
    val d = TestData.playa2026
    private val tz: ZoneId = ZoneId.of("America/Los_Angeles")

    /** Epoch millis for a local BRC time during the 2026 event. */
    fun at(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, tz).toInstant().toEpochMilli()

    /** Noon on Tuesday of burn week: daylight, no quiet hours, no burn perimeters. */
    val NOON: Long = at(1, 12)

    fun xy(clock: String, ft: Double): Xy = Clock.point(d.layout, Clock.parse(clock)!!.toDouble(), ft)
    fun xy(clockMinutes: Double, ft: Double): Xy = Clock.point(d.layout, clockMinutes, ft)

    fun fix(t: Long, p: Xy, mph: Double?, bearing: Double?, accuracyM: Double? = 4.0) =
        Fix(t, d.latLon(p), mph?.let { it / MPH_PER_MPS }, bearing, accuracyM)

    /** Straight line from [from] along [bearing] at [mph], one fix per second for [seconds]. */
    fun straight(from: Xy, bearing: Double, mph: Double, seconds: Int, t0: Long = NOON): List<Fix> {
        val dir = Xy.fromBearing(bearing)
        val ftPerSec = mph * 5280 / 3600
        return (0..seconds).map { s -> fix(t0 + s * 1000L, from + dir * (ftPerSec * s), mph, bearing) }
    }

    /** From [a] to [b] at [mph], one fix per second. */
    fun line(a: Xy, b: Xy, mph: Double, t0: Long = NOON): List<Fix> {
        val ftPerSec = mph * 5280 / 3600
        val seconds = (a.distanceTo(b) / ftPerSec).toInt()
        return straight(a, bearingOf(b - a), mph, seconds, t0)
    }

    /** Along a circle of [radiusFt] around the Man, clockwise from [startClock], at [mph]. */
    fun arc(startClock: String, radiusFt: Double, mph: Double, seconds: Int, t0: Long = NOON): List<Fix> {
        val ftPerSec = mph * 5280 / 3600
        val minutesPerFt = 720 / (2 * Math.PI * radiusFt)
        val c0 = Clock.parse(startClock)!!.toDouble()
        return (0..seconds).map { s ->
            val c = c0 + s * ftPerSec * minutesPerFt
            val p = xy(c, radiusFt)
            val bearing = Clock.toBearing(d.layout, c) + 90 // clockwise tangent
            fix(t0 + s * 1000L, p, mph, bearing)
        }
    }
}
