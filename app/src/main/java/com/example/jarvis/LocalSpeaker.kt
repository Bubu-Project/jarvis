package com.example.jarvis

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

// Phone ki apni Hindi awaaz (server ke bina bhi chalti hai): commands ka jawab, call aur message ka elaan.
// Awaaz na aaye to: Settings > Language > Text-to-speech mein Google TTS ke liye Hindi voice download karo.
class LocalSpeaker(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedAlarm: Int? = null
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

    private fun restoreAlarm() {
        val v = savedAlarm ?: return
        try { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } catch (_: Exception) {}
        savedAlarm = null
    }

    // bolna khatam hone par onDone chalta hai (awaaz na ho tab bhi turant chalta hai)
    // loud = true: ringtone ke upar bhi sunayi de, isliye alarm wali awaaz (tez) se bolta hai
    fun speak(text: String, loud: Boolean = false, onDone: () -> Unit = {}) {
        val t = tts
        if (t == null || !ready) {
            DebugLogger.log("TTS", "Bol nahi paya (awaaz taiyar nahi): $text")
            handler.post { onDone() }
            return
        }
        val id = UUID.randomUUID().toString()
        var params: Bundle? = null
        if (loud) {
            params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            try {
                if (savedAlarm == null) savedAlarm = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxOf(1, (max * 0.8).toInt()), 0)
            } catch (_: Exception) {}
        }
        val done: () -> Unit = {
            if (loud) restoreAlarm()
            onDone()
        }
        synchronized(callbacks) { callbacks[id] = done }
        val res = t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        if (res != TextToSpeech.SUCCESS) finish(id)
    }

    fun stop() {
        try { tts?.stop() } catch (_: Exception) {}
        restoreAlarm()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
    }
}
