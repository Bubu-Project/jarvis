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

class OpenAITTS(private val context: Context) {

    // GitHub Secret se aayegi
    private val API_KEY = BuildConfig.OPENAI_API_KEY

    // Voice: nova (female), shimmer (sweet female), alloy (neutral), coral (warm female)
    private val VOICE = "nova"

    // Model: gpt-4o-mini-tts (best quality + instructions support)
    private val MODEL = "gpt-4o-mini-tts"

    // Indian accent + warm tone
    private val INSTRUCTIONS = "Speak in a warm, friendly Indian English accent. " +
            "Sound like a helpful Indian female assistant. Be natural and conversational."

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

        val url = "https://api.openai.com/v1/audio/speech"

        // JSON body escape
        val escapedText = text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
        val escapedInstructions = INSTRUCTIONS.replace("\"", "\\\"")

        val json = """
            {
                "model": "$MODEL",
                "input": "$escapedText",
                "voice": "$VOICE",
                "instructions": "$escapedInstructions",
                "response_format": "mp3",
                "speed": 1.0
            }
        """.trimIndent()

        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("Content-Type", "application/json")
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
