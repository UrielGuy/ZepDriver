package com.zepdriver.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zepdriver.DriveUiState
import com.zepdriver.PlayaBundle
import com.zepdriver.ThemeMode
import com.zepdriver.core.rules.Zone
import com.zepdriver.core.rules.ZoneKind
import com.zepdriver.core.vehicles.VehicleModel
import com.zepdriver.ui.MainViewModel
import com.zepdriver.ui.SettingsSection
import com.zepdriver.ui.common.BM_DISCLAIMER
import com.zepdriver.ui.common.EmbargoStatusText
import com.zepdriver.ui.common.Fmt
import com.zepdriver.ui.common.SectionTitle
import kotlin.math.roundToInt

/** Settings (parked only), one section at a time. */
@Composable
fun SettingsScreen(vm: MainViewModel, bundle: PlayaBundle, ui: DriveUiState) {
    val section by vm.settingsSection.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (s in SettingsSection.entries.filter { it != SettingsSection.SIMULATOR || vm.sim.available }) {
                FilterChip(
                    selected = s == section,
                    onClick = { vm.setSettingsSection(s) },
                    label = { Text(s.label) },
                )
            }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f)) {
            when (section) {
                SettingsSection.CAR -> ProfileSection(vm, ui)
                SettingsSection.APP -> AppSection(vm)
                SettingsSection.RADIO -> RadioSection(vm)
                SettingsSection.TRIPS -> TripSection(vm)
                SettingsSection.DATA -> DataSection(vm, bundle)
                SettingsSection.RULES -> RulesSection(vm, bundle)
                SettingsSection.SIMULATOR -> SimulatorSection(vm)
            }
        }
    }
}

private val TRAIL_CHOICES = listOf(1, 3, 6, 12, 24, 48, 72, 24 * 7)

@Composable
private fun AppSection(vm: MainViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var tilt by remember { mutableFloatStateOf(prefs.mapPitchDeg.toFloat()) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SectionTitle("Voice")
        SwitchRow(
            title = "Voice warnings and directions",
            subtitle = "Speed, zone warnings and turn-by-turn. Audio can go to the car's sound system over Bluetooth.",
            checked = prefs.voiceEnabled,
            onChange = { vm.updatePrefs(vm.prefs.value.copy(voiceEnabled = it)) },
        )
        VoiceStatus(vm)

        SectionTitle("Theme")
        RadioRow(
            title = "Automatic",
            subtitle = "Follows the light sensor; sunrise/sunset if there is none.",
            selected = prefs.themeMode == ThemeMode.AUTO,
            onClick = { vm.updatePrefs(vm.prefs.value.copy(themeMode = ThemeMode.AUTO)) },
        )
        RadioRow(
            title = "Day",
            subtitle = "High contrast for sunlight.",
            selected = prefs.themeMode == ThemeMode.DAY,
            onClick = { vm.updatePrefs(vm.prefs.value.copy(themeMode = ThemeMode.DAY)) },
        )
        RadioRow(
            title = "Night",
            subtitle = "Dark and red-shifted to keep your night vision.",
            selected = prefs.themeMode == ThemeMode.NIGHT,
            onClick = { vm.updatePrefs(vm.prefs.value.copy(themeMode = ThemeMode.NIGHT)) },
        )

        SectionTitle("My vehicle on the map")
        Text(
            "A 3D model drawn larger than life so you can spot yourself. Tap it on the map to see your address.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (m in VehicleModel.entries) {
            RadioRow(
                title = m.label,
                subtitle = m.description,
                selected = prefs.vehicleModel == m,
                onClick = { vm.updatePrefs(vm.prefs.value.copy(vehicleModel = m)) },
            )
        }

        SectionTitle("Map")
        SwitchRow(
            title = "Potty arrow",
            subtitle = "Always point to the nearest porta-potties, with the distance.",
            checked = prefs.showPottyArrow,
            onChange = { vm.updatePrefs(vm.prefs.value.copy(showPottyArrow = it)) },
        )
        SwitchRow(
            title = "Show my trail",
            subtitle = "Only on this phone; never sent to other cars.",
            checked = prefs.showTrail,
            onChange = { vm.updatePrefs(vm.prefs.value.copy(showTrail = it)) },
        )
        if (prefs.showTrail) {
            Text("Trail length", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (h in TRAIL_CHOICES) {
                    FilterChip(
                        selected = prefs.trailHours == h,
                        onClick = { vm.updatePrefs(vm.prefs.value.copy(trailHours = h)) },
                        label = { Text(Fmt.hours(h)) },
                    )
                }
            }
        }
        Text(
            "Map tilt while driving: ${tilt.roundToInt()}°",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Slider(
            value = tilt,
            onValueChange = { tilt = it },
            onValueChangeFinished = { vm.updatePrefs(vm.prefs.value.copy(mapPitchDeg = tilt.roundToInt().toDouble())) },
            valueRange = 0f..60f,
            steps = 11,
        )
    }
}

@Composable
private fun DataSection(vm: MainViewModel, bundle: PlayaBundle) {
    val status by vm.updateStatus.collectAsStateWithLifecycle()
    val embargo by vm.embargo.collectAsStateWithLifecycle()
    val timeSource by vm.timeSource.collectAsStateWithLifecycle()
    val data = bundle.data
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionTitle("City data")
        Text("Black Rock City ${data.layout.year}", style = MaterialTheme.typography.titleMedium)
        Text("Data version ${data.dataVersion}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "${data.camps.size} camps · ${data.art.size} art pieces · ${data.toilets.size} potty banks",
            style = MaterialTheme.typography.bodyMedium,
        )
        EmbargoStatusText(embargo, timeSource)
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Button(onClick = vm::checkForUpdate, modifier = Modifier.fillMaxWidth()) { Text("Check for update") }
        Text(
            "Everything works offline. When there's internet, newer data is downloaded in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(data.layout.attribution, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(BM_DISCLAIMER, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun ZoneKind.label(): String = when (this) {
    ZoneKind.NO_ENTRY -> "No entry"
    ZoneKind.CROSS_ONLY -> "Cross only"
    ZoneKind.LICENSE_RESTRICTED -> "License restricted"
    ZoneKind.SOUND_OFF -> "Sound off"
    ZoneKind.CAUTION_AREA -> "Caution"
    else -> name
}

@Composable
private fun RulesSection(vm: MainViewModel, bundle: PlayaBundle) {
    val zones by vm.zones.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val rules = bundle.ruleSet
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("${rules.year} driving rules")
        Text(
            "Speed limit ${rules.speedLimitMph.roundToInt()} mph everywhere. These rules are built into the app and " +
                "reviewed against the DMV's Mutant Vehicle rules and sound policy on burningman.org before every burn. " +
                "They can't be edited here.",
            style = MaterialTheme.typography.bodyMedium,
        )
        // One card per zone name (some rules have several shapes or entries).
        for (z in zones.distinctBy { it.name to it.kind }) {
            ZoneCard(z, applies = runCatching { z.appliesTo(profile) }.getOrDefault(true))
        }
        SectionTitle("Sources")
        for (s in rules.sources) {
            Text("• $s", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ZoneCard(z: Zone, applies: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(z.name, style = MaterialTheme.typography.titleMedium)
            Text(
                z.kind.label() + if (applies) "" else " · doesn't apply to your car",
                style = MaterialTheme.typography.labelLarge,
                color = if (applies) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            z.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Recorded warnings + the phone's offline voice for directions, with install and test. */
@Composable
private fun VoiceStatus(vm: MainViewModel) {
    val voice by vm.voice.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (voice.warningClips > 0) "Warnings: ${voice.warningClips} recorded clips, always available"
            else "Warnings: spoken by the phone's voice (this build has no recorded clips)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            when (voice.offlineVoice) {
                null -> "Directions voice: checking…"
                true -> "Directions voice: offline English voice installed"
                false -> "Directions voice: no offline English voice. Directions won't be spoken without internet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (voice.offlineVoice == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::testVoice) { Text("Test voice") }
            if (voice.offlineVoice == false) {
                Button(onClick = vm::installVoice) { Text("Install voice") }
                TextButton(onClick = vm::refreshVoice) { Text("Check again") }
            }
        }
    }
}
