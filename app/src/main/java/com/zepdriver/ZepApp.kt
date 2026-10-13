package com.zepdriver

import android.app.Application
import android.util.Log
import com.zepdriver.data.PlayaRepositoryImpl
import com.zepdriver.data.SettingsStoreImpl
import com.zepdriver.data.TripLogImpl
import com.zepdriver.drive.DriveControllerImpl
import com.zepdriver.drive.DriveEngine
import com.zepdriver.drive.SimulatorControllerImpl
import com.zepdriver.mesh.MeshControllerImpl
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Creates the platform singletons and publishes them through [AppGraph]. */
class ZepApp : Application() {

    /** App-wide scope; failures are logged rather than crashing the app mid-drive. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e("ZepApp", "uncaught coroutine failure", e) }
    )

    lateinit var driveEngine: DriveEngine
        private set

    lateinit var meshController: MeshControllerImpl
        private set

    override fun onCreate() {
        super.onCreate()
        val settings = SettingsStoreImpl(this)
        val playa = PlayaRepositoryImpl(this, appScope)
        val trips = TripLogImpl(this)
        val mesh = MeshControllerImpl(this, appScope, settings)
        val engine = DriveEngine(appScope, playa, settings, trips, mesh)
        meshController = mesh
        driveEngine = engine

        AppGraph.settings = settings
        AppGraph.playa = playa
        AppGraph.trips = trips
        AppGraph.mesh = mesh
        AppGraph.drive = DriveControllerImpl(this, engine)
        // Playa simulator for testing at home: debug builds only, always starts off.
        AppGraph.sim = SimulatorControllerImpl(engine, mesh, available = BuildConfig.DEBUG)

        playa.start()
        mesh.start()
    }
}
