package com.zepdriver.core.data

import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.LocalProjection
import com.zepdriver.core.geo.Polygon
import com.zepdriver.core.geo.Polyline
import com.zepdriver.core.geo.Xy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads a data bundle produced by `tools/data/build_playa_data.py`.
 *
 * [read] returns the text of a bundle file given its path relative to the
 * bundle root (e.g. "layout.json", "geo/streets.geojson"). On Android this
 * reads from assets or from a downloaded update directory.
 */
object PlayaDataLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(read: (String) -> String): PlayaData {
        val layout = json.decodeFromString(Layout.serializer(), read("layout.json"))
        val proj = LocalProjection(layout.man.toLatLon())
        val manifest = json.parseToJsonElement(read("manifest.json")).jsonObject
        val version = manifest["version"]?.jsonPrimitive?.contentOrNull ?: "unknown"

        val streets = features(read("geo/streets.geojson")).mapNotNull { f ->
            val props = f.props
            val name = props["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val kind = when (props["kind"]?.jsonPrimitive?.contentOrNull) {
                "annular" -> StreetKind.ANNULAR
                "avenue" -> StreetKind.AVENUE
                "path" -> StreetKind.PATH
                "center_camp" -> StreetKind.CENTER_CAMP
                else -> StreetKind.OTHER
            }
            val width = props["widthFt"]?.jsonPrimitive?.doubleOrNull ?: 30.0
            Street(name, kind, width, Polyline(lineCoords(f.coords, proj)))
        }
        val plazas = features(read("geo/plazas.geojson")).flatMap { f ->
            val name = f.props["name"]?.jsonPrimitive?.contentOrNull ?: "Plaza"
            polygons(f, proj).map { NamedPolygon(name, it) }
        }
        val toilets = features(read("geo/toilets.geojson")).flatMap { f ->
            val cls = f.props["class"]?.jsonPrimitive?.contentOrNull ?: "outer"
            polygons(f, proj).map { Toilet(cls, it) }
        }
        val pois = features(read("geo/poi.geojson")).map { f ->
            val c = f.coords.jsonArray
            val ll = LatLon(c[1].jsonPrimitive.double, c[0].jsonPrimitive.double)
            Poi(f.props["name"]?.jsonPrimitive?.contentOrNull ?: "", proj.toXy(ll), ll)
        }
        return PlayaData(
            layout = layout,
            dataVersion = version,
            streets = streets,
            blocks = features(read("geo/blocks.geojson")).flatMap { polygons(it, proj) },
            plazas = plazas,
            toilets = toilets,
            fence = features(read("geo/fence.geojson")).flatMap { polygons(it, proj) }.first(),
            dmz = features(read("geo/dmz.geojson")).flatMap { polygons(it, proj) },
            pois = pois,
            art = json.decodeFromString(read("art.json")),
            camps = json.decodeFromString(read("camps.json")),
        )
    }

    private class Feature(val props: JsonObject, val type: String, val coords: JsonElement)

    private fun features(text: String): List<Feature> =
        json.parseToJsonElement(text).jsonObject["features"]!!.jsonArray.map {
            val o = it.jsonObject
            val g = o["geometry"]!!.jsonObject
            Feature(
                o["properties"] as? JsonObject ?: JsonObject(emptyMap()),
                g["type"]!!.jsonPrimitive.content,
                g["coordinates"]!!,
            )
        }

    private fun lineCoords(coords: JsonElement, proj: LocalProjection): List<Xy> =
        coords.jsonArray.map { pt ->
            val a = pt.jsonArray
            proj.toXy(LatLon(a[1].jsonPrimitive.double, a[0].jsonPrimitive.double))
        }

    private fun polygon(rings: JsonArray, proj: LocalProjection): Polygon {
        val all = rings.map { lineCoords(it, proj).let { r -> if (r.size > 1 && r.first() == r.last()) r.dropLast(1) else r } }
        return Polygon(all.first(), all.drop(1))
    }

    private fun polygons(f: Feature, proj: LocalProjection): List<Polygon> = when (f.type) {
        "Polygon" -> listOf(polygon(f.coords.jsonArray, proj))
        "MultiPolygon" -> f.coords.jsonArray.map { polygon(it.jsonArray, proj) }
        else -> emptyList()
    }
}
