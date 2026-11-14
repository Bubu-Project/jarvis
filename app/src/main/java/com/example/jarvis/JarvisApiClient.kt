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
    // ⚠️ Kaggle restart par ye URL badal jata hai. Sirf yahi badlo (end mein /chat_voice mat likhna)
    private val BASE_URL = "https://footwork-upscale-oxidant.ngrok-free.dev"

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // onResponse(replyAudio, userText, action)
    // action = "none" (normal baat / agent kaam) ya "command" / "flashlight_on" / "flashlight_off"
    // Server 204 de to onError("IGNORED") aata hai (Jarvis so raha hai, wake word nahi bola)
    fun sendAudio(
        audioFile: File,
        onResponse: (File, String, String) -> Unit,
        onError: (String) -> Unit
    ) {
        val requestBody = audioFile.asRequestBody("audio/wav".toMediaTypeOrNull())
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", audioFile.name, requestBody)
            .build()
        val request = Request.Builder()
            .url("$BASE_URL/chat_voice")
            .post(multipartBody)
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { onError(e.message ?: "Unknown") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code == 204) {
                        handler.post { onError("IGNORED") }
                        return
                    }
                    if (!response.isSuccessful) {
                        val body = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                        handler.post { onError("Server Error: ${response.code} $body") }
                        return
                    }
                    try {
                        val rawText = response.header("X-User-Text") ?: ""
                        val userText = try {
                            URLDecoder.decode(rawText, "UTF-8")
                        } catch (e: Exception) { rawText }
                        val action = response.header("X-Action") ?: "none"

                        val outputFile = File(context.cacheDir, "jarvis_reply.mp3")
                        val inputStream = response.body?.byteStream()
                        val outputStream = FileOutputStream(outputFile)
                        inputStream?.copyTo(outputStream)
                        outputStream.close()
                        handler.post { onResponse(outputFile, userText, action) }
                    } catch (e: Exception) {
                        handler.post { onError("Save Error: ${e.message}") }
                    }
                }
            }
        })
    }

    // Server se poochta hai: koi agent ka kaam pura hua? Hua to audio file milti hai, warna null
    fun fetchNotification(onResult: (File?) -> Unit) {
        val request = Request.Builder()
            .url("$BASE_URL/next_notification")
            .get()
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { onResult(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.code != 200) {
                        handler.post { onResult(null) }
                        return
                    }
                    try {
                        val outputFile = File(context.cacheDir, "notif_${System.currentTimeMillis()}.mp3")
                        val outputStream = FileOutputStream(outputFile)
                        response.body?.byteStream()?.copyTo(outputStream)
                        outputStream.close()
                        handler.post { onResult(outputFile) }
                    } catch (e: Exception) {
                        handler.post { onResult(null) }
                    }
                }
            }
        })
    }
}
