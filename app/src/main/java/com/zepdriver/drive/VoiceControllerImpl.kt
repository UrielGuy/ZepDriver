package com.zepdriver.drive

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import com.zepdriver.VoiceController
import com.zepdriver.VoiceState
import com.zepdriver.core.alerts.Speech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Checks for an offline voice and plays the voice test, independent of the driving service. */
class VoiceControllerImpl(context: Context, private val scope: CoroutineScope) : VoiceController {
    private val app = context.applicationContext
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var voice = VoiceOut(app, attributes)
    private val _state = MutableStateFlow(VoiceState(warningClips = voice.clips.size))
    override val state: StateFlow<VoiceState> = _state.asStateFlow()
    private var checkJob: Job? = null

    init {
        check()
    }

    override fun refresh() {
        // A fresh engine connection sees voices installed since the last one.
        voice.release()
        voice = VoiceOut(app, attributes)
        _state.value = VoiceState(offlineVoice = null, warningClips = voice.clips.size)
        check()
    }

    override fun test() {
        voice.say(Speech.SLOW_DOWN_ALARM)
        voice.say("In 300 feet, turn right onto 4:30. Then the Esplanade.")
    }

    override fun openInstall() {
        val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(install)
        } catch (e: ActivityNotFoundException) {
            try {
                app.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: ActivityNotFoundException) {
                Log.w(TAG, "no text-to-speech settings screen", e2)
            }
        }
    }

    private fun check() {
        checkJob?.cancel()
        checkJob = scope.launch {
            // The engine binds asynchronously; give it up to ~15 s.
            repeat(30) {
                val has = voice.hasOfflineEnglishVoice()
                if (has != null) {
                    _state.value = _state.value.copy(offlineVoice = has)
                    return@launch
                }
                delay(500)
            }
            _state.value = _state.value.copy(offlineVoice = false)
        }
    }

    private companion object {
        const val TAG = "Voice"
    }
}
