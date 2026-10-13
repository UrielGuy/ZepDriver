package com.zepdriver.core.rules

import com.zepdriver.core.brc.Clock
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.data.StreetKind
import com.zepdriver.core.geo.Polygon
import com.zepdriver.core.profile.LicenseClass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Black Rock City 2026 DMV rules, coded.
 *
 * Rule basis (2026 Mutant Vehicle Owner's Handbook v3.0, Vehicle Rules and
 * Protocols, MV Sound Policy, Accessibility Vehicle Operator's Handbook):
 * - 5 mph maximum everywhere.
 * - No driving on the Esplanade itself (crossing it is fine; the open-playa
 *   side of the Esplanade is fine).
 * - No driving on pedestrian-only ways: the 3:00 / 6:00 / 9:00 promenades,
 *   the promenade to the Temple, and the community paths.
 * - No driving inside the Great Circle around the Man.
 * - No driving in the Center Camp plaza or in walk-in camping.
 * - Playa-only (incl. large / LCU) licenses: city streets only on the
 *   shortest route between camp and open playa, without turning.
 * - No amplified sound while driving city streets 02:00–10:00.
 * - Sound systems off at the Temple burn perimeter.
 *
 * Items marked VERIFY are reasonable interpretations, not quoted rules, and
 * must be checked in the annual review.
 */
object RuleSet2026 : RuleSet {
    override val year = 2026
    override val speedLimitMph = 5.0

    override val sources = listOf(
        "Mutant Vehicle Owner's Handbook v3.0 (updated 2026-08-03), burningman.org",
        "Vehicle Rules and Protocols, burningman.org",
        "Mutant Vehicle Sound Policy (2026), burningman.org",
        "Accessibility Vehicle Operator's Handbook, burningman.org",
        "2026 BRC Measurements (2026-02-25) and GIS map data, innovate.burningman.org",
    )

    private val isMutantVehicle: (com.zepdriver.core.profile.VehicleProfile) -> Boolean =
        { it.licenseClass != LicenseClass.ACCESSIBILITY_VEHICLE }

    override fun zones(data: PlayaData): List<Zone> {
        val layout = data.layout
        val tz = ZoneId.of(layout.timeZone)
        val esp = layout.esplanade
        val k = layout.outerRing
        val zones = mutableListOf<Zone>()

        // --- No entry -------------------------------------------------------
        val greatCircle = data.plazas.firstOrNull { it.name == "Great Circle" }?.polygon
            ?: Polygon.circle(data.manXy, layout.greatCircleRadiusFt)
        zones += Zone(
            id = "great-circle", name = "Great Circle", kind = ZoneKind.NO_ENTRY,
            shapes = listOf(ZoneShape.Area(greatCircle)),
            spokenName = "the Great Circle",
            note = "No driving inside the Great Circle around the Man. Park outside it.",
        )

        data.plazas.firstOrNull { it.name == "Center Camp Plaza" }?.let {
            zones += Zone(
                id = "center-camp", name = "Center Camp Plaza", kind = ZoneKind.NO_ENTRY,
                shapes = listOf(ZoneShape.Area(it.polygon)),
                spokenName = "Center Camp",
                note = "Pedestrian plaza. No vehicles.",
            )
        }

        val walkIn = layout.walkInCamping
        zones += Zone(
            id = "walk-in-camping", name = "Walk-in camping", kind = ZoneKind.NO_ENTRY,
            shapes = listOf(
                ZoneShape.Area(
                    Clock.sector(
                        layout,
                        Clock.parse(walkIn.fromClock)!!.toDouble(),
                        Clock.parse(walkIn.toClock)!!.toDouble(),
                        // VERIFY: depth of walk-in camping beyond K.
                        innerFt = k.radiusFt + k.widthFt / 2,
                        outerFt = k.radiusFt + 1400,
                    ),
                ),
            ),
            spokenName = "walk-in camping",
            note = "Tents everywhere. No vehicles beyond K between 2:00 and 5:00.",
        )

        zones += Zone(
            id = "trash-fence", name = "Trash fence", kind = ZoneKind.NO_ENTRY,
            shapes = listOf(ZoneShape.Outside(data.fence)),
            spokenName = "the trash fence",
            note = "Stay inside the perimeter fence.",
        )

        // --- Cross only -------------------------------------------------------
        zones += Zone(
            id = "esplanade", name = "Esplanade", kind = ZoneKind.CROSS_ONLY,
            shapes = data.streets.filter { it.kind == StreetKind.ANNULAR && it.name == "ESP" }
                .map { ZoneShape.Corridor(it.line, it.widthFt / 2) },
            spokenName = "the Esplanade",
            note = "Cross it, don't drive on it. Use the open-playa side.",
        )

        // Promenades: the 3, 6, 9 and 12 o'clock axes between the Man and the Esplanade.
        val promenadeNames = setOf("3:00", "6:00", "9:00", "12:00")
        val promenades = data.streets.filter { s ->
            s.kind == StreetKind.AVENUE && s.name in promenadeNames &&
                s.line.points.all { it.length() <= esp.radiusFt + esp.widthFt / 2 }
        }
        zones += Zone(
            id = "promenades", name = "Promenade", kind = ZoneKind.CROSS_ONLY,
            shapes = promenades.map { ZoneShape.Corridor(it.line, it.widthFt / 2) },
            spokenName = "the promenade",
            note = "Pedestrian promenade. Cross it, don't drive along it.",
        )

        zones += Zone(
            id = "community-paths", name = "Community path", kind = ZoneKind.CROSS_ONLY,
            shapes = data.streets.filter { it.kind == StreetKind.PATH }
                .map { ZoneShape.Corridor(it.line, it.widthFt / 2) },
            spokenName = "the community path",
            note = "Pedestrian and bike path. Cross it, don't drive along it.",
        )

        // --- License restricted ------------------------------------------------
        val cityStart = Clock.parse(layout.cityStartClock)!!.toDouble()
        val cityEnd = Clock.parse(layout.cityEndClock)!!.toDouble()
        val city = Clock.sector(
            layout, cityStart, cityEnd,
            innerFt = esp.radiusFt + esp.widthFt / 2,
            outerFt = k.radiusFt + k.widthFt / 2,
            steps = 96,
        )
        zones += Zone(
            id = "city-playa-only", name = "City streets", kind = ZoneKind.LICENSE_RESTRICTED,
            shapes = listOf(ZoneShape.Area(city)),
            appliesTo = { it.licenseClass == LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY },
            spokenName = "the city",
            note = "Playa-only license: straight route between your camp and the open playa only. No cruising.",
        )

        // --- Sound -----------------------------------------------------------
        zones += Zone(
            id = "quiet-hours", name = "Quiet hours in the city", kind = ZoneKind.SOUND_OFF,
            shapes = listOf(ZoneShape.Area(city)),
            appliesTo = { isMutantVehicle(it) && it.hasSoundSystem },
            activeAt = { t -> Instant.ofEpochMilli(t).atZone(tz).hour in 2 until 10 },
            spokenName = "the city during quiet hours",
            note = "No amplified sound while driving city streets 2 am to 10 am.",
        )

        val templeBurn = LocalDate.parse(layout.templeBurn)
        zones += Zone(
            id = "temple-burn-sound", name = "Temple burn", kind = ZoneKind.SOUND_OFF,
            // VERIFY: perimeter size; the rule is "sound off on arrival at the perimeter".
            shapes = listOf(ZoneShape.Area(Polygon.circle(data.templeXy, 1000.0))),
            appliesTo = { isMutantVehicle(it) && it.hasSoundSystem },
            activeAt = burnNight(templeBurn, tz),
            spokenName = "the Temple burn perimeter",
            note = "Sound systems off at the Temple burn. Violations cost future licenses.",
        )

        // --- Caution ----------------------------------------------------------
        data.plazas.filter { it.name != "Great Circle" && it.name != "Center Camp Plaza" }.forEach {
            zones += Zone(
                id = "plaza:${it.name}", name = it.name, kind = ZoneKind.CAUTION_AREA,
                shapes = listOf(ZoneShape.Area(it.polygon)),
                spokenName = "the plaza",
                note = "Plaza: walkers and bikes. Go slow.",
            )
        }
        val manBurn = LocalDate.parse(layout.manBurn)
        zones += Zone(
            id = "man-burn", name = "Man burn perimeter", kind = ZoneKind.CAUTION_AREA,
            // VERIFY: perimeter size. Rangers set the actual line on the night.
            shapes = listOf(ZoneShape.Area(Polygon.circle(data.manXy, 1000.0))),
            activeAt = burnNight(manBurn, tz),
            spokenName = "the burn perimeter",
            note = "Burn night: follow Rangers, park at the perimeter.",
        )
        return zones
    }

    /** 18:00 on [date] until 04:00 the next morning, local time. */
    private fun burnNight(date: LocalDate, tz: ZoneId): (Long) -> Boolean {
        val start = date.atTime(18, 0).atZone(tz).toInstant().toEpochMilli()
        val end = date.plusDays(1).atTime(4, 0).atZone(tz).toInstant().toEpochMilli()
        return { t -> t in start until end }
    }
}
