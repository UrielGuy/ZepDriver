package com.zepdriver.ui.common

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.zepdriver.core.embargo.DataAccess
import com.zepdriver.core.embargo.EmbargoState
import com.zepdriver.core.embargo.Naughty
import com.zepdriver.core.embargo.TimeSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Required by the Burning Man API terms, in a prominent place in the app. */
const val BM_DISCLAIMER = "This app is not affiliated, endorsed, or verified by Burning Man Project."

private val BRC = ZoneId.of("America/Los_Angeles")
private val dayFormat = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
private val timeFormat = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US)

private fun day(millis: Long) = dayFormat.format(Instant.ofEpochMilli(millis).atZone(BRC))
private fun moment(millis: Long) = timeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun TimeSource?.who() = when (this) {
    TimeSource.SATELLITE -> "the GPS satellites say"
    TimeSource.NETWORK -> "the network says"
    TimeSource.SERVER -> "the internet says"
    null -> "real time says"
}

/** Caught cheating: explains why camps and art are hidden. */
@Composable
fun NaughtyDialog(naughty: Naughty, source: TimeSource?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val body = when (naughty) {
        is Naughty.ClockAhead ->
            "Your phone's clock says ${moment(naughty.phoneMillis)}, but ${source.who()} it's really " +
                "${moment(naughty.realMillis)}. Nice try! Burning Man releases camp and art locations on " +
                "set dates, so they stay hidden, this year's and last year's, until your clock is right. " +
                "Turn on automatic date & time."
        Naughty.ModifiedApp ->
            "This copy of ZepDriver has been changed or re-signed by someone else. Camp and art " +
                "locations stay hidden. Install ZepDriver from its official source."
        Naughty.ModifiedData ->
            "ZepDriver's camp and art data has been tampered with. Camp and art locations stay " +
                "hidden. Reinstall the app to fix it."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Naughty! 😈") },
        text = { Text(body) },
        confirmButton = {
            if (naughty is Naughty.ClockAhead) {
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        // No date settings screen; the text says what to do.
                    }
                    onDismiss()
                }) { Text("Fix my clock") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = if (naughty is Naughty.ClockAhead) {
            { TextButton(onClick = onDismiss) { Text("Fine, I'll behave") } }
        } else {
            null
        },
    )
}

/** One or two lines for Settings: what's shown and when the rest unlocks. */
@Composable
fun EmbargoStatusText(state: EmbargoState, source: TimeSource?) {
    val s = state.schedule
    val text = when {
        state.naughty != null -> "Camp and art locations are hidden: this phone failed ZepDriver's tamper checks."
        s == null -> null
        state.access == DataAccess.PREVIOUS_YEAR ->
            "Showing last year's city until Burning Man releases ${s.year}'s locations: camps on " +
                "${day(s.campsAtMillis)}, art when the gates open on ${day(s.artAtMillis)}."
        state.access == DataAccess.CAMPS ->
            "${s.year} camps are in. Art locations come when the gates open on ${day(s.artAtMillis)}."
        else -> null
    }
    if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium)
    if (state.awaitingConfirmation) {
        Text(
            "Your phone's clock says more is out, but ZepDriver confirms the date with GPS or network " +
                "time first. Drive outside or get online for a moment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (s != null && source != null && state.naughty == null) {
        Text(
            "Date confirmed by ${when (source) {
                TimeSource.SATELLITE -> "GPS"
                TimeSource.NETWORK -> "network time"
                TimeSource.SERVER -> "the data server"
            }}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
