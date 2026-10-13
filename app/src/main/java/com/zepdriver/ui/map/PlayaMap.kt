package com.zepdriver.ui.map

import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.RectF
import android.os.SystemClock
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
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import kotlin.math.hypot

/** How the camera should behave. */
data class MapCamera(
    /** Follow the vehicle heading-up at [pitchDeg] (moving or on a route). Otherwise a free overview. */
    val follow: Boolean,
    /** The driver has panned/zoomed: leave the camera where they put it until re-centered. */
    val detached: Boolean = false,
    /** Called when the driver starts a pan/zoom/rotate gesture. */
    val onUserGesture: () -> Unit = {},
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
 * What the driver tapped: the topmost tappable feature's [kind] (see
 * GeoJson.KIND_*, or [KIND_SPOT] for empty playa), its `id` / `name`
 * properties, where it is ([featureAt], for point features) and the tapped
 * spot [at].
 */
data class MapTap(
    val kind: String,
    val id: String? = null,
    val name: String? = null,
    val featureAt: LatLon? = null,
    val at: LatLon,
) {
    companion object {
        const val KIND_SPOT = "spot"
    }
}

/**
 * Offline 3D playa map (MapLibre). [styleJson] is the whole style; [sources]
 * maps runtime GeoJSON source ids to their current GeoJSON text. Taps report
 * what was tapped ([onTap]); [onLongPress] returns true if it used the press.
 */
@Composable
fun PlayaMap(
    styleJson: String,
    sources: Map<String, String>,
    camera: MapCamera,
    modifier: Modifier = Modifier,
    onTap: (MapTap) -> Unit = {},
    onLongPress: (LatLon) -> Boolean = { false },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = context.resources.displayMetrics.density
    val controller = remember { PlayaMapController(density) }
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
        controller.onUserGesture = camera.onUserGesture
        controller.onTap = onTap
        controller.onLongPress = onLongPress
        controller.myPosition = camera.fix?.position
    }

    // Moving: follow every fix, heading-up.
    LaunchedEffect(camera.follow, camera.fix, camera.pitchDeg) {
        val fix = camera.fix
        if (camera.follow && fix != null) controller.follow(fix, camera.pitchDeg)
    }
    // Parked: overview on entering parked mode, on the first fix and on "recenter",
    // but never yank the camera away from where the driver just moved it.
    LaunchedEffect(camera.follow, camera.recenterKey, camera.fix != null) {
        if (!camera.follow && !camera.detached) {
            controller.overview(camera.fix?.position ?: camera.home, camera.overviewBearing, camera.pitchDeg, camera.fix == null)
        }
    }
    LaunchedEffect(camera.focusKey) {
        val f = camera.focus
        if (f != null && !camera.follow) controller.focusOn(f, camera.overviewBearing, camera.pitchDeg)
    }
}

/** Owns the MapLibre objects; all calls happen on the main thread. */
private class PlayaMapController(private val density: Float) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleJson: String? = null
    private var loadedJson: String? = null
    private val sources = HashMap<String, String>()
    private val applied = HashMap<String, String>()
    private var pendingCamera: CameraPosition? = null
    private var released = false
    var onUserGesture: () -> Unit = {}
    var onTap: (MapTap) -> Unit = {}
    var onLongPress: (LatLon) -> Boolean = { false }
    var myPosition: LatLon? = null
    private var lastFollowAt = 0L

    fun onMapReady(m: MapLibreMap) {
        if (released) return
        map = m
        m.uiSettings.setLogoEnabled(false)
        m.uiSettings.setAttributionEnabled(false)
        m.uiSettings.setCompassEnabled(true)
        m.uiSettings.setAllGesturesEnabled(true)
        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserGesture()
        }
        // A tap is not a camera move: it shows what's there and leaves the camera alone.
        m.addOnMapClickListener { latLng ->
            runCatching { onTap(hitTest(m, latLng)) }
            true
        }
        m.addOnMapLongClickListener { latLng -> onLongPress(LatLon(latLng.latitude, latLng.longitude)) }
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

    /** The topmost tappable feature within a finger's width of [latLng], else the spot itself. */
    private fun hitTest(m: MapLibreMap, latLng: LatLng): MapTap {
        val at = LatLon(latLng.latitude, latLng.longitude)
        val projection = m.projection
        val pt = projection.toScreenLocation(latLng)
        val r = TAP_TOLERANCE_DP * density
        fun screenDistance(p: LatLon): Float {
            val s = projection.toScreenLocation(LatLng(p.lat, p.lon))
            return hypot(s.x - pt.x, s.y - pt.y)
        }
        // Our own car, even where its model is small or hidden.
        myPosition?.let { me ->
            if (screenDistance(me) <= r * 1.5f) return MapTap(GeoJson.KIND_ME, featureAt = me, at = at)
        }
        val s = style ?: return MapTap(MapTap.KIND_SPOT, at = at)
        val box = RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r)
        for ((kind, layerIds) in MapStyle.TAP_LAYERS) {
            val ids = layerIds.filter { s.getLayer(it) != null }
            if (ids.isEmpty()) continue
            val features = runCatching { m.queryRenderedFeatures(box, *ids.toTypedArray()) }.getOrNull()
            if (features.isNullOrEmpty()) continue
            // Several (e.g. camps) under the finger: the nearest point wins.
            val best = features.minBy { f -> f.pointAt()?.let { screenDistance(it) } ?: Float.MAX_VALUE }
            return MapTap(
                kind = best.string("kind") ?: kind,
                id = best.string("id"),
                name = best.string("name"),
                featureAt = best.pointAt(),
                at = at,
            )
        }
        return MapTap(MapTap.KIND_SPOT, at = at)
    }

    private fun Feature.string(key: String): String? =
        if (hasNonNullValueForProperty(key)) runCatching { getStringProperty(key) }.getOrNull() else null

    private fun Feature.pointAt(): LatLon? = (geometry() as? Point)?.let { LatLon(it.latitude(), it.longitude()) }

    fun follow(fix: Fix, pitchDeg: Double) {
        val current = map?.cameraPosition
        val pos = CameraPosition.Builder()
            .target(LatLng(fix.position.lat, fix.position.lon))
            .zoom(17.0)
            .bearing(fix.bearingDeg ?: current?.bearing ?: 0.0)
            .tilt(pitchDeg.coerceIn(0.0, 60.0))
            .build()
        // The drawn car is smoothed per frame, so frequent calls just track it exactly;
        // a lone update (first fix, resuming follow) eases in.
        val now = SystemClock.elapsedRealtime()
        val gap = now - lastFollowAt
        lastFollowAt = now
        val m = map
        if (m != null && gap < 150) {
            m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
        } else {
            move(pos, 600)
        }
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
        onTap = {}
        onLongPress = { false }
        map = null
        style = null
    }
}

private const val TAP_TOLERANCE_DP = 12f
