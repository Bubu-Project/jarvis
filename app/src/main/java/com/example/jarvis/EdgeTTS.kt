package com.example.jarvis

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import okhttp3.*
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class EdgeTTS(private val context: Context) {

    private val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private val CHROMIUM_VERSION = "130.0.2849.68"

    private val VOICE = "en-IN-NeerjaNeural"

    private var webSocket: WebSocket? = null
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isSpeaking = false
    private val audioBuffer = mutableListOf<Byte>()
    private var audioFile: File? = null

    interface TTSListener {
        fun onStart()
        fun onDone()
        fun onError(message: String)
    }

    private var listener: TTSListener? = null
    fun setListener(l: TTSListener) { listener = l }

    // =====================================================
    // Sec-MS-GEC Token Generator (Fresh har baar)
    // =====================================================
    private fun generateSecMsGec(): String {
        try {
            // Windows file time epoch (1601-01-01) in seconds
            val ticks = System.currentTimeMillis() / 1000L + 11644473600L
            // Round down to nearest 5 minutes (300 seconds)
            val roundedTicks = (ticks / 300) * 300
            // Convert to 100-nanosecond intervals
            val windowsTicks = roundedTicks * 10000000L

            val strToHash = "$windowsTicks$TRUSTED_CLIENT_TOKEN"

            val md = MessageDigest.getInstance("SHA-256")
            val hashBytes = md.digest(strToHash.toByteArray(Charsets.US_ASCII))

            // Uppercase hex string
            val hexString = hashBytes.joinToString("") { "%02X".format(it) }
            Log.d("JARVIS_EDGE", "GEC token: $hexString")
            return hexString
        } catch (e: Exception) {
            Log.e("JARVIS_EDGE", "GEC error: ${e.message}")
            return ""
        }
    }

    private fun buildWebSocketUrl(): String {
        val gec = generateSecMsGec()
        return "wss://speech.platform.bing.com/consumer/speech/" +
                "synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                "&Sec-MS-GEC=$gec" +
                "&Sec-MS-GEC-Version=1-$CHROMIUM_VERSION" +
                "&ConnectionId=${UUID.randomUUID().toString().replace("-", "")}"
    }

    fun speak(text: String) {
        if (isSpeaking) {
            stop()
        }

        isSpeaking = true
        audioBuffer.clear()
        listener?.onStart()

        try {
            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(buildWebSocketUrl())
                .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
                .addHeader("Accept-Encoding", "gzip, deflate, br")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Pragma", "no-cache")
                .addHeader("Cache-Control", "no-cache")
                .build()

            val connectionId = UUID.randomUUID().toString().replace("-", "")

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("JARVIS_EDGE", "WebSocket Connected")
                    sendConfig(webSocket)
                    sendSSML(webSocket, text, connectionId)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleBinaryAudio(bytes.toByteArray())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        handler.post { playAudio() }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isSpeaking = false
                    val err = t.message ?: "Connection failed"
                    val code = response?.code ?: 0
                    Log.e("JARVIS_EDGE", "Failed: $err | Code: $code")
                    handler.post {
                        Toast.makeText(
                            context,
                            "Edge TTS: $err (Code: $code)",
                            Toast.LENGTH_LONG
                        ).show()
                        listener?.onError(err)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d("JARVIS_EDGE", "Closed: $code")
                }
            })

        } catch (e: Exception) {
            isSpeaking = false
            Log.e("JARVIS_EDGE", "Start error: ${e.message}")
            handler.post {
                Toast.makeText(context, "Edge Error: ${e.message}", Toast.LENGTH_LONG).show()
                listener?.onError(e.message ?: "Unknown")
            }
        }
    }

    private fun sendConfig(ws: WebSocket) {
        val timestamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            java.util.Locale.US
        ).format(java.util.Date())

        val config = "X-Timestamp:$timestamp\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"

        ws.send(config)
    }

    private fun sendSSML(ws: WebSocket, text: String, connectionId: String) {
        val escapedText = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        val ssml = "X-RequestId:$connectionId\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:${System.currentTimeMillis()}Z\r\n" +
                "Path:ssml\r\n\r\n" +
                "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-IN'>" +
                "<voice name='$VOICE'>" +
                "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>" +
                escapedText +
                "</prosody></voice></speak>"

        ws.send(ssml)
    }

    private fun handleBinaryAudio(data: ByteArray) {
        try {
            if (data.size < 2) return

            val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)

            if (data.size < 2 + headerLength) {
                audioBuffer.addAll(data.toList())
                return
            }

            val header = String(data, 2, headerLength, Charsets.UTF_8)

            if (header.contains("Path:audio")) {
                val audioStart = 2 + headerLength
                if (audioStart < data.size) {
                    for (i in audioStart until data.size) {
                        audioBuffer.add(data[i])
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("JARVIS_EDGE", "Binary parse error: ${e.message}")
        }
    }

    private fun playAudio() {
        try {
            if (audioBuffer.isEmpty()) {
                isSpeaking = false
                listener?.onError("No audio received")
                return
            }

            audioFile = File(context.cacheDir, "jarvis_edge_tts.mp3")
            FileOutputStream(audioFile).use { it.write(audioBuffer.toByteArray()) }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile!!.absolutePath)
                setOnCompletionListener {
                    isSpeaking = false
                    listener?.onDone()
                }
                setOnErrorListener { _, _, _ ->
                    isSpeaking = false
                    listener?.onError("Playback error")
                    true
                }
                prepare()
                start()
            }

            Log.d("JARVIS_EDGE", "Playing: ${audioBuffer.size} bytes")

        } catch (e: Exception) {
            isSpeaking = false
            Log.e("JARVIS_EDGE", "Playback error: ${e.message}")
            listener?.onError(e.message ?: "Playback failed")
        }
    }

    fun stop() {
        try { webSocket?.close(1000, "stop") } catch (_: Exception) {}
        webSocket = null
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        audioBuffer.clear()
        isSpeaking = false
    }

    fun isSpeaking(): Boolean = isSpeaking
}
