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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

    // STEP 1: Temporary Token Fetch Karna (v2 API)
    private fun fetchTokenAndConnect() {
        val client = OkHttpClient()
        val json = "{\"expires_in\": 3600}"
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.assemblyai.com/v2/realtime/token")
            .addHeader("Authorization", API_KEY)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("JARVIS_STT", "Token fetch failed: ${e.message}")
                handler.post { Toast.makeText(context, "Token Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    try {
                        val responseBody = response.body?.string() ?: ""
                        val token = JsonParser.parseString(responseBody).asJsonObject.get("token").asString
                        Log.d("JARVIS_STT", "Token fetched")
                        connectWebSocket(token)
                    } catch (e: Exception) {
                        Log.e("JARVIS_STT", "Token parse error: ${e.message}")
                    }
                } else {
                    Log.e("JARVIS_STT", "Token API failed: ${response.code}")
                    handler.post { Toast.makeText(context, "Token API Error: ${response.code}", Toast.LENGTH_LONG).show() }
                }
            }
        })
    }

    // STEP 2: WebSocket Connect Karna (v2 Realtime API)
    @SuppressLint("MissingPermission")
    private fun connectWebSocket(token: String) {
        try {
            val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            val request = Request.Builder()
                .url("wss://api.assemblyai.com/v2/realtime/ws?sample_rate=16000&token=$token")
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
                            handler.post { listener?.onTranscript(transcript, true) }
                        } else if (type == "PartialTranscript" && transcript.isNotBlank()) {
                            handler.post { listener?.onTranscript(transcript, false) }
                        }
                    } catch (e: Exception) {
                        Log.e("JARVIS_STT", "Parse error: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("JARVIS_STT", "WebSocket failed: ${t.message}")
                    handler.post { listener?.onError(t.message ?: "Connection failed") }
                }
            })
            isRecording = true
        } catch (e: Exception) {
            Log.e("JARVIS_STT", "Connect failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioStreaming() {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("JARVIS_STT", "Mic init failed")
            handler.post { Toast.makeText(context, "Mic Init Failed!", Toast.LENGTH_LONG).show() }
            return
        }

        audioRecord?.startRecording()
        Log.d("JARVIS_STT", "Audio streaming started")

        recordingThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val base64Audio = Base64.encodeToString(buffer.copyOf(read), Base64.NO_WRAP)
                    val json = JsonObject().apply { addProperty("audio_data", base64Audio) }
                    webSocket?.send(json.toString())
                }
            }
        }
        recordingThread?.start()
    }

    fun stopListening() {
        isRecording = false
        try { recordingThread?.join(1000); recordingThread = null } catch (_: Exception) {}
        try { audioRecord?.stop(); audioRecord?.release(); audioRecord = null } catch (_: Exception) {}
        try { webSocket?.close(1000, "Closing"); webSocket = null } catch (_: Exception) {}
    }
}
