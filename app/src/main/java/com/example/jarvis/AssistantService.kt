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

    // 🔥 Gemini को हटा दिया गया है
    private lateinit var apiClient: JarvisApiClient
    private lateinit var actionExecutor: ActionExecutor

    private val handler = Handler(Looper.getMainLooper())
    private var isListeningActive = false

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
                SystemClock.elapsedRealtime() + 500,
                restartPendingIntent
            )
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        try {
            actionExecutor = ActionExecutor(this)
            apiClient = JarvisApiClient() // अपना नया API क्लाइंट इनिशियलाइज किया
            createNotification()
            isListeningActive = true
            DebugLogger.log("SERVICE", "onCreate started - Gemini Removed")
            
            startCustomJarvisSystem()
        } catch (e: Exception) {
            DebugLogger.log("SERVICE", "CRASH: ${e.message}")
        }
    }

    private fun startCustomJarvisSystem() {
        DebugLogger.log("SERVICE", "Starting Custom Jarvis System...")
        
        // ========================================================
        // 🚨 यहाँ तू अपना Deepgram या AssemblyAI (STT) शुरू करेगा
        // जो तेरी आवाज़ सुनकर टेक्स्ट बनाएगा और onUserSpoke() को भेजेगा
        // ========================================================
        
        simulateListening() // अभी के लिए यह डमी फंक्शन चला रहे हैं
    }

    // यह सिर्फ टेस्टिंग के लिए है। बाद में इसे STT से रिप्लेस करना।
    private fun simulateListening() {
        DebugLogger.log("MIC", "Listening...")
        val userText = "नमस्ते जार्विस" 
        onUserSpoke(userText)
    }

    // 🔥 यह फंक्शन तब कॉल होगा जब यूजर कुछ बोलेगा
    private fun onUserSpoke(text: String) {
        DebugLogger.log("SERVICE", "User said: $text")
        
        if (isDeviceCommand(text)) {
            handler.post { executeDeviceCommand(text) }
            return
        }

        // अपने कागल सर्वर (JarvisApiClient) को टेक्स्ट भेजो
        apiClient.sendMessage(
            text = text,
            onResponse = { reply ->
                DebugLogger.log("SERVICE", "AI: $reply")
                speakResponse(reply)
            },
            onError = { error ->
                DebugLogger.log("SERVICE", "API Error: $error")
            }
        )
    }

    // 🔥 यह फंक्शन जार्विस के जवाब को आवाज़ में बदलेगा
    private fun speakResponse(responseText: String) {
        DebugLogger.log("TTS", "Speaking: $responseText")
        
        // ========================================================
        // 🚨 यहाँ तू अपनी FishAudioTTS या ElevenLabsTTS लगाएगा
        // ========================================================
        
        /* 
        val tts = FishAudioTTS()
        tts.speak(responseText) { 
            handler.post { simulateListening() } // बोलने के बाद फिर सुनो
        }
        */
        
        // अभी के लिए 3 सेकंड बाद फिर से सुनना शुरू कर देंगे
        handler.postDelayed({ simulateListening() }, 3000) 
    }

    private fun isDeviceCommand(text: String): Boolean {
        val cmd = text.lowercase()
        val keywords = listOf("flashlight", "torch", "call", "phone", "dial", "youtube", "play", "gana", "song", "open", "kholo")
        return keywords.any { cmd.contains(it) }
    }

    private fun executeDeviceCommand(text: String) {
        val cmd = text.lowercase().trim()
        DebugLogger.log("CMD", "Executing: ${cmd.take(60)}")

        when {
            cmd.contains("flashlight") || cmd.contains("torch") -> {
                if (cmd.contains("off") || cmd.contains("band") || cmd.contains("bujha")) {
                    actionExecutor.toggleFlashlight(false)
                } else {
                    actionExecutor.toggleFlashlight(true)
                }
            }
            cmd.contains("call") || cmd.contains("phone") -> {
                val name = cmd.replace("call", "").replace("karo", "").replace("phone", "").replace("to", "").replace("please", "").trim()
                if (name.isNotBlank()) actionExecutor.callContact(name)
            }
            cmd.contains("youtube") || cmd.contains("play") || cmd.contains("gana") || cmd.contains("song") -> {
                val query = cmd.replace("play", "").replace("on youtube", "").replace("youtube", "").replace("song", "").replace("gana", "").replace("chalao", "").replace("please", "").trim()
                if (query.isNotBlank()) actionExecutor.playOnYoutube(query)
            }
            cmd.startsWith("open ") || cmd.contains("kholo") -> {
                val app = cmd.replace("open", "").replace("kholo", "").replace("please", "").trim()
                if (app.isNotBlank()) actionExecutor.openApp(app)
            }
        }
    }

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
            .setContentText("Custom Model Active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isListeningActive = false
        handler.removeCallbacksAndMessages(null)
        DebugLogger.log("SERVICE", "Service Destroyed")
    }
}
