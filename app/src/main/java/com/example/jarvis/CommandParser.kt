package com.example.jarvis

object CommandParser {

    fun tryHandle(
        command: String,
        executor: ActionExecutor,
        speak: (String) -> Unit
    ): Boolean {
        val text = command.lowercase().trim()

        Regex("^(open|launch|start)\\s+(.+)").find(text)?.let { match ->
            val appName = match.groupValues[2].trim()
            val ok = executor.openApp(appName)
            speak(if (ok) "Opening $appName" else "I couldn't find $appName on this phone")
            return true
        }

        Regex("^call\\s+(.+)").find(text)?.let { match ->
            val name = match.groupValues[1].trim()
            val result = executor.callContact(name)
            // ERROR FIX: Boolean ko String mein badla
            speak(if (result) "Calling $name" else "Failed to call $name")
            return true
        }

        Regex("^play\\s+(.+?)(\\s+on\\s+youtube)?$").find(text)?.let { match ->
            val song = match.groupValues[1].trim()
            executor.playOnYoutube(song)
            speak("Playing $song on YouTube")
            return true
        }

        Regex("^(message|text)\\s+(\\w+)\\s+(saying\\s+)?(.+)").find(text)?.let { match ->
            val name = match.groupValues[2].trim()
            val body = match.groupValues[4].trim()
            val result = executor.sendSms(name, body)
            // ERROR FIX: Boolean ko String mein badla
            speak(if (result) "Message sent to $name" else "Failed to send message to $name")
            return true
        }

        if (text.contains("flashlight") || text.contains("torch")) {
            val on = !text.contains("off")
            executor.toggleFlashlight(on)
            speak(if (on) "Flashlight on" else "Flashlight off")
            return true
        }

        return false
    }
}
