package com.example.jarvis

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager

class ActionExecutor(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun toggleFlashlight(on: Boolean) {
        try {
            // Jis camera mein flash hai wahi chuno
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

    private fun findNumber(name: String): String? {
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name)
            )
            val cursor = context.contentResolver.query(
                uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (_: Exception) {}
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
            val smsManager: SmsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
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
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase().contains(appName.lowercase())
        } ?: return false
        val launchIntent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }
}
