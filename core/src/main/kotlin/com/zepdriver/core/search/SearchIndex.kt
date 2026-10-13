package com.zepdriver.core.search

import com.zepdriver.core.brc.BrcAddressing
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.nav.Destination

data class SearchResult(
    val destination: Destination,
    /** Second line: address and kind, e.g. "Camp · 4:30 & G". */
    val subtitle: String,
    val description: String?,
)

/** Offline search over camps, art, points of interest and BRC addresses. */
class SearchIndex(private val data: PlayaData, private val addressing: BrcAddressing) {
    fun search(query: String, limit: Int = 50): List<SearchResult> = TODO("rules agent")
}
