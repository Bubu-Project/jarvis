package com.example.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class HudState { OFF, STANDBY, LISTENING, THINKING, SPEAKING, CALL }

// Iron Man jaisa HUD: ghoomte ring, tick marks, awaaz ki lehrein aur beech mein arc-reactor jaisa core
class HudView(context: Context) : View(context) {
    private var state = HudState.OFF
    private var amp = 0.05f      // lehron ki unchai (halat ke saath dheere-dheere badalti hai)
    private var spin = 0.2f      // ring ki raftaar
    private var rot = 0f
    private var t = 0f
    private var lastNs = 0L
    private val d = resources.displayMetrics.density
    private val rect = RectF()
    private val path = Path()
    private var glow: RadialGradient? = null
    private var glowKey = 0L

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(16, 0, 229, 255)
    }

    fun setState(s: HudState) {
        state = s
    }

    private fun colorFor(s: HudState): Int = when (s) {
        HudState.OFF -> 0xFF3A5568.toInt()
        HudState.STANDBY -> 0xFF1E88C7.toInt()
        HudState.LISTENING -> 0xFF00E5FF.toInt()
        HudState.THINKING -> 0xFF29B6FF.toInt()
        HudState.SPEAKING -> 0xFF00F0FF.toInt()
        HudState.CALL -> 0xFFFF9100.toInt()
    }

    private fun ampTarget(): Float = when (state) {
        HudState.OFF -> 0.03f
        HudState.STANDBY -> 0.06f
        HudState.LISTENING -> 0.22f
        HudState.THINKING -> 0.10f
        HudState.SPEAKING -> 0.90f
        HudState.CALL -> 0.60f
    }

    private fun spinTarget(): Float = when (state) {
        HudState.OFF -> 0.12f
        HudState.STANDBY -> 0.30f
        HudState.LISTENING -> 0.80f
        HudState.THINKING -> 3.20f
        HudState.SPEAKING -> 1.40f
        HudState.CALL -> 2.00f
    }

    private fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    private fun rad(deg: Float): Double = deg * PI / 180.0

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
        lastNs = now
        t += dt
        amp += (ampTarget() - amp) * min(1f, dt * 4f)
        spin += (spinTarget() - spin) * min(1f, dt * 3f)
        rot += dt * 40f * spin

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) * 0.30f
        val col = colorFor(state)

        // ---- halka grid + neeche girti scan line ----
        val step = 40f * d
        var x = 0f
        while (x < w) {
            c.drawLine(x, 0f, x, h, grid)
            x += step
        }
        var y = 0f
        while (y < h) {
            c.drawLine(0f, y, w, y, grid)
            y += step
        }
        val sy = (t * 70f * d) % h
        stroke.strokeWidth = 1.5f * d
        stroke.color = alpha(col, 40)
        c.drawLine(0f, sy, w, sy, stroke)

        // ---- peeche ki chamak ----
        val key = (col.toLong() shl 20) xor (width.toLong() shl 10) xor height.toLong()
        if (glow == null || key != glowKey) {
            glowKey = key
            glow = RadialGradient(
                cx, cy, r * 1.9f,
                intArrayOf(alpha(col, 120), alpha(col, 40), alpha(col, 0)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }
        fill.shader = glow
        c.drawCircle(cx, cy, r * 1.9f, fill)
        fill.shader = null

        // ---- bahar ki tick marks ----
        for (i in 0 until 72) {
            val long = i % 6 == 0
            val r1 = r * 1.10f
            val r2 = r1 + if (long) 10f * d else 5f * d
            stroke.strokeWidth = 1.5f * d
            stroke.color = alpha(col, if (long) 200 else 110)
            c.save()
            c.rotate(i * 5f + rot * 0.15f, cx, cy)
            c.drawLine(cx, cy - r1, cx, cy - r2, stroke)
            c.restore()
        }

        // ---- awaaz ki lehrein (equalizer) ----
        for (i in 0 until 56) {
            val p1 = sin(t * 8f + i * 0.65f) * 0.5f + 0.5f
            val p2 = sin(t * 3.1f + i * 1.7f) * 0.5f + 0.5f
            val len = 4f * d + amp * 34f * d * p1 * (0.4f + 0.6f * p2)
            stroke.strokeWidth = 3f * d
            stroke.color = alpha(col, 210)
            c.save()
            c.rotate(i * (360f / 56f), cx, cy)
            c.drawLine(cx, cy - r * 1.26f, cx, cy - r * 1.26f - len, stroke)
            c.restore()
        }

        // ---- bahar ka poora ring ----
        stroke.strokeWidth = 1.5f * d
        stroke.color = alpha(col, 110)
        c.drawCircle(cx, cy, r, stroke)

        // ---- ghoomte hue tukde (segments) ----
        stroke.strokeWidth = 5f * d
        stroke.color = alpha(col, 230)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        for (i in 0 until 12) {
            c.drawArc(rect, rot + i * 30f, 20f, false, stroke)
        }
        val r2 = r * 0.82f
        stroke.strokeWidth = 2.5f * d
        stroke.color = alpha(col, 170)
        rect.set(cx - r2, cy - r2, cx + r2, cy + r2)
        for (i in 0 until 8) {
            c.drawArc(rect, -rot * 1.3f + i * 45f, 28f, false, stroke)
        }

        // ---- hexagon aur triangle ----
        val hr = r * 0.62f
        path.reset()
        for (k in 0..5) {
            val a = rad(-rot * 0.6f + k * 60f)
            val px = cx + hr * cos(a).toFloat()
            val py = cy + hr * sin(a).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        stroke.strokeWidth = 2f * d
        stroke.color = alpha(col, 150)
        c.drawPath(path, stroke)

        val tr = r * 0.46f
        path.reset()
        for (k in 0..2) {
            val a = rad(rot * 0.8f + k * 120f - 90f)
            val px = cx + tr * cos(a).toFloat()
            val py = cy + tr * sin(a).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        stroke.strokeWidth = 1.5f * d
        stroke.color = alpha(col, 130)
        c.drawPath(path, stroke)

        // ---- beech ka core (dhadakta hai) ----
        val pulse = 1f + 0.10f * amp * sin(t * 10f)
        fill.color = alpha(col, 70)
        c.drawCircle(cx, cy, r * 0.36f * pulse, fill)
        fill.color = alpha(col, 180)
        c.drawCircle(cx, cy, r * 0.24f * pulse, fill)
        fill.color = alpha(Color.WHITE, 235)
        c.drawCircle(cx, cy, r * 0.12f * pulse, fill)

        postInvalidateOnAnimation()
    }
}
