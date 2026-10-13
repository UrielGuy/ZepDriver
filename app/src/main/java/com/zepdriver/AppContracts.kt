package com.zepdriver

import android.net.Uri
import com.zepdriver.core.brc.BrcAddressing
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.mesh.FleetVehicle
import com.zepdriver.core.mesh.MeshText
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.nav.NavState
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.DriveState
import com.zepdriver.core.rules.RuleSet
import com.zepdriver.core.search.SearchIndex
import com.zepdriver.core.trip.TripPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/*
 * Contracts between the UI layer (com.zepdriver.ui) and the platform layer
 * (com.zepdriver.data / .drive / .mesh). The UI only talks to these
 * interfaces, obtained from [AppGraph].
 */

/** A loaded data bundle plus everything derived from it. */
class PlayaBundle(
    val data: PlayaData,
    val ruleSet: RuleSet,
    val addressing: BrcAddressing,
    val search: SearchIndex,
    /**
     * URI prefix MapLibre can load bundle files from, ending in "/":
     * "asset://playa/2026/" for the built-in bundle or "file:///…/" for a
     * downloaded update. Append e.g. "geo/blocks.geojson".
     */
    val uriPrefix: String,
)

interface PlayaRepository {
    /** Null until the bundle has loaded (a second or two after start). */
    val bundle: StateFlow<PlayaBundle?>
    /** Human status of the last update check, e.g. "Up to date (e4c7a84d)". */
    val updateStatus: StateFlow<String>
    /** Checks online for a newer bundle now (also runs periodically when online). */
    fun checkForUpdate()
}

enum class ThemeMode { AUTO, DAY, NIGHT }

@Serializable
data class AppPrefs(
    val voiceEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    /** Show our own car's trail on the map. Never sent over the mesh. */
    val showTrail: Boolean = true,
    /** How far back the trail goes. */
    val trailHours: Int = 6,
    /** Map tilt for the 3D view while driving, degrees. */
    val mapPitchDeg: Double = 55.0,
)

@Serializable
data class Favorite(
    val id: String,
    /** "Home", "My art piece", or anything the driver typed. */
    val label: String,
    val destination: Destination,
)

interface SettingsStore {
    val profile: StateFlow<VehicleProfile>
    fun updateProfile(profile: VehicleProfile)

    val prefs: StateFlow<AppPrefs>
    fun updatePrefs(prefs: AppPrefs)

    val favorites: StateFlow<List<Favorite>>
    fun saveFavorite(favorite: Favorite)
    fun removeFavorite(id: String)
}

data class DriveUiState(
    /** Null until the first GPS fix. */
    val drive: DriveState? = null,
    /** Null when not navigating. */
    val nav: NavState? = null,
    /** Remaining stops, current target first. */
    val stops: List<Destination> = emptyList(),
    val serviceRunning: Boolean = false,
    /** Router is (re)building its grid; navigation requests wait for it. */
    val routerBusy: Boolean = false,
    /** Last routing problem to show, e.g. "No legal route to X". */
    val message: String? = null,
)

interface DriveController {
    val state: StateFlow<DriveUiState>
    /** Starts the foreground service (location permission must be granted). */
    fun start()
    fun stop()
    /** Plans and starts navigation through [stops] from the current position. */
    fun navigate(stops: List<Destination>)
    fun stopNavigation()
    /** Inserts the nearest porta-potty bank as the next stop (works in any mode). */
    fun goToPotties()
}

enum class MeshConnection { NO_CAR_NAME, DISCONNECTED, SCANNING, CONNECTING, CONNECTED, PROVISIONING, FLASHING, ERROR }

data class MeshUiState(
    val connection: MeshConnection = MeshConnection.DISCONNECTED,
    /** Name of the radio we're connected to / flashing. */
    val deviceName: String? = null,
    /** 0..1 while flashing, else null. */
    val flashProgress: Float? = null,
    /** A USB radio is plugged in right now. */
    val usbDevicePresent: Boolean = false,
    val status: String = "",
    val log: List<String> = emptyList(),
)

interface MeshController {
    val state: StateFlow<MeshUiState>
    /** Other ZepDriver vehicles, most recently heard first. */
    val fleet: StateFlow<List<FleetVehicle>>
    val messages: StateFlow<List<MeshText>>
    /** Scans for and connects to our radio over BLE. Refuses (NO_CAR_NAME) until a car name is set. */
    fun connectBluetooth()
    fun disconnect()
    fun sendText(text: String)
    /** Flashes Meshtastic onto the plugged-in USB board, then provisions it with the car name. */
    fun flashUsbRadio()
    /** Provisions an already-Meshtastic USB board without flashing. */
    fun provisionUsbRadio()
}

data class TripDay(
    /** Local date "2026-09-01". */
    val date: String,
    val points: Int,
    val distanceFt: Double,
)

interface TripLog {
    /** Our own trail since [sinceMillis], updating live. */
    fun trail(sinceMillis: Long): Flow<List<TripPoint>>
    suspend fun days(): List<TripDay>
    /** Writes KML (or GPX) for the given days and returns a shareable content:// URI. */
    suspend fun export(days: List<String>, kml: Boolean = true): Uri
    suspend fun clear()
}

/** Service locator, initialised by [ZepApp.onCreate]. */
object AppGraph {
    lateinit var playa: PlayaRepository
    lateinit var settings: SettingsStore
    lateinit var drive: DriveController
    lateinit var mesh: MeshController
    lateinit var trips: TripLog
}
