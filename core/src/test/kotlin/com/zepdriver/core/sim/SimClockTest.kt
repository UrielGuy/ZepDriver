package com.zepdriver.core.sim

import com.zepdriver.core.DriveSim.NOON
import com.zepdriver.core.TestData
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.DriveMonitor
import com.zepdriver.core.rules.DriveState
import com.zepdriver.core.rules.DrivingMode
import com.zepdriver.core.rules.RuleSet2026
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimClockTest {
    private val d = TestData.playa2026

    @Test fun warpScalesTimeAndStaysMonotonic() {
        val c = SimClock(NOON, warp = 10)
        assertEquals(2_500, c.advance(250))
        assertEquals(NOON + 2_500, c.nowMillis)
        // A zero (or negative) wall step still moves time forward.
        val before = c.nowMillis
        assertTrue(c.advance(0) >= 1)
        assertTrue(c.advance(-5) >= 1)
        assertTrue(c.nowMillis > before)
        c.warp = 0
        assertEquals(1, c.warp)
        c.warp = 100
        assertEquals(SimClock.MAX_WARP, c.warp)
        assertEquals(1_000, SimClock.tickMillis(1))
        assertTrue(SimClock.tickMillis(10) in 250..500)
        assertTrue(SimClock.WARPS.all { SimClock.clampWarp(it) == it })
    }

    /** Drives [wallMillis] of wall time in ticks; returns the fixes. */
    private fun drive(sim: PlayaSimulator, clock: SimClock, wallMillis: Long): List<Fix> {
        val tick = SimClock.tickMillis(clock.warp)
        return (0 until wallMillis / tick).map {
            val dt = clock.advance(tick)
            sim.step(dt, clock.nowMillis)
        }
    }

    @Test fun atTimesTenTheCarCoversTenTimesTheDistancePerWallSecond() {
        fun cruise(warp: Int): Pair<Double, Long> {
            val sim = PlayaSimulator(d, SimMode.MANUAL, SimStart.DEEP_PLAYA, gpsNoise = false)
            val clock = SimClock(NOON, warp = 1)
            sim.setTargetSpeed(5.0)
            drive(sim, clock, 15_000) // up to speed
            clock.warp = warp
            val from = sim.vehicle.position
            val t0 = clock.nowMillis
            val fixes = drive(sim, clock, 10_000)
            // Speed stays the car's real speed whatever the warp.
            assertTrue(fixes.all { (it.speedMph ?: 0.0) in 4.9..5.1 }, "warp $warp speeds")
            assertTrue(fixes.zipWithNext().all { (a, b) -> b.timeMillis > a.timeMillis })
            return sim.vehicle.position.distanceTo(from) to (clock.nowMillis - t0)
        }
        val (ft1, ms1) = cruise(1)
        val (ft10, ms10) = cruise(10)
        assertEquals(10_000, ms1)
        assertEquals(100_000, ms10)
        assertEquals(ft1 * 10, ft10, ft1 * 0.02)
    }

    @Test fun speedWarningsStillEscalateAndClearAtHighWarp() {
        val sim = PlayaSimulator(d, SimMode.MANUAL, SimStart.DEEP_PLAYA, gpsNoise = false)
        val clock = SimClock(NOON, warp = 10)
        val m = DriveMonitor(d, RuleSet2026, VehicleProfile())
        fun at(mph: Double): List<DriveState> {
            sim.setTargetSpeed(mph)
            return drive(sim, clock, 5_000).map { m.update(it) } // 50 s simulated
        }
        val cruising = at(4.5)
        assertEquals(AlertLevel.NONE, cruising.last().speedLevel)
        assertEquals(DrivingMode.MOVING, cruising.last().mode)
        assertEquals(AlertLevel.CAUTION, at(5.7).last().speedLevel)
        assertEquals(AlertLevel.WARNING, at(6.2).last().speedLevel)
        assertEquals(AlertLevel.ALARM, at(6.8).last().speedLevel)
        assertEquals(AlertLevel.NONE, at(4.0).last().speedLevel)
        val stopped = at(0.0).last()
        assertEquals(DrivingMode.PARKED, stopped.mode)
    }

    @Test fun teleportKeepsHeadingAndStops() {
        val sim = PlayaSimulator(d, SimMode.TOUR, gpsNoise = false)
        repeat(20) { sim.step(1_000, NOON + it * 1_000L) }
        val heading = sim.vehicle.headingDeg
        val to = SimStart.DEEP_PLAYA.pose(d).first
        sim.teleport(to)
        assertEquals(SimMode.MANUAL, sim.mode)
        assertEquals(to, sim.vehicle.position)
        assertEquals(heading, sim.vehicle.headingDeg, 1e-9)
        assertEquals(0.0, sim.vehicle.speedMph)
        assertEquals(0.0, sim.targetSpeedMph)
        val fix = sim.step(1_000, NOON + 30_000)
        assertTrue(sim.vehicle.position.distanceTo(to) < 1.0)
        assertEquals(0.0, fix.speedMph ?: 0.0, 1e-9)
    }
}
