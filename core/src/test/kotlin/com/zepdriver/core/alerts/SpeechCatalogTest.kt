package com.zepdriver.core.alerts

import com.zepdriver.core.TestData
import com.zepdriver.core.profile.LicenseClass
import com.zepdriver.core.profile.LicenseHours
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.DriveMonitor
import com.zepdriver.core.rules.RuleSet2026
import com.zepdriver.core.sim.PlayaSimulator
import com.zepdriver.core.sim.SimMode
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertTrue

class SpeechCatalogTest {
    private val d = TestData.playa2026
    private val catalog = Speech.catalog(d, RuleSet2026).toSet()

    @Test fun catalogCoversCoreWarnings() {
        assertTrue(Speech.SLOW_DOWN_ALARM in catalog)
        assertTrue("Get out of the Great Circle" in catalog)
        assertTrue(catalog.any { it.startsWith("Crossing the Esplanade") })
        assertTrue(catalog.size in 15..200, "${catalog.size} phrases")
        assertTrue(catalog.map(::speechClipId).toSet().size == catalog.size, "clip ids are unique")
    }

    /** Everything the monitor says on the full simulated tour can be pre-recorded. */
    @Test fun everySpokenWarningIsInTheCatalog() {
        val profiles = listOf(
            VehicleProfile(),
            VehicleProfile(licenseClass = LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY, licenseHours = LicenseHours.DAY),
        )
        // Evening, so the day-only license warns too.
        val start = ZonedDateTime.of(2026, 9, 2, 21, 0, 0, 0, ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli()
        for (profile in profiles) {
            val sim = PlayaSimulator(d, SimMode.TOUR, seed = 3)
            val monitor = DriveMonitor(d, RuleSet2026, profile)
            val spoken = mutableSetOf<String>()
            for (i in 0 until 3_000) {
                monitor.update(sim.step(1000, start + i * 1000L)).alerts.mapNotNullTo(spoken) { it.speech }
                if (sim.tour!!.finished && sim.vehicle.speedMph == 0.0) break
            }
            assertTrue(spoken.size >= 4, "spoken: $spoken")
            val missing = spoken - catalog
            assertTrue(missing.isEmpty(), "not in catalog: $missing")
        }
    }
}
