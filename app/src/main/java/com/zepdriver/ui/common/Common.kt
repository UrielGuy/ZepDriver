package com.zepdriver.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zepdriver.core.nav.DestinationKind
import kotlinx.coroutines.delay

/** Wall-clock time that updates every [periodMillis] (for "3 min ago" labels). */
@Composable
fun rememberNow(periodMillis: Long = 15_000L): State<Long> =
    produceState(System.currentTimeMillis(), periodMillis) {
        while (true) {
            delay(periodMillis)
            value = System.currentTimeMillis()
        }
    }

/**
 * For a text field: focuses it and opens the keyboard when it first appears,
 * so the user can start typing right away.
 */
@Composable
fun Modifier.focusOnShow(): Modifier {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        withFrameNanos { } // after the first layout (and, in a dialog, once its window is up)
        requester.requestFocus()
        keyboard?.show()
    }
    return focusRequester(requester)
}

/** Toggles twice a second while [active] (alarm flashing); false otherwise. */
@Composable
fun rememberFlash(active: Boolean, periodMillis: Long = 400L): Boolean {
    val state = produceState(false, active) {
        if (!active) {
            value = false
            return@produceState
        }
        while (true) {
            delay(periodMillis)
            value = !value
        }
    }
    return active && state.value
}

/** Section title inside a scrolling screen. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

/** Centered hint text for empty lists. */
@Composable
fun EmptyHint(title: String, body: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

fun DestinationKind.label(): String = when (this) {
    DestinationKind.CAMP -> "Camp"
    DestinationKind.ART -> "Art"
    DestinationKind.TOILET -> "Potties"
    DestinationKind.POI -> "Place"
    DestinationKind.FAVORITE -> "Favorite"
    DestinationKind.ADDRESS -> "Address"
    DestinationKind.PIN -> "Pin"
}
