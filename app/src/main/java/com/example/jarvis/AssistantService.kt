package com.example.jarvis

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File

class AssistantService : Service() {

    private lateinit var apiClient: JarvisApiClient
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var audioRecorder: AudioRecorderHelper
    
    private var mediaPlayer: MediaPlayer? = null
    private var audioFile: File? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        try {
            apiClient = JarvisApiClient(this)
            actionExecutor = ActionExecutor(this) // 🔥 Commands wapas aagaye
            audioRecorder = AudioRecorderHelper(this)
            createNotification()
            startListening()
        } catch (e: Exception) {
            DebugLogger.log("SERVICE", "CRASH: ${e.message}")
        }
    }

    private fun startListening() {
        audioFile = File(cacheDir, "input_audio.wav")
        DebugLogger.log("SERVICE", "Listening started... (Speak now)")
        
        // 🔥 Jab tak chup nahi hoga, record karta rahega
        audioRecorder.startRecording(audioFile!!) {
            DebugLogger.log("SERVICE", "Silence detected. Sending to API...")
            sendToApi()
        }
    }

    private fun sendToApi() {
        audioFile?.let { file ->
            if (file.length() < 2000) { // Agar bahut chhoti file hai (sirf noise)
                startListening() 
                return
            }
            
            apiClient.sendAudio(file, 
                onResponse = { replyAudio ->
                    playAudio(replyAudio)
                },
                onError = { error ->
                    DebugLogger.log("SERVICE", "API Error: $error")
                    startListening() 
                }
            )
        }
    }

    private fun playAudio(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    DebugLogger.log("SERVICE", "Playback finished. Listening again...")
                    handler.postDelayed({ startListening() }, 500)
                }
                prepare()
                start()
            }
            DebugLogger.log("SERVICE", "Playing Jarvis reply...")
        } catch (e: Exception) {
            DebugLogger.log("SERVICE", "Playback failed: ${e.message}")
            handler.postDelayed({ startListening() }, 1000)
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
        handler.removeCallbacksAndMessages(null)
        try { audioRecorder.stopRecording() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
    }
}
