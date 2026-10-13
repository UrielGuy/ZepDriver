package com.zepdriver.drive

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Speaks sentences one at a time, in order. A sentence with a pre-recorded
 * clip in assets/speech (every fixed warning; see core Speech and
 * tools/speech) plays the clip, so warnings talk even on a phone without an
 * offline text-to-speech voice. Anything else (navigation prompts with camp
 * and street names) goes to the phone's text-to-speech.
 *
 * [onBusy] / [onIdle] bracket each sentence, for audio focus.
 */
class VoiceOut(
    context: Context,
    private val attributes: AudioAttributes,
    private val onBusy: () -> Unit = {},
    private val onIdle: () -> Unit = {},
) {
    private val app = context.applicationContext

    /** Phrase text -> clip file name, from assets/speech/index.json (empty if the build has no clips). */
    val clips: Map<String, String> = try {
        val text = app.assets.open("speech/index.json").bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
    } catch (e: Exception) {
        emptyMap()
    }

    @Volatile var ttsReady = false
        private set
    @Volatile var ttsFailed = false
        private set
    private val done = ConcurrentHashMap<String, CountDownLatch>()
    val tts: TextToSpeech = TextToSpeech(app) { status ->
        if (status == TextToSpeech.SUCCESS) setUpTts() else ttsFailed = true
    }

    private val lock = Object()
    private val pending = ArrayDeque<String>()
    private var stopCurrent: (() -> Unit)? = null
    @Volatile private var running = true
    private var seq = 0

    private val worker = Thread({ loop() }, "zep-voice").apply {
        isDaemon = true
        start()
    }

    /** Something is being said or waiting to be said. */
    val busy: Boolean get() = synchronized(lock) { stopCurrent != null || pending.isNotEmpty() }

    /** Queues [text]; with [flush], cuts off whatever is playing and drops the queue first. */
    fun say(text: String, flush: Boolean = false) {
        synchronized(lock) {
            if (flush) {
                pending.clear()
                stopCurrent?.invoke()
            }
            pending.addLast(text)
            lock.notifyAll()
        }
    }

    fun release() {
        running = false
        synchronized(lock) {
            pending.clear()
            stopCurrent?.invoke()
            lock.notifyAll()
        }
        worker.interrupt()
        try {
            tts.stop()
            tts.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "tts shutdown failed", e)
        }
    }

    private fun setUpTts() {
        tts.setLanguage(Locale.US)
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
        ttsReady = true
    }

    private fun finish(utteranceId: String?) {
        utteranceId?.let { done.remove(it)?.countDown() }
    }

    private fun loop() {
        while (running) {
            val text = synchronized(lock) {
                while (running && pending.isEmpty()) {
                    try {
                        lock.wait()
                    } catch (e: InterruptedException) {
                        return
                    }
                }
                if (!running) return
                pending.removeFirst()
            }
            onBusy()
            try {
                val clip = clips[text]
                if (clip != null) playClip(clip) else speakTts(text)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "speaking failed", e)
            } finally {
                synchronized(lock) { stopCurrent = null }
                onIdle()
            }
        }
    }

    private fun playClip(file: String) {
        val latch = CountDownLatch(1)
        val player = MediaPlayer()
        try {
            app.assets.openFd("speech/$file").use { fd ->
                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            player.setAudioAttributes(attributes)
            player.setOnCompletionListener { latch.countDown() }
            player.setOnErrorListener { _, _, _ -> latch.countDown(); true }
            player.prepare()
            synchronized(lock) {
                stopCurrent = {
                    try {
                        player.stop()
                    } catch (e: IllegalStateException) {
                        // already stopped
                    }
                    latch.countDown()
                }
            }
            player.start()
            latch.await(MAX_SENTENCE_SECONDS, TimeUnit.SECONDS)
        } finally {
            player.release()
        }
    }

    private fun speakTts(text: String) {
        if (!ttsReady) return
        val id = "zep-${seq++}"
        val latch = CountDownLatch(1)
        done[id] = latch
        synchronized(lock) { stopCurrent = { tts.stop(); latch.countDown() } }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            done.remove(id)
            return
        }
        latch.await(MAX_SENTENCE_SECONDS, TimeUnit.SECONDS)
        done.remove(id)
    }

    /**
     * Whether the phone can speak English without the internet: an installed,
     * offline en voice exists. Null until the engine has started.
     */
    fun hasOfflineEnglishVoice(): Boolean? {
        if (ttsFailed) return false
        if (!ttsReady) return null
        return try {
            val voices = tts.voices.orEmpty()
            voices.any { v ->
                v.locale.language == "en" && !v.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice query failed", e)
            false
        }
    }

    private companion object {
        const val TAG = "VoiceOut"
        const val MAX_SENTENCE_SECONDS = 15L
    }
}
