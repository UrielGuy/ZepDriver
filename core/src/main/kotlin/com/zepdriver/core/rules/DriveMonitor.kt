package com.zepdriver.core.rules

import com.zepdriver.core.alerts.Alert
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.profile.VehicleProfile

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
    var profile: VehicleProfile = profile

    /** [navigating] keeps the mode at MOVING while a route is active. */
    fun update(fix: Fix, navigating: Boolean = false): DriveState = TODO("rules agent")
}
