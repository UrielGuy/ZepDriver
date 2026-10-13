package com.zepdriver.core.nav

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.bearingOf

/** Straight-line direction and distance to a porta-potty bank. */
data class ToiletPointer(
    val destination: Destination,
    /** Distance to the nearest edge of the bank. */
    val distanceFt: Double,
    /** Compass bearing from the vehicle to the bank's center. */
    val bearingDeg: Double,
)

/**
 * Nearest porta-potty bank as the crow flies, for the always-on potty arrow.
 * (The Potties button routes by driving distance via [Router.nearestToilet].)
 */
class ToiletFinder(private val data: PlayaData) {
    private val destinations = data.toilets.mapIndexed { i, t ->
        Destination(Router.TOILET_NAME, data.latLon(t.center), DestinationKind.TOILET, refId = "toilet:$i")
    }

    fun nearest(from: LatLon): ToiletPointer? {
        val p = data.xy(from)
        var best = -1
        var bestD = Double.MAX_VALUE
        for ((i, t) in data.toilets.withIndex()) {
            // Cheap reject by bounding box before the exact edge distance.
            val b = t.polygon.bounds
            val dx = maxOf(b.minX - p.x, 0.0, p.x - b.maxX)
            val dy = maxOf(b.minY - p.y, 0.0, p.y - b.maxY)
            if (dx * dx + dy * dy >= bestD * bestD) continue
            val d = t.polygon.distanceTo(p)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best < 0) return null
        val center = data.toilets[best].center
        return ToiletPointer(destinations[best], bestD, bearingOf(center - p))
    }
}
