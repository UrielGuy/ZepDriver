package com.zepdriver.ui.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zepdriver.DriveUiState
import com.zepdriver.PlayaBundle
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.nav.ToiletFinder
import com.zepdriver.ui.MainViewModel
import com.zepdriver.ui.common.FavoriteLabelDialog
import com.zepdriver.ui.common.rememberFlash
import com.zepdriver.ui.common.rememberNow
import com.zepdriver.ui.map.GeoJson
import com.zepdriver.ui.map.MapCamera
import com.zepdriver.ui.map.MapStyle
import com.zepdriver.ui.map.MapTap
import com.zepdriver.ui.map.PlayaMap
import com.zepdriver.ui.sim.SimOverlay
import com.zepdriver.ui.theme.Zep
import kotlinx.coroutines.delay

/** Widest the alert banner and nav panel get (landscape keeps the map's center clear). */
private val PANEL_MAX_WIDTH = 480.dp
/** Room the speedometer and the Potties button each need beside the info popup. */
private val SIDE_CONTROLS = 210.dp
/** While moving, the info popup closes itself after this long. */
private const val POPUP_MOVING_MILLIS = 8_000L

/** Remembers the last known heading so the car model keeps facing that way when stopped. */
private class HeadingMemo(var deg: Double) {
    fun update(bearing: Double?): Double {
        if (bearing != null) deg = bearing
        return deg
    }
}

/**
 * The home screen in both modes: 3D map, speedometer, alerts, navigation and
 * the Potties button, with small GPS/radio indicators in a corner. Tapping the
 * map shows what's there (and your own BRC address when you tap your car).
 * While [locked] (moving) the popup is info only.
 */
@Composable
fun DriveScreen(
    vm: MainViewModel,
    bundle: PlayaBundle,
    ui: DriveUiState,
    locked: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colors = Zep.colors
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val fleet by vm.fleet.collectAsStateWithLifecycle()
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val zonesJson by vm.zonesGeoJson.collectAsStateWithLifecycle()
    val staticLayers by vm.staticLayers.collectAsStateWithLifecycle()
    val trailJson by vm.trailGeoJson.collectAsStateWithLifecycle()
    val draft by vm.draftStops.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()
    val recenter by vm.recenter.collectAsStateWithLifecycle()
    val detached by vm.mapDetached.collectAsStateWithLifecycle()
    val simState by vm.sim.state.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val now by rememberNow(15_000L)

    val data = bundle.data
    val drive = ui.drive
    val fix = drive?.fix
    val nav = ui.nav
    val navigating = nav != null && !nav.finished

    // --- Map ------------------------------------------------------------------------
    val styleJson = remember(bundle.uriPrefix, colors.map) {
        MapStyle.build(bundle.uriPrefix, colors.map, data.layout.man.lat)
    }
    val headingMemo = remember(data) { HeadingMemo(data.layout.twelveOClockBearingDeg) }
    val modelHeading = headingMemo.update(fix?.bearingDeg)
    // Glide between GPS fixes so the car, camera and route line move smoothly.
    val pose by rememberSmoothPose(fix, modelHeading)
    val drawnFix = pose?.let { p -> fix?.let { f -> f.copy(position = p.position, bearingDeg = f.bearingDeg?.let { p.headingDeg }) } }
    val meJson = remember(pose, prefs.vehicleModel, profile) {
        GeoJson.me(data, drawnFix, prefs.vehicleModel, profile, pose?.headingDeg ?: modelHeading)
    }
    val routeJson = remember(nav?.plan, nav?.legIndex, nav?.finished, nav?.legAhead, pose?.position) {
        GeoJson.route(nav, carAt = pose?.position)
    }
    val stopsJson = remember(ui.stops, draft, navigating) {
        if (navigating) GeoJson.stops(ui.stops, draft = false) else GeoJson.stops(draft, draft = true)
    }
    val fleetJson = remember(fleet, now) { GeoJson.fleet(fleet, now) }
    val focusJson = remember(focus) { GeoJson.focus(focus?.position) }
    val placesJson = remember(profile.homeCamp, favorites) { GeoJson.places(profile.homeCamp, favorites) }
    val layers = staticLayers
    val sources = remember(zonesJson, layers, trailJson, meJson, routeJson, stopsJson, fleetJson, focusJson, placesJson) {
        buildMap {
            put(MapStyle.SRC_ZONES, zonesJson)
            if (layers != null) {
                put(MapStyle.SRC_ART, layers.art)
                put(MapStyle.SRC_ART_POINTS, layers.artPoints)
                put(MapStyle.SRC_CAMPS, layers.camps)
                put(MapStyle.SRC_TOILET_POINTS, layers.toiletPoints)
                put(MapStyle.SRC_LANDMARKS, layers.landmarks)
            }
            put(MapStyle.SRC_PLACES, placesJson)
            put(MapStyle.SRC_TRAIL, trailJson)
            put(MapStyle.SRC_ROUTE, routeJson)
            put(MapStyle.SRC_STOPS, stopsJson)
            put(MapStyle.SRC_FLEET, fleetJson)
            put(MapStyle.SRC_ME, meJson)
            put(MapStyle.SRC_FOCUS, focusJson)
        }
    }
    // Follow the car while moving or on a route, until the driver pans or zooms.
    val wantsFollow = locked || navigating
    val following = wantsFollow && !detached
    LaunchedEffect(wantsFollow) { if (wantsFollow) vm.resumeFollowing() }
    val camera = MapCamera(
        follow = following,
        detached = detached,
        onUserGesture = vm::onMapGesture,
        fix = drawnFix ?: fix,
        pitchDeg = prefs.mapPitchDeg,
        overviewBearing = data.layout.twelveOClockBearingDeg,
        home = data.layout.man.toLatLon(),
        recenterKey = recenter,
        focus = focus?.position,
        focusKey = focus?.seq ?: 0L,
    )

    // --- Potty arrow --------------------------------------------------------------------
    // Screen-up is the vehicle heading while following, else the overview's 12:00-up.
    var lastHeading by remember { mutableStateOf(data.layout.twelveOClockBearingDeg) }
    val heading = fix?.bearingDeg
    SideEffect { if (following && heading != null) lastHeading = heading }
    val screenUp = if (wantsFollow) lastHeading else data.layout.twelveOClockBearingDeg
    val toiletFinder = remember(data) { ToiletFinder(data) }
    val potty = remember(fix?.position, prefs.showPottyArrow) {
        if (prefs.showPottyArrow) fix?.position?.let(toiletFinder::nearest) else null
    }

    // --- Alerts -------------------------------------------------------------------------
    val speedLevel = drive?.speedLevel ?: AlertLevel.NONE
    val topAlert = drive?.alerts?.firstOrNull()
    val alarm = speedLevel == AlertLevel.ALARM || topAlert?.level == AlertLevel.ALARM
    val flashOn = rememberFlash(alarm)
    val debugUnlocked by vm.debugUnlocked.collectAsStateWithLifecycle()
    val screenshotMode by vm.screenshotMode.collectAsStateWithLifecycle()
    val statusRow: @Composable (Modifier) -> Unit = { m ->
        Row(m, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.sim.available && !screenshotMode) DebugLockToggle(unlocked = debugUnlocked, onClick = vm::toggleDebugUnlock)
            StatusChip(fix, mesh, fleet.size, ui.routerBusy)
        }
    }

    // --- Tap-for-info popup --------------------------------------------------------------
    var tap by remember { mutableStateOf<MapTap?>(null) }
    var saving by remember { mutableStateOf<Destination?>(null) }
    val shownStops = if (navigating) ui.stops else draft
    val info = remember(tap, drive, fleet, shownStops, favorites, profile, now) {
        tap?.let { describeTap(it, bundle, drive, fleet, shownStops, favorites, profile, System.currentTimeMillis()) }
    }
    // Moving: info only, and it goes away by itself.
    LaunchedEffect(tap, locked) {
        if (tap != null && locked) {
            delay(POPUP_MOVING_MILLIS)
            tap = null
        }
    }
    val onTap: (MapTap) -> Unit = { t ->
        // With a popup open, tapping empty playa just closes it.
        tap = if (tap != null && t.kind == MapTap.KIND_SPOT) null else t
    }
    // The simulator's ETA clock runs on simulated (possibly warped) time.
    val etaNow = if (simState.enabled) fix?.timeMillis ?: System.currentTimeMillis() else System.currentTimeMillis()

    Box(modifier.fillMaxSize()) {
        PlayaMap(
            styleJson = styleJson,
            sources = sources,
            camera = camera,
            modifier = Modifier.fillMaxSize(),
            onTap = onTap,
            onLongPress = vm::teleportSim,
        )

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // Speedometer and Potties live at the top so the car can sit low on the
            // screen with the route ahead in view (the follow camera is offset to match).
            val wide = maxWidth >= SIDE_CONTROLS * 2 + 320.dp
            val speedo: @Composable () -> Unit = { Speedometer(drive?.speedMph, speedLevel, flashOn) }
            val rightSide: @Composable () -> Unit = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PottiesButton(
                        onClick = vm::goToPotties,
                        pointer = potty,
                        arrowRotationDeg = potty?.let { (it.bearingDeg - screenUp).toFloat() } ?: 0f,
                    )
                    statusRow(Modifier)
                }
            }
            val panels: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (topAlert != null && topAlert.level != AlertLevel.NONE) {
                        AlertBanner(topAlert, flashOn, Modifier.fillMaxWidth())
                    }
                    if (nav != null) {
                        val stopped = (drive?.speedMph ?: 0.0) < 0.5
                        NavPanel(
                            nav = nav,
                            stops = ui.stops,
                            canEnd = stopped || nav.finished || debugUnlocked,
                            onEnd = vm::stopNavigation,
                            modifier = Modifier.fillMaxWidth(),
                            nowMillis = etaNow,
                        )
                    }
                }
            }
            if (wide) {
                // Landscape / tablets: speedometer | alerts + route | Potties, all along the top.
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    speedo()
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        panels(Modifier.widthIn(max = PANEL_MAX_WIDTH).fillMaxWidth())
                    }
                    Spacer(Modifier.width(12.dp))
                    rightSide()
                }
            } else {
                Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        speedo()
                        Spacer(Modifier.weight(1f))
                        rightSide()
                    }
                    panels(Modifier.fillMaxWidth())
                }
            }

            // Bottom: only the map popup and the re-center button, so the route ahead stays visible.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                info?.let { i ->
                    MapInfoCard(
                        info = i,
                        showActions = !locked,
                        onGoNow = { d -> vm.goNow(d); tap = null },
                        onAddStop = { d -> vm.addStop(d); tap = null },
                        onSave = { d -> saving = d },
                        onClose = { tap = null },
                        modifier = Modifier.widthIn(max = PANEL_MAX_WIDTH).fillMaxWidth(),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (wantsFollow && detached) {
                        // Like Google Maps: after panning/zooming, one tap resumes following.
                        ExtendedFloatingActionButton(
                            onClick = vm::recenter,
                            icon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                            text = { Text("Re-center") },
                        )
                    } else if (!wantsFollow) {
                        FloatingActionButton(onClick = vm::recenter) {
                            Icon(Icons.Filled.LocationOn, contentDescription = "Center on my car")
                        }
                    }
                }
            }
        }

        if (alarm) AlarmBorder(flashOn)

        // Playa simulator pad (debug builds; nothing unless it's on): left edge, mid-height.
        if (!screenshotMode) SimOverlay(
            vm.sim,
            Modifier
                .align(Alignment.CenterStart)
                .padding(contentPadding)
                .padding(start = 20.dp),
        )
    }

    saving?.let { dest ->
        FavoriteLabelDialog(
            initialCustom = dest.name,
            onDismiss = { saving = null },
            onSave = { label ->
                vm.saveFavorite(label, dest)
                saving = null
                tap = null
            },
        )
    }
}
