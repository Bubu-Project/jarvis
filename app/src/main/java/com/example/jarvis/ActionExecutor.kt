package com.example.jarvis

import android.annotation.SuppressLint
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
    // Devanagari naam ko Latin mein badalke bhi dhundta hai (राहुल -> rahul)
    private fun nameCandidates(name: String): List<String> {
        val n = name.trim()
        val out = LinkedHashSet<String>()
        out.add(n)
        CONTACT_ALIASES[n]?.let { out.add(it) }
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                val tr = android.icu.text.Transliterator.getInstance(
                    "Devanagari-Latin; NFD; [:Nonspacing Mark:] Remove; NFC"
                )
                val latin = tr.transliterate(n).trim()
                if (latin.isNotEmpty()) {
                    out.add(latin)
                    if (latin.endsWith("a") && latin.length > 3) out.add(latin.dropLast(1))
                }
            } catch (_: Exception) {}
        }
        return out.toList()
    }

    private fun findNumber(name: String): String? {
        for (cand in nameCandidates(name)) {
            try {
                val uri = Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(cand)
                )
                val cursor = context.contentResolver.query(
                    uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null
                )
                cursor?.use {
                    if (it.moveToFirst()) return it.getString(0)
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun callContact(name: String): Boolean {
        val number = findNumber(name) ?: return false
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }

    fun sendSms(name: String, message: String): Boolean {
        val number = findNumber(name) ?: return false
        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(number, null, message, null, null)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

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
        val wanted = nameCandidates(appName).map { it.lowercase() } +
            listOfNotNull(APP_ALIASES[appName.trim()])
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
