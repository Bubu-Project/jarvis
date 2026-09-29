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

class DeepgramTTS(private val context: Context) {

    private val API_KEY = BuildConfig.DEEPGRAM_API_KEY

    // Aura-2 voice - Indian English female
    // Options: aura-2-asteria-en, aura-2-luna-en, aura-2-stella-en, aura-2-athena-en
    private val VOICE_MODEL = "aura-2-asteria-en"

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

        val url = "https://api.deepgram.com/v1/speak?model=$VOICE_MODEL&encoding=mp3"

        val escapedText = text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")

        val json = """{"text": "$escapedText"}"""

        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $API_KEY")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isSpeaking = false
                handler.post {
                    Toast.makeText(
                        context,
                        "Deepgram Fail: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    listener?.onError(e.message ?: "Network error")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    isSpeaking = false
                    val errBody = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                    Log.e("JARVIS_DEEPGRAM", "HTTP ${response.code}: $errBody")
                    handler.post {
                        Toast.makeText(
                            context,
                            "Deepgram ${response.code}",
                            Toast.LENGTH_LONG
                        ).show()
                        listener?.onError("HTTP ${response.code}")
                    }
                    return
                }

                try {
                    val audioBytes = response.body?.bytes() ?: throw Exception("Empty audio")

                    val tempFile = File(context.cacheDir, "jarvis_deepgram.mp3")
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
                            Log.d("JARVIS_DEEPGRAM", "Playing: ${audioBytes.size} bytes")
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
