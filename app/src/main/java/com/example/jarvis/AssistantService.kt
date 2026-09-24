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

    // =====================================================
    // GROQ API KEY
    // =====================================================
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

    private val wakeTimeout = Runnable {
        isAwake = false
        wakeHandled = false
        startListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =====================================================
    // CREATE SERVICE
    // =====================================================
    override fun onCreate() {
        super.onCreate()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            android.util.Log.e("JARVIS_DEBUG", "Speech recognition is NOT available")
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        actionExecutor = ActionExecutor(this)
        requestQueue = Volley.newRequestQueue(this)
        createNotification()
        setupTTS()
        setupRecognizer()
        isListeningActive = true
        handler.postDelayed({ startListening() }, 3000)
    }

    // =====================================================
    // TTS - Hinglish Friendly (en-IN)
    // =====================================================
    private fun setupTTS() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech.setLanguage(Locale("en", "IN"))
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                textToSpeech.setSpeechRate(1.0f)
                textToSpeech.setPitch(1.0f)
                setupTTSListener()
            }
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
                        android.util.Log.d("JARVIS_DEBUG", "TTS DONE - restarting listen")
                        isSpeaking = false
                        if (isListeningActive) {
                            handler.postDelayed({ startListening() }, 1200)
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    handler.post {
                        isSpeaking = false
                        if (isListeningActive) {
                            handler.postDelayed({ startListening() }, 1200)
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

            // Safety net
            handler.postDelayed({
                if (isSpeaking) {
                    android.util.Log.d("JARVIS_DEBUG", "TTS SAFETY NET triggered")
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

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer.setRecognitionListener(this)
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_DEBUG", "CRASH in setupRecognizer: ${e.message}")
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

    // =====================================================
    // START LISTENING
    // =====================================================
    private fun startListening() {
        // Agar media cooldown chal raha hai, toh mic on mat karo
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
                android.util.Log.e("JARVIS_DEBUG", "CRASH in startListening: ${e.message}")
                restartRecognizer()
            }
        }
    }

    // =====================================================
    // RESTART SPEECH RECOGNIZER
    // =====================================================
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
                android.util.Log.e("JARVIS_DEBUG", "CRASH in restartRecognizer: ${e.message}")
                handler.postDelayed({ startListening() }, 1000)
            }
        }, 800)
    }

    // =====================================================
    // FINAL RESULT
    // =====================================================
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

    // =====================================================
    // PARTIAL RESULT - Sirf mark karo
    // =====================================================
    override fun onPartialResults(partialResults: Bundle?) {
        if (!isListeningActive || isSpeaking || isAwake || wakeHandled) return

        val resultsList = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (resultsList.isNullOrEmpty()) return

        val spokenText = resultsList.firstOrNull()?.lowercase(Locale.US)?.trim() ?: ""
        if (!wakeWordDetector.containsWakeWord(spokenText)) return

        wakeHandled = true
        android.util.Log.d("JARVIS_DEBUG", "Wake word spotted in partial")
    }

    // =====================================================
    // SPEECH HANDLER
    // =====================================================
    private fun handleSpeech(text: String) {
        android.util.Log.d("JARVIS_DEBUG", "handleSpeech: [$text]")

        if (isAwake) {
            isAwake = false
            handler.removeCallbacks(wakeTimeout)
            executeVoiceCommand(text)
            return
        }

        if (wakeWordDetector.containsWakeWord(text)) {
            val command = wakeWordDetector.removeWakeWord(text)
            if (command.isBlank()) {
                isAwake = true
                handler.removeCallbacks(wakeTimeout)
                handler.postDelayed(wakeTimeout, 8000)
                speak("Yes Sir", "WAKE_UP")
            } else {
                executeVoiceCommand(command)
            }
        } else {
            startListening()
        }
    }

    // =====================================================
    // COMMAND EXECUTOR
    // =====================================================
    private fun executeVoiceCommand(command: String) {
        val cmd = command.lowercase(Locale.US).trim()
        android.util.Log.d("JARVIS_CMD", "COMMAND RECEIVED: [$cmd]")

        when {
            // ============ FLASHLIGHT ============
            cmd.contains("flashlight") || cmd.contains("flash light") ||
            cmd.contains("torch") -> {

                if (cmd.contains("off") || cmd.contains("band") || cmd.contains("bujha") ||
                    cmd.contains("turn off") || cmd.contains("switch off")) {
                    actionExecutor.toggleFlashlight(false)
                    speak("Flashlight turned off, Sir.", "FLASH_OFF")
                } else if (cmd.contains("on") || cmd.contains("chalu") || cmd.contains("jala") ||
                    cmd.contains("turn on") || cmd.contains("switch on")) {
                    actionExecutor.toggleFlashlight(true)
                    speak("Flashlight turned on, Sir.", "FLASH_ON")
                } else {
                    actionExecutor.toggleFlashlight(true)
                    speak("Toggling flashlight, Sir.", "FLASH_TOGGLE")
                }
            }

            // ============ TIME ============
            cmd.contains("time") || cmd.contains("samay") -> {
                val time = actionExecutor.getCurrentTime()
                speak("Sir, abhi $time ho raha hai.", "TIME")
            }

            // ============ DATE ============
            cmd.contains("date") || cmd.contains("today") || cmd.contains("tarikh") -> {
                val date = actionExecutor.getCurrentDate()
                speak("Sir, aaj $date hai.", "DATE")
            }

            // ============ BATTERY ============
            cmd.contains("battery") || cmd.contains("charge") -> {
                val level = actionExecutor.getBatteryLevel()
                speak("Sir, aapki battery $level percent hai.", "BATTERY")
            }

            // ============ CALL ============
            cmd.contains("call") || cmd.contains("phone karo") || cmd.contains("dial") -> {
                val contactName = cmd
                    .replace("call", "")
                    .replace("karo", "")
                    .replace("phone", "")
                    .replace("dial", "")
                    .replace("please", "")
                    .replace("to", "")
                    .trim()

                if (contactName.isNotBlank()) {
                    val success = actionExecutor.callContact(contactName)
                    if (success) {
                        speak("Calling $contactName, Sir.", "CALL")
                    } else {
                        speak("Sir, $contactName naam ka contact nahi mila.", "CALL_ERROR")
                    }
                } else {
                    speak("Sir, kisko call karna hai?", "CALL_EMPTY")
                }
                pauseListeningUntil = System.currentTimeMillis() + 30000
            }

            // ============ YOUTUBE ============
            cmd.contains("youtube") || cmd.contains("play") || cmd.contains("gana") || cmd.contains("song") -> {
                val query = cmd
                    .replace("play", "")
                    .replace("on youtube", "")
                    .replace("youtube", "")
                    .replace("song", "")
                    .replace("gana", "")
                    .replace("chalao", "")
                    .replace("please", "")
                    .trim()
                if (query.isNotBlank()) {
                    actionExecutor.playOnYoutube(query)
                    speak("Playing $query on YouTube, Sir.", "YOUTUBE")
                } else {
                    speak("Sir, kya play karna hai?", "YOUTUBE_EMPTY")
                }
                pauseListeningUntil = System.currentTimeMillis() + 120000
            }

            // ============ OPEN APP ============
            cmd.startsWith("open ") || cmd.contains("kholo") || cmd.contains("launch") -> {
                val appName = cmd
                    .replace("open", "")
                    .replace("kholo", "")
                    .replace("launch", "")
                    .replace("please", "")
                    .trim()
                val success = actionExecutor.openApp(appName)
                if (success) {
                    speak("Opening $appName, Sir.", "OPEN_APP")
                } else {
                    speak("Sir, $appName app nahi mili.", "APP_ERROR")
                }
            }

            // ============ UNKNOWN - AI SE POOCHO ============
            else -> {
                android.util.Log.d("JARVIS_CMD", "Sending to AI: [$cmd]")
                askGroqAI(cmd)
            }
        }
    }

    // =====================================================
    // GROQ AI - HINGLISH MODE
    // =====================================================
    private fun askGroqAI(question: String) {
        val url = "https://api.groq.com/openai/v1/chat/completions"

        val requestBody = JSONObject().apply {
            put("model", "openai/gpt-oss-20b")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are Jarvis, a smart and friendly AI assistant for an Indian user. " +
                            "RULES: " +
                            "1. Always reply in HINGLISH (Hindi + English mixed) using ROMAN script only. " +
                            "2. Example: 'Sir, aapka din bahut accha jayega. Kya main aapki koi aur madad kar sakta hoon?' " +
                            "3. Address the user as 'Sir' always. " +
                            "4. Keep replies SHORT - max 2-3 sentences. " +
                            "5. Be witty, warm, and helpful like a real friend. " +
                            "6. NEVER use Devanagari (हिंदी) script - only Roman letters. " +
                            "7. If user speaks English, reply mostly in English. If Hindi, reply in Hinglish. " +
                            "8. No markdown, no bullet points, no special symbols - just plain speech.")
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

                    android.util.Log.d("JARVIS_AI", "AI Reply: $aiReply")
                    speak(aiReply, "AI_REPLY")

                } catch (e: Exception) {
                    android.util.Log.e("JARVIS_AI", "Parse error: ${e.message}")
                    speak("Sorry Sir, samajh nahi paya.", "AI_ERROR")
                }
            },
            { error ->
                val errMsg = when {
                    error.networkResponse != null -> "HTTP ${error.networkResponse.statusCode}"
                    else -> error.message ?: "Unknown"
                }
                android.util.Log.e("JARVIS_AI", "API ERROR: $errMsg")
                speak("Sir, error code $errMsg", "AI_NETWORK_ERROR")
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
        android.util.Log.e("JARVIS_DEBUG", "Speech Error Code: $error")
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

    // =====================================================
    // DESTROY
    // =====================================================
    override fun onDestroy() {
        super.onDestroy()
        isListeningActive = false
        handler.removeCallbacksAndMessages(null)
        try { speechRecognizer.destroy() } catch (_: Exception) {}
        try { textToSpeech.stop() } catch (_: Exception) {}
        try { textToSpeech.shutdown() } catch (_: Exception) {}
        android.util.Log.d("JARVIS_DEBUG", "Service Destroyed - Mic turned off")
    }
}
