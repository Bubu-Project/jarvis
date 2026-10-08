package com.example.jarvis

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class XaiVoiceClient(private val apiKey: String) {

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    interface XaiListener {
        fun onConnected()
        fun onAudioReceived(audioData: ByteArray)
        fun onTranscriptReceived(text: String, isFinal: Boolean)
        fun onError(message: String)
        fun onDisconnected()
    }

    private var listener: XaiListener? = null
    fun setListener(l: XaiListener) { listener = l }

    // =====================================================
    // STEP 1: Ephemeral Token Fetch
    // =====================================================
    fun connect() {
        val url = "https://api.x.ai/v1/realtime/client_secrets"
        
        val json = """
            {
                "expires_after": {
                    "seconds": 300
                },
                "session": {
                    "type": "realtime",
                    "model": "grok-voice-latest",
                    "voice": "eve",
                    "instructions": "You are Jarvis, a smart, witty and friendly AI assistant for an Indian user. Always reply in HINGLISH (Hindi + English mixed) using ROMAN script only. Address the user as 'Sir'. Keep replies SHORT - max 2-3 sentences. Be warm and helpful.",
                    "audio": {
                        "input": {
                            "format": { "type": "audio/pcm", "rate": 24000 },
                            "turn_detection": {
                                "type": "server_vad",
                                "threshold": 0.5,
                                "prefix_padding_ms": 300,
                                "silence_duration_ms": 500
                            }
                        },
                        "output": {
                            "format": { "type": "audio/pcm", "rate": 24000 },
                            "voice": "eve"
                        }
                    }
                }
            }
        """.trimIndent()

        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("JARVIS_XAI", "Token fetch failed: ${e.message}")
                listener?.onError("Token fetch failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: ""
                    Log.e("JARVIS_XAI", "Token error ${response.code}: $err")
                    listener?.onError("Token error ${response.code}")
                    return
                }
                try {
                    val respBody = response.body?.string() ?: ""
                    val json = JSONObject(respBody)
                    val token = json.optString("value", 
                        json.optString("client_secret", 
                            json.optJSONObject("client_secret")?.optString("value") ?: ""))
                    
                    if (token.isBlank()) {
                        listener?.onError("Token empty")
                        return
                    }
                    Log.d("JARVIS_XAI", "Token received")
                    connectWebSocket(token)
                } catch (e: Exception) {
                    Log.e("JARVIS_XAI", "Token parse error: ${e.message}")
                    listener?.onError("Token parse: ${e.message}")
                }
            }
        })
    }

    // =====================================================
    // STEP 2: WebSocket Connect
    // =====================================================
    private fun connectWebSocket(token: String) {
        val url = "wss://api.x.ai/v1/realtime?model=grok-voice-latest"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("JARVIS_XAI", "WebSocket connected")
                // Session update bhejo
                sendSessionUpdate(webSocket)
                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleTextMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener?.onAudioReceived(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("JARVIS_XAI", "WS failed: ${t.message}")
                listener?.onError(t.message ?: "WS failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("JARVIS_XAI", "WS closed: $code")
                listener?.onDisconnected()
            }
        })
    }

    private fun sendSessionUpdate(ws: WebSocket) {
        val json = """
            {
                "type": "session.update",
                "session": {
                    "instructions": "You are Jarvis, a smart and friendly AI assistant. Reply in Hinglish. Address user as Sir. Keep replies short.",
                    "voice": "eve"
                }
            }
        """.trimIndent()
        ws.send(json)
    }

    private fun handleTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            
            when (type) {
                "response.audio_transcript.delta",
                "response.output_audio_transcript.delta" -> {
                    val delta = json.optString("delta", "")
                    if (delta.isNotBlank()) listener?.onTranscriptReceived(delta, false)
                }
                "response.audio_transcript.done",
                "response.output_audio_transcript.done" -> {
                    val transcript = json.optString("transcript", "")
                    if (transcript.isNotBlank()) listener?.onTranscriptReceived(transcript, true)
                }
                "error" -> {
                    val err = json.optJSONObject("error")?.optString("message") ?: "Unknown error"
                    Log.e("JARVIS_XAI", "Server error: $err")
                    listener?.onError(err)
                }
            }
        } catch (e: Exception) {
            Log.e("JARVIS_XAI", "Parse error: ${e.message}")
        }
    }

    // Audio bhejo (mic se)
    fun sendAudio(pcmBytes: ByteArray) {
        webSocket?.send(ByteString.of(*pcmBytes))
    }

    fun disconnect() {
        try { webSocket?.close(1000, "closing") } catch (_: Exception) {}
        webSocket = null
    }
}
