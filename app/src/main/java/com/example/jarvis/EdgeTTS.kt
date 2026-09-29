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
import java.io.IOException
import java.util.UUID

class EdgeTTS(private val context: Context) {

    // Edge TTS endpoint (free, no API key)
    private val WS_URL = "wss://speech.platform.bing.com/consumer/speech/" +
            "synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    // Indian English female voice
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

    fun speak(text: String) {
        if (isSpeaking) {
            stop()
        }

        isSpeaking = true
        audioBuffer.clear()
        listener?.onStart()

        try {
            val client = OkHttpClient.Builder()
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(WS_URL)
                .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
                .addHeader("Accept-Encoding", "gzip, deflate, br")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            val connectionId = UUID.randomUUID().toString().replace("-", "")

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("JARVIS_EDGE", "Connected")
                    sendConfig(webSocket)
                    sendSSML(webSocket, text, connectionId)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Binary audio data
                    handleBinaryAudio(bytes.toByteArray())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // Control messages - ignore
                    if (text.contains("Path:turn.end")) {
                        // Audio complete
                        handler.post { playAudio() }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isSpeaking = false
                    val err = t.message ?: "Connection failed"
                    Log.e("JARVIS_EDGE", "Failed: $err")
                    handler.post {
                        Toast.makeText(context, "Edge TTS: $err", Toast.LENGTH_LONG).show()
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
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .format(java.util.Date())

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
            // Binary frame: 2 bytes header length (big endian) + header + audio
            if (data.size < 2) return

            val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)

            if (data.size < 2 + headerLength) {
                // Too short - might be all audio
                audioBuffer.addAll(data.toList())
                return
            }

            val header = String(data, 2, headerLength, Charsets.UTF_8)

            // Only add if this is audio data
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

            Log.d("JARVIS_EDGE", "Playing audio: ${audioBuffer.size} bytes")

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
