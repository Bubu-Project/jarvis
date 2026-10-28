package com.example.jarvis

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.ContactsContract

class ActionExecutor(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // 🔦 1. फ्लैशलाइट (Debugger के साथ)
    fun toggleFlashlight(on: Boolean) {
        try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, on)
            DebugLogger.log("ACTION", "Flashlight turned: $on")
        } catch (e: Exception) {
            DebugLogger.log("ACTION_ERR", "Flashlight Error: ${e.message}")
        }
    }

    // 📞 2. कॉल करना (Debugger के साथ)
    fun callContact(name: String) {
        try {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
            val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
            
            if (cursor != null && cursor.moveToFirst()) {
                val number = cursor.getString(0)
                cursor.close()
                val intent = Intent(Intent.ACTION_CALL)
                intent.data = Uri.parse("tel:$number")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                DebugLogger.log("ACTION", "Calling $name at $number")
            } else {
                DebugLogger.log("ACTION_ERR", "Contact not found: $name")
                val intent = Intent(Intent.ACTION_DIAL)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            DebugLogger.log("ACTION_ERR", "Call Error: ${e.message}")
        }
    }

    // 📺 3. यूट्यूब (जो पहले से चल रहा है)
    fun playOnYoutube(query: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            DebugLogger.log("ACTION", "Playing on YouTube: $query")
        } catch (e: Exception) {
            DebugLogger.log("ACTION_ERR", "YouTube Error: ${e.message}")
        }
    }

    // 📱 4. ऐप खोलना
    fun openApp(appName: String) {
        val packageName = when (appName.lowercase().trim()) {
            "whatsapp", "व्हाट्सएप" -> "com.whatsapp"
            "instagram", "इंस्टाग्राम" -> "com.instagram.android"
            "facebook", "फेसबुक" -> "com.facebook.katana"
            "telegram", "टेलीग्राम" -> "org.telegram.messenger"
            "camera", "कैमरा" -> "com.android.camera"
            "settings", "सेटिंग्स" -> "com.android.settings"
            else -> ""
        }

        if (packageName.isNotEmpty()) {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                DebugLogger.log("ACTION", "Opened app: $appName")
                return
            }
        }
        DebugLogger.log("ACTION_ERR", "App not found: $appName")
    }
}
