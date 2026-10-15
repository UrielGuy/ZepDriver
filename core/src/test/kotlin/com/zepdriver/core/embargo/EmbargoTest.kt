package com.zepdriver.core.embargo

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbargoTest {
    private fun t(iso: String) = Instant.parse(iso).toEpochMilli()

    private val schedule = ReleaseSchedule(
        year = 2027,
        campsAtMillis = t("2027-08-10T19:00:00Z"),
        artAtMillis = t("2027-08-29T07:00:00Z"),
    )

    @Test fun followsTheScheduleWithRealTime() {
        fun at(iso: String) = Embargo.evaluate(schedule, t(iso), t(iso), phoneNow = t(iso)).access
        assertEquals(DataAccess.PREVIOUS_YEAR, at("2027-06-01T00:00:00Z"))
        assertEquals(DataAccess.CAMPS, at("2027-08-10T19:00:00Z"))
        assertEquals(DataAccess.CAMPS, at("2027-08-20T00:00:00Z"))
        assertEquals(DataAccess.CAMPS_AND_ART, at("2027-08-29T07:00:00Z"))
    }

    @Test fun phoneClockAloneUnlocksNothing() {
        // Set to burn week, but no GPS fix or network time yet.
        val s = Embargo.evaluate(schedule, realNow = null, realAtLeast = null, phoneNow = t("2027-08-30T00:00:00Z"))
        assertEquals(DataAccess.PREVIOUS_YEAR, s.access)
        assertNull(s.naughty)
        assertTrue(s.awaitingConfirmation)
    }

    @Test fun clockSetForwardIsNaughty() {
        val real = t("2027-07-01T00:00:00Z")
        val s = Embargo.evaluate(schedule, real, real, phoneNow = t("2027-08-30T00:00:00Z"))
        assertEquals(DataAccess.NOTHING, s.access)
        val n = assertIs<Naughty.ClockAhead>(s.naughty)
        assertEquals(real, n.realMillis)
    }

    @Test fun clockSetForwardFromCampsToArtIsNaughty() {
        val real = t("2027-08-15T00:00:00Z")
        val s = Embargo.evaluate(schedule, real, real, phoneNow = t("2027-08-29T08:00:00Z"))
        assertEquals(DataAccess.NOTHING, s.access)
    }

    @Test fun aWrongClockThatUnlocksNothingIsFine() {
        // Hours off (manual clock, wrong time zone) but no release date in between.
        val real = t("2027-07-01T00:00:00Z")
        val s = Embargo.evaluate(schedule, real, real, phoneNow = real + 9 * 3_600_000L)
        assertEquals(DataAccess.PREVIOUS_YEAR, s.access)
        assertNull(s.naughty)
        // Behind is never cheating.
        val behind = Embargo.evaluate(schedule, real, real, phoneNow = real - 400L * 86_400_000L)
        assertNull(behind.naughty)
    }

    @Test fun aFewMinutesFastAtReleaseIsFine() {
        val real = schedule.campsAtMillis - 3 * 60_000L
        val s = Embargo.evaluate(schedule, real, real, phoneNow = real + 5 * 60_000L)
        assertNull(s.naughty)
        assertEquals(DataAccess.PREVIOUS_YEAR, s.access)
        assertTrue(s.awaitingConfirmation)
    }

    @Test fun confirmedFloorKeepsAccessOffline() {
        // Confirmed during the event, then restarted with no fix yet.
        val floor = t("2027-08-30T00:00:00Z")
        val s = Embargo.evaluate(schedule, realNow = null, realAtLeast = floor, phoneNow = floor + 3_600_000L)
        assertEquals(DataAccess.CAMPS_AND_ART, s.access)
        assertFalse(s.awaitingConfirmation)
    }

    @Test fun tamperingHidesEverything() {
        val real = t("2027-09-01T00:00:00Z")
        val s = Embargo.evaluate(schedule, real, real, real, tamper = Naughty.ModifiedApp)
        assertEquals(DataAccess.NOTHING, s.access)
        assertEquals(Naughty.ModifiedApp, s.naughty)
    }

    @Test fun noEmbargoedDataMeansPreviousYearOnly() {
        val s = Embargo.evaluate(null, null, null, t("2027-09-01T00:00:00Z"))
        assertEquals(DataAccess.PREVIOUS_YEAR, s.access)
        assertNull(s.naughty)
    }

    @Test fun artNeverBeforeCamps() {
        assertFailsWith<IllegalArgumentException> { ReleaseSchedule(2027, campsAtMillis = 10, artAtMillis = 5) }
    }

    @Test fun trustedClockRunsOnTheMonotonicClock() {
        val c = TrustedClock()
        assertNull(c.now(1_000))
        c.add(TimeSample(TimeSource.SATELLITE, utcMillis = 1_000_000, elapsedMillis = 5_000))
        assertEquals(1_010_000, c.now(15_000))
        assertEquals(TimeSource.SATELLITE, c.source())
        // A newer reading wins; an older one only raises the floor.
        c.add(TimeSample(TimeSource.NETWORK, utcMillis = 1_020_500, elapsedMillis = 25_000))
        c.add(TimeSample(TimeSource.SERVER, utcMillis = 1_002_000, elapsedMillis = 7_000))
        assertEquals(1_020_500, c.now(25_000))
        assertEquals(1_020_500, c.floorMillis)
    }

    @Test fun trustedClockDropsImplausibleReadings() {
        val c = TrustedClock(floorMillis = 500, plausible = 0L..2_000_000L)
        assertFalse(c.add(TimeSample(TimeSource.SATELLITE, utcMillis = 9_000_000, elapsedMillis = 1)))
        assertNull(c.now(10))
        assertEquals(500, c.atLeast(10))
        assertTrue(c.add(TimeSample(TimeSource.SATELLITE, utcMillis = 1_000, elapsedMillis = 1)))
        assertEquals(1_009, c.atLeast(10))
    }

    @Test fun releaseDatesAreCodedForEveryEmbargoedYear() {
        for (year in DataRelease.years) {
            val s = DataRelease.forYear(year)!!
            assertEquals(year, s.year)
            assertTrue(s.campsAtMillis <= s.artAtMillis)
        }
        assertNull(DataRelease.forYear(1999))
    }
}
