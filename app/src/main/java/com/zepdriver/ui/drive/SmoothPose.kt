package com.zepdriver.ui.drive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import com.zepdriver.core.geo.signedTurn
import kotlin.math.abs
import kotlin.math.cos

/** Where to draw our vehicle this frame. */
data class Pose(val position: LatLon, val headingDeg: Double)

/**
 * GPS arrives about once a second; drawing the car (and pointing the camera)
 * only then makes driving look like a slideshow. This glides from the last
 * drawn pose to each new fix over the time the fix took to arrive, at ~30 fps,
 * so the car, camera and route line move continuously. Big jumps (a simulator
 * teleport, a GPS glitch) snap instead of sliding across the playa.
 */
@Composable
fun rememberSmoothPose(fix: Fix?, headingDeg: Double): State<Pose?> {
    val pose = remember { mutableStateOf<Pose?>(null) }
    val lastArrival = remember { longArrayOf(0L) }
    LaunchedEffect(fix) {
        if (fix == null) {
            pose.value = null
            return@LaunchedEffect
        }
        val target = Pose(fix.position, headingDeg)
        val from = pose.value
        val arrived = System.nanoTime()
        val gapMs = if (lastArrival[0] == 0L) 0L else (arrived - lastArrival[0]) / 1_000_000
        lastArrival[0] = arrived
        if (from == null || gapMs <= 0 || feetBetween(from.position, target.position) > SNAP_FT) {
            pose.value = target
            return@LaunchedEffect
        }
        val durationMs = gapMs.coerceIn(MIN_GLIDE_MS, MAX_GLIDE_MS).toDouble()
        val start = withFrameNanos { it }
        var lastEmit = 0L
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - start) / 1e6 / durationMs).coerceIn(0.0, 1.0)
            if (t >= 1.0 || now - lastEmit >= FRAME_NS) {
                lastEmit = now
                pose.value = lerp(from, target, t)
            }
            if (t >= 1.0) break
        }
    }
    return pose
}

private fun lerp(a: Pose, b: Pose, t: Double) = Pose(
    LatLon(
        a.position.lat + (b.position.lat - a.position.lat) * t,
        a.position.lon + (b.position.lon - a.position.lon) * t,
    ),
    a.headingDeg + signedTurn(a.headingDeg, b.headingDeg) * t,
)

private fun feetBetween(a: LatLon, b: LatLon): Double {
    val dy = (b.lat - a.lat) * 364_567.2
    val dx = (b.lon - a.lon) * 364_567.2 * cos(Math.toRadians(a.lat))
    return abs(dx) + abs(dy)
}

private const val SNAP_FT = 400.0
private const val MIN_GLIDE_MS = 100L
private const val MAX_GLIDE_MS = 1_500L
/** ~30 fps is plenty for map updates and keeps GeoJSON churn down. */
private const val FRAME_NS = 33_000_000L
