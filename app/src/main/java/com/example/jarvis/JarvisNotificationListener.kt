package com.example.jarvis

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

// App aur service ke beech sandesh pahunchane ka chhota pul
object JarvisEvents {
    // (ऐप का naam, bhejne wala, message)
    @Volatile var onMessage: ((String, String, String) -> Unit)? = null
}

// WhatsApp / SMS / Telegram ka message aane par Jarvis bolta hai.
// Chalane ke liye: Jarvis Permissions ऐप mein "Notification access" dena padta hai.
class JarvisNotificationListener : NotificationListenerService() {
    private val recent = HashMap<String, Long>()

    private val msgPackages = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
        "com.google.android.apps.messaging", "com.android.mms",
        "com.samsung.android.messaging", "com.facebook.orca"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val n = sbn.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        val isMsg = n.category == Notification.CATEGORY_MESSAGE || pkg in msgPackages
        if (!isMsg) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { "मैसेज" }

        // "WhatsApp" naam ka khali summary notification na bole
        if (text.isBlank() || title.equals(app, ignoreCase = true)) return

        // wahi notification baar-baar update ho to ek hi baar bolo
        val key = "$pkg|$title|$text"
        val now = System.currentTimeMillis()
        val last = recent[key]
        if (last != null && now - last < 8000) return
        recent[key] = now
        if (recent.size > 50) recent.clear()

        JarvisEvents.onMessage?.invoke(app, title, text)
    }
}
