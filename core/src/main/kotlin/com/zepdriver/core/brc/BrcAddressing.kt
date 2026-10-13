package com.zepdriver.core.brc

import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.geo.LatLon

/** Converts between coordinates and Black Rock City addresses. */
class BrcAddressing(private val data: PlayaData) {
    /**
     * Human address for a position: "7:45 & Esplanade", "4:30 & G",
     * "Deep playa 2:00 @ 3,400 ft", "Center Camp", "Great Circle".
     */
    fun describe(p: LatLon): String = TODO("rules agent")

    /** Parses "4:30 & G", "G & 4:30", "4:30 and Esplanade", "2:00 3400'" etc. Null if not an address. */
    fun parse(text: String): LatLon? = TODO("rules agent")
}
