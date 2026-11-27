package com.example.jarvis

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File

class AssistantService : Service() {
    private lateinit var apiClient: JarvisApiClient
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var systemActions: SystemActions
    private lateinit var commandRouter: CommandRouter
    private lateinit var audioRecorder: AudioRecorderHelper
    private lateinit var speaker: LocalSpeaker
    private lateinit var callHandler: PhoneCallHandler
    private lateinit var bargeIn: BargeInMonitor
    private lateinit var localRecognizer: LocalRecognizer

    private var lastInteraction = 0L
    private var mediaPlayer: MediaPlayer? = null
    private var audioFile: File? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var busy = false

    private var recording = false
    private var suppressNext = false
    private var pendingNotif: File? = null

    @Volatile
    private var callMode = false

    @Volatile
    private var gen = 0

    private val playQueue = java.util.ArrayDeque<File>()
    private var playing = false
    private var streamDone = true
    private var streamLogged = false
    private var bargeActive = false

    private val pendingSpeech = java.util.ArrayDeque<String>()

    private val poller = object : Runnable {
        override fun run() {
            if (!busy && !callMode) {
                apiClient.fetchNotification { file ->
                    if (file != null) {
                        if (busy || callMode) {
                            pendingNotif = file
                        } else {
                            playNotification(file)
                        }
                    }
                }
            }

            handler.postDelayed(this, 4000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()

        apiClient = JarvisApiClient(this)
        actionExecutor = ActionExecutor(this)
        systemActions = SystemActions(this)
        commandRouter = CommandRouter(actionExecutor, systemActions)

        audioRecorder = AudioRecorderHelper()
        speaker = LocalSpeaker(this)
        bargeIn = BargeInMonitor()

        localRecognizer = LocalRecognizer(this)
        localRecognizer.prepare()

        callHandler = PhoneCallHandler(
            context = this,
            audioRecorder = audioRecorder,
            apiClient = apiClient,
            localRecognizer = localRecognizer,
            speaker = speaker,

            onCallStarted = {
                callMode = true
                busy = true
                gen++

                apiClient.cancelCurrent()
                bargeIn.stop()
                bargeActive = false

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

        JarvisEvents.onMessage = { app, who, text ->
            handler.post {
                val clipped =
                    if (text.length > 160) text.take(160) else text

                announceLocal(
                    "सर, $who का $app आया है। $clipped"
                )
            }
        }

        createNotification()
        startListening()

        handler.postDelayed(poller, 4000)

        handler.postDelayed({
            reportPermissions()
        }, 3000)
    }

    private fun reportPermissions() {
        fun ok(permission: String): Boolean {
            return ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }

        val items = listOf(
            "Mic" to Manifest.permission.RECORD_AUDIO,
            "Phone-state" to Manifest.permission.READ_PHONE_STATE,
            "Call-log" to Manifest.permission.READ_CALL_LOG,
            "Contacts" to Manifest.permission.READ_CONTACTS,
            "Call-uthana" to Manifest.permission.ANSWER_PHONE_CALLS,
            "Call-karna" to Manifest.permission.CALL_PHONE,
            "SMS" to Manifest.permission.SEND_SMS
        )

        items.forEach { (name, perm) ->
            DebugLogger.log(
                "PERM",
                "$name: " + if (ok(perm)) "OK" else "NAHI MILI"
            )
        }

        try {
            val nm =
                getSystemService(NotificationManager::class.java)

            DebugLogger.log(
                "PERM",
                "DoNotDisturb access: " +
                        if (nm.isNotificationPolicyAccessGranted)
                            "OK"
                        else
                            "NAHI"
            )

            val listeners =
                NotificationManagerCompat.getEnabledListenerPackages(this)

            DebugLogger.log(
                "PERM",
                "Notification access: " +
                        if (listeners.contains(packageName))
                            "OK"
                        else
                            "NAHI"
            )

            DebugLogger.log(
                "PERM",
                "Modify settings: " +
                        if (Settings.System.canWrite(this))
                            "OK"
                        else
                            "NAHI"
            )
        } catch (_: Exception) {
        }

        DebugLogger.log(
            "PERM",
            "Hindi awaaz: " +
                    if (speaker.isReady()) "OK" else "NAHI"
        )
    }

    private fun startListening() {
        if (callMode) return

        bargeIn.stop()
        bargeActive = false

        busy = false

        playQueue.clear()
        playing = false
        streamDone = true

        if (pendingSpeech.isNotEmpty()) {
            busy = true
            drainSpeech()
            return
        }

        pendingNotif?.let { notification ->
            pendingNotif = null
            playNotification(notification)
            return
        }

        audioFile = File(
            cacheDir,
            "input_audio.wav"
        )

        DebugLogger.log(
            "SERVICE",
            "Listening... Speak now"
        )

        recording = true

        audioRecorder.startRecording(audioFile!!) {
            recording = false

            if (suppressNext) {
                suppressNext = false
            } else {
                DebugLogger.log(
                    "SERVICE",
                    "Silence detected. Sending to API..."
                )

                sendToApi()
            }
        }
    }

    private fun announceLocal(text: String) {
        pendingSpeech.addLast(text)

        if (!busy && !callMode) {
            if (recording) {
                suppressNext = true
                audioRecorder.stopRecording()
            }

            busy = true
            drainSpeech()
        }
    }

    private fun drainSpeech() {
        if (callMode) return

        val s = pendingSpeech.pollFirst()

        if (s == null) {
            startListening()
            return
        }

        speaker.speak(s) {
            drainSpeech()
        }
    }

    private fun sendToApi() {
        if (callMode) return

        audioFile?.let { file ->

            if (file.length() < 2000) {
                startListening()
                return
            }

            busy = true

            playQueue.clear()
            playing = false
            streamDone = false
            streamLogged = false

            val myGen = ++gen

            if (localRecognizer.isReady()) {

                localRecognizer.recognize(file) { text ->

                    if (myGen == gen) {

                        DebugLogger.log(
                            "LOCAL",
                            "Phone ne suna: $text"
                        )

                        if (!tryLocalCommand(text)) {
                            callServer(file, myGen)
                        }
                    }
                }

            } else {
                callServer(file, myGen)
            }
        }
    }

    private fun callServer(
        file: File,
        myGen: Int
    ) {
        apiClient.sendAudio(
            file,

            onResponse = { replyAudio, userText, action ->

                if (myGen == gen) {

                    lastInteraction =
                        System.currentTimeMillis()

                    DebugLogger.log(
                        "SERVICE",
                        "User said: $userText"
                    )

                    if (action != "none") {

                        val say =
                            commandRouter.execute(userText)

                        when {

                            say == null -> {
                                DebugLogger.log(
                                    "SERVICE",
                                    "Command samajh nahi aaya"
                                )

                                handler.postDelayed(
                                    {
                                        startListening()
                                    },
                                    500
                                )
                            }

                            say.isBlank() -> {
                                handler.postDelayed(
                                    {
                                        startListening()
                                    },
                                    500
                                )
                            }

                            else -> {
                                speaker.speak(say) {
                                    startListening()
                                }
                            }
                        }

                    } else {
                        playAudio(replyAudio)
                    }
                }
            },

            onStreamChunk = { chunk, userText ->

                if (myGen == gen) {

                    if (!streamLogged) {

                        streamLogged = true

                        lastInteraction =
                            System.currentTimeMillis()

                        DebugLogger.log(
                            "SERVICE",
                            "User said: $userText"
                        )

                        DebugLogger.log(
                            "SERVICE",
                            "Jarvis bolna shuru (streaming)"
                        )
                    }

                    enqueueChunk(chunk)
                }
            },

            onStreamEnd = {
                if (myGen == gen) {
                    onStreamFinished()
                }
            },

            onError = { error ->

                if (myGen == gen) {

                    if (error == "IGNORED") {

                        DebugLogger.log(
                            "SERVICE",
                            "Jarvis so raha hai (wake word nahi bola)"
                        )

                    } else {

                        DebugLogger.log(
                            "SERVICE",
                            "API Error: $error"
                        )
                    }

                    startListening()
                }
            },

            awake = isAwakeLocal(),

            onSleep = {
                lastInteraction = 0L
            }
        )
    }

    private fun isAwakeLocal(): Boolean {
        return System.currentTimeMillis() -
                lastInteraction < 120_000L
    }

    private val wakeWords = listOf(
        "जार्विस",
        "जारविस",
        "जार्विश",
        "जर्विस",
        "jarvis"
    )

    private fun isWakeToken(tok: String): Boolean {
        val t = tok
            .trim(',', '.', '।', '!', '?')
            .lowercase()

        if (t.length < 4) return false

        val first = t[0]
        val last = t[t.length - 1]

        if (first != 'ज' && first != 'j') return false

        if (
            last != 'स' &&
            last != 'श' &&
            last != 's' &&
            last != 'h'
        ) {
            return false
        }

        return wakeWords.any {
            fuzzyRatio(t, it) >= 0.55
        }
    }

    private fun splitWake(
        text: String
    ): Pair<Boolean, String> {

        val toks =
            text.trim()
                .split(" ")
                .filter { it.isNotBlank() }

        val n = toks.size

        val allowed =
            ((0 until minOf(3, n)) +
                    (maxOf(0, n - 2) until n))
                .toSortedSet()

        for (i in allowed) {

            if (isWakeToken(toks[i])) {

                return Pair(
                    true,
                    (
                        toks.take(i) +
                                toks.drop(i + 1)
                        ).joinToString(" ")
                )
            }

            if (
                i + 1 < n &&
                isWakeToken(
                    toks[i] + toks[i + 1]
                )
            ) {

                return Pair(
                    true,
                    (
                        toks.take(i) +
                                toks.drop(i + 2)
                        ).joinToString(" ")
                )
            }
        }

        return Pair(false, text.trim())
    }

    private fun tryLocalCommand(
        text: String
    ): Boolean {

        val raw = text.trim()

        if (
            raw.isBlank() ||
            raw.split(" ").size > 8
        ) {
            return false
        }

        val (woke, cleaned) =
            splitWake(raw)

        if (!woke && !isAwakeLocal()) {
            return false
        }

        if (
            cleaned.isBlank() ||
            !commandRouter.isSimple(cleaned)
        ) {
            return false
        }

        val say =
            commandRouter.execute(cleaned)
                ?: return false

        lastInteraction =
            System.currentTimeMillis()

        DebugLogger.log(
            "SERVICE",
            "Turant (phone par hi): $cleaned"
        )

        if (say.isBlank()) {

            handler.postDelayed(
                {
                    startListening()
                },
                400
            )

        } else {

            speaker.speak(say) {
                startListening()
            }
        }

        return true
    }

    private fun interruptSpeech() {

        DebugLogger.log(
            "SERVICE",
            "Beeech mein roka gaya: Jarvis chup"
        )

        gen++

        apiClient.cancelCurrent()

        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }

        playQueue.clear()
        playing = false
        streamDone = true

        speaker.stop()

        startListening()
    }

    private fun enqueueChunk(f: File) {

        if (callMode) return

        playQueue.addLast(f)

        if (!bargeActive) {

            bargeActive = true

            bargeIn.start {
                interruptSpeech()
            }
        }

        if (!playing) {
            playNext()
        }
    }

    private fun playNext() {

        val f = playQueue.pollFirst()

        if (f == null) {

            playing = false

            if (streamDone) {
                handler.postDelayed(
                    {
                        startListening()
                    },
                    500
                )
            }

            return
        }

        playing = true

        try {

            mediaPlayer?.release()

            mediaPlayer =
                MediaPlayer().apply {

                    setDataSource(
                        f.absolutePath
                    )

                    setOnCompletionListener {
                        playNext()
                    }

                    prepare()
                    start()
                }

        } catch (_: Exception) {
            playNext()
        }
    }

    private fun onStreamFinished() {

        streamDone = true

        if (
            !playing &&
            playQueue.isEmpty()
        ) {

            DebugLogger.log(
                "SERVICE",
                "Playback finished. Listening again..."
            )

            handler.postDelayed(
                {
                    startListening()
                },
                500
            )
        }
    }

    private fun playNotification(file: File) {

        DebugLogger.log(
            "SERVICE",
            "Jarvis task update bata raha hai"
        )

        busy = true

        if (recording) {
            suppressNext = true
            audioRecorder.stopRecording()
        }

        playAudio(file)
    }

    private fun playAudio(file: File) {

        try {

            mediaPlayer?.release()

            mediaPlayer =
                MediaPlayer().apply {

                    setDataSource(
                        file.absolutePath
                    )

                    setOnCompletionListener {

                        DebugLogger.log(
                            "SERVICE",
                            "Playback finished. Listening again..."
                        )

                        handler.postDelayed(
                            {
                                startListening()
                            },
                            500
                        )
                    }

                    prepare()
                    start()
                }

        } catch (_: Exception) {

            handler.postDelayed(
                {
                    startListening()
                },
                1000
            )
        }
    }

    private fun createNotification() {

        val channelId =
            "jarvis_channel"

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    channelId,
                    "Jarvis",
                    NotificationManager.IMPORTANCE_LOW
                )

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }

        startForeground(
            1,

            NotificationCompat.Builder(
                this,
                channelId
            )
                .setContentTitle("Jarvis AI")
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .build()
        )
    }

    override fun onDestroy() {

        super.onDestroy()

        handler.removeCallbacksAndMessages(null)

        JarvisEvents.onMessage = null

        try {
            callHandler.unregister()
        } catch (_: Exception) {
        }

        try {
            bargeIn.stop()
        } catch (_: Exception) {
        }

        try {
            speaker.shutdown()
        } catch (_: Exception) {
        }

        try {
            audioRecorder.stopRecording()
        } catch (_: Exception) {
        }

        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
    }
}
