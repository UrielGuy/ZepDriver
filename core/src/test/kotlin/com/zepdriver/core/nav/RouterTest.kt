package com.zepdriver.core.nav

import com.zepdriver.core.TestData
import com.zepdriver.core.brc.Clock
import com.zepdriver.core.data.StreetKind
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.Polygon
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.profile.LicenseClass
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.HomeCorridor
import com.zepdriver.core.rules.RuleSet2026
import com.zepdriver.core.rules.ZoneShape
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared, expensive fixtures (one grid per profile). */
internal object NavFixtures {
    val d = TestData.playa2026
    val router: Router by lazy { Router(d, RuleSet2026, VehicleProfile()) }
    val playaOnly: Router by lazy {
        Router(d, RuleSet2026, VehicleProfile(licenseClass = LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY))
    }

    fun at(clock: String, ft: Double): Xy = Clock.point(d.layout, Clock.parse(clock)!!.toDouble(), ft)
    fun ll(clock: String, ft: Double): LatLon = d.latLon(at(clock, ft))

    /** The camp nearest to [clock] on ring [letter]. */
    fun campNear(clock: String, letter: String): Destination {
        val p = at(clock, d.layout.ring(letter)!!.radiusFt)
        val c = d.camps.minBy { d.xy(it.position).distanceTo(p) }
        return Destination(c.name, c.position, DestinationKind.CAMP, c.id, c.address)
    }

    val city: Polygon by lazy {
        (RuleSet2026.zones(d).first { it.id == "city-playa-only" }.shapes.first() as ZoneShape.Area).polygon
    }
    val esplanade by lazy { d.streets.filter { it.kind == StreetKind.ANNULAR && it.name == "ESP" } }
    val openPlayaArt: List<Xy> by lazy { d.artXy.filter { d.fence.contains(it) && !city.contains(it) } }
    val cityArt: List<Xy> by lazy { d.artXy.filter { city.contains(it) } }

    fun xy(path: List<LatLon>) = path.map { d.xy(it) }

    /** Points every [step] ft along [pts], with their distance from the start. */
    fun samples(pts: List<Xy>, step: Double = 5.0): List<Pair<Double, Xy>> {
        val out = ArrayList<Pair<Double, Xy>>()
        var along = 0.0
        for (k in 0 until pts.size - 1) {
            val a = pts[k]; val b = pts[k + 1]
            val len = a.distanceTo(b)
            var t = 0.0
            while (t < len) {
                out += (along + t) to (a + (b - a) * (t / len))
                t += step
            }
            along += len
        }
        out += along to pts.last()
        return out
    }

    fun length(pts: List<Xy>) = (1 until pts.size).sumOf { pts[it - 1].distanceTo(pts[it]) }

    fun ms(block: () -> Unit): Double {
        val t0 = System.nanoTime(); block(); return (System.nanoTime() - t0) / 1e6
    }
}

class RouterTest {
    private val d = NavFixtures.d
    private val router get() = NavFixtures.router

    @Test fun gridBuildTime() {
        // Warm up once, then time a build.
        NavFixtures.router
        val r = Router(d, RuleSet2026, VehicleProfile())
        println("Router grid ${r.grid.w}x${r.grid.h} = ${r.grid.n} cells @ ${r.grid.cellFt} ft, built in ${r.buildMillis} ms")
        assertTrue(r.grid.n in 800_000..1_500_000)
        assertTrue(r.buildMillis < 3000, "grid build took ${r.buildMillis} ms")
    }

    @Test fun deepPlayaToCityCampIsLegal() {
        val from = NavFixtures.ll("12:00", 3500.0)
        val camp = NavFixtures.campNear("4:30", "G")
        lateinit var plan: RoutePlan
        val t = NavFixtures.ms { plan = router.plan(from, listOf(camp))!! }
        println("deep playa -> ${camp.name} (${camp.address}): ${plan.distanceFt.toInt()} ft, planned in ${"%.1f".format(t)} ms")
        val leg = plan.legs.single()
        leg.instructions.forEach { println("  [${it.pathIndex}] ${it.text}") }
        assertEquals(leg.parkAt, leg.path.last())
        assertNull(leg.walkFt)
        assertTrue(d.xy(leg.parkAt).distanceTo(d.xy(camp.position)) < 150)

        val pts = NavFixtures.xy(leg.path)
        val samples = NavFixtures.samples(pts)
        val total = samples.last().first
        val greatCircle = d.plazas.first { it.name == "Great Circle" }.polygon
        var inEsp = 0.0
        for ((a, p) in samples) {
            assertTrue(d.fence.contains(p), "outside fence at $p")
            assertTrue(!greatCircle.contains(p), "inside Great Circle at $p")
            // Blocks are rasterized by cell center: allow clipping a corner by < half a cell diagonal.
            val nearEnd = a < 30 || total - a < 30
            if (!nearEnd) for (b in d.blocks) {
                if (b.contains(p)) assertTrue(b.distanceToEdge(p) < 11.0, "inside a camp block at $p (along $a)")
            }
            if (a > 100 && total - a > 100) {
                for (art in NavFixtures.openPlayaArt) assertTrue(art.distanceTo(p) >= 80.0, "within ${art.distanceTo(p)} ft of art at $p")
                for (art in NavFixtures.cityArt) assertTrue(art.distanceTo(p) >= 30.0, "within ${art.distanceTo(p)} ft of plaza art at $p")
            }
            if (NavFixtures.esplanade.any { it.line.distanceTo(p) <= it.widthFt / 2 }) inEsp += 5.0
        }
        println("  length inside the Esplanade corridor: ${inEsp.toInt()} ft")
        assertTrue(inEsp in 1.0..150.0, "Esplanade driven for $inEsp ft")
        assertTrue(leg.instructions.any { it.text.contains("cross the Esplanade", ignoreCase = true) })
        assertTrue(leg.instructions.last().text.startsWith("Arrive at ${camp.name}"))
        assertTrue(leg.instructions.zipWithNext().all { (a, b) -> a.pathIndex <= b.pathIndex })
        assertTrue(leg.instructions.all { it.speech.isNotBlank() && it.text.isNotBlank() && it.pathIndex in leg.path.indices })
        assertTrue(leg.instructions.all { it.position == leg.path[it.pathIndex] })
    }

    @Test fun playaToPlayaCrossesPromenadeWithoutFollowingIt() {
        val plan = router.plan(NavFixtures.ll("4:00", 2300.0), listOf(Destination("X", NavFixtures.ll("8:00", 2300.0), DestinationKind.PIN)))!!
        val leg = plan.legs.single()
        val promenades = RuleSet2026.zones(d).first { it.id == "promenades" }.shapes.filterIsInstance<ZoneShape.Corridor>()
        var inProm = 0.0
        var inEsp = 0.0
        for ((_, p) in NavFixtures.samples(NavFixtures.xy(leg.path))) {
            if (promenades.any { it.line.distanceTo(p) <= it.halfWidthFt }) inProm += 5.0
            if (NavFixtures.esplanade.any { it.line.distanceTo(p) <= it.widthFt / 2 }) inEsp += 5.0
        }
        println("4:00 -> 8:00 inner playa: ${leg.distanceFt.toInt()} ft, promenade ${inProm.toInt()} ft, esplanade ${inEsp.toInt()} ft")
        leg.instructions.forEach { println("  [${it.pathIndex}] ${it.text}") }
        assertTrue(inProm in 1.0..120.0, "promenade driven for $inProm ft")
        assertTrue(inEsp < 100.0)
        assertTrue(leg.instructions.any { it.text.contains("cross the promenade", ignoreCase = true) })
        // Open playa leg: nearly straight (the chord is ~4,000 ft).
        assertTrue(leg.distanceFt < 1.3 * NavFixtures.at("4:00", 2300.0).distanceTo(NavFixtures.at("8:00", 2300.0)))
    }

    @Test fun cityRouteFollowsStreets() {
        val from = NavFixtures.ll("3:00", d.layout.ring("C")!!.radiusFt)
        val camp = NavFixtures.campNear("5:30", "H")
        val leg = router.plan(from, listOf(camp))!!.legs.single()
        leg.instructions.forEach { println("  [${it.pathIndex}] ${it.text}") }
        val samples = NavFixtures.samples(NavFixtures.xy(leg.path))
        val total = samples.last().first
        for ((a, p) in samples) {
            if (a < 30 || total - a < 30) continue
            for (b in d.blocks) if (b.contains(p)) assertTrue(b.distanceToEdge(p) < 11.0, "cuts through a camp at $p")
        }
        assertTrue(leg.instructions.any { Regex("onto [A-K]$|onto \\d{1,2}:\\d\\d").containsMatchIn(it.text) }, "street names in instructions")
        assertNull(leg.walkFt)
    }

    @Test fun playaOnlyWithoutHomeParksOutsideCity() {
        val camp = NavFixtures.campNear("4:30", "G")
        val leg = NavFixtures.playaOnly.plan(NavFixtures.ll("12:00", 3500.0), listOf(camp))!!.legs.single()
        val park = d.xy(leg.parkAt)
        println("playa-only to ${camp.name}: park at r=${park.length().toInt()} ft, walk ${leg.walkFt?.toInt()} ft; ${leg.instructions.last().text}")
        assertTrue(!NavFixtures.city.contains(park), "parked in the city")
        val walk = assertNotNull(leg.walkFt)
        assertTrue(walk > 1500, "walk $walk")
        assertTrue(leg.instructions.last().text.startsWith("Park here and walk"))
        for ((_, p) in NavFixtures.samples(NavFixtures.xy(leg.path))) {
            assertTrue(!NavFixtures.city.contains(p) || NavFixtures.city.distanceToEdge(p) < 11, "entered the city at $p")
        }
    }

    @Test fun playaOnlyWithHomeCampUsesHomeCorridor() {
        val home = NavFixtures.campNear("7:30", "E")
        val profile = VehicleProfile(licenseClass = LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY, homeCamp = home.position)
        val r = Router(d, RuleSet2026, profile)
        val leg = r.plan(NavFixtures.ll("6:30", 1500.0), listOf(home))!!.legs.single()
        leg.instructions.forEach { println("  [${it.pathIndex}] ${it.text}") }
        assertNull(leg.walkFt, "should reach home camp, parked ${d.xy(leg.parkAt).distanceTo(d.xy(home.position))} ft away")
        val corridor = HomeCorridor.build(d, home.position)
        for ((_, p) in NavFixtures.samples(NavFixtures.xy(leg.path))) {
            if (NavFixtures.city.contains(p) && NavFixtures.city.distanceToEdge(p) > 11) {
                assertTrue(corridor.distanceTo(p) <= HomeCorridor.HALF_WIDTH_FT + 11, "left the home corridor at $p")
            }
        }
        // Somebody else's camp across town is still a walk.
        val other = NavFixtures.campNear("3:30", "F")
        assertNotNull(r.plan(NavFixtures.ll("6:30", 1500.0), listOf(other))!!.legs.single().walkFt)
    }

    @Test fun nearestToiletFromCity() {
        val from = NavFixtures.ll("4:30", 4000.0)
        router.nearestToilet(from) // warm-up
        var t: Destination? = null
        val ms = NavFixtures.ms { t = router.nearestToilet(from) }
        val toilet = assertNotNull(t)
        println("nearest toilet from 4:30 & 4000 ft: ${"%.1f".format(ms)} ms, ${d.xy(toilet.position).distanceTo(d.xy(from)).toInt()} ft away")
        assertEquals(DestinationKind.TOILET, toilet.kind)
        assertEquals(Router.TOILET_NAME, toilet.name)
        val idx = toilet.refId!!.removePrefix("toilet:").toInt()
        assertEquals("in city", d.toilets[idx].cls)
        assertTrue(d.xy(toilet.position).distanceTo(d.xy(from)) < 1200)
        assertTrue(ms < 300, "nearestToilet took $ms ms")

        val deep = NavFixtures.ll("10:30", 4000.0)
        val ms2 = NavFixtures.ms { assertNotNull(router.nearestToilet(deep)) }
        println("nearest toilet from deep playa: ${"%.1f".format(ms2)} ms")
        assertTrue(ms2 < 300, "nearestToilet (deep playa) took $ms2 ms")
    }

    @Test fun multiStopPlanChainsLegs() {
        val art = d.art.first { d.fence.contains(d.xy(it.position)) && !NavFixtures.city.contains(d.xy(it.position)) && d.xy(it.position).length() > 3000 }
        val stops = listOf(
            Destination(art.name, art.position, DestinationKind.ART, art.id),
            NavFixtures.campNear("8:00", "C"),
            Destination("Pin", NavFixtures.ll("3:00", 1200.0), DestinationKind.PIN),
        )
        val plan = router.plan(NavFixtures.ll("6:30", 1500.0), stops)!!
        assertEquals(3, plan.legs.size)
        for (k in 1 until plan.legs.size) assertEquals(plan.legs[k - 1].parkAt, plan.legs[k].path.first())
        assertEquals(plan.legs.sumOf { it.distanceFt }, plan.distanceFt, 1e-6)
        // Art: parks outside the safe distance, close enough to walk without a walk note.
        val artLeg = plan.legs[0]
        val gap = d.xy(artLeg.parkAt).distanceTo(d.xy(art.position))
        println("parked $gap ft from ${art.name}")
        assertTrue(gap >= 80.0 && gap < 150.0, "art gap $gap")
        assertNull(artLeg.walkFt)
        assertTrue(artLeg.instructions.last().text == "Arrive at ${art.name}")
    }

    @Test fun startInsideCampBlockSnapsToStreet() {
        val camp = NavFixtures.campNear("9:00", "D")
        val inBlock = d.blocks.any { it.contains(d.xy(camp.position)) }
        val plan = router.plan(camp.position, listOf(Destination("Pin", NavFixtures.ll("9:00", 1500.0), DestinationKind.PIN)))
        val leg = assertNotNull(plan).legs.single()
        assertEquals(camp.position, leg.path.first())
        if (inBlock) assertTrue(d.xy(leg.path[1]).distanceTo(d.xy(camp.position)) < 200)
        assertNull(leg.walkFt)
    }

    @Test fun unsnappableStartReturnsNull() {
        val reno = LatLon(39.5296, -119.8138)
        assertNull(router.plan(reno, listOf(NavFixtures.campNear("4:30", "G"))))
        assertNull(router.nearestToilet(reno))
        assertEquals(0, router.plan(NavFixtures.ll("6:30", 1500.0), emptyList())!!.legs.size)
    }

    @Test fun timeBoundZonesUseNow() {
        val tz = java.time.ZoneId.of(d.layout.timeZone)
        val burnNight = java.time.LocalDate.parse(d.layout.manBurn).atTime(21, 0).atZone(tz).toInstant().toEpochMilli()
        val noon = java.time.LocalDate.parse(d.layout.manBurn).atTime(12, 0).atZone(tz).toInstant().toEpochMilli()
        val p = NavFixtures.at("4:30", 700.0)
        val night = Router(d, RuleSet2026, VehicleProfile(), nowMillis = burnNight)
        val day = Router(d, RuleSet2026, VehicleProfile(), nowMillis = noon)
        assertTrue(night.grid.tagAt(night.grid.cellAt(p)) and CostGrid.TAG_CAUTION != 0)
        assertTrue(day.grid.tagAt(day.grid.cellAt(p)) and CostGrid.TAG_CAUTION == 0)
        assertTrue(night.grid.costAt(night.grid.cellAt(p)) > day.grid.costAt(day.grid.cellAt(p)))
    }

    @Test fun cityArtDoesNotCutTheStreetGrid() {
        // Every B/G plaza with art is still passable around the art.
        for (plaza in d.plazas.filter { it.name.endsWith("Plaza") && it.name != "Center Camp Plaza" }) {
            val c = plaza.polygon.centroid()
            val cell = router.grid.nearestCell(c.x, c.y, 120.0) { true }
            assertTrue(cell >= 0, "${plaza.name} fully blocked")
            assertEquals(router.grid.largestComp, router.grid.comp[cell], "${plaza.name} cut off")
        }
    }

    @Test fun concurrentPlansAgree() {
        val from = NavFixtures.ll("10:00", 3000.0)
        val stops = listOf(NavFixtures.campNear("6:00", "F"))
        val expected = router.plan(from, stops)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((0 until 8).map { Callable { router.plan(from, stops) } }).map { it.get() }
            results.forEach { assertEquals(expected, it) }
        } finally {
            pool.shutdown()
        }
    }
}
