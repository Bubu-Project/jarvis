package com.example.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
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

    private fun decode(raw: String): String =
        try { URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }

    // Do tarah ke jawab:
    //  1) onResponse(audio, userText, action): ek poori awaaz (commands, tasks, chup hona wagairah)
    //  2) onStreamChunk(chunk, userText) baar-baar + onStreamEnd(): LLM ka jawab vaakya-by-vaakya
    // Server 204 de to onError("IGNORED") aata hai (Jarvis so raha hai, wake word nahi bola)
    fun sendAudio(
        audioFile: File,
        onResponse: (File, String, String) -> Unit,
        onStreamChunk: (File, String) -> Unit,
        onStreamEnd: () -> Unit,
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

                    val userText = decode(response.header("X-User-Text") ?: "")
                    val isStream = response.header("Content-Type")?.contains("x-jarvis-stream") == true

                    if (isStream) {
                        // Har vaakya: [4 byte lambai][mp3]. Lambai 0 = khatam
                        try {
                            val source = response.body!!.source()
                            var idx = 0
                            while (!source.exhausted()) {
                                val len = source.readInt()
                                if (len <= 0) break
                                val bytes = source.readByteArray(len.toLong())
                                val f = File(context.cacheDir, "chunk_${System.currentTimeMillis()}_${idx++}.mp3")
                                f.writeBytes(bytes)
                                handler.post { onStreamChunk(f, userText) }
                            }
                        } catch (e: Exception) {
                            // stream beech mein toot gayi: jo mil chuka wo chalega
                        }
                        handler.post { onStreamEnd() }
                        return
                    }

                    try {
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

    // Sirf text chahiye (phone call ke "उठाओ / काट दो" ke liye). Wake word ki zaroorat nahi
    fun transcribe(audioFile: File, onResult: (String?) -> Unit) {
        val requestBody = audioFile.asRequestBody("audio/wav".toMediaTypeOrNull())
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", audioFile.name, requestBody)
            .build()
        val request = Request.Builder()
            .url("$BASE_URL/transcribe")
            .post(multipartBody)
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { onResult(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        handler.post { onResult(null) }
                        return
                    }
                    val text = try {
                        JSONObject(response.body?.string() ?: "{}").optString("text", "")
                    } catch (e: Exception) { "" }
                    handler.post { onResult(text) }
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
