package com.zepdriver.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zepdriver.DriveUiState
import com.zepdriver.core.profile.LicenseClass
import com.zepdriver.core.profile.LicenseHours
import com.zepdriver.core.profile.VehicleProfile
import com.zepdriver.ui.MainViewModel
import com.zepdriver.ui.common.DestinationPickerDialog
import com.zepdriver.ui.common.SectionTitle

private fun LicenseClass.explanation(): String = when (this) {
    LicenseClass.MUTANT_VEHICLE -> "Open playa and city streets."
    LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY ->
        "Open playa only. In the city, just the direct route between your camp and the playa, no turning or cruising."
    LicenseClass.ACCESSIBILITY_VEHICLE ->
        "Transport for a participant with a mobility disability; may use city streets. Not subject to the MV sound policy."
}

/** Car profile: name, license, size, phone position, art distance, home camp. Saved as you edit. */
@Composable
internal fun ProfileSection(vm: MainViewModel, ui: DriveUiState) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(profile.carName) }
    var pickingHome by remember { mutableStateOf(false) }

    fun save(update: (VehicleProfile) -> VehicleProfile) = vm.updateProfile(update(vm.profile.value))

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionTitle("Car")
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                save { p -> p.copy(carName = it.trim()) }
            },
            label = { Text("Car name") },
            singleLine = true,
            isError = name.isBlank(),
            supportingText = {
                Text(if (name.isBlank()) "Required for the radio: other cars see this name." else "Shown to other cars on the mesh.")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("License")
        for (c in LicenseClass.entries) {
            RadioRow(
                title = c.label,
                subtitle = c.explanation(),
                selected = profile.licenseClass == c,
                onClick = { save { p -> p.copy(licenseClass = c) } },
            )
        }
        if (profile.licenseClass == LicenseClass.ACCESSIBILITY_VEHICLE) {
            AccessibilityNotes(profile)
        }

        SectionTitle("License hours")
        for (h in LicenseHours.entries) {
            RadioRow(
                title = h.label,
                selected = profile.licenseHours == h,
                onClick = { save { p -> p.copy(licenseHours = h) } },
            )
        }

        SectionTitle("Vehicle")
        SwitchRow(
            title = "Has a sound system",
            subtitle = "Enables sound-policy reminders (quiet hours, burn perimeters).",
            checked = profile.hasSoundSystem,
            onChange = { v -> save { p -> p.copy(hasSoundSystem = v) } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(
                label = "Length",
                value = profile.lengthFt,
                range = 3.0..150.0,
                onValid = { v -> save { p -> p.copy(lengthFt = v) } },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "Width",
                value = profile.widthFt,
                range = 2.0..60.0,
                onValid = { v -> save { p -> p.copy(widthFt = v) } },
                modifier = Modifier.weight(1f),
            )
        }
        NumberField(
            label = "Phone distance from the front",
            value = profile.phoneFromFrontFt,
            range = 0.0..150.0,
            onValid = { v -> save { p -> p.copy(phoneFromFrontFt = v) } },
            modifier = Modifier.fillMaxWidth(),
        )
        NumberField(
            label = "Art safe distance",
            value = profile.artBufferFt,
            range = 0.0..500.0,
            onValid = { v -> save { p -> p.copy(artBufferFt = v) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Routes keep this far from art, measured from the car's edges (default ${VehicleProfile.DEFAULT_ART_BUFFER_FT.toInt()} ft).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("Home camp")
        Text(
            profile.homeCampLabel ?: if (profile.homeCamp != null) "Set" else "Not set",
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickingHome = true }) { Text("Pick from search") }
            OutlinedButton(
                onClick = {
                    val here = ui.drive?.fix?.position
                    if (here != null) {
                        val label = ui.drive?.address ?: vm.describe(here) ?: "Pinned spot"
                        save { p -> p.copy(homeCamp = here, homeCampLabel = label) }
                    }
                },
                enabled = ui.drive?.fix != null,
            ) { Text("Use current location") }
        }
        if (profile.homeCamp != null) {
            TextButton(onClick = { save { p -> p.copy(homeCamp = null, homeCampLabel = null) } }) { Text("Clear home camp") }
        }
        if (profile.licenseClass == LicenseClass.MUTANT_VEHICLE_PLAYA_ONLY && profile.homeCamp == null) {
            Text(
                "Playa-only cars: set your home camp so the app knows your allowed route into the city.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (pickingHome) {
        DestinationPickerDialog(
            vm = vm,
            title = "Choose your home camp",
            onDismiss = { pickingHome = false },
            onPick = { d ->
                val label = listOfNotNull(d.name, d.address?.takeIf { it != d.name }).joinToString(" · ")
                save { p -> p.copy(homeCamp = d.position, homeCampLabel = label) }
                pickingHome = false
            },
        )
    }
}

@Composable
private fun AccessibilityNotes(profile: VehicleProfile) {
    val tooBig = profile.lengthFt > VehicleProfile.AV_MAX_LENGTH_FT || profile.widthFt > VehicleProfile.AV_MAX_WIDTH_FT
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (tooBig) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (tooBig) {
                Text(
                    "Accessibility Vehicles may be at most ${VehicleProfile.AV_MAX_LENGTH_FT.toInt()} ft long and " +
                        "${VehicleProfile.AV_MAX_WIDTH_FT.toInt()} ft wide. This car is " +
                        "${profile.lengthFt.toInt()} × ${profile.widthFt.toInt()} ft.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "The participant with the disability must be aboard whenever the vehicle drives in the city.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
