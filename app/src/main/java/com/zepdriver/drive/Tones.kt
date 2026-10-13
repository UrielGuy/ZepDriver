package com.zepdriver.drive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Alert sounds synthesized in code (no audio assets) and played on the media
 * stream so they come out of the car's sound system over Bluetooth / aux.
 * [play] blocks until the sound finishes; call it from a worker thread.
 */
internal object Tones {
    private const val RATE = 22_050

    /** Soft two-note chime (advisory). */
    val chime: ShortArray by lazy {
        concat(
            tone(880.0, 140, 0.30, decay = 6.0),
            tone(1318.5, 320, 0.30, decay = 5.0),
        )
    }

    /** Three short beeps (warning). */
    val beeps: ShortArray by lazy {
        val beep = tone(1000.0, 120, 0.70)
        val gap = silence(90)
        concat(beep, gap, beep, gap, beep)
    }

    /** Loud two-tone siren burst (alarm), ~1.3 s. */
    val siren: ShortArray by lazy {
        val hi = tone(960.0, 220, 0.95)
        val lo = tone(640.0, 220, 0.95)
        concat(hi, lo, hi, lo, hi, lo)
    }

    fun play(samples: ShortArray) {
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep(samples.size * 1000L / RATE + 60)
            track.stop()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w("Tones", "playback failed", e)
        } finally {
            track?.release()
        }
    }

    private fun tone(freq: Double, ms: Int, amp: Double, decay: Double = 0.0): ShortArray {
        val n = RATE * ms / 1000
        val ramp = RATE * 6 / 1000 // 6 ms fade in/out against clicks
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val edge = min(1.0, min(i, n - 1 - i).toDouble() / ramp)
            val env = edge * (if (decay > 0) exp(-decay * t) else 1.0)
            (sin(2 * PI * freq * t) * amp * env * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun silence(ms: Int) = ShortArray(RATE * ms / 1000)

    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }
}
