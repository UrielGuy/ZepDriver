package com.zepdriver.core.search

import com.zepdriver.core.brc.BrcAddressing
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.Xy
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.nav.DestinationKind
import java.text.Normalizer

data class SearchResult(
    val destination: Destination,
    /** Second line: address and kind, e.g. "Camp · 4:30 & G". */
    val subtitle: String,
    val description: String?,
)

/** Offline search over camps, art, points of interest and BRC addresses. */
class SearchIndex(private val data: PlayaData, private val addressing: BrcAddressing) {
    private class Entry(
        val result: SearchResult,
        val xy: Xy,
        val name: String,
        /** [name] without a leading "the ", so "man" finds "The Man" exactly. */
        val bare: String,
        /** " word word" — leading space so " q" finds word prefixes. */
        val nameWords: String,
        /** Artist / landmark. */
        val secondary: String,
        val address: String,
        val hometown: String,
        val description: String,
    )

    private val entries: List<Entry>
    private val toilets: List<Entry>

    init {
        val list = ArrayList<Entry>()
        data.camps.forEachIndexed { i, c ->
            val xy = data.campXy[i]
            val address = c.address?.let { addressing.parseAddress(it)?.label ?: it } ?: addressing.describe(xy)
            list += entry(
                Destination(c.name, c.position, DestinationKind.CAMP, c.id, address), xy,
                "Camp · $address", c.description,
                secondary = c.landmark, address = c.address, hometown = c.hometown,
            )
        }
        data.art.forEachIndexed { i, a ->
            val xy = data.artXy[i]
            val address = addressing.describe(xy)
            list += entry(
                Destination(a.name, a.position, DestinationKind.ART, a.id, address), xy,
                "Art · $address", a.description,
                secondary = a.artist, address = a.address, hometown = a.hometown,
            )
        }
        data.pois.filter { it.name.isNotBlank() }.forEach { p ->
            val address = addressing.describe(p.xy)
            list += entry(
                Destination(p.name, p.position, DestinationKind.POI, "poi:${p.name}", address), p.xy,
                "Place · $address", null,
            )
        }
        entries = list
        toilets = data.toilets.mapIndexed { i, t ->
            val address = addressing.describe(t.center)
            entry(
                Destination("Porta-potties $address", data.latLon(t.center), DestinationKind.TOILET, "toilet:$i", address),
                t.center, "Porta-potties · $address", null,
            )
        }
    }

    private fun entry(
        destination: Destination, xy: Xy, subtitle: String, description: String?,
        secondary: String? = null, address: String? = null, hometown: String? = null,
    ): Entry {
        val name = normalize(destination.name)
        return Entry(
            SearchResult(destination, subtitle, description), xy, name, name.removePrefix("the "), " $name",
            " " + normalize(secondary.orEmpty()), " " + normalize(address.orEmpty()),
            " " + normalize(hometown.orEmpty()), " " + normalize(description.orEmpty()),
        )
    }

    /**
     * Ranked results for [query]. An address ("4:30 & G") comes first; toilet words
     * ("potty", "restroom") list the porta-potty banks. With [near], equally ranked
     * results are ordered by distance.
     */
    fun search(query: String, limit: Int = 50, near: LatLon? = null): List<SearchResult> {
        val q = normalize(query)
        if (q.isEmpty() || limit <= 0) return emptyList()
        val tokens = q.split(' ')
        val origin = near?.let { data.xy(it) }
        val scored = ArrayList<Pair<Int, Entry>>()
        for (e in entries) {
            val s = score(e, q, tokens)
            if (s > 0) scored += s to e
        }
        val toiletIntent = isToiletQuery(q)
        for (e in toilets) {
            val s = if (toiletIntent) SCORE_TOILET_INTENT else score(e, q, tokens)
            if (s > 0) scored += s to e
        }
        scored.sortWith(
            compareByDescending<Pair<Int, Entry>> { it.first }
                .thenBy { p -> origin?.let { p.second.xy.distanceTo(it) } ?: 0.0 }
                .thenBy { it.second.name.length }
                .thenBy { it.second.name },
        )
        val out = ArrayList<SearchResult>(minOf(limit, scored.size + 1))
        addressing.parseAddress(query)?.let { a ->
            val described = addressing.describe(a.position)
            out += SearchResult(
                Destination(a.label, a.position, DestinationKind.ADDRESS, address = a.label),
                if (described == a.label) "Address" else "Address · $described",
                null,
            )
        }
        for ((_, e) in scored) {
            if (out.size >= limit) break
            out += e.result
        }
        return out
    }

    private fun score(e: Entry, q: String, tokens: List<String>): Int {
        val wordQ = " $q"
        return when {
            e.name == q || e.bare == q -> 1000
            e.name.startsWith(q) || e.bare.startsWith(q) -> 800
            e.nameWords.contains(wordQ) || allWordPrefixes(e.nameWords, tokens) -> 600
            e.name.contains(q) -> 400
            e.secondary.contains(q) || allWordPrefixes(e.secondary, tokens) -> 300
            e.address.contains(wordQ) || allWordPrefixes(e.address, tokens) -> 250
            e.hometown.contains(wordQ) -> 150
            e.description.contains(q) || allWordPrefixes(e.description, tokens) -> 100
            else -> 0
        }
    }

    private fun allWordPrefixes(field: String, tokens: List<String>): Boolean =
        field.length > 1 && tokens.all { field.contains(" $it") }

    private fun isToiletQuery(q: String): Boolean {
        val compact = q.replace(" ", "").removeSuffix("s")
        return TOILET_WORDS.any { it == compact || (compact.length >= 4 && it.startsWith(compact)) }
    }

    companion object {
        /** Below an exact name match, above everything else. */
        private const val SCORE_TOILET_INTENT = 900
        /** Matched whole (plural "s" ignored), or by a prefix of 4+ letters. */
        private val TOILET_WORDS = listOf(
            "potty", "pottie", "portapotty", "portapottie", "portajohn", "porta", "toilet", "restroom",
            "bathroom", "latrine", "honeybucket", "loo", "wc", "pee",
        )
        private val MARKS = Regex("""\p{M}+""")
        private val APOSTROPHES = Regex("""['’`]""")
        private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

        /** Lowercase, accents and punctuation stripped, single spaces. */
        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(MARKS, "")
                .lowercase()
                .replace(APOSTROPHES, "")
                .replace(NON_ALNUM, " ")
                .trim()
    }
}
