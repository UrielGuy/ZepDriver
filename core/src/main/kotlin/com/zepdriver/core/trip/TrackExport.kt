package com.zepdriver.core.trip

import com.zepdriver.core.alerts.AlertLevel

/** One logged point of our own vehicle's track. */
data class TripPoint(
    val timeMillis: Long,
    val lat: Double,
    val lon: Double,
    val speedMph: Double,
    /** Most severe alert at this point. */
    val level: AlertLevel = AlertLevel.NONE,
    val note: String? = null,
)

/** Writes tracks for Google Earth / My Maps (KML) and other tools (GPX). */
object TrackExport {
    fun kml(name: String, points: List<TripPoint>): String = TODO("mesh agent")
    fun gpx(name: String, points: List<TripPoint>): String = TODO("mesh agent")
}
