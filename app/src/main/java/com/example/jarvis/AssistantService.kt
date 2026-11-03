package com.example.jarvis

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class AssistantService : Service() {

    private lateinit var apiClient: JarvisApiClient
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var sttClient: AssemblyAISTT
    private lateinit var ttsClient: FishAudioTTS

    private val handler = Handler(Looper.getMainLooper())
    private var isProcessing = false
    private var isListeningActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        try {
            actionExecutor = ActionExecutor(this)
            apiClient = JarvisApiClient(this)
            sttClient = AssemblyAISTT(this)
            ttsClient = FishAudioTTS(this)
            
            createNotification()
            isListeningActive = true
            setupSTT()
            setupTTS()
            startListening()
            
        } catch (e: Exception) {
            DebugLogger.log("SERVICE", "CRASH: ${e.message}")
        }
    }

    private fun setupSTT() {
        sttClient.setListener(object : AssemblyAISTT.TranscriptionListener {
            override fun onTranscript(text: String, isFinal: Boolean) {
                if (isFinal && text.isNotBlank() && text.length > 3 && !isProcessing) {
                    onUserSpoke(text)
                }
            }
            override fun onError(message: String) {
                handler.postDelayed({
                    if (isListeningActive && !isProcessing) sttClient.startListening()
                }, 1500)
            }
        })
    }

    private fun setupTTS() {
        ttsClient.setListener(object : FishAudioTTS.TTSListener {
            override fun onStart() {}
            override fun onDone() {
                isProcessing = false
                handler.postDelayed({ startListening() }, 1500)
            }
            override fun onError(message: String) {
                isProcessing = false
                handler.postDelayed({ startListening() }, 1500)
            }
        })
    }

    private fun startListening() {
        if (!isListeningActive || isProcessing) return
        sttClient.startListening()
    }

    private fun onUserSpoke(text: String) {
        isProcessing = true
        sttClient.stopListening() 

        if (isDeviceCommand(text)) {
            executeDeviceCommand(text)
            isProcessing = false
            handler.postDelayed({ startListening() }, 1000)
            return
        }

        apiClient.sendMessage(
            text = text,
            onResponse = { reply ->
                speakResponse(reply)
            },
            onError = { error ->
                isProcessing = false
                startListening() 
            }
        )
    }

    private fun speakResponse(responseText: String) {
        ttsClient.speak(responseText)
    }

    private fun isDeviceCommand(text: String): Boolean {
        val cmd = text.lowercase().replace(" ", "") 
        val keywords = listOf(
            "flashlight", "torch", "फ्लैशलाइट", "टॉर्च", "लाइट", "फ्लेशलाइट", "फ्लासलाइट", "टोर्च", 
            "call", "phone", "कॉल", "फोन", "मिलाओ", "डायल",
            "youtube", "play", "gana", "song", "गाना", "चलाओ", "यूट्यूब", "बजाओ", 
            "open", "kholo", "खोलो", "ओपन"
        )
        return keywords.any { cmd.contains(it) }
    }

    private fun executeDeviceCommand(text: String) {
        val cmd = text.lowercase().trim()
        val cmdNoSpace = cmd.replace(" ", "")

        when {
            cmdNoSpace.contains("flashlight") || cmdNoSpace.contains("torch") || cmdNoSpace.contains("फ्लैशलाइट") || cmdNoSpace.contains("लाइट") -> {
                if (cmd.contains("off") || cmd.contains("band") || cmd.contains("बंद")) {
                    actionExecutor.toggleFlashlight(false)
                    speakResponse("फ्लैशलाइट बंद।")
                } else {
                    actionExecutor.toggleFlashlight(true)
                    speakResponse("फ्लैशलाइट चालू।")
                }
            }
            cmdNoSpace.contains("call") || cmdNoSpace.contains("phone") || cmdNoSpace.contains("कॉल") || cmdNoSpace.contains("फोन") -> {
                var name = cmd
                val wordsToRemove = listOf("call", "karo", "phone", "to", "please", "कॉल", "फोन", "करो", "को", "कर", "मिलाओ")
                for (word in wordsToRemove) name = name.replace(word, "")
                name = name.trim()
                
                if (name.isNotBlank()) {
                    if (actionExecutor.callContact(name)) speakResponse("कॉलिंग $name")
                    else speakResponse("नंबर नहीं मिला।")
                }
            }
            cmdNoSpace.contains("youtube") || cmdNoSpace.contains("play") || cmdNoSpace.contains("gana") || cmdNoSpace.contains("गाना") || cmdNoSpace.contains("चलाओ") -> {
                val query = cmd.replace("play", "").replace("youtube", "").replace("song", "").replace("gana", "").replace("chalao", "").replace("यूट्यूब", "").replace("गाना", "").replace("चलाओ", "").trim()
                if (query.isNotBlank()) {
                    actionExecutor.playOnYoutube(query)
                    speakResponse("यूट्यूब चला रही हूँ।")
                }
            }
            cmd.startsWith("open ") || cmd.contains("kholo") || cmd.contains("खोलो") || cmd.contains("ओपन") -> {
                val app = cmd.replace("open", "").replace("kholo", "").replace("ओपन", "").replace("खोलो", "").replace("करो", "").trim()
                if (app.isNotBlank()) {
                    if (actionExecutor.openApp(app)) speakResponse("$app खोल रही हूँ।")
                }
            }
        }
    }

    private fun createNotification() {
        val channelId = "jarvis_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Jarvis Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis AI").setContentText("Active").setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isListeningActive = false
        try { sttClient.stopListening() } catch (_: Exception) {}
        try { ttsClient.stop() } catch (_: Exception) {}
    }
}
