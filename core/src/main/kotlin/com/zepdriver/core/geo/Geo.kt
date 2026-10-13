package com.zepdriver.core.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

const val FEET_PER_METER = 3.28084
const val MPH_PER_MPS = 2.236936

/** WGS84 coordinate. */
@kotlinx.serialization.Serializable
data class LatLon(val lat: Double, val lon: Double)

/**
 * Planar coordinate in feet on the local playa plane: x = east, y = north,
 * origin at the projection's origin (normally the Man).
 */
data class Xy(val x: Double, val y: Double) {
    operator fun plus(o: Xy) = Xy(x + o.x, y + o.y)
    operator fun minus(o: Xy) = Xy(x - o.x, y - o.y)
    operator fun times(k: Double) = Xy(x * k, y * k)
    fun length() = hypot(x, y)
    fun dot(o: Xy) = x * o.x + y * o.y
    fun cross(o: Xy) = x * o.y - y * o.x
    fun distanceTo(o: Xy) = hypot(x - o.x, y - o.y)
    fun normalized(): Xy {
        val l = length()
        return if (l == 0.0) this else Xy(x / l, y / l)
    }

    companion object {
        /** Unit vector pointing along compass [bearingDeg] (0 = north, 90 = east). */
        fun fromBearing(bearingDeg: Double): Xy {
            val r = Math.toRadians(bearingDeg)
            return Xy(kotlin.math.sin(r), cos(r))
        }
    }
}

/** Compass bearing (0..360, 0 = north) of vector [v]. */
fun bearingOf(v: Xy): Double = normalizeDeg(Math.toDegrees(atan2(v.x, v.y)))

fun normalizeDeg(d: Double): Double {
    var r = d % 360.0
    if (r < 0) r += 360.0
    return r
}

/** Smallest absolute difference between two bearings, 0..180. */
fun bearingDiff(a: Double, b: Double): Double {
    val d = abs(normalizeDeg(a) - normalizeDeg(b))
    return if (d > 180) 360 - d else d
}

/** Signed turn from bearing [from] to [to], -180..180 (positive = right / clockwise). */
fun signedTurn(from: Double, to: Double): Double {
    var d = normalizeDeg(to) - normalizeDeg(from)
    if (d > 180) d -= 360
    if (d < -180) d += 360
    return d
}

/**
 * Equirectangular projection around [origin]. Over the few miles of Black Rock
 * City the error is well under a foot, which is far below GPS noise.
 */
class LocalProjection(val origin: LatLon) {
    private val ftPerDegLat = 364_567.2 // mean, at ~40.8°N
    private val ftPerDegLon = ftPerDegLat * cos(origin.lat * PI / 180.0)

    fun toXy(p: LatLon) = Xy((p.lon - origin.lon) * ftPerDegLon, (p.lat - origin.lat) * ftPerDegLat)
    fun toLatLon(p: Xy) = LatLon(origin.lat + p.y / ftPerDegLat, origin.lon + p.x / ftPerDegLon)
}

/** Axis-aligned bounding box in feet. */
data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    fun contains(p: Xy) = p.x in minX..maxX && p.y in minY..maxY
    fun expanded(d: Double) = Bounds(minX - d, minY - d, maxX + d, maxY + d)
    fun intersects(o: Bounds) = minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY

    companion object {
        fun of(points: Iterable<Xy>): Bounds {
            var a = Double.MAX_VALUE; var b = Double.MAX_VALUE
            var c = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
            for (p in points) {
                if (p.x < a) a = p.x; if (p.y < b) b = p.y
                if (p.x > c) c = p.x; if (p.y > d) d = p.y
            }
            return Bounds(a, b, c, d)
        }
    }
}

/** Distance from [p] to segment [a]-[b]. */
fun distanceToSegment(p: Xy, a: Xy, b: Xy): Double = p.distanceTo(closestPointOnSegment(p, a, b))

fun closestPointOnSegment(p: Xy, a: Xy, b: Xy): Xy {
    val ab = b - a
    val len2 = ab.dot(ab)
    if (len2 == 0.0) return a
    val t = ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
    return a + ab * t
}

/** A polygon with an outer ring and optional holes. Rings need not be closed. */
class Polygon(val outer: List<Xy>, val holes: List<List<Xy>> = emptyList()) {
    val bounds: Bounds = Bounds.of(outer)

    fun contains(p: Xy): Boolean {
        if (!bounds.contains(p)) return false
        if (!ringContains(outer, p)) return false
        return holes.none { ringContains(it, p) }
    }

    /** Distance from [p] to the polygon boundary (0 on the edge). */
    fun distanceToEdge(p: Xy): Double {
        var best = ringDistance(outer, p)
        for (h in holes) best = minOf(best, ringDistance(h, p))
        return best
    }

    /** 0 inside, otherwise distance to the boundary. */
    fun distanceTo(p: Xy): Double = if (contains(p)) 0.0 else distanceToEdge(p)

    fun centroid(): Xy {
        var x = 0.0; var y = 0.0
        for (q in outer) { x += q.x; y += q.y }
        return Xy(x / outer.size, y / outer.size)
    }

    companion object {
        fun ringContains(ring: List<Xy>, p: Xy): Boolean {
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val a = ring[i]; val b = ring[j]
                if ((a.y > p.y) != (b.y > p.y) &&
                    p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
                ) inside = !inside
                j = i
            }
            return inside
        }

        fun ringDistance(ring: List<Xy>, p: Xy): Double {
            var best = Double.MAX_VALUE
            for (i in ring.indices) {
                val d = distanceToSegment(p, ring[i], ring[(i + 1) % ring.size])
                if (d < best) best = d
            }
            return best
        }

        /** Regular polygon approximating a circle. */
        fun circle(center: Xy, radius: Double, segments: Int = 48): Polygon =
            Polygon((0 until segments).map {
                val a = 2 * PI * it / segments
                Xy(center.x + radius * cos(a), center.y + radius * kotlin.math.sin(a))
            })
    }
}

/** Result of projecting a point onto a polyline. */
data class PolylineHit(
    val distance: Double,
    val point: Xy,
    val segmentIndex: Int,
    /** Compass bearing of the segment that was hit. */
    val segmentBearing: Double,
    /** Distance along the polyline from its start to [point]. */
    val along: Double,
)

class Polyline(val points: List<Xy>) {
    val bounds: Bounds = Bounds.of(points)
    val length: Double by lazy {
        var l = 0.0
        for (i in 1 until points.size) l += points[i - 1].distanceTo(points[i])
        l
    }

    fun nearest(p: Xy): PolylineHit {
        var best: PolylineHit? = null
        var along = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            val c = closestPointOnSegment(p, a, b)
            val d = p.distanceTo(c)
            if (best == null || d < best.distance) {
                best = PolylineHit(d, c, i, bearingOf(b - a), along + a.distanceTo(c))
            }
            along += a.distanceTo(b)
        }
        return best ?: PolylineHit(p.distanceTo(points[0]), points[0], 0, 0.0, 0.0)
    }

    fun distanceTo(p: Xy) = nearest(p).distance
}

fun sqr(x: Double) = x * x
fun dist(ax: Double, ay: Double, bx: Double, by: Double) = sqrt(sqr(ax - bx) + sqr(ay - by))
