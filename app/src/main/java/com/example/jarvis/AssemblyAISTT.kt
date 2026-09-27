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
import android.widget.Toast
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

class AssemblyAISTT(private val context: Context) {

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
    fun setListener(l: TranscriptionListener) { listener = l }

    fun startListening() {
        if (isRecording) return
        fetchTokenAndConnect()
    }

    // =====================================================
    // STEP 1: Temporary Token Fetch (v3 API)
    // =====================================================
    private fun fetchTokenAndConnect() {
        val client = OkHttpClient()
        // expires_in_seconds 600 se kam hona chahiye
        val url = "https://streaming.assemblyai.com/v3/token?expires_in_seconds=300"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", API_KEY) // Bina "Bearer" ke
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("JARVIS_STT", "Token fetch failed: ${e.message}")
                handler.post {
                    Toast.makeText(context, "Token Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    try {
                        val responseBody = response.body?.string() ?: ""
                        Log.d("JARVIS_STT", "Token response: $responseBody")
                        val token = JsonParser.parseString(responseBody)
                            .asJsonObject.get("token").asString
                        Log.d("JARVIS_STT", "Token fetched successfully")
                        connectWebSocket(token)
                    } catch (e: Exception) {
                        Log.e("JARVIS_STT", "Token parse error: ${e.message}")
                    }
                } else {
                    val errBody = response.body?.string() ?: ""
                    Log.e("JARVIS_STT", "Token API failed: ${response.code} - $errBody")
                    handler.post {
                        Toast.makeText(
                            context,
                            "Token API Error: ${response.code}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }

    // =====================================================
    // STEP 2: WebSocket Connect (v3 API)
    // =====================================================
    @SuppressLint("MissingPermission")
    private fun connectWebSocket(token: String) {
        try {
            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            // v3 WebSocket URL with required parameters
            val url = "wss://streaming.assemblyai.com/v3/ws" +
                    "?sample_rate=16000" +
                    "&speech_model=universal-streaming-english" +
                    "&language_codes=en" +
                    "&token=$token"

            Log.d("JARVIS_STT", "Connecting to: $url")

            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("JARVIS_STT", "WebSocket Connected")
                    startAudioStreaming()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        Log.d("JARVIS_STT", "Raw message: $text")
                        val json = JsonParser.parseString(text).asJsonObject
                        val type = json.get("type")?.asString ?: ""
                        val transcript = json.get("transcript")?.asString ?: ""

                        // v3 message format
                        if (type == "Turn" && transcript.isNotBlank()) {
                            val isFinal = json.get("end_of_turn")?.asBoolean ?: false
                            handler.post { listener?.onTranscript(transcript, isFinal) }
                        } else if (type == "Begin") {
                            Log.d("JARVIS_STT", "Session Begin received")
                        }
                    } catch (e: Exception) {
                        Log.e("JARVIS_STT", "Parse error: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code ?: 0
                    val body = response?.body?.string() ?: ""
                    Log.e("JARVIS_STT", "WebSocket failed: ${t.message} | Code: $code | Body: $body")
                    handler.post {
                        Toast.makeText(
                            context,
                            "AssemblyAI Error: ${t.message} (Code: $code)",
                            Toast.LENGTH_LONG
                        ).show()
                        listener?.onError(t.message ?: "Connection failed")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("JARVIS_STT", "WebSocket Closed: $code - $reason")
                }
            })
            isRecording = true
        } catch (e: Exception) {
            Log.e("JARVIS_STT", "Connect failed: ${e.message}")
        }
    }

    // =====================================================
    // STEP 3: Audio Streaming to AssemblyAI
    // =====================================================
    @SuppressLint("MissingPermission")
    private fun startAudioStreaming() {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("JARVIS_STT", "Mic init failed")
                handler.post {
                    Toast.makeText(context, "Mic init failed!", Toast.LENGTH_LONG).show()
                }
                return
            }

            audioRecord?.startRecording()
            Log.d("JARVIS_STT", "Audio streaming started")

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val base64Audio = Base64.encodeToString(
                            buffer.copyOf(read),
                            Base64.NO_WRAP
                        )
                        val json = JsonObject().apply {
                            addProperty("audio_data", base64Audio)
                        }
                        webSocket?.send(json.toString())
                    }
                }
            }
            recordingThread?.start()

        } catch (e: Exception) {
            Log.e("JARVIS_STT", "Audio start error: ${e.message}")
            handler.post {
                Toast.makeText(context, "Audio Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // =====================================================
    // STOP
    // =====================================================
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
