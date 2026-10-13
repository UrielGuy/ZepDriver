package com.zepdriver.core.landmarks

import com.zepdriver.core.TestData
import com.zepdriver.core.brc.Clock
import com.zepdriver.core.geo.Polygon
import kotlin.test.Test
import kotlin.test.assertTrue

class LandmarksTest {
    private val d = TestData.playa2026
    private val prisms = Landmarks.build(d)

    private fun of(name: String) = prisms.filter { it.landmark == name }

    @Test fun allThreeLandmarksExist() {
        for (name in listOf(Landmarks.MAN, Landmarks.TEMPLE, Landmarks.CENTER_CAMP)) {
            assertTrue(of(name).isNotEmpty(), name)
        }
        assertTrue(prisms.all { it.topFt > it.baseFt && it.baseFt >= 0 && it.outer.size >= 3 })
    }

    @Test fun manStandsOnTheTowerAtTheMan() {
        val man = of(Landmarks.MAN)
        val figure = man.filter { it.part == LandmarkPart.FIGURE }
        assertTrue(figure.minOf { it.baseFt } >= 60.0, "the Man stands on the 60 ft platform")
        assertTrue(man.maxOf { it.topFt } in 95.0..120.0)
        assertTrue(man.all { p -> p.outer.all { it.length() < 90 } }, "within ~90 ft of the golden spike")
        // Fits inside the Great Circle.
        val greatCircle = d.plazas.first { it.name == "Great Circle" }.polygon
        assertTrue(man.all { p -> p.outer.all { greatCircle.contains(it) } })
    }

    @Test fun templeAndCanopyAreWhereTheyBelong() {
        val temple = of(Landmarks.TEMPLE)
        assertTrue(temple.all { p -> p.outer.all { it.distanceTo(d.templeXy) < 115 } })
        val cc = d.layout.centerCamp
        val ccXy = Clock.point(d.layout, Clock.parse(cc.clock)!!.toDouble(), cc.distanceFt)
        val canopy = of(Landmarks.CENTER_CAMP)
        assertTrue(canopy.all { p -> p.outer.all { it.distanceTo(ccXy) < 125 } })
        // The Canopy sits inside the Center Camp plaza.
        val plaza: Polygon = d.plazas.first { it.name == "Center Camp Plaza" }.polygon
        assertTrue(canopy.all { p -> p.outer.all { plaza.contains(it) } })
    }
}
