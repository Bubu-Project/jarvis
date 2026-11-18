package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Ek hi jagah se saari permissions: har button dabao aur Allow karo.
// Ye app drawer mein alag icon "Jarvis Permissions" ke naam se dikhega.
class PermissionsActivity : Activity() {

    private fun runtimePermissions(): Array<String> {
        val list = arrayListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= 31) list.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        val title = TextView(this).apply {
            text = "Jarvis Permissions\nHar button dabao aur Allow karo (upar se neeche, ek-ek karke)"
            textSize = 18f
        }
        root.addView(title)

        fun addButton(label: String, action: () -> Unit) {
            val b = Button(this).apply {
                text = label
                setOnClickListener {
                    try { action() } catch (_: Exception) {}
                }
            }
            root.addView(b)
        }

        addButton("1) Mic, Phone, Contacts, Call log, SMS, Calls") {
            requestPermissions(runtimePermissions(), 100)
        }
        addButton("2) Do Not Disturb access (call par ringtone chup karne ke liye)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        addButton("3) Notification access (WhatsApp / SMS message padhne ke liye)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        addButton("4) Modify system settings (brightness ke liye)") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        addButton("5) Display over other apps (ऐप kholne ke liye)") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        addButton("6) Battery optimization band karo (background mein chalta rahe)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
