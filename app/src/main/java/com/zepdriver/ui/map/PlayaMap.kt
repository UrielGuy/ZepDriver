package com.zepdriver.ui.map

import android.content.ComponentCallbacks
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/** How the camera should behave. */
data class MapCamera(
    /** Moving: follow the vehicle heading-up at [pitchDeg]. Parked: free overview. */
    val follow: Boolean,
    val fix: Fix?,
    val pitchDeg: Double,
    /** Bearing for the parked overview (BRC 12:00 up). */
    val overviewBearing: Double,
    /** Where to look when there is no fix yet (the Man). */
    val home: LatLon,
    /** Bumped to re-center on the vehicle while parked. */
    val recenterKey: Int,
    val focus: LatLon?,
    val focusKey: Long,
)

/**
 * Offline 3D playa map (MapLibre). [styleJson] is the whole style; [sources]
 * maps runtime GeoJSON source ids to their current GeoJSON text.
 */
@Composable
fun PlayaMap(
    styleJson: String,
    sources: Map<String, String>,
    camera: MapCamera,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { PlayaMapController() }
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).also { view ->
            view.onCreate(null)
            view.getMapAsync { map -> controller.onMapReady(map) }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        val memory = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                mapView.onLowMemory()
            }
        }
        context.applicationContext.registerComponentCallbacks(memory)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(memory)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            controller.release()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)

    SideEffect {
        controller.setStyle(styleJson)
        controller.setSources(sources)
        controller.setGesturesEnabled(!camera.follow)
    }

    // Moving: follow every fix, heading-up.
    LaunchedEffect(camera.follow, camera.fix?.timeMillis, camera.pitchDeg) {
        val fix = camera.fix
        if (camera.follow && fix != null) controller.follow(fix, camera.pitchDeg)
    }
    // Parked: overview on entering parked mode, on the first fix and on "recenter".
    LaunchedEffect(camera.follow, camera.recenterKey, camera.fix != null) {
        if (!camera.follow) {
            controller.overview(camera.fix?.position ?: camera.home, camera.overviewBearing, camera.pitchDeg, camera.fix == null)
        }
    }
    LaunchedEffect(camera.focusKey) {
        val f = camera.focus
        if (f != null && !camera.follow) controller.focusOn(f, camera.overviewBearing, camera.pitchDeg)
    }
}

/** Owns the MapLibre objects; all calls happen on the main thread. */
private class PlayaMapController {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleJson: String? = null
    private var loadedJson: String? = null
    private val sources = HashMap<String, String>()
    private val applied = HashMap<String, String>()
    private var pendingCamera: CameraPosition? = null
    private var gestures = true
    private var released = false

    fun onMapReady(m: MapLibreMap) {
        if (released) return
        map = m
        m.uiSettings.setLogoEnabled(false)
        m.uiSettings.setAttributionEnabled(false)
        m.uiSettings.setCompassEnabled(true)
        m.uiSettings.setAllGesturesEnabled(gestures)
        m.setMinZoomPreference(11.0)
        m.setMaxZoomPreference(20.0)
        pendingCamera?.let { m.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
        pendingCamera = null
        loadStyleIfNeeded()
    }

    fun setStyle(json: String) {
        if (json == styleJson) return
        styleJson = json
        loadStyleIfNeeded()
    }

    private fun loadStyleIfNeeded() {
        val m = map ?: return
        val json = styleJson ?: return
        if (json == loadedJson) return
        loadedJson = json
        style = null
        applied.clear()
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            if (released || loadedJson != json) return@setStyle
            style = s
            applySources()
        }
    }

    fun setSources(next: Map<String, String>) {
        for ((id, json) in next) sources[id] = json
        applySources()
    }

    private fun applySources() {
        val s = style ?: return
        for ((id, json) in sources) {
            if (applied[id] === json) continue
            val src = s.getSourceAs<GeoJsonSource>(id) ?: continue
            src.setGeoJson(json)
            applied[id] = json
        }
    }

    fun setGesturesEnabled(enabled: Boolean) {
        if (enabled == gestures) return
        gestures = enabled
        map?.uiSettings?.setAllGesturesEnabled(enabled)
    }

    fun follow(fix: Fix, pitchDeg: Double) {
        val current = map?.cameraPosition
        val pos = CameraPosition.Builder()
            .target(LatLng(fix.position.lat, fix.position.lon))
            .zoom(17.0)
            .bearing(fix.bearingDeg ?: current?.bearing ?: 0.0)
            .tilt(pitchDeg.coerceIn(0.0, 60.0))
            .build()
        move(pos, 900)
    }

    fun overview(center: LatLon, bearing: Double, pitchDeg: Double, wide: Boolean) {
        val pos = CameraPosition.Builder()
            .target(LatLng(center.lat, center.lon))
            .zoom(if (wide) 13.6 else 15.5)
            .bearing(bearing)
            .tilt(pitchDeg.coerceIn(0.0, 35.0))
            .build()
        move(pos, 800)
    }

    fun focusOn(center: LatLon, bearing: Double, pitchDeg: Double) {
        val pos = CameraPosition.Builder()
            .target(LatLng(center.lat, center.lon))
            .zoom(16.5)
            .bearing(bearing)
            .tilt(pitchDeg.coerceIn(0.0, 35.0))
            .build()
        move(pos, 800)
    }

    private fun move(pos: CameraPosition, durationMs: Int) {
        val m = map
        if (m == null) {
            pendingCamera = pos
            return
        }
        m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), durationMs)
    }

    fun release() {
        released = true
        map = null
        style = null
    }
}
