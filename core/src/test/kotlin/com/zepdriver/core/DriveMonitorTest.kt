package com.zepdriver.core

import com.zepdriver.core.DriveSim.NOON
import com.zepdriver.core.DriveSim.arc
import com.zepdriver.core.DriveSim.at
import com.zepdriver.core.DriveSim.fix
import com.zepdriver.core.DriveSim.line
import com.zepdriver.core.DriveSim.straight
import com.zepdriver.core.DriveSim.xy
import com.zepdriver.core.alerts.Alert
import com.zepdriver.core.alerts.AlertKind
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.brc.Clock
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.bearingOf
import com.zepdriver.core.profile.LicenseClass
import com.zepdriver.core.profile.LicenseHours
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.DriveMonitor
import com.zepdriver.core.rules.DriveState
import com.zepdriver.core.rules.DrivingMode
import com.zepdriver.core.rules.RuleSet2026
import com.zepdriver.core.rules.ZoneMonitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveMonitorTest {
    private val d = TestData.playa2026

    private fun monitor(profile: VehicleProfile = VehicleProfile()) = DriveMonitor(d, RuleSet2026, profile)
    private fun run(fixes: List<Fix>, m: DriveMonitor = monitor(), navigating: Boolean = false) =
        fixes.map { m.update(it, navigating) }

    private fun DriveState.zone(kind: AlertKind): Alert? = alerts.firstOrNull { it.kind == kind }
    private fun List<DriveState>.kinds() = flatMap { s -> s.alerts.map { it.kind } }.toSet()

    // --- Cross-only roads -------------------------------------------------------------

    @Test fun drivingAlongEsplanadeAlarms() {
        val states = run(arc("4:00", 2500.0, 4.5, 15))
        val first = states.indexOfFirst { it.zone(AlertKind.DRIVING_ALONG) != null }
        assertTrue(first in 4..8, "alarm at $first s")
        val alarm = states[first].zone(AlertKind.DRIVING_ALONG)!!
        assertEquals(AlertLevel.ALARM, alarm.level)
        assertEquals("Driving on Esplanade", alarm.title)
        assertEquals("You're driving on the Esplanade. Get off it.", alarm.speech)
        assertEquals("zone:esplanade:driving_along", alarm.key)
        // Before the alarm: a soft reminder only.
        assertEquals(AlertLevel.ADVISORY, states[1].zone(AlertKind.CROSSING)?.level)
        assertTrue(states.drop(first).all { it.zone(AlertKind.DRIVING_ALONG) != null })
        assertEquals(AlertLevel.ALARM, states.last().topLevel)
        assertTrue(states.last().address!!.endsWith("& Esplanade"), states.last().address)
    }

    @Test fun crossingEsplanadePerpendicularIsOnlyAdvisory() {
        val outward = Clock.toBearing(d.layout, 270.0)
        val states = run(straight(xy("4:30", 2380.0), outward, 4.5, 40))
        val crossing = states.mapNotNull { it.zone(AlertKind.CROSSING) }
        assertTrue(crossing.isNotEmpty())
        assertEquals("Crossing Esplanade", crossing.first().title)
        assertEquals("Crossing the Esplanade. Don't drive along it.", crossing.first().speech)
        assertTrue(states.all { it.topLevel <= AlertLevel.ADVISORY }, states.map { it.alerts }.toString())
        assertFalse(AlertKind.DRIVING_ALONG in states.kinds())
    }

    @Test fun diagonalCrossingIsOnlyAdvisory() {
        // 50° to the road axis: still a crossing.
        val p = xy("8:00", 2440.0)
        val outward = Clock.toBearing(d.layout, 480.0)
        val states = run(straight(p, outward + 40, 4.5, 25))
        assertTrue(AlertKind.CROSSING in states.kinds())
        assertFalse(AlertKind.DRIVING_ALONG in states.kinds())
    }

    @Test fun playaSideOfEsplanadeIsFine() {
        // The open-playa side of the Esplanade is legal; 40 ft inside the centerline.
        val states = run(arc("4:00", 2460.0, 4.5, 20))
        assertTrue(states.all { it.alerts.isEmpty() }, states.flatMap { it.alerts }.toString())
    }

    @Test fun shallowApproachWarnsSoftly() {
        // Converging on the Esplanade at ~15°, from 60 ft inside.
        val start = xy("4:00", 2455.0)
        val tangent = Clock.toBearing(d.layout, 240.0) + 90
        val states = run(straight(start, tangent - 15, 4.5, 10))
        val adv = states.mapNotNull { it.zone(AlertKind.CROSSING) }
        assertTrue(adv.isNotEmpty())
        assertEquals("Esplanade — cross only", adv.first().title)
        assertTrue(states.all { it.topLevel <= AlertLevel.ADVISORY })
    }

    @Test fun drivingAlongPromenadeAlarmsButCrossingDoesNot() {
        val inward = Clock.toBearing(d.layout, 180.0) + 180
        val along = run(straight(xy("3:00", 1800.0), inward, 4.0, 12))
        assertTrue(along.last().alerts.any { it.kind == AlertKind.DRIVING_ALONG && it.title == "Driving on Promenade" })
        // Crossing the 3:00 promenade on a circle around the Man.
        val across = run(arc("2:50", 1500.0, 4.0, 25))
        assertTrue(AlertKind.CROSSING in across.kinds())
        assertFalse(AlertKind.DRIVING_ALONG in across.kinds())
    }

    // --- No-entry areas ---------------------------------------------------------------

    @Test fun approachingGreatCircleWarnsThenAlarms() {
        // Phone mounted 20 ft behind the front bumper.
        val m = monitor(VehicleProfile(phoneFromFrontFt = 20.0))
        val states = run(line(xy("4:30", 700.0), xy("4:30", 50.0), 4.0), m)
        val warn = states.indexOfFirst { it.zone(AlertKind.NO_ENTRY_APPROACH) != null }
        val alarm = states.indexOfFirst { it.zone(AlertKind.NO_ENTRY_INSIDE) != null }
        assertTrue(warn > 0 && alarm > warn, "warn $warn alarm $alarm")
        val w = states[warn].zone(AlertKind.NO_ENTRY_APPROACH)!!
        assertEquals(AlertLevel.WARNING, w.level)
        assertEquals("Great Circle ahead", w.title)
        assertEquals("The Great Circle ahead. Turn away.", w.speech)
        // Warned several seconds before entering (8 s lookahead at 4 mph ≈ 47 ft).
        assertTrue(alarm - warn >= 5, "warn $warn alarm $alarm")
        val a = states[alarm].zone(AlertKind.NO_ENTRY_INSIDE)!!
        assertEquals(AlertLevel.ALARM, a.level)
        assertEquals("Inside Great Circle", a.title)
        assertEquals("Get out of the Great Circle", a.speech)
        assertNotNull(a.detail)
        // The front of the vehicle enters first: the alarm sounds while the phone is still outside.
        val greatCircle = d.plazas.first { it.name == "Great Circle" }.polygon
        assertFalse(greatCircle.contains(d.xy(states[alarm].fix!!.position)))
        // Once inside, the approach warning is replaced, not shown alongside.
        assertNull(states[alarm + 1].zone(AlertKind.NO_ENTRY_APPROACH))
        assertEquals("Great Circle", states.last().address)
    }

    @Test fun drivingAwayOrPastGreatCircleIsQuiet() {
        val outward = Clock.toBearing(d.layout, 270.0)
        assertTrue(run(straight(xy("4:30", 230.0), outward, 4.0, 15)).all { it.alerts.isEmpty() })
        assertTrue(run(arc("4:00", 270.0, 4.0, 15)).all { it.alerts.isEmpty() })
    }

    @Test fun centerCampAndTrashFence() {
        val toCc = run(line(xy("6:00", 2620.0), xy("6:00", 2850.0), 3.0))
        assertTrue(toCc.any { it.zone(AlertKind.NO_ENTRY_INSIDE)?.title == "Inside Center Camp Plaza" })

        val fence = d.fence
        val pastFence = xy("12:00", 9000.0)
        assertFalse(fence.contains(pastFence))
        val s = monitor().update(fix(NOON, pastFence, 2.0, 0.0))
        val a = s.zone(AlertKind.NO_ENTRY_INSIDE)!!
        assertEquals("Outside the trash fence", a.title)
        assertEquals("Outside the fence", s.address)
    }

    @Test fun alertLingersBrieflyAfterLeaving() {
        val m = ZoneMonitor(d, RuleSet2026.zones(d), VehicleProfile())
        val inside = xy("4:30", 100.0)
        val outside = xy("4:30", 400.0)
        assertTrue(m.update(NOON, inside, null, 0.0).any { it.kind == AlertKind.NO_ENTRY_INSIDE })
        assertTrue(m.update(NOON + 1000, outside, null, 0.0).any { it.kind == AlertKind.NO_ENTRY_INSIDE })
        assertTrue(m.update(NOON + 2000, outside, null, 0.0).any { it.kind == AlertKind.NO_ENTRY_INSIDE })
        assertTrue(m.update(NOON + 3500, outside, null, 0.0).isEmpty())
    }

    // --- License ----------------------------------------------------------------------

    @Test fun playaOnlyCruisingCityWarnsThenAlarms() {
        val m = monitor(VehicleProfile(licenseClass = LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY))
        val states = run(arc("4:05", 4060.0 + 140, 4.0, 30), m)
        val license = states.mapNotNull { it.zone(AlertKind.LICENSE_AREA) }
        assertEquals(AlertLevel.WARNING, license.first().level)
        assertEquals("Playa-only license: no city driving", license.first().title)
        assertNotNull(license.first().speech)
        assertEquals(AlertLevel.ALARM, states.last().zone(AlertKind.LICENSE_AREA)!!.level)
        // A full Mutant Vehicle license may drive there.
        assertFalse(AlertKind.LICENSE_AREA in run(arc("4:05", 4200.0, 4.0, 30)).kinds())
    }

    @Test fun playaOnlyOnHomeRouteIsAdvisory() {
        val home = d.latLon(xy("4:30", 4825.0 + 60))
        val m = monitor(VehicleProfile(licenseClass = LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY, homeCamp = home))
        // Drive out of camp along the 4:30 avenue to the playa.
        val states = run(line(xy("4:30", 4870.0), xy("4:30", 2300.0), 4.5), m)
        val license = states.mapNotNull { it.zone(AlertKind.LICENSE_AREA) }
        assertTrue(license.isNotEmpty())
        assertTrue(license.all { it.level == AlertLevel.ADVISORY && it.title == "Direct route to camp only" }, "$license")
        // Turning onto a ring road leaves the route.
        val m2 = monitor(VehicleProfile(licenseClass = LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY, homeCamp = home))
        val off = run(arc("4:30", 3495.0, 4.0, 20), m2)
        assertEquals(AlertLevel.WARNING, off.last().zone(AlertKind.LICENSE_AREA)?.level)
    }

    @Test fun parkedPlayaOnlyInCampIsQuiet() {
        val m = monitor(VehicleProfile(licenseClass = LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY))
        val p = xy("7:00", 4600.0)
        val states = (0..30).map { m.update(fix(NOON + it * 1000L, p, 0.0, null)) }
        assertTrue(states.all { it.alerts.isEmpty() }, states.last().alerts.toString())
        assertEquals(DrivingMode.PARKED, states.last().mode)
    }

    @Test fun profileChangeTakesEffect() {
        val m = monitor()
        val fixes = arc("4:05", 4200.0, 4.0, 10)
        fixes.take(5).forEach { assertNull(m.update(it).zone(AlertKind.LICENSE_AREA)) }
        m.profile = VehicleProfile(licenseClass = LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY)
        val s = fixes.drop(5).map { m.update(it) }
        assertNotNull(s.last().zone(AlertKind.LICENSE_AREA))
    }

    @Test fun licenseHours() {
        val dayLicense = VehicleProfile(licenseHours = LicenseHours.DAY)
        val night = run(arc("12:00", 3500.0, 4.0, 5, t0 = at(1, 23)), monitor(dayLicense))
        assertTrue(night.last().isNight)
        assertEquals(DrivingMode.MOVING, night.last().mode)
        val a = night.last().zone(AlertKind.LICENSE_HOURS)!!
        assertEquals(AlertLevel.WARNING, a.level)
        assertEquals("Day license: no driving at night", a.title)
        assertNull(night[0].zone(AlertKind.LICENSE_HOURS)) // still PARKED

        val day = run(arc("12:00", 3500.0, 4.0, 5), monitor(dayLicense))
        assertFalse(day.last().isNight)
        assertNull(day.last().zone(AlertKind.LICENSE_HOURS))

        val nightLicense = run(arc("12:00", 3500.0, 4.0, 5), monitor(VehicleProfile(licenseHours = LicenseHours.NIGHT)))
        assertEquals("Night license: no driving in daylight", nightLicense.last().zone(AlertKind.LICENSE_HOURS)!!.title)
    }

    // --- Sound and caution ------------------------------------------------------------

    @Test fun quietHoursInCity() {
        val s = run(arc("8:00", 4200.0, 3.0, 3, t0 = at(2, 3)))
        val a = s.last().zone(AlertKind.SOUND)!!
        assertEquals(AlertLevel.ADVISORY, a.level)
        assertEquals("zone:quiet-hours:sound", a.key)
        assertTrue(a.speech!!.contains("2 am"))
        assertNull(run(arc("8:00", 4200.0, 3.0, 3)).last().zone(AlertKind.SOUND))
        // Not for vehicles without a sound system.
        val quiet = monitor(VehicleProfile(hasSoundSystem = false))
        assertNull(run(arc("8:00", 4200.0, 3.0, 3, t0 = at(2, 3)), quiet).last().zone(AlertKind.SOUND))
    }

    @Test fun plazaIsCautionWithoutSpeech() {
        val plaza = d.plazas.first { it.name == "4:30 & G Plaza" }.polygon.centroid()
        val s = monitor().update(fix(NOON, plaza, 2.0, null))
        val a = s.alerts.single()
        assertEquals(AlertLevel.CAUTION, a.level)
        assertNull(a.speech)
        assertEquals("4:30 & G Plaza", a.title)
    }

    // --- Misc -------------------------------------------------------------------------

    @Test fun speedAndGpsAlertsSortedBySeverity() {
        val m = monitor()
        val p = xy("12:00", 3500.0)
        var s: DriveState? = null
        for (i in 0..3) s = m.update(fix(NOON + i * 1000L, p, 7.0, 0.0, accuracyM = 30.0))
        val st = s!!
        assertEquals(AlertLevel.ALARM, st.speedLevel)
        assertEquals(listOf(AlertKind.SPEED, AlertKind.GPS), st.alerts.map { it.kind })
        assertEquals(AlertLevel.CAUTION, st.alerts[1].level)
        assertEquals(7.0, st.speedMph, 1e-6)
        assertEquals("Deep playa 12:00 @ 3,500 ft", st.address)
    }

    @Test fun startsParkedAndMovesAfterDwell() {
        val states = run(arc("12:00", 3500.0, 4.0, 4))
        assertEquals(DrivingMode.PARKED, states[0].mode)
        assertEquals(DrivingMode.MOVING, states.last().mode)
        assertEquals(DrivingMode.MOVING, monitor().update(fix(NOON, xy("12:00", 3500.0), 0.0, null), navigating = true).mode)
    }

    @Test fun headingFromMotionWhenNoBearing() {
        // Same Esplanade drive, but the platform reports no bearing and no Doppler speed.
        val fixes = arc("4:00", 2500.0, 4.5, 15).map { it.copy(bearingDeg = null, speedMps = null) }
        val states = run(fixes)
        assertTrue(states.last().alerts.any { it.kind == AlertKind.DRIVING_ALONG })
    }

    @Test fun fastEnoughForAPhone() {
        val m = monitor(VehicleProfile(licenseClass = LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY, homeCamp = d.latLon(xy("4:30", 4825.0))))
        // A loop through city, Esplanade and inner playa.
        val fixes = arc("2:00", 2500.0, 5.0, 300) + arc("2:00", 4200.0, 5.0, 300, t0 = NOON + 400_000) +
            straight(xy("6:00", 2400.0), bearingOf(xy("6:00", 300.0) - xy("6:00", 2400.0)), 5.0, 300, t0 = NOON + 800_000)
        val warmUp = monitor(m.profile)
        fixes.forEach { warmUp.update(it) }
        val t0 = System.nanoTime()
        fixes.forEach { m.update(it) }
        val perUpdateMs = (System.nanoTime() - t0) / 1e6 / fixes.size
        assertTrue(perUpdateMs < 3.0, "per update $perUpdateMs ms")
    }
}
