package com.zepdriver.core

import com.zepdriver.core.brc.CampGeocoder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The API gives camps addresses only; check our positions against the 2026
 * bundle, whose camp coordinates came from iBurn. Theirs scatter around the
 * named corner (where on the block the camp sits); ours is the corner itself,
 * so a camp's width or two of difference is expected.
 */
class CampGeocoderTest {
    private val d = TestData.playa2026
    private val geo = CampGeocoder(d)

    @Test fun matchesIBurnPositions() {
        val errors = d.camps.mapNotNull { c ->
            geo.locate(c.address)?.let { d.xy(it).distanceTo(d.xy(c.position)) }
        }.sorted()
        val located = errors.size.toDouble() / d.camps.size
        val p90 = errors[(errors.size * 0.9).toInt()]
        println("geocoded ${errors.size}/${d.camps.size}, median ${errors[errors.size / 2].toInt()} ft, p90 ${p90.toInt()} ft, max ${errors.last().toInt()} ft")
        assertTrue(located > 0.98, "geocoded only ${(located * 100).toInt()}%")
        assertTrue(errors[errors.size / 2] < 200, "median error ${errors[errors.size / 2]} ft")
        assertTrue(p90 < 350, "p90 error $p90 ft")
    }

    @Test fun formats() {
        for (a in listOf("G & 9:15", "4:30 & D", "4:30 G Plaza @ 2:15", "Center Camp Plaza @ 6:30", "2:00 B Plaza & B",
            "10:00 & 10:00 B Plaza", "7:30 Portal & A", "Esplanade & 3:00 Portal", "6:00 Portal", "10:00 B Plaza")) {
            assertNotNull(geo.locate(a), a)
        }
        assertNull(geo.locate(null))
        assertNull(geo.locate("Airport Road"))
    }
}
