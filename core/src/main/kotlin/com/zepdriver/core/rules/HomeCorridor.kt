package com.zepdriver.core.rules

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.data.StreetKind
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.Polyline
import com.zepdriver.core.geo.Xy

/**
 * The only city route a playa-only vehicle may drive: from its camp to the
 * nearest radial avenue, then straight along that avenue to the Esplanade
 * (the open-playa edge of the city).
 */
object HomeCorridor {
    /** Half-width of the allowed corridor, generous for GPS error and parking. */
    const val HALF_WIDTH_FT = 60.0

    fun build(data: PlayaData, home: LatLon): Polyline {
        val camp = data.xy(home)
        val esp = data.layout.esplanade
        val avenues = data.streets.filter { it.kind == StreetKind.AVENUE }
        val street = avenues.minByOrNull { it.line.distanceTo(camp) }
            ?: return Polyline(listOf(camp, camp.normalized() * (esp.radiusFt - esp.widthFt)))
        val onAvenue = street.line.nearest(camp).point
        // Follow the avenue's direction (radial) inward to just past the Esplanade.
        val dir = onAvenue.normalized()
        val playaEdge = dir * (esp.radiusFt - esp.widthFt)
        return Polyline(listOf(camp, onAvenue, playaEdge))
    }

    fun contains(corridor: Polyline, p: Xy): Boolean = corridor.distanceTo(p) <= HALF_WIDTH_FT
}
