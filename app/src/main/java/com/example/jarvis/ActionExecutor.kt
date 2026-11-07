package com.example.jarvis

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.ContactsContract

class ActionExecutor(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun toggleFlashlight(on: Boolean) {
        try { cameraManager.setTorchMode(cameraManager.cameraIdList[0], on) } catch (_: Exception) {}
    }

    fun callContact(name: String): Boolean {
        try {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
            val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val number = cursor.getString(0)
                cursor.close()
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    fun playOnYoutube(query: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openApp(appName: String): Boolean {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull { pm.getApplicationLabel(it).toString().lowercase().contains(appName.lowercase()) } ?: return false
        val launchIntent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }
}
