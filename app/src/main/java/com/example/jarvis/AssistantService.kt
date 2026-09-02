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

    // API KEY YAHAN PASTE KARNI HAI 👇
    private val LLAMA_API_KEY = "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"
    private var isAwake = false
    private var isListeningActive = false

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        audioManager =
            getSystemService(Context.AUDIO_SERVICE) as AudioManager

        actionExecutor = ActionExecutor(this)

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.US
                setupTTSListener()
            }
        }

        setupRecognizer()
        startForegroundServiceNotification()
        setupAudioFocus()

        isListeningActive = true
        startListening()
    }

    private fun setupRecognizer() {
        speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer.setRecognitionListener(this)

        recognizerIntent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault()
            )

            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                1
            )
        }
    }

    private fun setupTTSListener() {

        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {

                override fun onStart(utteranceId: String?) {
                    handler.post {
                        try {
                            speechRecognizer.stopListening()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    handler.post {
                        startListening()
                    }
                }

                override fun onError(utteranceId: String?) {
                    handler.post {
                        startListening()
                    }
                }
            }
        )
    }

    private fun setupAudioFocus() {

        audioManager.requestAudioFocus(
            { focusChange ->

                when (focusChange) {

                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {

                        isListeningActive = false

                        try {
                            speechRecognizer.stopListening()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    AudioManager.AUDIOFOCUS_GAIN -> {

                        isListeningActive = true
                        startListening()
                    }
                }
            },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
    }

    private fun startListening() {

        if (!isListeningActive) {
            return
        }

        handler.post {

            try {
                speechRecognizer.startListening(recognizerIntent)

            } catch (e: Exception) {

                e.printStackTrace()
                restartRecognizer()
            }
        }
    }

    private fun restartRecognizer() {

        handler.postDelayed({

            try {

                speechRecognizer.destroy()

                setupRecognizer()

                speechRecognizer.startListening(
                    recognizerIntent
                )

            } catch (e: Exception) {

                e.printStackTrace()
            }

        }, 500)
    }

    private fun startForegroundServiceNotification() {

        val channelId = "jarvis_service_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                channelId,
                "Jarvis Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            manager.createNotificationChannel(channel)
        }

        val notification: Notification =
            NotificationCompat.Builder(
                this,
                channelId
            )
                .setContentTitle("JARVIS AI")
                .setContentText(
                    "Listening for commands..."
                )
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .setOngoing(true)
                .build()

        startForeground(1, notification)
    }

    override fun onResults(results: Bundle?) {

        val matches =
            results?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
            )

        if (!matches.isNullOrEmpty()) {

            val spokenText =
                matches[0]
                    .lowercase(Locale.US)
                    .trim()

            if (!isAwake) {

                if (spokenText.contains("jarvis")) {

                    isAwake = true

                    textToSpeech.speak(
                        "Yes Sir",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "WAKE_UP"
                    )

                } else {

                    startListening()
                }

            } else {

                executeVoiceCommand(spokenText)

                isAwake = false
            }

        } else {

            startListening()
        }
    }

    private fun executeVoiceCommand(command: String) {

        when {

            command.contains("turn on flashlight") ||
                    command.contains("torch on") -> {

                actionExecutor.toggleFlashlight(true)

                textToSpeech.speak(
                    "Flashlight turned on, sir.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "ACTION"
                )
            }

            command.contains("turn off flashlight") ||
                    command.contains("torch off") -> {

                actionExecutor.toggleFlashlight(false)

                textToSpeech.speak(
                    "Flashlight turned off, sir.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "ACTION"
                )
            }

            command.startsWith("open ") -> {

                val appName =
                    command
                        .replace("open ", "")
                        .trim()

                val success =
                    actionExecutor.openApp(appName)

                if (success) {

                    textToSpeech.speak(
                        "Opening $appName, sir.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "ACTION"
                    )

                } else {

                    textToSpeech.speak(
                        "App not found, sir.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "ACTION"
                    )
                }
            }

            command.startsWith("call ") -> {

                val contactName =
                    command
                        .replace("call ", "")
                        .trim()

                actionExecutor.callContact(
                    contactName
                )

                textToSpeech.speak(
                    "Calling $contactName, sir.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "ACTION"
                )
            }

            command.contains("play") &&
                    command.contains("on youtube") -> {

                val query =
                    command
                        .replace("play", "")
                        .replace("on youtube", "")
                        .trim()

                actionExecutor.playOnYoutube(query)

                textToSpeech.speak(
                    "Playing on YouTube, sir.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "ACTION"
                )
            }

            else -> {

                askLlama3AI(command)
            }
        }
    }

    private fun askLlama3AI(userQuery: String) {

        val queue =
            Volley.newRequestQueue(this)

        val url =
            "https://api.groq.com/openai/v1/chat/completions"

        val jsonBody =
            JSONObject().apply {

                put(
                    "model",
                    "llama-3.1-8b-instant"
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
                                    "You are JARVIS, a futuristic AI assistant inspired by Iron Man. You are loyal, intelligent, witty and helpful. Talk like a real supportive assistant. Keep answers concise and natural because your responses will be spoken aloud."
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

        val jsonObjectRequest =
            object : JsonObjectRequest(
                Method.POST,
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

                        textToSpeech.speak(
                            aiResponse,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "AI_RESPONSE"
                        )

                    } catch (e: Exception) {

                        e.printStackTrace()

                        textToSpeech.speak(
                            "Sorry sir, I could not process that.",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "ERROR_RESPONSE"
                        )
                    }
                },

                { error ->

                    error.printStackTrace()

                    textToSpeech.speak(
                        "Sorry sir, I am having trouble connecting to my AI system.",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
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

        queue.add(jsonObjectRequest)
    }

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
    }

    override fun onError(
        error: Int
    ) {

        if (isListeningActive) {

            handler.postDelayed(
                {
                    startListening()
                },
                500
            )
        }
    }

    override fun onPartialResults(
        partialResults: Bundle?
    ) {
    }

    override fun onEvent(
        eventType: Int,
        params: Bundle?
    ) {
    }

    override fun onDestroy() {

        isListeningActive = false

        handler.removeCallbacksAndMessages(null)

        try {
            speechRecognizer.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            textToSpeech.stop()
            textToSpeech.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        super.onDestroy()
    }
}
