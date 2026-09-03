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

    // API KEY YAHAN APNI EXISTING GROQ KEY PASTE KARNA 👇
    private val LLAMA_API_KEY = "PASTE_YOUR_EXISTING_GROQ_KEY_HERE"

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var requestQueue: com.android.volley.RequestQueue

    private var isAwake = false
    private var isListeningActive = false
    private var isRecognizerListening = false
    private var isSpeaking = false
    private var ttsReady = false

    private val wakeTimeoutRunnable = Runnable {
        if (isAwake) {
            isAwake = false
            startListening()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        audioManager =
            getSystemService(Context.AUDIO_SERVICE) as AudioManager

        actionExecutor = ActionExecutor(this)

        requestQueue = Volley.newRequestQueue(this)

        startForegroundServiceNotification()

        setupTextToSpeech()
        setupRecognizer()
        setupAudioFocus()

        isListeningActive = true

        handler.postDelayed({
            startListening()
        }, 800)
    }

    // =========================================================
    // TEXT TO SPEECH
    // =========================================================

    private fun setupTextToSpeech() {

        textToSpeech = TextToSpeech(this) { status ->

            if (status == TextToSpeech.SUCCESS) {

                val result =
                    textToSpeech.setLanguage(Locale.US)

                ttsReady =
                    result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED

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

                        try {
                            if (isRecognizerListening) {
                                speechRecognizer.stopListening()
                            }
                        } catch (_: Exception) {
                        }

                        isRecognizerListening = false
                    }
                }

                override fun onDone(utteranceId: String?) {

                    handler.post {

                        isSpeaking = false

                        if (isListeningActive) {
                            handler.postDelayed({
                                startListening()
                            }, 250)
                        }
                    }
                }

                override fun onError(utteranceId: String?) {

                    handler.post {

                        isSpeaking = false

                        if (isListeningActive) {
                            handler.postDelayed({
                                startListening()
                            }, 250)
                        }
                    }
                }
            }
        )
    }

    private fun speak(text: String, utteranceId: String) {

        if (!ttsReady) {
            startListening()
            return
        }

        handler.post {

            isSpeaking = true

            try {
                if (isRecognizerListening) {
                    speechRecognizer.stopListening()
                }
            } catch (_: Exception) {
            }

            isRecognizerListening = false

            textToSpeech.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )
        }
    }

    // =========================================================
    // SPEECH RECOGNIZER
    // =========================================================

    private fun setupRecognizer() {

        speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer.setRecognitionListener(this)

        recognizerIntent =
            Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                // Better for Indian English
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "en-IN"
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    "en-IN"
                )

                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    3
                )

                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true
                )

                putExtra(
                    RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    packageName
                )
            }
    }

    private fun startListening() {

        if (!isListeningActive) return

        if (isSpeaking) return

        if (isRecognizerListening) return

        handler.post {

            if (!isListeningActive) return@post
            if (isSpeaking) return@post
            if (isRecognizerListening) return@post

            try {

                isRecognizerListening = true

                speechRecognizer.startListening(
                    recognizerIntent
                )

            } catch (e: Exception) {

                isRecognizerListening = false

                restartRecognizer()
            }
        }
    }

    private fun restartRecognizer() {

        if (!isListeningActive) return

        handler.postDelayed({

            if (!isListeningActive) return@postDelayed
            if (isSpeaking) return@postDelayed

            try {

                speechRecognizer.destroy()

            } catch (_: Exception) {
            }

            try {

                setupRecognizer()

                isRecognizerListening = true

                speechRecognizer.startListening(
                    recognizerIntent
                )

            } catch (_: Exception) {

                isRecognizerListening = false

                handler.postDelayed({
                    startListening()
                }, 1000)
            }

        }, 700)
    }

    // =========================================================
    // AUDIO FOCUS
    // =========================================================

    private fun setupAudioFocus() {

        audioManager.requestAudioFocus(
            { focusChange ->

                when (focusChange) {

                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {

                        isListeningActive = false

                        try {
                            speechRecognizer.stopListening()
                        } catch (_: Exception) {
                        }

                        isRecognizerListening = false
                    }

                    AudioManager.AUDIOFOCUS_GAIN -> {

                        isListeningActive = true

                        handler.postDelayed({
                            startListening()
                        }, 500)
                    }
                }
            },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
    }

    // =========================================================
    // WAKE WORD + COMMAND
    // =========================================================

    override fun onResults(results: Bundle?) {

        isRecognizerListening = false

        val matches =
            results?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
            )

        if (matches.isNullOrEmpty()) {

            if (isListeningActive && !isSpeaking) {
                handler.postDelayed({
                    startListening()
                }, 300)
            }

            return
        }

        val spokenText =
            matches[0]
                .lowercase(Locale.US)
                .trim()

        if (spokenText.isEmpty()) {
            startListening()
            return
        }

        handleSpokenText(spokenText)
    }

    private fun handleSpokenText(spokenText: String) {

        // -----------------------------------------------------
        // JARVIS IS ALREADY AWAKE
        // -----------------------------------------------------

        if (isAwake) {

            handler.removeCallbacks(wakeTimeoutRunnable)

            isAwake = false

            executeVoiceCommand(spokenText)

            return
        }

        // -----------------------------------------------------
        // WAKE WORD DETECTION
        // -----------------------------------------------------

        if (containsWakeWord(spokenText)) {

            val command =
                removeWakeWord(spokenText)

            // Example:
            // "jarvis"
            // "hey jarvis"
            // "jarvis flashlight on"

            if (command.isBlank()) {

                isAwake = true

                handler.removeCallbacks(wakeTimeoutRunnable)

                handler.postDelayed(
                    wakeTimeoutRunnable,
                    7000
                )

                speak(
                    "Yes Sir",
                    "WAKE_UP"
                )

            } else {

                // Wake word + command in SAME sentence
                // Example:
                // "Jarvis turn on flashlight"

                isAwake = false

                executeVoiceCommand(command)
            }

        } else {

            // Nothing useful heard
            startListening()
        }
    }

    private fun containsWakeWord(text: String): Boolean {

        return text.contains("jarvis") ||
                text.contains("jarvis") ||
                text.contains("jarvis assistant")
    }

    private fun removeWakeWord(text: String): String {

        return text
            .replace("hey jarvis", "")
            .replace("ok jarvis", "")
            .replace("okay jarvis", "")
            .replace("jarvis assistant", "")
            .replace("jarvis", "")
            .trim()
    }

    // =========================================================
    // COMMAND EXECUTOR
    // =========================================================

    private fun executeVoiceCommand(command: String) {

        val cleanCommand =
            command
                .lowercase(Locale.US)
                .trim()

        when {

            // -------------------------------------------------
            // FLASHLIGHT ON
            // -------------------------------------------------

            cleanCommand.contains("turn on flashlight") ||
                    cleanCommand.contains("turn flashlight on") ||
                    cleanCommand.contains("flashlight on") ||
                    cleanCommand.contains("torch on") -> {

                actionExecutor.toggleFlashlight(true)

                speak(
                    "Flashlight turned on, Sir.",
                    "FLASHLIGHT_ON"
                )
            }

            // -------------------------------------------------
            // FLASHLIGHT OFF
            // -------------------------------------------------

            cleanCommand.contains("turn off flashlight") ||
                    cleanCommand.contains("turn flashlight off") ||
                    cleanCommand.contains("flashlight off") ||
                    cleanCommand.contains("torch off") -> {

                actionExecutor.toggleFlashlight(false)

                speak(
                    "Flashlight turned off, Sir.",
                    "FLASHLIGHT_OFF"
                )
            }

            // -------------------------------------------------
            // OPEN APP
            // -------------------------------------------------

            cleanCommand.startsWith("open ") -> {

                val appName =
                    cleanCommand
                        .removePrefix("open ")
                        .trim()

                val success =
                    actionExecutor.openApp(appName)

                if (success) {

                    speak(
                        "Opening $appName, Sir.",
                        "OPEN_APP"
                    )

                } else {

                    speak(
                        "I could not find that app, Sir.",
                        "APP_NOT_FOUND"
                    )
                }
            }

            // -------------------------------------------------
            // CALL
            // -------------------------------------------------

            cleanCommand.startsWith("call ") -> {

                val contactName =
                    cleanCommand
                        .removePrefix("call ")
                        .trim()

                actionExecutor.callContact(
                    contactName
                )

                speak(
                    "Calling $contactName, Sir.",
                    "CALL_CONTACT"
                )
            }

            // -------------------------------------------------
            // YOUTUBE
            // -------------------------------------------------

            cleanCommand.contains("play") &&
                    cleanCommand.contains("youtube") -> {

                val query =
                    cleanCommand
                        .replace("play", "")
                        .replace("on youtube", "")
                        .replace("youtube", "")
                        .trim()

                if (query.isNotBlank()) {

                    actionExecutor.playOnYoutube(
                        query
                    )

                    speak(
                        "Playing $query on YouTube, Sir.",
                        "YOUTUBE"
                    )

                } else {

                    speak(
                        "What would you like me to play, Sir?",
                        "YOUTUBE_EMPTY"
                    )
                }
            }

            // -------------------------------------------------
            // JOB / WORK MODE
            // -------------------------------------------------

            cleanCommand.startsWith("job ") ||
                    cleanCommand.contains("find me a job") ||
                    cleanCommand.contains("job search") ||
                    cleanCommand.contains("career") ||
                    cleanCommand.contains("resume") ||
                    cleanCommand.contains("interview") ||
                    cleanCommand.contains("job preparation") -> {

                askLlama3AI(
                    """
                    The user is using JARVIS Work Mode.

                    Help the user with jobs, career planning,
                    resume improvement, interview preparation,
                    coding preparation, learning plans and
                    professional development.

                    User request:
                    $cleanCommand
                    """.trimIndent()
                )
            }

            // -------------------------------------------------
            // NORMAL AI
            // -------------------------------------------------

            else -> {

                askLlama3AI(cleanCommand)
            }
        }
    }

    // =========================================================
    // GROQ AI
    // =========================================================

    private fun askLlama3AI(userQuery: String) {

        if (LLAMA_API_KEY == "PASTE_YOUR_EXISTING_GROQ_KEY_HERE") {

            speak(
                "Sir, my AI key has not been configured yet.",
                "NO_API_KEY"
            )

            return
        }

        val url =
            "https://api.groq.com/openai/v1/chat/completions"

        val jsonBody =
            JSONObject().apply {

                put(
                    "model",
                    "llama-3.1-8b-instant"
                )

                put(
                    "temperature",
                    0.6
                )

                put(
                    "max_tokens",
                    300
                )

                put(
                    "messages",
                    JSONArray().apply {

                        // SYSTEM
                        put(
                            JSONObject().apply {

                                put(
                                    "role",
                                    "system"
                                )

                                put(
                                    "content",
                                    """
                                    You are JARVIS, a futuristic personal AI assistant.

                                    Personality:
                                    - Intelligent
                                    - Calm
                                    - Loyal
                                    - Helpful
                                    - Slightly witty
                                    - Professional but friendly

                                    Address the user as Sir when appropriate.

                                    The user is building JARVIS as a real
                                    Android AI assistant and wants practical
                                    help with programming, development,
                                    robotics, AI, electronics, career,
                                    jobs and learning.

                                    You are also the user's Work Assistant.
                                    Help with:
                                    - Job preparation
                                    - Resume improvement
                                    - Interview preparation
                                    - Coding
                                    - Debugging
                                    - Learning plans
                                    - Project planning
                                    - Professional development

                                    Keep spoken responses concise,
                                    natural and easy to understand.

                                    Do not use markdown unless necessary,
                                    because your answer will be spoken aloud.
                                    """.trimIndent()
                                )
                            }
                        )

                        // USER
                        put(
                            JSONObject().apply {

                                put(
                                    "role",
                                    "user"
                                )

                                put(
                                    "content",
                                    userQuery
                                )
                            }
                        )
                    }
                )
            }

        val request =
            object : JsonObjectRequest(
                Request.Method.POST,
                url,
                jsonBody,

                { response ->

                    try {

                        val choices =
                            response.getJSONArray(
                                "choices"
                            )

                        if (choices.length() == 0) {

                            speak(
                                "I did not receive a response, Sir.",
                                "EMPTY_RESPONSE"
                            )

                            return@JsonObjectRequest
                        }

                        val message =
                            choices
                                .getJSONObject(0)
                                .getJSONObject("message")

                        val aiResponse =
                            message
                                .getString("content")
                                .trim()

                        if (aiResponse.isNotBlank()) {

                            speak(
                                aiResponse,
                                "AI_RESPONSE"
                            )

                        } else {

                            speak(
                                "I received an empty response, Sir.",
                                "EMPTY_AI"
                            )
                        }

                    } catch (e: Exception) {

                        e.printStackTrace()

                        speak(
                            "Sorry Sir, I could not process the AI response.",
                            "AI_PARSE_ERROR"
                        )
                    }
                },

                { error ->

                    error.printStackTrace()

                    speak(
                        "Sorry Sir, I am having trouble connecting to my AI system.",
                        "NETWORK_ERROR"
                    )
                }
            ) {

                @Throws(AuthFailureError::class)
                override fun getHeaders():
                        MutableMap<String, String> {

                    val headers =
                        HashMap<String, String>()

                    headers["Authorization"] =
                        "Bearer $LLAMA_API_KEY"

                    headers["Content-Type"] =
                        "application/json"

                    return headers
                }
            }

        request.tag = "JARVIS_AI_REQUEST"

        requestQueue.add(request)
    }

    // =========================================================
    // RECOGNITION CALLBACKS
    // =========================================================

    override fun onReadyForSpeech(
        params: Bundle?
    ) {
    }

    override fun onBeginningOfSpeech() {
    }

    override fun onRmsChanged(
        rmsdB: Float
    ) {
    }

    override fun onBufferReceived(
        buffer: ByteArray?
    ) {
    }

    override fun onEndOfSpeech() {

        isRecognizerListening = false
    }

    override fun onError(
        error: Int
    ) {

        isRecognizerListening = false

        if (!isListeningActive) return

        if (isSpeaking) return

        // SpeechRecognizer errors are common after
        // silence/timeouts. Restart cleanly.

        handler.postDelayed({

            if (!isListeningActive) return@postDelayed
            if (isSpeaking) return@postDelayed
            if (isRecognizerListening) return@postDelayed

            startListening()

        }, 500)
    }

    override fun onPartialResults(
        partialResults: Bundle?
    ) {
        // Partial results intentionally ignored.
        // Final result is used for stable commands.
    }

    override fun onEvent(
        eventType: Int,
        params: Bundle?
    ) {
    }

    // =========================================================
    // FOREGROUND SERVICE
    // =========================================================

    private fun startForegroundServiceNotification() {

        val channelId =
            "jarvis_service_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel =
                NotificationChannel(
                    channelId,
                    "JARVIS Assistant",
                    NotificationManager.IMPORTANCE_LOW
                )

            val manager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            manager.createNotificationChannel(
                channel
            )
        }

        val notification: Notification =
            NotificationCompat.Builder(
                this,
                channelId
            )
                .setContentTitle(
                    "JARVIS AI"
                )
                .setContentText(
                    "Listening for 'Jarvis'..."
                )
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .setOngoing(true)
                .setCategory(
                    NotificationCompat.CATEGORY_SERVICE
                )
                .build()

        startForeground(
            1,
            notification
        )
    }

    // =========================================================
    // DESTROY
    // =========================================================

    override fun onDestroy() {

        isListeningActive = false
        isAwake = false
        isRecognizerListening = false
        isSpeaking = false

        handler.removeCallbacksAndMessages(null)

        try {
            requestQueue.cancelAll("JARVIS_AI_REQUEST")
        } catch (_: Exception) {
        }

        try {
            speechRecognizer.stopListening()
        } catch (_: Exception) {
        }

        try {
            speechRecognizer.destroy()
        } catch (_: Exception) {
        }

        try {
            textToSpeech.stop()
            textToSpeech.shutdown()
        } catch (_: Exception) {
        }

        try {
            audioManager.abandonAudioFocus(null)
        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}
