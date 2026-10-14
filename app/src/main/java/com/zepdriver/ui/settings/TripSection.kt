package com.zepdriver.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zepdriver.ui.MainViewModel
import com.zepdriver.ui.common.EmptyHint
import com.zepdriver.ui.common.Fmt
import com.zepdriver.ui.common.SectionTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun shareTrack(context: Context, uri: Uri, kml: Boolean) {
    val mime = if (kml) "application/vnd.google-earth.kml+xml" else "application/gpx+xml"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, if (kml) "Share KML" else "Share GPX"))
}

/** Our own trip log: pick days, export KML/GPX through the share sheet, or clear it. */
@Composable
internal fun TripSection(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val days by vm.tripDays.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var exporting by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshTripDays() }

    fun export(kml: Boolean) {
        val chosen = selected.sorted()
        if (chosen.isEmpty()) return
        scope.launch {
            exporting = true
            try {
                val uri = vm.exportTrips(chosen, kml)
                shareTrack(context, uri, kml)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.toast("Export failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                exporting = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionTitle("Trip log")
        TripPrivacy(vm)
        Text(
            "Your own track, speeds and warnings, stored on this phone: never sent over the mesh or by the app " +
                "over the internet. It only leaves the phone through an export, or your Google backup if you choose it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (days.isEmpty()) {
            EmptyHint("No trips logged yet", "Trips are recorded automatically while you drive.")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size} of ${days.size} days selected", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    selected = if (selected.size == days.size) emptySet() else days.map { it.date }.toSet()
                }) { Text(if (selected.size == days.size) "Select none" else "Select all") }
            }
            for (d in days) {
                val checked = d.date in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = checked,
                            onValueChange = { on -> selected = if (on) selected + d.date else selected - d.date },
                            role = Role.Checkbox,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(d.date, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${Fmt.distance(d.distanceFt)} · ${d.points} points",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (exporting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { export(kml = true) },
                enabled = selected.isNotEmpty() && !exporting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Export KML (Google Earth / My Maps)")
            }
            OutlinedButton(
                onClick = { export(kml = false) },
                enabled = selected.isNotEmpty() && !exporting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export GPX") }
            Text(
                "Open in Google Earth, or import into Google My Maps (mymaps.google.com → Import).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { confirmClear = true }) {
                Text("Clear trip log", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the trip log?") },
            text = { Text("All logged days are deleted from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    selected = emptySet()
                    vm.clearTrips()
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** Whether to record at all, and how long to keep what's recorded. */
@Composable
private fun TripPrivacy(vm: MainViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    SwitchRow(
        title = "Record my trips",
        subtitle = if (prefs.recordTrips) "Needed for your trail on the map and for exports."
        else "Off: nothing is recorded, and there's no trail on the map.",
        checked = prefs.recordTrips,
        onChange = { vm.updatePrefs(vm.prefs.value.copy(recordTrips = it)) },
    )
    if (prefs.recordTrips) {
        Text("Back up", style = MaterialTheme.typography.bodyMedium)
        RadioRow(
            title = "Only on this phone",
            subtitle = "Never copied anywhere. Lost if the phone is lost or reset.",
            selected = !prefs.tripCloudBackup,
            onClick = { vm.updatePrefs(vm.prefs.value.copy(tripCloudBackup = false)) },
        )
        RadioRow(
            title = "Back up to my Google account",
            subtitle = "Included in Android's own encrypted backup, restored on a new phone.",
            selected = prefs.tripCloudBackup,
            onClick = { vm.updatePrefs(vm.prefs.value.copy(tripCloudBackup = true)) },
        )
        Text("Keep trips for", style = MaterialTheme.typography.bodyMedium)
        for ((days, label) in KEEP_CHOICES) {
            RadioRow(
                title = label,
                selected = prefs.tripKeepDays == days,
                onClick = { vm.updatePrefs(vm.prefs.value.copy(tripKeepDays = days)) },
            )
        }
    }
}

private val KEEP_CHOICES = listOf(
    1 to "1 day",
    7 to "1 week",
    30 to "1 month",
    0 to "Until I delete them",
)
