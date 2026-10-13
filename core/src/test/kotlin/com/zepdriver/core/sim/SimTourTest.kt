package com.zepdriver.core.sim

import com.zepdriver.core.DriveSim.NOON
import com.zepdriver.core.TestData
import com.zepdriver.core.alerts.AlertKind
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.brc.BrcAddressing
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.bearingOf
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.DriveMonitor
import com.zepdriver.core.rules.DriveState
import com.zepdriver.core.rules.DrivingMode
import com.zepdriver.core.rules.RuleSet2026
import com.zepdriver.core.rules.Zone
import com.zepdriver.core.rules.ZoneMonitor
import com.zepdriver.core.rules.ZoneShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimTourTest {
    private val d = TestData.playa2026
    private val zones = RuleSet2026.zones(d)
    private val tour = SimTour.build(d)
    private val addressing = BrcAddressing(d)

    private fun seg(id: String) = tour.first { it.id == id }
    private fun SimTour.Segment.xy() = path.map(d::xy)
    private fun zone(id: String) = zones.first { it.id == id }

    private fun Zone.contains(p: Xy, marginFt: Double = 0.0) = shapes.any { s ->
        when (s) {
            is ZoneShape.Area -> s.polygon.contains(p) || s.polygon.distanceToEdge(p) <= marginFt
            is ZoneShape.Outside -> !s.polygon.contains(p)
            is ZoneShape.Corridor -> s.line.distanceTo(p) <= s.halfWidthFt + marginFt
        }
    }

    /** Zones that would alert a standard Mutant Vehicle at noon. */
    private val restricted = zones.filter {
        it.kind.name in setOf("NO_ENTRY", "CROSS_ONLY") || it.id.startsWith("plaza:")
    }

    // --- Geometry ---------------------------------------------------------------------

    @Test fun segmentsJoinUp() {
        assertEquals(
            listOf(
                "cruise", "speed-caution", "speed-warning", "speed-alarm", "speed-ok",
                "gc-approach", "gc-enter", "gc-exit", "to-esplanade",
                "esp-cross", "u-turn", "esp-cross-back", "esp-merge", "esp-along",
                "city-in", "city-street", "city-out",
            ),
            tour.map { it.id },
        )
        for ((a, b) in tour.zipWithNext()) {
            assertTrue(d.xy(a.path.last()).distanceTo(d.xy(b.path.first())) < 1.0, "${a.id} -> ${b.id}")
        }
        assertTrue(tour.all { it.label.isNotBlank() && it.speedMph in 2.0..7.0 })
        assertEquals("Speed: 6.2 mph (warning)", seg("speed-warning").label)
        assertEquals("Driving along the Esplanade (violation)", seg("esp-along").label)
    }

    @Test fun speedRunIsOnOpenPlaya() {
        for (id in listOf("cruise", "speed-caution", "speed-warning", "speed-alarm", "speed-ok", "to-esplanade")) {
            for (p in seg(id).xy()) {
                val hit = restricted.firstOrNull { it.contains(p, marginFt = 60.0) }
                assertTrue(hit == null, "$id passes ${hit?.name} at ${addressing.describe(p)}")
            }
        }
        assertEquals(listOf(5.7, 6.2, 6.8), listOf("speed-caution", "speed-warning", "speed-alarm").map { seg(it).speedMph })
    }

    @Test fun greatCircleSegmentsGoInAndOut() {
        val gc = zone("great-circle")
        assertFalse(gc.contains(seg("gc-approach").xy().last(), marginFt = 100.0), "approach ends well outside")
        val enter = seg("gc-enter").xy()
        assertFalse(gc.contains(enter.first()))
        assertTrue(gc.contains(enter.last()), "ends inside the Great Circle")
        // Straight at the Man.
        val dir = bearingOf(enter.last() - enter.first())
        assertEquals(0.0, com.zepdriver.core.geo.bearingDiff(dir, bearingOf(d.manXy - enter.first())), 1.0)
        assertFalse(gc.contains(seg("gc-exit").xy().last(), marginFt = 100.0), "exits")
        // The exit stays off the promenades that cross the Great Circle.
        val promenades = zone("promenades")
        assertTrue(seg("gc-exit").xy().none { promenades.contains(it, marginFt = 10.0) })
    }

    @Test fun esplanadeCrossingIsSquare() {
        val esp = zone("esplanade")
        val cross = seg("esp-cross").xy()
        val (a, b) = cross.first() to cross.last()
        assertFalse(esp.contains(a, 5.0))
        assertFalse(esp.contains(b, 5.0))
        val r = d.layout.esplanade.radiusFt
        assertTrue(a.length() < r && b.length() > r, "crosses from the playa side to the city side")
        // Radial = perpendicular to the ring.
        assertEquals(0.0, com.zepdriver.core.geo.bearingDiff(bearingOf(b - a), bearingOf(a)), 1.0)
        assertTrue(addressing.describe(b).startsWith("5:00 & "), addressing.describe(b))
        val back = seg("esp-cross-back").xy()
        assertTrue(back.first().length() > r + 20 && back.last().length() < r - 20)
    }

    @Test fun alongSegmentStaysOnTheEsplanade() {
        val esp = zone("esplanade")
        val along = seg("esp-along").xy()
        assertTrue(along.all { esp.contains(it) }, "every point inside the Esplanade corridor")
        val lengthFt = along.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
        assertTrue(lengthFt in 280.0..360.0, "about 300 ft along: $lengthFt")
        assertTrue(addressing.describe(along.first()).endsWith("& Esplanade"))
        assertEquals("4:30 & Esplanade", addressing.describe(along.last()))
        // Parallel to the road the whole way.
        for ((p, q) in along.zipWithNext()) {
            val road = esp.shapes.filterIsInstance<ZoneShape.Corridor>().minBy { it.line.distanceTo(p) }.line.nearest(p)
            assertTrue(ZoneMonitor.axisAngle(bearingOf(q - p), road.segmentBearing) < 10)
        }
    }

    @Test fun cityLoopUsesStreets() {
        assertEquals("4:30 & Esplanade", addressing.describe(seg("city-in").xy().first()))
        assertEquals("4:30 & C", addressing.describe(seg("city-in").xy().last()))
        assertEquals("4:00 & C", addressing.describe(seg("city-street").xy().last()))
        assertTrue(addressing.describe(seg("city-out").xy().last()).startsWith("Inner playa 4:00"))
        assertTrue(listOf("city-in", "city-street", "city-out").all { seg(it).speedMph == 5.0 })
    }

    // --- The whole tour through DriveMonitor ----------------------------------------------

    private class Sample(val segment: String, val state: DriveState)

    private fun runTour(noise: Boolean, seed: Long): Pair<List<Sample>, PlayaSimulator> {
        val sim = PlayaSimulator(d, SimMode.TOUR, seed = seed, gpsNoise = noise)
        val monitor = DriveMonitor(d, RuleSet2026, VehicleProfile())
        val out = ArrayList<Sample>()
        var t = NOON
        for (i in 0 until 3_000) {
            val id = sim.tour!!.current.id
            val fix = sim.step(1000, t)
            out += Sample(id, monitor.update(fix))
            t += 1000
            if (sim.tour!!.finished && sim.vehicle.speedMph == 0.0) break
        }
        return out to sim
    }

    private fun List<Sample>.of(vararg ids: String) = filter { it.segment in ids }
    private fun List<Sample>.has(kind: AlertKind, level: AlertLevel) = any { s -> s.state.alerts.any { it.kind == kind && it.level == level } }
    private fun List<Sample>.has(kind: AlertKind) = any { s -> s.state.alerts.any { it.kind == kind } }
    private fun List<Sample>.firstIndex(kind: AlertKind) = indexOfFirst { s -> s.state.alerts.any { it.kind == kind } }

    @Test fun tourTriggersEachWarningWhereIntended() {
        for ((noise, seed) in listOf(false to 1L, true to 1L, true to 42L, true to 2026L)) {
            val (run, sim) = runTour(noise, seed)
            val tag = "noise=$noise seed=$seed"
            assertTrue(sim.tour!!.finished, "tour finishes ($tag)")
            assertEquals(0.0, sim.vehicle.speedMph)
            assertTrue(run.size in 900..1500, "about 20 minutes: ${run.size} s")

            // Cruising: nothing to say.
            assertTrue(run.of("cruise").all { it.state.alerts.isEmpty() }, tag)
            assertEquals(DrivingMode.MOVING, run.of("cruise").last().state.mode)

            // Speed: amber, then warning, then alarm, then clear.
            val caution = run.of("speed-caution")
            assertTrue(caution.has(AlertKind.SPEED, AlertLevel.CAUTION), tag)
            assertFalse(caution.has(AlertKind.SPEED, AlertLevel.WARNING) || caution.has(AlertKind.SPEED, AlertLevel.ALARM), tag)
            val warning = run.of("speed-warning")
            assertTrue(warning.has(AlertKind.SPEED, AlertLevel.WARNING), tag)
            assertFalse(warning.has(AlertKind.SPEED, AlertLevel.ALARM), tag)
            assertTrue(run.of("speed-alarm").has(AlertKind.SPEED, AlertLevel.ALARM), tag)
            assertEquals(AlertLevel.NONE, run.of("speed-ok").last().state.speedLevel, tag)
            assertTrue(run.of("speed-ok").takeLast(10).all { it.state.alerts.isEmpty() }, tag)

            // The Great Circle: warned before entering, alarm inside, quiet once out.
            val gc = run.of("gc-approach", "gc-enter", "gc-exit")
            val warn = gc.firstIndex(AlertKind.NO_ENTRY_APPROACH)
            val inside = gc.firstIndex(AlertKind.NO_ENTRY_INSIDE)
            assertTrue(warn >= 0 && inside > warn + 4, "warn $warn inside $inside ($tag)")
            assertTrue(gc.has(AlertKind.NO_ENTRY_APPROACH, AlertLevel.WARNING))
            assertEquals("Great Circle ahead", gc[warn].state.alerts.first { it.kind == AlertKind.NO_ENTRY_APPROACH }.title)
            assertEquals("Inside Great Circle", gc[inside].state.alerts.first { it.kind == AlertKind.NO_ENTRY_INSIDE }.title)
            assertEquals("gc-enter", gc[inside].segment)
            assertTrue(gc.last().state.alerts.isEmpty(), "${gc.last().state.alerts} ($tag)")
            assertFalse(run.filterNot { it.segment.startsWith("gc-") }.has(AlertKind.NO_ENTRY_INSIDE), tag)
            assertTrue(run.of("to-esplanade").all { it.state.alerts.isEmpty() }, tag)

            // Crossing the Esplanade square-on (and back): a reminder, never an alarm.
            val crossing = run.of("esp-cross", "u-turn", "esp-cross-back")
            assertTrue(crossing.has(AlertKind.CROSSING, AlertLevel.ADVISORY), tag)
            assertTrue(crossing.all { it.state.topLevel <= AlertLevel.ADVISORY }, tag)
            assertEquals(
                "Crossing Esplanade",
                crossing.first { s -> s.state.alerts.any { it.kind == AlertKind.CROSSING } }.state.alerts.first().title,
            )

            // Driving along it: alarm.
            val along = run.of("esp-along")
            val alarm = along.firstOrNull { s -> s.state.alerts.any { it.kind == AlertKind.DRIVING_ALONG } }
            assertTrue(alarm != null, tag)
            val a = alarm.state.alerts.first { it.kind == AlertKind.DRIVING_ALONG }
            assertEquals(AlertLevel.ALARM, a.level)
            assertEquals("zone:esplanade:driving_along", a.key)
            assertTrue(along.takeLast(along.size / 2).all { s -> s.state.alerts.any { it.kind == AlertKind.DRIVING_ALONG } }, tag)
            assertTrue(along.last().state.address!!.endsWith("& Esplanade"))
            // ...and only there (it lingers while turning off onto 4:30).
            val alongSegments = run.filter { s -> s.state.alerts.any { it.kind == AlertKind.DRIVING_ALONG } }.map { it.segment }.toSet()
            assertTrue(alongSegments.all { it in setOf("esp-merge", "esp-along", "city-in") }, "$alongSegments ($tag)")

            // City loop: legal for a full license; plazas only get a caution.
            val city = run.of("city-in", "city-street", "city-out").drop(10)
            assertTrue(city.all { it.state.topLevel <= AlertLevel.CAUTION || it.state.alerts.all { a -> a.kind == AlertKind.CROSSING } }, tag)
            assertTrue(city.has(AlertKind.CAUTION_AREA, AlertLevel.CAUTION), "passes the 4:30 & B plaza ($tag)")
            assertTrue(run.of("city-street").any { it.state.address == "4:15 & C" }, tag)
            assertTrue(run.of("city-out").has(AlertKind.CROSSING, AlertLevel.ADVISORY), tag)

            // Never a false alarm: every ALARM is one the script asked for.
            val alarmSegments = run.filter { it.state.topLevel == AlertLevel.ALARM }.map { it.segment }.toSet()
            assertTrue(
                alarmSegments.all { it in setOf("speed-alarm", "speed-ok", "gc-enter", "gc-exit", "esp-merge", "esp-along", "city-in") },
                "$alarmSegments ($tag)",
            )
            assertTrue(run.last().state.address!!.startsWith("Inner playa"))
        }
    }

    @Test fun playaOnlyLicenseIsWarnedInTheCity() {
        val sim = PlayaSimulator(d, SimMode.TOUR, seed = 3)
        val monitor = DriveMonitor(d, RuleSet2026, VehicleProfile(licenseClass = com.zepdriver.core.profile.LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY))
        var t = NOON
        val license = HashMap<String, MutableSet<AlertLevel>>()
        while (!(sim.tour!!.finished && sim.vehicle.speedMph == 0.0)) {
            val id = sim.tour!!.current.id
            monitor.update(sim.step(1000, t)).alerts.filter { it.kind == AlertKind.LICENSE_AREA }
                .forEach { license.getOrPut(id) { HashSet() } += it.level }
            t += 1000
        }
        val city = setOf("esp-cross", "u-turn", "esp-cross-back", "city-in", "city-street", "city-out")
        assertTrue(license.keys.isNotEmpty() && license.keys.all { it in city }, "$license")
        assertTrue(AlertLevel.ALARM in license["city-street"]!!, "$license")
    }
}
