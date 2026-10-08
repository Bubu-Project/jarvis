package com.example.jarvis

import android.util.Log
import android.widget.Toast
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveClient(private val apiKey: String, private val context: android.content.Context) {

    companion object {
        private const val WS_URL = "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        // Naya model (Sept 2026 release)
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
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // =====================================================
    // CONNECT
    // =====================================================
    fun connect() {
        Log.d("JARVIS_GEMINI", "=== CONNECT START ===")
        showToast("Gemini: Connecting...")

        // alt=websocket zaroori hai
        val url = "$WS_URL?key=$apiKey&alt=websocket"
        Log.d("JARVIS_GEMINI", "URL: ${url.take(80)}...")

        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("JARVIS_GEMINI", "WebSocket opened")
                showToast("Gemini: WS Open, setup bhej raha hoon...")
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                val body = try { response?.body?.string() ?: "" } catch (_: Exception) { "" }
                Log.e("JARVIS_GEMINI", "WS failed: ${t.message} | Code: $code | Body: $body")
                showToast("Gemini FAIL: ${t.message} ($code)")
                listener?.onError(t.message ?: "WS failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("JARVIS_GEMINI", "WS closed: $code - $reason")
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
                "systemInstruction": {
                    "parts": [{
                        "text": "You are Jarvis, a smart, witty and friendly AI assistant for an Indian user. Always reply in HINGLISH (Hindi + English mixed) using ROMAN script only. Address the user as 'Sir'. Keep replies SHORT - max 2-3 sentences. Be warm and helpful like a close friend. Never use Devanagari script."
                    }]
                }
            }
        }
        """.trimIndent()

        ws.send(setup)
        Log.d("JARVIS_GEMINI", "Setup sent")
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
                Log.d("JARVIS_GEMINI", "Setup complete!")
                showToast("Gemini: Ready!")
                listener?.onReady()
                return
            }

            // Server content
            if (json.has("serverContent")) {
                val serverContent = json.getJSONObject("serverContent")

                // Interrupted (barge-in)
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d("JARVIS_GEMINI", "Interrupted!")
                    listener?.onInterrupted()
                }

                // Model turn (AI response)
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)

                            // Audio data
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val mimeType = inlineData.optString("mimeType", "")
                                if (mimeType.startsWith("audio/")) {
                                    val base64 = inlineData.getString("data")
                                    val audioBytes = android.util.Base64.decode(
                                        base64, android.util.Base64.DEFAULT
                                    )
                                    listener?.onAudioReceived(audioBytes)
                                }
                            }

                            // AI text transcript
                            if (part.has("text")) {
                                val aiText = part.getString("text")
                                if (aiText.isNotBlank()) {
                                    listener?.onAITranscript(aiText)
                                }
                            }
                        }
                    }
                }

                // Turn complete
                if (serverContent.optBoolean("turnComplete", false)) {
                    Log.d("JARVIS_GEMINI", "Turn complete")
                }
            }

            // Error
            if (json.has("error")) {
                val err = json.getJSONObject("error").optString("message", "Unknown")
                Log.e("JARVIS_GEMINI", "Error: $err")
                showToast("Gemini: ${err.take(80)}")
                listener?.onError(err)
            }

        } catch (e: Exception) {
            Log.e("JARVIS_GEMINI", "Parse error: ${e.message}")
        }
    }

    // =====================================================
    // SEND AUDIO (mic se - 16kHz PCM)
    // =====================================================
    fun sendAudio(pcmBytes: ByteArray) {
        if (!isSetupComplete) return

        try {
            val base64 = android.util.Base64.encodeToString(pcmBytes, android.util.Base64.NO_WRAP)
            val json = """
            {
                "realtimeInput": {
                    "mediaChunks": [{
                        "mimeType": "audio/pcm;rate=16000",
                        "data": "$base64"
                    }]
                }
            }
            """.trimIndent()

            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e("JARVIS_GEMINI", "Send audio error: ${e.message}")
        }
    }

    fun disconnect() {
        isSetupComplete = false
        try { webSocket?.close(1000, "closing") } catch (_: Exception) {}
        webSocket = null
    }
}
