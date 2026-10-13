package com.zepdriver.core.rules

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.Polygon
import com.zepdriver.core.geo.Polyline
import com.zepdriver.core.profile.VehicleProfile

enum class ZoneKind {
    /** May not be driven into at all. */
    NO_ENTRY,
    /** May be crossed but not driven along (Esplanade, promenades, paths). */
    CROSS_ONLY,
    /**
     * Only some licenses may drive here (e.g. city streets for playa-only
     * vehicles, which may only take the direct route camp <-> open playa).
     */
    LICENSE_RESTRICTED,
    /** Amplified sound must be off here while the zone is active. */
    SOUND_OFF,
    /** Drive with extra care (plazas, burn perimeters); reminder only. */
    CAUTION_AREA,
}

sealed interface ZoneShape {
    data class Area(val polygon: Polygon) : ZoneShape
    /** A road: centerline plus half its width. */
    data class Corridor(val line: Polyline, val halfWidthFt: Double) : ZoneShape
    /** Everything outside [polygon] (e.g. beyond the trash fence). */
    data class Outside(val polygon: Polygon) : ZoneShape
}

class Zone(
    val id: String,
    /** Display name, e.g. "Esplanade". */
    val name: String,
    val kind: ZoneKind,
    val shapes: List<ZoneShape>,
    /** Whether this zone applies to the given vehicle. */
    val appliesTo: (VehicleProfile) -> Boolean = { true },
    /** Whether this zone is active at the given epoch millis (burn nights etc). */
    val activeAt: (Long) -> Boolean = { true },
    /** How the voice refers to it: "the Esplanade", "the Great Circle". */
    val spokenName: String = name,
    /** Why the rule exists / what to do, shown in the banner detail. */
    val note: String? = null,
)

/**
 * One year's DMV rules, coded. Users never edit these; they're reviewed and
 * updated before each burn (see SPEC.md §13).
 */
interface RuleSet {
    val year: Int
    val speedLimitMph: Double
    /** Where each rule came from, for the in-app "About the rules" screen. */
    val sources: List<String>
    fun zones(data: PlayaData): List<Zone>
}

fun ruleSetFor(year: Int): RuleSet = when (year) {
    2026 -> RuleSet2026
    else -> RuleSet2026
}
