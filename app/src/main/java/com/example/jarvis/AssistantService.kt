package com.example.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

class AssistantService : Service(), RecognitionListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var audioManager: AudioManager
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var requestQueue: RequestQueue
    private val wakeWordDetector = WakeWordDetector()

    private val LLAMA_API_KEY = BuildConfig.GROQ_API_KEY

    private val handler = Handler(Looper.getMainLooper())

    private var isListeningActive = false
    private var isRecognizerListening = false
    private var isSpeaking = false
    private var isAwake = false
    private var ttsReady = false
    private var wakeHandled = false
    private var recognizerRestartPending = false
    private var pauseListeningUntil: Long = 0L
    private var isConversationMode = false
    private var conversationTimeout: Runnable? = null
    private var isWaitingForCallResponse = false
    private var pendingCallName: String? = null

    private val wakeTimeout = Runnable {
        isAwake = false
        wakeHandled = false
        startListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            android.util.Log.d("JARVIS_DEBUG", "Service onCreate started")

            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            actionExecutor = ActionExecutor(this)
            requestQueue = Volley.newRequestQueue(this)
            createNotification()
            setupTTS()
            setupRecognizer()
            setupCallReceiver()
            isListeningActive = true
            handler.postDelayed({ startListening() }, 3000)

            android.util.Log.d("JARVIS_DEBUG", "Service onCreate completed")
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_DEBUG", "CRASH in onCreate: ${e.message}")
            e.printStackTrace()
        }
    }

    // =====================================================
    // TTS - Girl Voice (GF Jaisi)
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

                    // ChatGPT jaisi clear, sweet, slow voice
                    textToSpeech.setSpeechRate(0.85f)
                    textToSpeech.setPitch(1.10f)

                    // Best Indian female voice select karo
                    try {
                        val voices = textToSpeech.voices
                        if (voices != null) {
                            val femaleVoice = voices
                                .filter { it.locale.language == "en" }
                                .sortedByDescending { v ->
                                    var score = 0
                                    if (v.locale.country == "IN") score += 100
                                    if (v.locale.country == "GB") score += 50
                                    if (v.locale.country == "US") score += 30
                                    if (v.name.contains("female", true)) score += 50
                                    if (v.name.contains("-f-", true)) score += 40
                                    if (v.name.contains("samantha", true)) score += 30
                                    if (v.name.contains("victoria", true)) score += 30
                                    if (v.name.contains("karen", true)) score += 30
                                    if (v.name.contains("moira", true)) score += 30
                                    if (v.name.contains("tessa", true)) score += 30
                                    if (v.name.contains("veena", true)) score += 40
                                    if (v.name.contains("raveena", true)) score += 40
                                    if (v.name.contains("heera", true)) score += 40
                                    if (v.name.contains("priya", true)) score += 40
                                    score
                                }
                                .firstOrNull()

                            if (femaleVoice != null) {
                                textToSpeech.voice = femaleVoice
                                android.util.Log.d("JARVIS_TTS", "Voice: ${femaleVoice.name} (${femaleVoice.locale})")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("JARVIS_TTS", "Voice error: ${e.message}")
                    }

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
    private fun setupTTSListener() {
        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    handler.post {
                        isSpeaking = true
                        try { speechRecognizer.stopListening() } catch (_: Exception) {}
                        isRecognizerListening = false
                    }
                }

                override fun onDone(utteranceId: String?) {
                    handler.post {
                        isSpeaking = false
                        if (isListeningActive && System.currentTimeMillis() >= pauseListeningUntil) {
                            handler.postDelayed({ startListening() }, 1000)
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    handler.post {
                        isSpeaking = false
                        if (isListeningActive && System.currentTimeMillis() >= pauseListeningUntil) {
                            handler.postDelayed({ startListening() }, 1000)
                        }
                    }
                }
            }
        )
    }

    private fun speak(text: String, id: String) {
        if (!ttsReady) return
        handler.post {
            isSpeaking = true
            try { speechRecognizer.stopListening() } catch (_: Exception) {}
            isRecognizerListening = false
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)

            handler.postDelayed({
                if (isSpeaking) {
                    isSpeaking = false
                    if (isListeningActive) startListening()
                }
            }, 7000)
        }
    }

    // =====================================================
    // SPEECH RECOGNIZER
    // =====================================================
    private fun setupRecognizer() {
        try { speechRecognizer.destroy() } catch (_: Exception) {}

        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer.setRecognitionListener(this)
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_DEBUG", "setupRecognizer error: ${e.message}")
            return
        }

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
    }

    private fun startListening() {
        if (System.currentTimeMillis() < pauseListeningUntil) {
            handler.postDelayed({ if (isListeningActive) startListening() }, 5000)
            return
        }

        if (!isListeningActive || isSpeaking || isRecognizerListening) return

        handler.post {
            if (!isListeningActive || isSpeaking || isRecognizerListening) return@post
            try {
                speechRecognizer.cancel()
            } catch (_: Exception) {}

            try {
                wakeHandled = false
                isRecognizerListening = true
                speechRecognizer.startListening(recognizerIntent)
            } catch (e: Exception) {
                isRecognizerListening = false
                android.util.Log.e("JARVIS_DEBUG", "startListening error: ${e.message}")
                restartRecognizer()
            }
        }
    }

    private fun restartRecognizer() {
        if (!isListeningActive || recognizerRestartPending) return
        recognizerRestartPending = true

        handler.postDelayed({
            recognizerRestartPending = false
            if (!isListeningActive || isSpeaking) return@postDelayed
            try { speechRecognizer.destroy() } catch (_: Exception) {}

            try {
                setupRecognizer()
                isRecognizerListening = true
                speechRecognizer.startListening(recognizerIntent)
            } catch (e: Exception) {
                isRecognizerListening = false
                handler.postDelayed({ startListening() }, 1000)
            }
        }, 800)
    }

    override fun onResults(results: Bundle?) {
        isRecognizerListening = false

        val resultsList = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (resultsList.isNullOrEmpty()) {
            handler.postDelayed({ startListening() }, 300)
            return
        }

        val spokenText = resultsList.firstOrNull()?.lowercase(Locale.US)?.trim() ?: ""
        if (spokenText.isBlank()) {
            startListening()
            return
        }

        android.util.Log.d("JARVIS_DEBUG", "FULL RESULT: [$spokenText]")
        wakeHandled = false
        handleSpeech(spokenText)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (!isListeningActive || isSpeaking || isAwake || wakeHandled) return

        val resultsList = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (resultsList.isNullOrEmpty()) return

        val spokenText = resultsList.firstOrNull()?.lowercase(Locale.US)?.trim() ?: ""
        if (!wakeWordDetector.containsWakeWord(spokenText)) return

        wakeHandled = true
        android.util.Log.d("JARVIS_DEBUG", "Wake word in partial")
    }

    // =====================================================
    // SPEECH HANDLER (Conversation Mode)
    // =====================================================
    private fun handleSpeech(text: String) {
        android.util.Log.d("JARVIS_DEBUG", "handleSpeech: [$text]")

        if (isConversationMode) {
            val exitWords = listOf(
                "stop", "band karo", "shut down", "goodbye", "bye",
                "chup", "chup ho jao", "bas", "ruko", "mat suno",
                "conversation band", "exit", "quit", "end conversation"
            )

            val lowerText = text.lowercase().trim()
            if (exitWords.any { lowerText.contains(it) }) {
                isConversationMode = false
                conversationTimeout?.let { handler.removeCallbacks(it) }
                speak("Theek hai Sir, main chup ho jaati hoon.", "CONVERSATION_END")
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
                speak("Haan Sir, boliye. Main sun rahi hoon.", "WAKE_UP")
            } else {
                isConversationMode = true
                resetConversationTimeout()
                executeVoiceCommand(command)
            }
        } else {
            startListening()
        }
    }

    private fun resetConversationTimeout() {
        try {
            conversationTimeout?.let { handler.removeCallbacks(it) }

            val timeout = Runnable {
                if (isConversationMode) {
                    isConversationMode = false
                    android.util.Log.d("JARVIS_CONV", "Timeout - wake word mode")
                }
            }
            conversationTimeout = timeout
            handler.postDelayed(timeout, 60000)
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_CONV", "Timeout error: ${e.message}")
        }
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
            android.util.Log.d("JARVIS_CALL", "CallReceiver registered")
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_CALL", "Receiver setup failed: ${e.message}")
        }
    }

    private fun handleIncomingCall(number: String?) {
        if (number.isNullOrBlank()) return

        val isSpam = actionExecutor.isSpamNumber(number)
        if (isSpam) {
            android.util.Log.d("JARVIS_CALL", "Spam: $number")
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
                android.util.Log.d("JARVIS_CALL", "Call response timeout")
            }
        }, 15000)
    }

    // =====================================================
    // COMMAND EXECUTOR
    // =====================================================
    private fun executeVoiceCommand(command: String) {
        val cmd = command.lowercase(Locale.US).trim()
        android.util.Log.d("JARVIS_CMD", "COMMAND: [$cmd]")

        when {
            // CALL ANSWER
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

            // CALL REJECT
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

            // FLASHLIGHT
            cmd.contains("flashlight") || cmd.contains("flash light") || cmd.contains("torch") -> {
                if (cmd.contains("off") || cmd.contains("band") || cmd.contains("bujha")) {
                    actionExecutor.toggleFlashlight(false)
                    speak("Flashlight off, Sir.", "FLASH_OFF")
                } else {
                    actionExecutor.toggleFlashlight(true)
                    speak("Flashlight on, Sir.", "FLASH_ON")
                }
            }

            // TIME
            cmd.contains("time") || cmd.contains("samay") -> {
                speak("Sir, abhi ${actionExecutor.getCurrentTime()} ho raha hai.", "TIME")
            }

            // DATE
            cmd.contains("date") || cmd.contains("today") || cmd.contains("tarikh") -> {
                speak("Sir, aaj ${actionExecutor.getCurrentDate()} hai.", "DATE")
            }

            // BATTERY
            cmd.contains("battery") || cmd.contains("charge") -> {
                speak("Sir, battery ${actionExecutor.getBatteryLevel()} percent hai.", "BATTERY")
            }

            // CALL
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
                pauseListeningUntil = System.currentTimeMillis() + 30000
            }

            // YOUTUBE
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
                pauseListeningUntil = System.currentTimeMillis() + 120000
            }

            // OPEN APP
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

            // AI
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

            val requestBody = JSONObject().apply {
                put("model", "openai/gpt-oss-20b")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are Jarvis, a smart, witty and friendly AI assistant for an Indian user. " +
                                "RULES: " +
                                "1. Always reply in HINGLISH (Hindi + English mixed) using ROMAN script only. " +
                                "2. Address the user as 'Sir' always. " +
                                "3. Keep replies SHORT - max 2-3 sentences. " +
                                "4. Be warm, sweet, and helpful like a close friend. " +
                                "5. NEVER use Devanagari script - only Roman letters. " +
                                "6. No markdown, no bullet points - just plain speech. " +
                                "7. If user asks to teach English, be a patient teacher.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", question)
                    })
                })
                put("temperature", 0.7)
                put("max_tokens", 200)
            }

            val request = object : JsonObjectRequest(
                Request.Method.POST,
                url,
                requestBody,
                { response ->
                    try {
                        val aiReply = response
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()

                        android.util.Log.d("JARVIS_AI", "AI: $aiReply")
                        speak(aiReply, "AI_REPLY")
                    } catch (e: Exception) {
                        android.util.Log.e("JARVIS_AI", "Parse: ${e.message}")
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
            android.util.Log.e("JARVIS_AI", "askGroqAI error: ${e.message}")
            speak("Sorry Sir, kuch problem hai.", "AI_ERROR")
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
            .build()

        startForeground(1, notification)
    }

    // =====================================================
    // RECOGNITION LISTENER
    // =====================================================
    override fun onError(error: Int) {
        isRecognizerListening = false
        android.util.Log.e("JARVIS_DEBUG", "Speech Error: $error")
        if (isListeningActive && !isSpeaking) {
            val delayMs = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> 300L
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                else -> 500L
            }
            handler.postDelayed({ startListening() }, delayMs)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        super.onDestroy()
        isListeningActive = false
        conversationTimeout?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        try { speechRecognizer.destroy() } catch (_: Exception) {}
        try { textToSpeech.stop() } catch (_: Exception) {}
        try { textToSpeech.shutdown() } catch (_: Exception) {}
        try { unregisterReceiver(CallReceiver.instance) } catch (_: Exception) {}
        android.util.Log.d("JARVIS_DEBUG", "Service destroyed")
    }
}
