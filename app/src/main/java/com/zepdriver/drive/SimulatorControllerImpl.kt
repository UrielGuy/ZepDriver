package com.zepdriver.drive

import com.zepdriver.SimState
import com.zepdriver.SimulatorController
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.mesh.MeshText
import com.zepdriver.core.nav.NavState
import com.zepdriver.core.sim.FakeFleet
import com.zepdriver.core.sim.PlayaSimulator
import com.zepdriver.core.sim.SimClock
import com.zepdriver.core.sim.SimMode
import com.zepdriver.core.sim.SimStart
import com.zepdriver.mesh.MeshControllerImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Supplies simulated fixes in place of GPS. Called on the drive engine's thread only. */
interface SimFixSource {
    /** Time warp: simulated time runs this many times faster than real time. */
    val warp: Int

    /**
     * The fix [dtMillis] of real time after the previous one ([nowMillis] is
     * the wall clock). The fix is stamped with simulated time, which runs
     * [warp] times faster. [nav] is the current navigation state (followed in
     * autopilot mode). Null skips this tick.
     */
    fun nextFix(data: PlayaData, nowMillis: Long, dtMillis: Long, nav: NavState?): Fix?
}

/**
 * [SimulatorController] backed by the core [PlayaSimulator]. While enabled,
 * [DriveEngine] ticks it (1–4 Hz, faster at high warp) instead of using GPS.
 * Simulated time comes from a [SimClock]. All simulator state lives on the
 * engine thread; commands are posted there.
 */
class SimulatorControllerImpl(
    private val engine: DriveEngine,
    private val mesh: MeshControllerImpl,
    /** BuildConfig.DEBUG: the simulator is a testing tool. */
    override val available: Boolean,
) : SimulatorController, SimFixSource {

    private val _state = MutableStateFlow(SimState())
    override val state: StateFlow<SimState> = _state.asStateFlow()

    // --- engine-thread state ---
    private var sim: PlayaSimulator? = null
    private var simData: PlayaData? = null
    private var fleet: FakeFleet? = null
    private var fleetData: PlayaData? = null
    private var clock: SimClock? = null

    override val warp: Int get() = _state.value.warp

    override fun setEnabled(enabled: Boolean) = command {
        if (enabled == _state.value.enabled) return@command
        sim = null
        simData = null
        clock = null
        stopFleet()
        _state.update {
            SimState(enabled = enabled, mode = it.mode, start = it.start, warp = it.warp)
        }
        engine.setSimulation(if (enabled) this else null)
    }

    override fun setMode(mode: SimMode) = command {
        _state.update { it.copy(mode = mode) }
        sim?.setMode(mode)
        publish()
    }

    override fun setStart(start: SimStart) = command {
        _state.update { it.copy(start = start) }
        sim?.let {
            it.setStart(start)
            engine.onSimulatedJump()
        }
        publish()
    }

    override fun setFakeFleet(enabled: Boolean) = command {
        if (enabled && !_state.value.enabled) return@command
        _state.update { it.copy(fakeFleet = enabled) }
        if (!enabled) stopFleet()
    }

    override fun changeSpeed(deltaMph: Double) = command {
        sim?.changeSpeed(deltaMph)
        publish()
    }

    override fun steer(degPerSec: Double) = command {
        sim?.steer(degPerSec)
        publish()
    }

    override fun stop() = command {
        sim?.stop()
        publish()
    }

    override fun nextSegment() = command {
        sim?.nextSegment()
        publish()
    }

    override fun setWarp(warp: Int) = command {
        val w = SimClock.clampWarp(warp)
        _state.update { it.copy(warp = w) }
        clock?.warp = w
    }

    override fun teleport(position: LatLon) = command {
        val ps = sim ?: return@command
        val data = simData ?: return@command
        ps.teleport(data.xy(position))
        engine.onSimulatedJump()
        publish()
    }

    // --- SimFixSource (engine thread) ----------------------------------------------

    override fun nextFix(data: PlayaData, nowMillis: Long, dtMillis: Long, nav: NavState?): Fix? {
        val s = _state.value
        if (!s.enabled) return null
        val ps = sim?.takeIf { simData === data }
            ?: PlayaSimulator(data, s.mode, s.start, seed = nowMillis).also {
                sim = it
                simData = data
            }
        if (ps.mode == SimMode.AUTOPILOT) {
            val plan = nav?.takeIf { !it.finished }?.plan
            ps.followRoute(plan, plan?.path)
        }
        // Simulated time: starts at the wall clock, then runs `warp` times faster (and never backwards).
        val c = clock ?: SimClock(nowMillis, s.warp).also { clock = it }
        c.warp = s.warp
        val simDt = c.advance(dtMillis)
        val simNow = c.nowMillis
        val fix = ps.step(simDt, simNow)
        if (s.fakeFleet) tickFleet(data, simNow, simDt)
        publish()
        return fix
    }

    private fun tickFleet(data: PlayaData, nowMillis: Long, dtMillis: Long) {
        val f = fleet?.takeIf { fleetData === data }
            ?: FakeFleet(data, seed = nowMillis).also {
                fleet = it
                fleetData = data
                greet(nowMillis)
            }
        mesh.setSimulatedVehicles(f.step(dtMillis, nowMillis))
    }

    /** One fake car says hello (shown in messages, marked simulated, never transmitted). */
    private fun greet(nowMillis: Long) {
        val name = FakeFleet.NAMES.first()
        mesh.addSimulatedMessage(
            MeshText(
                fromNode = FakeFleet.NODE_BASE,
                fromName = name,
                text = "Hi from $name 👋 (simulated, not sent over the radio)",
                timeMillis = nowMillis,
                mine = false,
            ),
        )
    }

    private fun stopFleet() {
        if (fleet == null && !_state.value.fakeFleet) return
        fleet = null
        fleetData = null
        mesh.clearSimulated()
    }

    private fun publish() {
        val ps = sim ?: return
        val (step, steps) = ps.tourProgress ?: (0 to 0)
        _state.update {
            it.copy(
                mode = ps.mode,
                targetSpeedMph = ps.targetSpeedMph,
                tourLabel = ps.tourLabel,
                tourStep = step,
                tourSteps = steps,
                following = ps.hasRoute,
            )
        }
    }

    private fun command(block: () -> Unit) {
        if (!available) return
        engine.onEngine(block)
    }
}
