package com.zepdriver.core.sim

import com.zepdriver.core.DriveSim.NOON
import com.zepdriver.core.DriveSim.xy
import com.zepdriver.core.TestData
import com.zepdriver.core.geo.Polyline
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.bearingDiff
import com.zepdriver.core.geo.signedTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VehicleSimTest {
    private val d = TestData.playa2026
    private val start = xy("3:30", 1500.0)

    private fun sim(heading: Double = 0.0) = VehicleSim(d, start, heading)

    @Test fun acceleratesSmoothlyToTarget() {
        val s = sim()
        s.targetSpeedMph = 5.0
        val speeds = (1..10).map { s.step(1000, NOON + it * 1000L).speedMph!! }
        assertEquals(0.7, speeds[0], 1e-6)
        assertEquals(1.4, speeds[1], 1e-6)
        assertEquals(5.0, speeds.last(), 1e-6)
        assertTrue(speeds.zipWithNext().all { (a, b) -> b >= a && b - a <= 0.7 + 1e-9 })
        // Distance = area under the speed ramp: 7.14 s accelerating + 2.86 s at 5 mph.
        val ramp = 5.0 / 0.7
        val expectedFt = (5.0 * ramp / 2 + 5.0 * (10 - ramp)) * FPS_PER_MPH
        assertEquals(expectedFt, s.position.distanceTo(start), 0.5)
        // Straight ahead (north).
        assertEquals(0.0, bearingDiff(com.zepdriver.core.geo.bearingOf(s.position - start), 0.0), 0.01)

        s.targetSpeedMph = 0.0
        repeat(8) { s.step(1000, NOON + 20_000L + it * 1000L) }
        assertEquals(0.0, s.speedMph)
    }

    @Test fun fixLooksLikeAPhoneGps() {
        val s = sim(heading = 90.0)
        val parked = s.step(1000, NOON)
        assertEquals(NOON, parked.timeMillis)
        assertEquals(0.0, parked.speedMps)
        assertNull(parked.bearingDeg, "no bearing while stopped")
        assertEquals(3.0, parked.accuracyM)
        s.targetSpeedMph = 4.0
        repeat(10) { s.step(1000, NOON + it * 1000L) }
        val moving = s.fix(NOON + 10_000)
        assertEquals(4.0, moving.speedMph!!, 1e-6)
        assertEquals(90.0, moving.bearingDeg!!, 1e-6)
        assertEquals(s.latLon, moving.position)
    }

    @Test fun turnsAtSteerRateButNotInPlace() {
        val s = sim(heading = 0.0)
        s.steerRateDegPerSec = 25.0
        s.step(4000, NOON)
        assertEquals(0.0, s.headingDeg, 1e-9, "a parked car can't turn")
        s.steerRateDegPerSec = 0.0
        s.targetSpeedMph = 5.0
        repeat(10) { s.step(1000, NOON + it * 1000L) }
        s.steerRateDegPerSec = 25.0
        s.step(4000, NOON + 20_000)
        assertEquals(100.0, s.headingDeg, 1e-6)
        s.steerRateDegPerSec = -25.0
        s.step(2000, NOON + 22_000)
        assertEquals(50.0, s.headingDeg, 1e-6)
        // Full circle at 25°/s: back near where the circle started, radius v/ω.
        s.steerRateDegPerSec = 25.0
        val before = s.position
        s.step(14_400, NOON + 40_000)
        assertTrue(s.position.distanceTo(before) < 1.0, "circle closes: ${s.position.distanceTo(before)}")
    }

    @Test fun gpsNoiseIsSmallAndReproducible() {
        fun run(seed: Long) = VehicleSim.withGpsNoise(d, start, 45.0, seed).let { s ->
            s.targetSpeedMph = 5.0
            (1..30).map { s.step(1000, NOON + it * 1000L) to s.position }
        }
        val a = run(9)
        val b = run(9)
        assertEquals(a.map { it.first }, b.map { it.first })
        assertTrue(a.map { it.first } != run(10).map { it.first })
        for ((fix, truth) in a) {
            assertTrue(d.xy(fix.position).distanceTo(truth) <= 1.5 * Math.sqrt(2.0) + 1e-6)
        }
        for ((fix, _) in a.drop(10)) {
            assertEquals(5.0, fix.speedMph!!, 0.05 + 1e-9)
            assertTrue(bearingDiff(fix.bearingDeg!!, 45.0) <= 1.0 + 1e-9)
        }
    }

    // --- Autopilot -------------------------------------------------------------------

    private fun drive(s: VehicleSim, pilot: Autopilot, maxSeconds: Int = 600, untilStopped: Boolean = true): List<Xy> {
        s.driver = pilot
        val track = ArrayList<Xy>()
        var t = NOON
        for (i in 0 until maxSeconds) {
            s.step(1000, t)
            t += 1000
            track += s.position
            if (pilot.finished && (!untilStopped || s.speedMph == 0.0)) break
        }
        return track
    }

    @Test fun autopilotFollowsAPolylineAndStopsAtItsEnd() {
        // An L with a 90° corner, then a gentle curve; the car starts facing the wrong way.
        val corner = start + Xy(300.0, 400.0)
        val curve = (1..10).map { i -> corner + Xy(30.0 * i, -1.5 * i * i) }
        val path = listOf(start, start + Xy(0.0, 400.0), corner) + curve
        val s = sim(heading = 180.0)
        val pilot = Autopilot(path, 5.0)
        val track = drive(s, pilot)
        assertTrue(pilot.finished)
        assertEquals(0.0, s.speedMph)
        assertTrue(s.position.distanceTo(path.last()) <= 20.0, "stopped ${s.position.distanceTo(path.last())} ft from the end")
        // After the initial turn-around, it stays close to the path (corners are cut by < lookahead).
        val line = Polyline(path)
        // (Turning around at the start swings out like a real car would.)
        assertTrue(track.maxOf { line.distanceTo(it) } < 80.0)
        val worst = track.drop(60).maxOf { line.distanceTo(it) }
        assertTrue(worst < 25.0, "max deviation $worst ft")
        assertTrue(pilot.fraction > 0.95)
    }

    @Test fun autopilotWithoutStopKeepsRolling() {
        val path = listOf(start, start + Xy(500.0, 0.0))
        val s = sim(heading = 90.0)
        s.teleport(start, 90.0, 4.0)
        val pilot = Autopilot(path, 4.0, stopAtEnd = false)
        drive(s, pilot, untilStopped = false)
        assertTrue(pilot.finished)
        assertEquals(4.0, s.speedMph, 1e-6)
        assertTrue(s.position.distanceTo(path.last()) <= 20.0)
    }

    @Test fun autopilotHandlesAUTurn() {
        val out = start + Xy(0.0, 200.0)
        val turn = SimTour.uTurnLeft(out, 0.0, 15.0)
        val path = listOf(start) + turn + listOf(turn.last() + Xy(0.0, -200.0))
        val s = sim(heading = 0.0)
        val pilot = Autopilot(path, 4.0)
        drive(s, pilot)
        assertTrue(pilot.finished)
        assertTrue(s.position.distanceTo(path.last()) <= 20.0, "${s.position.distanceTo(path.last())}")
        assertTrue(bearingDiff(s.headingDeg, 180.0) < 20.0, "heading ${s.headingDeg}")
    }

    @Test fun uTurnGeometry() {
        val p = Xy(0.0, 0.0)
        val turn = SimTour.uTurnLeft(p, 90.0, 15.0)
        assertEquals(p.x, turn.first().x, 1e-9)
        assertEquals(p.y, turn.first().y, 1e-9)
        // Heading east, a left U-turn ends 30 ft to the north.
        assertEquals(0.0, turn.last().x, 1e-9)
        assertEquals(30.0, turn.last().y, 1e-9)
        assertTrue(signedTurn(90.0, com.zepdriver.core.geo.bearingOf(turn[1] - turn[0])) < 0, "turns left")
    }

    @Test fun fixHasBearingOnlyWhileMoving() {
        val s = sim()
        s.targetSpeedMph = 0.2
        repeat(3) { s.step(1000, NOON + it * 1000L) }
        assertNull(s.fix(NOON).bearingDeg)
        s.targetSpeedMph = 1.0
        repeat(3) { s.step(1000, NOON + it * 1000L) }
        assertNotNull(s.fix(NOON).bearingDeg)
    }
}
