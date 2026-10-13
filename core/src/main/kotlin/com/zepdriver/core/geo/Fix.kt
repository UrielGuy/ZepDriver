package com.zepdriver.core.geo

/** One GPS reading, as delivered by the platform location provider. */
data class Fix(
    val timeMillis: Long,
    val position: LatLon,
    /** Ground speed in meters/second (Doppler), or null if unknown. */
    val speedMps: Double?,
    /** Course over ground, compass degrees, or null if unknown / stationary. */
    val bearingDeg: Double?,
    /** Horizontal accuracy (68%) in meters, or null if unknown. */
    val accuracyM: Double?,
) {
    val speedMph: Double? get() = speedMps?.let { it * MPH_PER_MPS }
}
