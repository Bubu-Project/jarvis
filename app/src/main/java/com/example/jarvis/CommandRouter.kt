package com.example.jarvis

// Awaaz ke text se phone ka command pehchanta aur chalata hai. Thoda galat suna ho tab bhi pakadta hai.
// execute() lautata hai:
//   null      = koi command nahi mila
//   ""        = command chala, bolne ko kuch nahi
//   "kuch..." = command chala, ye vaakya bolo
class CommandRouter(private val ex: ActionExecutor, private val sys: SystemActions) {

    // Hindi ke milte-julte akshar ek jaise bana deta hai (ी/ि, ू/ु, ै/े, ॉ/ो, nukta wagairah)
    private fun norm(s: String): String = s.lowercase()
        .replace("\u093C", "")
        .replace("\u0901", "\u0902")
        .replace("\u0949", "\u094B")
        .replace("\u0911", "\u0913")
        .replace("\u0940", "\u093F")
        .replace("\u0942", "\u0941")
        .replace("\u0948", "\u0947")
        .replace("\u094C", "\u094B")
        .replace("\u0945", "\u0947")

    fun execute(text: String): String? {
        val cmd = text.lowercase().trim()
        if (cmd.isBlank()) return null
        val n = norm(cmd)
        val toks = n.split(" ", ",", "।", "?", "!").filter { it.isNotBlank() }

        // exact ya thoda galat (fuzzy) milan. English shabd sirf exact milte hain
        fun has(vararg w: String): Boolean = w.any { kw ->
            val k = norm(kw)
            n.contains(k) || (
                k.length >= 4 && k.any { it.code > 127 } &&
                    toks.any { tk -> Math.abs(tk.length - k.length) <= 2 && fuzzyRatio(tk, k) >= 0.74 }
                )
        }

        // poore shabd hata ta hai (naam ke andar ke akshar nahi kat te)
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

            has("बैटरी", "battery", "चार्जिंग") -> sys.batteryStatus()

            has("कितने बजे", "कितना बजा", "कितना बज", "समय क्या", "टाइम क्या", "समय बताओ", "टाइम बताओ", "what time") ->
                sys.currentTime()

            has("तारीख", "तारिख", "कौन सा दिन", "what date") -> sys.currentDate()

            // Message pehle: message ke text mein "फोन"/"कॉल" ho sakta hai
            has("मैसेज", "message", "sms", "संदेश") -> {
                val key = listOf("मैसेज", "मेसेज", "message", "sms", "संदेश").firstOrNull { cmd.contains(it) } ?: ""
                val fillers = setOf("को", "to", "send", "मुझे", "एक", "का", "के", "से")
                val verbs = setOf("भेजो", "भेज", "दो", "करो", "कर", "लिखो", "कि", "की", "that", "भेजना")
                var name = ""
                var msg = ""
                if (key.isNotBlank()) {
                    val before = cmd.substringBefore(key).split(" ")
                        .map { it.trim(',', '.', '।') }.filter { it.isNotBlank() && it !in fillers }
                    val after = cmd.substringAfter(key).split(" ")
                        .map { it.trim(',', '.', '।') }.filter { it.isNotBlank() && it !in verbs }
                    if (before.isNotEmpty()) {
                        name = before.joinToString(" ")
                        msg = after.joinToString(" ")
                    } else {
                        val i = after.indexOf("को")
                        if (i > 0) {
                            name = after.take(i).joinToString(" ")
                            msg = after.drop(i + 1).joinToString(" ")
                        }
                    }
                }
                when {
                    name.isBlank() -> "किसे मैसेज भेजना है? ऐसे बोलिए: राहुल को मैसेज भेजो कि मैं आ रहा हूँ।"
                    msg.isBlank() -> "$name को क्या मैसेज भेजना है?"
                    else -> ex.sendSms(name, msg)
                }
            }

            has("call", "कॉल", "फोन", "फ़ोन") -> {
                val name = dropWords("call", "कॉल", "फोन", "फ़ोन", "को", "करो", "कर", "लगाओ", "लगा", "दो", "से", "बात")
                if (name.isBlank()) "किसे कॉल करना है?" else ex.callContact(name)
            }

            // Music ke buttons: "गाना" wale YouTube branch se pehle
            has("अगला गाना", "next song", "नेक्स्ट", "अगला") -> { sys.musicNext(); "" }
            has("पिछला गाना", "previous", "पिछला") -> { sys.musicPrevious(); "" }
            has("गाना रोको", "गाना बंद", "म्यूजिक बंद", "pause", "पॉज") -> { sys.musicPause(); "" }

            has("रास्ता", "रूट", "नेविगेट", "navigate", "directions") -> {
                val dest = dropWords(
                    "रास्ता", "रूट", "नेविगेट", "navigate", "directions", "बताओ", "दिखाओ", "का", "की", "के", "तक",
                    "जाना", "है", "मुझे", "को", "करो", "कर", "लगाओ"
                )
                when {
                    dest.isBlank() -> "कहाँ जाना है?"
                    sys.navigateTo(dest) -> "$dest का रास्ता खोल रहा हूँ।"
                    else -> "मैप नहीं खुल पाया।"
                }
            }

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

            has("ब्राइटनेस", "brightness", "रोशनी") -> {
                when {
                    has("फुल", "full", "max", "मैक्स") -> sys.changeBrightness(null, 255)
                    has("कम", "घटा", "धीमा", "down", "decrease") -> sys.changeBrightness(-60, null)
                    else -> sys.changeBrightness(60, null)
                }
            }

            has("डू नॉट डिस्टर्ब", "dnd", "डिस्टर्ब") -> {
                sys.setDnd(!has("बंद", "बन्द", "off", "ऑफ", "हटा"))
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

            has("open", "खोलो", "खोल") -> {
                val app = dropWords("open", "खोलो", "खोल", "ऐप", "app", "एप", "को")
                if (app.isNotBlank() && ex.openApp(app)) "$app खोल रहा हूँ।" else "$app ऐप नहीं मिला।"
            }

            else -> null
        }
    }
}
