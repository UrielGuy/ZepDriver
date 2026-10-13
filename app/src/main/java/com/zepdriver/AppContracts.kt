package com.zepdriver

import android.net.Uri
import com.zepdriver.core.brc.BrcAddressing
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.mesh.FleetVehicle
import com.zepdriver.core.mesh.MeshText
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.nav.NavState
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.DriveState
import com.zepdriver.core.rules.RuleSet
import com.zepdriver.core.search.SearchIndex
import com.zepdriver.core.sim.SimMode
import com.zepdriver.core.sim.SimStart
import com.zepdriver.core.trip.TripPoint
import com.zepdriver.core.vehicles.VehicleModel
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
    /** The first-run "install an offline voice" prompt has been answered. */
    val voicePromptSeen: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    /** Show our own car's trail on the map. Never sent over the mesh. */
    val showTrail: Boolean = true,
    /** How far back the trail goes. */
    val trailHours: Int = 6,
    /** Always show an arrow + distance to the nearest porta-potties on the driving view. */
    val showPottyArrow: Boolean = true,
    /** Map tilt for the 3D view while driving, degrees. */
    val mapPitchDeg: Double = 55.0,
    /** How our own car is drawn on the map. */
    val vehicleModel: VehicleModel = VehicleModel.ART_CAR,
)

@Serializable
data class Favorite(
    val id: String,
    /** "Home", or the name the driver chose (defaults to the place's own name). */
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
    /** Makes [destination] the first stop of the active route, or starts navigating to it. */
    fun goNow(destination: Destination)
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

data class SimState(
    /** Simulated GPS is replacing the real one. */
    val enabled: Boolean = false,
    val mode: SimMode = SimMode.MANUAL,
    /** Where MANUAL / AUTOPILOT drives start (the tour has its own start). */
    val start: SimStart = SimStart.OPEN_PLAYA,
    /** The speed the simulated car is heading for. */
    val targetSpeedMph: Double = 0.0,
    /** TOUR: what the current segment shows, e.g. "Speed: 6.2 mph (warning)". */
    val tourLabel: String? = null,
    /** TOUR: 1-based segment number, and the number of segments. */
    val tourStep: Int = 0,
    val tourSteps: Int = 0,
    /** AUTOPILOT: following a route right now. */
    val following: Boolean = false,
    /** Made-up vehicles (and one hello message) in the fleet view; never transmitted. */
    val fakeFleet: Boolean = false,
    /**
     * Time warp: simulated time runs this many times faster than real time
     * (1, 2, 5, 10 or 20; see SimClock). Speeds stay real playa speeds.
     */
    val warp: Int = 1,
)

/**
 * Playa simulator for testing at home (debug builds only): replaces GPS with
 * a simulated car at Black Rock City, so warnings, navigation and the trip
 * log react as if driving on playa. Simulated positions are never sent over
 * the mesh. Always starts disabled; nothing is persisted.
 */
interface SimulatorController {
    /** False in release builds, where every call is ignored. */
    val available: Boolean
    val state: StateFlow<SimState>
    fun setEnabled(enabled: Boolean)
    fun setMode(mode: SimMode)
    /** Moves the car to [start] and stops (MANUAL / AUTOPILOT). */
    fun setStart(start: SimStart)
    fun setFakeFleet(enabled: Boolean)
    /** Changes the target speed by [deltaMph] (kept within 0..10). In TOUR mode this takes over (MANUAL). */
    fun changeSpeed(deltaMph: Double)
    /** Steering while a button is held: degrees per second, positive = right; 0 on release. */
    fun steer(degPerSec: Double)
    /** Takes over (MANUAL) and brakes to a stop. */
    fun stop()
    /** TOUR: jumps to the next segment. */
    fun nextSegment()
    /** Sets the time warp (see [SimState.warp]). */
    fun setWarp(warp: Int)
    /** Moves the simulated car to [position], keeping its heading, and stops it. In TOUR mode this takes over (MANUAL). */
    fun teleport(position: LatLon)
}

data class VoiceState(
    /** An offline English text-to-speech voice is installed; null while checking. */
    val offlineVoice: Boolean? = null,
    /** Warning sentences pre-recorded in this build (they speak without any phone voice). */
    val warningClips: Int = 0,
)

/**
 * Warnings play pre-recorded clips; turn-by-turn directions need the phone's
 * text-to-speech, which must work offline on the playa.
 */
interface VoiceController {
    val state: StateFlow<VoiceState>
    /** Re-checks the phone's voices (e.g. after returning from the install screen). */
    fun refresh()
    /** Plays a recorded warning and a spoken direction. */
    fun test()
    /** Opens Android's screen for downloading offline voice data. */
    fun openInstall()
}

/** Service locator, initialised by [ZepApp.onCreate]. */
object AppGraph {
    lateinit var playa: PlayaRepository
    lateinit var settings: SettingsStore
    lateinit var drive: DriveController
    lateinit var mesh: MeshController
    lateinit var trips: TripLog
    lateinit var sim: SimulatorController
    lateinit var voice: VoiceController
}
