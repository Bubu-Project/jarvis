package com.example.jarvis

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.io.File

// Phone aane par:
//  1) Jarvis bolta hai "सर, <naam> का फ़ोन आ रहा है। उठाऊँ या काट दूँ?"
//  2) Tum bolte ho "उठाओ" ya "काट दो"
//  3) Jarvis call uthata / kaat deta hai (bina phone chhue)
// Zaroori permissions: READ_PHONE_STATE, READ_CALL_LOG, READ_CONTACTS, ANSWER_PHONE_CALLS, RECORD_AUDIO
class PhoneCallHandler(
    private val context: Context,
    private val audioRecorder: AudioRecorderHelper,
    private val apiClient: JarvisApiClient,
    private val speaker: LocalSpeaker,
    private val onCallStarted: () -> Unit,   // call chalu/ring: baaki sunna rok do
    private val onCallEnded: () -> Unit      // call khatam: dobara sunna shuru
) {
    private val handler = Handler(Looper.getMainLooper())
    private var ringing = false
    private var inCall = false
    private var announced = false
    private var lastNumber: String? = null
    private var announceTask: Runnable? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> onRinging(number)
                TelephonyManager.EXTRA_STATE_OFFHOOK -> onOffhook()
                TelephonyManager.EXTRA_STATE_IDLE -> onIdle()
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        registered = false
        cancelAnnounce()
    }

    private fun cancelAnnounce() {
        announceTask?.let { handler.removeCallbacks(it) }
        announceTask = null
    }

    private fun onRinging(number: String?) {
        if (!number.isNullOrBlank()) lastNumber = number
        if (inCall) return
        if (!ringing) {
            ringing = true
            announced = false
            DebugLogger.log("CALL", "Phone aa raha hai")
            onCallStarted()
        }
        // Number wala doosra broadcast aane ke liye thoda ruko
        if (!announced && announceTask == null) {
            val task = Runnable {
                announceTask = null
                announce()
            }
            announceTask = task
            handler.postDelayed(task, 1200)
        }
    }

    private fun onOffhook() {
        cancelAnnounce()
        ringing = false
        inCall = true
        try { audioRecorder.stopRecording() } catch (_: Exception) {}
        speaker.stop()
        onCallStarted()
    }

    private fun onIdle() {
        if (!ringing && !inCall && !announced) return
        cancelAnnounce()
        ringing = false
        inCall = false
        announced = false
        lastNumber = null
        try { audioRecorder.stopRecording() } catch (_: Exception) {}
        speaker.stop()
        DebugLogger.log("CALL", "Call khatam")
        handler.postDelayed({ onCallEnded() }, 1500)
    }

    private fun announce() {
        if (!ringing) return
        announced = true
        val num = lastNumber
        val name = if (!num.isNullOrBlank()) lookupName(num) else null
        val say = when {
            name != null -> "सर, $name का फ़ोन आ रहा है। उठाऊँ या काट दूँ?"
            !num.isNullOrBlank() -> "सर, एक अनजान नंबर से फ़ोन आ रहा है। उठाऊँ या काट दूँ?"
            else -> "सर, फ़ोन आ रहा है। उठाऊँ या काट दूँ?"
        }
        DebugLogger.log("CALL", say)
        speaker.speak(say) { listenForDecision(0) }
    }

    private fun lookupName(number: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) { null }
    }

    private fun listenForDecision(attempt: Int) {
        if (!ringing || attempt > 3) return
        val f = File(context.cacheDir, "call_decision.wav")
        audioRecorder.startRecording(f) {
            if (!ringing) return@startRecording
            if (f.length() < 2000) {
                listenForDecision(attempt + 1)
                return@startRecording
            }
            apiClient.transcribe(f) { text ->
                if (ringing) handleDecision(text ?: "", attempt)
            }
        }
    }

    private fun handleDecision(text: String, attempt: Int) {
        val t = text.lowercase()
        DebugLogger.log("CALL", "Suna: $t")
        val reject = listOf("मत", "काट", "कट", "cut", "reject", "नहीं", "नही", "मना", "decline", "disconnect", "हटा")
        val accept = listOf("उठा", "हाँ", "हां", "रिसीव", "receive", "answer", "ले लो", "बात", "yes", "जी", "ओके", "okay")
        when {
            reject.any { t.contains(it) } -> rejectCall()
            accept.any { t.contains(it) } -> acceptCall()
            else -> listenForDecision(attempt + 1)
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptCall() {
        try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tm.acceptRingingCall()
                DebugLogger.log("CALL", "Call utha li")
                // Haath khali rahe isliye speaker on karne ki koshish (har phone par nahi chalta)
                handler.postDelayed({
                    try {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        am.isSpeakerphoneOn = true
                    } catch (_: Exception) {}
                }, 2000)
            }
        } catch (e: Exception) {
            DebugLogger.log("CALL", "Call uthane ki permission nahi: ${e.message}")
            speaker.speak("सर, फ़ोन उठाने की अनुमति नहीं है।")
        }
    }

    @SuppressLint("MissingPermission")
    private fun rejectCall() {
        try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tm.endCall()
                DebugLogger.log("CALL", "Call kaat di")
            } else {
                speaker.speak("सर, इस फ़ोन पर मैं कॉल नहीं काट सकता।")
            }
        } catch (e: Exception) {
            DebugLogger.log("CALL", "Call katne ki permission nahi: ${e.message}")
            speaker.speak("सर, कॉल काटने की अनुमति नहीं है।")
        }
    }
}
