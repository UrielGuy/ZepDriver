package com.zepdriver.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zepdriver.DriveUiState
import com.zepdriver.Favorite
import com.zepdriver.ui.MainViewModel
import com.zepdriver.ui.common.EmptyHint
import com.zepdriver.ui.common.FavoriteLabelDialog

/** Saved places: Home, My art piece and custom ones. */
@Composable
fun FavoritesScreen(vm: MainViewModel, ui: DriveUiState) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    var savingHere by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Favorite?>(null) }
    val hasFix = ui.drive?.fix != null

    Column(Modifier.fillMaxSize()) {
        Text(
            "Favorites",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        Box(Modifier.weight(1f)) {
            if (favorites.isEmpty()) {
                EmptyHint(
                    "No favorites yet",
                    "Save your camp as Home and your art piece as My art piece, from Search or from where you're parked right now.",
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(favorites, key = { it.id }) { f ->
                        FavoriteRow(
                            favorite = f,
                            onGo = { vm.goNow(f.destination.copy(name = f.label)) },
                            onAdd = { vm.addStop(f.destination.copy(name = f.label)) },
                            onShow = { vm.showOnMap(f.destination.position) },
                            onDelete = { deleting = f },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
        Button(
            onClick = { savingHere = true },
            enabled = hasFix,
            modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 56.dp),
        ) {
            Icon(Icons.Filled.LocationOn, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (hasFix) "Save current location as…" else "Save current location (waiting for GPS)")
        }
    }

    if (savingHere) {
        FavoriteLabelDialog(
            initialCustom = "",
            title = "Save this spot as",
            onDismiss = { savingHere = false },
            onSave = { label ->
                val dest = vm.currentLocationDestination(label)
                if (dest != null) vm.saveFavorite(label, dest) else vm.toast("No GPS fix yet")
                savingHere = false
            },
        )
    }

    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${f.label}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeFavorite(f.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FavoriteRow(
    favorite: Favorite,
    onGo: () -> Unit,
    onAdd: () -> Unit,
    onShow: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onShow)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (favorite.label == MainViewModel.LABEL_HOME) Icons.Filled.Home else Icons.Filled.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(favorite.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val d = favorite.destination
            val sub = listOfNotNull(d.name.takeIf { it != favorite.label }, d.address).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        IconButton(onClick = onGo) { Icon(Icons.Filled.PlayArrow, contentDescription = "Go") }
        IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add stop") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
    }
}
