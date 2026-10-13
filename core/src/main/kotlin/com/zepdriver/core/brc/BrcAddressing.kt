package com.zepdriver.core.brc

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.data.RingLayout
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.Polygon
import com.zepdriver.core.geo.Xy
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A parsed BRC address: a clock direction plus a ring street or a distance from the Man. */
data class BrcAddress(
    /** Clock minutes, 0 until 720. */
    val clockMinutes: Int,
    /** Distance from the Man in feet (the ring's radius for street addresses). */
    val radiusFt: Double,
    /** The annular street, or null for "2:00 @ 3,400 ft" style addresses. */
    val ring: RingLayout?,
    val position: LatLon,
) {
    /** Canonical form: "4:30 & G", "7:45 & Esplanade", "2:00 @ 3,400 ft". */
    val label: String
        get() = Clock.format(clockMinutes) +
            if (ring != null) " & ${BrcAddressing.streetName(ring)}" else " @ ${BrcAddressing.formatFeet(radiusFt)}"
}

/** Converts between coordinates and Black Rock City addresses. */
class BrcAddressing(private val data: PlayaData) {
    private val layout = data.layout
    private val esp = layout.esplanade
    private val outer = layout.outerRing
    private val cityStart = Clock.parse(layout.cityStartClock)!!.toDouble()
    private val cityEnd = Clock.parse(layout.cityEndClock)!!.toDouble()

    private val greatCircle: Polygon = data.plazas.firstOrNull { it.name == "Great Circle" }?.polygon
        ?: Polygon.circle(data.manXy, layout.greatCircleRadiusFt)
    private val centerCamp: Polygon = data.plazas.firstOrNull { it.name == "Center Camp Plaza" }?.polygon
        ?: Polygon.circle(
            Clock.point(layout, Clock.parse(layout.centerCamp.clock)!!.toDouble(), layout.centerCamp.distanceFt),
            layout.centerCamp.plazaRadiusFt,
        )

    /** Inner edge of the city: closer to the Man than this is the inner playa. */
    private val cityInnerFt = esp.radiusFt - esp.widthFt / 2
    /** Outer edge of the city: K plus one K-width of slack for its outer camps' frontage. */
    private val cityOuterFt = outer.radiusFt + outer.widthFt

    /** Ring names/aliases (lowercase) to rings, for parsing. */
    private val ringAliases: Map<String, RingLayout> = buildMap {
        for (r in layout.rings) {
            put(r.letter.lowercase(), r)
            put(r.name.lowercase(), r)
        }
    }

    /**
     * Human address for a position: "7:45 & Esplanade", "4:30 & G",
     * "Deep playa 2:00 @ 3,400 ft", "Center Camp", "Great Circle".
     */
    fun describe(p: LatLon): String = describe(data.xy(p))

    fun describe(p: Xy): String {
        if (greatCircle.contains(p)) return "Great Circle"
        if (centerCamp.contains(p)) return "Center Camp"
        if (!data.fence.contains(p)) return "Outside the fence"
        val r = p.length()
        val clock = Clock.ofPoint(layout, p)
        val clockText = Clock.format(roundTo(clock, 5.0).toInt())
        val far = "$clockText @ ${formatFeet(roundTo(r, 50.0))}"
        return if (inCityArc(clock)) {
            when {
                r < cityInnerFt -> "Inner playa $far"
                r <= cityOuterFt -> "$clockText & ${streetName(nearestRing(r))}"
                else -> "Outer playa $far"
            }
        } else {
            if (r < esp.radiusFt) "Inner playa $far" else "Deep playa $far"
        }
    }

    /** Clock minutes (0 until 720) of [p] as seen from the Man. */
    fun clockOf(p: LatLon): Double = Clock.ofPoint(layout, data.xy(p))

    /** Distance of [p] from the Man in feet. */
    fun radiusOf(p: LatLon): Double = data.xy(p).length()

    /** The annular street whose radius is closest to [radiusFt]. */
    fun nearestRing(radiusFt: Double): RingLayout = layout.rings.minBy { abs(it.radiusFt - radiusFt) }

    /** True if [p] lies within the city's clock arc and between the Esplanade and K. */
    fun isInCity(p: Xy): Boolean {
        val r = p.length()
        return r in cityInnerFt..cityOuterFt && inCityArc(Clock.ofPoint(layout, p))
    }

    /** Tolerates float error so exactly 2:00 / 10:00 count as city. */
    private fun inCityArc(clock: Double) = Clock.inArc(clock, cityStart - CLOCK_EPSILON, cityEnd + CLOCK_EPSILON)

    /** Parses "4:30 & G", "G & 4:30", "4:30 and Esplanade", "2:00 3400'" etc. Null if not an address. */
    fun parse(text: String): LatLon? = parseAddress(text)?.position

    /** Like [parse], but keeps the structure of the address. */
    fun parseAddress(text: String): BrcAddress? {
        val t = text.trim().lowercase(Locale.ROOT)
            .replace('’', '\'').replace('′', '\'')
            .replace(Regex("""\s*(?:ft\.?|feet|foot|')\s*$"""), "")
            .trim()
        if (t.isEmpty() || t.length > 40) return null
        for (sep in SEPARATOR.findAll(t)) {
            val left = t.substring(0, sep.range.first).trim()
            val right = t.substring(sep.range.last + 1).trim()
            if (left.isEmpty() || right.isEmpty()) continue
            build(left, right)?.let { return it }
            build(right, left)?.let { return it }
        }
        return null
    }

    private fun build(clockText: String, otherText: String): BrcAddress? {
        val clock = Clock.parse(clockText) ?: return null
        val ring = ringFor(otherText)
        val radius = ring?.radiusFt ?: parseDistance(otherText) ?: return null
        return BrcAddress(clock, radius, ring, data.latLon(Clock.point(layout, clock.toDouble(), radius)))
    }

    private fun ringFor(text: String): RingLayout? {
        val s = text.replace(Regex("""\s+"""), " ").removeSuffix(" st").removeSuffix(" street").trim()
        ringAliases[s]?.let { return it }
        if (s.length >= 3) return layout.rings.firstOrNull { it.name.lowercase().startsWith(s) }
        return null
    }

    private fun parseDistance(text: String): Double? {
        val m = Regex("""^(\d{1,2}(?:,\d{3})+|\d{1,5})(?:\.\d+)?$""").matchEntire(text.trim()) ?: return null
        val ft = m.value.replace(",", "").toDouble()
        return ft.takeIf { it <= MAX_DISTANCE_FT }
    }

    companion object {
        private const val MAX_DISTANCE_FT = 20_000.0
        private const val CLOCK_EPSILON = 0.01
        private val SEPARATOR = Regex("""\s*(?:&|\band\b|@|\bat\b|\+|/|,(?!\d))\s*|\s+""")

        /** "Esplanade" for the Esplanade, otherwise the ring's letter. */
        fun streetName(ring: RingLayout): String = if (ring.letter == "ESP") ring.name else ring.letter

        /** "3,400 ft". */
        fun formatFeet(ft: Double): String = String.format(Locale.US, "%,d ft", ft.roundToInt())

        private fun roundTo(v: Double, step: Double) = (v / step).roundToInt() * step
    }
}
