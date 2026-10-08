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
import android.widget.Toast
import androidx.core.app.NotificationCompat

class AssistantService : Service() {

    private lateinit var geminiClient: GeminiLiveClient
    private lateinit var audioManager: GeminiAudioManager
    private lateinit var actionExecutor: ActionExecutor

    private val GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY

    private val handler = Handler(Looper.getMainLooper())

    private var isListeningActive = false
    private var isSessionActive = false

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
            createNotification()
            isListeningActive = true
            startGeminiSession()
        } catch (e: Exception) {
            android.util.Log.e("JARVIS_DEBUG", "CRASH: ${e.message}")
            Toast.makeText(this, "Crash: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startGeminiSession() {
        audioManager = GeminiAudioManager()
        audioManager.setListener(object : GeminiAudioManager.AudioListener {
            override fun onMicData(pcmBytes: ByteArray) {
                if (isSessionActive) {
                    geminiClient.sendAudio(pcmBytes)
                }
            }

            override fun onError(message: String) {
                android.util.Log.e("JARVIS_GEMINI_AUDIO", "Audio: $message")
            }
        })

        geminiClient = GeminiLiveClient(GEMINI_API_KEY, this)
        geminiClient.setListener(object : GeminiLiveClient.GeminiListener {
            override fun onReady() {
                android.util.Log.d("JARVIS_GEMINI", "Ready!")
                isSessionActive = true
                handler.post {
                    audioManager.startSpeaker()
                    audioManager.startMic()
                }
            }

            override fun onAudioReceived(pcmBytes: ByteArray) {
                audioManager.playAudio(pcmBytes)
            }

            override fun onUserTranscript(text: String) {
                android.util.Log.d("JARVIS_GEMINI", "User: $text")
            }

            override fun onAITranscript(text: String) {
                android.util.Log.d("JARVIS_GEMINI", "AI: $text")
                // Check karo agar device command hai toh
                if (isDeviceCommand(text)) {
                    handler.post { executeDeviceCommand(text) }
                }
            }

            override fun onInterrupted() {
                android.util.Log.d("JARVIS_GEMINI", "Interrupted - flushing")
                audioManager.flushPlayback()
            }

            override fun onError(message: String) {
                android.util.Log.e("JARVIS_GEMINI", "Error: $message")
            }

            override fun onDisconnected() {
                android.util.Log.d("JARVIS_GEMINI", "Disconnected")
                isSessionActive = false
            }
        })

        geminiClient.connect()
    }

    private fun isDeviceCommand(text: String): Boolean {
        val cmd = text.lowercase()
        val keywords = listOf(
            "flashlight", "torch", "call", "phone", "dial",
            "youtube", "play", "gana", "song", "open", "kholo", "battery"
        )
        return keywords.any { cmd.contains(it) }
    }

    private fun executeDeviceCommand(text: String) {
        val cmd = text.lowercase().trim()

        when {
            cmd.contains("flashlight") || cmd.contains("torch") -> {
                if (cmd.contains("off") || cmd.contains("band") || cmd.contains("bujha")) {
                    actionExecutor.toggleFlashlight(false)
                } else {
                    actionExecutor.toggleFlashlight(true)
                }
            }
            cmd.contains("call") || cmd.contains("phone") -> {
                val name = cmd
                    .replace("call", "").replace("karo", "")
                    .replace("phone", "").replace("to", "")
                    .replace("please", "").trim()
                if (name.isNotBlank()) actionExecutor.callContact(name)
            }
            cmd.contains("youtube") || cmd.contains("play") ||
            cmd.contains("gana") || cmd.contains("song") -> {
                val query = cmd
                    .replace("play", "").replace("on youtube", "")
                    .replace("youtube", "").replace("song", "")
                    .replace("gana", "").replace("chalao", "")
                    .replace("please", "").trim()
                if (query.isNotBlank()) actionExecutor.playOnYoutube(query)
            }
            cmd.startsWith("open ") || cmd.contains("kholo") -> {
                val app = cmd
                    .replace("open", "").replace("kholo", "")
                    .replace("please", "").trim()
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
            .setContentText("Gemini Live active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isListeningActive = false
        isSessionActive = false
        handler.removeCallbacksAndMessages(null)
        try { audioManager.stopAll() } catch (_: Exception) {}
        try { geminiClient.disconnect() } catch (_: Exception) {}
    }
}
