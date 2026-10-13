package com.zepdriver.core.rules

import com.zepdriver.core.alerts.Speech
import com.zepdriver.core.alerts.Alert
import com.zepdriver.core.alerts.AlertKind
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.brc.BrcAddressing
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.profile.LicenseHours
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.sun.SunTimes

enum class DrivingMode { PARKED, MOVING }

data class DriveState(
    val fix: Fix?,
    /** Smoothed speed shown on the speedometer. */
    val speedMph: Double,
    /** Speed-only severity: NONE < 5.5, CAUTION >= 5.5, WARNING >= 6.0, ALARM >= 6.5 (with hysteresis). */
    val speedLevel: AlertLevel,
    val mode: DrivingMode,
    /** All active alerts (speed included), most severe first. */
    val alerts: List<Alert>,
    /** BRC address of the vehicle, e.g. "7:45 & Esplanade" or "Deep playa 2:00 @ 3,400 ft". */
    val address: String?,
    /** True between sunset and sunrise at BRC. */
    val isNight: Boolean,
) {
    val topLevel: AlertLevel get() = alerts.maxOfOrNull { it.level } ?: AlertLevel.NONE
}

/**
 * Turns the GPS stream into speedometer, mode, address and compliance alerts.
 * Feed every fix to [update]; not thread-safe.
 */
class DriveMonitor(
    private val data: PlayaData,
    private val ruleSet: RuleSet,
    profile: VehicleProfile,
) {
    private val zones = ZoneMonitor(data, ruleSet.zones(data), profile)
    private val speed = SpeedMonitor()
    private val modes = DrivingModeDetector()
    private val heading = HeadingEstimator()
    private val sun = SunTimes.forCity(data)
    val addressing = BrcAddressing(data)

    var profile: VehicleProfile = profile
        set(value) {
            field = value
            zones.profile = value
        }

    /** [navigating] keeps the mode at MOVING while a route is active. */
    fun update(fix: Fix, navigating: Boolean = false): DriveState {
        val t = fix.timeMillis
        val xy = data.xy(fix.position)
        val mph = speed.update(fix)
        val mode = modes.update(t, mph, navigating)
        val headingDeg = heading.update(t, xy, fix.bearingDeg)
        val isNight = sun.isNight(t)

        val alerts = ArrayList<Alert>()
        speed.alert()?.let { alerts += it }
        alerts += zones.update(t, xy, headingDeg, mph)
        licenseHoursAlert(mode, isNight)?.let { alerts += it }
        if ((fix.accuracyM ?: 0.0) > WEAK_GPS_M) {
            alerts += Alert(GPS_KEY, AlertKind.GPS, AlertLevel.CAUTION, "Weak GPS — warnings may be off",
                detail = "Accuracy ±${fix.accuracyM!!.toInt()} m")
        }
        alerts.sortByDescending { it.level } // stable: keeps speed first among equals

        return DriveState(
            fix = fix,
            speedMph = mph,
            speedLevel = speed.level,
            mode = mode,
            alerts = alerts,
            address = addressing.describe(xy),
            isNight = isNight,
        )
    }

    private fun licenseHoursAlert(mode: DrivingMode, isNight: Boolean): Alert? {
        if (mode != DrivingMode.MOVING) return null
        return when {
            profile.licenseHours == LicenseHours.DAY && isNight -> Alert(
                HOURS_KEY, AlertKind.LICENSE_HOURS, AlertLevel.WARNING, "Day license: no driving at night",
                detail = "Your license is valid sunrise to sunset.", speech = Speech.DAY_LICENSE,
            )
            profile.licenseHours == LicenseHours.NIGHT && !isNight -> Alert(
                HOURS_KEY, AlertKind.LICENSE_HOURS, AlertLevel.WARNING, "Night license: no driving in daylight",
                detail = "Your license is valid sunset to sunrise.", speech = Speech.NIGHT_LICENSE,
            )
            else -> null
        }
    }

    companion object {
        /** Horizontal accuracy (m) above which zone warnings are unreliable. */
        const val WEAK_GPS_M = 15.0
        const val GPS_KEY = "gps"
        const val HOURS_KEY = "license:hours"
    }
}
