package com.zepdriver.data

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.zepdriver.EmbargoController
import com.zepdriver.core.embargo.Embargo
import com.zepdriver.core.embargo.EmbargoState
import com.zepdriver.core.embargo.Naughty
import com.zepdriver.core.embargo.TimeSample
import com.zepdriver.core.embargo.TimeSource
import com.zepdriver.core.embargo.TrustedClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Enforces Burning Man's location embargo for the year sealed into this APK.
 *
 * Release dates are checked against real time, never the phone's clock alone:
 * GNSS time from real GPS fixes, the system's network time, and the Date of
 * our own HTTPS responses, carried forward on the monotonic clock. The latest
 * confirmed time is saved (HMAC'd, in no-backup storage) so an unlock survives
 * a restart without signal. A phone clock set past a release date real time
 * hasn't reached, a re-signed APK, or changed data or state all count as
 * tampering: then no camps or art are shown at all.
 */
class EmbargoControllerImpl(private val context: Context, private val scope: CoroutineScope) : EmbargoController {

    private val sealedResult = SealedData.load(context)

    /** The embargoed year's data, if this build has it and it checked out. */
    val sealed: SealedData? = (sealedResult as? SealedData.Result.Ok)?.data

    private val floorFile = File(context.noBackupFilesDir, "embargo-floor")
    @Volatile private var stateTamper: Naughty? = null
    @Volatile private var savedFloor: Long? = null

    private val clock: TrustedClock = sealed.let { s ->
        if (s == null) TrustedClock()
        else TrustedClock(readFloor(s), plausible = (s.sealedAt - DAY_MS)..(s.sealedAt + PLAUSIBLE_AHEAD_MS))
    }

    private val _state = MutableStateFlow(evaluate())
    override val state: StateFlow<EmbargoState> = _state.asStateFlow()

    private val _timeSource = MutableStateFlow<TimeSource?>(null)
    override val timeSource: StateFlow<TimeSource?> = _timeSource.asStateFlow()

    /** Starts watching the system's GNSS and network clocks. */
    fun start() {
        if (sealedResult is SealedData.Result.None) return
        scope.launch(Dispatchers.Default) {
            while (true) {
                pollSystemClocks()
                update()
                delay(POLL_MS)
            }
        }
    }

    /** A fix straight from the GPS chip (not a mock location): its time comes from the satellites. */
    fun onLocation(location: Location) {
        if (sealed == null || location.provider != LocationManager.GPS_PROVIDER || isMock(location)) return
        if (location.time <= 0L) return
        add(TimeSample(TimeSource.SATELLITE, location.time, location.elapsedRealtimeNanos / 1_000_000))
    }

    /** The Date header of an HTTPS response from our data server, taken when it arrived. */
    fun onServerTime(utcMillis: Long, elapsedMillis: Long) {
        if (sealed == null || utcMillis <= 0L) return
        add(TimeSample(TimeSource.SERVER, utcMillis, elapsedMillis))
    }

    private fun add(sample: TimeSample) {
        if (clock.add(sample)) {
            _timeSource.value = sample.source
            update()
        }
    }

    private fun pollSystemClocks() {
        val elapsed = SystemClock.elapsedRealtime()
        // Both throw DateTimeException until the system has a reading.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { SystemClock.currentGnssTimeClock().millis() }.getOrNull()?.let {
                add(TimeSample(TimeSource.SATELLITE, it, elapsed))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { SystemClock.currentNetworkTimeClock().millis() }.getOrNull()?.let {
                add(TimeSample(TimeSource.NETWORK, it, elapsed))
            }
        }
    }

    @Synchronized
    private fun update() {
        _state.value = evaluate()
        saveFloor()
    }

    private fun evaluate(): EmbargoState {
        val tamper = when (val r = sealedResult) {
            is SealedData.Result.Tampered -> r.why
            else -> stateTamper
        }
        val elapsed = SystemClock.elapsedRealtime()
        return Embargo.evaluate(
            schedule = sealed?.schedule,
            realNow = clock.now(elapsed),
            realAtLeast = clock.atLeast(elapsed),
            phoneNow = System.currentTimeMillis(),
            tamper = tamper,
        )
    }

    // --- the confirmed-time floor ------------------------------------------------

    private fun readFloor(s: SealedData): Long? {
        if (!floorFile.exists()) return null
        return try {
            val (value, mac) = floorFile.readText().trim().split(":", limit = 2)
            if (mac != s.mac(floorMessage(s, value))) throw SecurityException("floor MAC mismatch")
            value.toLong().also { savedFloor = it }
        } catch (e: Exception) {
            Log.w(TAG, "saved embargo state was changed", e)
            stateTamper = Naughty.ModifiedData
            null
        }
    }

    private fun saveFloor() {
        val s = sealed ?: return
        if (stateTamper != null) return
        val floor = clock.atLeast(SystemClock.elapsedRealtime()) ?: return
        val saved = savedFloor
        if (saved != null && floor - saved < SAVE_STEP_MS) return
        try {
            val tmp = File(floorFile.path + ".tmp")
            tmp.writeText("$floor:${s.mac(floorMessage(s, floor.toString()))}")
            if (!tmp.renameTo(floorFile)) throw java.io.IOException("rename failed")
            savedFloor = floor
        } catch (e: Exception) {
            Log.w(TAG, "couldn't save embargo state", e)
        }
    }

    private fun floorMessage(s: SealedData, value: String) = "floor:${s.year}:$value"

    @Suppress("DEPRECATION")
    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider

    private companion object {
        const val TAG = "Embargo"
        const val DAY_MS = 86_400_000L
        /** Readings more than this past the sealing time are GNSS glitches. */
        const val PLAUSIBLE_AHEAD_MS = 3 * 365 * DAY_MS
        const val POLL_MS = 30_000L
        const val SAVE_STEP_MS = 60_000L
    }
}
