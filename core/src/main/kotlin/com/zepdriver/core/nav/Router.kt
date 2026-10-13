package com.zepdriver.core.nav

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.RuleSet

data class Instruction(
    /** Index into the leg's path where the maneuver happens. */
    val pathIndex: Int,
    val position: LatLon,
    /** Banner text, e.g. "Bear left toward 3:00". */
    val text: String,
    /** Spoken text, without distance (the session adds "In 200 feet, "). */
    val speech: String,
    /** Signed turn angle in degrees (positive = right). */
    val turnDeg: Double,
)

data class RouteLeg(
    val destination: Destination,
    /** Drivable path from the leg start to [parkAt] (or the destination). */
    val path: List<LatLon>,
    val distanceFt: Double,
    val instructions: List<Instruction>,
    /** Where to stop the vehicle. Equals the last path point. */
    val parkAt: LatLon,
    /** Non-null when the vehicle can't legally get closer: walking distance from [parkAt]. */
    val walkFt: Double?,
)

data class RoutePlan(val legs: List<RouteLeg>) {
    val distanceFt: Double get() = legs.sumOf { it.distanceFt }
    val path: List<LatLon> get() = legs.flatMap { it.path }
}

/**
 * Plans legal routes for the given vehicle: obstacle-aware on the open playa
 * (art safe distance, no-entry zones), street-following in the city where the
 * license allows, crossing cross-only roads rather than following them.
 *
 * Construction builds a cost grid (~1 M cells of [cellFt]) and may take
 * ~1 s on a phone; build off the main thread and rebuild when the profile
 * changes. Time-bound zones are evaluated once, at [nowMillis].
 *
 * Thread-safety: after construction the grid is immutable and every search
 * uses per-thread scratch buffers, so [plan] and [nearestToilet] may be called
 * concurrently from several threads.
 */
class Router(
    private val data: PlayaData,
    private val ruleSet: RuleSet,
    val profile: VehicleProfile,
    nowMillis: Long = System.currentTimeMillis(),
    cellFt: Double = 15.0,
) {
    internal val grid: CostGrid
    private val search: GridSearch
    private val instructions: InstructionBuilder

    /** How long building the cost grid took. */
    val buildMillis: Long

    init {
        val t0 = System.nanoTime()
        grid = CostGrid.build(data, ruleSet, profile, nowMillis, cellFt)
        search = GridSearch(grid)
        instructions = InstructionBuilder(data, grid)
        buildMillis = (System.nanoTime() - t0) / 1_000_000
    }

    internal val playa: PlayaData get() = data

    /** True if the vehicle may drive at [p] (ignoring cost; cross-only roads count as drivable). */
    fun isDrivable(p: LatLon): Boolean {
        val xy = data.xy(p)
        return grid.inGrid(xy.x, xy.y) && grid.costAt(grid.cellAt(xy)) != CostGrid.BLOCKED
    }

    /**
     * Plans [from] -> stops[0] -> stops[1] ... A stop the vehicle can't legally
     * reach still gets a leg, ending at the closest reachable point with
     * [RouteLeg.walkFt] set. Null only if [from] is nowhere near drivable playa.
     */
    fun plan(from: LatLon, stops: List<Destination>): RoutePlan? {
        var fromXy = data.xy(from)
        var startCell = snapStart(fromXy) ?: return null
        val legs = ArrayList<RouteLeg>(stops.size)
        for (stop in stops) {
            val (leg, endCell) = leg(fromXy, startCell, stop)
            legs += leg
            fromXy = data.xy(leg.parkAt)
            startCell = endCell
        }
        return RoutePlan(legs)
    }

    /** The porta-potty bank closest by driving distance. */
    fun nearestToilet(from: LatLon): Destination? {
        val start = snapStart(data.xy(from)) ?: return null
        val path = search.nearestTarget(start, grid.toiletCells) ?: return null
        val ti = grid.toiletOfCell[path.last()] ?: return null
        val t = data.toilets[ti]
        return Destination(
            name = TOILET_NAME,
            position = data.latLon(t.center),
            kind = DestinationKind.TOILET,
            refId = "toilet:$ti",
        )
    }

    private fun snapStart(p: Xy): Int? {
        val big = grid.compSize.getOrElse(grid.largestComp) { 0 }
        val minSize = maxOf(50, big / 20)
        val c = grid.nearestCell(p.x, p.y, START_SNAP_FT) { grid.compSize[grid.comp[it]] >= minSize }
        return c.takeIf { it >= 0 }
    }

    /** Closest cell to [p] reachable from component [comp]; prefers cells off cross-only roads. */
    private fun snapGoal(p: Xy, comp: Int): Int {
        val far = (grid.w + grid.h) * grid.cellFt
        val c = grid.nearestCell(p.x, p.y, far) { grid.comp[it] == comp && grid.costAt(it) < CostGrid.CROSS }
        if (c >= 0) return c
        return grid.nearestCell(p.x, p.y, far) { grid.comp[it] == comp }
    }

    private fun leg(fromXy: Xy, startCell: Int, dest: Destination): Pair<RouteLeg, Int> {
        val goalXy = data.xy(dest.position)
        val goalCell = snapGoal(goalXy, grid.comp[startCell]).takeIf { it >= 0 } ?: startCell
        val cells = search.astar(startCell, goalCell) ?: intArrayOf(startCell)
        val keep = search.smooth(cells)

        val pts = ArrayList<Xy>(keep.size + 2)
        val startOwn = grid.inGrid(fromXy.x, fromXy.y) && grid.cellAt(fromXy) == startCell
        pts += fromXy
        for ((k, idx) in keep.withIndex()) if (k > 0 || !startOwn) pts += grid.center(cells[idx])
        val goalOwn = grid.inGrid(goalXy.x, goalXy.y) && grid.cellAt(goalXy) == goalCell
        if (goalOwn) {
            if (pts.size > 1 && cells.size > 1) pts[pts.size - 1] = goalXy else pts += goalXy
        }
        val path = dedupe(pts)
        val park = path.last()
        val walk = goalXy.distanceTo(park).takeIf { it > WALK_THRESHOLD_FT }
        val built = instructions.build(path, dest, walk)
        var len = 0.0
        for (i in 1 until built.path.size) len += built.path[i - 1].distanceTo(built.path[i])
        val parkAt = data.latLon(park)
        val leg = RouteLeg(
            destination = dest,
            path = built.path.map { data.latLon(it) }.let { it.dropLast(1) + parkAt },
            distanceFt = len,
            instructions = built.instructions,
            parkAt = parkAt,
            walkFt = walk,
        )
        return leg to goalCell
    }

    private fun dedupe(pts: List<Xy>): List<Xy> {
        val out = ArrayList<Xy>(pts.size)
        for (p in pts) if (out.isEmpty() || out.last().distanceTo(p) > 0.5) out += p
        if (out.size == 1) out += out[0]
        return out
    }

    companion object {
        const val TOILET_NAME = "Porta-potties"
        /** Beyond this straight-line gap between [RouteLeg.parkAt] and the destination, the driver walks. */
        const val WALK_THRESHOLD_FT = 150.0
        /** How far from drivable playa a start position may be. */
        const val START_SNAP_FT = 2000.0
    }
}
