package com.example.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.SEND_SMS
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val PERMISSION_REQUEST_CODE = 101

    private var testRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        startButton.setOnClickListener {
            if (hasAllPermissions()) {
                startService(Intent(this, AssistantService::class.java))
                statusText.text = "Jarvis is listening... say 'Jarvis' followed by a command."
            } else {
                requestPermissions()
            }
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, AssistantService::class.java))
            statusText.text = "Jarvis stopped."
        }

        if (!hasAllPermissions()) {
            requestPermissions()
        }

        // =====================================================
        // TEMPORARY TEST BUTTON
        // Ye button seedha Activity (foreground) se mic test karta hai,
        // Service use kiye bina - taaki pata chale problem Service
        // context ki hai ya kuch aur.
        // =====================================================

        val testButton = Button(this)
        testButton.text = "TEST MIC (Foreground)"
        testButton.setBackgroundColor(android.graphics.Color.RED)
        testButton.setTextColor(android.graphics.Color.WHITE)

        val rootView = statusText.parent as ViewGroup

        val testButtonParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        testButtonParams.topMargin = 40

        rootView.addView(testButton, testButtonParams)

        testButton.setOnClickListener {

            if (!hasAllPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }

            Toast.makeText(this, "Starting foreground mic test...", Toast.LENGTH_SHORT).show()

            try {
                testRecognizer?.destroy()
            } catch (_: Exception) {}

            testRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            testRecognizer?.setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    Toast.makeText(this@MainActivity, "TEST: READY FOR SPEECH", Toast.LENGTH_SHORT).show()
                }

                override fun onBeginningOfSpeech() {
                    Toast.makeText(this@MainActivity, "TEST: BEGINNING OF SPEECH", Toast.LENGTH_SHORT).show()
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Toast.makeText(this@MainActivity, "TEST: END OF SPEECH", Toast.LENGTH_SHORT).show()
                }

                override fun onError(error: Int) {
                    Toast.makeText(this@MainActivity, "TEST: ERROR CODE $error", Toast.LENGTH_LONG).show()
                }

                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull() ?: "(empty)"
                    Toast.makeText(this@MainActivity, "TEST RESULT: $text", Toast.LENGTH_LONG).show()
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
            }

            try {
                testRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "TEST CRASH: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }
}
