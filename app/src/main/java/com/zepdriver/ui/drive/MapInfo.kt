package com.zepdriver.ui.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zepdriver.Favorite
import com.zepdriver.PlayaBundle
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.landmarks.Landmarks
import com.zepdriver.core.mesh.FleetVehicle
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.nav.DestinationKind
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.core.rules.DriveState
import com.zepdriver.ui.MainViewModel
import com.zepdriver.ui.common.Fmt
import com.zepdriver.ui.common.label
import com.zepdriver.ui.map.GeoJson
import com.zepdriver.ui.map.MapTap
import com.zepdriver.ui.theme.Zep

/** What the map's info popup says about a tap. */
data class TapInfo(
    val title: String,
    /** Second line: what it is and where, e.g. "Camp · 4:30 & G". */
    val subtitle: String?,
    /** Live details: speed, when a fleet car was last heard. */
    val details: List<String> = emptyList(),
    /** Short description (art, camps, landmarks). */
    val description: String? = null,
    /** Where Go now / Add stop / Save lead; null offers none. */
    val destination: Destination? = null,
    /** Offer Go now / Add stop (not for our own car). */
    val canRoute: Boolean = true,
    /** Offer Save (not for places that already are favorites). */
    val canSave: Boolean = true,
)

private fun sub(vararg parts: String?): String? =
    parts.filterNot { it.isNullOrBlank() }.joinToString(" · ").ifEmpty { null }

/**
 * Turns a map tap into popup text using the current data: our car's live
 * address and speed, fleet cars, places, landmarks, or the BRC address of an
 * empty spot.
 */
fun describeTap(
    tap: MapTap,
    bundle: PlayaBundle,
    drive: DriveState?,
    fleet: List<FleetVehicle>,
    stops: List<Destination>,
    favorites: List<Favorite>,
    profile: VehicleProfile,
    nowMillis: Long,
): TapInfo {
    val data = bundle.data
    fun addr(p: LatLon): String? = runCatching { bundle.addressing.describe(p) }.getOrNull()
    val here = tap.featureAt ?: tap.at

    when (tap.kind) {
        GeoJson.KIND_ME -> {
            val pos = drive?.fix?.position ?: here
            val address = drive?.address ?: addr(pos)
            return TapInfo(
                title = if (address != null) "You're at $address" else "You're here",
                subtitle = sub(profile.carName.ifBlank { "Your car" }, drive?.let { "${Fmt.speed(it.speedMph)} mph" }),
                destination = Destination(address ?: "My spot", pos, DestinationKind.FAVORITE, address = address),
                canRoute = false,
            )
        }
        GeoJson.KIND_FLEET -> {
            val v = fleet.firstOrNull { it.nodeNum.toString() == tap.id }
            if (v != null) {
                val name = v.name.ifBlank { v.shortName.ifBlank { "Car" } }
                val pos = v.position ?: here
                val address = addr(pos)
                val heard = v.positionTimeMillis ?: v.lastHeardMillis
                return TapInfo(
                    title = name,
                    subtitle = sub("Fleet car", address),
                    details = listOfNotNull(
                        "Heard ${Fmt.ago(nowMillis, heard)}",
                        v.speedMph?.let { "${Fmt.speed(it)} mph" },
                    ),
                    destination = Destination(name, pos, DestinationKind.PIN, address = address),
                )
            }
        }
        GeoJson.KIND_STOP -> {
            val i = tap.id?.toIntOrNull()
            val dest = if (i != null) stops.getOrNull(i) else null
            if (i != null && dest != null) {
                return TapInfo(
                    title = dest.name,
                    subtitle = sub("Stop ${i + 1}", dest.address ?: addr(dest.position)),
                    destination = dest,
                )
            }
        }
        GeoJson.KIND_HOME -> {
            val homeFavorite = favorites.firstOrNull { it.label == MainViewModel.LABEL_HOME }
            val pos = profile.homeCamp ?: homeFavorite?.destination?.position ?: here
            val address = profile.homeCampLabel ?: homeFavorite?.destination?.address ?: addr(pos)
            return TapInfo(
                title = MainViewModel.LABEL_HOME,
                subtitle = sub("Home camp", address),
                destination = Destination(MainViewModel.LABEL_HOME, pos, DestinationKind.FAVORITE, address = address),
                canSave = false,
            )
        }
        GeoJson.KIND_FAVORITE -> {
            val f = favorites.firstOrNull { it.id == tap.id }
            if (f != null) {
                val d = f.destination
                val kind = if (d.kind == DestinationKind.FAVORITE) "Favorite" else "Favorite ${d.kind.label().lowercase()}"
                return TapInfo(
                    title = f.label,
                    subtitle = sub(kind, d.address ?: addr(d.position)),
                    destination = d.copy(name = f.label),
                    canSave = false,
                )
            }
        }
        GeoJson.KIND_LANDMARK -> {
            val name = tap.id ?: tap.name
            val xy = name?.let { Landmarks.center(data, it) }
            if (name != null && xy != null) {
                val pos = data.latLon(xy)
                val address = addr(pos)
                return TapInfo(
                    title = name,
                    subtitle = sub("Landmark", address),
                    description = Landmarks.ABOUT[name],
                    destination = Destination(name, pos, DestinationKind.POI, refId = "landmark:$name", address = address),
                )
            }
        }
        GeoJson.KIND_ART -> {
            val art = data.art.firstOrNull { it.id == tap.id }
            if (art != null) {
                val address = addr(art.position)
                return TapInfo(
                    title = art.name,
                    subtitle = sub(art.artist?.takeIf { it.isNotBlank() }?.let { "Art by $it" } ?: "Art", address),
                    description = Fmt.snippet(art.description, 160),
                    destination = Destination(art.name, art.position, DestinationKind.ART, art.id, address),
                )
            }
        }
        GeoJson.KIND_CAMP -> {
            val camp = data.camps.firstOrNull { it.id == tap.id }
            if (camp != null) {
                val address = camp.address?.let { bundle.addressing.parseAddress(it)?.label ?: it } ?: addr(camp.position)
                return TapInfo(
                    title = camp.name,
                    subtitle = sub("Camp", address),
                    description = Fmt.snippet(camp.description, 160),
                    destination = Destination(camp.name, camp.position, DestinationKind.CAMP, camp.id, address),
                )
            }
        }
        GeoJson.KIND_TOILET -> {
            val xy = data.xy(here)
            val i = tap.id?.removePrefix("toilet:")?.toIntOrNull()?.takeIf { it in data.toilets.indices }
                ?: data.toilets.indices.minByOrNull { data.toilets[it].center.distanceTo(xy) }
            if (i != null) {
                val pos = data.latLon(data.toilets[i].center)
                val address = addr(pos)
                return TapInfo(
                    title = "Potties",
                    subtitle = sub("Porta-potties", address),
                    destination = Destination("Porta-potties ${address ?: ""}".trim(), pos, DestinationKind.TOILET, "toilet:$i", address),
                )
            }
        }
        GeoJson.KIND_POI -> {
            val name = tap.name
            if (!name.isNullOrBlank()) {
                val pos = data.pois.firstOrNull { it.name == name }?.position ?: here
                val address = addr(pos)
                return TapInfo(
                    title = name,
                    subtitle = sub("Place", address),
                    destination = Destination(name, pos, DestinationKind.POI, "poi:$name", address),
                )
            }
        }
    }
    // Empty playa (or something that has since gone): the spot's BRC address.
    val address = addr(tap.at) ?: "Open playa"
    return TapInfo(
        title = address,
        subtitle = "Playa",
        destination = Destination(address, tap.at, DestinationKind.ADDRESS, address = address),
    )
}

/**
 * Small popup card describing what was tapped on the map. Actions only when
 * [showActions] (parked); otherwise it's read-only.
 */
@Composable
fun MapInfoCard(
    info: TapInfo,
    showActions: Boolean,
    onGoNow: (Destination) -> Unit,
    onAddStop: (Destination) -> Unit,
    onSave: (Destination) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Zep.colors
    Surface(
        color = c.panel,
        contentColor = c.onPanel,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 6.dp)) {
                    Text(
                        info.title,
                        style = TextStyle(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.ExtraBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    info.subtitle?.let {
                        Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onPanelMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (info.details.isNotEmpty()) {
                    Text(info.details.joinToString(" · "), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                info.description?.let {
                    Text(it, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                val dest = info.destination
                if (showActions && dest != null && (info.canRoute || info.canSave)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        if (info.canRoute) {
                            Button(onClick = { onGoNow(dest) }) { Text("Go now") }
                            OutlinedButton(onClick = { onAddStop(dest) }) { Text("Add stop") }
                        }
                        if (info.canSave) {
                            OutlinedButton(onClick = { onSave(dest) }) { Text("Save") }
                        }
                    }
                }
            }
        }
    }
}
