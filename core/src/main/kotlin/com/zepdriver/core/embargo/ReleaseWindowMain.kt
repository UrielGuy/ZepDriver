package com.zepdriver.core.embargo

import java.time.Instant
import kotlin.system.exitProcess

/**
 * CI gate for the daily data build. Prints a description, then a last line of
 * "open" while the year's data can change (from [DAYS_BEFORE] days before the
 * gates open until [DAYS_AFTER] days after) or "closed" otherwise, including
 * when the year has no coded release dates.
 *
 * Usage: ReleaseWindowMain <year>
 */
fun main(args: Array<String>) {
    val year = args.firstOrNull()?.trim()?.toIntOrNull() ?: run {
        System.err.println("usage: ReleaseWindowMain <year>")
        exitProcess(2)
    }
    val now = System.currentTimeMillis()
    val window = releaseWindow(year)
    if (window == null) {
        println("No release dates coded for $year (annual review).")
        println("closed")
        return
    }
    println("$year data window: ${Instant.ofEpochMilli(window.first)} to ${Instant.ofEpochMilli(window.last)}")
    println(if (now in window) "open" else "closed")
}

private const val DAY_MS = 86_400_000L
private const val DAYS_BEFORE = 31
private const val DAYS_AFTER = 9

/** When [year]'s placements may change: a month before the gates open through the event. */
fun releaseWindow(year: Int): LongRange? = DataRelease.forYear(year)?.let {
    (it.artAtMillis - DAYS_BEFORE * DAY_MS)..(it.artAtMillis + DAYS_AFTER * DAY_MS)
}
