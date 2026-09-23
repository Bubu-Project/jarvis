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
            android.util.Log.e("JARVIS_SPEECH", "Speech recognition is NOT available")
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        actionExecutor = ActionExecutor(this)
        requestQueue = Volley.newRequestQueue(this)
        createNotification()
        setupTTS()
        setupRecognizer()
        setupAudioFocus()
        isListeningActive = true
        handler.postDelayed({ startListening() }, 3000)
    }

    // =====================================================
    // TTS
    // =====================================================
    private fun setupTTS() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val languageResult = textToSpeech.setLanguage(Locale.US)
                ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                        languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                textToSpeech.setSpeechRate(1.0f)
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
                            handler.postDelayed({ startListening() }, 800)
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    handler.post {
                        isSpeaking = false
                        if (isListeningActive) {
                            handler.postDelayed({ startListening() }, 800)
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
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }
    }

    // =====================================================
    // START LISTENING
    // =====================================================
    private fun startListening() {
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

        if (wakeHandled) {
            wakeHandled = false
            if (isListeningActive && !isSpeaking) {
                handler.postDelayed({ startListening() }, 300)
            }
            return
        }

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
        handleSpeech(spokenText)
    }

    // =====================================================
    // PARTIAL RESULT
    // =====================================================
    override fun onPartialResults(partialResults: Bundle?) {
        if (!isListeningActive || isSpeaking || isAwake || wakeHandled) return

        val resultsList = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (resultsList.isNullOrEmpty()) return

        val spokenText = resultsList.firstOrNull()?.lowercase(Locale.US)?.trim() ?: ""
        if (!wakeWordDetector.containsWakeWord(spokenText)) return

        wakeHandled = true
        isRecognizerListening = false
        try { speechRecognizer.stopListening() } catch (_: Exception) {}

        val command = wakeWordDetector.removeWakeWord(spokenText)

        if (command.isBlank()) {
            isAwake = true
            handler.removeCallbacks(wakeTimeout)
            handler.postDelayed(wakeTimeout, 8000)
            speak("Yes Sir", "WAKE_UP")
        } else {
            isAwake = false
            executeVoiceCommand(command)
        }
    }

    // =====================================================
    // SPEECH HANDLER
    // =====================================================
    private fun handleSpeech(text: String) {
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
    // COMMAND EXECUTOR (Saare commands add hain)
    // =====================================================
    private fun executeVoiceCommand(command: String) {
        val cmd = command.lowercase(Locale.US).trim()

        when {
            // FLASHLIGHT ON
            cmd.contains("flashlight on") || cmd.contains("torch on") || cmd.contains("turn on flashlight") || cmd.contains("turn flashlight on") -> {
                actionExecutor.toggleFlashlight(true)
                speak("Flashlight turned on, Sir.", "FLASH_ON")
            }
            // FLASHLIGHT OFF
            cmd.contains("flashlight off") || cmd.contains("torch off") || cmd.contains("turn off flashlight") || cmd.contains("turn flashlight off") -> {
                actionExecutor.toggleFlashlight(false)
                speak("Flashlight turned off, Sir.", "FLASH_OFF")
            }
            // OPEN APP
            cmd.startsWith("open ") -> {
                val appName = cmd.removePrefix("open ").trim()
                val success = actionExecutor.openApp(appName)
                if (success) {
                    speak("Opening $appName, Sir.", "OPEN_APP")
                } else {
                    speak("I could not find that app, Sir.", "APP_ERROR")
                }
            }
            // CALL
            cmd.startsWith("call ") -> {
                val contactName = cmd.removePrefix("call ").trim()
                actionExecutor.callContact(contactName)
                speak("Calling $contactName, Sir.", "CALL")
            }
            // YOUTUBE
            cmd.contains("youtube") && cmd.contains("play") -> {
                val query = cmd.replace("play", "").replace("on youtube", "").replace("youtube", "").trim()
                if (query.isNotBlank()) {
                    actionExecutor.playOnYoutube(query)
                    speak("Playing $query on YouTube, Sir.", "YOUTUBE")
                } else {
                    speak("What would you like me to play, Sir?", "YOUTUBE_EMPTY")
                }
            }
            // JOB / WORK MODE
            cmd.startsWith("job ") || cmd.contains("find me a job") || cmd.contains("job search") || cmd.contains("career") || cmd.contains("resume") || cmd.contains("interview") -> {
                speak("Searching for job information, Sir.", "JOB_SEARCH")
            }
            // UNKNOWN COMMAND
            else -> {
                speak("I am not sure how to do that yet, Sir.", "UNKNOWN")
            }
        }
    }

    // =====================================================
    // BAKEI FUNCTIONS (createNotification, setupAudioFocus, etc.)
    // =====================================================
    private fun createNotification() {
        val channelId = "jarvis_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Jarvis Service", NotificationManager.IMPORTANCE_LOW)
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

    private fun setupAudioFocus() {
        // Tera purana audio focus code yahan (agar hai toh)
    }

    // =====================================================
    // BAKEI RECOGNITION LISTENER METHODS (Zaroori hain)
    // =====================================================
    override fun onError(error: Int) {
        isRecognizerListening = false
        if (isListeningActive && !isSpeaking) {
            handler.postDelayed({ startListening() }, 500)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
