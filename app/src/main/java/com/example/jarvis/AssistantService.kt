package com.example.jarvis

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException

class AssistantService : Service() {

    private lateinit var apiClient: JarvisApiClient
    private lateinit var actionExecutor: ActionExecutor
    
    private var mediaRecorder: MediaRecorder? = null
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
            actionExecutor = ActionExecutor(this)
            apiClient = JarvisApiClient(this)
            
            createNotification()
            startRecording()
            
        } catch (e: Exception) {
            DebugLogger.log("SERVICE", "CRASH: ${e.message}")
        }
    }

    private fun startRecording() {
        audioFile = File(cacheDir, "input_audio.wav")
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
            DebugLogger.log("SERVICE", "Recording started...")
            
            // 🔥 4 सेकंड बाद रिकॉर्डिंग अपने आप रुक जाएगी और API को जाएगी
            handler.postDelayed({ stopRecordingAndProcess() }, 4000)
            
        } catch (e: IOException) {
            DebugLogger.log("SERVICE", "Recording failed: ${e.message}")
        }
    }

    private fun stopRecordingAndProcess() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            DebugLogger.log("SERVICE", "Recording stopped. Sending to API...")
            
            audioFile?.let { file ->
                apiClient.sendAudio(file, 
                    onResponse = { replyAudio ->
                        playAudio(replyAudio)
                    },
                    onError = { error ->
                        DebugLogger.log("SERVICE", "API Error: $error")
                        startRecording() // Error aane par fir se record karo
                    }
                )
            }
        } catch (e: Exception) {
            DebugLogger.log("SERVICE", "Stop failed: ${e.message}")
        }
    }

    private fun playAudio(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    DebugLogger.log("SERVICE", "Playback finished. Listening again...")
                    handler.postDelayed({ startRecording() }, 1000)
                }
                prepare()
                start()
            }
            DebugLogger.log("SERVICE", "Playing Jarvis reply...")
        } catch (e: Exception) {
            DebugLogger.log("SERVICE", "Playback failed: ${e.message}")
            handler.postDelayed({ startRecording() }, 1000)
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
        try { mediaRecorder?.release() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
    }
}
