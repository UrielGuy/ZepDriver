package com.zepdriver.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zepdriver.MeshConnection
import com.zepdriver.ui.MainViewModel
import com.zepdriver.ui.common.SectionTitle

private enum class RadioAction { BLUETOOTH, FLASH, PROVISION }

private fun MeshConnection.label(): String = when (this) {
    MeshConnection.NO_CAR_NAME -> "Needs a car name"
    MeshConnection.DISCONNECTED -> "Not connected"
    MeshConnection.SCANNING -> "Looking for the radio…"
    MeshConnection.CONNECTING -> "Connecting…"
    MeshConnection.CONNECTED -> "Connected"
    MeshConnection.PROVISIONING -> "Setting up the radio…"
    MeshConnection.FLASHING -> "Installing firmware…"
    MeshConnection.ERROR -> "Error"
}

private fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyArray()
    }

private fun Context.hasPermissions(perms: Array<String>): Boolean =
    perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

/** Mesh radio: Bluetooth connection, USB flashing / provisioning, status and log. */
@Composable
internal fun RadioSection(vm: MainViewModel) {
    val context = LocalContext.current
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    var needsName by remember { mutableStateOf<RadioAction?>(null) }
    var confirmFlash by remember { mutableStateOf(false) }

    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) vm.connectBluetooth()
        else vm.toast("Bluetooth permission is needed to reach the radio")
    }

    fun perform(action: RadioAction) {
        when (action) {
            RadioAction.BLUETOOTH -> {
                val perms = bluetoothPermissions()
                if (perms.isEmpty() || context.hasPermissions(perms)) vm.connectBluetooth() else btLauncher.launch(perms)
            }
            RadioAction.FLASH -> vm.flashUsbRadio()
            RadioAction.PROVISION -> vm.provisionUsbRadio()
        }
    }

    /** Every connect action first makes sure the car has a name. */
    fun request(action: RadioAction) {
        if (vm.profile.value.carName.isBlank() || mesh.connection == MeshConnection.NO_CAR_NAME) {
            needsName = action
        } else {
            perform(action)
        }
    }

    val busy = mesh.connection in setOf(
        MeshConnection.SCANNING, MeshConnection.CONNECTING, MeshConnection.PROVISIONING, MeshConnection.FLASHING,
    )
    val linked = mesh.connection == MeshConnection.CONNECTED || busy

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("Fleet radio")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(mesh.connection.label(), style = MaterialTheme.typography.titleLarge)
                mesh.deviceName?.let { Text("Radio: $it", style = MaterialTheme.typography.bodyMedium) }
                Text(
                    "Car name: ${profile.carName.ifBlank { "not set" }}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (mesh.status.isNotBlank()) {
                    Text(mesh.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val progress = mesh.flashProgress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (mesh.usbDevicePresent) "USB radio plugged in." else "No USB radio plugged in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (linked) {
            OutlinedButton(onClick = vm::disconnectRadio, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
        }
        Button(
            onClick = { request(RadioAction.BLUETOOTH) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Connect over Bluetooth") }
        Button(
            onClick = { confirmFlash = true },
            enabled = mesh.usbDevicePresent && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Flash & set up USB radio") }
        OutlinedButton(
            onClick = { request(RadioAction.PROVISION) },
            enabled = mesh.usbDevicePresent && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Set up USB radio (already Meshtastic)") }

        Text(
            "Recommended board: Heltec WiFi LoRa 32 V3 (US 915 MHz). Plug it into the phone with a USB-OTG cable to " +
                "install the firmware and the shared ZepDriver channel; afterwards power it from the car and connect " +
                "over Bluetooth. Mesh messages and positions are public to anyone determined to listen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (mesh.log.isNotEmpty()) {
            SectionTitle("Log")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    for (line in mesh.log.takeLast(100).asReversed()) {
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
    }

    if (confirmFlash) {
        AlertDialog(
            onDismissRequest = { confirmFlash = false },
            title = { Text("Flash the USB radio?") },
            text = { Text("This erases the board and installs Meshtastic with the ZepDriver settings. Keep it plugged in until it finishes.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmFlash = false
                    request(RadioAction.FLASH)
                }) { Text("Flash") }
            },
            dismissButton = { TextButton(onClick = { confirmFlash = false }) { Text("Cancel") } },
        )
    }

    needsName?.let { action ->
        CarNameDialog(
            initial = profile.carName,
            onDismiss = { needsName = null },
            onSave = { name ->
                vm.updateProfile(vm.profile.value.copy(carName = name))
                needsName = null
                perform(action)
            },
        )
    }
}

@Composable
private fun CarNameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name your car") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Other ZepDriver cars see this name on their maps. It's set on the radio, so it's needed first.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Car name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save and continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
