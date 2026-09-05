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

    // =====================================================
    // GROQ API KEY
    // SIRF ISI JAGAH APNI EXISTING GROQ KEY PASTE KARO
    // =====================================================
    private val LLAMA_API_KEY = "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"

    private val handler = Handler(Looper.getMainLooper())

    private var isListeningActive = false
    private var isRecognizerListening = false
    private var isSpeaking = false
    private var isAwake = false
    private var ttsReady = false
    private var speechErrorReported = false
    private var wakeHandled = false
    private var recognizerRestartPending = false

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
            android.util.Log.e(
                "JARVIS_SPEECH",
                "Speech recognition is NOT available on this device"
            )
        }
        audioManager =
            getSystemService(Context.AUDIO_SERVICE) as AudioManager

        actionExecutor = ActionExecutor(this)

        requestQueue = Volley.newRequestQueue(this)

        createNotification()

        setupTTS()

        setupRecognizer()

        setupAudioFocus()

        isListeningActive = true

        handler.postDelayed(
            {
                startListening()
            },
            1000
        )
    }

    // =====================================================
    // TTS
    // =====================================================

    private fun setupTTS() {

        textToSpeech = TextToSpeech(this) { status ->

            if (status == TextToSpeech.SUCCESS) {

                val languageResult =
                    textToSpeech.setLanguage(Locale.US)

                ttsReady =
                    languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED

                textToSpeech.setSpeechRate(1.0f)

                setupTTSListener()
            }
        }
    }

    private fun setupTTSListener() {

        textToSpeech.setOnUtteranceProgressListener(

            object : UtteranceProgressListener() {

                override fun onStart(
                    utteranceId: String?
                ) {

                    handler.post {

                        isSpeaking = true

                        try {
                            speechRecognizer.stopListening()
                        } catch (_: Exception) {}

                        isRecognizerListening = false
                    }
                }

                override fun onDone(
                    utteranceId: String?
                ) {

                    handler.post {

                        android.widget.Toast.makeText(
                            this@AssistantService,
                            "TTS DONE - restarting listen",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()

                        isSpeaking = false

                        if (isListeningActive) {

                            handler.postDelayed(
                                {
                                    startListening()
                                },
                                350
                            )
                        }
                    }
                }

                override fun onError(
                    utteranceId: String?
                ) {

                    handler.post {

                        isSpeaking = false

                        if (isListeningActive) {

                            handler.postDelayed(
                                {
                                    startListening()
                                },
                                350
                            )
                        }
                    }
                }
            }
        )
    }

    private fun speak(
        text: String,
        id: String
    ) {

        if (!ttsReady) {
            return
        }

        handler.post {

            isSpeaking = true

            try {
                speechRecognizer.stopListening()
            } catch (_: Exception) {}

            isRecognizerListening = false

            textToSpeech.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                id
            )
        }
    }

    // =====================================================
    // SPEECH RECOGNIZER
    // =====================================================

    private fun setupRecognizer() {

        try {
            speechRecognizer.destroy()
        } catch (_: Exception) {}

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak(
                "Sir, speech recognition is not available on this device.",
                "SPEECH_UNAVAILABLE"
            )
            return
        }

        try {

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer.setRecognitionListener(this)

        } catch (e: Exception) {

            handler.post {
                android.widget.Toast.makeText(
                    this,
                    "CRASH in setupRecognizer: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }

            e.printStackTrace()

            return
        }

        recognizerIntent =
            Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    5
                )

                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true
                )

                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    500L
                )

                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1500L
                )

                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    2000L
                )
            }
    }

    // =====================================================
    // START LISTENING
    // =====================================================

    private fun startListening() {

        handler.post {
            android.widget.Toast.makeText(
                this,
                "startListening CALLED",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        if (!isListeningActive) return

        if (isSpeaking) return

        if (isRecognizerListening) return

        handler.post {

            if (!isListeningActive) return@post

            if (isSpeaking) return@post

            if (isRecognizerListening) return@post

            try {

                wakeHandled = false

                isRecognizerListening = true

                speechRecognizer.startListening(
                    recognizerIntent
                )

            } catch (e: Exception) {

                isRecognizerListening = false

                handler.post {
                    android.widget.Toast.makeText(
                        this,
                        "CRASH in startListening: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }

                e.printStackTrace()

                restartRecognizer()
            }
        }
    }

    // =====================================================
    // RESTART SPEECH RECOGNIZER
    // =====================================================

    private fun restartRecognizer() {

        if (!isListeningActive) return

        if (recognizerRestartPending) return

        recognizerRestartPending = true

        handler.postDelayed({

            recognizerRestartPending = false

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

            } catch (e: Exception) {

                isRecognizerListening = false

                handler.post {
                    android.widget.Toast.makeText(
                        this,
                        "CRASH in restartRecognizer: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }

                e.printStackTrace()

                handler.postDelayed(
                    {
                        startListening()
                    },
                    1000
                )
            }

        }, 800)
    }

    // =====================================================
    // WAKE WORD
    // =====================================================

    private fun containsWakeWord(
        text: String
    ): Boolean {

        val value =
            text
                .lowercase(Locale.US)
                .replace("-", " ")
                .replace(".", " ")
                .replace(",", " ")
                .replace("?", " ")
                .replace("!", " ")
                .trim()

        return value.contains("jarvis") ||
                value.contains("jar vis") ||
                value.contains("jervis") ||
                value.contains("jarvish") ||
                value.contains("jarvice")
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
            .replace("jar vis", "")
            .replace("jervis", "")
            .replace("jarvish", "")
            .replace("jarvice", "")
            .replace("jarvis", "")
            .trim()
    }

    // =====================================================
    // FINAL RESULT
    // =====================================================

    override fun onResults(
        results: Bundle?
    ) {
        handler.post {
            android.widget.Toast.makeText(
                this,
                "onResults CALLED",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        isRecognizerListening = false

        if (wakeHandled) {

            wakeHandled = false

            if (
                isListeningActive &&
                !isSpeaking
            ) {

                handler.postDelayed(
                    {
                        startListening()
                    },
                    300
                )
            }

            return
        }

        val resultsList =
            results?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
            )

        if (resultsList.isNullOrEmpty()) {

            handler.post {
                android.widget.Toast.makeText(
                    this,
                    "Got EMPTY result (mic heard nothing clear)",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }

            handler.postDelayed(
                {
                    startListening()
                },
                300
            )

            return
        }

        val spokenText =
            resultsList.firstOrNull()
                ?.lowercase(Locale.US)
                ?.trim()
                ?: ""

        if (spokenText.isBlank()) {

            startListening()

            return
        }

        handleSpeech(
            spokenText
        )
    }

    // =====================================================
    // PARTIAL RESULT
    // =====================================================

    override fun onPartialResults(
        partialResults: Bundle?
    ) {

        if (!isListeningActive) return

        if (isSpeaking) return

        if (isAwake) return

        if (wakeHandled) return

        val resultsList =
            partialResults?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
            )

        if (resultsList.isNullOrEmpty()) return

        val spokenText =
            resultsList.firstOrNull()
                ?.lowercase(Locale.US)
                ?.trim()
                ?: ""

        if (!containsWakeWord(spokenText)) {
            return
        }

        wakeHandled = true

        isRecognizerListening = false

        try {
            speechRecognizer.stopListening()
        } catch (_: Exception) {}

        val command =
            removeWakeWord(
                spokenText
            )

        if (command.isBlank()) {

            isAwake = true

            handler.removeCallbacks(
                wakeTimeout
            )

            handler.postDelayed(
                wakeTimeout,
                8000
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
    }

    // =====================================================
    // SPEECH HANDLER
    // =====================================================

    private fun handleSpeech(
        text: String
    ) {

        if (isAwake) {

            isAwake = false

            handler.removeCallbacks(
                wakeTimeout
            )

            executeVoiceCommand(
                text
            )

            return
        }

        if (containsWakeWord(text)) {

            val command =
                removeWakeWord(text)

            if (command.isBlank()) {

                isAwake = true

                handler.removeCallbacks(
                    wakeTimeout
                )

                handler.postDelayed(
                    wakeTimeout,
                    8000
                )

                speak(
                    "Yes Sir",
                    "WAKE_UP"
                )

            } else {

                executeVoiceCommand(
                    command
                )
            }

        } else {

            startListening()
        }
    }

    // =====================================================
    // COMMAND EXECUTOR
    // =====================================================

    private fun executeVoiceCommand(
        command: String
    ) {

        val cmd =
            command
                .lowercase(Locale.US)
                .trim()

        when {

            // FLASHLIGHT ON
            cmd.contains("flashlight on") ||
            cmd.contains("torch on") ||
            cmd.contains("turn on flashlight") ||
            cmd.contains("turn flashlight on") -> {

                actionExecutor.toggleFlashlight(
                    true
                )

                speak(
                    "Flashlight turned on, Sir.",
                    "FLASH_ON"
                )
            }

            // FLASHLIGHT OFF
            cmd.contains("flashlight off") ||
            cmd.contains("torch off") ||
            cmd.contains("turn off flashlight") ||
            cmd.contains("turn flashlight off") -> {

                actionExecutor.toggleFlashlight(
                    false
                )

                speak(
                    "Flashlight turned off, Sir.",
                    "FLASH_OFF"
                )
            }

            // OPEN APP
            cmd.startsWith("open ") -> {

                val appName =
                    cmd
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
                        "APP_ERROR"
                    )
                }
            }

            // CALL
            cmd.startsWith("call ") -> {

                val contactName =
                    cmd
                        .removePrefix("call ")
                        .trim()

                actionExecutor.callContact(
                    contactName
                )

                speak(
                    "Calling $contactName, Sir.",
                    "CALL"
                )
            }

            // YOUTUBE
            cmd.contains("youtube") &&
            cmd.contains("play") -> {

                val query =
                    cmd
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

            // WORK MODE
            cmd.startsWith("job ") ||
            cmd.contains("find me a job") ||
            cmd.contains("job search") ||
            cmd.contains("career") ||
            cmd.contains("resume") ||
            cmd.contains("interview") ||
            cmd.contains("job preparation") -> {

                askAI(
                    """
                    The user is using JARVIS Work Mode.

                    Help with jobs, remote jobs,
                    career planning, resume,
                    interview preparation,
                    coding preparation,
                    AI/ML learning,
                    project planning and
                    professional development.

                    Give practical and actionable advice.

                    User request:
                    $cmd
                    """.trimIndent()
                )
            }

            // NORMAL AI
            else -> {

                askAI(cmd)
            }
        }
    }

    // =====================================================
    // GROQ AI
    // =====================================================

    private fun askAI(
        userQuery: String
    ) {

        if (
            LLAMA_API_KEY ==
            "PASTE_YOUR_EXISTING_GROQ_KEY_HERE"
        ) {

            speak(
                "Sir, my AI key is not configured.",
                "NO_KEY"
            )

            return
        }

        val url =
            "https://api.groq.com/openai/v1/chat/completions"

        val body =
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
                                    Intelligent, calm, loyal,
                                    helpful, slightly witty,
                                    professional and friendly.

                                    Address the user as Sir when appropriate.

                                    Help with programming, AI,
                                    machine learning, robotics,
                                    electronics, Android development,
                                    career, jobs, resume,
                                    interviews and learning.

                                    You are also the user's
                                    Work Assistant.

                                    Give practical answers.

                                    Keep responses concise and
                                    natural because responses
                                    are spoken aloud.

                                    Avoid markdown when possible.
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

                body,

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

                        val answer =
                            message
                                .getString("content")
                                .trim()

                        if (answer.isNotBlank()) {

                            speak(
                                answer,
                                "AI_RESPONSE"
                            )

                        } else {

                            speak(
                                "I received an empty response, Sir.",
                                "EMPTY_RESPONSE"
                            )
                        }

                    } catch (e: Exception) {

                        e.printStackTrace()

                        speak(
                            "Sorry Sir, I could not process the AI response.",
                            "AI_ERROR"
                        )
                    }
                },

                { error ->

                    error.printStackTrace()

                    speak(
                        "Sorry Sir, I cannot connect to my AI system right now.",
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

    // =====================================================
    // AUDIO FOCUS
    // =====================================================

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

    // =====================================================
    // EMPTY CALLBACKS
    // =====================================================

    override fun onReadyForSpeech(
        params: Bundle?
    ) {
        handler.post {
            android.widget.Toast.makeText(
                this,
                "READY FOR SPEECH",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onBeginningOfSpeech() {
        handler.post {
            android.widget.Toast.makeText(
                this,
                "BEGINNING OF SPEECH",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onRmsChanged(
        rmsdB: Float
    ) {}

    override fun onBufferReceived(
        buffer: ByteArray?
    ) {}

    override fun onEndOfSpeech() {
        isRecognizerListening = false
    }

    override fun onError(error: Int) {

        isRecognizerListening = false

        android.util.Log.e(
            "JARVIS_SPEECH",
            "SpeechRecognizer error code: $error"
        )

        handler.post {
            android.widget.Toast.makeText(
                this,
                "Speech error: $error",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        if (!speechErrorReported) {
            speechErrorReported = true
            speak(
                "Speech recognition error code $error",
                "SPEECH_ERROR"
            )
        }

        if (!isListeningActive) return

        if (isSpeaking) return

        handler.postDelayed(
            {
                startListening()
            },
            2000
        )
    }

    override fun onEvent(
        eventType: Int,
        params: Bundle?
    ) {}

    // =====================================================
    // NOTIFICATION
    // =====================================================

    private fun createNotification() {

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

    // =====================================================
    // DESTROY
    // =====================================================

    override fun onDestroy() {

        isListeningActive = false
        isAwake = false
        isRecognizerListening = false
        isSpeaking = false
        wakeHandled = false

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
