package com.example.jarvis

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager

class ActionExecutor(private val context: Context) {

    fun openApp(appName: String): Boolean {
        val pm = context.packageManager
        // ✅ यहाँ PackageManager फ्लैग को सेफ तरीके से डिक्लेअर कर दिया गया है
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val normalizedQuery = appName.lowercase().replace(" ", "")
        val match = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase().replace(" ", "").contains(normalizedQuery)
        } ?: return false

        val launchIntent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }

    fun callContact(name: String): Boolean {
        val phoneNumber = lookupContactNumber(name) ?: return false
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    fun sendSms(name: String, message: String): Boolean {
        val phoneNumber = lookupContactNumber(name) ?: return false
        try {
            // ✅ SmsManager को नए और पुराने दोनों एंड्रॉयड वर्शन्स के लिए सेफ बना दिया गया है
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun playOnYoutube(query: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.google.android.youtube")
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            intent.setPackage(null)
            context.startActivity(intent)
        }
    }

    fun toggleFlashlight(on: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(cameraId, on)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun lookupContactNumber(name: String): String? {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                return it.getString(numberIndex)
            }
        }
        return null
    }
}
