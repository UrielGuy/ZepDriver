package com.zepdriver.core.embargo

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Release dates for each year's embargoed locations, coded like the DMV rules
 * and reviewed every year before the burn (SPEC.md §13).
 *
 * Terms of Service for Burning Man APIs and Datasets: developers get location
 * data three weeks before the event; it stays Burning Man's confidential
 * information until then. Theme camp locations may be shown to users from
 * 12:01 am on the Sunday of the week before the event; art locations only
 * once the Gate officially opens on the first day of the event.
 */
object DataRelease {
    /** Bundle files that hold embargoed locations; sealed in the APK. */
    val SEALED_FILES = listOf("camps.json", "art.json")

    private val BRC = ZoneId.of("America/Los_Angeles")

    private fun brc(iso: String) = LocalDateTime.parse(iso).atZone(BRC).toInstant().toEpochMilli()

    private val schedules: Map<Int, ReleaseSchedule> = listOf(
        // Gates opened Sunday Aug 30, 2026.
        ReleaseSchedule(2026, campsAtMillis = brc("2026-08-23T00:01"), artAtMillis = brc("2026-08-30T00:01")),
        // VERIFY in the annual review: event expected Sun Aug 29 – Mon Sep 6, 2027.
        ReleaseSchedule(2027, campsAtMillis = brc("2027-08-22T00:01"), artAtMillis = brc("2027-08-29T00:01")),
    ).associateBy { it.year }

    val years: Set<Int> get() = schedules.keys

    fun forYear(year: Int): ReleaseSchedule? = schedules[year]
}
