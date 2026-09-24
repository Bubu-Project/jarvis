package com.example.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActionExecutor(private val context: Context) {

    fun openApp(appName: String): Boolean {
        val pm = context.packageManager
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

    // ✅ FIXED: Permission check + Dialer fallback
    fun callContact(name: String): Boolean {
        val phoneNumber = lookupContactNumber(name) ?: return false

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = if (hasCallPermission) {
            Intent(Intent.ACTION_CALL)
        } else {
            // Agar permission nahi hai, toh dialer khol do (number ready hoga)
            Intent(Intent.ACTION_DIAL)
        }.apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    fun sendSms(name: String, message: String): Boolean {
        val phoneNumber = lookupContactNumber(name) ?: return false
        try {
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

    // ✅ FIXED: Better YouTube intent + browser fallback
    fun playOnYoutube(query: String) {
        // Pehle try karo YouTube app se search karne ki
        val youtubeIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(youtubeIntent)
        } catch (e: Exception) {
            // Agar YouTube app search intent support nahi karta, toh URL se kholo
            try {
                val urlIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(urlIntent)
            } catch (e2: Exception) {
                // Agar YouTube app hi nahi hai, toh browser mein kholo
                val browserIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            }
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

    // ✅ NAYA: Time aur Date batane ke liye
    fun getCurrentTime(): String {
        val formatter = SimpleDateFormat("hh:mm a", Locale.US)
        return formatter.format(Date())
    }

    fun getCurrentDate(): String {
        val formatter = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.US)
        return formatter.format(Date())
    }

    // ✅ NAYA: Battery status batane ke liye
    fun getBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun lookupContactNumber(name: String): String? {
        val hasReadPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasReadPermission) return null

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
