package com.example.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
//  1) Ringtone chup (vibrate), phir Jarvis bolta hai "सर, <naam> का फ़ोन आ रहा है। उठाऊँ या काट दूँ?"
//  2) Tum bolte ho "उठाओ" ya "काट दो"
//  3) Jarvis call uthata / kaat deta hai (bina phone chhue)
//  4) Call na uthe to "फ़ोन छूट गया" bhi batata hai
// Zaroori permissions: READ_PHONE_STATE, READ_CALL_LOG, READ_CONTACTS, ANSWER_PHONE_CALLS, RECORD_AUDIO
class PhoneCallHandler(
    private val context: Context,
    private val audioRecorder: AudioRecorderHelper,
    private val apiClient: JarvisApiClient,
    private val localRecognizer: LocalRecognizer,
    private val speaker: LocalSpeaker,
    private val onCallStarted: () -> Unit,   // call chalu/ring: baaki sunna rok do
    private val onCallEnded: () -> Unit      // call khatam: dobara sunna shuru
) {
    private val handler = Handler(Looper.getMainLooper())
    private var ringing = false
    private var inCall = false
    private var announced = false
    private var handledByUs = false
    private var lastNumber: String? = null
    private var lastName: String? = null
    private var announceTask: Runnable? = null
    private var registered = false
    private var savedRinger: Int? = null
    private var decisionStarted = false
    private var savedRingVol: Int? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            DebugLogger.log("CALL", "Phone state: $state")
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
        val perm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        DebugLogger.log("CALL", "Call sunne wala chalu. READ_PHONE_STATE=" + if (perm) "OK" else "NAHI (call ka elaan nahi hoga)")
    }

    fun unregister() {
        if (!registered) return
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        registered = false
        cancelAnnounce()
        restoreRinger()
    }

    private fun cancelAnnounce() {
        announceTask?.let { handler.removeCallbacks(it) }
        announceTask = null
    }

    // Ringtone ki jagah sirf vibrate. Na ho paye to ringtone ki awaaz bilkul kam kar deta hai
    private fun silenceRinger() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (savedRinger == null) savedRinger = am.ringerMode
        try {
            am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            DebugLogger.log("CALL", "Ringtone chup kar di (vibrate)")
            return
        } catch (e: Exception) {
            DebugLogger.log("CALL", "Vibrate mode nahi laga: ${e.message}")
        }
        try {
            if (savedRingVol == null) savedRingVol = am.getStreamVolume(AudioManager.STREAM_RING)
            am.setStreamVolume(AudioManager.STREAM_RING, 1, 0)
            DebugLogger.log("CALL", "Ringtone ki awaaz minimum kar di")
        } catch (e: Exception) {
            DebugLogger.log("CALL", "Ringtone chup nahi hui (Do Not Disturb access dijiye): ${e.message}")
        }
    }

    private fun restoreRinger() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedRinger?.let { try { am.ringerMode = it } catch (_: Exception) {} }
        savedRingVol?.let { try { am.setStreamVolume(AudioManager.STREAM_RING, it, 0) } catch (_: Exception) {} }
        savedRinger = null
        savedRingVol = null
    }

    private fun onRinging(number: String?) {
        if (!number.isNullOrBlank()) lastNumber = number
        if (inCall) return
        if (!ringing) {
            ringing = true
            announced = false
            handledByUs = false
            decisionStarted = false
            val logPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED
            DebugLogger.log("CALL", "Phone aa raha hai. number=" + (number ?: "NAHI MILA") +
                (if (logPerm) "" else " (READ_CALL_LOG permission nahi, isliye naam nahi milega)"))
            silenceRinger()
            onCallStarted()
        }
        // Number wala doosra broadcast aane ke liye thoda ruko
        if (!announced && announceTask == null) {
            val task = Runnable {
                announceTask = null
                announce()
            }
            announceTask = task
            handler.postDelayed(task, 900)
        }
    }

    private fun onOffhook() {
        cancelAnnounce()
        ringing = false
        inCall = true
        restoreRinger()
        try { audioRecorder.stopRecording() } catch (_: Exception) {}
        speaker.stop()
        onCallStarted()
    }

    private fun onIdle() {
        if (!ringing && !inCall && !announced) return
        val missed = ringing && !inCall && !handledByUs
        val who = lastName ?: lastNumber
        cancelAnnounce()
        ringing = false
        inCall = false
        announced = false
        handledByUs = false
        decisionStarted = false
        lastNumber = null
        lastName = null
        try { audioRecorder.stopRecording() } catch (_: Exception) {}
        speaker.stop()
        restoreRinger()
        DebugLogger.log("CALL", "Call khatam")
        if (missed && !who.isNullOrBlank()) {
            handler.postDelayed({
                speaker.speak("सर, $who का फ़ोन छूट गया।") { onCallEnded() }
            }, 700)
        } else {
            handler.postDelayed({ onCallEnded() }, 1500)
        }
    }

    private fun announce() {
        if (!ringing) return
        announced = true
        val num = lastNumber
        val name = if (!num.isNullOrBlank()) lookupName(num) else null
        lastName = name
        val say = when {
            name != null -> "सर, $name का फ़ोन आ रहा है। उठाऊँ या काट दूँ?"
            !num.isNullOrBlank() -> "सर, एक अनजान नंबर से फ़ोन आ रहा है। उठाऊँ या काट दूँ?"
            else -> "सर, फ़ोन आ रहा है। उठाऊँ या काट दूँ?"
        }
        DebugLogger.log("CALL", say)
        speaker.speak(say, loud = true) { startDecision() }
        // Awaaz khatam hone ka signal na aaye to bhi 7 second baad sunna shuru kar do
        handler.postDelayed({ startDecision() }, 7000)
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

    private fun startDecision() {
        if (!ringing || decisionStarted) return
        decisionStarted = true
        DebugLogger.log("CALL", "Ab bolo: उठाओ ya काट दो")
        listenForDecision(0)
    }

    private fun listenForDecision(attempt: Int) {
        if (!ringing || attempt > 3) return
        val f = File(context.cacheDir, "call_decision.wav")
        audioRecorder.startRecording(f) {
            if (ringing) {
                if (f.length() < 2000) {
                    listenForDecision(attempt + 1)
                } else {
                    val viaServer = {
                        apiClient.transcribe(f) { text ->
                            if (ringing) handleDecision(text ?: "", attempt)
                        }
                    }
                    if (localRecognizer.isReady()) {
                        // Phone ke andar hi suno (turant, server ke bina)
                        localRecognizer.recognize(f) { local ->
                            if (ringing) {
                                if (local.isBlank()) viaServer() else handleDecision(local, attempt)
                            }
                        }
                    } else {
                        viaServer()
                    }
                }
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
        handledByUs = true
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
        handledByUs = true
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
