package com.zepdriver.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zepdriver.core.search.SearchResult
import com.zepdriver.ui.MainViewModel
import com.zepdriver.ui.common.DestinationSheet
import com.zepdriver.ui.common.EmptyHint
import com.zepdriver.ui.common.Fmt

/** Offline search over camps, art, places and BRC addresses. */
@Composable
fun SearchScreen(vm: MainViewModel) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<SearchResult?>(null) }
    val focusManager = LocalFocusManager.current

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            singleLine = true,
            placeholder = { Text("Camp, art, place or \"4:30 & G\"") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        if (searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        when {
            query.isBlank() -> EmptyHint(
                "Search the city",
                "Camps and art by name or description, places like \"Center Camp\", or an address like \"4:30 & G\" or \"2:00 3000'\".",
            )
            results.isEmpty() && !searching -> EmptyHint("Nothing found", "Try another spelling or an address.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(results) { r ->
                    ResultRow(r) {
                        focusManager.clearFocus()
                        selected = r
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    selected?.let { r ->
        DestinationSheet(
            dest = r.destination,
            subtitle = r.subtitle,
            description = r.description,
            vm = vm,
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun ResultRow(r: SearchResult, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(r.destination.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(r.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        Fmt.snippet(r.description)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
