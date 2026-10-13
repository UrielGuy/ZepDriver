package com.zepdriver.core

import com.zepdriver.core.alerts.AlertKind
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.brc.Clock
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.MPH_PER_MPS
import com.zepdriver.core.profile.LicenseClass
import com.zepdriver.core.profile.SpeedLimits
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.DriveMonitor
import com.zepdriver.core.rules.RuleSet2026
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedLimitsTest {
    private val d = TestData.playa2026

    @Test fun defaultsAndCap() {
        assertEquals(SpeedLimits(5.5, 6.0, 6.5), VehicleProfile().effectiveSpeedLimits)
        assertEquals(SpeedLimits(8.0, 9.0, 10.0), VehicleProfile(licenseClass = LicenseClass.BIKE).effectiveSpeedLimits)
        val wild = VehicleProfile(speedLimits = SpeedLimits(12.0, 3.0, 25.0)).effectiveSpeedLimits
        assertTrue(wild.alarmMph <= SpeedLimits.MAX_MPH && wild.warningMph >= wild.cautionMph && wild.alarmMph >= wild.warningMph, "$wild")
    }

    /** Drives at [mph] along the open playa and returns the speed alert levels seen. */
    private fun levelsAt(mph: Double, profile: VehicleProfile): Set<AlertLevel> {
        val m = DriveMonitor(d, RuleSet2026, profile)
        val start = Clock.point(d.layout, Clock.parse("1:00")!!.toDouble(), 3500.0)
        val ftPerSec = mph / MPH_PER_MPS * 3.28084
        val out = mutableSetOf<AlertLevel>()
        for (i in 0 until 20) {
            val p = start + com.zepdriver.core.geo.Xy(ftPerSec * i, 0.0)
            val s = m.update(Fix(1_000_000L + i * 1000L, d.latLon(p), mph / MPH_PER_MPS, 90.0, 3.0))
            s.alerts.filter { it.kind == AlertKind.SPEED }.mapTo(out) { it.level }
        }
        return out
    }

    @Test fun customThresholdsApply() {
        assertTrue(AlertLevel.WARNING in levelsAt(6.2, VehicleProfile()))
        val relaxed = VehicleProfile(speedLimits = SpeedLimits(7.0, 8.0, 9.0))
        assertTrue(levelsAt(6.2, relaxed).isEmpty())
        assertTrue(AlertLevel.ALARM in levelsAt(9.5, relaxed))
    }

    @Test fun bikesMayRideTheEsplanade() {
        val bike = VehicleProfile(licenseClass = LicenseClass.BIKE)
        val zones = RuleSet2026.zones(d).filter { it.appliesTo(bike) }.map { it.id }.toSet()
        assertTrue("esplanade" !in zones && "promenades" !in zones && "great-circle" !in zones)
        assertTrue("trash-fence" in zones && "walk-in-camping" in zones)
    }
}
