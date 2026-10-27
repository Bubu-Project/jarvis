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
    private var isProcessing = false // ताकि एक के बाद एक प्रोसेस हो
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
            apiClient = JarvisApiClient(this)
            sttClient = AssemblyAISTT(this)
            ttsClient = FishAudioTTS(this)
            
            createNotification()
            isListeningActive = true
            DebugLogger.log("SERVICE", "onCreate started - All clients initialized")
            
            setupSTT()
            setupTTS()
            
            // सबसे पहले सुनना शुरू करो
            startListening()
            
        } catch (e: Exception) {
            DebugLogger.log("SERVICE", "CRASH: ${e.message}")
        }
    }

    // 🔥 1. STT (सुनने) का सेटअप
    private fun setupSTT() {
        sttClient.setListener(object : AssemblyAISTT.TranscriptionListener {
            override fun onTranscript(text: String, isFinal: Boolean) {
                // 🔥 फिल्टर: सिर्फ 4 अक्षरों से बड़े टेक्स्ट को सुनेगा (Echo/Noise इग्नोर होगा)
                if (isFinal && text.isNotBlank() && text.length > 4 && !isProcessing) {
                    DebugLogger.log("STT", "Final Text: $text")
                    onUserSpoke(text)
                }
            }

            override fun onError(message: String) {
                DebugLogger.log("STT_ERROR", message)
                // अगर सॉकेट बंद हो जाए, तो 1.5 सेकंड बाद वापस चालू करने की कोशिश करे
                handler.postDelayed({
                    if (isListeningActive && !isProcessing) {
                        DebugLogger.log("SERVICE", "Reconnecting STT after error...")
                        sttClient.startListening()
                    }
                }, 1500)
            }
        })
    }

    // 🔥 2. TTS (बोलने) का सेटअप
    private fun setupTTS() {
        ttsClient.setListener(object : FishAudioTTS.TTSListener {
            override fun onStart() {
                DebugLogger.log("TTS", "Speaking started...")
            }

            override fun onDone() {
                DebugLogger.log("TTS", "Speaking finished.")
                isProcessing = false
                // 🔥 1.5 सेकंड का इंतज़ार ताकि स्पीकर की आवाज़ पूरी खत्म हो जाए
                handler.postDelayed({ startListening() }, 1500)
            }

            override fun onError(message: String) {
                DebugLogger.log("TTS_ERROR", message)
                isProcessing = false
                handler.postDelayed({ startListening() }, 1500)
            }
        })
    }

    // 🔥 3. सुनना शुरू करना
    private fun startListening() {
        if (!isListeningActive || isProcessing) return
        DebugLogger.log("SERVICE", "Listening started...")
        sttClient.startListening()
    }

    // 🔥 4. जब यूजर बोल ले
    private fun onUserSpoke(text: String) {
        isProcessing = true
        DebugLogger.log("SERVICE", "Processing User text...")
        
        // माइक को बंद करो ताकि जार्विस बोलते वक्त अपनी आवाज़ न सुने
        sttClient.stopListening() 

        if (isDeviceCommand(text)) {
            executeDeviceCommand(text)
            isProcessing = false
            startListening()
            return
        }

        apiClient.sendMessage(
            text = text,
            onResponse = { reply ->
                DebugLogger.log("SERVICE", "AI: $reply")
                speakResponse(reply)
            },
            onError = { error ->
                DebugLogger.log("SERVICE", "API Error: $error")
                isProcessing = false
                startListening() 
            }
        )
    }

    // 🔥 5. जवाब बोलना
    private fun speakResponse(responseText: String) {
        ttsClient.speak(responseText)
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
            .setContentText("Voice Assistant Active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isListeningActive = false
        handler.removeCallbacksAndMessages(null)
        try { sttClient.stopListening() } catch (_: Exception) {}
        try { ttsClient.stop() } catch (_: Exception) {}
        DebugLogger.log("SERVICE", "Service Destroyed")
    }
}
