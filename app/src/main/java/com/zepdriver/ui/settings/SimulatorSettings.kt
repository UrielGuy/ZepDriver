package com.zepdriver.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zepdriver.core.sim.SimMode
import com.zepdriver.core.sim.SimStart
import com.zepdriver.ui.MainViewModel
import com.zepdriver.ui.common.SectionTitle

private fun SimMode.description(): String = when (this) {
    SimMode.MANUAL -> "Steer and set the speed with the controls on the driving view."
    SimMode.TOUR -> "A scripted drive that sets off each warning in turn: speeding, the Great Circle, " +
        "crossing vs driving along the Esplanade, and a loop through the city (about 20 min). " +
        "Use Next on the driving view to skip ahead."
    SimMode.AUTOPILOT -> "Follows the active route at 5 mph (+/− changes the speed). " +
        "Plan a route first; with no route the car slows to a stop."
}

/** "Simulator (testing)": debug builds only. */
@Composable
internal fun SimulatorSection(vm: MainViewModel) {
    val sim = vm.sim
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SectionTitle("Simulator (testing)")
        if (!sim.available) {
            Text("Only available in debug builds.", style = MaterialTheme.typography.bodyMedium)
        } else {
            SimulatorSettings(vm)
        }
    }
}

@Composable
private fun SimulatorSettings(vm: MainViewModel) {
    val sim = vm.sim
    val state by sim.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Replaces GPS with a simulated car at Black Rock City. For testing at home.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Warnings, navigation, Potties and the trip log react as if you were driving on playa. " +
                "Simulated positions are never sent to other cars. Turns itself off when the app restarts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            title = "Simulated GPS",
            subtitle = if (state.enabled) "On: the driving view shows SIMULATED GPS and a control pad" else "Off: using the phone's GPS",
            checked = state.enabled,
            onChange = sim::setEnabled,
        )

        SectionTitle("Mode")
        for (mode in SimMode.entries) {
            RadioRow(
                title = mode.label,
                subtitle = mode.description(),
                selected = state.mode == mode,
                onClick = { sim.setMode(mode) },
            )
        }

        SectionTitle("Start at")
        if (state.mode == SimMode.TOUR) {
            Text(
                "The guided tour has its own start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (start in SimStart.entries) {
            RadioRow(
                title = start.label,
                selected = state.start == start,
                onClick = { sim.setStart(start) },
            )
        }

        SectionTitle("Fleet")
        SwitchRow(
            title = "Fake fleet",
            subtitle = if (state.enabled) {
                "Three made-up cars wander the open playa on the map and in Fleet, and one says hi. Nothing is transmitted."
            } else {
                "Turn on simulated GPS first."
            },
            checked = state.fakeFleet,
            onChange = sim::setFakeFleet,
        )
    }
}
