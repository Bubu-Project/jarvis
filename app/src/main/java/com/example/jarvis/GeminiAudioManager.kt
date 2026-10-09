package com.example.jarvis

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

class GeminiAudioManager {

    companion object {
        private const val INPUT_RATE = 16000
        private const val OUTPUT_RATE = 24000
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

    // =====================================================
    // MIC CAPTURE - 16kHz
    // =====================================================
    @SuppressLint("MissingPermission")
    fun startMic() {
        if (isRecording) return

        try {
            val bufferSize = AudioRecord.getMinBufferSize(INPUT_RATE, CHANNEL_IN, ENCODING)
            val actualBuffer = maxOf(bufferSize, 16384)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                INPUT_RATE,
                CHANNEL_IN,
                ENCODING,
                actualBuffer
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                DebugLogger.log("AUDIO", "Mic init FAILED")
                listener?.onError("Mic init failed")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            DebugLogger.log("AUDIO", "Mic started, state=${audioRecord?.recordingState}")

            recordingThread = Thread {
                val chunkSize = 3200
                val buffer = ByteArray(chunkSize)
                var readCount = 0
                while (isRecording) {
                    try {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            readCount++
                            if (readCount % 10 == 0) {
                                DebugLogger.log("AUDIO", "Mic read: $read b (total: $readCount)")
                            }
                            listener?.onMicData(buffer.copyOf(read))
                        } else if (read < 0) {
                            DebugLogger.log("AUDIO", "Mic read ERROR: $read")
                        }
                    } catch (e: Exception) {
                        DebugLogger.log("AUDIO", "Read exception: ${e.message}")
                    }
                }
            }
            recordingThread?.start()

        } catch (e: Exception) {
            DebugLogger.log("AUDIO", "Mic error: ${e.message}")
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
                        .setSampleRate(OUTPUT_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(actualBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                DebugLogger.log("AUDIO", "Speaker init FAILED")
                return
            }

            audioTrack?.play()
            audioTrack?.setVolume(1.0f)
            isPlaying = true
            DebugLogger.log("AUDIO", "Speaker started OK")

        } catch (e: Exception) {
            DebugLogger.log("AUDIO", "Speaker error: ${e.message}")
        }
    }

    fun playAudio(pcmBytes: ByteArray) {
        if (!isPlaying || audioTrack == null) {
            DebugLogger.log("AUDIO", "Play skipped: playing=$isPlaying")
            return
        }

        try {
            val written = audioTrack?.write(pcmBytes, 0, pcmBytes.size) ?: 0
            if (written > 0) {
                DebugLogger.log("AUDIO", "Played: $written bytes")
            }
        } catch (e: Exception) {
            DebugLogger.log("AUDIO", "Play error: ${e.message}")
        }
    }

    fun flushPlayback() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
            DebugLogger.log("AUDIO", "Flushed")
        } catch (_: Exception) {}
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
