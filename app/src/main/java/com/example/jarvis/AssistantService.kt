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
import androidx.core.app.NotificationCompat
import java.util.Locale

class AssistantService : Service(), RecognitionListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var audioManager: AudioManager
    private lateinit var actionExecutor: ActionExecutor
    
    private var isAwake = false 
    private var isListeningActive = false

    override fun onCreate() {
        super.onCreate()
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        actionExecutor = ActionExecutor(this) 

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.US
            }
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(this)

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        startForegroundServiceNotification()
        setupAudioFocus()

        isListeningActive = true
        startListening()
    }

    private fun setupAudioFocus() {
        audioManager.requestAudioFocus(
            { focusChange ->
                when (focusChange) {
                    // YouTube chalte hi mic listen karna band kar dega taaki lag na ho
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        isListeningActive = false
                        speechRecognizer.stopListening()
                    }
                    // YouTube band hote hi wapas background listening chalu
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
        if (!isListeningActive) return
        Handler(Looper.getMainLooper()).post {
            try {
                speechRecognizer.startListening(recognizerIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val spokenText = matches[0].lowercase(Locale.getDefault()).trim()

            if (!isAwake) {
                // Pehle sirf "Jarvis" word check hoga
                if (spokenText.contains("jarvis")) {
                    isAwake = true
                    
                    // Jarvis bolega "Yes Sir"
                    textToSpeech.speak("Yes Sir", TextToSpeech.QUEUE_FLUSH, null, "JarvisTTS")
                    
                    // 1.5 second ka delay taaki Jarvis apni khud ki awaaz na sun le mic mein
                    Handler(Looper.getMainLooper()).postDelayed({
                        startListening() 
                    }, 1500)
                } else {
                    startListening()
                }
            } else {
                // "Yes Sir" bolne ke baad wala main command yahan chalega
                executeVoiceCommand(spokenText)
                isAwake = false
                startListening()
            }
        } else {
            startListening()
        }
    }

    private fun executeVoiceCommand(command: String) {
        when {
            command.contains("turn on flashlight") || command.contains("torch on") -> {
                actionExecutor.toggleFlashlight(true)
            }
            command.contains("turn off flashlight") || command.contains("torch off") -> {
                actionExecutor.toggleFlashlight(false)
            }
            command.startsWith("open ") -> {
                val appName = command.replace("open ", "").trim()
                actionExecutor.openApp(appName)
            }
            command.startsWith("call ") -> {
                val contactName = command.replace("call ", "").trim()
                actionExecutor.callContact(contactName)
            }
            command.contains("play") && command.contains("on youtube") -> {
                val query = command.replace("play", "").replace("on youtube", "").trim()
                actionExecutor.playOnYoutube(query)
            }
        }
    }

    override fun onError(error: Int) {
        startListening() // Error aane par loop break nahi hoga, chalta rahega
    }

    private fun startForegroundServiceNotification() {
        val channelId = "jarvis_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Jarvis Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis Active")
            .setContentText("Listening for 'Jarvis' in background...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isListeningActive = false
        speechRecognizer.destroy()
        textToSpeech.shutdown()
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
