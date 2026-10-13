package com.zepdriver.core.nav

import com.zepdriver.core.geo.LatLon
import kotlinx.serialization.Serializable

@Serializable
enum class DestinationKind { CAMP, ART, TOILET, POI, FAVORITE, ADDRESS, PIN }

@Serializable
data class Destination(
    val name: String,
    val position: LatLon,
    val kind: DestinationKind,
    /** Source id (camp/art uid) when applicable. */
    val refId: String? = null,
    /** BRC address string for display, e.g. "4:30 & G". */
    val address: String? = null,
)
