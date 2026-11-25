package com.example.jarvis

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// J.A.R.V.I.S dashboard: poori screen code se banti hai (koi layout .xml nahi chahiye)
class MainActivity : AppCompatActivity() {

    private val CYAN = 0xFF00E5FF.toInt()
    private val BG = 0xFF03080F.toInt()
    private val DIM = 0xFF6F8CA0.toInt()
    private val ALERT = 0xFFFF5252.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var hud: HudView
    private lateinit var stateText: TextView
    private lateinit var chip: TextView
    private lateinit var cmdTag: TextView
    private lateinit var cmdText: TextView
    private lateinit var mainBtn: TextView
    private lateinit var debugText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logPanel: LinearLayout

    private var running = false
    private var awakeUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())

        running = isServiceRunning()
        setHud(if (running) idleState() else HudState.OFF)

        // Logs ab chhupe rehte hain (DEV LOGS button se dikhte hain). Dashboard inhi se halat samajhta hai
        DebugLogger.setListener { chunk ->
            runOnUiThread {
                chunk.split("\n").filter { it.isNotBlank() }.forEach { onLog(it) }
            }
        }
        requestPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLogger.setListener { }
    }

    // ---------------------------------------------------------------- UI
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun outline(stroke: Int, fill: Int = Color.TRANSPARENT, radius: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(maxOf(1, dp(1)), stroke)
            cornerRadius = dp(radius).toFloat()
        }

    private fun tv(label: String, size: Float, color: Int, bold: Boolean = false, spacing: Float = 0f): TextView =
        TextView(this).apply {
            this.text = label
            textSize = size
            setTextColor(color)
            letterSpacing = spacing
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(16))
        }

        val title = tv("J.A.R.V.I.S", 28f, CYAN, true, 0.35f).apply { gravity = Gravity.CENTER }
        val sub = tv("JUST A RATHER VERY INTELLIGENT SYSTEM", 9f, DIM, false, 0.18f).apply { gravity = Gravity.CENTER }
        chip = tv("● OFFLINE", 11f, DIM, true, 0.2f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        col.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(sub, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        col.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        })

        hud = HudView(this)
        col.addView(hud, LinearLayout.LayoutParams(MATCH, 0, 1f))

        stateText = tv("OFFLINE", 14f, CYAN, true, 0.3f).apply { gravity = Gravity.CENTER }
        col.addView(stateText, LinearLayout.LayoutParams(MATCH, WRAP))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = outline(0x6600E5FF.toInt(), 0xCC0B1B2A.toInt(), 14)
        }
        cmdTag = tv("LAST COMMAND", 9f, DIM, false, 0.2f)
        cmdText = tv("Say “Jarvis” to begin", 15f, Color.WHITE).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(cmdTag)
        card.addView(cmdText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        mainBtn = tv("", 15f, CYAN, true, 0.25f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            isClickable = true
            setOnClickListener { toggle() }
        }
        col.addView(mainBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun small(label: String, onClick: () -> Unit): TextView =
            tv(label, 11f, DIM, true, 0.15f).apply {
                setPadding(dp(14), dp(12), dp(14), dp(4))
                isClickable = true
                setOnClickListener { onClick() }
            }
        row.addView(small("PERMISSIONS") {
            startActivity(Intent(this@MainActivity, PermissionsActivity::class.java))
        })
        row.addView(small("SERVER") { showServerDialog() })
        row.addView(small("DEV LOGS") { toggleLogs() })
        col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // Developer logs (chhupa hua panel)
        debugText = tv("", 10f, 0xFF39FF88.toInt()).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        logScroll = ScrollView(this).apply { addView(debugText) }
        logPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2030A12.toInt())
            visibility = View.GONE
            setPadding(0, dp(40), 0, 0)
        }
        val closeBtn = tv("✕  CLOSE LOGS", 12f, CYAN, true, 0.2f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { toggleLogs() }
        }
        logPanel.addView(closeBtn, LinearLayout.LayoutParams(MATCH, WRAP))
        logPanel.addView(logScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(logPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun toggleLogs() {
        logPanel.visibility = if (logPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- halat
    private fun awake() = System.currentTimeMillis() < awakeUntil
    private fun idleState() = if (awake()) HudState.LISTENING else HudState.STANDBY

    private fun label(s: HudState): String = when (s) {
        HudState.OFF -> "OFFLINE"
        HudState.STANDBY -> "STANDBY  ·  SAY “JARVIS”"
        HudState.LISTENING -> "LISTENING"
        HudState.THINKING -> "PROCESSING"
        HudState.SPEAKING -> "SPEAKING"
        HudState.CALL -> "INCOMING CALL"
    }

    private fun setHud(s: HudState) {
        hud.setState(s)
        stateText.text = label(s)
        stateText.setTextColor(if (s == HudState.CALL) 0xFFFF9100.toInt() else CYAN)
        refreshChip()
    }

    private fun refreshChip() {
        if (running) {
            chip.text = "● ONLINE"
            chip.setTextColor(CYAN)
            chip.background = outline(0x8800E5FF.toInt(), 0x2200E5FF.toInt(), 20)
            mainBtn.text = "■  SHUT DOWN"
            mainBtn.setTextColor(ALERT)
            mainBtn.background = outline(ALERT, 0x22FF5252.toInt(), 14)
        } else {
            chip.text = "● OFFLINE"
            chip.setTextColor(DIM)
            chip.background = outline(0x446F8CA0.toInt(), Color.TRANSPARENT, 20)
            mainBtn.text = "▶  ACTIVATE JARVIS"
            mainBtn.setTextColor(CYAN)
            mainBtn.background = outline(CYAN, 0x2200E5FF.toInt(), 14)
        }
    }

    private fun showCmd(text: String, tag: String) {
        cmdTag.text = "LAST COMMAND  ·  $tag"
        cmdText.text = text
    }

    // Service ke logs padhkar dashboard badalta hai
    private fun onLog(line: String) {
        debugText.append(line + "\n")
        if (debugText.length() > 9000) debugText.text = debugText.text.takeLast(6000)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        if (!running) return

        when {
            line.contains("Listening... Speak now") -> setHud(idleState())
            line.contains("Silence detected") -> setHud(HudState.THINKING)
            line.contains("Jarvis bolna shuru") || line.contains("task update") -> setHud(HudState.SPEAKING)
            line.contains("Playback finished") -> setHud(idleState())
            line.contains("so raha hai") -> {
                awakeUntil = 0L
                setHud(HudState.STANDBY)
            }
            line.contains("Turant (phone par hi):") -> {
                awakeUntil = System.currentTimeMillis() + 120_000L
                showCmd(line.substringAfter("hi):").trim(), "ON-DEVICE  ⚡")
            }
            line.contains("User said:") -> {
                val txt = line.substringAfter("User said:").trim()
                if (txt.isNotBlank()) {
                    awakeUntil = System.currentTimeMillis() + 120_000L
                    showCmd(txt, "SERVER")
                }
            }
            line.contains("Phone aa raha hai") -> setHud(HudState.CALL)
            line.contains("Call khatam") -> setHud(idleState())
            line.contains("API Error") -> {
                chip.text = "● SERVER ERROR"
                chip.setTextColor(ALERT)
            }
        }
    }

    // ---------------------------------------------------------------- service
    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == AssistantService::class.java.name }
    }

    private fun toggle() {
        val i = Intent(this, AssistantService::class.java)
        if (!running) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            running = true
            setHud(HudState.STANDBY)
        } else {
            stopService(i)
            running = false
            awakeUntil = 0L
            setHud(HudState.OFF)
        }
    }

    // Kaggle ka naya ngrok URL yahin se badlo (code badalne ya build ki zaroorat nahi)
    private fun showServerDialog() {
        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val input = EditText(this).apply {
            setText(prefs.getString("base_url", "") ?: "")
            hint = "https://xxxx.ngrok-free.dev"
            setSingleLine(true)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        AlertDialog.Builder(this)
            .setTitle("Server URL (Kaggle ngrok)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                var u = input.text.toString().trim()
                u = u.removeSuffix("/chat_voice").trimEnd('/')
                if (u.isNotEmpty() && !u.startsWith("http")) u = "https://$u"
                prefs.edit().putString("base_url", u).apply()
                Toast.makeText(
                    this,
                    if (u.isEmpty()) "Default URL use hoga" else "URL save ho gaya",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }
}
