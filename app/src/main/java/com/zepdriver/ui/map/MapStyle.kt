package com.zepdriver.ui.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.cos
import kotlin.math.pow

/** Map colors for one theme, as CSS hex strings for the style JSON. */
data class MapPalette(
    val ground: String,
    val outsideFence: String,
    val fence: String,
    val block: String,
    val blockOpacity: Double,
    val plaza: String,
    val street: String,
    val streetLabel: String,
    val labelHalo: String,
    val toilet: String,
    val toiletLabel: String,
    val art: String,
    val artLabel: String,
    val camp: String,
    val campLabel: String,
    val poiLabel: String,
    val noEntry: String,
    val crossOnly: String,
    val license: String,
    val soft: String,
    val trail: String,
    val route: String,
    val routeLater: String,
    val routeCasing: String,
    val walk: String,
    val stop: String,
    val stopText: String,
    val fleet: String,
    val fleetLabel: String,
    val me: String,
    val meStroke: String,
    val focus: String,
)

object MapPalettes {
    /** High contrast for direct sunlight on a light tan playa. */
    val DAY = MapPalette(
        ground = "#EADBBE",
        outsideFence = "#D8C7A4",
        fence = "#6B5537",
        block = "#C7A97A",
        blockOpacity = 0.9,
        plaza = "#E2CDA6",
        street = "#FBF5E8",
        streetLabel = "#24190F",
        labelHalo = "#FBF5E8",
        toilet = "#1660C8",
        toiletLabel = "#0D3F86",
        art = "#B02A86",
        artLabel = "#5E0F47",
        camp = "#5B4330",
        campLabel = "#2E2117",
        poiLabel = "#3A2A1A",
        noEntry = "#D3221B",
        crossOnly = "#EE7A0C",
        license = "#7434B4",
        soft = "#8F7B55",
        trail = "#3F2E9C",
        route = "#0A6CFF",
        routeLater = "#5C93E6",
        routeCasing = "#FFFFFF",
        walk = "#0A6CFF",
        stop = "#0A4FC2",
        stopText = "#FFFFFF",
        fleet = "#008F5A",
        fleetLabel = "#004D30",
        me = "#111111",
        meStroke = "#FFFFFF",
        focus = "#D3221B",
    )

    /** Dark, dim and red/amber shifted to keep night vision. */
    val NIGHT = MapPalette(
        ground = "#120B07",
        outsideFence = "#080402",
        fence = "#6E3A22",
        block = "#24150C",
        blockOpacity = 0.85,
        plaza = "#1C110A",
        street = "#4E301C",
        streetLabel = "#C7784C",
        labelHalo = "#120B07",
        toilet = "#4C5F96",
        toiletLabel = "#8C9AD0",
        art = "#7E2C4E",
        artLabel = "#C9708F",
        camp = "#7A5236",
        campLabel = "#A97852",
        poiLabel = "#B0744C",
        noEntry = "#B3261E",
        crossOnly = "#B4600F",
        license = "#6E3E92",
        soft = "#5C4030",
        trail = "#8D5AA8",
        route = "#FF7A1A",
        routeLater = "#A85A20",
        routeCasing = "#2A1206",
        walk = "#FF9A4A",
        stop = "#E0661A",
        stopText = "#120B07",
        fleet = "#D2A02A",
        fleetLabel = "#D9AE52",
        me = "#FF4A26",
        meStroke = "#FFD2B8",
        focus = "#FF4A26",
    )
}

/**
 * Builds the complete offline MapLibre style (version 8) as JSON. No basemap
 * tiles: the playa is a plain background, bundle GeoJSON files are loaded by
 * URI and runtime layers come from [DYNAMIC_SOURCES], filled in with
 * GeoJsonSource.setGeoJson after the style loads.
 */
object MapStyle {
    const val FONT = "OpenSans-Regular"

    const val SRC_ZONES = "zones"
    const val SRC_ART = "art"
    const val SRC_ART_POINTS = "art-points"
    const val SRC_CAMPS = "camps"
    const val SRC_TOILET_POINTS = "toilet-points"
    const val SRC_TRAIL = "trail"
    const val SRC_ROUTE = "route"
    const val SRC_STOPS = "stops"
    const val SRC_FLEET = "fleet"
    const val SRC_ME = "me"
    const val SRC_FOCUS = "focus"

    val DYNAMIC_SOURCES = listOf(
        SRC_ZONES, SRC_ART, SRC_ART_POINTS, SRC_CAMPS, SRC_TOILET_POINTS,
        SRC_TRAIL, SRC_ROUTE, SRC_STOPS, SRC_FLEET, SRC_ME, SRC_FOCUS,
    )

    /**
     * @param uriPrefix bundle root, e.g. "asset://playa/2026/"
     * @param latitude used to turn widths in feet into pixels per zoom level
     */
    fun build(uriPrefix: String, palette: MapPalette, latitude: Double): String {
        val p = palette
        val sources = linkedMapOf<String, Any?>()
        for (f in listOf("blocks", "streets", "plazas", "toilets", "fence", "poi")) {
            sources[f] = mapOf("type" to "geojson", "data" to "${uriPrefix}geo/$f.geojson")
        }
        for (id in DYNAMIC_SOURCES) {
            sources[id] = mapOf(
                "type" to "geojson",
                "data" to mapOf("type" to "FeatureCollection", "features" to emptyList<Any>()),
            )
        }

        fun eq(prop: String, value: Any) = listOf("==", listOf("get", prop), value)
        fun all(vararg c: Any) = listOf("all") + c.toList()
        fun groundWidth(prop: String, minPx: Double = 1.0) = feetToPixels(listOf("get", prop), latitude, minPx)
        fun font() = listOf(FONT)

        val layers = mutableListOf<Map<String, Any?>>()
        fun layer(
            id: String,
            type: String,
            source: String? = null,
            filter: Any? = null,
            minzoom: Double? = null,
            layout: Map<String, Any?> = emptyMap(),
            paint: Map<String, Any?> = emptyMap(),
        ) {
            val m = linkedMapOf<String, Any?>("id" to id, "type" to type)
            if (source != null) m["source"] = source
            if (filter != null) m["filter"] = filter
            if (minzoom != null) m["minzoom"] = minzoom
            if (layout.isNotEmpty()) m["layout"] = layout
            if (paint.isNotEmpty()) m["paint"] = paint
            layers += m
        }

        // --- Ground -------------------------------------------------------------
        layer("background", "background", paint = mapOf("background-color" to p.outsideFence))
        layer("inside-fence", "fill", "fence", paint = mapOf("fill-color" to p.ground))
        layer("fence-line", "line", "fence", paint = mapOf(
            "line-color" to p.fence, "line-width" to 2.0, "line-dasharray" to listOf(3.0, 2.0),
        ))
        layer("plazas", "fill", "plazas", paint = mapOf("fill-color" to p.plaza, "fill-opacity" to 0.8))

        // Zone areas: no-entry red, soft zones subtle, license zones outline only.
        layer("zone-no-entry", "fill", SRC_ZONES, all(eq("g", "area"), eq("kind", "NO_ENTRY")), paint = mapOf(
            "fill-color" to p.noEntry, "fill-opacity" to 0.22,
        ))
        layer("zone-soft", "fill", SRC_ZONES,
            all(eq("g", "area"), listOf("match", listOf("get", "kind"), listOf("SOUND_OFF", "CAUTION_AREA"), true, false)),
            paint = mapOf("fill-color" to p.soft, "fill-opacity" to 0.12),
        )

        // --- Streets --------------------------------------------------------------
        layer("streets", "line", "streets",
            layout = mapOf("line-cap" to "round", "line-join" to "round"),
            paint = mapOf("line-color" to p.street, "line-width" to groundWidth("widthFt")),
        )
        layer("zone-cross-only", "line", SRC_ZONES, all(eq("g", "line"), eq("kind", "CROSS_ONLY")),
            layout = mapOf("line-join" to "round"),
            paint = mapOf(
                "line-color" to p.crossOnly, "line-opacity" to 0.55,
                "line-width" to groundWidth("widthFt", 2.0),
                "line-dasharray" to listOf(1.5, 0.75),
            ),
        )
        layer("zone-no-entry-line", "line", SRC_ZONES, all(eq("g", "area"), eq("kind", "NO_ENTRY")), paint = mapOf(
            "line-color" to p.noEntry, "line-width" to 2.0, "line-opacity" to 0.9,
        ))
        layer("zone-license-line", "line", SRC_ZONES, all(eq("g", "area"), eq("kind", "LICENSE_RESTRICTED")), paint = mapOf(
            "line-color" to p.license, "line-width" to 3.0, "line-dasharray" to listOf(2.0, 1.0),
        ))
        // --- 3D -----------------------------------------------------------------------
        layer("blocks", "fill-extrusion", "blocks", paint = mapOf(
            "fill-extrusion-color" to p.block,
            "fill-extrusion-height" to 3.0,
            "fill-extrusion-base" to 0.0,
            "fill-extrusion-opacity" to p.blockOpacity,
        ))
        layer("art", "fill-extrusion", SRC_ART, minzoom = 13.0, paint = mapOf(
            "fill-extrusion-color" to p.art,
            "fill-extrusion-height" to 6.0,
            "fill-extrusion-base" to 0.0,
            "fill-extrusion-opacity" to 0.95,
        ))

        // Potties above the blocks so they're never hidden, with a dot that stays
        // visible when zoomed out.
        layer("toilets", "fill", "toilets", paint = mapOf(
            "fill-color" to p.toilet, "fill-opacity" to 0.85, "fill-outline-color" to p.toilet,
        ))
        layer("toilet-dots", "circle", SRC_TOILET_POINTS, paint = mapOf(
            "circle-color" to p.toilet,
            "circle-radius" to listOf("interpolate", listOf("linear"), listOf("zoom"), 12, 2.5, 16, 5, 19, 8),
            "circle-stroke-color" to p.labelHalo,
            "circle-stroke-width" to 1.0,
        ))

        // --- Own trail and route ------------------------------------------------------
        layer("trail", "line", SRC_TRAIL,
            layout = mapOf("line-cap" to "round", "line-join" to "round"),
            paint = mapOf("line-color" to p.trail, "line-width" to 2.0, "line-opacity" to 0.75),
        )
        layer("route-casing", "line", SRC_ROUTE, eq("g", "drive"),
            layout = mapOf("line-cap" to "round", "line-join" to "round"),
            paint = mapOf("line-color" to p.routeCasing, "line-width" to 12.0, "line-opacity" to 0.9),
        )
        layer("route-later", "line", SRC_ROUTE, all(eq("g", "drive"), eq("current", false)),
            layout = mapOf("line-cap" to "round", "line-join" to "round"),
            paint = mapOf("line-color" to p.routeLater, "line-width" to 7.0),
        )
        layer("route", "line", SRC_ROUTE, all(eq("g", "drive"), eq("current", true)),
            layout = mapOf("line-cap" to "round", "line-join" to "round"),
            paint = mapOf("line-color" to p.route, "line-width" to 8.0),
        )
        layer("route-walk", "line", SRC_ROUTE, eq("g", "walk"),
            layout = mapOf("line-cap" to "round"),
            paint = mapOf("line-color" to p.walk, "line-width" to 3.0, "line-dasharray" to listOf(1.0, 1.5)),
        )

        // --- Labels -----------------------------------------------------------------
        fun halo() = mapOf("text-halo-color" to p.labelHalo, "text-halo-width" to 1.5)

        layer("street-labels", "symbol", "streets",
            filter = listOf("match", listOf("get", "kind"), listOf("annular", "avenue"), true, false),
            minzoom = 15.0,
            layout = mapOf(
                "symbol-placement" to "line",
                "text-field" to listOf("match", listOf("get", "name"), "ESP", "Esplanade", listOf("get", "name")),
                "text-font" to font(),
                "text-size" to listOf("interpolate", listOf("linear"), listOf("zoom"), 15, 11, 18, 15),
                "symbol-spacing" to 300,
                "text-max-angle" to 30,
            ),
            paint = mapOf("text-color" to p.streetLabel) + halo(),
        )
        layer("camps", "circle", SRC_CAMPS, minzoom = 15.0, paint = mapOf(
            "circle-color" to p.camp,
            "circle-radius" to listOf("interpolate", listOf("linear"), listOf("zoom"), 15, 2, 18, 5),
            "circle-opacity" to 0.85,
        ))
        layer("camp-labels", "symbol", SRC_CAMPS, minzoom = 16.0,
            layout = mapOf(
                "text-field" to listOf("get", "name"),
                "text-font" to font(),
                "text-size" to 11,
                "text-anchor" to "top",
                "text-offset" to listOf(0.0, 0.5),
                "text-max-width" to 8,
                "text-optional" to true,
            ),
            paint = mapOf("text-color" to p.campLabel) + halo(),
        )
        layer("poi-labels", "symbol", "poi", minzoom = 15.5,
            layout = mapOf(
                "text-field" to listOf("get", "name"),
                "text-font" to font(),
                "text-size" to 11,
                "text-max-width" to 8,
            ),
            paint = mapOf("text-color" to p.poiLabel) + halo(),
        )
        layer("toilet-labels", "symbol", SRC_TOILET_POINTS, minzoom = 15.0,
            layout = mapOf(
                "text-field" to "Potties",
                "text-font" to font(),
                "text-size" to 12,
                "text-allow-overlap" to false,
            ),
            paint = mapOf("text-color" to p.toiletLabel) + halo(),
        )
        layer("art-labels", "symbol", SRC_ART_POINTS, minzoom = 15.0,
            layout = mapOf(
                "text-field" to listOf("get", "name"),
                "text-font" to font(),
                "text-size" to 12,
                "text-anchor" to "top",
                "text-offset" to listOf(0.0, 0.9),
                "text-max-width" to 9,
                "text-optional" to true,
            ),
            paint = mapOf("text-color" to p.artLabel) + halo(),
        )

        // --- Other vehicles, stops, focus pin, own vehicle (top) ---------------------
        val stale = eq("stale", true)
        layer("fleet", "circle", SRC_FLEET, paint = mapOf(
            "circle-color" to p.fleet,
            "circle-radius" to 8.0,
            "circle-stroke-color" to p.labelHalo,
            "circle-stroke-width" to 2.0,
            "circle-opacity" to listOf("case", stale, 0.35, 1.0),
            "circle-stroke-opacity" to listOf("case", stale, 0.35, 1.0),
        ))
        layer("fleet-labels", "symbol", SRC_FLEET,
            layout = mapOf(
                "text-field" to listOf("get", "label"),
                "text-font" to font(),
                "text-size" to 13,
                "text-anchor" to "left",
                "text-offset" to listOf(1.0, 0.0),
                "text-allow-overlap" to true,
            ),
            paint = mapOf(
                "text-color" to p.fleetLabel,
                "text-opacity" to listOf("case", stale, 0.45, 1.0),
            ) + halo(),
        )
        layer("stops", "circle", SRC_STOPS, paint = mapOf(
            "circle-color" to p.stop,
            "circle-radius" to 12.0,
            "circle-stroke-color" to p.stopText,
            "circle-stroke-width" to 2.0,
            "circle-opacity" to listOf("case", eq("draft", true), 0.6, 1.0),
        ))
        layer("stop-numbers", "symbol", SRC_STOPS,
            layout = mapOf(
                "text-field" to listOf("to-string", listOf("get", "n")),
                "text-font" to font(),
                "text-size" to 14,
                "text-allow-overlap" to true,
                "text-ignore-placement" to true,
            ),
            paint = mapOf("text-color" to p.stopText),
        )
        layer("focus", "circle", SRC_FOCUS, paint = mapOf(
            "circle-color" to "rgba(0,0,0,0)",
            "circle-radius" to 16.0,
            "circle-stroke-color" to p.focus,
            "circle-stroke-width" to 4.0,
        ))
        layer("me-dot", "circle", SRC_ME, eq("g", "pt"), paint = mapOf(
            "circle-color" to p.me,
            "circle-radius" to 9.0,
            "circle-stroke-color" to p.meStroke,
            "circle-stroke-width" to 3.0,
        ))
        layer("me-arrow", "fill", SRC_ME, eq("g", "arrow"), paint = mapOf(
            "fill-color" to p.me,
            "fill-outline-color" to p.meStroke,
        ))

        val style = linkedMapOf<String, Any?>(
            "version" to 8,
            "name" to "ZepDriver",
            "glyphs" to "asset://glyphs/{fontstack}/{range}.pbf",
            "sources" to sources,
            "layers" to layers,
        )
        return toJson(style).toString()
    }

    /**
     * Expression for a line width that matches a ground width in feet at every
     * zoom (MapLibre uses 512 px tiles: m/px = 40075016.686·cos(lat) / (512·2^z)).
     */
    internal fun feetToPixels(feetExpr: Any, latitude: Double, minPx: Double): List<Any> {
        val metersPerPxAtZ0 = 40_075_016.686 * cos(Math.toRadians(latitude)) / 512.0
        fun k(z: Int) = 0.3048 * 2.0.pow(z) / metersPerPxAtZ0
        return listOf(
            "interpolate", listOf("exponential", 2), listOf("zoom"),
            10, listOf("max", minPx, listOf("*", feetExpr, k(10))),
            22, listOf("max", minPx, listOf("*", feetExpr, k(22))),
        )
    }

    /** Converts nested Kotlin maps/lists/primitives to JSON. */
    internal fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, value) -> k.toString() to toJson(value) })
        is Iterable<*> -> JsonArray(v.map { toJson(it) })
        is Array<*> -> JsonArray(v.map { toJson(it) })
        else -> JsonPrimitive(v.toString())
    }
}
