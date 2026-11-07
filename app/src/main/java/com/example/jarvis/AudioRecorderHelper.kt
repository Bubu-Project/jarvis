package com.example.jarvis

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AudioRecorderHelper(private val context: android.content.Context) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val handler = Handler(Looper.getMainLooper())

    // 🔥 Voice Activity Detection (VAD) Variables
    private val SILENCE_THRESHOLD = 500 // Awaaz ki minimum level
    private val SILENCE_DURATION_MS = 1500L // 1.5 second chup rahe to record rok do

    fun startRecording(outputFile: File, onSilenceDetected: () -> Unit) {
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        audioRecord?.startRecording()
        isRecording = true

        Thread {
            val buffer = ByteArray(bufferSize)
            var silenceStartTime = 0L
            var hasSpoken = false
            val outputStream = FileOutputStream(outputFile)
            
            // WAV Header (Whisper ko WAV chahiye hota hai)
            outputStream.write(ByteArray(44)) 

            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    outputStream.write(buffer, 0, read)

                    // 🔥 Calculate Volume (RMS)
                    var sum = 0.0
                    for (i in 0 until read step 2) {
                        val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                        sum += sample * sample
                    }
                    val rms = Math.sqrt(sum / (read / 2))

                    if (rms > SILENCE_THRESHOLD) {
                        hasSpoken = true
                        silenceStartTime = 0 // Awaaz aayi, timer reset
                    } else if (hasSpoken) {
                        if (silenceStartTime == 0L) silenceStartTime = System.currentTimeMillis()
                        else if (System.currentTimeMillis() - silenceStartTime > SILENCE_DURATION_MS) {
                            // 🔥 1.5 second se chup hai, record rok do
                            break 
                        }
                    }
                }
            }
            outputStream.close()
            stopRecording()
            handler.post { onSilenceDetected() }
        }.start()
    }

    fun stopRecording() {
        isRecording = false
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }
}
