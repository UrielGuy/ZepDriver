package com.zepdriver.drive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.zepdriver.DriveController
import com.zepdriver.DriveUiState
import com.zepdriver.SimState
import com.zepdriver.SimulatorController
import com.zepdriver.core.nav.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** [DriveController] that starts/stops [DriveService] and forwards commands to the shared [DriveEngine]. */
class DriveControllerImpl(context: Context, val engine: DriveEngine) : DriveController {
    private val app = context.applicationContext

    override val state: StateFlow<DriveUiState> get() = engine.state

    override fun start() {
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            engine.showMessage("Location permission needed")
            return
        }
        try {
            ContextCompat.startForegroundService(app, DriveService.intent(app))
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background.
            Log.e("DriveController", "could not start DriveService", e)
            engine.showMessage("Couldn't start driving service: ${e.message}")
        }
    }

    override fun stop() {
        app.stopService(DriveService.intent(app))
    }

    @Volatile private var keepInBackground = false

    override fun keepRunningInBackground(keep: Boolean) {
        keepInBackground = keep
    }

    /**
     * More than [SimulatorController.AWAY_MILES] from the playa (where the
     * simulator drives), the service doesn't run in the background: it stops
     * when the app is left, or when the car gets that far out while the app is
     * in the background, and starts again when the app is opened. Call once,
     * on the main thread.
     */
    fun stopInBackgroundAwayFromPlaya(sim: StateFlow<SimState>, scope: CoroutineScope) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val foreground = MutableStateFlow(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> foreground.value = true
                    Lifecycle.Event.ON_STOP -> foreground.value = false
                    else -> Unit
                }
            },
        )
        val away = sim.map { it.awayFromPlaya }.distinctUntilChanged()
        var stoppedAway = false
        scope.launch(Dispatchers.Main) {
            combine(foreground, away) { fg, aw -> fg to aw }.collect { (fg, aw) ->
                if (fg) {
                    if (stoppedAway) {
                        stoppedAway = false
                        start()
                    }
                } else if (aw && !keepInBackground && engine.state.value.serviceRunning) {
                    Log.i("DriveController", "away from the playa and in the background: stopping")
                    stoppedAway = true
                    stop()
                }
            }
        }
    }

    override fun navigate(stops: List<Destination>) = engine.navigate(stops)

    override fun stopNavigation() = engine.stopNavigation()

    override fun goToPotties() = engine.goToPotties()

    override fun goNow(destination: Destination) = engine.goNow(destination)
}
