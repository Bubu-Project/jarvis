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
    private lateinit var commandRouter: CommandRouter
    private lateinit var audioRecorder: AudioRecorderHelper
    private lateinit var speaker: LocalSpeaker
    private lateinit var callHandler: PhoneCallHandler
    private var mediaPlayer: MediaPlayer? = null
    private var audioFile: File? = null
    private val handler = Handler(Looper.getMainLooper())

    // busy = server ko bhej rahe hain ya Jarvis bol raha hai (tab notification poll nahi hoti)
    @Volatile private var busy = false
    private var recording = false
    private var suppressNext = false
    private var pendingNotif: File? = null

    // callMode = phone ring ho raha hai ya call chal rahi hai: tab Jarvis normal sunna band rakhta hai
    @Volatile private var callMode = false

    // Streaming: vaakya ke tukde queue mein aate hain aur ek ke baad ek bajte hain
    private val playQueue = java.util.ArrayDeque<File>()
    private var playing = false
    private var streamDone = true
    private var streamLogged = false

    // Har 4 second mein server se poochta hai: koi agent ka kaam pura hua?
    private val poller = object : Runnable {
        override fun run() {
            if (!busy && !callMode) {
                apiClient.fetchNotification { file ->
                    if (file != null) {
                        if (busy || callMode) pendingNotif = file else playNotification(file)
                    }
                }
            }
            handler.postDelayed(this, 4000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        apiClient = JarvisApiClient(this)
        actionExecutor = ActionExecutor(this)
        commandRouter = CommandRouter(actionExecutor)
        audioRecorder = AudioRecorderHelper()
        speaker = LocalSpeaker(this)
        callHandler = PhoneCallHandler(
            context = this,
            audioRecorder = audioRecorder,
            apiClient = apiClient,
            speaker = speaker,
            onCallStarted = {
                callMode = true
                busy = true
                if (recording) {
                    suppressNext = true
                    audioRecorder.stopRecording()
                }
            },
            onCallEnded = {
                callMode = false
                startListening()
            }
        )
        callHandler.register()
        createNotification()
        startListening()
        handler.postDelayed(poller, 4000)
    }

    private fun startListening() {
        if (callMode) return   // call ke dauran normal sunna band
        busy = false
        playQueue.clear()
        playing = false
        streamDone = true
        pendingNotif?.let { n ->
            pendingNotif = null
            playNotification(n)
            return
        }
        audioFile = File(cacheDir, "input_audio.wav")
        DebugLogger.log("SERVICE", "Listening... Speak now")
        recording = true
        audioRecorder.startRecording(audioFile!!) {
            recording = false
            if (suppressNext) {
                // notification / call ke liye recording rok di gayi thi
                suppressNext = false
            } else {
                DebugLogger.log("SERVICE", "Silence detected. Sending to API...")
                sendToApi()
            }
        }
    }

    private fun sendToApi() {
        if (callMode) return
        audioFile?.let { file ->
            if (file.length() < 2000) { startListening(); return }
            busy = true
            playQueue.clear()
            playing = false
            streamDone = false
            streamLogged = false
            apiClient.sendAudio(file,
                onResponse = { replyAudio, userText, action ->
                    DebugLogger.log("SERVICE", "User said: $userText")
                    if (action != "none") {
                        // Server ne phone command bataya: app khud chalayega
                        val say = commandRouter.execute(userText)
                        when {
                            say == null -> playAudio(replyAudio)          // samajh nahi aaya: server ka "ठीक है।"
                            say.isBlank() -> handler.postDelayed({ startListening() }, 1500)
                            else -> speaker.speak(say) { startListening() }
                        }
                    } else {
                        playAudio(replyAudio)
                    }
                },
                onStreamChunk = { chunk, userText ->
                    if (!streamLogged) {
                        streamLogged = true
                        DebugLogger.log("SERVICE", "User said: $userText")
                        DebugLogger.log("SERVICE", "Jarvis bolna shuru (streaming)")
                    }
                    enqueueChunk(chunk)
                },
                onStreamEnd = { onStreamFinished() },
                onError = { error ->
                    if (error == "IGNORED") {
                        DebugLogger.log("SERVICE", "Jarvis so raha hai (wake word nahi bola)")
                    } else {
                        DebugLogger.log("SERVICE", "API Error: $error")
                    }
                    startListening()
                }
            )
        }
    }

    // ---------- Streaming playback ----------
    private fun enqueueChunk(f: File) {
        if (callMode) return
        playQueue.addLast(f)
        if (!playing) playNext()
    }

    private fun playNext() {
        val f = playQueue.pollFirst()
        if (f == null) {
            playing = false
            // sab bajh chuka aur server ka jawab bhi khatam: dobara suno
            if (streamDone) handler.postDelayed({ startListening() }, 500)
            return
        }
        playing = true
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { playNext() }
                prepare(); start()
            }
        } catch (e: Exception) {
            playNext()
        }
    }

    private fun onStreamFinished() {
        streamDone = true
        if (!playing && playQueue.isEmpty()) {
            DebugLogger.log("SERVICE", "Playback finished. Listening again...")
            handler.postDelayed({ startListening() }, 500)
        }
    }

    private fun playNotification(file: File) {
        DebugLogger.log("SERVICE", "Jarvis task update bata raha hai")
        busy = true
        if (recording) {
            suppressNext = true
            audioRecorder.stopRecording()
        }
        playAudio(file)
    }

    // Ek poori awaaz bajane ke liye (notification, tasks ka jawab)
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
        try { callHandler.unregister() } catch (_: Exception) {}
        try { speaker.shutdown() } catch (_: Exception) {}
        try { audioRecorder.stopRecording() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
    }
}
