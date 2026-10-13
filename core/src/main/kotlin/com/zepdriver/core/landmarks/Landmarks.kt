package com.zepdriver.core.landmarks

import com.zepdriver.core.brc.Clock
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.Xy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What a prism is made of; the map picks its color. */
enum class LandmarkPart { WOOD, WOOD_DARK, FIGURE, CANOPY, MAST }

/**
 * One extruded piece of a landmark: a footprint ([outer] minus [holes], local
 * feet) raised from [baseFt] to [topFt] above the playa. Map extrusions can
 * float (base > 0), which is how stairs, platforms and canopies are built.
 */
class Prism(
    val landmark: String,
    val part: LandmarkPart,
    val outer: List<Xy>,
    val baseFt: Double,
    val topFt: Double,
    val holes: List<List<Xy>> = emptyList(),
)

/**
 * Simple 3D models of the city's three big landmarks, generated from their
 * published 2026 designs:
 * - The Man on "Cryptomeria" (Alexander Rose): a cedar-tree tower with
 *   root-like buttresses and a double-helix stair rising 60 ft to the Man.
 * - "Temple of the Moon" (James Gwertzman): a Queen-of-the-Night flower of
 *   slatted petals radiating from a central hyperboloid around a column.
 * - Center Camp's "Canopy": a circular acre of tensile shade on masts.
 * Shapes are impressions, not survey models; sizes are approximate.
 */
object Landmarks {
    const val MAN = "The Man"
    const val TEMPLE = "The Temple"
    const val CENTER_CAMP = "Center Camp"

    /** One line about each landmark, for the map's info popup. */
    val ABOUT: Map<String, String> = mapOf(
        MAN to "The Man, standing on “Cryptomeria”, a 60 ft cedar-tree tower with a double-helix stair.",
        TEMPLE to "“Temple of the Moon”: slatted petals around a central spire.",
        CENTER_CAMP to "The Canopy: an acre of shade at the heart of the city.",
    )

    /** Where each landmark stands. */
    fun center(data: PlayaData, name: String): Xy? = when (name) {
        MAN -> data.manXy
        TEMPLE -> data.templeXy
        CENTER_CAMP -> data.layout.centerCamp.let { Clock.point(data.layout, Clock.parse(it.clock)!!.toDouble(), it.distanceFt) }
        else -> null
    }

    fun build(data: PlayaData): List<Prism> {
        val layout = data.layout
        val cc = layout.centerCamp
        val ccXy = Clock.point(layout, Clock.parse(cc.clock)!!.toDouble(), cc.distanceFt)
        // Bearing (radians, compass) of the 6:00 direction: the Man faces Center Camp.
        val sixOClock = Math.toRadians(Clock.toBearing(layout, 360.0))
        return man(data.manXy, sixOClock) + temple(data.templeXy, sixOClock) + canopy(ccXy)
    }

    // --- The Man on Cryptomeria ------------------------------------------------------

    private fun man(c: Xy, facing: Double): List<Prism> {
        val out = mutableListOf<Prism>()
        fun add(part: LandmarkPart, ring: List<Xy>, base: Double, top: Double, holes: List<List<Xy>> = emptyList()) {
            out += Prism(MAN, part, ring, base, top, holes)
        }

        // Roots: low buttresses flaring out from the trunk.
        for (i in 0 until 8) {
            val a = 2 * PI * i / 8 + PI / 8
            add(LandmarkPart.WOOD_DARK, box(c, a, 20.0, 75.0, 7.0), 0.0, 6.0)
        }
        // Trunk: tapering stacked discs up to the platform.
        val trunkLevels = 8
        for (i in 0 until trunkLevels) {
            val r = 30.0 - 13.0 * i / (trunkLevels - 1)
            add(LandmarkPart.WOOD, circle(c, r, 24), 60.0 * i / trunkLevels, 60.0 * (i + 1) / trunkLevels)
        }
        // Double-helix stair: two ribbons of treads winding around the trunk, offset by half a turn.
        for (strand in 0..1) {
            val steps = 36
            for (s in 0 until steps) {
                val t = s.toDouble() / steps
                val a = facing + strand * PI + t * 2.5 * 2 * PI
                val h = 60.0 * t
                val r = 30.0 - 13.0 * t + 6.0
                add(LandmarkPart.WOOD_DARK, box(c, a, r - 3.5, r + 3.5, 6.0), h, h + 1.5)
            }
        }
        // Platform the Man stands on.
        add(LandmarkPart.WOOD, circle(c, 22.0, 24), 60.0, 63.0)

        // The Man: ~40 ft tall, arms raised in a V, facing Center Camp.
        val side = facing + PI / 2 // direction of his right/left hand
        fun at(sideFt: Double, frontFt: Double) =
            c + dir(side) * sideFt + dir(facing) * frontFt
        fun block(sideFt: Double, widthFt: Double, depthFt: Double, base: Double, top: Double) =
            add(LandmarkPart.FIGURE, rect(at(sideFt, 0.0), side, widthFt, depthFt), base, top)
        block(-3.5, 3.0, 3.0, 63.0, 82.0) // legs
        block(3.5, 3.0, 3.0, 63.0, 82.0)
        block(0.0, 10.0, 4.0, 82.0, 96.0) // torso
        block(0.0, 3.0, 3.0, 96.0, 103.0) // head
        // Arms: stepped diagonals rising outward from the shoulders.
        for (k in 0 until 5) {
            val off = 6.0 + 2.6 * k
            val base = 92.0 + 2.2 * k
            block(-off, 2.6, 2.6, base, base + 3.2)
            block(off, 2.6, 2.6, base, base + 3.2)
        }
        return out
    }

    // --- Temple of the Moon --------------------------------------------------------

    private fun temple(c: Xy, facing: Double): List<Prism> {
        val out = mutableListOf<Prism>()
        // Central column.
        out += Prism(TEMPLE, LandmarkPart.WOOD_DARK, circle(c, 5.0, 16), 0.0, 78.0)
        // Hyperboloid shell: thin rings narrowing to a waist and flaring again.
        val levels = 14
        val height = 72.0
        for (i in 0 until levels) {
            val h = height * (i + 0.5) / levels
            val r = hyperboloidRadius(h, waistFt = 16.0, waistAtFt = 46.0, flare = 22.0)
            out += Prism(
                TEMPLE, LandmarkPart.WOOD, circle(c, r + 2.0, 32),
                height * i / levels + 0.6, height * (i + 1) / levels,
                holes = listOf(circle(c, r, 32)),
            )
        }
        // Petals: twelve leaf shapes made of radial slats that curl up toward the core.
        val petals = 12
        for (p in 0 until petals) {
            val axis = facing + 2 * PI * p / petals
            val slats = 5
            for (s in 0 until slats) {
                val across = (s - (slats - 1) / 2.0) / ((slats - 1) / 2.0) // -1..1
                val angle = axis + across * 0.16
                val inner = 30.0 + 8.0 * across * across
                val outer = 110.0 - 30.0 * across * across
                // Each slat is split along its length so it rises toward the center.
                val pieces = 4
                for (k in 0 until pieces) {
                    val r0 = inner + (outer - inner) * k / pieces
                    val r1 = inner + (outer - inner) * (k + 1) / pieces - 1.5
                    val top = 34.0 - 26.0 * k / pieces - 4.0 * across * across
                    out += Prism(TEMPLE, LandmarkPart.WOOD, box(c, angle, r0, r1, 2.2), 0.0, top)
                }
            }
        }
        return out
    }

    private fun hyperboloidRadius(h: Double, waistFt: Double, waistAtFt: Double, flare: Double): Double {
        val z = (h - waistAtFt) / flare
        return waistFt * sqrt(1 + z * z)
    }

    // --- Center Camp: The Canopy ---------------------------------------------------

    private fun canopy(c: Xy): List<Prism> {
        val out = mutableListOf<Prism>()
        val radius = 118.0 // one acre
        // Membrane: thin floating rings rising toward the central peak, low at the edge.
        val rings = 9
        for (i in 0 until rings) {
            val rOut = radius * (rings - i) / rings
            val rIn = radius * (rings - i - 1) / rings
            val top = 14.0 + 30.0 * (1 - rOut / radius) // a shallow cone: 14 ft at the edge, ~41 ft inside
            val holes = if (rIn > 0) listOf(circle(c, rIn, 48)) else emptyList()
            out += Prism(CENTER_CAMP, LandmarkPart.CANOPY, circle(c, rOut, 48), top - 1.2, top, holes)
        }
        // Masts: the central king pole and a ring of perimeter masts.
        out += Prism(CENTER_CAMP, LandmarkPart.MAST, circle(c, 2.0, 12), 0.0, 52.0)
        for (i in 0 until 12) {
            val a = 2 * PI * i / 12
            out += Prism(CENTER_CAMP, LandmarkPart.MAST, circle(c + dir(a) * (radius - 4), 1.2, 8), 0.0, 18.0)
        }
        return out
    }

    // --- Geometry helpers ------------------------------------------------------------

    /** Compass bearing in radians to a unit vector. */
    private fun dir(bearing: Double) = Xy(sin(bearing), cos(bearing))

    private fun circle(c: Xy, r: Double, n: Int): List<Xy> =
        (0 until n).map { val a = 2 * PI * it / n; c + dir(a) * r }

    /** A radial box from [r0] to [r1] along [bearing], [width] wide. */
    private fun box(c: Xy, bearing: Double, r0: Double, r1: Double, width: Double): List<Xy> {
        val d = dir(bearing)
        val n = dir(bearing + PI / 2) * (width / 2)
        val a = c + d * r0
        val b = c + d * r1
        return listOf(a - n, b - n, b + n, a + n)
    }

    /** A rectangle centered at [c], [width] along [sideBearing] and [depth] across it. */
    private fun rect(c: Xy, sideBearing: Double, width: Double, depth: Double): List<Xy> {
        val u = dir(sideBearing) * (width / 2)
        val v = dir(sideBearing + PI / 2) * (depth / 2)
        return listOf(c - u - v, c + u - v, c + u + v, c - u + v)
    }
}
