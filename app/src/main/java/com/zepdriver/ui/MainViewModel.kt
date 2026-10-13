package com.zepdriver.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zepdriver.AppGraph
import com.zepdriver.AppPrefs
import com.zepdriver.DriveUiState
import com.zepdriver.Favorite
import com.zepdriver.MeshUiState
import com.zepdriver.PlayaBundle
import com.zepdriver.SimulatorController
import com.zepdriver.TripDay
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.mesh.FleetVehicle
import com.zepdriver.core.mesh.MeshText
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.nav.DestinationKind
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.DrivingMode
import com.zepdriver.core.rules.Zone
import com.zepdriver.core.search.SearchResult
import com.zepdriver.ui.map.GeoJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class Tab(val label: String) { DRIVE("Drive"), SEARCH("Search"), ROUTE("Route"), FAVORITES("Favorites"), FLEET("Fleet"), SETTINGS("Settings") }

enum class SettingsSection(val label: String) { CAR("Car"), APP("App"), RADIO("Radio"), TRIPS("Trip log"), DATA("Data"), RULES("Rules"), SIMULATOR("Simulator") }

/** A request to center the map on a place; [seq] makes repeated requests distinct. */
data class MapFocus(val position: LatLon, val seq: Long)

/** Map layers derived once per data bundle (built off the main thread). */
data class StaticLayers(
    val art: String,
    val artPoints: String,
    val camps: String,
    val toiletPoints: String,
)

/** True when the UI must be locked to the driving view (moving or navigating). */
fun DriveUiState.isLocked(): Boolean =
    drive?.mode == DrivingMode.MOVING || (nav != null && nav?.finished == false)

class MainViewModel : ViewModel() {
    // --- Platform state, straight from the contracts -------------------------
    val bundle: StateFlow<PlayaBundle?> = AppGraph.playa.bundle
    val updateStatus: StateFlow<String> = AppGraph.playa.updateStatus
    val drive: StateFlow<DriveUiState> = AppGraph.drive.state
    val mesh: StateFlow<MeshUiState> = AppGraph.mesh.state
    val fleet: StateFlow<List<FleetVehicle>> = AppGraph.mesh.fleet
    val messages: StateFlow<List<MeshText>> = AppGraph.mesh.messages
    val profile: StateFlow<VehicleProfile> = AppGraph.settings.profile
    val prefs: StateFlow<AppPrefs> = AppGraph.settings.prefs
    val favorites: StateFlow<List<Favorite>> = AppGraph.settings.favorites
    /** Playa simulator (debug builds only; see [SimulatorController.available]). */
    val sim: SimulatorController = AppGraph.sim

    // --- Navigation between screens --------------------------------------------
    private val _tab = MutableStateFlow(Tab.DRIVE)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _settingsSection = MutableStateFlow(SettingsSection.CAR)
    val settingsSection: StateFlow<SettingsSection> = _settingsSection.asStateFlow()

    private val _focus = MutableStateFlow<MapFocus?>(null)
    val focus: StateFlow<MapFocus?> = _focus.asStateFlow()

    private val _recenter = MutableStateFlow(0)
    val recenter: StateFlow<Int> = _recenter.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Short confirmations for the snackbar ("Added to route"). */
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    // --- Route draft ---------------------------------------------------------------
    private val _draftStops = MutableStateFlow<List<Destination>>(emptyList())
    val draftStops: StateFlow<List<Destination>> = _draftStops.asStateFlow()

    // --- Search ------------------------------------------------------------------------
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    // --- Map data built in the background ----------------------------------------------
    private val _zones = MutableStateFlow<List<Zone>>(emptyList())
    /** This year's rule zones for the current bundle. */
    val zones: StateFlow<List<Zone>> = _zones.asStateFlow()

    private val _zonesGeoJson = MutableStateFlow(GeoJson.EMPTY)
    val zonesGeoJson: StateFlow<String> = _zonesGeoJson.asStateFlow()

    private val _staticLayers = MutableStateFlow<StaticLayers?>(null)
    val staticLayers: StateFlow<StaticLayers?> = _staticLayers.asStateFlow()

    private val _trailGeoJson = MutableStateFlow(GeoJson.EMPTY)
    val trailGeoJson: StateFlow<String> = _trailGeoJson.asStateFlow()

    // --- Trip log ----------------------------------------------------------------------
    private val _tripDays = MutableStateFlow<List<TripDay>>(emptyList())
    val tripDays: StateFlow<List<TripDay>> = _tripDays.asStateFlow()

    private fun ticker(periodMillis: Long) = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(periodMillis)
        }
    }

    init {
        // Zones and static layers, once per bundle.
        viewModelScope.launch {
            bundle.collectLatest { b ->
                if (b == null) {
                    _zones.value = emptyList()
                    _staticLayers.value = null
                    return@collectLatest
                }
                _staticLayers.value = withContext(Dispatchers.Default) {
                    StaticLayers(
                        art = GeoJson.artFootprints(b.data),
                        artPoints = GeoJson.artPoints(b.data),
                        camps = GeoJson.camps(b.data),
                        toiletPoints = GeoJson.toiletPoints(b.data),
                    )
                }
                _zones.value = withContext(Dispatchers.Default) {
                    runCatching { b.ruleSet.zones(b.data) }.getOrDefault(emptyList())
                }
            }
        }
        // Zone overlay: depends on the profile (license) and the time (burn nights, quiet hours).
        viewModelScope.launch {
            combine(bundle, zones, profile, ticker(60_000)) { b, z, p, now -> Quad(b, z, p, now) }
                .collectLatest { (b, z, p, now) ->
                    _zonesGeoJson.value = if (b == null) GeoJson.EMPTY else withContext(Dispatchers.Default) {
                        runCatching { GeoJson.zones(b.data, z, p, now) }.getOrDefault(GeoJson.EMPTY)
                    }
                }
        }
        // Own trail (never shared). Re-subscribed periodically so the window slides.
        viewModelScope.launch {
            combine(prefs, ticker(15 * 60_000L)) { p, now -> p to now }
                .collectLatest { (p, now) ->
                    if (!p.showTrail) {
                        _trailGeoJson.value = GeoJson.EMPTY
                        return@collectLatest
                    }
                    val since = now - p.trailHours.coerceAtLeast(1) * 3_600_000L
                    AppGraph.trips.trail(since)
                        .conflate()
                        .catch { emit(emptyList()) }
                        .collect { points ->
                            _trailGeoJson.value = withContext(Dispatchers.Default) { GeoJson.trail(points) }
                        }
                }
        }
        // Debounced search, off the main thread.
        viewModelScope.launch {
            combine(_query, bundle) { q, b -> q to b }.collectLatest { (q, b) ->
                val text = q.trim()
                if (b == null || text.isEmpty()) {
                    _results.value = emptyList()
                    _searching.value = false
                    return@collectLatest
                }
                _searching.value = true
                delay(250)
                _results.value = withContext(Dispatchers.Default) {
                    runCatching { b.search.search(text) }.getOrDefault(emptyList())
                }
                _searching.value = false
            }
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    // --- Screen navigation ----------------------------------------------------------------
    fun setTab(tab: Tab) {
        if (drive.value.isLocked() && tab != Tab.DRIVE) return
        _tab.value = tab
    }

    fun setSettingsSection(section: SettingsSection) {
        _settingsSection.value = section
    }

    /** A USB radio was plugged in: bring the radio settings forward (only when parked). */
    fun onUsbAttached() {
        if (drive.value.isLocked()) return
        _settingsSection.value = SettingsSection.RADIO
        _tab.value = Tab.SETTINGS
    }

    fun showOnMap(position: LatLon) {
        _focus.update { MapFocus(position, (it?.seq ?: 0L) + 1) }
        _tab.value = Tab.DRIVE
    }

    fun clearFocus() {
        _focus.value = null
    }

    fun recenter() {
        _focus.value = null
        _recenter.update { it + 1 }
    }

    fun toast(text: String) {
        _toasts.tryEmit(text)
    }

    // --- Driving ----------------------------------------------------------------------------
    fun startDriving() {
        runCatching { AppGraph.drive.start() }
    }

    fun goToPotties() {
        AppGraph.drive.goToPotties()
    }

    fun goNow(dest: Destination) {
        AppGraph.drive.navigate(listOf(dest))
        _focus.value = null
        _tab.value = Tab.DRIVE
    }

    fun stopNavigation() {
        AppGraph.drive.stopNavigation()
    }

    // --- Route draft ------------------------------------------------------------------------
    fun addStop(dest: Destination) {
        _draftStops.update { it + dest }
        toast("Added to route: ${dest.name}")
    }

    fun moveStop(index: Int, delta: Int) {
        _draftStops.update { list ->
            val to = index + delta
            if (index !in list.indices || to !in list.indices) return@update list
            list.toMutableList().apply { add(to, removeAt(index)) }
        }
    }

    fun removeStop(index: Int) {
        _draftStops.update { list -> list.filterIndexed { i, _ -> i != index } }
    }

    fun clearStops() {
        _draftStops.value = emptyList()
    }

    fun startRoute() {
        val stops = _draftStops.value
        if (stops.isEmpty()) return
        AppGraph.drive.navigate(stops)
        _draftStops.value = emptyList()
        _focus.value = null
        _tab.value = Tab.DRIVE
    }

    // --- Search -----------------------------------------------------------------------------
    fun setQuery(q: String) {
        _query.value = q
    }

    /** One-off search for pickers (home camp etc). */
    suspend fun searchOnce(q: String, limit: Int = 30): List<SearchResult> {
        val b = bundle.value ?: return emptyList()
        val text = q.trim()
        if (text.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) { runCatching { b.search.search(text, limit = limit) }.getOrDefault(emptyList()) }
    }

    /** BRC address of a position, or null if the bundle isn't loaded / can't describe it. */
    fun describe(p: LatLon): String? {
        val b = bundle.value ?: return null
        return runCatching { b.addressing.describe(p) }.getOrNull()
    }

    // --- Favorites ----------------------------------------------------------------------------
    fun saveFavorite(label: String, dest: Destination) {
        val clean = label.trim().ifEmpty { dest.name }
        val id = when (clean) {
            LABEL_HOME -> "home"
            LABEL_ART -> "my-art"
            else -> UUID.randomUUID().toString()
        }
        AppGraph.settings.saveFavorite(Favorite(id = id, label = clean, destination = dest))
        if (clean == LABEL_HOME) {
            val p = profile.value
            AppGraph.settings.updateProfile(
                p.copy(homeCamp = dest.position, homeCampLabel = dest.address ?: dest.name),
            )
        }
        toast("Saved as $clean")
    }

    fun removeFavorite(id: String) {
        AppGraph.settings.removeFavorite(id)
    }

    /** A destination for where we are right now, or null without a GPS fix. */
    fun currentLocationDestination(name: String): Destination? {
        val state = drive.value.drive ?: return null
        val fix = state.fix ?: return null
        return Destination(
            name = name,
            position = fix.position,
            kind = DestinationKind.FAVORITE,
            address = state.address ?: describe(fix.position),
        )
    }

    // --- Settings -------------------------------------------------------------------------------
    fun updateProfile(p: VehicleProfile) {
        AppGraph.settings.updateProfile(p)
    }

    fun updatePrefs(p: AppPrefs) {
        AppGraph.settings.updatePrefs(p)
    }

    fun checkForUpdate() {
        AppGraph.playa.checkForUpdate()
        toast("Checking for a data update…")
    }

    // --- Mesh -------------------------------------------------------------------------------------
    fun sendText(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) AppGraph.mesh.sendText(t)
    }

    fun connectBluetooth() = AppGraph.mesh.connectBluetooth()
    fun disconnectRadio() = AppGraph.mesh.disconnect()
    fun flashUsbRadio() = AppGraph.mesh.flashUsbRadio()
    fun provisionUsbRadio() = AppGraph.mesh.provisionUsbRadio()

    // --- Trip log ------------------------------------------------------------------------------------
    fun refreshTripDays() {
        viewModelScope.launch {
            _tripDays.value = runCatching { AppGraph.trips.days() }.getOrDefault(emptyList())
        }
    }

    suspend fun exportTrips(days: List<String>, kml: Boolean): Uri = AppGraph.trips.export(days, kml)

    fun clearTrips() {
        viewModelScope.launch {
            runCatching { AppGraph.trips.clear() }
            _tripDays.value = runCatching { AppGraph.trips.days() }.getOrDefault(emptyList())
            toast("Trip log cleared")
        }
    }

    companion object {
        const val LABEL_HOME = "Home"
        const val LABEL_ART = "My art piece"
    }
}
