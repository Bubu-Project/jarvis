package com.example.jarvis

object DebugLogger {
    private var listener: ((String) -> Unit)? = null
    private val buffer = StringBuilder()

    fun setListener(l: (String) -> Unit) {
        listener = l
        if (buffer.isNotEmpty()) {
            l(buffer.toString())
        }
    }

    fun log(tag: String, msg: String) {
        val line = "[$tag] $msg"
        android.util.Log.d(tag, msg)
        buffer.append(line).append("\n")
        // Max 100 lines rakho
        if (buffer.length > 5000) {
            buffer.delete(0, 2000)
        }
        listener?.invoke(line)
    }

    fun clear() {
        buffer.clear()
    }
}
