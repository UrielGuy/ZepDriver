package com.zepdriver.core.sim

import com.zepdriver.core.brc.Clock
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.bearingOf

enum class SimMode(val label: String) {
    /** The driver steers and sets the speed with on-screen controls. */
    MANUAL("Manual"),
    /** Drives the scripted [SimTour]. */
    TOUR("Guided tour"),
    /** Follows the active navigation route at 5 mph. */
    AUTOPILOT("Autopilot (follow route)"),
}

/** Where a simulated drive begins. */
enum class SimStart(val label: String) {
    OPEN_PLAYA("Open playa 3:30 @ 1,500 ft"),
    ESPLANADE("Esplanade 4:30 (playa side)"),
    DEEP_PLAYA("Deep playa 12:00 @ 3,500 ft"),
    CENTER_CAMP("Center Camp portal (playa side)"),
    ;

    /** Start position (local feet) and heading. */
    fun pose(data: PlayaData): Pair<Xy, Double> {
        val layout = data.layout
        fun at(clock: String, ft: Double) = Clock.point(layout, Clock.parse(clock)!!.toDouble(), ft)
        fun clockwise(clock: String) = Clock.toBearing(layout, Clock.parse(clock)!!.toDouble()) + 90
        val esp = layout.esplanade.radiusFt
        return when (this) {
            // Between the 3:00 and 6:00 promenades, facing clockwise around the Man.
            OPEN_PLAYA -> at("3:30", 1_500.0) to clockwise("3:30")
            // On the open-playa side of the Esplanade (legal), facing along it.
            ESPLANADE -> at("4:30", esp - 60) to clockwise("4:30")
            DEEP_PLAYA -> at("12:00", 3_500.0) to clockwise("12:00")
            // Just off the 6:00 promenade, facing Center Camp.
            CENTER_CAMP -> {
                val p = at("5:50", esp - 350)
                val cc = data.layout.centerCamp
                val camp = Clock.point(layout, Clock.parse(cc.clock)!!.toDouble(), cc.distanceFt)
                p to bearingOf(camp - p)
            }
        }
    }
}

/**
 * A simulated vehicle at Black Rock City in one of the [SimMode]s: what the
 * app's "Simulator" feeds into the drive engine instead of GPS. Not
 * thread-safe; call from one thread.
 */
class PlayaSimulator(
    private val data: PlayaData,
    mode: SimMode = SimMode.MANUAL,
    start: SimStart = SimStart.OPEN_PLAYA,
    seed: Long = 1L,
    gpsNoise: Boolean = true,
) {
    val vehicle: VehicleSim = start.pose(data).let { (p, h) ->
        if (gpsNoise) VehicleSim.withGpsNoise(data, p, h, seed) else VehicleSim(data, p, h, seed = seed)
    }

    var mode: SimMode = mode
        private set

    var start: SimStart = start
        private set

    var tour: TourDriver? = null
        private set

    /** Cruise speed in [SimMode.AUTOPILOT]. */
    var autopilotSpeedMph: Double = AUTOPILOT_MPH
        private set

    private var route: Autopilot? = null
    private var routeKey: Any? = null
    private var routeSet = false

    /** Slows to a stop with the wheel straight (autopilot without a route). */
    private val idle = VehicleSim.Driver { sim, _ ->
        sim.targetSpeedMph = 0.0
        sim.steerRateDegPerSec = 0.0
    }

    init {
        applyMode(mode)
    }

    fun setMode(mode: SimMode) {
        if (mode == this.mode) return
        this.mode = mode
        applyMode(mode)
    }

    private fun applyMode(mode: SimMode) {
        tour = null
        route = null
        routeKey = null
        routeSet = false
        vehicle.steerRateDegPerSec = 0.0
        when (mode) {
            SimMode.MANUAL -> {
                vehicle.driver = null
                // Taking over keeps the car rolling at its current speed.
                vehicle.targetSpeedMph = vehicle.speedMph
            }
            SimMode.TOUR -> {
                val t = TourDriver(data, SimTour.build(data))
                tour = t
                vehicle.teleport(t.startXy, t.startBearing, 0.0)
                vehicle.driver = t
            }
            SimMode.AUTOPILOT -> vehicle.driver = idle
        }
    }

    /** Moves to [start] and stops (outside [SimMode.TOUR], which has its own start). */
    fun setStart(start: SimStart) {
        this.start = start
        if (mode == SimMode.TOUR) return
        val (p, h) = start.pose(data)
        vehicle.teleport(p, h, 0.0)
        vehicle.targetSpeedMph = 0.0
        vehicle.steerRateDegPerSec = 0.0
        if (mode == SimMode.AUTOPILOT) {
            // Re-acquire the route from the new position.
            routeKey = null
            routeSet = false
        }
    }

    /**
     * Moves the car to [to] (local feet), keeping its heading, and stops it. In
     * [SimMode.TOUR] the driver takes over ([SimMode.MANUAL]).
     */
    fun teleport(to: Xy) {
        if (mode == SimMode.TOUR) setMode(SimMode.MANUAL)
        vehicle.teleport(to, vehicle.headingDeg, 0.0)
        vehicle.targetSpeedMph = 0.0
        vehicle.steerRateDegPerSec = 0.0
        if (mode == SimMode.AUTOPILOT) {
            // Re-acquire the route from the new position.
            routeKey = null
            routeSet = false
        }
    }

    /**
     * The route to follow in [SimMode.AUTOPILOT]: [path] is rebuilt only when
     * [key] changes (pass the route plan object). Null = no route: slow to a stop.
     */
    fun followRoute(key: Any?, path: List<LatLon>?) {
        if (mode != SimMode.AUTOPILOT) return
        if (routeSet && key === routeKey) return
        routeSet = true
        routeKey = key
        route = path?.takeIf { it.size >= 2 }?.let { Autopilot(it.map(data::xy), autopilotSpeedMph, stopAtEnd = true) }
        vehicle.driver = route ?: idle
    }

    fun step(dtMillis: Long, nowMillis: Long): Fix = vehicle.step(dtMillis, nowMillis)

    /** The speed the car is heading for: the manual setting, the tour segment's or the autopilot's. */
    val targetSpeedMph: Double
        get() = when (mode) {
            SimMode.MANUAL -> vehicle.targetSpeedMph
            SimMode.TOUR -> tour?.let { if (it.finished) 0.0 else it.current.speedMph } ?: 0.0
            SimMode.AUTOPILOT -> if (route?.finished == false) autopilotSpeedMph else 0.0
        }

    /** "Speed: 6.2 mph (warning)" etc. while touring. */
    val tourLabel: String?
        get() = tour?.let { if (it.finished) "Tour finished" else it.current.label }

    /** 1-based segment number and count while touring. */
    val tourProgress: Pair<Int, Int>?
        get() = tour?.let { (it.index + 1) to it.segments.size }

    /** True while autopilot has a route to follow. */
    val hasRoute: Boolean get() = route?.finished == false

    /**
     * Sets the speed. In [SimMode.AUTOPILOT] this changes the cruise speed;
     * in [SimMode.TOUR] the driver takes over ([SimMode.MANUAL]).
     */
    fun setTargetSpeed(mph: Double) {
        val v = mph.coerceIn(0.0, VehicleSim.MAX_SPEED_MPH)
        when (mode) {
            SimMode.AUTOPILOT -> {
                autopilotSpeedMph = v
                route?.speedMph = v
            }
            SimMode.TOUR -> {
                setMode(SimMode.MANUAL)
                vehicle.targetSpeedMph = v
            }
            SimMode.MANUAL -> vehicle.targetSpeedMph = v
        }
    }

    fun changeSpeed(deltaMph: Double) = setTargetSpeed(targetSpeedMph + deltaMph)

    /** Manual steering (deg/s, positive = right). Steering in a driven mode takes over. */
    fun steer(degPerSec: Double) {
        if (degPerSec != 0.0 && mode != SimMode.MANUAL) setMode(SimMode.MANUAL)
        if (mode == SimMode.MANUAL) vehicle.steerRateDegPerSec = degPerSec
    }

    /** Takes over and brakes to a stop. */
    fun stop() {
        setMode(SimMode.MANUAL)
        vehicle.targetSpeedMph = 0.0
        vehicle.steerRateDegPerSec = 0.0
    }

    /** Skips to the next tour segment. False when not touring or at the last one. */
    fun nextSegment(): Boolean = tour?.skip(vehicle) ?: false

    companion object {
        const val AUTOPILOT_MPH = 5.0
    }
}
