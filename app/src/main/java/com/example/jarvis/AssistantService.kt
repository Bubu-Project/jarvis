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
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        apiClient = JarvisApiClient(this)
        actionExecutor = ActionExecutor(this)
        audioRecorder = AudioRecorderHelper()
        createNotification()
        startListening()
    }

    private fun startListening() {
        audioFile = File(cacheDir, "input_audio.wav")
        DebugLogger.log("SERVICE", "Listening... Speak now")
        audioRecorder.startRecording(audioFile!!) {
            DebugLogger.log("SERVICE", "Silence detected. Sending to API...")
            sendToApi()
        }
    }

    private fun sendToApi() {
        audioFile?.let { file ->
            if (file.length() < 2000) { startListening(); return }
            apiClient.sendAudio(file, 
                onResponse = { replyAudio, userText ->
                    // 🔥 यहाँ Commands चेक होंगे
                    checkAndExecuteCommand(userText)
                    playAudio(replyAudio)
                },
                onError = { error ->
                    DebugLogger.log("SERVICE", "API Error: $error")
                    startListening() 
                }
            )
        }
    }

    private fun checkAndExecuteCommand(text: String) {
        val cmd = text.lowercase()
        if (cmd.contains("flashlight") || cmd.contains("फ्लैशलाइट") || cmd.contains("टॉर्च")) {
            actionExecutor.toggleFlashlight(!cmd.contains("off") && !cmd.contains("बंद"))
        } else if (cmd.contains("youtube") || cmd.contains("गाना") || cmd.contains("play")) {
            actionExecutor.playOnYoutube(cmd.replace("play", "").replace("youtube", "").trim())
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
                prepare(); start()
            }
        } catch (e: Exception) { handler.postDelayed({ startListening() }, 1000) }
    }

    private fun createNotification() {
        val channelId = "jarvis_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Jarvis", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        startForeground(1, NotificationCompat.Builder(this, channelId).setContentTitle("Jarvis AI").setSmallIcon(android.R.drawable.ic_btn_speak_now).build())
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { audioRecorder.stopRecording() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
    }
}
