package com.example.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

// Phone ke andar (offline) Hindi sunne wala. Pehli baar ~42 MB ka model download hota hai.
// Model tak na pahunche ya galat sune to app pehle ki tarah server ko bhej deta hai.
class LocalRecognizer(private val context: Context) {
    private val urls = listOf(
        "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
        "https://huggingface.co/rhasspy/vosk-models/resolve/main/hi/vosk-model-small-hi-0.22.zip"
    )
    private val modelDir = File(context.filesDir, "vosk-hi")
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var model: Model? = null
    @Volatile private var loading = false

    fun isReady(): Boolean = model != null

    private fun findRoot(): File? =
        modelDir.walkTopDown().firstOrNull {
            it.isDirectory && File(it, "am").isDirectory && File(it, "graph").isDirectory
        }

    // Background mein model taiyar karta hai (download + load)
    fun prepare() {
        if (model != null || loading) return
        loading = true
        Thread {
            try {
                if (findRoot() == null) download()
                val root = findRoot() ?: throw IllegalStateException("model folder nahi mila")
                model = Model(root.absolutePath)
                DebugLogger.log("LOCAL", "Offline recognizer taiyar: ab commands turant chalenge")
            } catch (e: Throwable) {
                DebugLogger.log("LOCAL", "Offline model nahi laga (server se hi chalega): ${e.message}")
            }
            loading = false
        }.start()
    }

    private fun download() {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val zip = File(context.cacheDir, "vosk-hi.zip")
        var ok = false
        for (u in urls) {
            try {
                DebugLogger.log("LOCAL", "Model download ho raha hai (~42 MB)...")
                client.newCall(Request.Builder().url(u).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    FileOutputStream(zip).use { out -> r.body!!.byteStream().copyTo(out) }
                }
                ok = true
                break
            } catch (e: Exception) {
                DebugLogger.log("LOCAL", "Download fail ($u): ${e.message}")
            }
        }
        if (!ok) throw IllegalStateException("model download nahi hua")

        modelDir.deleteRecursively()
        modelDir.mkdirs()
        val base = modelDir.canonicalPath
        ZipInputStream(zip.inputStream()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val f = File(modelDir, e.name)
                if (f.canonicalPath.startsWith(base)) {
                    if (e.isDirectory) {
                        f.mkdirs()
                    } else {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { out -> zin.copyTo(out) }
                    }
                }
                e = zin.nextEntry
            }
        }
        zip.delete()
    }

    // WAV file ko text mein badalta hai. Result main thread par aata hai ("" = samajh nahi aaya)
    fun recognize(wav: File, onResult: (String) -> Unit) {
        val m = model
        if (m == null) {
            handler.post { onResult("") }
            return
        }
        Thread {
            var text = ""
            try {
                val rec = Recognizer(m, 16000.0f)
                wav.inputStream().use { ins ->
                    ins.skip(44)
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        rec.acceptWaveForm(buf, n)
                    }
                }
                text = JSONObject(rec.finalResult).optString("text", "")
                rec.close()
            } catch (e: Throwable) {
                DebugLogger.log("LOCAL", "Recognize error: ${e.message}")
            }
            handler.post { onResult(text) }
        }.start()
    }
}
