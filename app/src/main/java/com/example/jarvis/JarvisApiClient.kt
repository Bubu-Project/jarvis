package com.example.jarvis

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class JarvisApiClient(private val context: android.content.Context) {

    private val API_URL = "https://footwork-upscale-oxidant.ngrok-free.dev/chat_voice"
    
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun sendAudio(audioFile: File, onResponse: (File) -> Unit, onError: (String) -> Unit) {
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

        DebugLogger.log("JARVIS_API", "Sending Audio to: $API_URL")

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
                    
                    // Response me Audio (MP3) aayega, use save karo
                    try {
                        val outputFile = File(context.cacheDir, "jarvis_reply.mp3")
                        val inputStream = response.body?.byteStream()
                        val outputStream = FileOutputStream(outputFile)
                        inputStream?.copyTo(outputStream)
                        outputStream.close()
                        
                        handler.post { onResponse(outputFile) }
                    } catch (e: Exception) {
                        handler.post { onError("Save Error: ${e.message}") }
                    }
                }
            }
        })
    }
}
