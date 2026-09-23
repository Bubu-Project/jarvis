package com.example.jarvis

import java.util.Locale

class WakeWordDetector {

    fun containsWakeWord(text: String): Boolean {
        val value = text.lowercase(Locale.US)
            .replace("-", " ")
            .replace(".", " ")
            .replace(",", " ")
            .replace("?", " ")
            .replace("!", " ")
            .trim()

        return value.contains("jarvis") ||
                value.contains("jar vis") ||
                value.contains("jervis") ||
                value.contains("jarvish") ||
                value.contains("jarvice")
    }

    fun removeWakeWord(text: String): String {
        return text.lowercase(Locale.US)
            .replace("hey jarvis", "")
            .replace("ok jarvis", "")
            .replace("okay jarvis", "")
            .replace("jarvis assistant", "")
            .replace("jar vis", "")
            .replace("jervis", "")
            .replace("jarvish", "")
            .replace("jarvice", "")
            .replace("jarvis", "")
            .trim()
    }
}
