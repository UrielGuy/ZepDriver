package com.zepdriver.core.sim

import com.zepdriver.core.DriveSim.NOON
import com.zepdriver.core.DriveSim.xy
import com.zepdriver.core.TestData
import com.zepdriver.core.alerts.AlertKind
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.brc.BrcAddressing
import com.zepdriver.core.geo.bearingDiff
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.DriveMonitor
import com.zepdriver.core.rules.RuleSet2026
import com.zepdriver.core.rules.ZoneKind
import com.zepdriver.core.rules.ZoneShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayaSimulatorTest {
    private val d = TestData.playa2026
    private val addressing = BrcAddressing(d)
    private val zones = RuleSet2026.zones(d)

    @Test fun startPresetsAreLegalAndWhereTheySay() {
        assertEquals("Inner playa 3:30 @ 1,500 ft", addressing.describe(SimStart.OPEN_PLAYA.pose(d).first))
        assertEquals("Deep playa 12:00 @ 3,500 ft", addressing.describe(SimStart.DEEP_PLAYA.pose(d).first))
        assertTrue(addressing.describe(SimStart.ESPLANADE.pose(d).first).startsWith("Inner playa 4:30"))
        for (s in SimStart.entries) {
            val p = s.pose(d).first
            for (z in zones.filter { it.kind == ZoneKind.NO_ENTRY || it.kind == ZoneKind.CROSS_ONLY }) {
                val inside = z.shapes.any { sh ->
                    when (sh) {
                        is ZoneShape.Area -> sh.polygon.contains(p)
                        is ZoneShape.Outside -> !sh.polygon.contains(p)
                        is ZoneShape.Corridor -> sh.line.distanceTo(p) <= sh.halfWidthFt + 20
                    }
                }
                assertFalse(inside, "$s starts in ${z.name}")
            }
        }
    }

    @Test fun eachPresetDrivesOffQuietlyExceptTowardCenterCamp() {
        for (start in SimStart.entries) {
            val sim = PlayaSimulator(d, SimMode.MANUAL, start, seed = 5)
            val m = DriveMonitor(d, RuleSet2026, VehicleProfile())
            sim.setTargetSpeed(4.0)
            val states = (0 until 25).map { m.update(sim.step(1000, NOON + it * 1000L)) }
            if (start == SimStart.CENTER_CAMP) {
                // Pointed at Center Camp: warned off the closed frontage (Rod's Road
                // in front of Center Camp) before reaching it, never inside it.
                val more = states + (25 until 120).map { m.update(sim.step(1000, NOON + it * 1000L)) }
                val firstWarning = more.indexOfFirst { s ->
                    s.alerts.any { it.kind == AlertKind.NO_ENTRY_APPROACH && it.title == "Center Camp frontage ahead" }
                }
                assertTrue(firstWarning >= 0, "no frontage warning")
                assertTrue(more.take(firstWarning).all { it.alerts.isEmpty() })
            } else {
                assertTrue(states.all { it.alerts.isEmpty() }, "$start: ${states.flatMap { it.alerts }.map { it.title }.toSet()}")
            }
        }
    }

    @Test fun manualControls() {
        val sim = PlayaSimulator(d, SimMode.MANUAL, SimStart.OPEN_PLAYA, gpsNoise = false)
        val h0 = sim.vehicle.headingDeg
        sim.changeSpeed(0.5)
        sim.changeSpeed(0.5)
        assertEquals(1.0, sim.targetSpeedMph, 1e-9)
        sim.setTargetSpeed(20.0)
        assertEquals(VehicleSim.MAX_SPEED_MPH, sim.targetSpeedMph)
        sim.setTargetSpeed(5.0)
        repeat(10) { sim.step(1000, NOON + it * 1000L) }
        sim.steer(-25.0)
        sim.step(2000, NOON + 12_000)
        sim.steer(0.0)
        sim.step(2000, NOON + 14_000)
        assertEquals(50.0, bearingDiff(h0, sim.vehicle.headingDeg), 1e-6)
        sim.stop()
        repeat(10) { sim.step(1000, NOON + 20_000L + it * 1000L) }
        assertEquals(0.0, sim.vehicle.speedMph)
        assertNull(sim.tourLabel)
    }

    @Test fun tourModeStartsAtItsOwnStartAndCanSkip() {
        val sim = PlayaSimulator(d, SimMode.MANUAL, SimStart.DEEP_PLAYA)
        sim.setMode(SimMode.TOUR)
        val tour = SimTour.build(d)
        assertTrue(sim.vehicle.position.distanceTo(d.xy(tour.first().path.first())) < 1.0)
        assertEquals(tour.first().label, sim.tourLabel)
        assertEquals(1 to tour.size, sim.tourProgress)
        assertEquals(4.5, sim.targetSpeedMph)
        // Presets don't move a touring car.
        sim.setStart(SimStart.ESPLANADE)
        assertTrue(sim.vehicle.position.distanceTo(d.xy(tour.first().path.first())) < 1.0)

        assertTrue(sim.nextSegment())
        assertEquals("Speed: 5.7 mph (amber)", sim.tourLabel)
        assertTrue(sim.vehicle.position.distanceTo(d.xy(tour[1].path.first())) < 1.0)
        repeat(tour.size) { sim.nextSegment() }
        assertFalse(sim.nextSegment())
        assertEquals(tour.size to tour.size, sim.tourProgress)

        // Touching the wheel takes over.
        sim.steer(25.0)
        assertEquals(SimMode.MANUAL, sim.mode)
        assertNull(sim.tourLabel)
    }

    @Test fun autopilotFollowsTheRouteThenStops() {
        val sim = PlayaSimulator(d, SimMode.AUTOPILOT, SimStart.OPEN_PLAYA, gpsNoise = false)
        val start = sim.vehicle.position
        // No route yet: stays put.
        sim.followRoute(null, null)
        repeat(5) { sim.step(1000, NOON + it * 1000L) }
        assertEquals(0.0, sim.vehicle.speedMph)
        assertFalse(sim.hasRoute)

        val route = listOf(start, xy("3:40", 1500.0), xy("3:40", 1200.0)).map(d::latLon)
        val key = Any()
        sim.followRoute(key, route)
        assertTrue(sim.hasRoute)
        assertEquals(5.0, sim.targetSpeedMph)
        var maxMph = 0.0
        var t = NOON + 10_000
        repeat(400) {
            // The engine passes the same plan every tick; that must not restart the route.
            sim.followRoute(key, route)
            sim.step(1000, t)
            t += 1000
            maxMph = maxOf(maxMph, sim.vehicle.speedMph)
        }
        assertEquals(5.0, maxMph, 1e-6)
        assertEquals(0.0, sim.vehicle.speedMph)
        assertTrue(sim.vehicle.position.distanceTo(xy("3:40", 1200.0)) <= 20.0)
        assertFalse(sim.hasRoute)

        // A faster cruise for testing speed warnings on a route.
        sim.followRoute(Any(), listOf(sim.vehicle.latLon, d.latLon(xy("3:40", 600.0))))
        sim.setTargetSpeed(6.5)
        repeat(30) { sim.step(1000, t); t += 1000 }
        assertEquals(6.5, sim.vehicle.speedMph, 1e-6)
        assertEquals(SimMode.AUTOPILOT, sim.mode)

        // The route ends (navigation stopped): slow to a stop.
        sim.followRoute(null, null)
        repeat(15) { sim.step(1000, t); t += 1000 }
        assertEquals(0.0, sim.vehicle.speedMph)
    }

    @Test fun takingOverKeepsSpeed() {
        val sim = PlayaSimulator(d, SimMode.TOUR, gpsNoise = false)
        repeat(20) { sim.step(1000, NOON + it * 1000L) }
        assertEquals(4.5, sim.vehicle.speedMph, 1e-6)
        sim.setMode(SimMode.MANUAL)
        assertEquals(4.5, sim.targetSpeedMph, 1e-6)
        sim.step(5000, NOON + 30_000)
        assertEquals(4.5, sim.vehicle.speedMph, 1e-6)
        sim.setStart(SimStart.DEEP_PLAYA)
        assertEquals(0.0, sim.vehicle.speedMph)
        assertTrue(sim.vehicle.position.distanceTo(SimStart.DEEP_PLAYA.pose(d).first) < 1e-6)
    }

    // --- Fake fleet ---------------------------------------------------------------------

    @Test fun fakeFleetWandersTheOpenPlaya() {
        val fleet = FakeFleet(d, count = 3, seed = 11)
        val gc = d.plazas.first { it.name == "Great Circle" }.polygon
        val city = zones.first { it.id == "city-playa-only" }.shapes.filterIsInstance<ZoneShape.Area>().first().polygon
        var t = NOON
        val first = fleet.step(0, t)
        assertEquals(FakeFleet.NAMES, first.map { it.name })
        assertTrue(first.all { FakeFleet.isSimulated(it.nodeNum) })
        assertEquals(3, first.map { it.nodeNum }.toSet().size)
        assertFalse(FakeFleet.isSimulated(0x12345678L))
        val starts = fleet.positions
        val headings = first.map { it.headingDeg!! }.toMutableList()
        repeat(3_600) {
            t += 1000
            val vs = fleet.step(1000, t)
            for ((i, v) in vs.withIndex()) {
                val p = d.xy(v.position!!)
                assertTrue(d.fence.contains(p), "inside the fence")
                assertFalse(gc.contains(p), "outside the Great Circle")
                assertFalse(city.contains(p), "outside the city at ${addressing.describe(p)}")
                assertTrue(v.speedMph!! in 0.0..5.0)
                assertEquals(t, v.lastHeardMillis)
                assertTrue(bearingDiff(headings[i], v.headingDeg!!) <= 26.0, "smooth heading")
                headings[i] = v.headingDeg!!
            }
        }
        assertTrue(fleet.positions.zip(starts).all { (a, b) -> a.distanceTo(b) > 100 }, "they move")
        // Deterministic for a seed.
        val again = FakeFleet(d, count = 3, seed = 11)
        assertEquals(starts, again.positions)
    }
}
