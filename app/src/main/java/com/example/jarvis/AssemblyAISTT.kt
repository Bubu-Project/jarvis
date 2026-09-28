package com.example.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.gson.JsonParser
import okhttp3.*
import okio.ByteString.Companion.toByteString
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

    // ✅ No-op - mic always listens (barge-in ke liye)
    fun pauseStreaming() {
        // No-op
    }

    fun resumeStreaming() {
        // No-op
    }

    private fun fetchTokenAndConnect() {
        val client = OkHttpClient()
        val url = "https://streaming.assemblyai.com/v3/token?expires_in_seconds=300"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", API_KEY)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post {
                    Toast.makeText(context, "Token Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    try {
                        val responseBody = response.body?.string() ?: ""
                        val token = JsonParser.parseString(responseBody)
                            .asJsonObject.get("token").asString
                        connectWebSocket(token)
                    } catch (e: Exception) {
                        Log.e("JARVIS_STT", "Token parse: ${e.message}")
                    }
                }
            }
        })
    }

    private fun connectWebSocket(token: String) {
        try {
            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val url = "wss://streaming.assemblyai.com/v3/ws" +
                    "?sample_rate=16000" +
                    "&encoding=pcm_s16le" +
                    "&speech_model=universal-streaming-english" +
                    "&token=$token"

            val request = Request.Builder().url(url).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("JARVIS_STT", "Connected")
                    startAudioStreaming()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JsonParser.parseString(text).asJsonObject
                        val type = json.get("type")?.asString ?: ""
                        val transcript = json.get("transcript")?.asString ?: ""

                        if (type == "Turn" && transcript.isNotBlank()) {
                            val isFinal = json.get("end_of_turn")?.asBoolean ?: false
                            handler.post { listener?.onTranscript(transcript, isFinal) }
                        }
                    } catch (e: Exception) {
                        Log.e("JARVIS_STT", "Parse: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
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
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            // ✅ VOICE_COMMUNICATION = AEC built-in (echo cancel)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("JARVIS_STT", "Mic init failed")
                return
            }

            audioRecord?.startRecording()
            Log.d("JARVIS_STT", "Audio streaming started (AEC on)")

            recordingThread = Thread {
                val chunkSize = 1600
                val buffer = ByteArray(chunkSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // 2x boost
                        val amplified = ByteArray(read)
                        for (i in 0 until read step 2) {
                            if (i + 1 < read) {
                                var sample = ((buffer[i + 1].toInt() and 0xFF) shl 8) or
                                        (buffer[i].toInt() and 0xFF)
                                if (sample > 32767) sample -= 65536
                                sample = (sample * 2).coerceIn(-32768, 32767)
                                amplified[i] = (sample and 0xFF).toByte()
                                amplified[i + 1] = ((sample shr 8) and 0xFF).toByte()
                            }
                        }
                        webSocket?.send(amplified.toByteString())
                    }
                }
            }
            recordingThread?.start()

        } catch (e: Exception) {
            Log.e("JARVIS_STT", "Audio error: ${e.message}")
        }
    }

    fun stopListening() {
        isRecording = false
        try { recordingThread?.join(1000); recordingThread = null } catch (_: Exception) {}
        try { audioRecord?.stop(); audioRecord?.release(); audioRecord = null } catch (_: Exception) {}
        try { webSocket?.close(1000, "Closing"); webSocket = null } catch (_: Exception) {}
    }

    fun isListening(): Boolean = isRecording
}
