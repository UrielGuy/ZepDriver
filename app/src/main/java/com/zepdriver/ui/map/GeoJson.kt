package com.zepdriver.ui.map

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.mesh.FleetVehicle
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.nav.NavState
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.Zone
import com.zepdriver.core.rules.ZoneShape
import com.zepdriver.core.trip.TripPoint
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

    /** Small square footprints around each art piece, extruded on the map. */
    fun artFootprints(data: PlayaData, halfSizeFt: Double = 15.0): String {
        val b = Builder()
        data.art.forEachIndexed { i, art ->
            val c = data.artXy.getOrNull(i) ?: data.xy(art.position)
            val h = halfSizeFt
            val ring = listOf(
                Xy(c.x - h, c.y - h), Xy(c.x + h, c.y - h), Xy(c.x + h, c.y + h), Xy(c.x - h, c.y + h),
            ).map { data.ll(it) }
            b.polygon(listOf(ring), mapOf("name" to art.name))
        }
        return b.build()
    }

    fun artPoints(data: PlayaData): String {
        val b = Builder()
        for (art in data.art) b.point(art.position, mapOf("name" to art.name))
        return b.build()
    }

    fun camps(data: PlayaData): String {
        val b = Builder()
        for (camp in data.camps) b.point(camp.position, mapOf("name" to camp.name))
        return b.build()
    }

    /** Toilet bank centers, for the "Potties" labels. */
    fun toiletPoints(data: PlayaData): String {
        val b = Builder()
        for (t in data.toilets) b.point(data.ll(t.center), mapOf("name" to "Potties"))
        return b.build()
    }

    /**
     * The active route: the current leg (`current = true`) and the legs after
     * it, plus a dashed walking segment where the car has to park short.
     */
    fun route(nav: NavState?): String {
        if (nav == null || nav.finished) return EMPTY
        val b = Builder()
        nav.plan.legs.forEachIndexed { i, leg ->
            if (i < nav.legIndex) return@forEachIndexed
            b.line(leg.path, mapOf("g" to "drive", "current" to (i == nav.legIndex)))
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
            b.point(s.position, mapOf("n" to (i + 1), "name" to s.name, "draft" to draft))
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
                    "label" to "$name · $age",
                    "stale" to (nowMillis - t > FLEET_STALE_MILLIS),
                    "heading" to v.headingDeg,
                ),
            )
        }
        return b.build()
    }

    /** Our own trail as one line (never shared over the mesh). */
    fun trail(points: List<TripPoint>, minSpacingFt: Double = 15.0, maxPoints: Int = 4000): String {
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
        // Break the line where there's a long gap (e.g. overnight) so it doesn't jump.
        val b = Builder()
        var seg = ArrayList<LatLon>()
        var prevT = Long.MIN_VALUE
        for (p in thinned) {
            if (prevT != Long.MIN_VALUE && p.timeMillis - prevT > 10 * 60_000L) {
                b.line(seg)
                seg = ArrayList()
            }
            seg += LatLon(p.lat, p.lon)
            prevT = p.timeMillis
        }
        b.line(seg)
        return b.build()
    }

    /**
     * Our own vehicle: a dot (`g = "pt"`) plus, when the heading is known, a
     * triangle pointing along it (`g = "arrow"`), sized in feet.
     */
    fun me(data: PlayaData, fix: Fix?, lengthFt: Double = 60.0): String {
        if (fix == null) return EMPTY
        val b = Builder()
        val bearing = fix.bearingDeg
        if (bearing != null) {
            val c = data.xy(fix.position)
            val fwd = Xy.fromBearing(bearing)
            val right = Xy(fwd.y, -fwd.x)
            val tip = c + fwd * (lengthFt * 0.6)
            val back = c - fwd * (lengthFt * 0.4)
            val notch = c - fwd * (lengthFt * 0.15)
            val half = lengthFt * 0.38
            val ring = listOf(tip, back + right * half, notch, back - right * half).map { data.ll(it) }
            b.polygon(listOf(ring), mapOf("g" to "arrow"))
        }
        b.point(fix.position, mapOf("g" to "pt"))
        return b.build()
    }

    fun focus(p: LatLon?): String = if (p == null) EMPTY else Builder().point(p).build()
}
