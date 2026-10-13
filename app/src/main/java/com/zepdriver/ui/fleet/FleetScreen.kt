package com.zepdriver.ui.fleet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zepdriver.MeshConnection
import com.zepdriver.PlayaBundle
import com.zepdriver.core.mesh.FleetVehicle
import com.zepdriver.core.mesh.MeshText
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.nav.DestinationKind
import com.zepdriver.ui.MainViewModel
import com.zepdriver.ui.SettingsSection
import com.zepdriver.ui.Tab as ScreenTab
import com.zepdriver.ui.common.EmptyHint
import com.zepdriver.ui.common.Fmt
import com.zepdriver.ui.common.rememberNow
import com.zepdriver.ui.map.GeoJson
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Other ZepDriver cars on the mesh, and text messages between them. */
@Composable
fun FleetScreen(vm: MainViewModel, bundle: PlayaBundle) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val mesh by vm.mesh.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = page) {
            Tab(selected = page == 0, onClick = { page = 0 }, text = { Text("Cars") })
            Tab(selected = page == 1, onClick = { page = 1 }, text = { Text("Messages (${messages.size})") })
        }
        if (mesh.connection != MeshConnection.CONNECTED) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable {
                        vm.setSettingsSection(SettingsSection.RADIO)
                        vm.setTab(ScreenTab.SETTINGS)
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    "Radio not connected. Tap to set it up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Box(Modifier.weight(1f)) {
            if (page == 0) VehicleList(vm, bundle) else MessagesPane(vm, messages)
        }
    }
}

@Composable
private fun VehicleList(vm: MainViewModel, bundle: PlayaBundle) {
    val fleet by vm.fleet.collectAsStateWithLifecycle()
    val now by rememberNow(15_000L)
    if (fleet.isEmpty()) {
        EmptyHint(
            "No other cars heard yet",
            "Every car running ZepDriver with a radio shows up here and on the map.",
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(fleet, key = { it.nodeNum }) { v ->
            VehicleRow(
                v = v,
                address = remember(v.position) {
                    v.position?.let { p -> runCatching { bundle.addressing.describe(p) }.getOrNull() }
                },
                now = now,
                onShow = { v.position?.let(vm::showOnMap) },
                onGo = {
                    v.position?.let { p ->
                        vm.goNow(Destination(name = v.name.ifBlank { v.shortName }, position = p, kind = DestinationKind.PIN))
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun VehicleRow(v: FleetVehicle, address: String?, now: Long, onShow: () -> Unit, onGo: () -> Unit) {
    val t = v.positionTimeMillis ?: v.lastHeardMillis
    val stale = now - t > GeoJson.FLEET_STALE_MILLIS
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = v.position != null, onClick = onShow)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                v.name.ifBlank { v.shortName.ifBlank { "Unnamed car" } },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                address ?: if (v.position == null) "No position yet" else "Locating…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            val speed = v.speedMph?.let { "${Fmt.speed(it)} mph" }
            Text(
                listOfNotNull(Fmt.ago(now, t), speed).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (v.position != null) {
            IconButton(onClick = onShow) { Icon(Icons.Filled.LocationOn, contentDescription = "Show on map") }
            IconButton(onClick = onGo) { Icon(Icons.Filled.PlayArrow, contentDescription = "Navigate to") }
        }
    }
}

@Composable
private fun MessagesPane(vm: MainViewModel, messages: List<MeshText>) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val sorted = remember(messages) { messages.sortedBy { it.timeMillis } }
    LaunchedEffect(sorted.size) {
        if (sorted.isNotEmpty()) listState.scrollToItem(sorted.size - 1)
    }
    val send = {
        if (draft.isNotBlank()) {
            vm.sendText(draft)
            draft = ""
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (sorted.isEmpty()) {
                EmptyHint("No messages", "Short texts go to every ZepDriver car in radio range. Treat them as public.")
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(sorted) { m -> MessageBubble(m) }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= MAX_TEXT) draft = it },
                placeholder = { Text("Message all cars") },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { send() }, enabled = draft.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/** Meshtastic text payloads are limited to roughly 200 bytes. */
private const val MAX_TEXT = 200

@Composable
private fun MessageBubble(m: MeshText) {
    val time = remember(m.timeMillis) {
        DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(m.timeMillis))
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                if (m.mine) "You · $time" else "${m.fromName} · $time",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                m.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (m.mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
