package com.zepdriver.core.sim

import com.zepdriver.core.brc.Clock
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.bearingOf
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * A scripted drive around Black Rock City that walks through the app's
 * warnings in order: cruising, the three speed levels, the Great Circle,
 * crossing vs driving along the Esplanade, and a loop through city streets.
 * Geometry comes from the year's layout (clock positions and ring radii).
 */
object SimTour {
    class Segment(
        /** Stable id, for tests and logs. */
        val id: String,
        /** What the driver should see happening, e.g. "Speed: 6.2 mph (warning)". */
        val label: String,
        val path: List<LatLon>,
        val speedMph: Double,
    )

    fun build(data: PlayaData): List<Segment> {
        val layout = data.layout
        val esp = layout.esplanade
        fun at(clock: String, ft: Double): Xy = Clock.point(layout, Clock.parse(clock)!!.toDouble(), ft)
        fun at(minutes: Double, ft: Double): Xy = Clock.point(layout, minutes, ft)
        fun min(clock: String) = Clock.parse(clock)!!.toDouble()
        fun ring(letter: String) = layout.ring(letter)!!.radiusFt

        val out = ArrayList<Segment>()
        fun add(id: String, label: String, speed: Double, path: List<Xy>) {
            out += Segment(id, label, path.map(data::latLon), speed)
        }

        // Open playa between the 3:00 and 6:00 promenades, 1,000 ft from the Man.
        val r = 1000.0
        add("cruise", "Cruising the open playa at 4.5 mph", 4.5, arc(layout, r, 195.0, 215.0))
        add("speed-caution", "Speed: 5.7 mph (amber)", 5.7, arc(layout, r, 215.0, 235.0))
        add("speed-warning", "Speed: 6.2 mph (warning)", 6.2, arc(layout, r, 235.0, 255.0))
        add("speed-alarm", "Speed: 6.8 mph (alarm)", 6.8, arc(layout, r, 255.0, 275.0))
        add("speed-ok", "Back to 4.5 mph", 4.5, arc(layout, r, 275.0, min("5:00")))

        // Straight at the Man along 5:00, into the Great Circle, and out again.
        add("gc-approach", "Heading straight for the Man", 4.5, listOf(at("5:00", r), at("5:00", 330.0)))
        add("gc-enter", "Entering the Great Circle (violation)", 4.5, listOf(at("5:00", 330.0), at("5:00", 120.0)))
        add(
            "gc-exit", "Leaving the Great Circle", 4.5,
            // Out toward 4:40: the promenades (3:00, 6:00) run right through the Great Circle.
            listOf(at("5:00", 120.0), at(min("4:40"), 180.0), at(min("4:40"), 450.0)),
        )
        add(
            "to-esplanade", "Open playa toward 5:00 & Esplanade", 5.0,
            listOf(at(min("4:40"), 450.0), at("5:00", 1100.0), at("5:00", esp.radiusFt - 220)),
        )

        // Crossing the Esplanade square-on is fine.
        val crossEnd = at("5:00", esp.radiusFt + 220)
        add("esp-cross", "Crossing the Esplanade (advisory only)", 4.5, listOf(at("5:00", esp.radiusFt - 220), crossEnd))
        val outward = bearingOf(crossEnd)
        val turn = uTurnLeft(crossEnd, outward, 15.0)
        add("u-turn", "U-turn on 5:00", 3.0, turn)
        val back = turn.last()
        val backClock = Clock.ofPoint(layout, back)
        val playaSide = at(backClock, esp.radiusFt - 120)
        add("esp-cross-back", "Crossing back to the open playa", 4.5, listOf(back, playaSide))

        // Driving along it is not.
        val alongStart = min("4:46")
        val alongEnd = min("4:30")
        add(
            "esp-merge", "Easing onto the Esplanade", 4.5,
            listOf(playaSide, at(min("4:55"), esp.radiusFt - 100), at(min("4:50"), esp.radiusFt - 50), at(alongStart, esp.radiusFt)),
        )
        add("esp-along", "Driving along the Esplanade (violation)", 4.5, arc(layout, esp.radiusFt, alongStart, alongEnd))

        // A loop through the city: out 4:30, along C, back in on 4:00.
        val c = ring("C")
        add("city-in", "Into the city on 4:30 to 4:30 & C", 5.0, listOf(at("4:30", esp.radiusFt), at("4:30", c)))
        add("city-street", "Along C to 4:00 & C", 5.0, arc(layout, c, min("4:30"), min("4:00")))
        add("city-out", "Back out to the open playa on 4:00", 5.0, listOf(at("4:00", c), at("4:00", esp.radiusFt - 200)))
        return out
    }

    /** Points every ≤20 ft on a circle around the Man from one clock time to another (either direction). */
    internal fun arc(layout: com.zepdriver.core.data.Layout, radiusFt: Double, fromMinutes: Double, toMinutes: Double): List<Xy> {
        val ftPerMinute = 2 * Math.PI * radiusFt / 720
        val n = max(1, ceil(abs(toMinutes - fromMinutes) * ftPerMinute / 20.0).toInt())
        return (0..n).map { Clock.point(layout, fromMinutes + (toMinutes - fromMinutes) * it / n, radiusFt) }
    }

    /** A left-hand U-turn of [radiusFt] starting at [from] heading [headingDeg]. */
    internal fun uTurnLeft(from: Xy, headingDeg: Double, radiusFt: Double, steps: Int = 8): List<Xy> {
        val center = from + Xy.fromBearing(headingDeg - 90) * radiusFt
        val b0 = headingDeg + 90 // bearing from the center to the vehicle
        return (0..steps).map { center + Xy.fromBearing(b0 - 180.0 * it / steps) * radiusFt }
    }
}

/**
 * Drives the [SimTour] segments one after another, each with its own
 * [Autopilot] at the segment's speed; stops at the end of the last one.
 */
class TourDriver(private val data: PlayaData, val segments: List<SimTour.Segment>) : VehicleSim.Driver {
    init {
        require(segments.isNotEmpty())
    }

    var index: Int = 0
        private set

    private var pilot = pilotFor(0)

    val current: SimTour.Segment get() = segments[index]

    val finished: Boolean get() = index == segments.lastIndex && pilot.finished

    /** Where the tour starts and which way it faces. */
    val startXy: Xy get() = pilot.start
    val startBearing: Double get() = pilot.startBearing

    override fun control(sim: VehicleSim, nowMillis: Long) {
        pilot.control(sim, nowMillis)
        if (pilot.finished && index < segments.lastIndex) {
            index++
            pilot = pilotFor(index)
            pilot.control(sim, nowMillis)
        }
    }

    /** Jumps to the start of the next segment (keeping the current speed). False at the last one. */
    fun skip(sim: VehicleSim): Boolean {
        if (index >= segments.lastIndex) return false
        index++
        pilot = pilotFor(index)
        sim.teleport(pilot.start, pilot.startBearing, sim.speedMph)
        return true
    }

    private fun pilotFor(i: Int) = Autopilot(
        segments[i].path.map(data::xy),
        segments[i].speedMph,
        stopAtEnd = i == segments.lastIndex,
    )
}
