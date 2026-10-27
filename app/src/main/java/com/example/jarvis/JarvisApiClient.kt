package com.example.jarvis

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class JarvisApiClient {
    // 🔥 यहाँ तेरा Ngrok URL डाला है
    private val API_URL = "https://footwork-upscale-oxidant.ngrok-free.dev/chat"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
        
    private val handler = Handler(Looper.getMainLooper())

    fun sendMessage(text: String, onResponse: (String) -> Unit, onError: (String) -> Unit) {
        val json = JSONObject()
        json.put("text", text)

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(API_URL)
            .post(body)
            .build()

        DebugLogger.log("JARVIS_API", "Sending: $text")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { 
                    DebugLogger.log("JARVIS_API", "Error: ${e.message}")
                    onError(e.message ?: "Unknown Error") 
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        handler.post { onError("Server Error: ${response.code}") }
                        return
                    }
                    val responseData = response.body?.string()
                    try {
                        val jsonObject = JSONObject(responseData ?: "")
                        val reply = jsonObject.getString("response")
                        DebugLogger.log("JARVIS_API", "Received: $reply")
                        handler.post { onResponse(reply) }
                    } catch (e: Exception) {
                        handler.post { onError("Parse Error: ${e.message}") }
                    }
                }
            }
        })
    }
}
