package com.example.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class JarvisApiClient(private val context: Context) {
    // ⚠️ Kaggle restart par ye URL badal jata hai, naya URL yahan daalo
    private val API_URL = "https://footwork-upscale-oxidant.ngrok-free.dev/chat_voice"
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun sendAudio(audioFile: File, onResponse: (File, String) -> Unit, onError: (String) -> Unit) {
        val requestBody = audioFile.asRequestBody("audio/wav".toMediaTypeOrNull())
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", audioFile.name, requestBody)
            .build()
        val request = Request.Builder()
            .url(API_URL)
            .post(multipartBody)
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { onError(e.message ?: "Unknown") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        val body = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                        handler.post { onError("Server Error: ${response.code} $body") }
                        return
                    }
                    try {
                        // Server Hindi text URL-encode karke bhejta hai, yahan decode hota hai
                        val rawText = response.header("X-User-Text") ?: ""
                        val userText = try {
                            URLDecoder.decode(rawText, "UTF-8")
                        } catch (e: Exception) { rawText }

                        val outputFile = File(context.cacheDir, "jarvis_reply.mp3")
                        val inputStream = response.body?.byteStream()
                        val outputStream = FileOutputStream(outputFile)
                        inputStream?.copyTo(outputStream)
                        outputStream.close()
                        handler.post { onResponse(outputFile, userText) }
                    } catch (e: Exception) {
                        handler.post { onError("Save Error: ${e.message}") }
                    }
                }
            }
        })
    }
}
