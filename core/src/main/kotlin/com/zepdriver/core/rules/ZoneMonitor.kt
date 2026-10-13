package com.zepdriver.core.rules

import com.zepdriver.core.alerts.Alert
import com.zepdriver.core.alerts.AlertKind
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.Bounds
import com.zepdriver.core.geo.Polyline
import com.zepdriver.core.geo.PolylineHit
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.geo.bearingDiff
import com.zepdriver.core.profile.VehicleProfile
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Evaluates the year's zones against the vehicle's position and heading and
 * produces zone alerts (no-entry, cross-only, license area, sound, caution).
 *
 * The phone is [VehicleProfile.phoneFromFrontFt] behind the vehicle's front;
 * both points are checked. Not thread-safe.
 */
class ZoneMonitor(
    private val data: PlayaData,
    zones: List<Zone>,
    profile: VehicleProfile,
) {
    private class Shape(val shape: ZoneShape, /** Null = unbounded (Outside). */ val bounds: Bounds?) {
        fun mayTouch(q: Bounds) = bounds == null || bounds.intersects(q)
        fun contains(p: Xy) = when (shape) {
            is ZoneShape.Area -> shape.polygon.contains(p)
            is ZoneShape.Outside -> !shape.polygon.contains(p)
            is ZoneShape.Corridor -> shape.line.distanceTo(p) <= shape.halfWidthFt
        }
    }

    private class Prepared(val zone: Zone, val shapes: List<Shape>)

    private class AlongState {
        var distanceFt = 0.0
        var millis = 0L
        var lastPos: Xy? = null
        var lastT: Long? = null
        fun reset() { distanceFt = 0.0; millis = 0; lastPos = null; lastT = null }
    }

    private class LicenseState {
        var offMillis = 0L
        var lastT: Long? = null
        var stoppedSince: Long? = null
    }

    private class Recent(val zoneId: String, val alert: Alert, val lastMillis: Long)

    private val prepared: List<Prepared> = zones.map { z ->
        Prepared(z, z.shapes.map { s ->
            Shape(
                s,
                when (s) {
                    is ZoneShape.Area -> s.polygon.bounds
                    is ZoneShape.Corridor -> s.line.bounds.expanded(s.halfWidthFt + CORRIDOR_MARGIN_FT)
                    is ZoneShape.Outside -> null
                },
            )
        })
    }

    private var applicable: List<Prepared> = emptyList()
    private var homeCorridor: Polyline? = null

    var profile: VehicleProfile = profile
        set(value) {
            field = value
            rebuild()
        }

    private val along = HashMap<String, AlongState>()
    private val license = HashMap<String, LicenseState>()
    private val recent = LinkedHashMap<String, Recent>()

    init { rebuild() }

    private fun rebuild() {
        applicable = prepared.filter { it.zone.appliesTo(profile) }
        homeCorridor = profile.homeCamp?.let { HomeCorridor.build(data, it) }
        along.clear(); license.clear(); recent.clear()
    }

    /**
     * @param phone phone position in local feet.
     * @param headingDeg course over ground, or null if unknown (directional checks are skipped).
     * @param speedMph smoothed speed.
     */
    fun update(timeMillis: Long, phone: Xy, headingDeg: Double?, speedMph: Double): List<Alert> {
        val dir = headingDeg?.let { Xy.fromBearing(it) }
        val front = dir?.let { phone + it * profile.phoneFromFrontFt } ?: phone
        val moving = speedMph >= MOVING_MPH
        val ahead: List<Xy> = if (dir != null && moving) {
            val lookahead = max(speedMph * FT_PER_SEC_PER_MPH * LOOKAHEAD_SECONDS, MIN_LOOKAHEAD_FT)
            val n = ceil(lookahead / SAMPLE_STEP_FT).toInt()
            (1..n).map { front + dir * (lookahead * it / n) }
        } else emptyList()
        val query = Bounds.of(listOfNotNull(phone, front, ahead.lastOrNull())).expanded(1.0)
        val ctx = Ctx(timeMillis, phone, front, headingDeg, moving, ahead, query)

        val current = ArrayList<Pair<String, Alert>>()
        for (p in applicable) {
            if (!p.zone.activeAt(timeMillis)) continue
            val alert = when (p.zone.kind) {
                ZoneKind.NO_ENTRY -> noEntry(p, ctx)
                ZoneKind.CROSS_ONLY -> crossOnly(p, ctx)
                ZoneKind.LICENSE_RESTRICTED -> licenseArea(p, ctx)
                ZoneKind.SOUND_OFF -> soundOff(p, ctx)
                ZoneKind.CAUTION_AREA -> caution(p, ctx)
            }
            if (alert != null) current += p.zone.id to alert
        }
        return withHysteresis(timeMillis, current)
    }

    fun reset() {
        along.clear(); license.clear(); recent.clear()
    }

    private class Ctx(
        val t: Long,
        val phone: Xy,
        val front: Xy,
        val heading: Double?,
        val moving: Boolean,
        val ahead: List<Xy>,
        val query: Bounds,
    )

    // --- No entry ---------------------------------------------------------------

    private fun noEntry(p: Prepared, c: Ctx): Alert? {
        val z = p.zone
        val shapes = p.shapes.filter { it.mayTouch(c.query) }
        if (shapes.isEmpty()) return null
        val hit = shapes.firstOrNull { it.contains(c.front) || it.contains(c.phone) }
        if (hit != null) {
            val outside = hit.shape is ZoneShape.Outside
            return Alert(
                key(z, AlertKind.NO_ENTRY_INSIDE), AlertKind.NO_ENTRY_INSIDE, AlertLevel.ALARM,
                title = if (outside) "Outside ${z.spokenName}" else "Inside ${z.name}",
                detail = z.note,
                speech = if (outside) "You're outside ${z.spokenName}. Turn back." else "Get out of ${z.spokenName}",
            )
        }
        if (c.ahead.isNotEmpty() && shapes.any { s -> c.ahead.any { s.contains(it) } }) {
            return Alert(
                key(z, AlertKind.NO_ENTRY_APPROACH), AlertKind.NO_ENTRY_APPROACH, AlertLevel.WARNING,
                title = "${z.name} ahead",
                detail = z.note,
                speech = "${z.spokenName.capitalized()} ahead. Turn away.",
            )
        }
        return null
    }

    // --- Cross only -------------------------------------------------------------

    private fun crossOnly(p: Prepared, c: Ctx): Alert? {
        val z = p.zone
        val state = along.getOrPut(z.id) { AlongState() }
        val shapes = p.shapes.filter { it.mayTouch(c.query) }
        if (shapes.isEmpty()) { state.reset(); return null }

        // Closest corridor to either vehicle point.
        var best: PolylineHit? = null
        var bestShape: ZoneShape.Corridor? = null
        for (s in shapes) {
            val cor = s.shape as? ZoneShape.Corridor ?: continue
            for (pt in if (c.front === c.phone) listOf(c.phone) else listOf(c.phone, c.front)) {
                val h = cor.line.nearest(pt)
                if (best == null || h.distance - cor.halfWidthFt < best.distance - bestShape!!.halfWidthFt) {
                    best = h; bestShape = cor
                }
            }
        }
        val cor = bestShape ?: run { state.reset(); return null }
        val within = best!!.distance <= cor.halfWidthFt + CORRIDOR_MARGIN_FT

        if (!within) {
            state.reset()
            return shallowApproach(z, shapes, c)
        }
        val heading = c.heading ?: return null // can't tell crossing from following; hold state
        val angle = axisAngle(heading, best.segmentBearing)
        val dt = state.lastT?.let { min(c.t - it, MAX_STEP_MILLIS) } ?: 0L
        when {
            angle < ALONG_MAX_DEG -> if (c.moving) {
                state.distanceFt += state.lastPos?.distanceTo(c.phone) ?: 0.0
                state.millis += dt
            }
            angle >= CROSSING_MIN_DEG -> { state.distanceFt = 0.0; state.millis = 0 }
        }
        state.lastPos = c.phone; state.lastT = c.t

        val limitFt = max(1.5 * 2 * cor.halfWidthFt, MIN_ALONG_FT)
        if (state.distanceFt > limitFt || state.millis >= ALONG_MILLIS) {
            return Alert(
                key(z, AlertKind.DRIVING_ALONG), AlertKind.DRIVING_ALONG, AlertLevel.ALARM,
                title = "Driving on ${z.name}",
                detail = z.note,
                speech = "You're driving on ${z.spokenName}. Get off it.",
            )
        }
        return if (angle < ALONG_MAX_DEG) shallowAlert(z) else Alert(
            key(z, AlertKind.CROSSING), AlertKind.CROSSING, AlertLevel.ADVISORY,
            title = "Crossing ${z.name}",
            detail = z.note,
            speech = "Crossing ${z.spokenName}. Don't drive along it.",
        )
    }

    /** About to enter a cross-only corridor at a shallow angle, i.e. likely to end up following it. */
    private fun shallowApproach(z: Zone, shapes: List<Shape>, c: Ctx): Alert? {
        val heading = c.heading ?: return null
        for (pt in c.ahead) {
            for (s in shapes) {
                val cor = s.shape as? ZoneShape.Corridor ?: continue
                val h = cor.line.nearest(pt)
                if (h.distance <= cor.halfWidthFt) {
                    return if (axisAngle(heading, h.segmentBearing) < ALONG_MAX_DEG) shallowAlert(z) else null
                }
            }
        }
        return null
    }

    private fun shallowAlert(z: Zone) = Alert(
        key(z, AlertKind.CROSSING), AlertKind.CROSSING, AlertLevel.ADVISORY,
        title = "${z.name} — cross only",
        detail = z.note,
        speech = "${z.spokenName.capitalized()} is cross only. Don't drive along it.",
    )

    // --- License area -------------------------------------------------------------

    private fun licenseArea(p: Prepared, c: Ctx): Alert? {
        val z = p.zone
        val state = license.getOrPut(z.id) { LicenseState() }
        val inside = p.shapes.any { it.mayTouch(c.query) && (it.contains(c.front) || it.contains(c.phone)) }
        val corridor = homeCorridor
        val onRoute = corridor != null && HomeCorridor.contains(corridor, c.phone)
        val dt = state.lastT?.let { min(c.t - it, MAX_STEP_MILLIS) } ?: 0L
        state.lastT = c.t
        if (!inside || onRoute) {
            state.offMillis = 0; state.stoppedSince = null
            if (!inside || !c.moving) return null
            return Alert(
                key(z, AlertKind.LICENSE_AREA) + ":route", AlertKind.LICENSE_AREA, AlertLevel.ADVISORY,
                title = "Direct route to camp only",
                detail = z.note,
                speech = "Direct route to camp only.",
            )
        }
        if (c.moving) {
            state.offMillis += dt
            state.stoppedSince = null
        } else {
            val since = state.stoppedSince ?: c.t.also { state.stoppedSince = it }
            if (c.t - since > LICENSE_PARKED_RESET_MILLIS) state.offMillis = 0
            // Parked in the city (e.g. at camp with no home camp set) without having driven: say nothing.
            if (state.offMillis == 0L) return null
        }
        val alarm = state.offMillis > LICENSE_ALARM_MILLIS
        return Alert(
            key(z, AlertKind.LICENSE_AREA), AlertKind.LICENSE_AREA,
            if (alarm) AlertLevel.ALARM else AlertLevel.WARNING,
            title = "Playa-only license: no city driving",
            detail = z.note,
            speech = if (alarm) "Playa-only license. Leave the city by the shortest route."
            else "Playa-only license. No city driving.",
        )
    }

    // --- Sound / caution ------------------------------------------------------------

    private fun soundOff(p: Prepared, c: Ctx): Alert? {
        val z = p.zone
        if (p.shapes.none { it.mayTouch(c.query) && it.contains(c.phone) }) return null
        return Alert(
            key(z, AlertKind.SOUND), AlertKind.SOUND, AlertLevel.ADVISORY,
            title = z.name, detail = z.note, speech = z.note ?: "Sound off in ${z.spokenName}.",
        )
    }

    private fun caution(p: Prepared, c: Ctx): Alert? {
        val z = p.zone
        if (p.shapes.none { it.mayTouch(c.query) && (it.contains(c.phone) || it.contains(c.front)) }) return null
        // AlertKind has no generic "caution area" kind; ART_BUFFER (slow down, people around) is the closest fit.
        return Alert(key(z, AlertKind.ART_BUFFER), AlertKind.ART_BUFFER, AlertLevel.CAUTION, title = z.name, detail = z.note)
    }

    // --- Hysteresis ------------------------------------------------------------------

    /** Keeps each zone alert ~2 s after its condition clears, unless the same zone now has another alert. */
    private fun withHysteresis(t: Long, current: List<Pair<String, Alert>>): List<Alert> {
        val zonesNow = current.mapTo(HashSet()) { it.first }
        val keysNow = current.mapTo(HashSet()) { it.second.key }
        val out = current.mapTo(ArrayList()) { it.second }
        val it = recent.values.iterator()
        while (it.hasNext()) {
            val r = it.next()
            if (r.alert.key in keysNow) continue
            if (r.zoneId in zonesNow || t - r.lastMillis > LINGER_MILLIS || t < r.lastMillis) it.remove()
            else out += r.alert
        }
        for ((zoneId, a) in current) recent[a.key] = Recent(zoneId, a, t)
        return out
    }

    private fun key(z: Zone, kind: AlertKind) = "zone:${z.id}:${kind.name.lowercase()}"

    companion object {
        /** Slack around corridor edges for GPS error, feet. */
        const val CORRIDOR_MARGIN_FT = 5.0
        const val LOOKAHEAD_SECONDS = 8.0
        const val MIN_LOOKAHEAD_FT = 40.0
        private const val SAMPLE_STEP_FT = 10.0
        private const val FT_PER_SEC_PER_MPH = 5280.0 / 3600.0
        /** Below this the vehicle counts as stopped for zone logic. */
        const val MOVING_MPH = 0.5
        /** Heading within this of a road's axis counts as driving along it. */
        const val ALONG_MAX_DEG = 35.0
        /** Heading at least this far off the road's axis counts as crossing it. */
        const val CROSSING_MIN_DEG = 45.0
        const val MIN_ALONG_FT = 50.0
        const val ALONG_MILLIS = 6_000L
        const val LICENSE_ALARM_MILLIS = 20_000L
        private const val LICENSE_PARKED_RESET_MILLIS = 30_000L
        const val LINGER_MILLIS = 2_000L
        private const val MAX_STEP_MILLIS = 2_000L

        /** Angle between a heading and a road's axis, 0..90 (direction of travel along the road doesn't matter). */
        fun axisAngle(heading: Double, roadBearing: Double): Double {
            val d = bearingDiff(heading, roadBearing)
            return if (d > 90) 180 - d else d
        }

        private fun String.capitalized() = replaceFirstChar { it.uppercaseChar() }
    }
}
