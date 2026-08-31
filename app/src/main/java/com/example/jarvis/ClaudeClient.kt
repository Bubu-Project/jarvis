package com.example.jarvis

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object ClaudeClient {

    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private val client = OkHttpClient()

    fun ask(userText: String, onReply: (String) -> Unit) {
        val apiKey = BuildConfig.ANTHROPIC_API_KEY
        if (apiKey.isBlank()) {
            onReply("I don't have an API key set up yet.")
            return
        }

        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", userText)
        )

        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 300)
            put("system", "You are Jarvis, a concise voice assistant. Keep replies short since they'll be spoken aloud.")
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onReply("Sorry, I couldn't reach the server.")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val content = json.optJSONArray("content")
                val text = content?.optJSONObject(0)?.optString("text") ?: "Sorry, I didn't get a reply."
                onReply(text)
            }
        })
    }
}
