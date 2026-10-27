package com.example.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class JarvisApiClient(private val context: Context) {
    
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun sendMessage(text: String, onResponse: (String) -> Unit, onError: (String) -> Unit) {
        // तेरा पक्का वाला URL
        val apiUrl = "https://footwork-upscale-oxidant.ngrok-free.dev/chat" 

        val json = JSONObject().put("text", text)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .addHeader("ngrok-skip-browser-warning", "true") // 🔥 यह 404 एरर हटाएगा
            .build()

        DebugLogger.log("JARVIS_API", "Sending to: $apiUrl")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { onError(e.message ?: "Unknown Error") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        handler.post { onError("Server Error: ${response.code}") }
                        return
                    }
                    try {
                        val jsonObject = JSONObject(response.body?.string() ?: "")
                        handler.post { onResponse(jsonObject.getString("response")) }
                    } catch (e: Exception) {
                        handler.post { onError("Parse Error: ${e.message}") }
                    }
                }
            }
        })
    }
}
