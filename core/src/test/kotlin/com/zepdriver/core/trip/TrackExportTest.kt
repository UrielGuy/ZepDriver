package com.zepdriver.core.trip

import com.zepdriver.core.alerts.AlertLevel
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackExportTest {
    private val t0 = 1_788_000_000_000L // 2026-08-29T10:40:00Z

    private fun pt(sec: Long, i: Int, level: AlertLevel = AlertLevel.NONE, note: String? = null) =
        TripPoint(t0 + sec * 1000, 40.7864 + i * 1e-4, -119.2065 + i * 1e-4, 4.5 + i, level, note)

    /** Two drives (a 15-minute stop in between) with a warning and an alarm. */
    private val points = listOf(
        pt(0, 0), pt(5, 1), pt(10, 2, AlertLevel.WARNING, "Esplanade — cross only <b>&</b> \"quotes\""),
        pt(15, 3, AlertLevel.CAUTION), pt(20, 4),
        pt(20 + 15 * 60, 5), pt(25 + 15 * 60, 6, AlertLevel.ALARM, null), pt(30 + 15 * 60, 7),
    )

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(xml.byteInputStream(Charsets.UTF_8))

    private fun Document.all(ns: String, tag: String): List<Element> =
        getElementsByTagNameNS(ns, tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    private val kmlNs = "http://www.opengis.net/kml/2.2"
    private val gpxNs = "http://www.topografix.com/GPX/1/1"

    @Test
    fun kmlIsWellFormedAndSplitsAtGaps() {
        val doc = parse(TrackExport.kml("Zebra's <trip> & more", points.shuffled(kotlin.random.Random(1))))
        assertEquals("kml", doc.documentElement.localName)
        assertEquals("Zebra's <trip> & more", doc.all(kmlNs, "Document").single().getElementsByTagNameNS(kmlNs, "name").item(0).textContent)
        val lines = doc.all(kmlNs, "LineString")
        assertEquals(2, lines.size)
        val coords = lines.map { it.getElementsByTagNameNS(kmlNs, "coordinates").item(0).textContent.trim().split(Regex("\\s+")) }
        assertEquals(5, coords[0].size)
        assertEquals(3, coords[1].size)
        assertEquals("-119.2065000,40.7864000", coords[0][0]) // lon,lat order, time-sorted
        val spans = doc.all(kmlNs, "TimeSpan")
        assertEquals("2026-08-29T10:40:00Z", spans[0].getElementsByTagNameNS(kmlNs, "begin").item(0).textContent)
        assertEquals("2026-08-29T10:40:20Z", spans[0].getElementsByTagNameNS(kmlNs, "end").item(0).textContent)

        // Only WARNING / ALARM points become placemarks; the note is escaped and round-trips.
        val points = doc.all(kmlNs, "Point")
        assertEquals(2, points.size)
        val names = doc.all(kmlNs, "Placemark").map { it.getElementsByTagNameNS(kmlNs, "name").item(0).textContent }
        assertTrue("Esplanade — cross only <b>&</b> \"quotes\"" in names)
        assertTrue("Alarm" in names)
        val whens = doc.all(kmlNs, "when").map { it.textContent }
        assertEquals(listOf("2026-08-29T10:40:10Z", "2026-08-29T10:55:25Z"), whens)
        assertEquals(3, doc.all(kmlNs, "Style").size)
    }

    @Test
    fun gpxIsWellFormedAndSplitsAtGaps() {
        val doc = parse(TrackExport.gpx("Night ride & <stuff>", points))
        val root = doc.documentElement
        assertEquals("gpx", root.localName)
        assertEquals("1.1", root.getAttribute("version"))
        val segs = doc.all(gpxNs, "trkseg")
        assertEquals(2, segs.size)
        assertEquals(5, segs[0].getElementsByTagNameNS(gpxNs, "trkpt").length)
        assertEquals(3, segs[1].getElementsByTagNameNS(gpxNs, "trkpt").length)
        val first = segs[0].getElementsByTagNameNS(gpxNs, "trkpt").item(0) as Element
        assertEquals("40.7864000", first.getAttribute("lat"))
        assertEquals("-119.2065000", first.getAttribute("lon"))
        assertEquals("2026-08-29T10:40:00Z", first.getElementsByTagNameNS(gpxNs, "time").item(0).textContent)
        assertEquals("Night ride & <stuff>", doc.all(gpxNs, "trk").single().getElementsByTagNameNS(gpxNs, "name").item(0).textContent)
        assertEquals(2, doc.all(gpxNs, "wpt").size)
    }

    @Test
    fun gapExactlyTenMinutesDoesNotSplit() {
        val pts = listOf(pt(0, 0), pt(600, 1), pt(1201, 2))
        assertEquals(listOf(2, 1), TrackExport.segments(pts).map { it.size })
    }

    @Test
    fun emptyAndSinglePointTracks() {
        parse(TrackExport.kml("empty", emptyList()))
        parse(TrackExport.gpx("empty", emptyList()))
        val one = parse(TrackExport.kml("one", listOf(pt(0, 0))))
        // A LineString needs two coordinates.
        assertEquals(2, one.all(kmlNs, "coordinates").single().textContent.trim().split(Regex("\\s+")).size)
    }

    @Test
    fun escapesAndDropsInvalidXmlChars() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", TrackExport.esc("a&b<c>d\"e'f"))
        assertEquals("ok🦓", TrackExport.esc("o\u0001k\u0000🦓"))
        parse(TrackExport.gpx("bell\u0007", listOf(pt(0, 0, AlertLevel.ALARM, "x\u0008y"))))
    }
}
