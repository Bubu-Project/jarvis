package com.example.jarvis

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

class GeminiAudioManager {

    companion object {
        private const val INPUT_RATE = 16000   // Mic input for Gemini
        private const val OUTPUT_RATE = 24000  // Gemini audio output
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isPlaying = false
    private var recordingThread: Thread? = null

    private var totalBytesPlayed = 0L

    interface AudioListener {
        fun onMicData(pcmBytes: ByteArray)
        fun onError(message: String)
    }

    private var listener: AudioListener? = null
    fun setListener(l: AudioListener) { listener = l }

    // =====================================================
    // MIC CAPTURE - 16kHz
    // =====================================================
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
                Log.e("JARVIS_GEMINI_AUDIO", "Mic init FAILED")
                listener?.onError("Mic init failed")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.d("JARVIS_GEMINI_AUDIO", "Mic started OK")

            recordingThread = Thread {
                val chunkSize = 3200  // 100ms at 16kHz
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

    // =====================================================
    // SPEAKER PLAYBACK - 24kHz
    // =====================================================
    fun startSpeaker() {
        if (isPlaying) return

        try {
            val bufferSize = AudioTrack.getMinBufferSize(OUTPUT_RATE, CHANNEL_OUT, ENCODING)
            val actualBuffer = maxOf(bufferSize, 32768)  // Bigger buffer for smooth playback

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

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e("JARVIS_GEMINI_AUDIO", "Speaker init FAILED")
                return
            }

            audioTrack?.play()
            audioTrack?.setVolume(1.0f)
            isPlaying = true
            totalBytesPlayed = 0L
            Log.d("JARVIS_GEMINI_AUDIO", "Speaker started OK, buffer: $actualBuffer")

        } catch (e: Exception) {
            Log.e("JARVIS_GEMINI_AUDIO", "Speaker error: ${e.message}")
        }
    }

    fun playAudio(pcmBytes: ByteArray) {
        if (!isPlaying || audioTrack == null) {
            Log.e("JARVIS_GEMINI_AUDIO", "Play skipped: playing=$isPlaying, track=${audioTrack != null}")
            return
        }

        try {
            val written = audioTrack?.write(pcmBytes, 0, pcmBytes.size) ?: 0
            totalBytesPlayed += written
            Log.d("JARVIS_GEMINI_AUDIO", "Played: $written bytes (total: $totalBytesPlayed)")
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
            totalBytesPlayed = 0L
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
        Log.d("JARVIS_GEMINI_AUDIO", "Mic stopped")
    }

    fun stopSpeaker() {
        isPlaying = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        Log.d("JARVIS_GEMINI_AUDIO", "Speaker stopped")
    }

    fun stopAll() {
        stopMic()
        stopSpeaker()
    }
}
