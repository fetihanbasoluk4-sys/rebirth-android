package com.rebirth.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

// Donen parcacik kuresi (orb). mode: 0 bosta, 1 dinliyor, 2 dusunuyor
class BubbleView(ctx: Context) : View(ctx) {
    var active = false
    var mode = 0

    private val n = 170
    private val px = FloatArray(n)
    private val py = FloatArray(n)
    private val pz = FloatArray(n)
    private var angle = 0f
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E80B0B12") }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val ticker = object : Runnable {
        override fun run() {
            angle += when (mode) {
                1 -> 0.10f
                2 -> 0.14f
                else -> 0.035f
            }
            invalidate()
            postDelayed(this, 50)
        }
    }

    init {
        for (i in 0 until n) {
            val y = 1f - 2f * (i + 0.5f) / n
            val r = Math.sqrt((1f - y * y).toDouble()).toFloat()
            val th = i * 2.3999632f
            px[i] = (Math.cos(th.toDouble()) * r).toFloat()
            py[i] = y
            pz[i] = (Math.sin(th.toDouble()) * r).toFloat()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val dens = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        val big = w / 2 - 3f
        val c = when {
            mode == 1 -> Color.parseColor("#FF3D3D")
            mode == 2 -> Color.parseColor("#B388FF")
            active -> Color.parseColor("#2EE66B")
            else -> Color.parseColor("#FF7A00")
        }
        canvas.drawCircle(cx, cy, big, bg)
        glow.shader = RadialGradient(
            cx, cy, big, intArrayOf((c and 0x00FFFFFF) or 0x66000000, 0x00000000),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, big, glow)
        ring.color = c
        canvas.drawCircle(cx, cy, big, ring)

        val pulse = if (mode == 1) 1f + 0.08f * Math.sin((angle * 4).toDouble()).toFloat() else 1f
        val rr = big * 0.80f * pulse
        val ca = Math.cos(angle.toDouble()).toFloat()
        val sa = Math.sin(angle.toDouble()).toFloat()
        val ct = 0.94f
        val st = 0.34f
        for (i in 0 until n) {
            val x1 = px[i] * ca + pz[i] * sa
            val z1 = -px[i] * sa + pz[i] * ca
            val y2 = py[i] * ct - z1 * st
            val z2 = py[i] * st + z1 * ct
            val d = (z2 + 1f) / 2f
            dot.alpha = (50 + 205 * d).toInt()
            canvas.drawCircle(cx + x1 * rr, cy + y2 * rr, (0.6f + 0.9f * d) * dens, dot)
        }
    }
}
