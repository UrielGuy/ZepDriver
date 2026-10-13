package com.zepdriver.core.profile

import com.zepdriver.core.geo.LatLon
import kotlinx.serialization.Serializable

/** The kind of DMV license the vehicle holds. Determines where it may drive. */
@Serializable
enum class LicenseClass(val label: String) {
    /** Standard Mutant Vehicle license: open playa and city streets. */
    MUTANT_VEHICLE("Mutant Vehicle (city + playa)"),

    /**
     * Playa-only Mutant Vehicle (includes large-vehicle / LCU restrictions):
     * city streets only on the shortest route between home camp and the open
     * playa, without turning.
     */
    MUTANT_VEHICLE_PLAYA_ONLY("Mutant Vehicle (playa only)"),

    /**
     * Accessibility Vehicle (formerly DPV): may drive city streets as
     * transportation for a participant with a mobility disability, who must be
     * aboard. Same pedestrian-area restrictions; not subject to MV sound policy.
     */
    ACCESSIBILITY_VEHICLE("Accessibility Vehicle"),

    /**
     * A bicycle (no DMV license). May ride the Esplanade, promenades, paths
     * and the Great Circle; still kept out of walk-in camping, the Center Camp
     * plaza and beyond the fence. Its own, higher speed thresholds.
     */
    BIKE("Bike"),
}

/** Speeds (mph) at which the speed warnings start. */
@Serializable
data class SpeedLimits(
    /** Amber speedometer, no sound. */
    val cautionMph: Double,
    /** Loud warning: siren + voice. */
    val warningMph: Double,
    /** Alarm: repeating siren, flashing screen. */
    val alarmMph: Double,
) {
    /** Clamped to [MIN_MPH]..[MAX_MPH] and kept in order caution ≤ warning ≤ alarm. */
    fun sanitized(): SpeedLimits {
        val c = cautionMph.coerceIn(MIN_MPH, MAX_MPH)
        val w = warningMph.coerceIn(c, MAX_MPH)
        val a = alarmMph.coerceIn(w, MAX_MPH)
        return SpeedLimits(c, w, a)
    }

    companion object {
        /** No threshold is ever above 10 mph. */
        const val MAX_MPH = 10.0
        const val MIN_MPH = 2.0

        fun defaultsFor(license: LicenseClass): SpeedLimits = when (license) {
            LicenseClass.BIKE -> SpeedLimits(8.0, 9.0, 10.0)
            else -> SpeedLimits(5.5, 6.0, 6.5)
        }
    }
}

/** Which hours the license is valid for. */
@Serializable
enum class LicenseHours(val label: String) {
    DAY("Day only"),
    NIGHT("Night only"),
    DAY_AND_NIGHT("Day and night"),
}

/** Per-car configuration. Edited only while parked. */
@Serializable
data class VehicleProfile(
    val carName: String = "",
    val licenseClass: LicenseClass = LicenseClass.MUTANT_VEHICLE,
    val licenseHours: LicenseHours = LicenseHours.DAY_AND_NIGHT,
    val hasSoundSystem: Boolean = true,
    val lengthFt: Double = 30.0,
    val widthFt: Double = 10.0,
    /** How far behind the front of the vehicle the phone is mounted. */
    val phoneFromFrontFt: Double = 5.0,
    /** Clearance to keep from art pieces when routing. */
    val artBufferFt: Double = DEFAULT_ART_BUFFER_FT,
    val homeCamp: LatLon? = null,
    val homeCampLabel: String? = null,
    /** Custom speed thresholds; null uses [SpeedLimits.defaultsFor] the license class. */
    val speedLimits: SpeedLimits? = null,
) {
    /** The thresholds in effect: custom ones if set, else this class's defaults. */
    val effectiveSpeedLimits: SpeedLimits
        get() = (speedLimits ?: SpeedLimits.defaultsFor(licenseClass)).sanitized()

    companion object {
        const val DEFAULT_ART_BUFFER_FT = 80.0
        /** Accessibility Vehicles may not exceed this size (DMV criteria). */
        const val AV_MAX_LENGTH_FT = 20.0
        const val AV_MAX_WIDTH_FT = 7.0
    }
}
