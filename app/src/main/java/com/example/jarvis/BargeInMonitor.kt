package com.example.jarvis

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

// Jarvis bol raha ho aur tum beech mein bolo to Jarvis ruk jaye.
// Echo hataane (AEC) ki koshish karta hai, par har phone par pakka nahi. Galat chalne par THRESHOLD badhao.
class BargeInMonitor {
    companion object {
        private const val THRESHOLD = 3000.0   // itni tez awaaz par hi rukega (zyada = kam sensitive)
        private const val NEEDED_HITS = 4      // lagatar itne tukde tez hon (~0.25 second)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null
    @Volatile private var running = false

    fun start(onInterrupt: () -> Unit) {
        if (running) return
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (e: Exception) { return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        } catch (_: Exception) {}
        try {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
        } catch (_: Exception) {}

        record = rec
        running = true
        try {
            rec.startRecording()
        } catch (e: Exception) {
            running = false
            return
        }

        Thread {
            val buf = ShortArray(1024)
            var hits = 0
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
                    val rms = sqrt(sum / n)
                    hits = if (rms > THRESHOLD) hits + 1 else 0
                    if (hits >= NEEDED_HITS) {
                        running = false
                        handler.post { onInterrupt() }
                        break
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    fun stop() {
        running = false
        try {
            record?.stop()
            record?.release()
        } catch (_: Exception) {}
        record = null
    }
}
