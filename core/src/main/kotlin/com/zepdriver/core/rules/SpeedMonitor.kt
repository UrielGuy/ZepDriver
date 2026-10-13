package com.zepdriver.core.rules

import com.zepdriver.core.alerts.Speech
import com.zepdriver.core.alerts.Alert
import com.zepdriver.core.alerts.AlertKind
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.geo.FEET_PER_METER
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.LocalProjection
import com.zepdriver.core.geo.MPH_PER_MPS
import java.util.Locale

/** Speed thresholds (mph) for each level. Chosen by the product owner for a 5 mph limit plus GPS tolerance. */
data class SpeedThresholds(
    val caution: Double = 5.5,
    val warning: Double = 6.0,
    val alarm: Double = 6.5,
    /** A level clears only once speed drops this far below its threshold. */
    val clearMarginMph: Double = 0.2,
    val raiseDwellMillis: Long = 1_000,
    val clearDwellMillis: Long = 2_000,
)

/**
 * Smooths GPS speed over a short window and grades it into
 * CAUTION / WARNING / ALARM with dwell time and hysteresis.
 */
class SpeedMonitor(
    thresholds: SpeedThresholds = SpeedThresholds(),
    private val windowMillis: Long = 2_500,
) {
    var thresholds: SpeedThresholds = thresholds
        private set
    private class Gate(val level: AlertLevel, val threshold: Double) {
        var active = false
        var since: Long? = null
    }

    /** (time, mph) speed samples within the window. */
    private val samples = ArrayDeque<Pair<Long, Double>>()
    /** (time, position) history within the window, for deriving speed when there is no Doppler. */
    private val positions = ArrayDeque<Pair<Long, LatLon>>()
    private var gates = gatesFor(thresholds)

    /** Switches thresholds (e.g. the driver changed them); levels re-arm from scratch. */
    fun setThresholds(t: SpeedThresholds) {
        if (t == thresholds) return
        thresholds = t
        gates = gatesFor(t)
        level = AlertLevel.NONE
    }

    private fun gatesFor(t: SpeedThresholds) = listOf(
        Gate(AlertLevel.CAUTION, t.caution),
        Gate(AlertLevel.WARNING, t.warning),
        Gate(AlertLevel.ALARM, t.alarm),
    )
    private var projection: LocalProjection? = null
    private var lastTime: Long? = null

    /** Smoothed speed in mph after the last [update]. */
    var speedMph: Double = 0.0
        private set

    var level: AlertLevel = AlertLevel.NONE
        private set

    /**
     * Feeds one fix; returns the smoothed speed in mph: the mean of the window's
     * samples after dropping the highest and lowest (so a single spike is ignored).
     */
    fun update(fix: Fix): Double {
        val t = fix.timeMillis
        val prev = lastTime
        if (prev != null && t <= prev) return speedMph // duplicate or out-of-order fix
        if (prev != null && t - prev > STALE_GAP_MILLIS) { samples.clear(); positions.clear() }
        lastTime = t
        val raw = fix.speedMph ?: derivedMph(t, fix.position)
        positions.addLast(t to fix.position)
        while (positions.first().first < t - windowMillis) positions.removeFirst()
        if (raw != null) samples.addLast(t to raw)
        while (samples.isNotEmpty() && samples.first().first <= t - windowMillis) samples.removeFirst()
        if (samples.isNotEmpty()) speedMph = trimmedMean(samples.map { it.second })
        updateLevel(t, speedMph)
        return speedMph
    }

    private fun trimmedMean(v: List<Double>): Double {
        if (v.size < 3) return v.average()
        val sorted = v.sorted()
        return sorted.subList(1, sorted.size - 1).average()
    }

    /** Speed from displacement since the oldest position in the window, which averages out jitter. */
    private fun derivedMph(t: Long, pos: LatLon): Double? {
        val (t0, p0) = positions.firstOrNull() ?: return null
        val dt = (t - t0) / 1000.0
        if (dt <= 0) return null
        val proj = projection ?: LocalProjection(pos).also { projection = it }
        val ft = proj.toXy(p0).distanceTo(proj.toXy(pos))
        return ft / FEET_PER_METER / dt * MPH_PER_MPS
    }

    private fun updateLevel(t: Long, v: Double) {
        for (g in gates) {
            if (!g.active) {
                if (v >= g.threshold) {
                    val since = g.since ?: t.also { g.since = it }
                    if (t - since >= thresholds.raiseDwellMillis - TIMING_SLACK_MILLIS) { g.active = true; g.since = null }
                } else g.since = null
            } else {
                if (v < g.threshold - thresholds.clearMarginMph) {
                    val since = g.since ?: t.also { g.since = it }
                    if (t - since >= thresholds.clearDwellMillis - TIMING_SLACK_MILLIS) { g.active = false; g.since = null }
                } else g.since = null
            }
        }
        level = gates.lastOrNull { it.active }?.level ?: AlertLevel.NONE
    }

    /** The speed alert for the current level, or null at NONE. */
    fun alert(): Alert? {
        val detail = String.format(Locale.US, "%.1f mph — warning at %.1f mph", speedMph, thresholds.warning)
        return when (level) {
            AlertLevel.NONE, AlertLevel.ADVISORY -> null
            AlertLevel.CAUTION -> Alert(KEY, AlertKind.SPEED, AlertLevel.CAUTION, "Near the speed limit", detail)
            AlertLevel.WARNING -> Alert(KEY, AlertKind.SPEED, AlertLevel.WARNING, "Slow down", detail, Speech.SLOW_DOWN)
            AlertLevel.ALARM -> Alert(
                KEY, AlertKind.SPEED, AlertLevel.ALARM, "Too fast — slow down", detail,
                Speech.SLOW_DOWN_ALARM,
            )
        }
    }

    fun reset() {
        samples.clear()
        positions.clear()
        gates.forEach { it.active = false; it.since = null }
        lastTime = null
        speedMph = 0.0
        level = AlertLevel.NONE
    }

    companion object {
        const val KEY = "speed"
        /** Fix intervals jitter by a few ms; don't make a 1 s dwell wait for a third fix. */
        private const val TIMING_SLACK_MILLIS = 150L
        private const val STALE_GAP_MILLIS = 10_000L
    }
}
