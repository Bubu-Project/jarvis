package com.example.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import java.util.Calendar

// Do shabdon ki kitni milti-julti hai (0.0 se 1.0). Galat sune naam bhi pakadne ke kaam aata hai
fun fuzzyRatio(a: String, b: String): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    val m = a.length
    val n = b.length
    if (m == 0 || n == 0) return 0.0
    var prev = IntArray(n + 1) { it }
    var cur = IntArray(n + 1)
    for (i in 1..m) {
        cur[0] = i
        for (j in 1..n) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        val tmp = prev
        prev = cur
        cur = tmp
    }
    return 1.0 - prev[n].toDouble() / maxOf(m, n)
}

data class Contact(val name: String, val number: String)

class ActionExecutor(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        // Hindi naam -> English naam jo tumhare contacts mein likha hai. Apne contacts ke hisaab se badlo/badhao
        val CONTACT_ALIASES = mapOf(
            "पापा" to "papa", "मम्मी" to "mummy", "मां" to "maa", "माँ" to "maa",
            "भैया" to "bhaiya", "दीदी" to "didi", "भाई" to "bhai"
        )

        // Hindi ऐप नाम -> English naam jo phone mein ऐप ka label hai
        val APP_ALIASES = mapOf(
            "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "यूट्यूब" to "youtube",
            "कैमरा" to "camera", "गूगल" to "google", "क्रोम" to "chrome", "इंस्टाग्राम" to "instagram",
            "फेसबुक" to "facebook", "टेलीग्राम" to "telegram", "सेटिंग" to "settings",
            "सेटिंग्स" to "settings", "गैलरी" to "gallery", "कैलकुलेटर" to "calculator",
            "मैप्स" to "maps", "जीमेल" to "gmail", "स्नैपचैट" to "snapchat", "फोनपे" to "phonepe",
            "पेटीएम" to "paytm", "घड़ी" to "clock"
        )

        private val HI_NUMS = mapOf(
            "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5,
            "छह" to 6, "छः" to 6, "छे" to 6, "छ" to 6, "सात" to 7, "आठ" to 8, "नौ" to 9,
            "दस" to 10, "ग्यारह" to 11, "बारह" to 12, "तेरह" to 13, "चौदह" to 14,
            "पंद्रह" to 15, "पन्द्रह" to 15, "सोलह" to 16, "सत्रह" to 17, "अठारह" to 18,
            "उन्नीस" to 19, "बीस" to 20, "पच्चीस" to 25, "तीस" to 30, "पैंतीस" to 35,
            "चालीस" to 40, "पैंतालीस" to 45, "पचास" to 50, "साठ" to 60
        )
    }

    // ---------------- Torch / Camera ----------------
    fun toggleFlashlight(on: Boolean) {
        try {
            val camId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(camId, on)
        } catch (_: Exception) {}
    }

    fun openCamera() {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    // ---------------- Contacts / Call / SMS ----------------
    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    // Devanagari ko Latin mein badalta hai (राहुल -> rahula), taaki English contact se mile
    private fun toLatin(s: String): String {
        var t = s.trim().lowercase()
        if (t.any { it.code in 0x0900..0x097F } && Build.VERSION.SDK_INT >= 24) {
            try {
                t = android.icu.text.Transliterator.getInstance(
                    "Devanagari-Latin; NFD; [:Nonspacing Mark:] Remove; NFC"
                ).transliterate(t)
            } catch (_: Exception) {}
        }
        return t.filter { it.isLetter() || it == ' ' }.lowercase().trim()
    }

    private fun loadContacts(): List<Contact> {
        val out = ArrayList<Contact>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    out.add(Contact(n, num))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    // Sabse milta-julta contact dhundta hai (thoda galat bola ho tab bhi)
    fun findContact(query: String): Contact? {
        val q = toLatin(CONTACT_ALIASES[query.trim()] ?: query)
        if (q.isBlank()) return null
        var best: Contact? = null
        var bestScore = 0.0
        for (c in loadContacts()) {
            val cl = toLatin(c.name)
            var score = fuzzyRatio(q, cl)
            for (tk in cl.split(" ")) {
                if (tk.isBlank()) continue
                score = maxOf(score, fuzzyRatio(q, tk))
                if (tk.startsWith(q) || (tk.length >= 3 && q.startsWith(tk))) score = maxOf(score, 0.9)
            }
            if (cl.contains(q)) score = maxOf(score, 0.95)
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return if (bestScore >= 0.6) best else null
    }

    // Bolne layak nateeja lautata hai (kamyabi ya wajah)
    fun callContactSpeak(query: String): String {
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) return "कॉन्टैक्ट पढ़ने की अनुमति नहीं है।"
        if (!hasPerm(Manifest.permission.CALL_PHONE)) return "कॉल करने की अनुमति नहीं है।"
        val c = findContact(query) ?: return "$query नाम का कॉन्टैक्ट नहीं मिला।"
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(c.number)}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "${c.name} को कॉल लगा रहा हूँ।"
        } catch (e: Exception) {
            "कॉल नहीं लग पाई।"
        }
    }

    fun sendSmsSpeak(query: String, message: String): String {
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) return "कॉन्टैक्ट पढ़ने की अनुमति नहीं है।"
        if (!hasPerm(Manifest.permission.SEND_SMS)) return "मैसेज भेजने की अनुमति नहीं है। Jarvis Permissions खोलकर SMS की अनुमति दीजिए।"
        val c = findContact(query) ?: return "$query नाम का कॉन्टैक्ट नहीं मिला।"
        return try {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = sms.divideMessage(message)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(c.number, null, parts, null, null)
            } else {
                sms.sendTextMessage(c.number, null, message, null, null)
            }
            "${c.name} को मैसेज भेज दिया।"
        } catch (e: Exception) {
            DebugLogger.log("SMS", "Error: ${e.message}")
            "मैसेज नहीं भेज पाया।"
        }
    }

    // Purane code (CommandParser.kt) ke liye: seedha true/false
    fun callContact(name: String): Boolean = callContactSpeak(name).contains("कॉल लगा रहा")

    fun sendSms(name: String, message: String): Boolean = sendSmsSpeak(name, message).contains("भेज दिया")

    // ---------------- YouTube / Apps ----------------
    fun playOnYoutube(query: String) {
        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun openApp(appName: String): Boolean {
        val pm = context.packageManager
        val wanted = listOfNotNull(appName.lowercase().trim(), APP_ALIASES[appName.trim()], toLatin(appName))
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull { info ->
            val label = pm.getApplicationLabel(info).toString().lowercase()
            wanted.any { w -> w.isNotBlank() && label.contains(w) }
        } ?: return false
        val launchIntent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }

    // ---------------- Volume ----------------
    fun volumeUp() {
        repeat(3) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }
    }

    fun volumeDown() {
        repeat(3) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
    }

    fun volumeMax() {
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun volumeMute() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
    }

    // ---------------- WiFi / Bluetooth ----------------
    // Android 10+ par koi app WiFi seedha on/off nahi kar sakta, isliye panel khulta hai
    fun openWifiPanel() {
        try {
            val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_WIFI)
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (_: Exception) {}
    }

    // true = seedha on/off ho gaya. false = nahi hua, Bluetooth settings khol di
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun setBluetooth(on: Boolean): Boolean {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                val ok = if (on) adapter.enable() else adapter.disable()
                if (ok) return true
            }
        } catch (_: Exception) {}
        try {
            val i = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (_: Exception) {}
        return false
    }

    // ---------------- Alarm / Timer ----------------
    private fun toNum(tok: String): Int? {
        val t = tok.trim().trim(',', '.', '।')
        if (t.isEmpty()) return null
        if (t.all { it.isDigit() }) {
            var n = 0
            for (c in t) n = n * 10 + Character.getNumericValue(c)
            return n
        }
        return HI_NUMS[t]
    }

    // "सुबह छह बजे का अलार्म लगाओ", "साढ़े सात बजे", "6:30", "शाम 5 बजकर 15 मिनट"
    // Kamyab hone par bolne layak confirmation lautata hai, warna null
    fun setAlarmFromText(text: String): String? {
        val t = text.lowercase()
        val toks = t.split(" ", ",", "।").filter { it.isNotBlank() }
        var hour: Int? = null
        var min = 0

        val m = Regex("(\\d{1,2})\\s*[:.]\\s*(\\d{2})").find(t)
        if (m != null) {
            hour = m.groupValues[1].toInt()
            min = m.groupValues[2].toInt()
        } else {
            val bi = toks.indexOfFirst { it.startsWith("बजे") || it.startsWith("बजकर") }
            if (bi > 0) hour = toNum(toks[bi - 1])
            val mi = toks.indexOfFirst { it.startsWith("मिनट") }
            if (mi > 0) min = toNum(toks[mi - 1]) ?: 0
        }
        if (hour == null) return null
        var h: Int = hour

        if (t.contains("साढ़े") || t.contains("साढे") || t.contains("साड़े")) {
            min = 30
        } else if (t.contains("सवा")) {
            min = 15
        } else if (t.contains("पौने")) {
            min = 45
            h = if (h <= 1) 12 else h - 1
        }

        val pm = t.contains("शाम") || t.contains("दोपहर") || toks.contains("pm")
        val night = t.contains("रात")
        val am = t.contains("सुबह") || t.contains("सवेरे") || toks.contains("am")

        val h24: Int = when {
            h > 12 -> h
            pm -> if (h < 12) h + 12 else 12
            night -> if (h == 12) 0 else if (h >= 6) h + 12 else h
            am -> if (h == 12) 0 else h
            else -> {
                // Subah/shaam nahi bola: jo samay pehle aaye wahi chuno
                val cal = Calendar.getInstance()
                val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val a = h % 12
                if (a * 60 + min > nowMin) a else if ((a + 12) * 60 + min > nowMin) a + 12 else a
            }
        }
        if (h24 !in 0..23 || min !in 0..59) return null

        return try {
            val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h24)
                putExtra(AlarmClock.EXTRA_MINUTES, min)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
            val h12 = if (h24 % 12 == 0) 12 else h24 % 12
            val part = when {
                h24 < 4 -> "रात"
                h24 < 12 -> "सुबह"
                h24 < 16 -> "दोपहर"
                h24 < 20 -> "शाम"
                else -> "रात"
            }
            if (min == 0) "अलार्म $part $h12 बजे के लिए लगा दिया।"
            else "अलार्म $part $h12 बजकर $min मिनट के लिए लगा दिया।"
        } catch (e: Exception) { null }
    }

    // "10 मिनट का टाइमर लगाओ", "एक घंटे का टाइमर", "30 सेकंड का टाइमर"
    fun setTimerFromText(text: String): String? {
        val t = text.lowercase()
        val toks = t.split(" ", ",", "।").filter { it.isNotBlank() }
        var secs = 0
        for (i in toks.indices) {
            val tok = toks[i]
            val prev = if (i > 0) toNum(toks[i - 1]) else null
            if (prev == null) continue
            when {
                tok.startsWith("सेकंड") || tok.startsWith("सेकेंड") || tok.startsWith("second") -> secs += prev
                tok.startsWith("मिनट") || tok.startsWith("minute") -> secs += prev * 60
                tok.startsWith("घंटे") || tok.startsWith("घंटा") || tok.startsWith("घण्टे") || tok.startsWith("hour") ->
                    secs += prev * 3600
            }
        }
        if (secs == 0) {
            if (t.contains("डेढ़") || t.contains("डेढ")) secs = 5400
            else if (t.contains("आधा") || t.contains("आधे")) secs = 1800
        }
        if (secs <= 0) return null

        return try {
            val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, secs)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
            val hh = secs / 3600
            val mm = (secs % 3600) / 60
            val ss = secs % 60
            val parts = ArrayList<String>()
            if (hh > 0) parts.add("$hh घंटे")
            if (mm > 0) parts.add("$mm मिनट")
            if (ss > 0) parts.add("$ss सेकंड")
            "${parts.joinToString(" ")} का टाइमर लगा दिया।"
        } catch (e: Exception) { null }
    }
}
