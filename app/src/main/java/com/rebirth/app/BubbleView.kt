package com.rebirth.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class BubbleView(ctx: Context) : View(ctx) {
    var active = false
        set(v) {
            field = v
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF7A00") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val flame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFF3C4") }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        ring.color = if (active) Color.parseColor("#2EE66B") else Color.parseColor("#FFB066")
        canvas.drawCircle(w / 2, h / 2, w / 2 - 4, fill)
        canvas.drawCircle(w / 2, h / 2, w / 2 - 4, ring)
        val p = Path()
        p.moveTo(w * .5f, h * .16f)
        p.quadTo(w * .74f, h * .45f, w * .70f, h * .62f)
        p.quadTo(w * .66f, h * .84f, w * .5f, h * .84f)
        p.quadTo(w * .34f, h * .84f, w * .30f, h * .62f)
        p.quadTo(w * .28f, h * .45f, w * .5f, h * .16f)
        p.close()
        canvas.drawPath(p, flame)
    }
}
