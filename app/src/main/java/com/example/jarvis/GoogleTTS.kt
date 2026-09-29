package com.example.jarvis

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.net.URLEncoder

class GoogleTTS(private val context: Context) {

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

        try {
            // Text ko URL encode karo
            val encodedText = URLEncoder.encode(text, "UTF-8")

            // Google Translate TTS - en-IN accent (Indian English female)
            val url = "https://translate.google.com/translate_tts" +
                    "?ie=UTF-8" +
                    "&tl=en-IN" +
                    "&client=tw-ob" +
                    "&q=$encodedText"

            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { mp ->
                    Log.d("JARVIS_GOOGLE_TTS", "Playing: $text")
                    mp.start()
                }
                setOnCompletionListener {
                    isSpeaking = false
                    listener?.onDone()
                }
                setOnErrorListener { _, what, extra ->
                    isSpeaking = false
                    Log.e("JARVIS_GOOGLE_TTS", "Error: $what, $extra")
                    handler.post {
                        listener?.onError("Playback error")
                    }
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            isSpeaking = false
            Log.e("JARVIS_GOOGLE_TTS", "Error: ${e.message}")
            handler.post {
                Toast.makeText(
                    context,
                    "Google TTS Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                listener?.onError(e.message ?: "Unknown error")
            }
        }
    }

    fun stop() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        isSpeaking = false
    }

    fun isSpeaking(): Boolean = isSpeaking
}
