package com.zepdriver.ui.map

import com.zepdriver.Favorite
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.FEET_PER_METER
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.landmarks.Landmarks
import com.zepdriver.core.mesh.FleetVehicle
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.nav.NavState
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.Zone
import com.zepdriver.core.rules.ZoneShape
import com.zepdriver.core.trip.TripPoint
import com.zepdriver.core.vehicles.VehicleModel
import com.zepdriver.core.vehicles.VehicleModels
import com.zepdriver.ui.common.Fmt
import kotlinx.serialization.json.JsonPrimitive

/**
 * GeoJSON text for the map's runtime sources. Pure Kotlin (no Android), so it
 * can be built off the main thread and unit tested.
 */
object GeoJson {
    const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"

    /** Positions older than this are drawn faded. */
    const val FLEET_STALE_MILLIS = 10 * 60_000L

    // `kind` of tappable features (see MapStyle.TAP_LAYERS); ids go in `id`.
    const val KIND_ME = "me"
    const val KIND_FLEET = "fleet"
    const val KIND_STOP = "stop"
    const val KIND_HOME = "home"
    const val KIND_FAVORITE = "favorite"
    const val KIND_LANDMARK = "landmark"
    const val KIND_ART = "art"
    const val KIND_TOILET = "toilet"
    const val KIND_CAMP = "camp"
    const val KIND_POI = "poi"

    /** Streams features into a FeatureCollection string. */
    class Builder {
        private val sb = StringBuilder(256).append("{\"type\":\"FeatureCollection\",\"features\":[")
        private var count = 0

        val isEmpty: Boolean get() = count == 0

        fun point(p: LatLon, props: Map<String, Any?> = emptyMap()): Builder {
            begin(props, "Point")
            coord(p)
            end()
            return this
        }

        fun line(points: List<LatLon>, props: Map<String, Any?> = emptyMap()): Builder {
            if (points.size < 2) return this
            begin(props, "LineString")
            coords(points)
            end()
            return this
        }

        /** Polygon from an outer ring and holes; rings are closed automatically. */
        fun polygon(rings: List<List<LatLon>>, props: Map<String, Any?> = emptyMap()): Builder {
            val valid = rings.filter { it.size >= 3 }
            if (valid.isEmpty() || valid.first() !== rings.first()) return this
            begin(props, "Polygon")
            sb.append('[')
            valid.forEachIndexed { i, ring ->
                if (i > 0) sb.append(',')
                val closed = if (ring.first() == ring.last()) ring else ring + ring.first()
                coords(closed)
            }
            sb.append(']')
            end()
            return this
        }

        fun build(): String = sb.toString() + "]}"

        private fun begin(props: Map<String, Any?>, type: String) {
            if (count++ > 0) sb.append(',')
            sb.append("{\"type\":\"Feature\",\"properties\":{")
            var first = true
            for ((k, v) in props) {
                if (!first) sb.append(',')
                first = false
                sb.append(JsonPrimitive(k).toString()).append(':')
                appendValue(v)
            }
            sb.append("},\"geometry\":{\"type\":\"").append(type).append("\",\"coordinates\":")
        }

        private fun end() {
            sb.append("}}")
        }

        private fun appendValue(v: Any?) {
            when (v) {
                null -> sb.append("null")
                is Boolean -> sb.append(if (v) "true" else "false")
                is Int, is Long, is Short, is Byte -> sb.append(v.toString())
                is Number -> {
                    val d = v.toDouble()
                    if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(num(d))
                }
                else -> sb.append(JsonPrimitive(v.toString()).toString())
            }
        }

        private fun coord(p: LatLon) {
            sb.append('[').append(num(p.lon, 1e7)).append(',').append(num(p.lat, 1e7)).append(']')
        }

        private fun coords(points: List<LatLon>) {
            sb.append('[')
            points.forEachIndexed { i, p ->
                if (i > 0) sb.append(',')
                coord(p)
            }
            sb.append(']')
        }

        private fun num(d: Double, scale: Double = 1e4): String {
            val r = Math.round(d * scale) / scale
            // Plain decimal notation; Double.toString is fine for these magnitudes.
            return if (r == Math.floor(r) && kotlin.math.abs(r) < 1e15) r.toLong().toString() else r.toString()
        }
    }

    private fun PlayaData.ll(p: Xy): LatLon = latLon(p)

    /**
     * Rule zones that apply to this vehicle right now. Areas get `g = "area"`,
     * road corridors `g = "line"` with their full width in `widthFt`.
     * "Outside" shapes (beyond the trash fence) are drawn by the fence layer.
     */
    fun zones(data: PlayaData, zones: List<Zone>, profile: VehicleProfile, nowMillis: Long): String {
        val b = Builder()
        for (z in zones) {
            val visible = runCatching { z.appliesTo(profile) && z.activeAt(nowMillis) }.getOrDefault(false)
            if (!visible) continue
            for (shape in z.shapes) {
                when (shape) {
                    is ZoneShape.Area -> b.polygon(
                        listOf(shape.polygon.outer.map { data.ll(it) }) +
                            shape.polygon.holes.map { h -> h.map { data.ll(it) } },
                        mapOf("g" to "area", "kind" to z.kind.name, "name" to z.name),
                    )
                    is ZoneShape.Corridor -> b.line(
                        shape.line.points.map { data.ll(it) },
                        mapOf("g" to "line", "kind" to z.kind.name, "name" to z.name, "widthFt" to shape.halfWidthFt * 2),
                    )
                    is ZoneShape.Outside -> Unit
                }
            }
        }
        return b.build()
    }

    /** The 3D landmark models; heights in meters for fill-extrusion. */
    fun landmarks(data: PlayaData): String {
        val b = Builder()
        for (p in Landmarks.build(data)) {
            b.polygon(
                listOf(p.outer.map { data.ll(it) }) + p.holes.map { h -> h.map { data.ll(it) } },
                mapOf(
                    "kind" to KIND_LANDMARK,
                    "id" to p.landmark,
                    "part" to p.part.name,
                    "base" to p.baseFt / FEET_PER_METER,
                    "top" to p.topFt / FEET_PER_METER,
                ),
            )
        }
        return b.build()
    }

    /**
     * Home (from the car profile, or a favorite saved as Home) plus the other
     * favorites, as points with `kind` "home" or "favorite".
     */
    fun places(home: LatLon?, favorites: List<Favorite>): String {
        val b = Builder()
        val homeFavorite = favorites.firstOrNull { it.label == HOME_LABEL }
        val homeAt = home ?: homeFavorite?.destination?.position
        if (homeAt != null) b.point(homeAt, mapOf("kind" to KIND_HOME, "name" to HOME_LABEL))
        for (f in favorites) {
            if (f === homeFavorite) continue
            b.point(f.destination.position, mapOf("kind" to KIND_FAVORITE, "id" to f.id, "name" to f.label))
        }
        return b.build()
    }

    private const val HOME_LABEL = "Home"

    /** Small square footprints around each art piece, extruded on the map. */
    fun artFootprints(data: PlayaData, halfSizeFt: Double = 15.0): String {
        val b = Builder()
        data.art.forEachIndexed { i, art ->
            val c = data.artXy.getOrNull(i) ?: data.xy(art.position)
            val h = halfSizeFt
            val ring = listOf(
                Xy(c.x - h, c.y - h), Xy(c.x + h, c.y - h), Xy(c.x + h, c.y + h), Xy(c.x - h, c.y + h),
            ).map { data.ll(it) }
            b.polygon(listOf(ring), mapOf("kind" to KIND_ART, "id" to art.id, "name" to art.name))
        }
        return b.build()
    }

    fun artPoints(data: PlayaData): String {
        val b = Builder()
        for (art in data.art) b.point(art.position, mapOf("kind" to KIND_ART, "id" to art.id, "name" to art.name))
        return b.build()
    }

    fun camps(data: PlayaData): String {
        val b = Builder()
        for (camp in data.camps) b.point(camp.position, mapOf("kind" to KIND_CAMP, "id" to camp.id, "name" to camp.name))
        return b.build()
    }

    /** Toilet bank centers, for the "Potties" labels. */
    fun toiletPoints(data: PlayaData): String {
        val b = Builder()
        data.toilets.forEachIndexed { i, t ->
            b.point(data.ll(t.center), mapOf("kind" to KIND_TOILET, "id" to "toilet:$i", "name" to "Potties"))
        }
        return b.build()
    }

    /**
     * The active route: the current leg (`current = true`) and the legs after
     * it, plus a dashed walking segment where the car has to park short.
     */
    /** The route; the current leg starts at [carAt] (the drawn car) when given. */
    fun route(nav: NavState?, carAt: LatLon? = null): String {
        if (nav == null || nav.finished) return EMPTY
        val b = Builder()
        nav.plan.legs.forEachIndexed { i, leg ->
            if (i < nav.legIndex) return@forEachIndexed
            // The current leg is drawn from the car, never from where the route was planned.
            val current = i == nav.legIndex
            val path = when {
                current && nav.legAhead.size >= 2 -> listOfNotNull(carAt ?: nav.legAhead[0]) + nav.legAhead.drop(1)
                else -> leg.path
            }
            b.line(path, mapOf("g" to "drive", "current" to current))
            if (leg.walkFt != null && leg.parkAt != leg.destination.position) {
                b.line(listOf(leg.parkAt, leg.destination.position), mapOf("g" to "walk", "current" to (i == nav.legIndex)))
            }
        }
        return b.build()
    }

    /** Numbered stop markers (1 = next). [draft] marks stops that are only planned. */
    fun stops(stops: List<Destination>, draft: Boolean): String {
        val b = Builder()
        stops.forEachIndexed { i, s ->
            b.point(s.position, mapOf("kind" to KIND_STOP, "id" to i.toString(), "n" to (i + 1), "name" to s.name, "draft" to draft))
        }
        return b.build()
    }

    /** Other app vehicles with "Name · 3 min" labels; stale ones are flagged. */
    fun fleet(vehicles: List<FleetVehicle>, nowMillis: Long): String {
        val b = Builder()
        for (v in vehicles) {
            val pos = v.position ?: continue
            val t = v.positionTimeMillis ?: v.lastHeardMillis
            val age = Fmt.age(nowMillis, t)
            val name = v.name.ifBlank { v.shortName.ifBlank { "Car" } }
            b.point(
                pos,
                mapOf(
                    "kind" to KIND_FLEET,
                    // A string: large node numbers don't survive the map's float conversion.
                    "id" to v.nodeNum.toString(),
                    "label" to "$name · $age",
                    "stale" to (nowMillis - t > FLEET_STALE_MILLIS),
                    "heading" to v.headingDeg,
                ),
            )
        }
        return b.build()
    }

    /** Our own trail as one line (never shared over the mesh). */
    fun trail(points: List<TripPoint>, minSpacingFt: Double = 15.0, maxPoints: Int = 4000, maxJumpFt: Double = 1_500.0): String {
        if (points.size < 2) return EMPTY
        // Thin by distance using a cheap local scale, then cap the count.
        val ftPerDegLat = 364_567.2
        val ftPerDegLon = ftPerDegLat * kotlin.math.cos(Math.toRadians(points[0].lat))
        val kept = ArrayList<TripPoint>(minOf(points.size, maxPoints * 2))
        var last: TripPoint? = null
        for (p in points) {
            val l = last
            if (l == null || kotlin.math.hypot((p.lat - l.lat) * ftPerDegLat, (p.lon - l.lon) * ftPerDegLon) >= minSpacingFt) {
                kept += p
                last = p
            }
        }
        if (kept.last() !== points.last()) kept += points.last()
        val thinned = if (kept.size <= maxPoints) kept else {
            val step = kept.size.toDouble() / maxPoints
            (0 until maxPoints).map { kept[(it * step).toInt()] } + kept.last()
        }
        // Break the line where there's a long gap (e.g. overnight) or a jump (the
        // simulator's car moved by hand) so it doesn't cut across the playa.
        val b = Builder()
        var seg = ArrayList<LatLon>()
        var prev: TripPoint? = null
        for (p in thinned) {
            val q = prev
            if (q != null && (p.timeMillis - q.timeMillis > 10 * 60_000L ||
                    kotlin.math.hypot((p.lat - q.lat) * ftPerDegLat, (p.lon - q.lon) * ftPerDegLon) > maxJumpFt)
            ) {
                b.line(seg)
                seg = ArrayList()
            }
            seg += LatLon(p.lat, p.lon)
            prev = p
        }
        b.line(seg)
        return b.build()
    }

    /**
     * Our own vehicle: a 3D [model] (prisms with `g = "model"`, a `part` and
     * `base`/`top` heights in meters) facing [headingDeg], plus a dot
     * (`g = "pt"`) that the map shows when zoomed out.
     */
    fun me(
        data: PlayaData,
        fix: Fix?,
        model: VehicleModel,
        profile: VehicleProfile,
        headingDeg: Double,
    ): String {
        if (fix == null) return EMPTY
        val b = Builder()
        for (p in VehicleModels.build(model, data.xy(fix.position), headingDeg, profile)) {
            b.polygon(
                listOf(p.outer.map { data.ll(it) }),
                mapOf(
                    "g" to "model",
                    "kind" to KIND_ME,
                    "part" to p.part.name,
                    "base" to p.baseFt / FEET_PER_METER,
                    "top" to p.topFt / FEET_PER_METER,
                ),
            )
        }
        b.point(fix.position, mapOf("g" to "pt", "kind" to KIND_ME))
        return b.build()
    }

    fun focus(p: LatLon?): String = if (p == null) EMPTY else Builder().point(p).build()
}
