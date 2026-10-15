package com.zepdriver.core.brc

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.Xy

/**
 * Turns the Burning Man API's camp addresses into positions. The API gives
 * camps a street address only, no coordinates: "G & 9:15" (a corner),
 * "4:30 G Plaza @ 2:15" (a spot around a plaza), "7:30 Portal & A" (a portal).
 * Each becomes the corner, plaza spot or portal it names.
 */
class CampGeocoder(private val data: PlayaData, private val addressing: BrcAddressing = BrcAddressing(data)) {
    private val layout = data.layout
    private val esp = layout.esplanade
    private val ringA = layout.rings.first { it.letter == "A" }

    private class Plaza(val center: Xy, val radiusFt: Double)

    private val plazas: Map<String, Plaza> = data.plazas.associate { p ->
        val c = p.polygon.centroid()
        key(p.name) to Plaza(c, p.polygon.outer.map { it.distanceTo(c) }.average())
    }

    fun locate(address: String?): LatLon? {
        val raw = address?.replace(" None None", "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val s = raw.lowercase()
        PLAZA.find(s)?.let { m ->
            val plaza = plazas[key(m.groupValues[1] + " plaza")] ?: return@let
            val around = AT_CLOCK.find(s.substring(m.range.last + 1))?.let { Clock.parse(it.groupValues[1]) }
            val at = if (around == null) plaza.center
            else plaza.center + Xy.fromBearing(Clock.toBearing(layout, around.toDouble())) * (plaza.radiusFt * PLAZA_EDGE)
            return data.latLon(at)
        }
        PORTAL.find(s)?.let { m ->
            val clock = Clock.parse(m.groupValues[1]) ?: return@let
            val radius = when {
                "esplanade" in s -> esp.radiusFt
                PORTAL_AT_A.containsMatchIn(s) -> ringA.radiusFt
                else -> (esp.radiusFt + ringA.radiusFt) / 2
            }
            return data.latLon(Clock.point(layout, clock.toDouble(), radius))
        }
        return addressing.parse(raw)
    }

    private companion object {
        /** Camps "@" a plaza clock sit near its edge. */
        const val PLAZA_EDGE = 0.8
        val PLAZA = Regex("""(center camp|\d{1,2}:\d{2}\s*&?\s*[a-z]+)\s+plaza""")
        val AT_CLOCK = Regex("""@\s*(\d{1,2}:\d{2})""")
        val PORTAL = Regex("""(\d{1,2}:\d{2})\s+portal""")
        val PORTAL_AT_A = Regex("""portal\s*&\s*a\b""")

        /** "4:30 & G Plaza" and "4:30 G Plaza" match. */
        fun key(name: String) = name.lowercase().replace("&", "").replace(Regex("""\s+"""), "")
    }
}
