package com.zepdriver.core

import com.zepdriver.core.DriveSim.at
import com.zepdriver.core.sun.SunTimes
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SunTimesTest {
    private val sun = SunTimes.forCity(TestData.playa2026)
    private val tz = ZoneId.of("America/Los_Angeles")

    private fun minutesOfDay(i: java.time.Instant) = i.atZone(tz).toLocalTime().toSecondOfDay() / 60

    @Test fun burnWeekSunriseSunset() {
        // Published times for Black Rock City on 2026-09-01: sunrise ≈ 6:27, sunset ≈ 19:31 PDT.
        val day = sun.day(LocalDate.of(2026, 9, 1))
        val rise = minutesOfDay(assertNotNull(day.sunrise))
        val set = minutesOfDay(assertNotNull(day.sunset))
        assertTrue(abs(rise - (6 * 60 + 27)) <= 8, "sunrise $rise")
        assertTrue(abs(set - (19 * 60 + 31)) <= 8, "sunset $set")
    }

    @Test fun winterSolstice() {
        // Reno-area 2026-12-21: sunrise ≈ 7:12, sunset ≈ 16:35 PST.
        val day = sun.day(LocalDate.of(2026, 12, 21))
        assertTrue(abs(minutesOfDay(day.sunrise!!) - (7 * 60 + 12)) <= 10)
        assertTrue(abs(minutesOfDay(day.sunset!!) - (16 * 60 + 35)) <= 10)
    }

    @Test fun isNight() {
        assertTrue(sun.isNight(at(1, 23)))
        assertTrue(sun.isNight(at(2, 4)))
        assertFalse(sun.isNight(at(1, 12)))
        assertFalse(sun.isNight(at(1, 8)))
        assertTrue(sun.isNight(at(1, 20)))
    }

    @Test fun polar() {
        val arctic = SunTimes(78.0, 15.0, ZoneId.of("UTC"))
        val summer = arctic.day(LocalDate.of(2026, 6, 21))
        assertTrue(summer.sunrise == null && summer.sunset == null)
        assertFalse(arctic.isNight(java.time.Instant.parse("2026-06-21T00:00:00Z").toEpochMilli()))
        assertTrue(arctic.isNight(java.time.Instant.parse("2026-12-21T12:00:00Z").toEpochMilli()))
    }
}
