package com.zepdriver.ui.sim

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zepdriver.SimState
import com.zepdriver.SimulatorController
import com.zepdriver.core.sim.SimMode
import java.util.Locale

/** Steering rate while a steer button is held, degrees per second. */
private const val STEER_DEG_PER_SEC = 25.0
private const val SPEED_STEP_MPH = 0.5

private val PadBackground = Color(0x99000000)
private val ButtonColor = Color(0x33FFFFFF)
private val ButtonPressed = Color(0x88FFFFFF)
private val SimMagenta = Color(0xFFD500F9)

/**
 * Playa simulator overlay for the driving view: an unmistakable "SIMULATED
 * GPS" chip plus a compact control pad. Shows nothing unless the simulator is
 * on. It stays usable while moving (it's a test tool).
 */
@Composable
fun SimOverlay(sim: SimulatorController, modifier: Modifier = Modifier) {
    val state by sim.state.collectAsStateWithLifecycle()
    if (state.enabled) SimPad(sim, state, modifier)
}

@Composable
private fun SimPad(sim: SimulatorController, state: SimState, modifier: Modifier) {
    Surface(
        color = PadBackground,
        contentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.widthIn(max = 172.dp),
    ) {
        Column(
            Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SimulatedChip()
            Text(
                statusLine(state),
                fontSize = 13.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldButton("◀", "Steer left", onPress = { sim.steer(-STEER_DEG_PER_SEC) }, onRelease = { sim.steer(0.0) })
                HoldButton("▶", "Steer right", onPress = { sim.steer(STEER_DEG_PER_SEC) }, onRelease = { sim.steer(0.0) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PadButton("−", "Slower") { sim.changeSpeed(-SPEED_STEP_MPH) }
                Text(
                    String.format(Locale.US, "%.1f", state.targetSpeedMph),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(40.dp),
                )
                PadButton("+", "Faster") { sim.changeSpeed(SPEED_STEP_MPH) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PadButton("Stop", "Stop the simulated car", wide = true) { sim.stop() }
                if (state.mode == SimMode.TOUR && state.tourStep < state.tourSteps) {
                    PadButton("Next", "Next tour segment", wide = true) { sim.nextSegment() }
                }
            }
            PadButton("Sim off", "Turn the simulator off", wide = true) { sim.setEnabled(false) }
        }
    }
}

private fun statusLine(state: SimState): String = when (state.mode) {
    SimMode.MANUAL -> "Manual · target speed (mph)"
    SimMode.AUTOPILOT -> if (state.following) "Autopilot · following route" else "Autopilot · no route"
    SimMode.TOUR -> {
        val step = if (state.tourSteps > 0) "${state.tourStep}/${state.tourSteps} " else ""
        "Tour $step· ${state.tourLabel ?: ""}"
    }
}

@Composable
private fun SimulatedChip() {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SimMagenta)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text("SIMULATED GPS", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

/** Steers while held; releasing (or leaving the screen) straightens the wheel. */
@Composable
private fun HoldButton(label: String, description: String, onPress: () -> Unit, onRelease: () -> Unit) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(width = 64.dp, height = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) ButtonPressed else ButtonColor)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        press()
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            release()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun PadButton(label: String, description: String, wide: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = if (wide) 72.dp else 44.dp, height = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ButtonColor)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = if (wide) 15.sp else 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    }
}
