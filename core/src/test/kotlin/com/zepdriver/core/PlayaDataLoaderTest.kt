package com.zepdriver.core

import com.zepdriver.core.data.StreetKind
import com.zepdriver.core.geo.Xy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayaDataLoaderTest {
    private val d = TestData.playa2026

    @Test fun loadsAllLayers() {
        assertEquals(2026, d.layout.year)
        assertTrue(d.streets.size > 500)
        assertTrue(d.blocks.size > 200)
        assertTrue(d.toilets.size > 40)
        assertTrue(d.art.size > 300)
        assertTrue(d.camps.size > 1000)
        assertTrue(d.streets.any { it.kind == StreetKind.ANNULAR && it.name == "ESP" })
    }

    @Test fun manIsOriginAndInsideFence() {
        assertTrue(d.fence.contains(Xy(0.0, 0.0)))
        val man = d.pois.first { it.name == "The Man" }
        assertTrue(man.xy.length() < 30, "Man POI ${man.xy} should be near origin")
    }

    @Test fun esplanadeRadiusMatchesLayout() {
        val esp = d.streets.filter { it.name == "ESP" }
        val r = esp.flatMap { it.line.points }.map { it.length() }.average()
        assertTrue(kotlin.math.abs(r - 2500) < 30, "Esplanade mean radius $r")
    }
}
