package com.zepdriver.core.data

import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.LocalProjection
import com.zepdriver.core.geo.Polygon
import com.zepdriver.core.geo.Polyline
import com.zepdriver.core.geo.Xy
import kotlinx.serialization.Serializable

@Serializable
data class LatLonJson(val lat: Double, val lon: Double) {
    fun toLatLon() = LatLon(lat, lon)
}

@Serializable
data class RingLayout(val letter: String, val name: String, val radiusFt: Double, val widthFt: Double)

@Serializable
data class CenterCampLayout(val clock: String, val distanceFt: Double, val plazaRadiusFt: Double)

@Serializable
data class ClockRange(val fromClock: String, val toClock: String)

/** City-wide constants for one year (from `layout.json`). */
@Serializable
data class Layout(
    val year: Int,
    val man: LatLonJson,
    val temple: LatLonJson,
    val twelveOClockBearingDeg: Double,
    val rings: List<RingLayout>,
    val cityStartClock: String,
    val cityEndClock: String,
    val radialWidthFt: Double,
    val centerCamp: CenterCampLayout,
    val greatCircleRadiusFt: Double,
    val walkInCamping: ClockRange,
    val timeZone: String,
    val eventStart: String,
    val eventEnd: String,
    val manBurn: String,
    val templeBurn: String,
    val attribution: String,
) {
    val esplanade: RingLayout get() = rings.first { it.letter == "ESP" }
    val outerRing: RingLayout get() = rings.last()
    fun ring(letter: String): RingLayout? = rings.firstOrNull { it.letter.equals(letter, ignoreCase = true) }
}

enum class StreetKind { ANNULAR, AVENUE, PATH, CENTER_CAMP, OTHER }

/** A street centerline segment from the GIS `street_lines` layer. */
class Street(
    /** "ESP", "A".."K" for annular streets, "4:30" style for radials and paths. */
    val name: String,
    val kind: StreetKind,
    val widthFt: Double,
    val line: Polyline,
)

class NamedPolygon(val name: String, val polygon: Polygon)

class Toilet(
    /** GIS class: "in city", "playa", "man", "temple", "2/10", "outer". */
    val cls: String,
    val polygon: Polygon,
) {
    val center: Xy = polygon.centroid()
}

class Poi(val name: String, val xy: Xy, val position: LatLon)

@Serializable
data class ArtPiece(
    val id: String,
    val name: String,
    val artist: String? = null,
    val hometown: String? = null,
    val description: String? = null,
    val category: String? = null,
    val address: String? = null,
    val lat: Double,
    val lon: Double,
    val image: String? = null,
) {
    val position: LatLon get() = LatLon(lat, lon)
}

@Serializable
data class Camp(
    val id: String,
    val name: String,
    val hometown: String? = null,
    val description: String? = null,
    val landmark: String? = null,
    val address: String? = null,
    val frontage: String? = null,
    val intersection: String? = null,
    val lat: Double,
    val lon: Double,
) {
    val position: LatLon get() = LatLon(lat, lon)
}

/**
 * Everything the app knows about one year's city, in both WGS84 and local
 * feet (via [projection], origin = the Man).
 */
class PlayaData(
    val layout: Layout,
    val dataVersion: String,
    val streets: List<Street>,
    val blocks: List<Polygon>,
    val plazas: List<NamedPolygon>,
    val toilets: List<Toilet>,
    val fence: Polygon,
    val dmz: List<Polygon>,
    val pois: List<Poi>,
    val art: List<ArtPiece>,
    val camps: List<Camp>,
) {
    val projection = LocalProjection(layout.man.toLatLon())
    val manXy: Xy = Xy(0.0, 0.0)
    val templeXy: Xy by lazy { projection.toXy(layout.temple.toLatLon()) }
    val artXy: List<Xy> by lazy { art.map { projection.toXy(it.position) } }
    val campXy: List<Xy> by lazy { camps.map { projection.toXy(it.position) } }

    fun xy(p: LatLon) = projection.toXy(p)
    fun latLon(p: Xy) = projection.toLatLon(p)
}
