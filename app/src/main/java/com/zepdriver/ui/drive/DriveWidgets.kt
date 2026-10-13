package com.zepdriver.ui.drive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zepdriver.MeshConnection
import com.zepdriver.MeshUiState
import com.zepdriver.core.alerts.Alert
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.geo.FEET_PER_METER
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.nav.NavState
import com.zepdriver.core.nav.ToiletPointer
import com.zepdriver.ui.common.Fmt
import com.zepdriver.ui.theme.Zep

/** Panel background/content for an alert level; ALARM alternates with [flashOn]. */
@Composable
private fun levelColors(level: AlertLevel, flashOn: Boolean): Pair<Color, Color> {
    val c = Zep.colors
    return if (level == AlertLevel.ALARM && flashOn) c.alarmAlt to c.onAlarmAlt else c.forLevel(level)
}

/** The huge glanceable speedometer, colored by the speed level. */
@Composable
fun Speedometer(speedMph: Double?, level: AlertLevel, flashOn: Boolean, modifier: Modifier = Modifier) {
    val (bg, fg) = levelColors(level, flashOn)
    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Column(
            Modifier.widthIn(min = 168.dp).padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                Fmt.speed(speedMph),
                style = TextStyle(
                    fontSize = 104.sp,
                    lineHeight = 108.sp,
                    fontWeight = FontWeight.Black,
                    fontFeatureSettings = "tnum",
                ),
                maxLines = 1,
            )
            Text(
                "mph",
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** Banner for the most severe alert. */
@Composable
fun AlertBanner(alert: Alert, flashOn: Boolean, modifier: Modifier = Modifier) {
    val (bg, fg) = levelColors(alert.level, flashOn)
    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                alert.title,
                style = TextStyle(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = alert.detail
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = TextStyle(fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Turn-by-turn panel while navigating. [nowMillis] is the time the ETA counts from. */
@Composable
fun NavPanel(
    nav: NavState,
    stops: List<Destination>,
    canEnd: Boolean,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val c = Zep.colors
    val leg = nav.plan.legs.getOrNull(nav.legIndex)
    val stopName = leg?.destination?.name ?: stops.firstOrNull()?.name ?: "Destination"
    Surface(
        color = c.panel,
        contentColor = c.onPanel,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (nav.finished) {
                Text(
                    "Arrived: $stopName",
                    style = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(onClick = onEnd, modifier = Modifier.heightIn(min = 52.dp)) { Text("Done", fontSize = 18.sp) }
            } else {
                RouteProgress(nav, stopName, leg?.walkFt, canEnd, onEnd, nowMillis)
            }
        }
    }
}

@Composable
private fun RouteProgress(nav: NavState, stopName: String, walk: Double?, canEnd: Boolean, onEnd: () -> Unit, nowMillis: Long) {
    val c = Zep.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val next = nav.nextInstruction
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                next?.text ?: "Continue to $stopName",
                style = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (next != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    Fmt.distance(nav.distanceToNextFt),
                    style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Black, fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "To $stopName",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                Fmt.distance(nav.toStopFt),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.onPanelMuted,
            )
        }
        // ETA to the current stop, assuming the 5 mph limit the whole way.
        val minutes = nav.minutesToStop()
        Text(
            "ETA ${Fmt.duration(minutes)} · ${Fmt.clockIn(nowMillis, minutes)} at 5 mph",
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
        if (walk != null && walk > 0) {
            Text(
                "Park here and walk ${Fmt.feet(walk)}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val more = nav.plan.legs.size - nav.legIndex - 1
        if (more > 0 || canEnd) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (more > 0) {
                    Text(
                        "Then $more more stop${if (more == 1) "" else "s"}",
                        fontSize = 15.sp,
                        color = c.onPanelMuted,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                // Only offered while stopped: ending the route is a change, and
                // nothing changes while the car moves.
                if (canEnd) {
                    OutlinedButton(onClick = onEnd) { Text("End route") }
                }
            }
        }
    }
}

/**
 * Small GPS / radio / routing indicators for a corner of the driving view.
 * (The BRC address shows when you tap your car on the map.)
 */
@Composable
fun StatusChip(
    fix: Fix?,
    mesh: MeshUiState,
    fleetCount: Int,
    routerBusy: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Zep.colors
    Surface(
        color = c.panel.copy(alpha = 0.92f),
        contentColor = c.onPanel,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            GpsIndicator(fix)
            MeshIndicator(mesh, fleetCount)
            if (routerBusy) StatusDot(c.caution, "Preparing routes…")
        }
    }
}

@Composable
private fun GpsIndicator(fix: Fix?) {
    val c = Zep.colors
    val acc = fix?.accuracyM
    when {
        fix == null -> StatusDot(c.bad, "No GPS")
        acc == null -> StatusDot(c.good, "GPS")
        acc > 25 -> StatusDot(c.caution, "GPS weak ±${(acc * FEET_PER_METER).toInt()} ft")
        else -> StatusDot(c.good, "GPS ±${(acc * FEET_PER_METER).toInt()} ft")
    }
}

@Composable
private fun MeshIndicator(mesh: MeshUiState, fleetCount: Int) {
    val c = Zep.colors
    when (mesh.connection) {
        MeshConnection.CONNECTED -> StatusDot(c.good, if (fleetCount > 0) "Mesh · $fleetCount car${if (fleetCount == 1) "" else "s"}" else "Mesh")
        MeshConnection.SCANNING, MeshConnection.CONNECTING, MeshConnection.PROVISIONING -> StatusDot(c.caution, "Radio connecting")
        MeshConnection.FLASHING -> StatusDot(c.caution, "Radio flashing")
        MeshConnection.ERROR -> StatusDot(c.bad, "Radio error")
        MeshConnection.NO_CAR_NAME, MeshConnection.DISCONNECTED -> StatusDot(c.onPanelMuted, "No radio")
    }
}

@Composable
private fun StatusDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Zep.colors.onPanelMuted, maxLines = 1)
    }
}

/**
 * The one control that works in every mode. With a [pointer] it also shows an
 * arrow (rotated by [arrowRotationDeg] from screen-up) and the distance to the
 * nearest porta-potties.
 */
@Composable
fun PottiesButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pointer: ToiletPointer? = null,
    arrowRotationDeg: Float = 0f,
) {
    val c = Zep.colors
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = c.potties, contentColor = c.onPotties),
        shape = RoundedCornerShape(24.dp),
        contentPadding = ButtonDefaults.ContentPadding,
        modifier = modifier.height(84.dp).widthIn(min = 150.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
    ) {
        if (pointer == null) {
            Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(8.dp))
            Text("Potties", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        } else {
            DirectionArrow(arrowRotationDeg, c.onPotties, Modifier.size(36.dp))
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Potties", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 26.sp)
                Text(
                    Fmt.distance(pointer.distanceFt),
                    style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                )
            }
        }
    }
}

/** A solid arrowhead pointing up, rotated clockwise by [rotationDeg]. */
@Composable
private fun DirectionArrow(rotationDeg: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.rotate(rotationDeg)) {
        val w = size.width
        val h = size.height
        val arrow = Path().apply {
            moveTo(w / 2, 0f)
            lineTo(w, h)
            lineTo(w / 2, h * 0.72f)
            lineTo(0f, h)
            close()
        }
        drawPath(arrow, color)
    }
}

/** Full-screen flashing border for alarms. */
@Composable
fun AlarmBorder(flashOn: Boolean, modifier: Modifier = Modifier) {
    val c = Zep.colors
    Box(modifier.fillMaxSize().border(16.dp, if (flashOn) c.alarmAlt else c.alarm))
}

/**
 * Debug builds only: overrides the moving lock so everything stays usable
 * while driving (for testing with the simulator). Amber "Unlocked" when on.
 */
@Composable
fun DebugLockToggle(unlocked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Zep.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (unlocked) c.caution else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        contentColor = if (unlocked) Color.Black else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.height(36.dp),
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = if (unlocked) "Lock the screen while moving" else "Unlock the screen while moving (debug)",
                modifier = Modifier.size(18.dp),
            )
            if (unlocked) {
                Spacer(Modifier.width(6.dp))
                Text("Unlocked", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
