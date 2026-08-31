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
            val ok = executor.callContact(name)
            speak(if (ok) "Calling $name" else "I couldn't find a contact named $name")
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
            val ok = executor.sendSms(name, body)
            speak(if (ok) "Message sent to $name" else "I couldn't find a contact named $name")
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
