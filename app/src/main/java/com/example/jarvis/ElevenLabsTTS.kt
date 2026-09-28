package com.example.jarvis

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ElevenLabsTTS(private val context: Context) {

    // ⚠️ YAHAN APNI ELEVENLABS API KEY PASTE KAR
    private val API_KEY = "sk_f613c599d81c6d9fdc366d5ad8bb9743932767d26c5a5341"

    // Voice ID - Rachel (ChatGPT jaisi). Indian female ke liye change kar
    private val VOICE_ID = "21m00Tcm4TlvDq8ikWAM"

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isSpeaking = false

    interface TTSListener {
        fun onStart()
        fun onDone()
        fun onError(message: String)
    }

    private var listener: TTSListener? = null
    fun setListener(l: TTSListener) { listener = l }

    fun speak(text: String) {
        if (isSpeaking) {
            try { mediaPlayer?.stop() } catch (_: Exception) {}
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
        }

        isSpeaking = true
        listener?.onStart()

        val url = "https://api.elevenlabs.io/v1/text-to-speech/$VOICE_ID"

        val escapedText = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

        val json = """
            {
                "text": "$escapedText",
                "model_id": "eleven_multilingual_v2",
                "voice_settings": {
                    "stability": 0.5,
                    "similarity_boost": 0.75,
                    "style": 0.0,
                    "use_speaker_boost": true
                }
            }
        """.trimIndent()

        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", API_KEY)
            .addHeader("Accept", "audio/mpeg")
            .post(body)
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isSpeaking = false
                handler.post {
                    Toast.makeText(
                        context,
                        "TTS Fail: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    listener?.onError(e.message ?: "Network error")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    isSpeaking = false
                    val errBody = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                    handler.post {
                        Toast.makeText(
                            context,
                            "TTS ${response.code}: $errBody",
                            Toast.LENGTH_LONG
                        ).show()
                        listener?.onError("HTTP ${response.code}")
                    }
                    return
                }

                try {
                    val audioBytes = response.body?.bytes() ?: throw Exception("Empty audio")

                    val tempFile = File(context.cacheDir, "jarvis_tts.mp3")
                    FileOutputStream(tempFile).use { it.write(audioBytes) }

                    handler.post {
                        try {
                            mediaPlayer = MediaPlayer().apply {
                                setDataSource(tempFile.absolutePath)
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
                        } catch (e: Exception) {
                            isSpeaking = false
                            Toast.makeText(
                                context,
                                "Playback Error: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            listener?.onError("Playback: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    isSpeaking = false
                    handler.post {
                        Toast.makeText(
                            context,
                            "Parse Error: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        listener?.onError("Parse: ${e.message}")
                    }
                }
            }
        })
    }

    fun stop() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        isSpeaking = false
    }

    fun isSpeaking(): Boolean = isSpeaking
}
