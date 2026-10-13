package com.zepdriver.core

import com.zepdriver.core.DriveSim.xy
import com.zepdriver.core.brc.BrcAddressing
import com.zepdriver.core.brc.Clock
import com.zepdriver.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrcAddressingTest {
    private val d = TestData.playa2026
    private val a = BrcAddressing(d)

    private fun describe(clock: String, ft: Double) = a.describe(d.latLon(xy(clock, ft)))

    @Test fun cityIntersections() {
        assertEquals("4:30 & G", describe("4:30", 4825.0))
        assertEquals("7:45 & Esplanade", describe("7:45", 2500.0))
        assertEquals("10:00 & K", describe("10:00", 5755.0))
        assertEquals("2:00 & A", describe("2:00", 2935.0))
    }

    @Test fun roundsClockAndPicksNearestRing() {
        // 4:32 rounds to 4:30; 4,700 ft is closer to G (4,825) than F (4,545).
        assertEquals("4:30 & G", a.describe(d.latLon(xy(272.0, 4700.0))))
        assertEquals("4:35 & G", a.describe(d.latLon(xy(273.0, 4825.0))))
        // Between Esplanade and A.
        assertEquals("8:00 & Esplanade", describe("8:00", 2600.0))
        assertEquals("8:00 & A", describe("8:00", 2800.0))
    }

    @Test fun specialPlaces() {
        assertEquals("Great Circle", a.describe(d.latLon(d.manXy)))
        assertEquals("Center Camp", describe("6:00", 2999.0))
        assertEquals("Outside the fence", describe("12:00", 9000.0))
    }

    @Test fun playaAddresses() {
        assertEquals("Inner playa 3:15 @ 1,250 ft", describe("3:15", 1240.0))
        assertEquals("Inner playa 12:00 @ 2,450 ft", describe("12:00", 2450.0))
        assertEquals("Deep playa 12:30 @ 4,200 ft", describe("12:30", 4210.0))
        assertEquals("Deep playa 1:55 @ 3,000 ft", a.describe(d.latLon(xy(115.0, 3000.0))))
        assertEquals("Outer playa 4:00 @ 6,200 ft", describe("4:00", 6200.0))
    }

    @Test fun parsesStreetAddresses() {
        val g430 = xy("4:30", 4825.0)
        for (text in listOf(
            "4:30 & G", "G & 4:30", "4:30 and G", "4:30 G", "4:30 & g", "430 & G", "4:30 & Great Oak",
            "great oak & 4:30", "4.30 @ G", "4:30, G", "4:30&G", "  4:30   &   G  ",
        )) {
            val p = assertNotNull(a.parse(text), text)
            assertTrue(d.xy(p).distanceTo(g430) < 1, "$text -> ${d.xy(p)}")
        }
        val esp = xy("4:30", 2500.0)
        for (text in listOf("4:30 & Esplanade", "4:30 and Esp", "Esplanade & 4:30", "esp 4:30", "4:30 & ESP")) {
            assertTrue(d.xy(assertNotNull(a.parse(text), text)).distanceTo(esp) < 1, text)
        }
    }

    @Test fun parsesDistanceAddresses() {
        val target = xy("2:00", 3400.0)
        for (text in listOf("2:00 3400", "2:00 @ 3400'", "2:00 & 3,400 ft", "3400 @ 2:00", "2:00 at 3400 feet", "2:00 3400’")) {
            val p = assertNotNull(a.parse(text), text)
            assertTrue(d.xy(p).distanceTo(target) < 1, text)
        }
        assertEquals("2:00 @ 3,400 ft", a.parseAddress("2:00 3400")!!.label)
        assertEquals("4:30 & G", a.parseAddress("great oak and 430")!!.label)
        assertEquals("7:45 & Esplanade", a.parseAddress("esp & 7:45")!!.label)
    }

    @Test fun rejectsNonAddresses() {
        for (text in listOf("", "Snuggles", "4:30", "G", "13:00 & G", "4:30 & Z", "4:75 & G", "camp 10", "the 10 commandments", "4:30 & Gr")) {
            assertNull(a.parse(text), text)
        }
    }

    @Test fun roundTripCityIntersections() {
        val start = Clock.parse(d.layout.cityStartClock)!!
        val end = Clock.parse(d.layout.cityEndClock)!!
        var checked = 0
        for (ring in d.layout.rings) {
            for (clock in start..end step 15) {
                val p: LatLon = d.latLon(Clock.point(d.layout, clock.toDouble(), ring.radiusFt))
                val text = a.describe(p)
                if (text == "Center Camp") continue
                val back = assertNotNull(a.parse(text), text)
                assertTrue(d.xy(back).distanceTo(d.xy(p)) < 100, "$text ${d.xy(back)} vs ${d.xy(p)}")
                checked++
            }
        }
        assertTrue(checked > 350)
        // Off-grid points within ~100 ft too (5-minute rounding).
        for (clock in listOf(130.0, 201.0, 333.0, 452.0)) {
            val p = d.latLon(Clock.point(d.layout, clock, 4060.0))
            assertTrue(d.xy(a.parse(a.describe(p))!!).distanceTo(d.xy(p)) < 110)
        }
    }
}
