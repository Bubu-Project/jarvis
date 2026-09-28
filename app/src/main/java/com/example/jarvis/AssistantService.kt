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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
    private lateinit var elevenTTS: ElevenLabsTTS
    private lateinit var audioManager: AudioManager
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var requestQueue: RequestQueue
    private val wakeWordDetector = WakeWordDetector()

    private val LLAMA_API_KEY = BuildConfig.GROQ_API_KEY

    private val handler = Handler(Looper.getMainLooper())

    private var isListeningActive = false
    private var isSpeaking = false
    private var isAwake = false
    private var isConversationMode = false
    private var conversationTimeout: Runnable? = null
    private var isWaitingForCallResponse = false
    private var pendingCallName: String? = null
    private var lastProcessedText = ""

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
            setupElevenTTS()
            setupCallReceiver()
            setupAssemblyAI()
            isListeningActive = true
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_DEBUG", "CRASH in onCreate: ${e.message}")
        }
    }

    // =====================================================
    // ELEVENLABS TTS SETUP
    // =====================================================
    private fun setupElevenTTS() {
        try {
            elevenTTS = ElevenLabsTTS(this)
            elevenTTS.setListener(object : ElevenLabsTTS.TTSListener {
                override fun onStart() {
                    handler.post {
                        isSpeaking = true
                        try { assemblyAISTT.pauseStreaming() } catch (_: Exception) {}
                    }
                }

                override fun onDone() {
                    handler.post {
                        isSpeaking = false
                        lastProcessedText = ""
                        handler.postDelayed({
                            if (!isSpeaking) {
                                try { assemblyAISTT.resumeStreaming() } catch (_: Exception) {}
                            }
                        }, 500)
                    }
                }

                override fun onError(message: String) {
                    android.util.Log.e("JARVIS_TTS", "Error: $message")
                    handler.post {
                        isSpeaking = false
                        lastProcessedText = ""
                        try { assemblyAISTT.resumeStreaming() } catch (_: Exception) {}
                    }
                }
            })
            android.util.Log.d("JARVIS_TTS", "ElevenLabs TTS initialized")
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_TTS", "Setup failed: ${e.message}")
        }
    }

    private fun speak(text: String, id: String) {
        handler.post {
            try { elevenTTS.speak(text) } catch (e: Exception) {
                android.util.Log.e("JARVIS_TTS", "Speak failed: ${e.message}")
            }
        }
    }

    // =====================================================
    // ASSEMBLYAI SETUP
    // =====================================================
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
                clearConversationHistory()
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
                clearConversationHistory()
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
                    clearConversationHistory()
                }
            }
            conversationTimeout = timeout
            handler.postDelayed(timeout, 120000)
        } catch (_: Exception) {}
    }

    private fun clearConversationHistory() {
        try {
            while (conversationHistory.length() > 0) {
                conversationHistory.remove(0)
            }
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
    // GROQ AI
    // =====================================================
    private fun askGroqAI(question: String) {
        try {
            val url = "https://api.groq.com/openai/v1/chat/completions"
            val messages = JSONArray()

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
                        "7. You have MEMORY of this conversation.")
            })

            for (i in 0 until conversationHistory.length()) {
                messages.put(conversationHistory.getJSONObject(i))
            }

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

                        addToHistory("user", question)
                        addToHistory("assistant", aiReply)
                        speak(aiReply, "AI_REPLY")
                    } catch (e: Exception) {
                        speak("Sorry Sir, samajh nahi paya.", "AI_ERROR")
                    }
                },
                { error ->
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

    private fun addToHistory(role: String, content: String) {
        try {
            conversationHistory.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
            while (conversationHistory.length() > 20) {
                conversationHistory.remove(0)
            }
        } catch (_: Exception) {}
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
        clearConversationHistory()
        try { assemblyAISTT.stopListening() } catch (_: Exception) {}
        try { elevenTTS.stop() } catch (_: Exception) {}
        try { unregisterReceiver(CallReceiver.instance) } catch (_: Exception) {}
    }
}
