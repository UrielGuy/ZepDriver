package com.zepdriver.core.sim

import com.zepdriver.core.brc.Clock
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.bearingOf
import com.zepdriver.core.geo.signedTurn
import com.zepdriver.core.mesh.FleetVehicle
import kotlin.random.Random

/**
 * A few made-up art cars wandering the open playa (inside the fence, outside
 * the city and the Great Circle) at 2–5 mph, for testing the fleet view
 * without radios. Their node numbers ([NODE_BASE]+i) can't be real
 * Meshtastic nodes in practice and are never transmitted.
 */
class FakeFleet(private val data: PlayaData, count: Int = 3, seed: Long = 7L) {
    private val random = Random(seed)
    private val layout = data.layout
    private val cityStart = Clock.parse(layout.cityStartClock)!!.toDouble()
    private val cityEnd = Clock.parse(layout.cityEndClock)!!.toDouble()
    private val cityInnerFt = layout.esplanade.radiusFt - layout.esplanade.widthFt / 2

    private inner class Car(val index: Int, start: Xy, heading: Double) : VehicleSim.Driver {
        val name = NAMES[index % NAMES.size].let { if (index < NAMES.size) it else "$it ${index / NAMES.size + 1}" }
        val shortName = SHORT_NAMES[index % SHORT_NAMES.size]
        val sim = VehicleSim(data, start, heading, seed = random.nextLong())
        private var nextChange = 0L
        private var wander = 0.0

        init {
            sim.driver = this
            sim.targetSpeedMph = 2.0 + random.nextDouble() * 3.0
            sim.teleport(start, heading, sim.targetSpeedMph)
        }

        override fun control(sim: VehicleSim, nowMillis: Long) {
            if (nowMillis >= nextChange) {
                nextChange = nowMillis + 4_000L + random.nextLong(8_000L)
                sim.targetSpeedMph = 2.0 + random.nextDouble() * 3.0
                wander = (random.nextDouble() * 2 - 1) * MAX_WANDER_DEG_PER_SEC
            }
            val p = sim.position
            val ahead = p + Xy.fromBearing(sim.headingDeg) * LOOK_AHEAD_FT
            sim.steerRateDegPerSec = if (isOpenPlaya(ahead) && isOpenPlaya(p)) {
                wander
            } else {
                // Head back toward the middle of the open playa on this side of the Man.
                val home = Clock.point(layout, Clock.ofPoint(layout, p), HOME_RADIUS_FT)
                val turn = signedTurn(sim.headingDeg, bearingOf(home - p))
                (turn * 0.8).coerceIn(-RETURN_TURN_DEG_PER_SEC, RETURN_TURN_DEG_PER_SEC)
            }
        }

        fun vehicle(nowMillis: Long) = FleetVehicle(
            nodeNum = NODE_BASE + index,
            name = name,
            shortName = shortName,
            position = sim.latLon,
            positionTimeMillis = nowMillis,
            speedMph = sim.speedMph,
            headingDeg = sim.headingDeg,
            lastHeardMillis = nowMillis,
        )
    }

    private val cars: List<Car> = (0 until count).map { i -> Car(i, randomOpenPoint(), random.nextDouble() * 360) }

    /** Advances every car by [dtMillis]; returns them as fleet entries heard at [nowMillis]. */
    fun step(dtMillis: Long, nowMillis: Long): List<FleetVehicle> = cars.map { c ->
        c.sim.step(dtMillis, nowMillis)
        c.vehicle(nowMillis)
    }

    /** Current positions in local feet (for tests). */
    val positions: List<Xy> get() = cars.map { it.sim.position }

    /** Open playa: inside the fence (with a margin), outside the city and well clear of the Great Circle. */
    fun isOpenPlaya(p: Xy): Boolean {
        val r = p.length()
        if (r < layout.greatCircleRadiusFt + GC_CLEAR_FT) return false
        if (!data.fence.contains(p) || data.fence.distanceToEdge(p) < FENCE_CLEAR_FT) return false
        val clock = Clock.ofPoint(layout, p)
        if (Clock.inArc(clock, cityStart - CITY_CLOCK_MARGIN, cityEnd + CITY_CLOCK_MARGIN) && r > cityInnerFt - CITY_CLEAR_FT) return false
        return true
    }

    private fun randomOpenPoint(): Xy {
        repeat(1_000) {
            val p = Clock.point(layout, random.nextDouble() * 720, 500.0 + random.nextDouble() * 3_500.0)
            if (isOpenPlaya(p) && isOpenPlaya(p + Xy(LOOK_AHEAD_FT, 0.0)) && isOpenPlaya(p - Xy(LOOK_AHEAD_FT, 0.0))) return p
        }
        return Clock.point(layout, 0.0, HOME_RADIUS_FT)
    }

    companion object {
        /** Synthetic node numbers: [NODE_BASE] + index. */
        const val NODE_BASE = 0xF0000001L
        private const val NODE_SPAN = 0x100L

        fun isSimulated(nodeNum: Long) = nodeNum >= NODE_BASE && nodeNum < NODE_BASE + NODE_SPAN

        val NAMES = listOf("Sim · Disco Fish", "Sim · Mothership", "Sim · Neon Snail")
        private val SHORT_NAMES = listOf("SFsh", "SMth", "SSnl")

        private const val LOOK_AHEAD_FT = 120.0
        private const val HOME_RADIUS_FT = 1_500.0
        private const val GC_CLEAR_FT = 250.0
        private const val FENCE_CLEAR_FT = 300.0
        private const val CITY_CLEAR_FT = 200.0
        private const val CITY_CLOCK_MARGIN = 10.0
        private const val MAX_WANDER_DEG_PER_SEC = 6.0
        private const val RETURN_TURN_DEG_PER_SEC = 25.0
    }
}
