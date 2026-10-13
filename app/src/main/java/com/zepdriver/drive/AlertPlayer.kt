package com.zepdriver.drive

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.zepdriver.AppPrefs
import com.zepdriver.core.alerts.Alert
import com.zepdriver.core.alerts.AlertKind
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.core.rules.DriveState
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Turns alerts and navigation prompts into sound, voice and vibration.
 *
 * - CAUTION: silent.
 * - ADVISORY: soft chime once when the alert appears, spoken once.
 * - WARNING: three beeps + vibration when it appears, beeps again every ~8 s
 *   while it persists; spoken once. Speeding is loud: the siren every ~4 s.
 * - ALARM: siren every ~2 s while active plus vibration; speech every ~10 s.
 *
 * Voice priority: ALARM > WARNING > navigation > ADVISORY. Tones play even
 * when voice is turned off in [AppPrefs]. Everything goes to the media stream
 * with transient ducking focus so it plays through the car's speakers.
 *
 * Repeats are timed by the wall clock. Under the simulator's time warp alerts
 * come and go faster than they can be said, so then at most one tone per
 * [WARP_TONE_GAP_MS] plays and only the most important line is spoken, never
 * queued behind another.
 */
class AlertPlayer(context: Context, private val prefs: StateFlow<AppPrefs>) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val toneExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "zep-tones") }
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(mediaAttributes)
        .setOnAudioFocusChangeListener { }
        .build()
    private val focusUsers = AtomicInteger(0)
    private val abandonFocus = Runnable { if (focusUsers.get() == 0) audio?.abandonAudioFocusRequest(focusRequest) }

    /** Bundled clips for fixed warnings, the phone's text-to-speech for the rest. */
    private val voice = VoiceOut(app, mediaAttributes, onBusy = ::acquireFocus, onIdle = ::releaseFocus)

    // Per-alert bookkeeping (accessed only from the caller's thread).
    private class Seen(var level: AlertLevel, var lastSound: Long, var lastSpeech: Long)
    private val seen = HashMap<String, Seen>()
    private var lastAlarmSound = 0L
    private var lastChime = 0L
    private var lastTone = 0L
    private var lastNavSpeech: String? = null
    private var lastNavSpeechAt = 0L

    private class Utterance(val priority: Int, val text: String)

    /**
     * Call once per drive update with the new state and the navigation prompt,
     * if any. [warp] > 1 while the simulator runs time faster.
     */
    fun update(state: DriveState, navSpeech: String?, now: Long = System.currentTimeMillis(), warp: Int = 1) {
        val speech = ArrayList<Utterance>()
        val active = state.alerts.associateBy { it.key }
        seen.keys.retainAll(active.keys)

        var playSiren = false
        var playBeeps = false
        var playChime = false
        var vibration: LongArray? = null

        for (alert in state.alerts) {
            val prev = seen[alert.key]
            val isNew = prev == null || alert.level > prev.level
            val s = prev ?: Seen(alert.level, 0L, 0L).also { seen[alert.key] = it }
            s.level = alert.level
            when (alert.level) {
                AlertLevel.ALARM -> {
                    if (isNew || now - s.lastSpeech >= ALARM_SPEECH_MS) {
                        alert.speechOrTitle()?.let { speech += Utterance(4, it) }
                        s.lastSpeech = now
                    }
                    if (isNew || now - lastAlarmSound >= ALARM_REPEAT_MS) {
                        playSiren = true
                        vibration = ALARM_VIBRATION
                    }
                }
                AlertLevel.WARNING -> {
                    if (isNew) {
                        alert.speechOrTitle()?.let { speech += Utterance(3, it) }
                        s.lastSpeech = now
                    }
                    // Speeding is a loud warning: the siren, not the beeps.
                    val loud = alert.kind == AlertKind.SPEED
                    if (isNew || now - s.lastSound >= (if (loud) SPEED_WARNING_REPEAT_MS else WARNING_REPEAT_MS)) {
                        if (loud) playSiren = true else playBeeps = true
                        s.lastSound = now
                        if (vibration == null) vibration = if (loud) ALARM_VIBRATION else WARNING_VIBRATION
                    }
                }
                AlertLevel.ADVISORY -> if (isNew) {
                    alert.speech?.let { speech += Utterance(1, it) }
                    playChime = true
                }
                AlertLevel.CAUTION, AlertLevel.NONE -> Unit
            }
        }
        if (!navSpeech.isNullOrBlank() && (navSpeech != lastNavSpeech || now - lastNavSpeechAt > 5_000)) {
            speech += Utterance(2, navSpeech)
            lastNavSpeech = navSpeech
            lastNavSpeechAt = now
        }

        // Time warp: don't stack tones, vibrations and speech faster than they play.
        val warped = warp > 1
        if (warped && now - lastTone < WARP_TONE_GAP_MS) {
            playSiren = false
            playBeeps = false
            playChime = false
            vibration = null
        }

        // One sound per update: the most severe wins.
        when {
            playSiren -> { lastAlarmSound = now; lastTone = now; playTone(Tones.siren) }
            playBeeps -> { lastTone = now; playTone(Tones.beeps) }
            playChime && now - lastChime > 2_000 -> { lastChime = now; lastTone = now; playTone(Tones.chime) }
        }
        vibration?.let { vibrate(it) }
        speak(speech, warped)
    }

    private fun Alert.speechOrTitle(): String? = speech ?: title.takeIf { it.isNotBlank() }

    private fun speak(utterances: List<Utterance>, warped: Boolean = false) {
        if (utterances.isEmpty() || !prefs.value.voiceEnabled) return
        val ordered = utterances.sortedByDescending { it.priority }
        val urgent = ordered.first().priority >= 3
        if (warped) {
            // Only the most important line, and only alarms cut in.
            val u = ordered.first()
            if (u.priority < 4 && voice.busy) return
            voice.say(u.text, flush = true)
            return
        }
        for ((i, u) in ordered.take(3).withIndex()) {
            // Advisories are dropped rather than queued behind something more important.
            if (u.priority <= 1 && (urgent || voice.busy)) continue
            voice.say(u.text, flush = i == 0 && u.priority >= 4)
        }
    }

    private fun playTone(samples: ShortArray) {
        acquireFocus()
        toneExecutor.execute {
            try {
                Tones.play(samples)
            } finally {
                releaseFocus()
            }
        }
    }

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        try {
            if (v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            Log.w(TAG, "vibration failed", e)
        }
    }

    private fun acquireFocus() {
        mainHandler.removeCallbacks(abandonFocus)
        if (focusUsers.getAndIncrement() == 0) {
            audio?.requestAudioFocus(focusRequest)
        }
    }

    private fun releaseFocus() {
        if (focusUsers.decrementAndGet() <= 0) {
            focusUsers.set(0)
            mainHandler.postDelayed(abandonFocus, 700)
        }
    }

    fun release() {
        toneExecutor.shutdownNow()
        voice.release()
        mainHandler.removeCallbacks(abandonFocus)
        audio?.abandonAudioFocusRequest(focusRequest)
    }

    private companion object {
        const val TAG = "AlertPlayer"
        const val ALARM_REPEAT_MS = 2_000L
        const val ALARM_SPEECH_MS = 10_000L
        const val WARNING_REPEAT_MS = 8_000L
        const val SPEED_WARNING_REPEAT_MS = 4_000L
        /** Under time warp: at least this long between tones. */
        const val WARP_TONE_GAP_MS = 2_500L
        val WARNING_VIBRATION = longArrayOf(0, 180, 120, 180, 120, 180)
        val ALARM_VIBRATION = longArrayOf(0, 500, 150, 500, 150, 500)
    }
}
