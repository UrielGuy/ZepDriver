package com.zepdriver.drive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.zepdriver.DriveController
import com.zepdriver.DriveUiState
import com.zepdriver.core.nav.Destination
import kotlinx.coroutines.flow.StateFlow

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

    override fun navigate(stops: List<Destination>) = engine.navigate(stops)

    override fun stopNavigation() = engine.stopNavigation()

    override fun goToPotties() = engine.goToPotties()

    override fun goNow(destination: Destination) = engine.goNow(destination)
}
