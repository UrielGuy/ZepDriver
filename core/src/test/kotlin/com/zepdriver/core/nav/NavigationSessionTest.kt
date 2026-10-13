package com.zepdriver.core.nav

import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.Xy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NavigationSessionTest {
    private val d = NavFixtures.d
    private val router get() = NavFixtures.router
    private var clock = 1_000_000L

    private fun fix(p: Xy) = Fix(clock.also { clock += 1000 }, d.latLon(p), 2.0, null, 5.0)

    /** Drives along the current leg's path, one fix every [step] ft, collecting speech. */
    private fun driveLeg(s: NavigationSession, step: Double = 40.0, speech: MutableList<String>): NavState? {
        val leg = s.state!!.plan.legs[s.state!!.legIndex]
        var last: NavState? = null
        for ((_, p) in NavFixtures.samples(NavFixtures.xy(leg.path), step).drop(1)) {
            last = s.update(fix(p))
            last?.speech?.let { speech += it }
            if (last != null && (last.legIndex != s.state!!.legIndex || last.finished)) break
        }
        return last
    }

    @Test fun advancesLegsAndFinishes() {
        val stops = listOf(
            Destination("Pin A", NavFixtures.ll("3:00", 1500.0), DestinationKind.PIN),
            NavFixtures.campNear("4:30", "D"),
        )
        val s = NavigationSession(router, NavFixtures.ll("1:30", 2000.0), stops)
        val st0 = assertNotNull(s.state)
        assertEquals(0, st0.legIndex)
        assertEquals(2, s.stops.size)
        assertTrue(st0.remainingFt > 0)
        val speech = ArrayList<String>()

        var st = driveLeg(s, speech = speech)!!
        assertTrue(speech.any { it.contains("Arrived at Pin A") }, "speech: $speech")
        assertEquals(1, st.legIndex)
        assertEquals(listOf(stops[1]), s.stops)
        assertFalse(st.finished)

        st = driveLeg(s, speech = speech)!!
        println("session speech: $speech")
        assertTrue(st.finished)
        assertTrue(s.stops.isEmpty())
        assertTrue(speech.any { it.contains("Arrived at ${stops[1].name}") })
        // Turn prompts were given ahead of maneuvers.
        assertTrue(speech.any { it.startsWith("In ") && it.contains(" feet, ") }, "speech: $speech")
        // Further updates stay finished and quiet.
        val after = s.update(fix(d.xy(stops[1].position)))!!
        assertTrue(after.finished)
        assertEquals(null, after.speech)
    }

    @Test fun progressAndNextInstruction() {
        val dest = NavFixtures.campNear("7:00", "F")
        val s = NavigationSession(router, NavFixtures.ll("6:30", 1500.0), listOf(dest))
        val leg = s.state!!.plan.legs[0]
        val pts = NavFixtures.xy(leg.path)
        val total = NavFixtures.length(pts)
        val mid = NavFixtures.samples(pts).let { it[it.size / 2] }
        val st = s.update(fix(mid.second))!!
        assertEquals(total - mid.first, st.remainingFt, 2.0)
        val next = assertNotNull(st.nextInstruction)
        assertTrue(st.distanceToNextFt >= 0)
        assertFalse(st.offRoute)
        // The next instruction lies ahead of the current position.
        val idx = leg.instructions.indexOf(next)
        val nextAlong = NavFixtures.length(pts.subList(0, next.pathIndex + 1))
        assertTrue(nextAlong >= mid.first - 1, "instruction $idx behind us")
    }

    @Test fun reroutesAfterTwoOffRouteFixes() {
        val dest = NavFixtures.campNear("8:30", "C")
        val start = NavFixtures.ll("7:00", 1500.0)
        val s = NavigationSession(router, start, listOf(dest))
        val firstPlan = s.state!!.plan
        // Wander ~400 ft off the route, away from the destination.
        val off = d.xy(start) + Xy(-400.0, -100.0).let { it }
        val a = s.update(fix(off))!!
        assertTrue(a.offRoute)
        assertTrue(a.speech?.contains(NavigationSession.REROUTING) != true)
        val c = s.update(fix(off + Xy(10.0, 0.0)))!!
        // Rerouted silently: this is open playa (prompts are spoken only in the city).
        assertTrue(c.speech?.contains(NavigationSession.REROUTING) != true)
        assertFalse(c.offRoute)
        assertTrue(c.plan != firstPlan)
        assertTrue(d.xy(c.plan.legs[0].path.first()).distanceTo(off + Xy(10.0, 0.0)) < 20)
        assertEquals(listOf(dest), s.stops)
    }

    @Test fun insertNextPutsPottiesFirst() {
        val dest = NavFixtures.campNear("5:00", "E")
        val start = NavFixtures.ll("9:00", 3300.0)
        val s = NavigationSession(router, start, listOf(dest))
        val toilet = assertNotNull(router.nearestToilet(start))
        val st = assertNotNull(s.insertNext(toilet, start))
        assertEquals(listOf(toilet, dest), s.stops)
        assertEquals(2, st.plan.legs.size)
        assertEquals(toilet, st.plan.legs[0].destination)
        assertEquals(0, st.legIndex)
    }

    @Test fun nullWhenStartIsOffPlaya() {
        val s = NavigationSession(router, LatLon(39.5296, -119.8138), listOf(NavFixtures.campNear("4:30", "G")))
        assertEquals(null, s.state)
        assertEquals(null, s.update(Fix(0, LatLon(39.5296, -119.8138), null, null, null)))
        // Once the vehicle shows up on the playa, the session plans from there.
        val st = s.update(Fix(1, NavFixtures.ll("6:30", 1500.0), null, null, null))
        assertNotNull(st)
    }

    @Test fun routeLineAlwaysStartsAtTheCar() {
        val dest = NavFixtures.campNear("8:30", "C")
        val start = NavFixtures.ll("7:00", 1500.0)
        val s = NavigationSession(router, start, listOf(dest))
        val path = s.state!!.plan.legs[0].path.map(d::xy)
        // Drive a good way along the planned path.
        val mid = path[path.size / 2]
        val st = s.update(fix(mid))!!
        assertTrue(d.xy(st.legAhead.first()).distanceTo(mid) < 1.0, "line starts at the car")
        assertTrue(st.legAhead.size < path.size + 2, "driven part is trimmed")
        assertTrue(d.xy(st.legAhead.last()).distanceTo(path.last()) < 1.0, "still ends at the stop")
        // A little off to the side: the line still starts at the car and joins the path.
        val aside = mid + Xy(30.0, 0.0)
        val st2 = s.update(fix(aside))!!
        assertTrue(d.xy(st2.legAhead.first()).distanceTo(aside) < 1.0)
    }

    @Test fun openPlayaIsSilentCityIsSpoken() {
        // Deep playa to an art-free spot on the open playa: shown, never spoken (except arrival).
        val playa = NavigationSession(router, NavFixtures.ll("12:00", 3500.0), listOf(
            Destination("Pin", NavFixtures.ll("2:30", 1200.0), DestinationKind.PIN),
        ))
        val quiet = mutableListOf<String>()
        driveLeg(playa, speech = quiet)
        assertTrue(quiet.none { it.startsWith("In ") || it == NavigationSession.REROUTING }, "playa speech: $quiet")
        assertTrue(quiet.all { it.startsWith("Arrived") }, "playa speech: $quiet")

        // Into the city: turn prompts are spoken.
        val city = NavigationSession(router, NavFixtures.ll("4:30", 2300.0), listOf(NavFixtures.campNear("5:00", "G")))
        val spoken = mutableListOf<String>()
        driveLeg(city, speech = spoken)
        assertTrue(spoken.any { it.startsWith("In ") && it.contains(" feet, ") }, "city speech: $spoken")
    }
}
