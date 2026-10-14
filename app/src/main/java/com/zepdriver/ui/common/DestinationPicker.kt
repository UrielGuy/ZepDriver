package com.zepdriver.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zepdriver.core.nav.Destination
import com.zepdriver.core.search.SearchResult
import com.zepdriver.ui.MainViewModel
import kotlinx.coroutines.delay

/** Search-and-pick dialog, e.g. for choosing the home camp. */
@Composable
fun DestinationPickerDialog(
    vm: MainViewModel,
    title: String,
    onDismiss: () -> Unit,
    onPick: (Destination) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results by produceState(emptyList<SearchResult>(), query) {
        delay(250)
        value = vm.searchOnce(query, limit = 20)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Camp name or \"7:30 & E\"") },
                    modifier = Modifier.fillMaxWidth().focusOnShow(),
                )
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (r in results) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(r.destination) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(r.destination.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
