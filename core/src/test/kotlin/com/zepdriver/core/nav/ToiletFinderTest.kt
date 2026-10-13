package com.zepdriver.core.nav

import com.zepdriver.core.TestData
import com.zepdriver.core.brc.Clock
import com.zepdriver.core.geo.bearingDiff
import com.zepdriver.core.geo.bearingOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToiletFinderTest {
    private val d = TestData.playa2026
    private val finder = ToiletFinder(d)

    @Test fun findsClosestBankAndPointsAtIt() {
        val here = Clock.point(d.layout, Clock.parse("4:30")!!.toDouble(), 3600.0)
        val hit = assertNotNull(finder.nearest(d.latLon(here)))
        val bruteForce = d.toilets.minOf { it.polygon.distanceTo(here) }
        assertEquals(bruteForce, hit.distanceFt, 1e-6)
        val center = d.xy(hit.destination.position)
        assertTrue(bearingDiff(bearingOf(center - here), hit.bearingDeg) < 0.5)
        assertEquals(DestinationKind.TOILET, hit.destination.kind)
    }

    @Test fun insideABankIsZeroFeet() {
        val bank = d.toilets.first()
        val hit = assertNotNull(finder.nearest(d.latLon(bank.center)))
        assertEquals(0.0, hit.distanceFt, 1e-6)
    }
}
