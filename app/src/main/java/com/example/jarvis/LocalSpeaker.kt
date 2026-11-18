package com.example.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

// Phone ki apni Hindi awaaz (server ke bina bhi chalti hai): commands ka jawab, call aur message ka elaan.
// Awaaz na aaye to: Settings > Language > Text-to-speech mein Google TTS ke liye Hindi voice download karo.
class LocalSpeaker(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val callbacks = HashMap<String, () -> Unit>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("hi", "IN"))
                ready = r != null && r >= TextToSpeech.LANG_AVAILABLE
                DebugLogger.log("TTS", if (ready) "Hindi awaaz taiyar" else "Hindi awaaz NAHI mili (code=$r)")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { finish(utteranceId) }
                    override fun onError(utteranceId: String?) { finish(utteranceId) }
                })
            } else {
                DebugLogger.log("TTS", "TextToSpeech shuru nahi hua (status=$status)")
            }
        }
    }

    fun isReady(): Boolean = ready

    private fun finish(id: String?) {
        if (id == null) return
        val cb = synchronized(callbacks) { callbacks.remove(id) }
        if (cb != null) handler.post { cb() }
    }

    // bolna khatam hone par onDone chalta hai (awaaz na ho tab bhi turant chalta hai)
    fun speak(text: String, onDone: () -> Unit = {}) {
        val t = tts
        if (t == null || !ready) {
            DebugLogger.log("TTS", "Bol nahi paya (awaaz taiyar nahi): $text")
            handler.post { onDone() }
            return
        }
        val id = UUID.randomUUID().toString()
        synchronized(callbacks) { callbacks[id] = onDone }
        val res = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (res != TextToSpeech.SUCCESS) finish(id)
    }

    fun stop() {
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
    }
}
