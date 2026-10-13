package com.zepdriver.core.nav

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.Polygon
import com.zepdriver.core.geo.Polyline
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.bearingOf
import com.zepdriver.core.geo.normalizeDeg
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.HomeCorridor
import com.zepdriver.core.rules.RuleSet
import com.zepdriver.core.rules.Zone
import com.zepdriver.core.rules.ZoneKind
import com.zepdriver.core.rules.ZoneShape
import java.util.BitSet
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Raster of driving costs over the local plane (feet), one byte per cell.
 *
 * Cost codes are unsigned bytes: [BLOCKED] (0) is impassable, [BASE] is open
 * playa / city street, [CAUTION] plazas, [CROSS] cross-only corridors. A move
 * between neighbouring cells costs its length times the mean of the two cells'
 * effective costs divided by [BASE], so [BASE] cells cost exactly their
 * distance in feet.
 *
 * Cross-only cells are direction-aware: moving across the corridor (within
 * [CROSS_TOLERANCE_DEG] of its normal) costs [CROSS_CHEAP], anything closer to
 * driving along it costs [CROSS] (x40). So crossing the Esplanade is cheap
 * while following it never pays.
 *
 * Immutable after [build]; safe to read from several threads.
 */
internal class CostGrid private constructor(
    val minX: Double,
    val minY: Double,
    val cellFt: Double,
    val w: Int,
    val h: Int,
) {
    val n = w * h
    val cost = ByteArray(n)
    /** Bit flags per cell: low 4 bits = cross-only zone index + 1, plus [TAG_CITY], [TAG_CAUTION]. */
    val tag = ByteArray(n)
    /** Connected-component id per passable cell (8-connected), -1 when blocked. */
    lateinit var comp: IntArray
    lateinit var compSize: IntArray
    /** Normal bearing (whole degrees 0..179) of the cross-only corridor at each cell, [NO_NORMAL] if none. */
    val crossNormal = ByteArray(n).also { java.util.Arrays.fill(it, NO_NORMAL.toByte()) }
    /** Cross-only zones by tag index. */
    var crossZones: List<Zone> = emptyList()
    /** Cells next to (or inside) a porta-potty bank, and which bank. */
    val toiletCells = BitSet(n)
    val toiletOfCell = HashMap<Int, Int>()
    var largestComp = -1

    fun costAt(i: Int): Int = cost[i].toInt() and 0xFF
    fun tagAt(i: Int): Int = tag[i].toInt() and 0xFF

    /** Effective cost of cell [i] for a grid move in direction [d] (index into [DX]/[DY]). */
    fun moveCost(i: Int, d: Int): Int {
        val c = cost[i].toInt() and 0xFF
        if (c != CROSS) return c
        val nb = crossNormal[i].toInt() and 0xFF
        return if (nb != NO_NORMAL && CHEAP_DIR[nb * 8 + d]) CROSS_CHEAP else c
    }

    /** Effective cost of cell [i] when travelling along compass [bearing]. */
    fun lineCost(i: Int, bearing: Double): Int {
        val c = cost[i].toInt() and 0xFF
        if (c != CROSS) return c
        val nb = crossNormal[i].toInt() and 0xFF
        return if (nb != NO_NORMAL && axisDiff(bearing, nb.toDouble()) <= CROSS_TOLERANCE_DEG) CROSS_CHEAP else c
    }
    fun cx(i: Int): Double = minX + (i % w + 0.5) * cellFt
    fun cy(i: Int): Double = minY + (i / w + 0.5) * cellFt
    fun center(i: Int) = Xy(cx(i), cy(i))
    fun col(x: Double) = floor((x - minX) / cellFt).toInt()
    fun row(y: Double) = floor((y - minY) / cellFt).toInt()
    fun inGrid(x: Double, y: Double) = col(x) in 0 until w && row(y) in 0 until h

    /** Cell containing (x, y), clamped to the grid. */
    fun cellAt(x: Double, y: Double): Int =
        row(y).coerceIn(0, h - 1) * w + col(x).coerceIn(0, w - 1)

    fun cellAt(p: Xy) = cellAt(p.x, p.y)

    // --- Rasterization -----------------------------------------------------

    /** Calls [action] for every cell whose center lies inside [poly] (even-odd over all rings). */
    inline fun forEachCellInPolygon(poly: Polygon, action: (Int) -> Unit) {
        val b = poly.bounds
        val r0 = max(0, floor((b.minY - minY) / cellFt - 0.5).toInt())
        val r1 = min(h - 1, ceil((b.maxY - minY) / cellFt - 0.5).toInt())
        if (r0 > r1) return
        val rings = ArrayList<List<Xy>>(1 + poly.holes.size).apply { add(poly.outer); addAll(poly.holes) }
        var xs = DoubleArray(16)
        for (r in r0..r1) {
            val y = minY + (r + 0.5) * cellFt
            var cnt = 0
            for (ring in rings) {
                val m = ring.size
                var j = m - 1
                for (k in 0 until m) {
                    val a = ring[k]; val c = ring[j]
                    if ((a.y > y) != (c.y > y)) {
                        if (cnt == xs.size) xs = xs.copyOf(cnt * 2)
                        xs[cnt++] = a.x + (y - a.y) * (c.x - a.x) / (c.y - a.y)
                    }
                    j = k
                }
            }
            if (cnt < 2) continue
            java.util.Arrays.sort(xs, 0, cnt)
            var k = 0
            while (k + 1 < cnt) {
                val c0 = max(0, ceil((xs[k] - minX) / cellFt - 0.5).toInt())
                val c1 = min(w - 1, ceil((xs[k + 1] - minX) / cellFt - 0.5).toInt() - 1)
                for (c in c0..c1) action(r * w + c)
                k += 2
            }
        }
    }

    /** Calls [action] for every cell whose center is within [radius] of segment a-b. */
    inline fun forEachCellNearSegment(ax: Double, ay: Double, bx: Double, by: Double, radius: Double, action: (Int) -> Unit) {
        val c0 = max(0, floor((min(ax, bx) - radius - minX) / cellFt - 0.5).toInt())
        val c1 = min(w - 1, ceil((max(ax, bx) + radius - minX) / cellFt - 0.5).toInt())
        val r0 = max(0, floor((min(ay, by) - radius - minY) / cellFt - 0.5).toInt())
        val r1 = min(h - 1, ceil((max(ay, by) + radius - minY) / cellFt - 0.5).toInt())
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val r2 = radius * radius
        for (r in r0..r1) {
            val py = minY + (r + 0.5) * cellFt
            for (c in c0..c1) {
                val px = minX + (c + 0.5) * cellFt
                var t = if (len2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / len2
                if (t < 0) t = 0.0 else if (t > 1) t = 1.0
                val ex = ax + dx * t - px; val ey = ay + dy * t - py
                if (ex * ex + ey * ey <= r2) action(r * w + c)
            }
        }
    }

    inline fun forEachCellNearPolyline(line: List<Xy>, radius: Double, closed: Boolean, action: (Int) -> Unit) {
        if (line.size == 1) {
            forEachCellNearSegment(line[0].x, line[0].y, line[0].x, line[0].y, radius, action)
            return
        }
        val last = if (closed) line.size else line.size - 1
        for (k in 0 until last) {
            val a = line[k]; val b = line[(k + 1) % line.size]
            forEachCellNearSegment(a.x, a.y, b.x, b.y, radius, action)
        }
    }

    /** Cells inside [poly] or within [margin] of its boundary. */
    inline fun forEachCellInDilatedPolygon(poly: Polygon, margin: Double, action: (Int) -> Unit) {
        forEachCellInPolygon(poly, action)
        if (margin > 0) {
            forEachCellNearPolyline(poly.outer, margin, true, action)
            for (hole in poly.holes) forEachCellNearPolyline(hole, margin, true, action)
        }
    }

    companion object {
        const val BLOCKED = 0
        const val BASE = 5
        const val CAUTION = 8
        /** x40 the base cost: driving along a cross-only road. */
        const val CROSS = 200
        /** x3: crossing a cross-only road (more or less perpendicular). */
        const val CROSS_CHEAP = 15
        const val CROSS_TOLERANCE_DEG = 25.0
        const val NO_NORMAL = 0xFF
        const val TAG_CROSS_MASK = 0x0F
        const val TAG_CITY = 0x10
        const val TAG_CAUTION = 0x20

        /**
         * In the city, art sits in plazas the streets run through; there the
         * clearance is reduced so the street grid (and a playa-only vehicle's
         * home corridor) stays connected. Open-playa art gets the full buffer.
         */
        const val CITY_ART_CLEARANCE_FT = 30.0
        /** How close to a porta-potty bank counts as "at" it. */
        const val TOILET_REACH_FT = 45.0

        fun build(
            data: PlayaData,
            ruleSet: RuleSet,
            profile: VehicleProfile,
            nowMillis: Long,
            cellFt: Double = 15.0,
        ): CostGrid {
            val fb = data.fence.bounds
            val pad = 3 * cellFt
            val minX = fb.minX - pad
            val minY = fb.minY - pad
            val w = ceil((fb.maxX + pad - minX) / cellFt).toInt()
            val h = ceil((fb.maxY + pad - minY) / cellFt).toInt()
            val g = CostGrid(minX, minY, cellFt, w, h)
            g.fill(data, ruleSet, profile, nowMillis)
            return g
        }
    }

    private fun fill(data: PlayaData, ruleSet: RuleSet, profile: VehicleProfile, nowMillis: Long) {
        val base = BASE.toByte()
        val blocked = BLOCKED.toByte()
        // Hazard areas are dilated so no point of a free cell lies inside them.
        val margin = cellFt * 0.75
        val all = ruleSet.zones(data)
        val zones = all.filter { it.appliesTo(profile) && it.activeAt(nowMillis) }

        // Inside the trash fence: drivable.
        forEachCellInPolygon(data.fence) { cost[it] = base }

        // City tag (regardless of license; used for art clearance and instructions).
        val cityZone = all.firstOrNull { it.id == "city-playa-only" }
            ?: all.firstOrNull { it.kind == ZoneKind.LICENSE_RESTRICTED }
        cityZone?.shapes?.forEach { s ->
            if (s is ZoneShape.Area) forEachCellInPolygon(s.polygon) { tag[it] = (tag[it].toInt() or TAG_CITY).toByte() }
        }

        // No-entry zones.
        val mask = ByteArray(n)
        for (z in zones.filter { it.kind == ZoneKind.NO_ENTRY }) {
            for (s in z.shapes) when (s) {
                is ZoneShape.Area -> forEachCellInDilatedPolygon(s.polygon, margin) { cost[it] = blocked }
                is ZoneShape.Corridor -> forEachCellNearPolyline(s.line.points, s.halfWidthFt + margin, false) { cost[it] = blocked }
                is ZoneShape.Outside -> {
                    java.util.Arrays.fill(mask, 0)
                    forEachCellInPolygon(s.polygon) { mask[it] = 1 }
                    for (i in 0 until n) if (mask[i].toInt() == 0) cost[i] = blocked
                    forEachCellNearPolyline(s.polygon.outer, margin, true) { cost[it] = blocked }
                }
            }
        }

        // Camps (city blocks). Not dilated: the streets between them are only 2 cells wide.
        for (b in data.blocks) forEachCellInPolygon(b) { cost[it] = blocked }

        // Art safe distance, measured from the vehicle's side.
        val half = profile.widthFt / 2
        for (a in data.artXy) {
            if (!inGrid(a.x, a.y)) continue
            val inCity = (tagAt(cellAt(a)) and TAG_CITY) != 0
            val clear = if (inCity) min(profile.artBufferFt, CITY_ART_CLEARANCE_FT) else profile.artBufferFt
            forEachCellNearSegment(a.x, a.y, a.x, a.y, clear + half + margin) { cost[it] = blocked }
        }

        // License restrictions (city streets for playa-only), except the home corridor.
        val restricted = zones.filter { it.kind == ZoneKind.LICENSE_RESTRICTED }
        if (restricted.isNotEmpty()) {
            java.util.Arrays.fill(mask, 0)
            profile.homeCamp?.let { home ->
                val corridor: Polyline = HomeCorridor.build(data, home)
                forEachCellNearPolyline(corridor.points, HomeCorridor.HALF_WIDTH_FT, false) { mask[it] = 1 }
            }
            for (z in restricted) for (s in z.shapes) {
                val block: (Int) -> Unit = { if (mask[it].toInt() == 0) cost[it] = blocked }
                when (s) {
                    is ZoneShape.Area -> forEachCellInPolygon(s.polygon, block)
                    is ZoneShape.Corridor -> forEachCellNearPolyline(s.line.points, s.halfWidthFt, false, block)
                    is ZoneShape.Outside -> {}
                }
            }
        }

        // Cross-only corridors: passable but very expensive.
        crossZones = zones.filter { it.kind == ZoneKind.CROSS_ONLY }.take(TAG_CROSS_MASK)
        crossZones.forEachIndexed { zi, z ->
            var normal = NO_NORMAL
            val mark: (Int) -> Unit = {
                tag[it] = ((tag[it].toInt() and TAG_CROSS_MASK.inv()) or (zi + 1)).toByte()
                if (cost[it].toInt() != 0) {
                    cost[it] = max(costAt(it), CROSS).toByte()
                    crossNormal[it] = normal.toByte()
                }
            }
            for (s in z.shapes) when (s) {
                is ZoneShape.Corridor -> {
                    val pts = s.line.points
                    for (k in 0 until pts.size - 1) {
                        val a = pts[k]; val b = pts[k + 1]
                        normal = (normalizeDeg(bearingOf(b - a) + 90.0) % 180.0).toInt().coerceIn(0, 179)
                        forEachCellNearSegment(a.x, a.y, b.x, b.y, s.halfWidthFt, mark)
                    }
                }
                is ZoneShape.Area -> { normal = NO_NORMAL; forEachCellInPolygon(s.polygon, mark) }
                is ZoneShape.Outside -> {}
            }
        }

        // Caution areas: mildly more expensive.
        for (z in zones.filter { it.kind == ZoneKind.CAUTION_AREA }) {
            val mark: (Int) -> Unit = {
                tag[it] = (tag[it].toInt() or TAG_CAUTION).toByte()
                if (cost[it].toInt() != 0 && costAt(it) < CAUTION) cost[it] = CAUTION.toByte()
            }
            for (s in z.shapes) when (s) {
                is ZoneShape.Area -> forEachCellInPolygon(s.polygon, mark)
                is ZoneShape.Corridor -> forEachCellNearPolyline(s.line.points, s.halfWidthFt, false, mark)
                is ZoneShape.Outside -> {}
            }
        }

        // Keep a blocked border so neighbour lookups never leave the grid.
        for (c in 0 until w) { cost[c] = blocked; cost[(h - 1) * w + c] = blocked }
        for (r in 0 until h) { cost[r * w] = blocked; cost[r * w + w - 1] = blocked }

        labelComponents()

        // Porta-potty targets.
        data.toilets.forEachIndexed { ti, t ->
            var found = false
            for (reach in doubleArrayOf(TOILET_REACH_FT, 3 * TOILET_REACH_FT)) {
                forEachCellInDilatedPolygon(t.polygon, reach) {
                    if (cost[it].toInt() != 0 && costAt(it) < CROSS) {
                        toiletCells.set(it)
                        toiletOfCell.putIfAbsent(it, ti)
                        found = true
                    }
                }
                if (found) break
            }
        }
    }

    private fun labelComponents() {
        val c = IntArray(n) { -1 }
        val sizes = ArrayList<Int>()
        val queue = IntArray(n)
        var id = 0
        for (s in 0 until n) {
            if (c[s] != -1 || cost[s].toInt() == 0) continue
            var head = 0; var tail = 0
            queue[tail++] = s; c[s] = id
            while (head < tail) {
                val u = queue[head++]
                for (d in 0 until 8) {
                    val v = u + DX[d] + DY[d] * w
                    if (c[v] == -1 && cost[v].toInt() != 0) { c[v] = id; queue[tail++] = v }
                }
            }
            sizes += tail
            id++
        }
        comp = c
        compSize = sizes.toIntArray()
        largestComp = compSize.indices.maxByOrNull { compSize[it] } ?: -1
    }

    /**
     * The free cell nearest (straight line) to (x, y) that satisfies [accept],
     * searching at most [maxRadiusFt] away. -1 if none.
     */
    fun nearestCell(x: Double, y: Double, maxRadiusFt: Double, accept: (Int) -> Boolean): Int {
        val c0 = col(x).coerceIn(0, w - 1)
        val r0 = row(y).coerceIn(0, h - 1)
        // Distance from the point to its (clamped) cell's center bounds the ring error.
        val off = sqrt(sqr(x - (minX + (c0 + 0.5) * cellFt)) + sqr(y - (minY + (r0 + 0.5) * cellFt)))
        val maxR = ceil((maxRadiusFt + off) / cellFt).toInt() + 1
        var best = -1
        var bestD = Double.MAX_VALUE
        fun check(r: Int, c: Int) {
            if (r < 0 || r >= h || c < 0 || c >= w) return
            val i = r * w + c
            if (cost[i].toInt() == 0 || !accept(i)) return
            val d = sqrt(sqr(minX + (c + 0.5) * cellFt - x) + sqr(minY + (r + 0.5) * cellFt - y))
            if (d < bestD) { bestD = d; best = i }
        }
        for (rad in 0..maxR) {
            if (best >= 0 && rad * cellFt - off > bestD) break
            if (rad == 0) { check(r0, c0); continue }
            if (r0 - rad >= 0 || r0 + rad < h) for (c in c0 - rad..c0 + rad) { check(r0 - rad, c); check(r0 + rad, c) }
            if (c0 - rad >= 0 || c0 + rad < w) for (r in r0 - rad + 1 until r0 + rad) { check(r, c0 - rad); check(r, c0 + rad) }
        }
        return if (best >= 0 && bestD <= maxRadiusFt + off) best else -1
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun sqr(v: Double) = v * v
}

internal val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
internal val DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
/** Compass bearing of each grid direction (y = north). */
internal val DIR_BEARING = doubleArrayOf(90.0, 270.0, 0.0, 180.0, 45.0, 135.0, 315.0, 225.0)

/** Difference between two undirected axes (bearings mod 180), 0..90. */
internal fun axisDiff(a: Double, b: Double): Double {
    val x = ((a - b) % 180.0 + 180.0) % 180.0
    return if (x > 90) 180 - x else x
}

/** CHEAP_DIR[normal * 8 + d]: grid direction d crosses a corridor with that normal. */
private val CHEAP_DIR = BooleanArray(180 * 8) {
    axisDiff(DIR_BEARING[it % 8], (it / 8).toDouble()) <= CostGrid.CROSS_TOLERANCE_DEG
}
