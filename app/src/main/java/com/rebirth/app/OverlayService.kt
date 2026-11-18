package com.rebirth.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {
    companion object {
        var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: BubbleView? = null
    private lateinit var panel: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var title: TextView
    private lateinit var tv: TextView
    private lateinit var input: EditText
    private var panelOpen = false
    private val history = mutableListOf<Pair<String, String>>()
    private var game: String? = null
    private var busy = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = getSharedPreferences("rebirth", MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        buildBubble()
        buildPanel()
        handler.post(scanner)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            if (panelOpen) wm.removeView(panel)
        } catch (e: Exception) {
        }
        try {
            bubble?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val ch = "rebirth"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ch, "Rebirth", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, ch)
            .setContentTitle("Rebirth çalışıyor")
            .setContentText("Dokun: ayarları aç")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun params(w: Int, h: Int, focusable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        return lp
    }

    private fun buildBubble() {
        val size = dp(56)
        val b = BubbleView(this)
        val lp = params(size, size, false)
        lp.x = resources.displayMetrics.widthPixels - size - dp(4)
        lp.y = resources.displayMetrics.heightPixels / 2
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = lp.x
                    oy = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (Math.abs(dx) + Math.abs(dy) > 12) moved = true
                    if (moved) {
                        lp.x = ox + dx.toInt()
                        lp.y = oy + dy.toInt()
                        wm.updateViewLayout(b, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                }
            }
            true
        }
        wm.addView(b, lp)
        bubble = b
    }

    private fun buildPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F015151F"))
                cornerRadius = dp(18).toFloat()
            }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        title = TextView(this).apply {
            text = "🔥 Rebirth"
            setTextColor(Color.parseColor("#FF7A00"))
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { togglePanel() }
        }
        head.addView(close)
        panel.addView(head)

        tv = TextView(this).apply {
            setTextColor(Color.parseColor("#EEEEEE"))
            textSize = 15f
            text = "Merhaba! Bir oyun açtığında tavsiye vereceğim. Bana soru da sorabilirsin.\n\n"
        }
        scroll = ScrollView(this)
        scroll.addView(tv)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        input = EditText(this).apply {
            hint = "Rebirth'e sor..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 15f
            setSingleLine(true)
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val send = Button(this).apply {
            text = "Gönder"
            setOnClickListener { sendChat() }
        }
        row.addView(send)
        panel.addView(row)
    }

    private fun togglePanel() {
        if (panelOpen) {
            wm.removeView(panel)
            panelOpen = false
        } else {
            val m = resources.displayMetrics
            val lp = params((m.widthPixels * 0.92).toInt(), (m.heightPixels * 0.5).toInt(), true)
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = dp(40)
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            wm.addView(panel, lp)
            panelOpen = true
        }
    }

    private fun append(who: String, text: String) {
        tv.append(who + ": " + text + "\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendChat() {
        val t = input.text.toString().trim()
        if (t.isEmpty() || busy) return
        input.setText("")
        append("Sen", t)
        ask(t)
    }

    private fun ask(text: String) {
        history.add("user" to text)
        busy = true
        val key = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        val sys = "Sen Rebirth adında bir oyun asistanısın. Türkçe, kısa, net ve uygulanabilir cevap ver. " +
            "Oyuncunun şu an oynadığı oyun: " + (game ?: "bilinmiyor") + "."
        val ctx = history.takeLast(12).toList()
        Thread {
            val out = Gemini.ask(key, model, ctx, sys)
            handler.post {
                history.add("model" to out)
                append("Rebirth", out)
                busy = false
                if (!panelOpen) {
                    Toast.makeText(this, "Rebirth: tavsiye hazır, daireye dokun", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private val scanner = object : Runnable {
        override fun run() {
            if (prefs.getBoolean("enabled", true) && GameDetector.hasAccess(this@OverlayService)) {
                val pkg = GameDetector.foreground(this@OverlayService)
                if (pkg != null) {
                    val g = GameDetector.gameName(this@OverlayService, pkg)
                    if (g != game) {
                        game = g
                        bubble?.active = g != null
                        title.text = if (g != null) "🎮 " + g else "🔥 Rebirth"
                        if (g != null && !busy) {
                            history.clear()
                            ask("'" + g + "' oynuyorum. Maksimum 5 madde: ipuçları, yaygın hatalar, ayar önerisi.")
                        }
                    }
                }
            }
            handler.postDelayed(this, 5000)
        }
    }
}
