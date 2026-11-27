package com.example.jarvis

import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Phone ke andar ke chhote kaam: bina server ke, turant
class SystemActions(private val context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun batteryStatus(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return "बैटरी $pct प्रतिशत है" + if (charging) "। फ़ोन चार्ज हो रहा है।" else "।"
    }

    fun currentTime(): String {
        val c = Calendar.getInstance()
        val h24 = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val part = when {
            h24 < 4 -> "रात"
            h24 < 12 -> "सुबह"
            h24 < 16 -> "दोपहर"
            h24 < 20 -> "शाम"
            else -> "रात"
        }
        return if (m == 0) "अभी $part के $h12 बजे हैं।" else "अभी $part के $h12 बजकर $m मिनट हुए हैं।"
    }

    fun currentDate(): String {
        val f = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("hi", "IN"))
        return "आज ${f.format(Date())} है।"
    }

    // Music / video ke buttons (YouTube, Spotify, sab par chalta hai)
    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun musicNext() = mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun musicPrevious() = mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun musicPause() = mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun musicPlay() = mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)

    fun navigateTo(dest: String): Boolean {
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}"))
            i.setPackage("com.google.android.apps.maps")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        } catch (e: Exception) {
            try {
                val i2 = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}"))
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i2)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    // delta = +/- badlav, absolute = seedha value (10 se 255)
    fun changeBrightness(delta: Int?, absolute: Int?): String {
        if (!Settings.System.canWrite(context)) {
            return "ब्राइटनेस बदलने की अनुमति नहीं है। Jarvis Permissions में Modify system settings दीजिए।"
        }
        return try {
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val v = (absolute ?: (cur + (delta ?: 0))).coerceIn(10, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            "ब्राइटनेस बदल दी।"
        } catch (e: Exception) {
            "ब्राइटनेस नहीं बदल पाया।"
        }
    }

    fun setDnd(on: Boolean): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            return "डू नॉट डिस्टर्ब की अनुमति नहीं है। Jarvis Permissions में दीजिए।"
        }
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_NONE
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return if (on) "डू नॉट डिस्टर्ब चालू कर दिया।" else "डू नॉट डिस्टर्ब बंद कर दिया।"
    }

    // ---------------- Phone lock / unlock ----------------
    // Lock ke liye Device admin chahiye (Jarvis Permissions ka button 7)
    fun lockScreen(): String {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, JarvisAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            return "फ़ोन लॉक करने के लिए Jarvis Permissions में Device admin दीजिए।"
        }
        return try {
            dpm.lockNow()
            ""
        } catch (e: Exception) {
            "फ़ोन लॉक नहीं हो पाया।"
        }
    }

    // Unlock: screen jalata hai aur swipe wala lock hatata hai.
    // PIN ya pattern laga ho to Android ise voice se kholne nahi deta (suraksha ke liye)
    @Suppress("DEPRECATION")
    fun wakeAndUnlock(): String {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "jarvis:wake"
            )
            wl.acquire(5000L)
            val i = Intent(context, UnlockActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            ""
        } catch (e: Exception) {
            "स्क्रीन नहीं खुल पाई।"
        }
    }
}
