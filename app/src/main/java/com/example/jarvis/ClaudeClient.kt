package com.example.jarvis

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class ClaudeClient {

    private val client = OkHttpClient()
    private val apiKey = "YOUR_CLAUDE_API_KEY_HERE"

    interface ClaudeCallback {
        fun onSuccess(response: String)
        fun onFailure(error: String)
    }

    fun askClaude(userQuery: String, callback: ClaudeCallback) {
        val url = "https://anthropic.com"

        val jsonBody = JSONObject().apply {
            put("model", "claude-3-5-sonnet-20241022")
            put("max_tokens", 1024)
            put("system", "You are JARVIS from Iron Man. You are a loyal, witty, and extremely intelligent AI friend. Talk like a real supportive buddy, keep answers brief, futuristic, and helpful.")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userQuery)
                })
            })
        }

        val mediaType = "application/json".toMediaType()
        val body = jsonBody.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback.onFailure(e.message ?: "Unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        callback.onFailure("Unexpected code $response")
                        return
                    }

                    val responseData = response.body?.string()
                    if (responseData != null) {
                        try {
                            val jsonResponse = JSONObject(responseData)
                            val contentArray = jsonResponse.getJSONArray("content")
                            if (contentArray.length() > 0) {
                                val textResponse = contentArray.getJSONObject(0).getString("text")
                                callback.onSuccess(textResponse)
                            } else {
                                callback.onFailure("Empty content from Claude")
                            }
                        } catch (e: Exception) {
                            callback.onFailure(e.message ?: "JSON parsing error")
                        }
                    } else {
                        callback.onFailure("Null response body")
                    }
                }
            }
        })
    }
}
