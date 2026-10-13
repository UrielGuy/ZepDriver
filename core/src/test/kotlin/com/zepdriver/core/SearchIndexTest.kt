package com.zepdriver.core

import com.zepdriver.core.brc.BrcAddressing
import com.zepdriver.core.nav.DestinationKind
import com.zepdriver.core.search.SearchIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchIndexTest {
    private val d = TestData.playa2026
    private val addressing = BrcAddressing(d)
    private val index = SearchIndex(d, addressing)

    @Test fun findsCampByPartialName() {
        val r = index.search("snugg")
        assertEquals("Snuggles", r.first().destination.name)
        assertEquals(DestinationKind.CAMP, r.first().destination.kind)
        assertEquals("a1XVI00000FBBVz2AP", r.first().destination.refId)
        // Data address "G & 9:15" is shown clock-first.
        assertEquals("9:15 & G", r.first().destination.address)
        assertEquals("Camp · 9:15 & G", r.first().subtitle)
        assertTrue(r.first().description!!.isNotEmpty())
    }

    @Test fun findsArtByPartialNameAndArtist() {
        val r = index.search("electric dand")
        assertEquals("Electric Dandelion", r.first().destination.name)
        assertEquals(DestinationKind.ART, r.first().destination.kind)
        assertTrue(r.first().subtitle.startsWith("Art · Outer playa 4:45 @ "), r.first().subtitle)
        assertEquals("Electric Dandelion", index.search("Abram Santa Cruz").first().destination.name)
        // Word prefix inside the name.
        assertTrue(index.search("dandel").any { it.destination.name == "Electric Dandelion" })
        // Words in any order.
        assertEquals("Electric Dandelion", index.search("dandelion electric").first().destination.name)
    }

    @Test fun deepPlayaArtSubtitle() {
        val r = index.search("Center of the Universe").first()
        assertEquals("Center of the Universe", r.destination.name)
        assertTrue(Regex("""Art · Deep playa 12:\d\d @ [\d,]+ ft""").matches(r.subtitle), r.subtitle)
    }

    @Test fun toleratesCaseAccentsAndPunctuation() {
        assertEquals("Après-Ski", index.search("apres ski").first().destination.name)
        assertEquals("Fjörgyn's Lair", index.search("FJORGYNS").first().destination.name)
        assertEquals("Burning My Crêpe", index.search("burning my crepe").first().destination.name)
        assertTrue(index.search("dare does art").any { it.destination.name.startsWith("D.A.R.E") })
    }

    @Test fun rankingPrefersExactThenPrefixThenSubstringThenDescription() {
        val r = index.search("cafe").map { it.destination.name }
        // Names containing the word "café" come before description-only matches.
        val firstDescOnly = r.indexOfFirst { !SearchIndex.normalize(it).contains("cafe") }
        val lastNamed = r.indexOfLast { SearchIndex.normalize(it).contains("cafe") }
        assertTrue(firstDescOnly == -1 || lastNamed < firstDescOnly, r.toString())
        val pre = index.search("lotus").map { it.destination.name }
        assertTrue(pre.indexOf("Lotus Dome & Café") >= 0)
    }

    @Test fun potties() {
        for (q in listOf("potty", "Potties", "toilet", "porta", "restroom", "Porta-potty", "bathrooms")) {
            val r = index.search(q)
            assertEquals(DestinationKind.TOILET, r.first().destination.kind, q)
            assertTrue(r.count { it.destination.kind == DestinationKind.TOILET } == d.toilets.size, q)
        }
        val t = index.search("potty").first()
        assertTrue(t.destination.name.startsWith("Porta-potties "))
        // City banks read like "Porta-potties 3:00 & B".
        assertTrue(index.search("potty").any { Regex("""Porta-potties \d{1,2}:\d\d & [A-K]""").matches(it.destination.name) })
        // Not every "port" word is a potty.
        assertEquals("300 Portal", index.search("300 portal").first().destination.name)
    }

    @Test fun pottiesNearestFirstWithOrigin() {
        val here = d.latLon(DriveSim.xy("4:30", 3300.0))
        val r = index.search("potty", near = here).filter { it.destination.kind == DestinationKind.TOILET }
        assertEquals(d.toilets.size, r.size)
        val dists = r.map { d.xy(it.destination.position).distanceTo(d.xy(here)) }
        assertEquals(dists.sorted(), dists)
    }

    @Test fun addressQueryComesFirst() {
        val r = index.search("4:30 & G")
        val top = r.first()
        assertEquals(DestinationKind.ADDRESS, top.destination.kind)
        assertEquals("4:30 & G", top.destination.name)
        assertTrue(d.xy(top.destination.position).distanceTo(DriveSim.xy("4:30", 4825.0)) < 1)
        // Camps at that intersection follow.
        val g915 = index.search("G & 9:15")
        assertEquals(DestinationKind.ADDRESS, g915.first().destination.kind)
        assertTrue(g915.any { it.destination.name == "Snuggles" })
        assertEquals("2:00 @ 3,400 ft", index.search("2:00 3400'").first().destination.name)
    }

    @Test fun pois() {
        val r = index.search("ranger hq").first()
        assertEquals(DestinationKind.POI, r.destination.kind)
        assertEquals("Ranger HQ", r.destination.name)
        assertTrue(r.subtitle.startsWith("Place · "))
        // A leading "The" doesn't stop an exact match from ranking first.
        assertEquals("The Man", index.search("man").first().destination.name)
        assertEquals("Place · Great Circle", index.search("the man").first().subtitle)
    }

    @Test fun emptyAndLimit() {
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("  ,. ").isEmpty())
        assertEquals(5, index.search("camp", limit = 5).size)
        assertTrue(index.search("zzzxqqq").isEmpty())
    }

    @Test fun fast() {
        index.search("warm")
        val t0 = System.nanoTime()
        repeat(50) { index.search("dust") }
        val ms = (System.nanoTime() - t0) / 1e6 / 50
        assertTrue(ms < 20, "search took $ms ms")
    }
}
