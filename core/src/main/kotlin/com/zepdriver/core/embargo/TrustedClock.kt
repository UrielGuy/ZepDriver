package com.zepdriver.core.embargo

/** Where a reading of real time came from. None of these follow the phone's own clock setting. */
enum class TimeSource {
    /** GNSS time from a real (not mock) GPS fix. */
    SATELLITE,
    /** The system's network time (NTP / carrier), independent of the user-set clock. */
    NETWORK,
    /** The Date header of an HTTPS response from our data server. */
    SERVER,
}

/**
 * One reading of real UTC time: [utcMillis] was the real time when the
 * monotonic clock (Android's elapsedRealtime, which nobody can set) read
 * [elapsedMillis].
 */
data class TimeSample(val source: TimeSource, val utcMillis: Long, val elapsedMillis: Long)

/**
 * Real time, worked out from trusted readings plus the monotonic clock rather
 * than the phone's clock, which anyone can set to next August.
 *
 * [floorMillis] is the latest real time ever confirmed, kept across restarts
 * and reboots: real time never goes backwards, so it is a safe lower bound
 * even with no fresh reading.
 *
 * Readings outside [plausible] are dropped: real time can't be before the app
 * was built, and a GNSS glitch years ahead must not unlock anything.
 */
class TrustedClock(
    floorMillis: Long? = null,
    private val plausible: LongRange = Long.MIN_VALUE..Long.MAX_VALUE,
) {
    private var latest: TimeSample? = null

    var floorMillis: Long? = floorMillis
        private set

    /** Takes a reading; returns false if it was implausible and dropped. */
    @Synchronized
    fun add(sample: TimeSample): Boolean {
        if (sample.utcMillis !in plausible) return false
        val l = latest
        if (l == null || sample.elapsedMillis >= l.elapsedMillis) latest = sample
        floorMillis = maxOf(floorMillis ?: Long.MIN_VALUE, sample.utcMillis)
        return true
    }

    /** Real time now, or null without a reading since boot. */
    @Synchronized
    fun now(elapsedNow: Long): Long? = latest?.let { it.utcMillis + (elapsedNow - it.elapsedMillis) }

    /** Where the latest reading came from, if any. */
    @Synchronized
    fun source(): TimeSource? = latest?.source

    /** A time that has certainly passed: [now] or the persisted floor, whichever is later. */
    @Synchronized
    fun atLeast(elapsedNow: Long): Long? {
        val n = now(elapsedNow)
        val f = floorMillis
        return when {
            n == null -> f
            f == null -> n
            else -> maxOf(n, f)
        }
    }
}
