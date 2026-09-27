package com.example.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class AssemblyAISTT(private val context: Context) {

    // ⚠️ YAHAN APNI ASSEMBLYAI API KEY PASTE KAR
    private val API_KEY = "3f61e0470f894c2e9e3565f4187cfab1"

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private val handler = Handler(Looper.getMainLooper())

    interface TranscriptionListener {
        fun onTranscript(text: String, isFinal: Boolean)
        fun onError(message: String)
    }

    private var listener: TranscriptionListener? = null

    fun setListener(l: TranscriptionListener) {
        listener = l
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isRecording) return

        try {
            // 1. WebSocket connection to AssemblyAI
            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url("wss://api.assemblyai.com/v2/realtime/ws?sample_rate=16000")
                .addHeader("Authorization", API_KEY)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("JARVIS_STT", "WebSocket Connected")
                    startAudioStreaming()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JsonParser.parseString(text).asJsonObject
                        val type = json.get("type")?.asString ?: ""
                        val transcript = json.get("text")?.asString ?: ""

                        if (type == "FinalTranscript" && transcript.isNotBlank()) {
                            handler.post {
                                listener?.onTranscript(transcript, true)
                            }
                        } else if (type == "PartialTranscript" && transcript.isNotBlank()) {
                            handler.post {
                                listener?.onTranscript(transcript, false)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("JARVIS_STT", "Parse error: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("JARVIS_STT", "WebSocket failed: ${t.message}")
                    handler.post {
                        listener?.onError(t.message ?: "Connection failed")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("JARVIS_STT", "WebSocket Closed")
                }
            })

            isRecording = true

        } catch (e: Exception) {
            Log.e("JARVIS_STT", "Start failed: ${e.message}")
            listener?.onError(e.message ?: "Unknown error")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioStreaming() {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val audioData = buffer.copyOf(read)
                    val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)

                    val json = JsonObject().apply {
                        addProperty("audio_data", base64Audio)
                    }

                    webSocket?.send(json.toString())
                }
            }
        }
        recordingThread?.start()
    }

    fun stopListening() {
        isRecording = false

        try {
            recordingThread?.join(1000)
            recordingThread = null
        } catch (_: Exception) {}

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) {}

        try {
            webSocket?.close(1000, "Closing")
            webSocket = null
        } catch (_: Exception) {}

        Log.d("JARVIS_STT", "Stopped listening")
    }

    fun isListening(): Boolean = isRecording
}
