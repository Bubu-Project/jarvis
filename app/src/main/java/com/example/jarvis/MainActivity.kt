package com.example.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val permissionCode = 100
    private lateinit var statusText: TextView
    private lateinit var debugText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        debugText = findViewById(R.id.debugText)

        DebugLogger.setListener { msg ->
            runOnUiThread { debugText.append("$msg\n") }
        }

        requestAllPermissions()

        findViewById<Button>(R.id.startButton).setOnClickListener {
            val intent = Intent(this, AssistantService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            statusText.text = "Jarvis is running..."
            DebugLogger.log("JARVIS_APP", "Service started")
            
            // 🔥 टेस्ट API कॉल (यहाँ भी /chat जोड़ें)
            // अगर यह टेस्ट कोड हटाना चाहो तो हटा सकते हो, लेकिन अभी के लिए सही कर दे रहे हैं
            val testApiUrl = "https://footwork-upscale-oxidant.ngrok-free.dev/chat"
            val apiClient = okhttp3.OkHttpClient()
            val json = org.json.JSONObject().put("text", "नमस्ते जार्विस, तुम कौन हो?")
            val body = okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), json.toString())
            val request = okhttp3.Request.Builder()
                .url(testApiUrl)
                .post(body)
                .addHeader("ngrok-skip-browser-warning", "true")
                .build()

            apiClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    DebugLogger.log("TEST_API", "❌ API Error: ${e.message}")
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (response.isSuccessful) {
                        DebugLogger.log("TEST_API", "✅ API Reply: ${response.body?.string()}")
                    } else {
                        DebugLogger.log("TEST_API", "❌ API Error: Server Error: ${response.code}")
                    }
                }
            })
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            val intent = Intent(this, AssistantService::class.java)
            stopService(intent)
            statusText.text = "Jarvis is not running."
            DebugLogger.log("JARVIS_APP", "Service stopped")
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionCode)
        }
    }
}
