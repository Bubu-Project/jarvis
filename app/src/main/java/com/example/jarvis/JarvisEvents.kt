package com.example.jarvis

object JarvisEvents {

    var onMessage: ((app: String, who: String, text: String) -> Unit)? = null

    fun notifyMessage(
        app: String,
        who: String,
        text: String
    ) {
        onMessage?.invoke(app, who, text)
    }
}
