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

    // 🔥 यहाँ अपनी STT और TTS क्लासेस को इनिशियलाइज करना है
    private lateinit var sttClient: AssemblyAISTT // या जो भी तेरा STT क्लास है
    private lateinit var ttsClient: FishAudioTTS  // या ElevenLabsTTS

    private val handler = Handler(Looper.getMainLooper())
    private var isListeningActive = false
    private var isProcessing = false // ताकि एक के बाद एक प्रोसेस हो

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
            apiClient = JarvisApiClient(this)
            
            // 🚨 यहाँ तू अपनी STT और TTS को सेट करेगा
            // उदाहरण: sttClient = AssemblyAISTT(this)
            // उदाहरण: ttsClient = FishAudioTTS(this)
            
            createNotification()
            isListeningActive = true
            DebugLogger.log("SERVICE", "onCreate started - Gemini Removed")
            
            // सबसे पहले सुनना शुरू करो
            startListening()
            
        } catch (e: Exception) {
            DebugLogger.log("SERVICE", "CRASH: ${e.message}")
        }
    }

    // 🔥 1. सुनने का फंक्शन (STT)
    private fun startListening() {
        if (!isListeningActive || isProcessing) return
        
        DebugLogger.log("SERVICE", "Listening started...")
        
        // 🚨 यहाँ अपना STT शुरू करने वाला कोड डाल
        /* 
        sttClient.startListening { recognizedText ->
            if (recognizedText.isNotBlank()) {
                onUserSpoke(recognizedText)
            }
        }
        */
        
        // फिलहाल टेस्टिंग के लिए डमी टेक्स्ट
        val dummyText = "नमस्ते जार्विस, तुम कौन हो?"
        handler.postDelayed({ onUserSpoke(dummyText) }, 2000) 
    }

    // 🔥 2. जब यूजर बोल ले (टेक्स्ट मिल जाए)
    private fun onUserSpoke(text: String) {
        isProcessing = true
        DebugLogger.log("SERVICE", "User said: $text")
        
        // अगर कोई डिवाइस कमांड है (जैसे फ्लैशलाइट ऑन करना)
        if (isDeviceCommand(text)) {
            executeDeviceCommand(text)
            isProcessing = false
            startListening()
            return
        }

        // नहीं तो सर्वर को भेजो
        apiClient.sendMessage(
            text = text,
            onResponse = { reply ->
                DebugLogger.log("SERVICE", "AI: $reply")
                speakResponse(reply)
            },
            onError = { error ->
                DebugLogger.log("SERVICE", "API Error: $error")
                isProcessing = false
                startListening() // एरर आने पर फिर से सुनना शुरू करो
            }
        )
    }

    // 🔥 3. जवाब को बोलना (TTS)
    private fun speakResponse(responseText: String) {
        DebugLogger.log("TTS", "Speaking: $responseText")
        
        // 🚨 यहाँ अपना TTS बोलने वाला कोड डाल
        /* 
        ttsClient.speak(responseText) {
            // जब बोलना खत्म हो जाए, तो फिर से सुनना शुरू करो
            handler.postDelayed({
                isProcessing = false
                startListening()
            }, 500)
        }
        */
        
        // फिलहाल टेस्टिंग के लिए 3 सेकंड बाद फिर से सुनने लगेगा
        handler.postDelayed({
            isProcessing = false
            startListening()
        }, 3000)
    }

    // ================= डिवाइस कमांड्स का लॉजिक =================
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

    // ================= नोटिफिकेशन =================
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
