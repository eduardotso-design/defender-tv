package com.scratch.defender

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.*
import kotlin.random.Random

object Sfx {
    private const val SR = 22050
    private val cache = HashMap<String, ShortArray>()

    fun init() {
        cache["fire"] = squareSweep(880f, 220f, 0.09f, 0.35f)
        cache["hit"] = squareSweep(600f, 100f, 0.08f, 0.3f)
        cache["boom"] = noiseBurst(0.35f, 0.6f)
        cache["bigBoom"] = noiseBurst(0.7f, 0.8f)
        cache["bomb"] = sweep(1200f, 60f, 0.6f, 0.7f)
        cache["hyper"] = sweep(200f, 1800f, 0.4f, 0.5f)
        cache["pickup"] = tone(880f, 1760f, 0.18f, 0.4f)
        cache["mutate"] = sweep(900f, 150f, 0.4f, 0.5f)
        cache["wave"] = fanfare()
        cache["warn"] = squareSweep(440f, 440f, 0.08f, 0.3f)
    }

    fun play(name: String) {
        val buf = cache[name] ?: return
        try {
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                buf.size * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            track.write(buf, 0, buf.size)
            track.setNotificationMarkerPosition(buf.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { try { t.release() } catch (_: Exception) {} }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        } catch (_: Throwable) {}
    }

    private fun squareSweep(f0: Float, f1: Float, dur: Float, vol: Float): ShortArray {
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        var phase = 0f
        for (i in 0 until n) {
            val t = i / n.toFloat()
            val f = f0 + (f1 - f0) * t
            phase += 2f * PI.toFloat() * f / SR
            val sq = if (sin(phase) >= 0f) 1f else -1f
            val env = (1f - t).coerceAtLeast(0f)
            out[i] = (sq * env * vol * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun sweep(f0: Float, f1: Float, dur: Float, vol: Float): ShortArray {
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        var phase = 0f
        for (i in 0 until n) {
            val t = i / n.toFloat()
            val f = f0 * (f1 / f0).pow(t)
            phase += 2f * PI.toFloat() * f / SR
            val env = (1f - t).coerceAtLeast(0f)
            out[i] = (sin(phase) * env * vol * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun noiseBurst(dur: Float, vol: Float): ShortArray {
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        var low = 0f
        for (i in 0 until n) {
            val t = i / n.toFloat()
            val noise = Random.nextFloat() * 2f - 1f
            low = low * 0.8f + noise * 0.2f // low-pass for rumble
            val env = (1f - t).pow(2f)
            out[i] = ((noise * 0.5f + low * 0.8f) * env * vol * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun tone(f0: Float, f1: Float, dur: Float, vol: Float): ShortArray {
        val n = (SR * dur).toInt()
        val out = ShortArray(n)
        var phase = 0f
        for (i in 0 until n) {
            val t = i / n.toFloat()
            val f = f0 + (f1 - f0) * t
            phase += 2f * PI.toFloat() * f / SR
            val env = sin(PI.toFloat() * t)
            out[i] = (sin(phase) * env * vol * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun fanfare(): ShortArray {
        val notes = floatArrayOf(523f, 659f, 784f, 1047f) // C E G C
        val perNote = 0.11f
        val n = (SR * perNote * notes.size).toInt()
        val out = ShortArray(n)
        var phase = 0f
        val per = (SR * perNote).toInt()
        for (i in 0 until n) {
            val note = (i / per).coerceAtMost(notes.size - 1)
            val localT = (i % per) / per.toFloat()
            val f = notes[note]
            phase += 2f * PI.toFloat() * f / SR
            val sq = if (sin(phase) >= 0f) 1f else -1f
            val env = sin(PI.toFloat() * localT)
            out[i] = (sq * env * 0.4f * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
