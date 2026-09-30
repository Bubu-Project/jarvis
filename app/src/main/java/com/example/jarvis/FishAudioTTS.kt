package com.example.jarvis

import android.content.Context
import android.media.AudioAttributes
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

class FishAudioTTS(private val context: Context) {

    private val API_KEY = BuildConfig.FISH_AUDIO_API_KEY

    // Pinky - Hindi female voice (Hinglish ke liye best)
    private val VOICE_ID = "3bdc0c48fd264887bb63511c3a258f25"

    private val MODEL = "s2.1-pro-free"

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
            stop()
        }

        isSpeaking = true
        listener?.onStart()

        val url = "https://api.fish.audio/v1/tts"

        val escapedText = text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")

        val json = """
            {
                "text": "$escapedText",
                "reference_id": "$VOICE_ID",
                "format": "mp3",
                "mp3_bitrate": 128,
                "normalize": true,
                "latency": "normal",
                "speed": 1.2
            }
        """.trimIndent()

        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("model", MODEL)
            .post(body)
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isSpeaking = false
                handler.post {
                    Toast.makeText(
                        context,
                        "Fish Audio Fail: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    listener?.onError(e.message ?: "Network error")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    isSpeaking = false
                    val errBody = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                    Log.e("JARVIS_FISH", "HTTP ${response.code}: $errBody")
                    handler.post {
                        Toast.makeText(
                            context,
                            "Fish Audio ${response.code}",
                            Toast.LENGTH_LONG
                        ).show()
                        listener?.onError("HTTP ${response.code}")
                    }
                    return
                }

                try {
                    val audioBytes = response.body?.bytes() ?: throw Exception("Empty audio")

                    val tempFile = File(context.cacheDir, "jarvis_fish.mp3")
                    FileOutputStream(tempFile).use { it.write(audioBytes) }

                    handler.post {
                        try {
                            mediaPlayer = MediaPlayer().apply {
                                // ✅ Audio attributes - media stream use kare
                                val audioAttributes = AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                                setAudioAttributes(audioAttributes)

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
                                // ✅ VOLUME MAX KARO
                                setVolume(1.0f, 1.0f)
                                start()
                            }
                            Log.d("JARVIS_FISH", "Playing: ${audioBytes.size} bytes")
                        } catch (e: Exception) {
                            isSpeaking = false
                            listener?.onError("Playback: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    isSpeaking = false
                    handler.post {
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
