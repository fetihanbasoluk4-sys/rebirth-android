package com.rebirth.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Base64
import android.util.DisplayMetrics
import java.io.ByteArrayOutputStream
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
    private var notif: Notification? = null
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    private var lastB64: String? = null
    private var pending: String? = null
    private lateinit var pendingLabel: TextView

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

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val data = i?.getParcelableExtra<Intent>("mp_data")
        val code = i?.getIntExtra("mp_code", 0) ?: 0
        if (data != null && code != 0 && projection == null) {
            goForeground(true)
            startProjection(code, data)
        }
        return START_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        cleanupProjection()
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
        notif = n
        goForeground(false)
    }

    private fun goForeground(withMp: Boolean) {
        val n = notif ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (withMp) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(1, n, t)
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

        pendingLabel = TextView(this).apply {
            text = "📎 Ekran görüntüsü ekli (kaldırmak için dokun)"
            setTextColor(Color.parseColor("#6FC3FF"))
            textSize = 13f
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { clearPending() }
        }
        panel.addView(pendingLabel)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val shot = Button(this).apply {
            text = "📷 Ekran al"
            isAllCaps = false
            setOnClickListener { captureScreen() }
        }
        val reply = Button(this).apply {
            text = "Yanıtla"
            isAllCaps = false
            setOnClickListener { answerScreen() }
        }
        actions.addView(shot, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(reply, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(actions)

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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun clearPending() {
        pending = null
        pendingLabel.visibility = View.GONE
    }

    private fun sendChat() {
        val t = input.text.toString().trim()
        if (busy) {
            toast("Bekle, cevap hazırlanıyor")
            return
        }
        if (t.isEmpty()) {
            if (pending != null) answerScreen()
            return
        }
        input.setText("")
        val img = pending
        clearPending()
        append("Sen", if (img != null) "📷 " + t else t)
        ask(t, img)
    }

    private fun answerScreen() {
        val img = pending
        if (img == null) {
            toast("Önce 📷 Ekran al'a bas")
            return
        }
        if (busy) {
            toast("Bekle, cevap hazırlanıyor")
            return
        }
        clearPending()
        append("Sen", "📷 Ekranı yanıtla")
        ask(
            "Ekran görüntüsüne bak. Ekranda oyun yoksa 'Ekranda oyun görmüyorum' de ve ne olduğunu " +
                "tek cümleyle yaz. Oyun varsa oyuncunun durumunu değerlendir ve şimdi ne yapması " +
                "gerektiğini kısa söyle.", img
        )
    }

    private fun ask(text: String, img: String? = null) {
        history.add("user" to (if (img != null) "[ekran görüntüsü] " + text else text))
        busy = true
        val key = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("model", "qwen/qwen3.8-27b") ?: "qwen/qwen3.8-27b"
        val sys = "Sen Rebirth adında bir oyun asistanısın. Türkçe, kısa, net ve uygulanabilir cevap ver. " +
            "Oyuncunun şu an oynadığı oyun: " + (game ?: "bilinmiyor") + "."
        val ctx = history.takeLast(12).toList()
        Thread {
            val out = Gemini.ask(key, model, ctx, sys, img)
            handler.post {
                history.add("model" to out)
                append("Rebirth", out)
                busy = false
                if (!panelOpen) {
                    Toast.makeText(this, "Rebirth: cevap hazır, daireye dokun", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------------- ekran goruntusu ----------------
    private fun screenSize(): IntArray {
        val real = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(real)
        val s = Math.min(1f, 1280f / Math.max(real.widthPixels, real.heightPixels))
        return intArrayOf((real.widthPixels * s).toInt(), (real.heightPixels * s).toInt(), real.densityDpi)
    }

    private fun startProjection(code: Int, data: Intent) {
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(code, data)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post { cleanupProjection() }
                }
            }, handler)
            val sz = screenSize()
            capW = sz[0]
            capH = sz[1]
            val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
            reader = r
            vd = mp.createVirtualDisplay(
                "rebirth", capW, capH, sz[2],
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
            )
            projection = mp
            toast("Ekran okuma hazır")
        } catch (e: Exception) {
            toast("Ekran okuma başlatılamadı: " + e.message)
        }
    }

    private fun cleanupProjection() {
        val p = projection
        projection = null
        try { vd?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        vd = null
        reader = null
        try { p?.stop() } catch (e: Exception) { }
    }

    private fun resizeIfNeeded() {
        val sz = screenSize()
        if (sz[0] == capW && sz[1] == capH) return
        val nr = ImageReader.newInstance(sz[0], sz[1], PixelFormat.RGBA_8888, 2)
        vd?.resize(sz[0], sz[1], sz[2])
        vd?.surface = nr.surface
        reader?.close()
        reader = nr
        capW = sz[0]
        capH = sz[1]
        Thread.sleep(600)
    }

    private fun grabFrame(): String? {
        resizeIfNeeded()
        val r = reader ?: return null
        var img = r.acquireLatestImage()
        if (img == null) {
            Thread.sleep(500)
            img = r.acquireLatestImage()
        }
        if (img == null) return lastB64
        val w = img.width
        val h = img.height
        val plane = img.planes[0]
        val pad = plane.rowStride - plane.pixelStride * w
        val bmp = Bitmap.createBitmap(w + pad / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        img.close()
        val crop = Bitmap.createBitmap(bmp, 0, 0, w, h)
        val out = ByteArrayOutputStream()
        crop.compress(Bitmap.CompressFormat.JPEG, 70, out)
        lastB64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return lastB64
    }

    private fun captureScreen() {
        if (vd == null) {
            toast("Ekran okuma izni yok. Rebirth uygulamasından ▶ Başlat'a basıp izni ver.")
            return
        }
        if (busy) {
            toast("Bekle, cevap hazırlanıyor")
            return
        }
        if (panelOpen) togglePanel()
        bubble?.visibility = View.INVISIBLE
        Thread {
            var b64: String? = null
            try {
                Thread.sleep(400)
                b64 = grabFrame()
            } catch (e: Exception) {
            }
            handler.post {
                bubble?.visibility = View.VISIBLE
                if (!panelOpen) togglePanel()
                if (b64 == null) {
                    append("Rebirth", "Ekran görüntüsü alınamadı, tekrar dene.")
                } else {
                    pending = b64
                    pendingLabel.visibility = View.VISIBLE
                    append("Rebirth", "📷 Ekran görüntüsü alındı. Bir şey yazıp Gönder'e bas ya da Yanıtla'ya bas.")
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
