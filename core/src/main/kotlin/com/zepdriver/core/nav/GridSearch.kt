package com.zepdriver.core.nav

import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.bearingOf
import java.util.BitSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val SQRT2 = 1.4142135f

/** Per-thread search buffers sized to the grid; reused across searches via a generation stamp. */
internal class SearchScratch(n: Int) {
    val g = FloatArray(n)
    /** (generation shl 1) or closedBit. */
    val mark = IntArray(n)
    /** Direction index (0..7) of the move that reached the cell. */
    val parent = ByteArray(n)
    var gen = 0
    var heapNode = IntArray(4096)
    var heapKey = FloatArray(4096)
    var heapSize = 0

    fun next() {
        gen++
        if (gen >= (1 shl 29)) { java.util.Arrays.fill(mark, 0); gen = 1 }
        heapSize = 0
    }

    fun push(node: Int, key: Float) {
        if (heapSize == heapNode.size) {
            heapNode = heapNode.copyOf(heapSize * 2)
            heapKey = heapKey.copyOf(heapSize * 2)
        }
        var i = heapSize++
        while (i > 0) {
            val p = (i - 1) ushr 1
            if (heapKey[p] <= key) break
            heapNode[i] = heapNode[p]; heapKey[i] = heapKey[p]
            i = p
        }
        heapNode[i] = node; heapKey[i] = key
    }

    /** Removes the minimum; returns its node. */
    fun pop(): Int {
        val top = heapNode[0]
        val lastNode = heapNode[--heapSize]
        val lastKey = heapKey[heapSize]
        var i = 0
        while (true) {
            var c = 2 * i + 1
            if (c >= heapSize) break
            if (c + 1 < heapSize && heapKey[c + 1] < heapKey[c]) c++
            if (heapKey[c] >= lastKey) break
            heapNode[i] = heapNode[c]; heapKey[i] = heapKey[c]
            i = c
        }
        if (heapSize > 0) { heapNode[i] = lastNode; heapKey[i] = lastKey }
        return top
    }
}

/** A* / Dijkstra over a [CostGrid]. Thread-safe: each thread gets its own scratch buffers. */
internal class GridSearch(private val grid: CostGrid) {
    private val scratch = ThreadLocal.withInitial { SearchScratch(grid.n) }

    /** Lowest-cost cell path from [start] to [goal] (inclusive), or null if unreachable. */
    fun astar(start: Int, goal: Int): IntArray? = search(start, goal, null)

    /** Dijkstra from [start] until the first cell in [targets]; returns that path or null. */
    fun nearestTarget(start: Int, targets: BitSet): IntArray? = search(start, -1, targets)

    private fun search(start: Int, goal: Int, targets: BitSet?): IntArray? {
        val gr = grid
        val w = gr.w
        val cs = gr.cellFt.toFloat()
        val costScale = cs / CostGrid.BASE / 2f
        val s = scratch.get()
        s.next()
        val open = s.gen shl 1
        val closed = open or 1
        val gx = goal % w
        val gy = goal / w
        fun heur(i: Int): Float {
            if (targets != null) return 0f
            val dx = abs(i % w - gx); val dy = abs(i / w - gy)
            val mn = min(dx, dy); val mx = max(dx, dy)
            return ((mx - mn) + SQRT2 * mn) * cs
        }
        s.g[start] = 0f
        s.mark[start] = open
        s.push(start, heur(start))
        val cost = gr.cost
        while (s.heapSize > 0) {
            val u = s.pop()
            if (s.mark[u] == closed) continue
            s.mark[u] = closed
            if (u == goal || (targets != null && targets[u])) return reconstruct(s, start, u)
            val gu = s.g[u]
            for (d in 0 until 8) {
                val v = u + DX[d] + DY[d] * w
                if (cost[v].toInt() == 0) continue
                val m = s.mark[v]
                if (m == closed) continue
                val cu = gr.moveCost(u, d)
                val cv = gr.moveCost(v, d)
                val step = if (d < 4) 1f else SQRT2
                val ng = gu + step * (cu + cv) * costScale
                if (m != open || ng < s.g[v]) {
                    s.g[v] = ng
                    s.mark[v] = open
                    s.parent[v] = d.toByte()
                    s.push(v, ng + heur(v))
                }
            }
        }
        return null
    }

    private fun reconstruct(s: SearchScratch, start: Int, end: Int): IntArray {
        val w = grid.w
        var len = 1
        var c = end
        while (c != start) { val d = s.parent[c].toInt(); c -= DX[d] + DY[d] * w; len++ }
        val out = IntArray(len)
        c = end
        for (k in len - 1 downTo 0) {
            out[k] = c
            if (k > 0) { val d = s.parent[c].toInt(); c -= DX[d] + DY[d] * w }
        }
        return out
    }

    // --- Smoothing ---------------------------------------------------------

    /** Cost of moving between consecutive cells of [path], cumulative. */
    private fun cumulativeCost(path: IntArray): DoubleArray {
        val cum = DoubleArray(path.size)
        for (k in 1 until path.size) {
            val a = path[k - 1]; val b = path[k]
            val len = dist(grid.cx(a), grid.cy(a), grid.cx(b), grid.cy(b))
            val d = dirOf(b - a)
            cum[k] = cum[k - 1] + len * (grid.moveCost(a, d) + grid.moveCost(b, d)) / (2.0 * CostGrid.BASE)
        }
        return cum
    }

    /**
     * Line-of-sight shortcutting: replaces runs of cells by straight segments
     * when the straight line crosses no blocked cell, enters no cost class
     * (plaza, cross-only road) the run it replaces avoided, and costs no more. Open-playa legs become
     * straight lines; street legs keep following streets; cross-only roads
     * are crossed as directly as the grid path crossed them.
     *
     * Returns indices into [path] of the kept vertices.
     */
    fun smooth(path: IntArray): IntArray {
        if (path.size <= 2) return path.indices.toList().toIntArray()
        val cum = cumulativeCost(path)
        val keep = ArrayList<Int>()
        keep += 0
        var i = 0
        while (i < path.size - 1) {
            var best = i + 1
            var maxCost = max(grid.costAt(path[i]), grid.costAt(path[i + 1]))
            var fails = 0
            var j = i + 2
            while (j < path.size) {
                maxCost = max(maxCost, grid.costAt(path[j]))
                if (lineOk(path[i], path[j], maxCost, cum[j] - cum[i])) {
                    best = j; fails = 0
                } else if (++fails > MAX_FAILS) break
                j++
            }
            keep += best
            i = best
        }
        return keep.toIntArray()
    }

    private fun lineOk(a: Int, b: Int, maxCost: Int, budget: Double): Boolean {
        val ax = grid.cx(a); val ay = grid.cy(a)
        val bx = grid.cx(b); val by = grid.cy(b)
        val len = dist(ax, ay, bx, by)
        val steps = max(1, ceil(len / (grid.cellFt / 4)).toInt())
        val stepLen = len / steps
        val bearing = bearingOf(Xy(bx - ax, by - ay))
        var total = 0.0
        val limit = budget + 1e-6 + 0.02 * budget
        for (k in 0..steps) {
            val t = k.toDouble() / steps
            val cell = grid.cellAt(ax + (bx - ax) * t, ay + (by - ay) * t)
            if (grid.costAt(cell) > maxCost) return false
            val c = grid.lineCost(cell, bearing)
            if (c == 0) return false
            if (k > 0) {
                total += stepLen * c / CostGrid.BASE
                if (total > limit) return false
            }
        }
        return true
    }

    /** Direction index of the move from a cell to its neighbour, given (neighbour - cell). */
    private fun dirOf(delta: Int): Int {
        val w = grid.w
        for (d in 0 until 8) if (DX[d] + DY[d] * w == delta) return d
        return 0
    }

    companion object {
        private const val MAX_FAILS = 40
        fun dist(ax: Double, ay: Double, bx: Double, by: Double) = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
    }
}
