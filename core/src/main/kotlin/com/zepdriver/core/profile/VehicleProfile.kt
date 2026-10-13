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
) {
    companion object {
        const val DEFAULT_ART_BUFFER_FT = 80.0
        /** Accessibility Vehicles may not exceed this size (DMV criteria). */
        const val AV_MAX_LENGTH_FT = 20.0
        const val AV_MAX_WIDTH_FT = 7.0
    }
}
