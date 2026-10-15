package com.zepdriver.drive

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zepdriver.AppGraph
import com.zepdriver.DriveUiState
import com.zepdriver.ZepApp
import com.zepdriver.data.EmbargoControllerImpl
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.rules.DrivingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service that keeps driving features alive with the screen off:
 * 1 Hz GPS into [DriveEngine], alert audio/voice/vibration, trip logging and
 * mesh position sharing. Holds a partial wake lock while running.
 */
class DriveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: DriveEngine
    private var player: AlertPlayer? = null
    private var gpsThread: HandlerThread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var locationActive = false
    private var lastNotificationText: String? = null
    private var lastNotificationAt = 0L
    private var embargo: EmbargoControllerImpl? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            embargo?.onLocation(location)
            engine.onFix(location.toFix())
        }
        // Implemented explicitly: these have no default implementation before API 30.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = (application as ZepApp).driveEngine
        embargo = (application as ZepApp).embargo
        createChannel()
        scope.launch { engine.state.collect { if (foreground) updateNotification(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foreground && !goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (player == null) {
            val p = AlertPlayer(this, AppGraph.settings.prefs)
            player = p
            engine.attach(p)
        }
        acquireWakeLock()
        startLocation()
        engine.setServiceRunning(true)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stopLocation()
        engine.detach()
        engine.setServiceRunning(false)
        player?.release()
        player = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        // On API 34+ the connectedDevice type needs a Bluetooth runtime permission.
        val btGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            granted(Manifest.permission.BLUETOOTH_CONNECT)
        if (btGranted) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Starting…"), types)
            foreground = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            engine.showMessage("Couldn't start driving service: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locationActive) return
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            engine.showMessage("Location permission needed")
            return
        }
        val lm = getSystemService(LocationManager::class.java) ?: return
        val thread = gpsThread ?: HandlerThread("zep-gps").also { it.start(); gpsThread = it }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, thread.looper)
            locationActive = true
        } catch (e: Exception) {
            Log.e(TAG, "requestLocationUpdates failed", e)
            engine.showMessage("GPS unavailable: ${e.message}")
        }
    }

    private fun stopLocation() {
        if (locationActive) {
            getSystemService(LocationManager::class.java)?.removeUpdates(listener)
            locationActive = false
        }
        gpsThread?.quitSafely()
        gpsThread = null
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZepDriver:drive").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // --- notification ----------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("Driving")
            .setDescription("Shown while ZepDriver is watching speed and zones")
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DriveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("ZepDriver is running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(state: DriveUiState) {
        val drive = state.drive
        val text = when {
            drive == null -> "Waiting for GPS"
            drive.mode == DrivingMode.MOVING ->
                String.format(Locale.US, "%.1f mph", drive.speedMph) + (drive.address?.let { " · $it" } ?: "")
            else -> "Parked" + (drive.address?.let { " · $it" } ?: "")
        }
        val now = System.currentTimeMillis()
        if (text == lastNotificationText || now - lastNotificationAt < 5_000) return
        lastNotificationText = text
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "DriveService"
        private const val CHANNEL_ID = "drive"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.zepdriver.action.STOP_DRIVING"

        fun intent(context: Context) = Intent(context, DriveService::class.java)
    }
}

/** Platform location to core [Fix]. */
internal fun Location.toFix() = Fix(
    timeMillis = time,
    position = LatLon(latitude, longitude),
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    bearingDeg = if (hasBearing()) bearing.toDouble() else null,
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
)
