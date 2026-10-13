package com.zepdriver.core.alerts

/**
 * Severity of an alert. The Android layer maps these to outputs:
 * - CAUTION: visual only (amber)
 * - ADVISORY: soft chime + banner, spoken once
 * - WARNING: tone + vibration + voice
 * - ALARM: loud repeating alarm, flashing screen, voice repeated
 */
enum class AlertLevel { NONE, CAUTION, ADVISORY, WARNING, ALARM }

enum class AlertKind {
    SPEED,
    /** Predicted to enter a no-entry zone soon. */
    NO_ENTRY_APPROACH,
    /** Inside a no-entry zone. */
    NO_ENTRY_INSIDE,
    /** Crossing a cross-only road (allowed; just a reminder). */
    CROSSING,
    /** Driving along a cross-only road (violation). */
    DRIVING_ALONG,
    /** Somewhere the license does not allow (e.g. playa-only car cruising the city). */
    LICENSE_AREA,
    /** Driving outside the license's valid hours (day/night). */
    LICENSE_HOURS,
    /** Amplified-sound restrictions apply here / now. */
    SOUND,
    /** Inside the art safe distance. */
    ART_BUFFER,
    /** In a drive-with-care area (plazas, burn perimeters). */
    CAUTION_AREA,
    /** Poor GPS accuracy; warnings may be unreliable. */
    GPS,
}

data class Alert(
    /** Stable identity used to de-duplicate speech across updates (e.g. "zone:great-circle"). */
    val key: String,
    val kind: AlertKind,
    val level: AlertLevel,
    /** Short banner text, e.g. "Esplanade — cross only". */
    val title: String,
    val detail: String? = null,
    /** What the voice says, or null for no speech. */
    val speech: String? = null,
)
