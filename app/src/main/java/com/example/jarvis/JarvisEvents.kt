
package com.example.jarvis

object JarvisEvents {
    @Volatile
    var onMessage: ((String, String, String) -> Unit)? = null
}
