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

    // =========================================================
    // GROQ API KEY
    // YAHAN APNI EXISTING GROQ API KEY PASTE KARO
    // =========================================================
    private val LLAMA_API_KEY = "PASTE_YOUR_EXISTING_GROQ_KEY_HERE"

    private val handler = Handler(Looper.getMainLooper())

    private var isAwake = false
    private var isListeningActive = false
    private var isRecognizerListening = false
    private var isSpeaking = false
    private var ttsReady = false

    // Prevents partial + final result from triggering twice
    private var wakeDetectedThisSession = false

    private val wakeTimeoutRunnable = Runnable {
        if (isAwake) {
            isAwake = false
            wakeDetectedThisSession = false
            startListening()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =========================================================
    // SERVICE START
    // =========================================================

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
        }, 1000)
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
                        } catch (_: Exception) {}

                        isRecognizerListening = false
                    }
                }

                override fun onDone(utteranceId: String?) {

                    handler.post {

                        isSpeaking = false

                        if (isListeningActive) {

                            handler.postDelayed(
                                {
                                    startListening()
                                },
                                300
                            )
                        }
                    }
                }

                override fun onError(utteranceId: String?) {

                    handler.post {

                        isSpeaking = false

                        if (isListeningActive) {

                            handler.postDelayed(
                                {
                                    startListening()
                                },
                                300
                            )
                        }
                    }
                }
            }
        )
    }

    private fun speak(
        text: String,
        utteranceId: String
    ) {

        if (!ttsReady) {

            handler.postDelayed(
                {
                    startListening()
                },
                500
            )

            return
        }

        handler.post {

            isSpeaking = true

            try {
                if (isRecognizerListening) {
                    speechRecognizer.stopListening()
                }
            } catch (_: Exception) {}

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

        try {
            speechRecognizer.destroy()
        } catch (_: Exception) {}

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

                // Indian English / Hinglish
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

                // Faster speech finalization
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    500L
                )

                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    900L
                )

                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1200L
                )

                putExtra(
                    RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    packageName
                )
            }
    }

    // =========================================================
    // START LISTENING
    // =========================================================

    private fun startListening() {

        if (!isListeningActive) return

        if (isSpeaking) return

        if (isRecognizerListening) return

        handler.post {

            if (!isListeningActive) return@post

            if (isSpeaking) return@post

            if (isRecognizerListening) return@post

            try {

                wakeDetectedThisSession = false

                isRecognizerListening = true

                speechRecognizer.startListening(
                    recognizerIntent
                )

            } catch (_: Exception) {

                isRecognizerListening = false

                restartRecognizer()
            }
        }
    }

    // =========================================================
    // RESTART RECOGNIZER
    // =========================================================

    private fun restartRecognizer() {

        if (!isListeningActive) return

        handler.postDelayed({

            if (!isListeningActive) return@postDelayed

            if (isSpeaking) return@postDelayed

            try {
                speechRecognizer.destroy()
            } catch (_: Exception) {}

            try {

                setupRecognizer()

                isRecognizerListening = true

                speechRecognizer.startListening(
                    recognizerIntent
                )

            } catch (_: Exception) {

                isRecognizerListening = false

                handler.postDelayed(
                    {
                        startListening()
                    },
                    1000
                )
            }

        }, 700)
    }

    // =========================================================
    // WAKE WORD
    // =========================================================

    private fun containsWakeWord(
        text: String
    ): Boolean {

        val normalized =
            text
                .lowercase(Locale.US)
                .replace("-", " ")
                .replace(".", " ")
                .replace(",", " ")
                .trim()

        return normalized.contains("jarvis") ||
                normalized.contains("jar vis") ||
                normalized.contains("jervis") ||
                normalized.contains("jarvish") ||
                normalized.contains("jarvice")
    }

    private fun removeWakeWord(
        text: String
    ): String {

        return text
            .lowercase(Locale.US)
            .replace("hey jarvis", "")
            .replace("ok jarvis", "")
            .replace("okay jarvis", "")
            .replace("jarvis assistant", "")
            .replace("jarvis", "")
            .replace("jar vis", "")
            .replace("jervis", "")
            .replace("jarvish", "")
            .replace("jarvice", "")
            .trim()
    }

    // =========================================================
    // FINAL SPEECH RESULT
    // =========================================================

    override fun onResults(
        results: Bundle?
    ) {

        isRecognizerListening = false

        if (wakeDetectedThisSession) {

            wakeDetectedThisSession = false

            if (isListeningActive && !isSpeaking) {
                handler.postDelayed(
                    {
                        startListening()
                    },
                    300
                )
            }

            return
        }

        val matches =
            results?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
            )

        if (matches.isNullOrEmpty()) {

            if (isListeningActive && !isSpeaking) {

                handler.postDelayed(
                    {
                        startListening()
                    },
                    300
                )
            }

            return
        }

        val spokenText =
            matches[0]
                .lowercase(Locale.US)
                .trim()

        if (spokenText.isBlank()) {

            startListening()

            return
        }

        handleSpokenText(spokenText)
    }

    // =========================================================
    // PARTIAL SPEECH RESULT
    // =========================================================

    override fun onPartialResults(
        partialResults: Bundle?
    ) {

        if (!isListeningActive) return

        if (isSpeaking) return

        if (isAwake) return

        if (wakeDetectedThisSession) return

        val matches =
            partialResults?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
            )

        if (matches.isNullOrEmpty()) return

        val spokenText =
            matches[0]
                .lowercase(Locale.US)
                .trim()

        if (!containsWakeWord(spokenText)) {
            return
        }

        // Wake word detected immediately
        wakeDetectedThisSession = true

        isRecognizerListening = false

        try {
            speechRecognizer.stopListening()
        } catch (_: Exception) {}

        handler.removeCallbacks(
            wakeTimeoutRunnable
        )

        val command =
            removeWakeWord(spokenText)

        if (command.isBlank()) {

            isAwake = true

            handler.postDelayed(
                wakeTimeoutRunnable,
                7000
            )

            speak(
                "Yes Sir",
                "WAKE_UP"
            )

        } else {

            isAwake = false

            executeVoiceCommand(command)
        }
    }

    // =========================================================
    // HANDLE SPOKEN TEXT
    // =========================================================

    private fun handleSpokenText(
        spokenText: String
    ) {

        if (isAwake) {

            handler.removeCallbacks(
                wakeTimeoutRunnable
            )

            isAwake = false

            executeVoiceCommand(
                spokenText
            )

            return
        }

        if (containsWakeWord(spokenText)) {

            val command =
                removeWakeWord(spokenText)

            if (command.isBlank()) {

                isAwake = true

                handler.removeCallbacks(
                    wakeTimeoutRunnable
                )

                handler.postDelayed(
                    wakeTimeoutRunnable,
                    7000
                )

                speak(
                    "Yes Sir",
                    "WAKE_UP"
                )

            } else {

                isAwake = false

                executeVoiceCommand(
                    command
                )
            }

        } else {

            startListening()
        }
    }

    // =========================================================
    // VOICE COMMANDS
    // =========================================================

    private fun executeVoiceCommand(
        command: String
    ) {

        val cleanCommand =
            command
                .lowercase(Locale.US)
                .trim()

        when {

            // -------------------------------------------------
            // FLASHLIGHT ON
            // -------------------------------------------------

            cleanCommand.contains(
                "turn on flashlight"
            ) ||

            cleanCommand.contains(
                "turn flashlight on"
            ) ||

            cleanCommand.contains(
                "flashlight on"
            ) ||

            cleanCommand.contains(
                "torch on"
            ) -> {

                actionExecutor.toggleFlashlight(
                    true
                )

                speak(
                    "Flashlight turned on, Sir.",
                    "FLASHLIGHT_ON"
                )
            }

            // -------------------------------------------------
            // FLASHLIGHT OFF
            // -------------------------------------------------

            cleanCommand.contains(
                "turn off flashlight"
            ) ||

            cleanCommand.contains(
                "turn flashlight off"
            ) ||

            cleanCommand.contains(
                "flashlight off"
            ) ||

            cleanCommand.contains(
                "torch off"
            ) -> {

                actionExecutor.toggleFlashlight(
                    false
                )

                speak(
                    "Flashlight turned off, Sir.",
                    "FLASHLIGHT_OFF"
                )
            }

            // -------------------------------------------------
            // OPEN APP
            // -------------------------------------------------

            cleanCommand.startsWith(
                "open "
            ) -> {

                val appName =
                    cleanCommand
                        .removePrefix("open ")
                        .trim()

                val success =
                    actionExecutor.openApp(
                        appName
                    )

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
            // CALL CONTACT
            // -------------------------------------------------

            cleanCommand.startsWith(
                "call "
            ) -> {

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
            // WORK / JOB MODE
            // -------------------------------------------------

            cleanCommand.startsWith("job ") ||

            cleanCommand.contains(
                "find me a job"
            ) ||

            cleanCommand.contains(
                "job search"
            ) ||

            cleanCommand.contains(
                "career"
            ) ||

            cleanCommand.contains(
                "resume"
            ) ||

            cleanCommand.contains(
                "interview"
            ) ||

            cleanCommand.contains(
                "job preparation"
            ) -> {

                askLlama3AI(
                    """
                    The user is using JARVIS Work Mode.

                    Help with:
                    - Jobs
                    - Remote jobs
                    - Career planning
                    - Resume improvement
                    - Interview preparation
                    - Coding preparation
                    - AI/ML learning
                    - Project planning
                    - Professional development

                    Give practical and actionable advice.

                    User request:
                    $cleanCommand
                    """.trimIndent()
                )
            }

            // -------------------------------------------------
            // NORMAL AI
            // -------------------------------------------------

            else -> {

                askLlama3AI(
                    cleanCommand
                )
            }
        }
    }

    // =========================================================
    // GROQ AI
    // =========================================================

    private fun askLlama3AI(
        userQuery: String
    ) {

        if (
            LLAMA_API_KEY ==
            "PASTE_YOUR_EXISTING_GROQ_KEY_HERE"
        ) {

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
                                    Android AI assistant.

                                    Help with:
                                    - Programming
                                    - AI
                                    - Machine Learning
                                    - Robotics
                                    - Electronics
                                    - Android development
                                    - Career
                                    - Jobs
                                    - Resume
                                    - Interviews
                                    - Learning
                                    - Projects

                                    You are also the user's Work Assistant.

                                    Give practical answers.

                                    Keep spoken responses concise,
                                    natural and easy to understand.

                                    Do not use markdown unless necessary,
                                    because your response will be spoken aloud.
                                    """.trimIndent()
                                )
                            }
                        )

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

                        val message =
                            choices
                                .getJSONObject(0)
                                .getJSONObject("message")

                        val aiResponse =
                            message
                                .getString("content")
                                .trim()

                        if (
                            aiResponse.isNotBlank()
                        ) {

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

        request.tag =
            "JARVIS_AI_REQUEST"

        requestQueue.add(
            request
        )
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
                        } catch (_: Exception) {}

                        isRecognizerListening = false
                    }

                    AudioManager.AUDIOFOCUS_GAIN -> {

                        isListeningActive = true

                        handler.postDelayed(
                            {
                                startListening()
                            },
                            500
                        )
                    }
                }
            },

            AudioManager.STREAM_MUSIC,

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
    }

    // =========================================================
    // RECOGNITION CALLBACKS
    // =========================================================

    override fun onReadyForSpeech(
        params: Bundle?
    ) {}

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(
        rmsdB: Float
    ) {}

    override fun onBufferReceived(
        buffer: ByteArray?
    ) {}

    override fun onEndOfSpeech() {
        isRecognizerListening = false
    }

    override fun onError(
        error: Int
    ) {

        isRecognizerListening = false

        if (!isListeningActive) return

        if (isSpeaking) return

        handler.postDelayed(
            {
                if (
                    isListeningActive &&
                    !isSpeaking &&
                    !isRecognizerListening
                ) {
                    startListening()
                }
            },
            500
        )
    }

    override fun onEvent(
        eventType: Int,
        params: Bundle?
    ) {}

    // =========================================================
    // FOREGROUND NOTIFICATION
    // =========================================================

    private fun startForegroundServiceNotification() {

        val channelId =
            "jarvis_service_channel"

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

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
            NotificationCompat
                .Builder(
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
    // SERVICE DESTROY
    // =========================================================

    override fun onDestroy() {

        isListeningActive = false

        isAwake = false

        isRecognizerListening = false

        isSpeaking = false

        wakeDetectedThisSession = false

        handler.removeCallbacksAndMessages(
            null
        )

        try {
            requestQueue.cancelAll(
                "JARVIS_AI_REQUEST"
            )
        } catch (_: Exception) {}

        try {
            speechRecognizer.stopListening()
        } catch (_: Exception) {}

        try {
            speechRecognizer.destroy()
        } catch (_: Exception) {}

        try {
            textToSpeech.stop()
            textToSpeech.shutdown()
        } catch (_: Exception) {}

        try {
            audioManager.abandonAudioFocus(
                null
            )
        } catch (_: Exception) {}

        super.onDestroy()
    }
}
