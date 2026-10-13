package com.zepdriver.drive

import android.os.SystemClock
import android.util.Log
import com.zepdriver.DriveUiState
import com.zepdriver.PlayaBundle
import com.zepdriver.PlayaRepository
import com.zepdriver.SettingsStore
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.nav.NavState
import com.zepdriver.core.nav.NavigationSession
import com.zepdriver.core.nav.Router
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.DriveMonitor
import com.zepdriver.core.rules.DriveState
import com.zepdriver.core.rules.DrivingMode
import com.zepdriver.core.trip.TripPoint
import com.zepdriver.data.TripLogImpl
import com.zepdriver.mesh.MeshControllerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The driving brain: turns GPS fixes into [DriveUiState] using the core
 * [DriveMonitor], runs navigation, and feeds alerts, the trip log and the mesh.
 *
 * All mutable state is confined to one thread ([engine]); public methods may
 * be called from anywhere. [DriveService] supplies fixes and the alert outputs.
 */
class DriveEngine(
    private val scope: CoroutineScope,
    playa: PlayaRepository,
    settings: SettingsStore,
    private val trips: TripLogImpl,
    private val mesh: MeshControllerImpl,
) {
    private val engine = Executors.newSingleThreadExecutor { r -> Thread(r, "zep-drive") }.asCoroutineDispatcher()

    private val _state = MutableStateFlow(DriveUiState())
    val state: StateFlow<DriveUiState> = _state.asStateFlow()

    private var bundle: PlayaBundle? = null
    private var profile: VehicleProfile = settings.profile.value
    private var monitor: DriveMonitor? = null
    private var router: Router? = null
    private var routerGeneration = 0
    /** Hour (epoch hours) the router was built for: time-bound zones are evaluated at construction. */
    private var routerHour = -1L
    private var session: NavigationSession? = null
    /** Navigation requested before a fix / router was available. */
    private var pending: List<Destination>? = null
    private var pendingPotties = false
    private var lastFix: Fix? = null
    private var lastFixAt = 0L
    private var alerts: AlertPlayer? = null

    private var lastLogged: Fix? = null
    private var lastLoggedLevel = AlertLevel.NONE

    /** Non-null while the playa simulator replaces GPS (debug builds). */
    private var sim: SimFixSource? = null
    private var simJob: Job? = null

    init {
        scope.launch(engine) {
            combine(playa.bundle, settings.profile) { b, p -> b to p }.collect { (b, p) -> onConfig(b, p) }
        }
    }

    // --- called by DriveService ---------------------------------------------

    fun attach(player: AlertPlayer) = post { alerts = player }

    fun detach() = post { alerts = null }

    fun setServiceRunning(running: Boolean) = _state.update { it.copy(serviceRunning = running) }

    /** A real GPS fix; ignored while the simulator is driving. */
    fun onFix(fix: Fix) = post { if (sim == null) process(fix) }

    // --- called by SimulatorControllerImpl ---------------------------------------

    /**
     * Replaces GPS with [source], ticked at 1 Hz on the engine thread, or goes
     * back to GPS with null. Either way the drive starts afresh: navigation
     * ends and the monitor forgets the previous track.
     */
    fun setSimulation(source: SimFixSource?) = post {
        if (source === sim) return@post
        sim = source
        simJob?.cancel()
        simJob = null
        resetDrive()
        if (source != null) {
            simJob = scope.launch(engine) {
                var last = SystemClock.elapsedRealtime()
                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    val dt = (now - last).coerceIn(0L, MAX_SIM_STEP_MS)
                    last = now
                    try {
                        tickSim(source, dt)
                    } catch (e: Exception) {
                        Log.e(TAG, "simulator tick failed", e)
                    }
                    delay((SIM_TICK_MS - (SystemClock.elapsedRealtime() - now)).coerceIn(10L, SIM_TICK_MS))
                }
            }
        }
    }

    /** Runs [block] on the engine thread (where the simulator's state lives). */
    fun onEngine(block: () -> Unit) = post(block)

    // --- called by DriveControllerImpl ----------------------------------------

    fun showMessage(message: String?) = _state.update { it.copy(message = message) }

    fun navigate(stops: List<Destination>) = post {
        pendingPotties = false
        if (stops.isEmpty()) {
            endNavigation(null)
            return@post
        }
        tryStart(stops)
    }

    fun stopNavigation() = post {
        pending = null
        pendingPotties = false
        endNavigation(null)
    }

    fun goToPotties() = post { potties() }

    /** Makes [dest] the next stop of the current route, or starts a route to it. */
    fun goNow(dest: Destination) = post {
        if (session == null) {
            pendingPotties = false
            tryStart(listOf(dest))
        } else {
            insertNext(dest)
        }
    }

    // --- internals (engine thread) ------------------------------------------

    private fun tickSim(source: SimFixSource, dtMillis: Long) {
        if (sim !== source) return
        val b = bundle ?: return
        val fix = source.nextFix(b.data, System.currentTimeMillis(), dtMillis, _state.value.nav) ?: return
        process(fix, simulated = true)
    }

    /** Forgets the current drive when switching between GPS and the simulator. */
    private fun resetDrive() {
        session = null
        pending = null
        pendingPotties = false
        lastFix = null
        lastFixAt = 0L
        lastLogged = null
        lastLoggedLevel = AlertLevel.NONE
        bundle?.let { b ->
            monitor = runCatching { DriveMonitor(b.data, b.ruleSet, profile) }
                .onFailure { Log.e(TAG, "DriveMonitor failed", it) }.getOrNull()
        }
        _state.update { it.copy(drive = null, nav = null, stops = emptyList(), message = null) }
    }

    private fun post(block: () -> Unit) {
        scope.launch(engine) {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "drive engine error", e)
            } catch (e: NotImplementedError) {
                Log.e(TAG, "drive engine: core not implemented", e)
            }
        }
    }

    private fun onConfig(b: PlayaBundle?, p: VehicleProfile) {
        if (b == null) return
        val old = profile
        profile = p
        if (b !== bundle) {
            bundle = b
            monitor = runCatching { DriveMonitor(b.data, b.ruleSet, p) }
                .onFailure { Log.e(TAG, "DriveMonitor failed", it) }.getOrNull()
            rebuildRouter()
        } else {
            monitor?.profile = p
            // The car name and sound system don't affect routing.
            if (old.copy(carName = "", hasSoundSystem = true) != p.copy(carName = "", hasSoundSystem = true)) {
                rebuildRouter()
            }
        }
    }

    private fun rebuildRouter() {
        val b = bundle ?: return
        val p = profile
        val generation = ++routerGeneration
        routerHour = System.currentTimeMillis() / HOUR_MS
        // The previous router stays usable until the new one is ready.
        _state.update { it.copy(routerBusy = true) }
        scope.launch(Dispatchers.Default) {
            val built = try {
                Router(b.data, b.ruleSet, p)
            } catch (e: Throwable) {
                Log.e(TAG, "Router build failed", e)
                null
            }
            withContext(engine) {
                if (generation != routerGeneration) return@withContext
                router = built
                _state.update {
                    it.copy(routerBusy = false, message = if (built == null) "Routing unavailable" else it.message)
                }
                if (built != null) {
                    try {
                        onRouterReady(built)
                    } catch (e: Throwable) {
                        Log.e(TAG, "re-planning failed", e)
                    }
                }
            }
        }
    }

    private fun onRouterReady(r: Router) {
        // Re-plan an active route with the new router (e.g. the profile changed).
        val s = session
        val fix = lastFix
        if (s != null && fix != null) {
            val remaining = runCatching { s.stops }.getOrDefault(emptyList())
            // If re-planning fails, keep following the old plan.
            if (remaining.isNotEmpty()) startSession(r, fix.position, remaining, replacing = true)
        }
        pending?.let { tryStart(it) }
        if (pendingPotties) potties()
    }

    private fun currentPosition(): LatLon? {
        val fix = lastFix ?: return null
        // Elapsed time rather than wall clock: the phone's clock may drift with no network on playa.
        return if (SystemClock.elapsedRealtime() - lastFixAt < STALE_FIX_MS) fix.position else null
    }

    private fun tryStart(stops: List<Destination>) {
        val pos = currentPosition()
        val r = router
        if (pos == null || r == null) {
            pending = stops
            showMessage(if (pos == null) "Waiting for GPS" else "Preparing routes…")
            return
        }
        pending = null
        startSession(r, pos, stops)
    }

    private fun startSession(r: Router, from: LatLon, stops: List<Destination>, replacing: Boolean = false) {
        val s = try {
            NavigationSession(r, from, stops)
        } catch (e: Throwable) {
            Log.e(TAG, "planning failed", e)
            null
        }
        val nav = s?.let { runCatching { it.state }.getOrNull() }
        if (s == null || nav == null) {
            if (replacing) return
            session = null
            _state.update { it.copy(nav = null, stops = emptyList(), message = "No legal route to ${stops.first().name}") }
            return
        }
        session = s
        _state.update { it.copy(nav = nav, stops = s.stops, message = null) }
        nav.speech?.let { speech -> _state.value.drive?.let { alerts?.update(it, speech) } }
    }

    private fun potties() {
        val pos = currentPosition()
        val r = router
        if (pos == null || r == null) {
            pendingPotties = true
            showMessage(if (pos == null) "Waiting for GPS" else "Preparing routes…")
            return
        }
        pendingPotties = false
        val toilet = runCatching { r.nearestToilet(pos) }.getOrNull()
        if (toilet == null) {
            showMessage("No reachable porta-potties found")
            return
        }
        if (session != null) insertNext(toilet, pos) else startSession(r, pos, listOf(toilet))
    }

    /** Puts [dest] first on the active route, replanning from [from]. */
    private fun insertNext(dest: Destination, from: LatLon? = currentPosition()) {
        val s = session ?: return
        if (from == null) {
            showMessage("Waiting for GPS")
            return
        }
        val nav = runCatching { s.insertNext(dest, from) }.getOrNull()
        if (nav == null) {
            showMessage("No legal route to ${dest.name}")
        } else {
            _state.update { it.copy(nav = nav, stops = s.stops, message = null) }
        }
    }

    private fun endNavigation(message: String?) {
        session = null
        _state.update { it.copy(nav = null, stops = emptyList(), message = message) }
    }

    private fun process(fix: Fix, simulated: Boolean = false) {
        lastFix = fix
        lastFixAt = SystemClock.elapsedRealtime()
        // Rebuild on the first fix of a new hour so time-bound zones (burn nights etc.) stay current.
        if (bundle != null && System.currentTimeMillis() / HOUR_MS != routerHour) rebuildRouter()
        val drive = monitor?.let { m ->
            try {
                m.update(fix)
            } catch (e: Throwable) {
                Log.e(TAG, "DriveMonitor.update failed", e)
                null
            }
        } ?: fallbackState(fix)

        var nav: NavState? = null
        var navSpeech: String? = null
        var message = _state.value.message
        session?.let { s ->
            nav = try {
                s.update(fix)
            } catch (e: Throwable) {
                Log.e(TAG, "navigation update failed", e)
                runCatching { s.state }.getOrNull()
            }
            navSpeech = nav?.speech
            val n = nav
            if (n != null && n.finished) {
                session = null
                message = "Arrived at ${n.plan.legs.lastOrNull()?.destination?.name ?: "destination"}"
                nav = null
            }
        }
        val stops = session?.let { s -> runCatching { s.stops }.getOrNull() } ?: emptyList()
        _state.update { it.copy(drive = drive, nav = nav, stops = stops, message = message) }

        alerts?.let { player ->
            try {
                player.update(drive, navSpeech)
            } catch (e: Exception) {
                Log.e(TAG, "alert output failed", e)
            }
        }
        logTrip(fix, drive)
        // Simulated positions never go out over the mesh: other cars would see a phantom.
        if (!simulated) mesh.onFix(fix, drive.mode)

        pending?.let { tryStart(it) }
        if (pendingPotties) potties()
    }

    private fun fallbackState(fix: Fix) = DriveState(
        fix = fix,
        speedMph = fix.speedMph ?: 0.0,
        speedLevel = AlertLevel.NONE,
        mode = if ((fix.speedMph ?: 0.0) > 1.0) DrivingMode.MOVING else DrivingMode.PARKED,
        alerts = emptyList(),
        address = null,
        isNight = false,
    )

    /** Every fix while moving (max one per 2 s, after > 10 ft), every 60 s while parked, and on new warnings. */
    private fun logTrip(fix: Fix, drive: DriveState) {
        val last = lastLogged
        val level = drive.topLevel
        val due = when {
            last == null -> true
            level >= AlertLevel.WARNING && level > lastLoggedLevel -> true
            drive.mode == DrivingMode.MOVING ->
                fix.timeMillis - last.timeMillis >= 2_000 &&
                    TripLogImpl.distanceFt(last.position.lat, last.position.lon, fix.position.lat, fix.position.lon) > 10.0
            else -> fix.timeMillis - last.timeMillis >= 60_000
        }
        lastLoggedLevel = level
        if (!due) return
        lastLogged = fix
        val note = drive.alerts.firstOrNull()?.takeIf { it.level >= AlertLevel.WARNING }?.title
        val point = TripPoint(fix.timeMillis, fix.position.lat, fix.position.lon, drive.speedMph, level, note)
        // A single small insert; done inline so points stay in order.
        try {
            trips.record(point)
        } catch (e: Exception) {
            Log.e(TAG, "trip log write failed", e)
        }
    }

    private companion object {
        const val TAG = "DriveEngine"
        const val STALE_FIX_MS = 30_000L
        const val HOUR_MS = 3_600_000L
        const val SIM_TICK_MS = 1_000L
        const val MAX_SIM_STEP_MS = 3_000L
    }
}
