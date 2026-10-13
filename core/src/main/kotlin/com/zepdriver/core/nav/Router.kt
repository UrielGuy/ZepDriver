package com.zepdriver.core.nav

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
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
 * Construction builds a cost grid and may take ~1 s; build off the main thread
 * and rebuild when the profile changes.
 */
class Router(
    private val data: PlayaData,
    private val ruleSet: RuleSet,
    val profile: VehicleProfile,
) {
    /** Plans [from] -> stops[0] -> stops[1] ... Null if no stop is reachable. */
    fun plan(from: LatLon, stops: List<Destination>): RoutePlan? = TODO("routing agent")

    /** The porta-potty bank closest by driving distance. */
    fun nearestToilet(from: LatLon): Destination? = TODO("routing agent")
}

data class NavState(
    val plan: RoutePlan,
    val legIndex: Int,
    val nextInstruction: Instruction?,
    val distanceToNextFt: Double,
    val remainingFt: Double,
    val offRoute: Boolean,
    /** Arrived at the final stop. */
    val finished: Boolean,
    /** Something to say now (turn prompt, "Arrived at X", "Rerouting"), or null. */
    val speech: String?,
)

/**
 * Tracks progress along a multi-stop plan, advances legs on arrival, and
 * reroutes when the vehicle strays more than ~100 ft from the path.
 */
class NavigationSession(
    private val router: Router,
    start: LatLon,
    stops: List<Destination>,
) {
    /** Remaining stops, current target first. */
    val stops: List<Destination> get() = TODO("routing agent")

    val state: NavState? get() = TODO("routing agent")

    fun update(fix: Fix): NavState? = TODO("routing agent")

    /** Inserts [dest] as the very next stop (the Potties button) and replans from [from]. */
    fun insertNext(dest: Destination, from: LatLon): NavState? = TODO("routing agent")
}
