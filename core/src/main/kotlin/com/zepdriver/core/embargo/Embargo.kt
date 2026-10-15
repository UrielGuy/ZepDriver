package com.zepdriver.core.embargo

/**
 * When a year's camp and art locations may be shown, per the Burning Man API
 * terms. Coded per year (see [DataRelease]); never read from data files, so
 * editing a file on the phone can't move it.
 */
data class ReleaseSchedule(val year: Int, val campsAtMillis: Long, val artAtMillis: Long) {
    init {
        require(artAtMillis >= campsAtMillis) { "art can't be released before camps" }
    }

    fun accessAt(timeMillis: Long?): DataAccess = when {
        timeMillis == null -> DataAccess.PREVIOUS_YEAR
        timeMillis >= artAtMillis -> DataAccess.CAMPS_AND_ART
        timeMillis >= campsAtMillis -> DataAccess.CAMPS
        else -> DataAccess.PREVIOUS_YEAR
    }
}

/** What the app may show, from least to most. */
enum class DataAccess {
    /** Caught tampering: no camps or art at all, not even last year's. */
    NOTHING,
    /** The new year's locations are still embargoed: show last year's city. */
    PREVIOUS_YEAR,
    /** The new year's map and camps; no art yet. */
    CAMPS,
    /** Everything for the new year. */
    CAMPS_AND_ART,
}

/** Why we think someone is cheating. */
sealed interface Naughty {
    /** The phone's clock is set past a release date that real time hasn't reached. */
    data class ClockAhead(val phoneMillis: Long, val realMillis: Long) : Naughty

    /** The app isn't signed by us: someone rebuilt or patched it. */
    data object ModifiedApp : Naughty

    /** The embargoed data, or our saved unlock state, was changed. */
    data object ModifiedData : Naughty
}

data class EmbargoState(
    val access: DataAccess,
    val naughty: Naughty? = null,
    /**
     * The phone's clock says more is released than we could confirm yet (no
     * GPS fix or network time so far). Not cheating, just unconfirmed.
     */
    val awaitingConfirmation: Boolean = false,
    /** The schedule in force, or null if this build carries no embargoed data. */
    val schedule: ReleaseSchedule? = null,
)

object Embargo {
    /**
     * Decides what to show.
     *
     * @param schedule the embargoed year's release dates; null if the build has no embargoed data
     * @param realNow real time now from a trusted reading, or null if none since boot
     * @param realAtLeast a time known to have passed (realNow or the persisted floor)
     * @param phoneNow the phone's own clock
     * @param tamper a tampering signal found elsewhere (signature, data integrity)
     */
    fun evaluate(
        schedule: ReleaseSchedule?,
        realNow: Long?,
        realAtLeast: Long?,
        phoneNow: Long,
        tamper: Naughty? = null,
    ): EmbargoState {
        if (tamper != null) return EmbargoState(DataAccess.NOTHING, tamper, schedule = schedule)
        if (schedule == null) return EmbargoState(DataAccess.PREVIOUS_YEAR)
        val confirmed = schedule.accessAt(realAtLeast)
        // Only a clock that would unlock something counts: a phone a few hours off
        // (wrong time zone, manual clock) that crosses no release date is left
        // alone, and so is one a few minutes fast right at a release.
        if (realNow != null && schedule.accessAt(phoneNow - CLOCK_TOLERANCE_MS) > confirmed) {
            return EmbargoState(DataAccess.NOTHING, Naughty.ClockAhead(phoneNow, realNow), schedule = schedule)
        }
        return EmbargoState(
            confirmed,
            awaitingConfirmation = schedule.accessAt(phoneNow) > confirmed,
            schedule = schedule,
        )
    }

    const val CLOCK_TOLERANCE_MS = 10 * 60_000L
}
