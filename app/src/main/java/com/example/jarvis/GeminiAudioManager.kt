package com.example.jarvis

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

class GeminiAudioManager {

    companion object {
        private const val INPUT_RATE = 16000   // Mic for Gemini
        private const val OUTPUT_RATE = 24000  // Gemini output
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isPlaying = false
    private var recordingThread: Thread? = null

    interface AudioListener {
        fun onMicData(pcmBytes: ByteArray)
        fun onError(message: String)
    }

    private var listener: AudioListener? = null
    fun setListener(l: AudioListener) { listener = l }

    @SuppressLint("MissingPermission")
    fun startMic() {
        if (isRecording) return

        try {
            val bufferSize = AudioRecord.getMinBufferSize(INPUT_RATE, CHANNEL_IN, ENCODING)
            val actualBuffer = maxOf(bufferSize, 8192)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_RATE,
                CHANNEL_IN,
                ENCODING,
                actualBuffer
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                listener?.onError("Mic init failed")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.d("JARVIS_GEMINI_AUDIO", "Mic started")

            recordingThread = Thread {
                // 100ms chunks at 16kHz = 1600 samples × 2 bytes = 3200 bytes
                val chunkSize = 3200
                val buffer = ByteArray(chunkSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        listener?.onMicData(buffer.copyOf(read))
                    }
                }
            }
            recordingThread?.start()

        } catch (e: Exception) {
            Log.e("JARVIS_GEMINI_AUDIO", "Mic error: ${e.message}")
            listener?.onError(e.message ?: "Mic error")
        }
    }

    fun startSpeaker() {
        if (isPlaying) return

        try {
            val bufferSize = AudioTrack.getMinBufferSize(OUTPUT_RATE, CHANNEL_OUT, ENCODING)
            val actualBuffer = maxOf(bufferSize, 16384)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(OUTPUT_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(actualBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying = true
            Log.d("JARVIS_GEMINI_AUDIO", "Speaker started")

        } catch (e: Exception) {
            Log.e("JARVIS_GEMINI_AUDIO", "Speaker error: ${e.message}")
        }
    }

    fun playAudio(pcmBytes: ByteArray) {
        try {
            audioTrack?.write(pcmBytes, 0, pcmBytes.size)
        } catch (e: Exception) {
            Log.e("JARVIS_GEMINI_AUDIO", "Play error: ${e.message}")
        }
    }

    // Barge-in - Gemini ne interrupt kiya
    fun flushPlayback() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
            Log.d("JARVIS_GEMINI_AUDIO", "Flushed (barge-in)")
        } catch (e: Exception) {
            Log.e("JARVIS_GEMINI_AUDIO", "Flush error: ${e.message}")
        }
    }

    fun stopMic() {
        isRecording = false
        try { recordingThread?.join(500) } catch (_: Exception) {}
        recordingThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    fun stopSpeaker() {
        isPlaying = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    fun stopAll() {
        stopMic()
        stopSpeaker()
    }
}
