package com.example.gempop

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Sound effects synthesized at startup (no audio files needed). */
class SoundFx {
    var enabled = true

    private val rate = 22050
    private val tracks = HashMap<String, AudioTrack>()

    init {
        add("swap", tone(500.0, 760.0, 70, 0.45))
        add("bad", tone(220.0, 130.0, 170, 0.5))
        for (i in 0..5) add("match$i", tone(523.0 * 1.12.pow(i), 640.0 * 1.12.pow(i), 150, 0.5))
        add("perfect", concat(tone(523.0, 523.0, 90, 0.5), tone(659.0, 659.0, 90, 0.5), tone(784.0, 784.0, 90, 0.5), tone(1047.0, 1047.0, 260, 0.55)))
        add("boom", boom(420))
        add("rainbow", concat(tone(300.0, 1500.0, 380, 0.5), tone(1500.0, 1500.0, 120, 0.4)))
        add("spawn", concat(tone(800.0, 1200.0, 90, 0.45), tone(1200.0, 1600.0, 120, 0.45)))
        add("shuffle", tone(400.0, 900.0, 220, 0.4))
        add("over", concat(tone(440.0, 440.0, 160, 0.5), tone(349.0, 349.0, 160, 0.5), tone(262.0, 262.0, 380, 0.5)))
    }

    fun play(name: String) {
        if (!enabled) return
        val t = tracks[name] ?: return
        try {
            t.stop()
            t.setPlaybackHeadPosition(0)
            t.play()
        } catch (_: Exception) {
        }
    }

    fun release() {
        tracks.values.forEach { try { it.release() } catch (_: Exception) {} }
        tracks.clear()
    }

    private fun add(name: String, pcm: ShortArray) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            tracks[name] = track
        } catch (_: Exception) {
        }
    }

    private fun tone(f0: Double, f1: Double, ms: Int, vol: Double): ShortArray {
        val n = rate * ms / 1000
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / n.toDouble()
            phase += 2 * PI * (f0 + (f1 - f0) * t) / rate
            val attack = minOf(1.0, i / (rate * 0.004))
            val env = attack * (1 - t).pow(1.4)
            val s = sin(phase) + 0.3 * sin(2 * phase)
            out[i] = (s * env * vol * 0.75 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun boom(ms: Int): ShortArray {
        val n = rate * ms / 1000
        val out = ShortArray(n)
        var low = 0.0
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / n.toDouble()
            low += 0.18 * ((Random.nextDouble() * 2 - 1) - low)
            phase += 2 * PI * (110.0 - 70.0 * t) / rate
            val env = (1 - t).pow(2.0)
            val s = low * 1.6 + sin(phase) * 0.6
            out[i] = (s * env * 0.8 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }
}
