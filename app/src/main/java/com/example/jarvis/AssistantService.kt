package com.example.jarvis

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.android.volley.AuthFailureError
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class AssistantService : Service() {

    private lateinit var assemblyAISTT: AssemblyAISTT
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var audioManager: AudioManager
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var requestQueue: RequestQueue
    private val wakeWordDetector = WakeWordDetector()

    private val LLAMA_API_KEY = BuildConfig.GROQ_API_KEY

    private val handler = Handler(Looper.getMainLooper())

    private var isListeningActive = false
    private var isSpeaking = false
    private var isAwake = false
    private var ttsReady = false
    private var isConversationMode = false
    private var conversationTimeout: Runnable? = null
    private var isWaitingForCallResponse = false
    private var pendingCallName: String? = null
    private var lastProcessedText = ""

    // ✅ NAYA: Conversation Memory
    private val conversationHistory = JSONArray()

    private val wakeTimeout = Runnable {
        isAwake = false
        isConversationMode = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val restartService = Intent(applicationContext, this.javaClass)
            restartService.setPackage(packageName)
            val restartPendingIntent = PendingIntent.getService(
                applicationContext, 1, restartService,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartPendingIntent
            )
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            actionExecutor = ActionExecutor(this)
            requestQueue = Volley.newRequestQueue(this)
            createNotification()
            setupTTS()
            setupCallReceiver()
            setupAssemblyAI()
            isListeningActive = true
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_DEBUG", "CRASH in onCreate: ${e.message}")
        }
    }

    private fun setupAssemblyAI() {
        try {
            assemblyAISTT = AssemblyAISTT(this)
            assemblyAISTT.setListener(object : AssemblyAISTT.TranscriptionListener {
                override fun onTranscript(text: String, isFinal: Boolean) {
                    handler.post { handleTranscript(text, isFinal) }
                }
                override fun onError(message: String) {
                    handler.post {
                        handler.postDelayed({
                            if (isListeningActive) setupAssemblyAI()
                        }, 3000)
                    }
                }
            })
            assemblyAISTT.startListening()
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_STT", "Setup failed: ${e.message}")
        }
    }

    private fun handleTranscript(text: String, isFinal: Boolean) {
        val cleanText = text.lowercase(Locale.US).trim()
        if (cleanText.isBlank()) return
        if (isSpeaking) return
        if (cleanText == lastProcessedText && isFinal) return
        if (!isFinal) return

        lastProcessedText = cleanText
        handleSpeech(cleanText)
    }

    // =====================================================
    // TTS - Indian Voice Priority
    // =====================================================
    private fun setupTTS() {
        try {
            textToSpeech = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        var result = textToSpeech.setLanguage(Locale("en", "IN"))
                        if (result == TextToSpeech.LANG_MISSING_DATA ||
                            result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            result = textToSpeech.setLanguage(Locale.UK)
                        }
                        if (result == TextToSpeech.LANG_MISSING_DATA ||
                            result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            result = textToSpeech.setLanguage(Locale.US)
                        }

                        ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                                result != TextToSpeech.LANG_NOT_SUPPORTED

                        // Natural sounding settings
                        textToSpeech.setSpeechRate(0.85f)
                        textToSpeech.setPitch(1.05f)

                        logAllVoices()
                        selectBestIndianVoice()
                        setupTTSListener()
                    } catch (e: Exception) {
                        android.util.Log.e("JARVIS_TTS", "TTS setup error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_TTS", "TTS init error: ${e.message}")
        }
    }

    // ✅ NAYA: Saari available voices log karo (debug ke liye)
    private fun logAllVoices() {
        try {
            val voices = textToSpeech.voices ?: return
            android.util.Log.d("JARVIS_TTS", "=== AVAILABLE VOICES ===")
            for (voice in voices) {
                if (voice.locale.language == "en") {
                    android.util.Log.d(
                        "JARVIS_TTS",
                        "Voice: ${voice.name} | Locale: ${voice.locale} | Quality: ${voice.quality}"
                    )
                }
            }
            android.util.Log.d("JARVIS_TTS", "=== END VOICES ===")
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_TTS", "Log voices error: ${e.message}")
        }
    }

    // ✅ NAYA: Best Indian voice (female ya male dono)
    private fun selectBestIndianVoice() {
        try {
            val voices = textToSpeech.voices ?: return

            // Indian names (female + male)
            val indianNames = listOf(
                "veena", "raveena", "heera", "priya", "aditi", "kavya",
                "rishi", "ravi", "arjun", "vikram", "hindi", "india"
            )

            // Female names (agar Indian nahi mili toh)
            val femaleNames = listOf(
                "female", "-f-", "samantha", "victoria", "karen",
                "moira", "tessa", "fiona", "susan", "allison", "ava",
                "amelie", "joanna", "salli", "kendra", "kimberly"
            )

            val maleNames = listOf("male", "-m-", "daniel", "alex", "fred", "oliver", "thomas")

            var bestVoice: android.speech.tts.Voice? = null
            var bestScore = -1

            for (voice in voices) {
                if (voice.locale.language != "en") continue

                var score = 0

                // Indian locale priority
                if (voice.locale.country == "IN") score += 200
                if (voice.locale.country == "GB") score += 50
                if (voice.locale.country == "US") score += 30

                // Indian name priority
                if (indianNames.any { voice.name.contains(it, true) }) score += 150

                // Female preference (light)
                if (femaleNames.any { voice.name.contains(it, true) }) score += 30

                // Male penalty (kam)
                if (maleNames.any { voice.name.contains(it, true) }) score -= 10

                // High quality bonus
                if (voice.quality >= android.speech.tts.Voice.QUALITY_HIGH) score += 40
                if (voice.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH) score += 80

                if (score > bestScore) {
                    bestScore = score
                    bestVoice = voice
                }
            }

            if (bestVoice != null) {
                textToSpeech.voice = bestVoice
                android.util.Log.d(
                    "JARVIS_TTS",
                    "SELECTED: ${bestVoice.name} | Locale: ${bestVoice.locale} | Score: $bestScore"
                )
            } else {
                android.util.Log.e("JARVIS_TTS", "No suitable voice found")
            }
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_TTS", "Voice selection error: ${e.message}")
        }
    }

    private fun setupTTSListener() {
        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    handler.post { isSpeaking = true }
                }

                override fun onDone(utteranceId: String?) {
                    handler.post {
                        isSpeaking = false
                        lastProcessedText = ""
                        handler.postDelayed({
                            if (!isSpeaking) {
                                try { assemblyAISTT.resumeStreaming() } catch (_: Exception) {}
                            }
                        }, 800)
                    }
                }

                override fun onError(utteranceId: String?) {
                    handler.post {
                        isSpeaking = false
                        lastProcessedText = ""
                        handler.postDelayed({
                            if (!isSpeaking) {
                                try { assemblyAISTT.resumeStreaming() } catch (_: Exception) {}
                            }
                        }, 800)
                    }
                }
            }
        )
    }

    private fun speak(text: String, id: String) {
        if (!ttsReady) return
        handler.post {
            isSpeaking = true
            try { assemblyAISTT.pauseStreaming() } catch (_: Exception) {}

            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_VOLUME, 100)
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)

            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        }
    }

    // =====================================================
    // SPEECH HANDLER
    // =====================================================
    private fun handleSpeech(text: String) {
        if (isConversationMode) {
            val lowerText = text.lowercase().trim()
            val exitWords = listOf(
                "stop", "band karo", "shut down", "goodbye", "bye",
                "chup", "chup ho jao", "bas", "ruko", "mat suno",
                "conversation band", "exit", "quit", "end conversation",
                "naya topic", "reset"
            )
            if (exitWords.any { lowerText.contains(it) }) {
                isConversationMode = false
                conversationTimeout?.let { handler.removeCallbacks(it) }
                conversationHistory.clear() // ✅ Memory clear
                speak("Theek hai Sir, main chup ho jaati hoon.", "CONVERSATION_END")
                return
            }

            val words = lowerText.split(" ").filter { it.isNotBlank() }
            val actionKeywords = listOf(
                "flashlight", "torch", "call", "play", "youtube", "open", "kholo",
                "time", "date", "battery", "weather", "mausam", "kya", "kaise",
                "kaun", "kahan", "batao", "sikhao", "samjhao", "capital", "rajdhani"
            )
            val hasKeyword = actionKeywords.any { lowerText.contains(it) }

            if (words.size < 2 && !hasKeyword) {
                resetConversationTimeout()
                return
            }

            resetConversationTimeout()
            executeVoiceCommand(text)
            return
        }

        if (isAwake) {
            isAwake = false
            handler.removeCallbacks(wakeTimeout)
            isConversationMode = true
            resetConversationTimeout()
            executeVoiceCommand(text)
            return
        }

        if (wakeWordDetector.containsWakeWord(text)) {
            val command = wakeWordDetector.removeWakeWord(text)
            if (command.isBlank()) {
                isConversationMode = true
                isAwake = true
                handler.removeCallbacks(wakeTimeout)
                handler.postDelayed(wakeTimeout, 10000)
                resetConversationTimeout()
                conversationHistory.clear() // ✅ Naya session
                speak("Haan Sir, boliye. Main sun rahi hoon.", "WAKE_UP")
            } else {
                isConversationMode = true
                resetConversationTimeout()
                executeVoiceCommand(command)
            }
        }
    }

    private fun resetConversationTimeout() {
        try {
            conversationTimeout?.let { handler.removeCallbacks(it) }

            val timeout = Runnable {
                if (isConversationMode) {
                    isConversationMode = false
                    conversationHistory.clear() // ✅ Timeout pe memory clear
                    android.util.Log.d("JARVIS_CONV", "Timeout - memory cleared")
                }
            }
            conversationTimeout = timeout
            handler.postDelayed(timeout, 120000) // 2 minute
        } catch (_: Exception) {}
    }

    // =====================================================
    // CALL HANDLING
    // =====================================================
    private fun setupCallReceiver() {
        try {
            val receiver = CallReceiver()
            CallReceiver.instance = receiver

            receiver.setListener { state, number ->
                handler.post {
                    when (state) {
                        android.telephony.TelephonyManager.CALL_STATE_RINGING -> {
                            handleIncomingCall(number)
                        }
                        android.telephony.TelephonyManager.CALL_STATE_IDLE -> {
                            if (isWaitingForCallResponse) {
                                isWaitingForCallResponse = false
                                pendingCallName = null
                            }
                        }
                    }
                }
            }

            val filter = android.content.IntentFilter(
                android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED
            )
            registerReceiver(receiver, filter)
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_CALL", "Receiver setup failed: ${e.message}")
        }
    }

    private fun handleIncomingCall(number: String?) {
        if (number.isNullOrBlank()) return

        val isSpam = actionExecutor.isSpamNumber(number)
        if (isSpam) {
            speak("Sir, spam call aa raha hai. Reject kar rahi hoon.", "SPAM_CALL")
            handler.postDelayed({ actionExecutor.rejectCall() }, 3500)
            return
        }

        val contactName = actionExecutor.getContactName(number)
        pendingCallName = contactName
        isWaitingForCallResponse = true

        val announcement = if (contactName != null) {
            "Sir, $contactName ka call aa raha hai. Uthau?"
        } else {
            "Sir, ek unknown number se call aa raha hai. Uthau?"
        }

        speak(announcement, "INCOMING_CALL")

        handler.postDelayed({
            if (isWaitingForCallResponse) {
                isWaitingForCallResponse = false
                pendingCallName = null
            }
        }, 15000)
    }

    // =====================================================
    // COMMAND EXECUTOR
    // =====================================================
    private fun executeVoiceCommand(command: String) {
        val cmd = command.lowercase(Locale.US).trim()

        when {
            isWaitingForCallResponse && (
                cmd.contains("haan") || cmd.contains("yes") || cmd.contains("uthao") ||
                cmd.contains("utha") || cmd.contains("answer") || cmd.contains("pick") ||
                cmd.contains("lo") || cmd.contains("attend") || cmd.contains("le lo")
            ) -> {
                isWaitingForCallResponse = false
                actionExecutor.answerCall()
                speak("Call utha rahi hoon, Sir.", "CALL_ANSWERED")
                pendingCallName = null
            }

            isWaitingForCallResponse && (
                cmd.contains("nahi") || cmd.contains("no") || cmd.contains("reject") ||
                cmd.contains("kato") || cmd.contains("kat") || cmd.contains("cut") ||
                cmd.contains("band") || cmd.contains("mat") || cmd.contains("chhod")
            ) -> {
                isWaitingForCallResponse = false
                actionExecutor.rejectCall()
                speak("Call reject kar diya, Sir.", "CALL_REJECTED")
                pendingCallName = null
            }

            cmd.contains("flashlight") || cmd.contains("flash light") || cmd.contains("torch") -> {
                if (cmd.contains("off") || cmd.contains("band") || cmd.contains("bujha")) {
                    actionExecutor.toggleFlashlight(false)
                    speak("Flashlight off, Sir.", "FLASH_OFF")
                } else {
                    actionExecutor.toggleFlashlight(true)
                    speak("Flashlight on, Sir.", "FLASH_ON")
                }
            }

            cmd.contains("time") || cmd.contains("samay") -> {
                speak("Sir, abhi ${actionExecutor.getCurrentTime()} ho raha hai.", "TIME")
            }

            cmd.contains("date") || cmd.contains("today") || cmd.contains("tarikh") -> {
                speak("Sir, aaj ${actionExecutor.getCurrentDate()} hai.", "DATE")
            }

            cmd.contains("battery") || cmd.contains("charge") -> {
                speak("Sir, battery ${actionExecutor.getBatteryLevel()} percent hai.", "BATTERY")
            }

            cmd.contains("call") || cmd.contains("phone karo") || cmd.contains("dial") -> {
                val contactName = cmd
                    .replace("call", "").replace("karo", "")
                    .replace("phone", "").replace("dial", "")
                    .replace("please", "").replace("to", "").trim()

                if (contactName.isNotBlank()) {
                    val success = actionExecutor.callContact(contactName)
                    if (success) speak("Calling $contactName, Sir.", "CALL")
                    else speak("Sir, $contactName nahi mila.", "CALL_ERROR")
                } else {
                    speak("Sir, kisko call karna hai?", "CALL_EMPTY")
                }
            }

            cmd.contains("youtube") || cmd.contains("play") || cmd.contains("gana") || cmd.contains("song") -> {
                val query = cmd
                    .replace("play", "").replace("on youtube", "")
                    .replace("youtube", "").replace("song", "")
                    .replace("gana", "").replace("chalao", "")
                    .replace("please", "").trim()

                if (query.isNotBlank()) {
                    actionExecutor.playOnYoutube(query)
                    speak("Playing $query, Sir.", "YOUTUBE")
                } else {
                    speak("Sir, kya play karna hai?", "YOUTUBE_EMPTY")
                }
            }

            cmd.startsWith("open ") || cmd.contains("kholo") || cmd.contains("launch") -> {
                val appName = cmd
                    .replace("open", "").replace("kholo", "")
                    .replace("launch", "").replace("please", "").trim()

                if (appName.isNotBlank()) {
                    val success = actionExecutor.openApp(appName)
                    if (success) speak("Opening $appName, Sir.", "OPEN_APP")
                    else speak("Sir, $appName nahi mili.", "APP_ERROR")
                } else {
                    speak("Sir, kaunsi app kholni hai?", "APP_EMPTY")
                }
            }

            else -> {
                askGroqAI(cmd)
            }
        }
    }

    // =====================================================
    // GROQ AI - WITH CONVERSATION MEMORY
    // =====================================================
    private fun askGroqAI(question: String) {
        try {
            val url = "https://api.groq.com/openai/v1/chat/completions"

            val messages = JSONArray()

            // System prompt
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", "You are Jarvis, a smart, witty and friendly AI assistant for an Indian user. " +
                        "RULES: " +
                        "1. Always reply in HINGLISH (Hindi + English mixed) using ROMAN script only. " +
                        "2. Address the user as 'Sir' always. " +
                        "3. Keep replies SHORT - max 2-3 sentences. " +
                        "4. Be warm, sweet, and helpful like a close friend. " +
                        "5. NEVER use Devanagari script - only Roman letters. " +
                        "6. No markdown, no bullet points - just plain speech. " +
                        "7. You have MEMORY of this conversation. Refer to previous messages when relevant. " +
                        "8. If user asks to teach English, be a patient teacher.")
            })

            // ✅ Conversation history add karo
            for (i in 0 until conversationHistory.length()) {
                messages.put(conversationHistory.getJSONObject(i))
            }

            // Current question
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", question)
            })

            val requestBody = JSONObject().apply {
                put("model", "openai/gpt-oss-20b")
                put("messages", messages)
                put("temperature", 0.7)
                put("max_tokens", 250)
            }

            val request = object : JsonObjectRequest(
                Request.Method.POST, url, requestBody,
                { response ->
                    try {
                        val aiReply = response
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()

                        android.util.Log.d("JARVIS_AI", "User: $question")
                        android.util.Log.d("JARVIS_AI", "AI: $aiReply")

                        // ✅ Memory mein add karo
                        addToHistory("user", question)
                        addToHistory("assistant", aiReply)

                        speak(aiReply, "AI_REPLY")
                    } catch (e: Exception) {
                        speak("Sorry Sir, samajh nahi paya.", "AI_ERROR")
                    }
                },
                { error ->
                    val errMsg = when {
                        error.networkResponse != null -> "HTTP ${error.networkResponse.statusCode}"
                        else -> error.message ?: "Unknown"
                    }
                    android.util.Log.e("JARVIS_AI", "API ERROR: $errMsg")
                    speak("Sorry Sir, connect nahi ho paya.", "AI_NETWORK_ERROR")
                }
            ) {
                @Throws(AuthFailureError::class)
                override fun getHeaders(): MutableMap<String, String> {
                    val headers = HashMap<String, String>()
                    headers["Authorization"] = "Bearer $LLAMA_API_KEY"
                    headers["Content-Type"] = "application/json"
                    return headers
                }
            }
            requestQueue.add(request)
        } catch (e: Exception) {
            speak("Sorry Sir, kuch problem hai.", "AI_ERROR")
        }
    }

    // ✅ NAYA: History add karne ka function
    private fun addToHistory(role: String, content: String) {
        try {
            conversationHistory.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })

            // Sirf last 20 messages rakho (10 pairs) - token limit ke liye
            while (conversationHistory.length() > 20) {
                conversationHistory.remove(0)
            }

            android.util.Log.d("JARVIS_MEM", "History size: ${conversationHistory.length()}")
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_MEM", "Add error: ${e.message}")
        }
    }

    // =====================================================
    // NOTIFICATION
    // =====================================================
    private fun createNotification() {
        val channelId = "jarvis_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Jarvis Service", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis AI")
            .setContentText("Listening for 'Jarvis'...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    // =====================================================
    // DESTROY
    // =====================================================
    override fun onDestroy() {
        super.onDestroy()
        isListeningActive = false
        conversationTimeout?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        conversationHistory.clear()
        try { assemblyAISTT.stopListening() } catch (_: Exception) {}
        try { textToSpeech.stop() } catch (_: Exception) {}
        try { textToSpeech.shutdown() } catch (_: Exception) {}
        try { unregisterReceiver(CallReceiver.instance) } catch (_: Exception) {}
    }
}
