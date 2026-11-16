package com.example.jarvis

// Awaaz ke text se phone ka command pehchanta aur chalata hai.
// execute() lautata hai:
//   null      = koi command nahi mila (app server ka jawab bajayega)
//   ""        = command chala, bolne ko kuch nahi
//   "kuch..." = command chala, ye vaakya bolo
class CommandRouter(private val ex: ActionExecutor) {

    fun execute(text: String): String? {
        val cmd = text.lowercase().trim()
        if (cmd.isBlank()) return null

        fun has(vararg w: String) = w.any { cmd.contains(it) }

        // poore shabd hata ta hai (naam ke andar ke akshar nahi kat te: "कोमल" mein "को" nahi katega)
        fun dropWords(vararg w: String): String {
            val stop = w.toSet()
            return cmd.split(" ")
                .map { it.trim(',', '.', '।', '!', '?') }
                .filter { it.isNotBlank() && it !in stop }
                .joinToString(" ")
        }

        // sub-string hata ta hai (YouTube search ke liye)
        fun strip(vararg w: String): String {
            var r = cmd
            w.forEach { r = r.replace(it, "") }
            return r.replace(Regex("\\s+"), " ").trim()
        }

        return when {
            has("अलार्म", "alarm") ->
                ex.setAlarmFromText(cmd) ?: "समय समझ नहीं आया, फिर से बोलिए।"

            has("टाइमर", "timer") ->
                ex.setTimerFromText(cmd) ?: "समय समझ नहीं आया, फिर से बोलिए।"

            has("आवाज", "volume", "वॉल्यूम", "वोल्यूम", "साउंड", "sound") &&
                has("बढ़ा", "बढा", "ज्यादा", "ज़्यादा", "तेज", "up", "increase", "फुल", "full", "max", "मैक्स",
                    "कम", "घटा", "धीमा", "धीरे", "down", "decrease", "म्यूट", "mute", "बंद") -> {
                when {
                    has("म्यूट", "mute", "बंद") -> { ex.volumeMute(); "आवाज़ बंद कर दी।" }
                    has("फुल", "full", "max", "मैक्स") -> { ex.volumeMax(); "आवाज़ पूरी कर दी।" }
                    has("कम", "घटा", "धीमा", "धीरे", "down", "decrease") -> { ex.volumeDown(); "आवाज़ कम कर दी।" }
                    else -> { ex.volumeUp(); "आवाज़ बढ़ा दी।" }
                }
            }

            has("wifi", "वाईफाई", "वाई-फाई", "वाई फाई", "वाइफाई", "वाई फ़ाई", "वाईफ़ाई") -> {
                ex.openWifiPanel()
                "वाई-फाई का पैनल खोल दिया है। एंड्रॉइड ऐप को सीधे चालू बंद नहीं करने देता।"
            }

            has("ब्लूटूथ", "bluetooth", "ब्लू टूथ") -> {
                val on = !has("बंद", "बन्द", "off", "ऑफ")
                if (ex.setBluetooth(on)) {
                    if (on) "ब्लूटूथ चालू कर दिया।" else "ब्लूटूथ बंद कर दिया।"
                } else {
                    "ब्लूटूथ की सेटिंग खोल दी है।"
                }
            }

            has("flashlight", "flash", "torch", "light", "फ्लैशलाइट", "फ्लैश", "फ़्लैश", "टॉर्च", "टार्च", "लाइट") -> {
                val on = !has("off", "बंद", "बन्द", "ऑफ", "बुझा")
                ex.toggleFlashlight(on)
                if (on) "टॉर्च चालू कर दी।" else "टॉर्च बंद कर दी।"
            }

            has("youtube", "यूट्यूब", "गाना", "play", "प्ले") -> {
                val q = strip(
                    "youtube", "यूट्यूब", "play", "प्ले", "पर", "on", "चलाओ", "चला", "सुनाओ", "सुना",
                    "बजाओ", "बजा", "गाना", "खोलो"
                )
                if (q.isBlank()) {
                    ex.openApp("youtube")
                    ""
                } else {
                    ex.playOnYoutube(q)
                    ""
                }
            }

            has("camera", "कैमरा") -> {
                ex.openCamera()
                "कैमरा खोल दिया।"
            }

            has("call", "कॉल", "फोन", "फ़ोन") -> {
                val name = dropWords("call", "कॉल", "फोन", "फ़ोन", "को", "करो", "कर", "लगाओ", "लगा", "दो", "से", "बात")
                when {
                    name.isBlank() -> "किसे कॉल करना है?"
                    ex.callContact(name) -> "$name को कॉल लगा रहा हूँ।"
                    else -> "$name नाम का कॉन्टैक्ट नहीं मिला।"
                }
            }

            has("message", "मैसेज", "sms") -> {
                val key = listOf("message", "मैसेज", "sms").first { cmd.contains(it) }
                val name = cmd.substringBefore(key).split(" ")
                    .filter { it.isNotBlank() && it != "को" && it != "to" }
                    .joinToString(" ")
                val msg = cmd.substringAfter(key).split(" ")
                    .filter { it.isNotBlank() && it !in setOf("भेजो", "send", "कि", "करो", "लिखो") }
                    .joinToString(" ")
                if (name.isNotBlank() && msg.isNotBlank() && ex.sendSms(name, msg)) {
                    "$name को मैसेज भेज दिया।"
                } else {
                    "मैसेज नहीं भेज पाया।"
                }
            }

            has("open", "खोलो", "खोल") -> {
                val app = dropWords("open", "खोलो", "खोल", "ऐप", "app", "एप", "को")
                if (app.isNotBlank() && ex.openApp(app)) "$app खोल रहा हूँ।" else "$app ऐप नहीं मिला।"
            }

            else -> null
        }
    }
}
