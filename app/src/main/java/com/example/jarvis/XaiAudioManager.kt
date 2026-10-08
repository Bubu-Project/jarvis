package com.example.jarvis

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log

class XaiAudioManager {

    companion object {
        private const val SAMPLE_RATE = 24000  // xAI uses 24kHz
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isPlaying = false
    private var recordingThread: Thread? = null

    private val handler = Handler(Looper.getMainLooper())

    interface AudioListener {
        fun onMicData(pcmBytes: ByteArray)
        fun onError(message: String)
    }

    private var listener: AudioListener? = null
    fun setListener(l: AudioListener) { listener = l }

    // =====================================================
    // MIC CAPTURE START
    // =====================================================
    @SuppressLint("MissingPermission")
    fun startMicCapture() {
        if (isRecording) return

        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            val actualBuffer = maxOf(bufferSize, 4096)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
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
            Log.d("JARVIS_XAI_AUDIO", "Mic capture started")

            recordingThread = Thread {
                // 20ms chunks at 24kHz = 480 samples × 2 bytes = 960 bytes
                val chunkSize = 960
                val buffer = ByteArray(chunkSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val chunk = buffer.copyOf(read)
                        listener?.onMicData(chunk)
                    }
                }
            }
            recordingThread?.start()

        } catch (e: Exception) {
            Log.e("JARVIS_XAI_AUDIO", "Mic start error: ${e.message}")
            listener?.onError(e.message ?: "Mic error")
        }
    }

    // =====================================================
    // SPEAKER PLAYBACK
    // =====================================================
    fun startSpeaker() {
        if (isPlaying) return

        try {
            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
            val actualBuffer = maxOf(bufferSize, 8192)

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
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(actualBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying = true
            Log.d("JARVIS_XAI_AUDIO", "Speaker started")

        } catch (e: Exception) {
            Log.e("JARVIS_XAI_AUDIO", "Speaker start error: ${e.message}")
            listener?.onError(e.message ?: "Speaker error")
        }
    }

    // xAI se aaya audio play karo
    fun playAudio(pcmBytes: ByteArray) {
        try {
            audioTrack?.write(pcmBytes, 0, pcmBytes.size)
        } catch (e: Exception) {
            Log.e("JARVIS_XAI_AUDIO", "Play error: ${e.message}")
        }
    }

    // Barge-in ke liye - abhi baj raha audio clear karo
    fun stopPlayback() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
            Log.d("JARVIS_XAI_AUDIO", "Playback flushed (barge-in)")
        } catch (e: Exception) {
            Log.e("JARVIS_XAI_AUDIO", "Stop playback error: ${e.message}")
        }
    }

    // =====================================================
    // STOP EVERYTHING
    // =====================================================
    fun stopMic() {
        isRecording = false
        try { recordingThread?.join(500) } catch (_: Exception) {}
        recordingThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        Log.d("JARVIS_XAI_AUDIO", "Mic stopped")
    }

    fun stopSpeaker() {
        isPlaying = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        Log.d("JARVIS_XAI_AUDIO", "Speaker stopped")
    }

    fun stopAll() {
        stopMic()
        stopSpeaker()
    }

    fun isMicActive(): Boolean = isRecording
    fun isSpeakerActive(): Boolean = isPlaying
}
