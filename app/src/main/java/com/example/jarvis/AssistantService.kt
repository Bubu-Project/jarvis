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
            
            startListening()
            
        } catch (e: Exception) {
            DebugLogger.log("SERVICE", "CRASH: ${e.message}")
        }
    }

    private fun setupSTT() {
        sttClient.setListener(object : AssemblyAISTT.TranscriptionListener {
            override fun onTranscript(text: String, isFinal: Boolean) {
                if (isFinal && text.isNotBlank() && text.length > 3 && !isProcessing) {
                    DebugLogger.log("STT", "Final Text: $text")
                    onUserSpoke(text)
                }
            }

            override fun onError(message: String) {
                DebugLogger.log("STT_ERROR", message)
                handler.postDelayed({
                    if (isListeningActive && !isProcessing) {
                        DebugLogger.log("SERVICE", "Reconnecting STT after error...")
                        sttClient.startListening()
                    }
                }, 1500)
            }
        })
    }

    private fun setupTTS() {
        ttsClient.setListener(object : FishAudioTTS.TTSListener {
            override fun onStart() {
                DebugLogger.log("TTS", "Speaking started...")
            }

            override fun onDone() {
                DebugLogger.log("TTS", "Speaking finished.")
                isProcessing = false
                handler.postDelayed({ startListening() }, 1500)
            }

            override fun onError(message: String) {
                DebugLogger.log("TTS_ERROR", message)
                isProcessing = false
                handler.postDelayed({ startListening() }, 1500)
            }
        })
    }

    private fun startListening() {
        if (!isListeningActive || isProcessing) return
        DebugLogger.log("SERVICE", "Listening started...")
        sttClient.startListening()
    }

    private fun onUserSpoke(text: String) {
        isProcessing = true
        DebugLogger.log("SERVICE", "Processing User text...")
        
        sttClient.stopListening() 

        // कमांड चेक करें
        if (isDeviceCommand(text)) {
            executeDeviceCommand(text)
            isProcessing = false
            handler.postDelayed({ startListening() }, 1000)
            return
        }

        // अगर नॉर्मल सवाल है, तो API पर भेजें
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

    private fun speakResponse(responseText: String) {
        ttsClient.speak(responseText)
    }

    // ================= 🔥 हिंदी कमांड्स का लॉजिक =================
    private fun isDeviceCommand(text: String): Boolean {
        val cmd = text.lowercase().replace(" ", "") // स्पेस हटा दिया ताकि "फ्लेश लाइट" और "फ्लैशलाइट" दोनों मैच हों
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
        DebugLogger.log("CMD", "Executing: ${cmd.take(60)}")

        when {
            // 🔦 फ्लैशलाइट
            cmdNoSpace.contains("flashlight") || cmdNoSpace.contains("torch") || 
            cmdNoSpace.contains("फ्लैशलाइट") || cmdNoSpace.contains("फ्लेशलाइट") || 
            cmdNoSpace.contains("फ्लासलाइट") || cmdNoSpace.contains("टॉर्च") || cmdNoSpace.contains("लाइट") -> {
                if (cmd.contains("off") || cmd.contains("band") || cmd.contains("बंद") || cmd.contains("बुझा")) {
                    actionExecutor.toggleFlashlight(false)
                    speakResponse("फ्लैशलाइट बंद कर दी गई है।")
                } else {
                    actionExecutor.toggleFlashlight(true)
                    speakResponse("फ्लैशलाइट चालू कर दी गई है।")
                }
            }
            // 📞 कॉल
            cmdNoSpace.contains("call") || cmdNoSpace.contains("phone") || cmdNoSpace.contains("कॉल") || cmdNoSpace.contains("फोन") -> {
                var name = cmd
                val wordsToRemove = listOf("call", "karo", "phone", "to", "please", "कॉल", "फोन", "करो", "को", "कर", "मिलाओ", "डायल")
                for (word in wordsToRemove) {
                    name = name.replace(word, "")
                }
                name = name.trim()
                
                if (name.isNotBlank()) {
                    actionExecutor.callContact(name)
                    speakResponse("कॉलिंग $name")
                } else {
                    speakResponse("मुझे नाम समझ नहीं आया, कृपया दोबारा बोलें।")
                }
            }
            // 📺 यूट्यूब
            cmdNoSpace.contains("youtube") || cmdNoSpace.contains("play") || cmdNoSpace.contains("gana") || cmdNoSpace.contains("song") || 
            cmdNoSpace.contains("गाना") || cmdNoSpace.contains("चलाओ") || cmdNoSpace.contains("यूट्यूब") -> {
                val query = cmd
                    .replace("play", "").replace("on youtube", "").replace("youtube", "").replace("song", "").replace("gana", "").replace("chalao", "").replace("please", "")
                    .replace("यूट्यूब", "").replace("पर", "").replace("पे", "").replace("गाना", "").replace("चलाओ", "").replace("चला", "").trim()
                if (query.isNotBlank()) {
                    actionExecutor.playOnYoutube(query)
                    speakResponse("यूट्यूब पर $query चला रही हूँ।")
                }
            }
            // 📱 ऐप खोलना
            cmd.startsWith("open ") || cmd.contains("kholo") || cmd.contains("खोलो") || cmd.contains("ओपन") -> {
                val app = cmd
                    .replace("open", "").replace("kholo", "").replace("please", "")
                    .replace("ओपन", "").replace("खोलो", "").replace("करो", "").trim()
                if (app.isNotBlank()) {
                    actionExecutor.openApp(app)
                    speakResponse("$app खोल रही हूँ।")
                }
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
