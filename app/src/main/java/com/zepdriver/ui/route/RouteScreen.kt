package com.zepdriver.ui.route

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zepdriver.DriveUiState
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.nav.DestinationKind
import com.zepdriver.ui.MainViewModel
import com.zepdriver.ui.Tab
import com.zepdriver.ui.common.EmptyHint
import com.zepdriver.ui.common.label

/** Multi-stop route planning (parked only). */
@Composable
fun RouteScreen(vm: MainViewModel, ui: DriveUiState) {
    val draft by vm.draftStops.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val hasFix = ui.drive?.fix != null
    val nav = ui.nav

    Column(Modifier.fillMaxSize()) {
        Text(
            "Route",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )
        if (ui.routerBusy) {
            Text(
                "Preparing routes for your car…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }

        if (nav != null) {
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (nav.finished) "Route finished" else "Navigating",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val remaining = ui.stops.joinToString(" → ") { it.name }
                        if (remaining.isNotEmpty()) {
                            Text(remaining, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    OutlinedButton(onClick = vm::stopNavigation) { Text(if (nav.finished) "Clear" else "Stop navigation") }
                }
            }
        }

        // Quick adds: home camp and favorites.
        val quick = buildList {
            val home = profile.homeCamp
            if (home != null && favorites.none { it.label == MainViewModel.LABEL_HOME }) {
                add(Destination(MainViewModel.LABEL_HOME, home, DestinationKind.FAVORITE, address = profile.homeCampLabel))
            }
            favorites.forEach { add(it.destination.copy(name = it.label)) }
        }
        if (quick.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(quick) { d ->
                    AssistChip(
                        onClick = { vm.addStop(d) },
                        label = { Text(d.name, maxLines = 1) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            if (draft.isEmpty()) {
                EmptyHint(
                    "No stops yet",
                    "Add stops from Search or Favorites, or tap a favorite above. Then press Start.",
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(draft) { i, stop ->
                        StopRow(
                            index = i,
                            stop = stop,
                            canUp = i > 0,
                            canDown = i < draft.size - 1,
                            onUp = { vm.moveStop(i, -1) },
                            onDown = { vm.moveStop(i, +1) },
                            onRemove = { vm.removeStop(i) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        if (!hasFix) {
            Text(
                "Waiting for GPS: routes start from where the car is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = vm::clearStops,
                enabled = draft.isNotEmpty(),
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text("Clear") }
            OutlinedButton(
                onClick = { vm.setTab(Tab.SEARCH) },
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text("Add") }
            Button(
                onClick = vm::startRoute,
                enabled = draft.isNotEmpty() && hasFix,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Start")
            }
        }
    }
}

@Composable
private fun StopRow(
    index: Int,
    stop: Destination,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("${index + 1}", fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(stop.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(stop.kind.label(), stop.address).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onUp, enabled = canUp) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
        IconButton(onClick = onDown, enabled = canDown) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
    }
}
