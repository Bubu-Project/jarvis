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
                    DebugLogger.log("SERVICE", "User said: $userText")
                    if (checkAndExecuteCommand(userText)) {
                        // Command chala, LLM ki awaaz skip karke dobara suno
                        DebugLogger.log("SERVICE", "Command executed")
                        handler.postDelayed({ startListening() }, 1500)
                    } else {
                        playAudio(replyAudio)
                    }
                },
                onError = { error ->
                    DebugLogger.log("SERVICE", "API Error: $error")
                    startListening()
                }
            )
        }
    }

    // true = command mila aur chala, false = normal baat-cheet
    private fun checkAndExecuteCommand(text: String): Boolean {
        val cmd = text.lowercase().trim()
        if (cmd.isBlank()) return false

        fun has(vararg w: String) = w.any { cmd.contains(it) }
        fun strip(vararg w: String): String {
            var r = cmd
            w.forEach { r = r.replace(it, "") }
            return r.trim()
        }

        when {
            has("flashlight", "flash", "torch", "फ्लैशलाइट", "फ्लैश", "फ़्लैश", "टॉर्च", "टार्च") -> {
                actionExecutor.toggleFlashlight(!has("off", "बंद", "बन्द", "ऑफ", "बुझा"))
            }
            has("youtube", "यूट्यूब", "गाना", "play", "प्ले") -> {
                val q = strip("youtube", "यूट्यूब", "play", "प्ले", "पर", "on", "चलाओ", "चला", "सुनाओ", "खोलो")
                if (q.isBlank()) actionExecutor.openApp("youtube") else actionExecutor.playOnYoutube(q)
            }
            has("camera", "कैमरा") -> actionExecutor.openCamera()
            has("call", "कॉल", "फोन", "फ़ोन") -> {
                val name = strip("call", "कॉल", "फोन", "फ़ोन", "को", "करो", "कर", "लगाओ")
                actionExecutor.callContact(name)
            }
            has("message", "मैसेज", "sms") -> {
                val key = listOf("message", "मैसेज", "sms").first { cmd.contains(it) }
                val name = cmd.substringBefore(key).replace("को", "").replace("to", "").trim()
                val msg = cmd.substringAfter(key)
                    .replace("भेजो", "").replace("send", "").replace("कि", "").trim()
                if (name.isNotBlank() && msg.isNotBlank()) actionExecutor.sendSms(name, msg)
            }
            has("open", "खोलो", "खोल") -> {
                actionExecutor.openApp(strip("open", "खोलो", "खोल", "ऐप", "app"))
            }
            else -> return false
        }
        return true
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
        startForeground(
            1,
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("Jarvis AI")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { audioRecorder.stopRecording() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
    }
}
