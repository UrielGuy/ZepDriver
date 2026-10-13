package com.zepdriver.core

import com.zepdriver.core.brc.Clock
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.profile.LicenseClass
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.RuleSet2026
import com.zepdriver.core.rules.ZoneKind
import com.zepdriver.core.rules.ZoneShape
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleSet2026Test {
    private val d = TestData.playa2026
    private val zones = RuleSet2026.zones(d).associateBy { it.id }

    private fun at(clock: String, ft: Double): Xy = Clock.point(d.layout, Clock.parse(clock)!!.toDouble(), ft)
    private fun inside(id: String, p: Xy) = zones[id]!!.shapes.any {
        when (it) {
            is ZoneShape.Area -> it.polygon.contains(p)
            is ZoneShape.Corridor -> it.line.distanceTo(p) <= it.halfWidthFt
            is ZoneShape.Outside -> !it.polygon.contains(p)
        }
    }

    @Test fun clockRoundTrip() {
        assertEquals(270, Clock.parse("4:30"))
        assertEquals("4:30", Clock.format(270))
        assertEquals(0, Clock.parse("12:00"))
        val p = at("4:30", 1000.0)
        assertTrue(p.y < -990, "4:30 should point due south, got $p")
        assertEquals(270.0, Clock.ofPoint(d.layout, p), 0.01)
    }

    @Test fun manAndGreatCircle() {
        assertTrue(inside("great-circle", Xy(0.0, 50.0)))
        assertFalse(inside("great-circle", at("3:00", 400.0)))
    }

    @Test fun esplanadeAndPromenades() {
        assertTrue(inside("esplanade", at("4:00", 2500.0)))
        assertFalse(inside("esplanade", at("4:00", 2450.0)))
        assertTrue(zones["promenades"]!!.shapes.size >= 4)
        assertTrue(inside("promenades", at("3:00", 1200.0)))
        assertTrue(inside("promenades", at("9:00", 1500.0)))
        assertFalse(inside("promenades", at("4:30", 1200.0)))
        assertEquals(ZoneKind.CROSS_ONLY, zones["esplanade"]!!.kind)
    }

    @Test fun centerCampFrontIsClosed() {
        assertEquals(ZoneKind.NO_ENTRY, zones["center-camp-front"]!!.kind)
        assertTrue(zones["center-camp-front"]!!.shapes.size >= 4)
        // Rod's Road where it stands in for the Esplanade, and the 6:00 road to the plaza.
        assertTrue(inside("center-camp-front", at("6:00", 2230.0)))
        assertTrue(inside("center-camp-front", at("6:00", 2650.0)))
        // The regular Esplanade and the 6:00 promenade toward the Man are unaffected.
        assertFalse(inside("center-camp-front", at("5:00", 2500.0)))
        assertFalse(inside("center-camp-front", at("6:00", 1500.0)))
    }

    @Test fun walkInAndFence() {
        assertTrue(inside("walk-in-camping", at("3:30", 6300.0)))
        assertFalse(inside("walk-in-camping", at("7:00", 6300.0)))
        assertTrue(inside("trash-fence", at("12:00", 9000.0)))
        assertFalse(inside("trash-fence", at("12:00", 3000.0)))
    }

    @Test fun cityZoneOnlyForPlayaOnly() {
        val city = zones["city-playa-only"]!!
        assertTrue(inside("city-playa-only", at("6:00", 4500.0)))
        assertFalse(inside("city-playa-only", at("12:00", 4500.0)))
        assertTrue(city.appliesTo(VehicleProfile(licenseClass = LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY)))
        assertFalse(city.appliesTo(VehicleProfile(licenseClass = LicenseClass.MUTANT_VEHICLE)))
    }

    @Test fun quietHoursAndBurnNights() {
        val tz = ZoneId.of("America/Los_Angeles")
        val quiet = zones["quiet-hours"]!!
        assertTrue(quiet.activeAt(ZonedDateTime.of(2026, 9, 2, 3, 0, 0, 0, tz).toInstant().toEpochMilli()))
        assertFalse(quiet.activeAt(ZonedDateTime.of(2026, 9, 2, 12, 0, 0, 0, tz).toInstant().toEpochMilli()))
        assertFalse(quiet.appliesTo(VehicleProfile(licenseClass = LicenseClass.ACCESSIBILITY_VEHICLE)))
        val temple = zones["temple-burn-sound"]!!
        assertTrue(temple.activeAt(ZonedDateTime.of(2026, 9, 6, 21, 0, 0, 0, tz).toInstant().toEpochMilli()))
        assertFalse(temple.activeAt(ZonedDateTime.of(2026, 9, 5, 21, 0, 0, 0, tz).toInstant().toEpochMilli()))
    }
}
