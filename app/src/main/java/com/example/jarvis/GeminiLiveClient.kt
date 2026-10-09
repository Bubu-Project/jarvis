package com.example.jarvis

import android.widget.Toast
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveClient(private val apiKey: String, private val context: android.content.Context) {

    companion object {
        private const val WS_URL = "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MODEL = "models/gemini-3.1-flash-live-preview"
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()

    private var isSetupComplete = false

    interface GeminiListener {
        fun onReady()
        fun onAudioReceived(pcmBytes: ByteArray)
        fun onUserTranscript(text: String)
        fun onAITranscript(text: String)
        fun onInterrupted()
        fun onError(message: String)
        fun onDisconnected()
    }

    private var listener: GeminiListener? = null
    fun setListener(l: GeminiListener) { listener = l }

    private fun showToast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // =====================================================
    // CONNECT
    // =====================================================
    fun connect() {
        DebugLogger.log("GEMINI", "=== CONNECT START ===")

        val url = "$WS_URL?key=$apiKey&alt=websocket"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLogger.log("GEMINI", "WS opened")
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                DebugLogger.log("GEMINI", "MSG: ${text.take(200)}")
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                DebugLogger.log("GEMINI", "FAIL: ${t.message} ($code)")
                listener?.onError(t.message ?: "WS failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLogger.log("GEMINI", "WS closed: $code - $reason")
                listener?.onDisconnected()
            }
        })
    }

    // =====================================================
    // SETUP MESSAGE
    // =====================================================
    private fun sendSetup(ws: WebSocket) {
        val setup = """
        {
            "setup": {
                "model": "$MODEL",
                "generationConfig": {
                    "responseModalities": ["AUDIO"],
                    "speechConfig": {
                        "voiceConfig": {
                            "prebuiltVoiceConfig": {
                                "voiceName": "Aoede"
                            }
                        }
                    }
                },
                "realtimeInputConfig": {
                    "automaticActivityDetection": {
                        "disabled": false,
                        "startOfSpeechSensitivity": "START_SENSITIVITY_HIGH",
                        "endOfSpeechSensitivity": "END_SENSITIVITY_HIGH",
                        "prefixPaddingMs": 100,
                        "silenceDurationMs": 500
                    }
                },
                "systemInstruction": {
                    "parts": [{
                        "text": "You are Jarvis, a smart, witty and friendly AI assistant for an Indian user. Always reply in HINGLISH (Hindi + English mixed) using ROMAN script only. Address the user as 'Sir'. Keep replies SHORT - max 2-3 sentences. Be warm and helpful like a close friend."
                    }]
                }
            }
        }
        """.trimIndent()

        ws.send(setup)
        DebugLogger.log("GEMINI", "Setup sent with VAD config")
    }

    // =====================================================
    // HANDLE MESSAGES
    // =====================================================
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            // Setup complete
            if (json.has("setupComplete")) {
                isSetupComplete = true
                DebugLogger.log("GEMINI", "Setup complete!")
                listener?.onReady()
                return
            }

            // Server content (audio/text response)
            if (json.has("serverContent")) {
                val sc = json.getJSONObject("serverContent")

                // Interrupted (barge-in)
                if (sc.optBoolean("interrupted", false)) {
                    DebugLogger.log("GEMINI", "Interrupted!")
                    listener?.onInterrupted()
                }

                // Model turn (AI response)
                if (sc.has("modelTurn")) {
                    val mt = sc.getJSONObject("modelTurn")
                    val parts = mt.optJSONArray("parts")
                    DebugLogger.log("GEMINI", "modelTurn parts: ${parts?.length() ?: 0}")

                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)

                            // Audio data
                            if (part.has("inlineData")) {
                                val inline = part.getJSONObject("inlineData")
                                val mime = inline.optString("mimeType", "")
                                val data = inline.optString("data", "")
                                DebugLogger.log("GEMINI", "AUDIO: mime=$mime len=${data.length}")

                                if (mime.startsWith("audio/") && data.isNotBlank()) {
                                    val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                                    DebugLogger.log("GEMINI", "AUDIO decoded: ${bytes.size}b")
                                    listener?.onAudioReceived(bytes)
                                }
                            }

                            // AI text transcript
                            if (part.has("text")) {
                                val aiText = part.getString("text")
                                if (aiText.isNotBlank()) {
                                    DebugLogger.log("GEMINI", "AI TEXT: ${aiText.take(80)}")
                                    listener?.onAITranscript(aiText)
                                }
                            }
                        }
                    }
                }

                // Turn complete
                if (sc.optBoolean("turnComplete", false)) {
                    DebugLogger.log("GEMINI", "Turn complete")
                }
            }

            // Error
            if (json.has("error")) {
                val err = json.getJSONObject("error").optString("message", "Unknown")
                DebugLogger.log("GEMINI", "ERROR: ${err.take(100)}")
                listener?.onError(err)
            }

        } catch (e: Exception) {
            DebugLogger.log("GEMINI", "Parse error: ${e.message}")
        }
    }

    // =====================================================
    // SEND AUDIO - NAYA FORMAT (mediaChunks deprecated)
    // =====================================================
    fun sendAudio(pcmBytes: ByteArray) {
        if (!isSetupComplete) return

        try {
            val base64 = android.util.Base64.encodeToString(pcmBytes, android.util.Base64.NO_WRAP)
            val json = """
            {
                "realtimeInput": {
                    "audio": {
                        "data": "$base64",
                        "mimeType": "audio/pcm;rate=16000"
                    }
                }
            }
            """.trimIndent()

            webSocket?.send(json)
        } catch (e: Exception) {
            DebugLogger.log("GEMINI", "Send error: ${e.message}")
        }
    }

    fun disconnect() {
        isSetupComplete = false
        try { webSocket?.close(1000, "closing") } catch (_: Exception) {}
        webSocket = null
    }
}
