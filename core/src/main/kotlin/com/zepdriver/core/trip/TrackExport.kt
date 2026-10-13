package com.zepdriver.core.trip

import com.zepdriver.core.alerts.AlertLevel
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One logged point of our own vehicle's track. */
data class TripPoint(
    val timeMillis: Long,
    val lat: Double,
    val lon: Double,
    val speedMph: Double,
    /** Most severe alert at this point. */
    val level: AlertLevel = AlertLevel.NONE,
    val note: String? = null,
)

/** Writes tracks for Google Earth / My Maps (KML) and other tools (GPX). */
object TrackExport {
    /** A gap longer than this between consecutive points starts a new line / segment. */
    const val SEGMENT_GAP_MILLIS = 10 * 60_000L

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    private val LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    /**
     * KML 2.2 for Google Earth and Google My Maps import: one styled
     * LineString per continuous drive (split at gaps > 10 min, with a
     * TimeSpan), plus a timestamped placemark for every WARNING / ALARM point
     * (its note as the description).
     */
    fun kml(name: String, points: List<TripPoint>): String {
        val sorted = points.sortedBy { it.timeMillis }
        val segments = segments(sorted)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<kml xmlns="http://www.opengis.net/kml/2.2">""").append('\n')
        sb.append("<Document>\n")
        sb.append("  <name>").append(esc(name)).append("</name>\n")
        sb.append(
            """
            |  <Style id="track"><LineStyle><color>ff00a5ff</color><width>4</width></LineStyle></Style>
            |  <Style id="warning"><IconStyle><color>ff00d7ff</color><scale>1.1</scale><Icon><href>https://maps.google.com/mapfiles/kml/shapes/caution.png</href></Icon></IconStyle></Style>
            |  <Style id="alarm"><IconStyle><color>ff0000ff</color><scale>1.3</scale><Icon><href>https://maps.google.com/mapfiles/kml/shapes/caution.png</href></Icon></IconStyle></Style>
            |
            """.trimMargin(),
        )
        if (segments.isNotEmpty()) {
            sb.append("  <Folder>\n    <name>Track</name>\n")
            segments.forEachIndexed { i, seg ->
                val first = seg.first()
                val last = seg.last()
                val maxMph = seg.maxOf { it.speedMph }
                sb.append("    <Placemark>\n")
                sb.append("      <name>").append(esc(if (segments.size == 1) name else "Drive ${i + 1}")).append("</name>\n")
                sb.append("      <description>")
                    .append(esc("${LABEL.format(Instant.ofEpochMilli(first.timeMillis))}, ${seg.size} points, max %.1f mph".format(Locale.US, maxMph)))
                    .append("</description>\n")
                sb.append("      <TimeSpan><begin>").append(iso(first.timeMillis)).append("</begin><end>")
                    .append(iso(last.timeMillis)).append("</end></TimeSpan>\n")
                sb.append("      <styleUrl>#track</styleUrl>\n")
                sb.append("      <LineString>\n        <tessellate>1</tessellate>\n        <coordinates>\n")
                // A LineString needs two coordinates; repeat a lone point.
                val coords = if (seg.size == 1) listOf(first, first) else seg
                coords.forEach { sb.append("          ").append(coord(it.lon)).append(',').append(coord(it.lat)).append('\n') }
                sb.append("        </coordinates>\n      </LineString>\n    </Placemark>\n")
            }
            sb.append("  </Folder>\n")
        }
        val events = sorted.filter { it.level == AlertLevel.WARNING || it.level == AlertLevel.ALARM }
        if (events.isNotEmpty()) {
            sb.append("  <Folder>\n    <name>Warnings</name>\n")
            for (p in events) {
                val label = p.note?.takeIf { it.isNotBlank() } ?: if (p.level == AlertLevel.ALARM) "Alarm" else "Warning"
                sb.append("    <Placemark>\n")
                sb.append("      <name>").append(esc(label)).append("</name>\n")
                sb.append("      <description>")
                    .append(esc("${p.level.name.lowercase().replaceFirstChar { it.uppercase() }} at ${LABEL.format(Instant.ofEpochMilli(p.timeMillis))}, %.1f mph".format(Locale.US, p.speedMph)))
                    .append("</description>\n")
                sb.append("      <TimeStamp><when>").append(iso(p.timeMillis)).append("</when></TimeStamp>\n")
                sb.append("      <styleUrl>#").append(if (p.level == AlertLevel.ALARM) "alarm" else "warning").append("</styleUrl>\n")
                sb.append("      <Point><coordinates>").append(coord(p.lon)).append(',').append(coord(p.lat)).append("</coordinates></Point>\n")
                sb.append("    </Placemark>\n")
            }
            sb.append("  </Folder>\n")
        }
        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    /** GPX 1.1: one trk with a trkseg per continuous drive (split at gaps > 10 min), UTC ISO-8601 times. */
    fun gpx(name: String, points: List<TripPoint>): String {
        val sorted = points.sortedBy { it.timeMillis }
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="ZepDriver" xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
                """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""",
        ).append('\n')
        sb.append("  <metadata>\n    <name>").append(esc(name)).append("</name>\n")
        sorted.firstOrNull()?.let { sb.append("    <time>").append(iso(it.timeMillis)).append("</time>\n") }
        sb.append("  </metadata>\n")
        // Warnings as waypoints (GPX puts wpt before trk).
        for (p in sorted.filter { it.level == AlertLevel.WARNING || it.level == AlertLevel.ALARM }) {
            sb.append("  <wpt lat=\"").append(coord(p.lat)).append("\" lon=\"").append(coord(p.lon)).append("\">\n")
            sb.append("    <time>").append(iso(p.timeMillis)).append("</time>\n")
            sb.append("    <name>").append(esc(p.note?.takeIf { it.isNotBlank() } ?: p.level.name)).append("</name>\n")
            sb.append("    <type>").append(p.level.name).append("</type>\n")
            sb.append("  </wpt>\n")
        }
        sb.append("  <trk>\n    <name>").append(esc(name)).append("</name>\n")
        for (seg in segments(sorted)) {
            sb.append("    <trkseg>\n")
            for (p in seg) {
                sb.append("      <trkpt lat=\"").append(coord(p.lat)).append("\" lon=\"").append(coord(p.lon)).append("\">")
                sb.append("<time>").append(iso(p.timeMillis)).append("</time>")
                // GPX 1.1 has no speed element; record it as a comment so it survives most tools.
                sb.append("<cmt>").append("%.1f mph".format(Locale.US, p.speedMph)).append("</cmt>")
                sb.append("</trkpt>\n")
            }
            sb.append("    </trkseg>\n")
        }
        sb.append("  </trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Splits time-sorted [points] wherever consecutive points are more than [SEGMENT_GAP_MILLIS] apart. */
    fun segments(points: List<TripPoint>): List<List<TripPoint>> {
        val out = ArrayList<List<TripPoint>>()
        var cur = ArrayList<TripPoint>()
        for (p in points) {
            if (cur.isNotEmpty() && p.timeMillis - cur.last().timeMillis > SEGMENT_GAP_MILLIS) {
                out += cur
                cur = ArrayList()
            }
            cur += p
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun iso(millis: Long): String = ISO.format(Instant.ofEpochMilli(millis))

    private fun coord(v: Double): String = "%.7f".format(Locale.US, v)

    /** XML 1.0 escaping; drops characters XML can't represent. */
    internal fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '&'.code -> sb.append("&amp;")
                cp == '<'.code -> sb.append("&lt;")
                cp == '>'.code -> sb.append("&gt;")
                cp == '"'.code -> sb.append("&quot;")
                cp == '\''.code -> sb.append("&apos;")
                cp == 0x9 || cp == 0xA || cp == 0xD -> sb.appendCodePoint(cp)
                cp < 0x20 || cp in 0xD800..0xDFFF || cp == 0xFFFE || cp == 0xFFFF -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }
}
