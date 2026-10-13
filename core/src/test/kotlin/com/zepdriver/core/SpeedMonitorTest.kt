package com.zepdriver.core

import com.zepdriver.core.DriveSim.NOON
import com.zepdriver.core.alerts.AlertKind
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.alerts.AlertLevel.ALARM
import com.zepdriver.core.alerts.AlertLevel.CAUTION
import com.zepdriver.core.alerts.AlertLevel.NONE
import com.zepdriver.core.alerts.AlertLevel.WARNING
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.rules.DrivingMode
import com.zepdriver.core.rules.DrivingModeDetector
import com.zepdriver.core.rules.SpeedMonitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedMonitorTest {
    private val start = DriveSim.xy("12:00", 3000.0)

    /** Feeds one fix per second at the given Doppler speeds; returns the level after each. */
    private fun levels(m: SpeedMonitor, vararg mph: Double, t0: Long = NOON): List<AlertLevel> =
        mph.mapIndexed { i, v ->
            m.update(DriveSim.fix(t0 + i * 1000L, start, v, 0.0))
            m.level
        }

    @Test fun belowLimitIsQuiet() {
        val m = SpeedMonitor()
        assertEquals(List(10) { NONE }, levels(m, *DoubleArray(10) { 5.0 }))
        assertNull(m.alert())
        assertEquals(5.0, m.speedMph, 1e-9)
    }

    @Test fun cautionNeedsDwell() {
        val m = SpeedMonitor()
        // Smoothed: 5.7 from the start; raises one second after first crossing.
        assertEquals(listOf(NONE, CAUTION, CAUTION), levels(m, 5.7, 5.7, 5.7))
        val a = m.alert()!!
        assertEquals(AlertKind.SPEED, a.kind)
        assertEquals(CAUTION, a.level)
        assertNull(a.speech)
    }

    @Test fun singleSpikeIsIgnored() {
        val m = SpeedMonitor()
        val l = levels(m, 4.0, 4.0, 4.0, 11.0, 4.0, 4.0, 4.0, 4.0)
        assertTrue(l.all { it == NONE }, "$l")
        assertEquals(4.0, m.speedMph, 1e-9)
    }

    @Test fun escalatesToAlarmAndClearsWithHysteresis() {
        val m = SpeedMonitor()
        val l = levels(m, 5.0, 5.0, 7.5, 7.5, 7.5, 7.5, 4.0, 4.0, 4.0, 4.0, 4.0, 4.0)
        // Smoothed (trimmed mean of 3): 5, 5, 5, 7.5, 7.5, 7.5, 7.5, 4, 4, ...
        assertEquals(listOf(NONE, NONE, NONE, NONE), l.subList(0, 4)) // 7.5 seen at 3 s: dwell pending
        assertEquals(ALARM, l[4]) // one second above every threshold
        assertEquals(listOf(ALARM, ALARM, ALARM, ALARM), l.subList(5, 9)) // below since 7 s: clearing takes 2 s
        assertEquals(NONE, l[9])
        assertEquals(NONE, l.last())
        val m2 = SpeedMonitor()
        levels(m2, 7.0, 7.0, 7.0)
        assertEquals(ALARM, m2.level)
        assertEquals("Slow down! Five miles an hour.", m2.alert()!!.speech)
    }

    @Test fun alarmDropsToCautionWhenSlowingSlightly() {
        val m = SpeedMonitor()
        val l = levels(m, 7.0, 7.0, 7.0, 5.6, 5.6, 5.6, 5.6, 5.6, 5.6)
        assertEquals(ALARM, l[2])
        assertEquals(ALARM, l[5]) // 5.6 since 4 s (trimmed mean), cleared at 6 s
        assertEquals(CAUTION, l[6])
        assertEquals(CAUTION, l.last())
    }

    @Test fun warningHoldsInsideHysteresisBand() {
        val m = SpeedMonitor()
        val l = levels(m, 6.2, 6.2, 6.2, 5.9, 5.9, 5.9, 5.9, 5.9, 5.9)
        assertEquals(WARNING, l[1])
        assertTrue(l.drop(1).all { it == WARNING }, "$l") // 5.9 >= 6.0 - 0.2
        assertEquals("Slow down", m.alert()!!.speech)
    }

    @Test fun derivesSpeedFromPositionsWithoutDoppler() {
        val m = SpeedMonitor()
        val ftPerSec = 10.0 // 6.82 mph
        repeat(6) { i ->
            m.update(DriveSim.fix(NOON + i * 1000L, start + Xy(ftPerSec * i, 0.0), null, null))
        }
        assertEquals(6.82, m.speedMph, 0.15)
        assertEquals(ALARM, m.level)
    }

    @Test fun ignoresDuplicateFixes() {
        val m = SpeedMonitor()
        m.update(DriveSim.fix(NOON, start, 3.0, 0.0))
        assertEquals(3.0, m.update(DriveSim.fix(NOON, start, 9.0, 0.0)), 1e-9)
    }
}

class DrivingModeDetectorTest {
    @Test fun parkedUntilMovingTwoSeconds() {
        val m = DrivingModeDetector()
        assertEquals(DrivingMode.PARKED, m.mode)
        assertEquals(DrivingMode.PARKED, m.update(0, 3.0, false))
        assertEquals(DrivingMode.PARKED, m.update(1000, 3.0, false))
        assertEquals(DrivingMode.MOVING, m.update(2000, 3.0, false))
    }

    @Test fun stopAndGoStaysMoving() {
        val m = DrivingModeDetector()
        var t = 0L
        repeat(3) { m.update(t, 4.0, false); t += 1000 }
        assertEquals(DrivingMode.MOVING, m.mode)
        repeat(8) { assertEquals(DrivingMode.MOVING, m.update(t, 0.0, false)); t += 1000 }
        repeat(2) { m.update(t, 3.0, false); t += 1000 }
        repeat(9) { assertEquals(DrivingMode.MOVING, m.update(t, 0.2, false)); t += 1000 }
        assertEquals(DrivingMode.MOVING, m.update(t, 0.2, false)) // 9 s
        t += 1000
        assertEquals(DrivingMode.PARKED, m.update(t, 0.2, false)) // 10 s
    }

    @Test fun navigationForcesMoving() {
        val m = DrivingModeDetector()
        assertEquals(DrivingMode.MOVING, m.update(0, 0.0, true))
        assertEquals(DrivingMode.MOVING, m.update(20_000, 0.0, true))
        // Route ended while stopped for a long time: parks right away.
        assertEquals(DrivingMode.PARKED, m.update(21_000, 0.0, false))
    }

    @Test fun jitterDoesNotUnpark() {
        val m = DrivingModeDetector()
        listOf(0.3, 1.8, 0.4, 1.9, 0.2, 1.6, 0.1).forEachIndexed { i, v ->
            assertEquals(DrivingMode.PARKED, m.update(i * 1000L, v, false))
        }
    }
}
