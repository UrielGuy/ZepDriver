package com.zepdriver.ui.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zepdriver.DriveUiState
import com.zepdriver.PlayaBundle
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.ui.MainViewModel
import com.zepdriver.ui.common.rememberFlash
import com.zepdriver.ui.common.rememberNow
import com.zepdriver.ui.map.GeoJson
import com.zepdriver.ui.map.MapCamera
import com.zepdriver.ui.map.MapStyle
import com.zepdriver.ui.map.PlayaMap
import com.zepdriver.ui.theme.Zep

/**
 * The home screen in both modes: 3D map, speedometer, alerts, address,
 * navigation and the Potties button. While [locked] (moving or navigating)
 * nothing else on it is interactive.
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
    val meJson = remember(fix) { GeoJson.me(data, fix) }
    val routeJson = remember(nav?.plan, nav?.legIndex, nav?.finished) { GeoJson.route(nav) }
    val stopsJson = remember(ui.stops, draft, navigating) {
        if (navigating) GeoJson.stops(ui.stops, draft = false) else GeoJson.stops(draft, draft = true)
    }
    val fleetJson = remember(fleet, now) { GeoJson.fleet(fleet, now) }
    val focusJson = remember(focus) { GeoJson.focus(focus?.position) }
    val layers = staticLayers
    val sources = remember(zonesJson, layers, trailJson, meJson, routeJson, stopsJson, fleetJson, focusJson) {
        buildMap {
            put(MapStyle.SRC_ZONES, zonesJson)
            if (layers != null) {
                put(MapStyle.SRC_ART, layers.art)
                put(MapStyle.SRC_ART_POINTS, layers.artPoints)
                put(MapStyle.SRC_CAMPS, layers.camps)
                put(MapStyle.SRC_TOILET_POINTS, layers.toiletPoints)
            }
            put(MapStyle.SRC_TRAIL, trailJson)
            put(MapStyle.SRC_ROUTE, routeJson)
            put(MapStyle.SRC_STOPS, stopsJson)
            put(MapStyle.SRC_FLEET, fleetJson)
            put(MapStyle.SRC_ME, meJson)
            put(MapStyle.SRC_FOCUS, focusJson)
        }
    }
    val camera = MapCamera(
        follow = locked,
        fix = fix,
        pitchDeg = prefs.mapPitchDeg,
        overviewBearing = data.layout.twelveOClockBearingDeg,
        home = data.layout.man.toLatLon(),
        recenterKey = recenter,
        focus = focus?.position,
        focusKey = focus?.seq ?: 0L,
    )

    // --- Alerts -------------------------------------------------------------------------
    val speedLevel = drive?.speedLevel ?: AlertLevel.NONE
    val topAlert = drive?.alerts?.firstOrNull()
    val alarm = speedLevel == AlertLevel.ALARM || topAlert?.level == AlertLevel.ALARM
    val flashOn = rememberFlash(alarm)

    Box(modifier.fillMaxSize()) {
        PlayaMap(styleJson = styleJson, sources = sources, camera = camera, modifier = Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (topAlert != null && topAlert.level != AlertLevel.NONE) {
                AlertBanner(topAlert, flashOn)
            }
            if (nav != null) {
                val stopped = (drive?.speedMph ?: 0.0) < 0.5
                NavPanel(
                    nav = nav,
                    stops = ui.stops,
                    canEnd = stopped || nav.finished,
                    onEnd = vm::stopNavigation,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Speedometer(drive?.speedMph, speedLevel, flashOn)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    if (!locked) {
                        FloatingActionButton(onClick = vm::recenter) {
                            Icon(Icons.Filled.LocationOn, contentDescription = "Center on my car")
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    PottiesButton(onClick = vm::goToPotties)
                }
                Spacer(Modifier.width(0.dp))
            }
            AddressBar(
                address = drive?.address,
                fix = fix,
                mesh = mesh,
                fleetCount = fleet.size,
                routerBusy = ui.routerBusy,
            )
        }

        if (alarm) AlarmBorder(flashOn)
    }
}
