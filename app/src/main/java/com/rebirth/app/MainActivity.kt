package com.rebirth.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var keyBox: EditText
    private lateinit var startBtn: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: String, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(Color.parseColor(color))
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = round("#171722", 22)
        c.setPadding(dp(16), dp(14), dp(16), dp(16))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(14)
        parent.addView(c, lp)
        return c
    }

    private fun label(parent: LinearLayout, text: String, size: Float, color: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.parseColor(color))
        parent.addView(t)
        return t
    }

    private fun pill(parent: LinearLayout, text: String, color: String, textColor: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = text
        b.textSize = 15f
        b.gravity = Gravity.CENTER
        b.setTextColor(Color.parseColor(textColor))
        b.setTypeface(null, Typeface.BOLD)
        b.background = round(color, 26)
        b.setPadding(dp(16), dp(14), dp(16), dp(14))
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(10)
        parent.addView(b, lp)
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("rebirth", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(44), dp(20), dp(30))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(BubbleView(this), LinearLayout.LayoutParams(dp(68), dp(68)))
        val tb = LinearLayout(this)
        tb.orientation = LinearLayout.VERTICAL
        tb.setPadding(dp(14), 0, 0, 0)
        val t1 = label(tb, "REBIRTH", 30f, "#FF7A00")
        t1.setTypeface(null, Typeface.BOLD)
        label(tb, "Oyun asistanın", 14f, "#8A8AA0")
        head.addView(tb)
        root.addView(head)

        val c1 = card(root)
        label(c1, "DURUM", 12f, "#8A8AA0")
        status = label(c1, "", 15f, "#E8E8F0")
        status.setLineSpacing(0f, 1.35f)
        status.setPadding(0, dp(6), 0, 0)

        val c2 = card(root)
        label(c2, "KURULUM", 12f, "#8A8AA0")
        pill(c2, "1   Üstte gösterme izni", "#2A2A3A", "#FFFFFF") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        }
        pill(c2, "2   Kullanım erişimi (oyunu algılar)", "#2A2A3A", "#FFFFFF") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        pill(c2, "3   Mikrofon izni", "#2A2A3A", "#FFFFFF") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
        keyBox = EditText(this)
        keyBox.hint = "Groq API anahtarı"
        keyBox.setTextColor(Color.WHITE)
        keyBox.setHintTextColor(Color.GRAY)
        keyBox.setSingleLine(true)
        keyBox.background = round("#22222E", 16)
        keyBox.setPadding(dp(16), dp(12), dp(16), dp(12))
        keyBox.setText(prefs.getString("api_key", ""))
        val klp = LinearLayout.LayoutParams(-1, -2)
        klp.topMargin = dp(14)
        c2.addView(keyBox, klp)
        pill(c2, "4   Anahtarı kaydet", "#2A2A3A", "#FFFFFF") {
            prefs.edit().putString("api_key", keyBox.text.toString().trim()).apply()
            Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
            refresh()
        }

        startBtn = pill(root, "▶  Başlat", "#FF7A00", "#111111") {
            if (OverlayService.running) {
                stopService(Intent(this, OverlayService::class.java))
                status.postDelayed({ refresh() }, 700)
            } else if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Önce 1. izni ver", Toast.LENGTH_LONG).show()
            } else {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                startActivityForResult(mpm.createScreenCaptureIntent(), 100)
            }
        }
        val slp = startBtn.layoutParams as LinearLayout.LayoutParams
        slp.topMargin = dp(20)

        val c3 = card(root)
        val sw = Switch(this)
        sw.text = "Telefon açılınca otomatik başlat"
        sw.setTextColor(Color.WHITE)
        sw.isChecked = prefs.getBoolean("autostart", false)
        sw.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("autostart", v).apply() }
        c3.addView(sw)

        val sv = ScrollView(this)
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0E0E15"), Color.parseColor("#1A1024")))
        sv.background = bg
        sv.addView(root)
        setContentView(sv)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            val i = Intent(this, OverlayService::class.java)
            if (resultCode == RESULT_OK && data != null) {
                i.putExtra("mp_code", resultCode)
                i.putExtra("mp_data", data)
            } else {
                Toast.makeText(this, "Ekran okuma izni verilmedi, sadece sohbet çalışır", Toast.LENGTH_LONG).show()
            }
            startForegroundService(i)
            status.postDelayed({ refresh() }, 700)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        fun mark(ok: Boolean) = if (ok) "✅" else "⬜"
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        status.text = mark(Settings.canDrawOverlays(this)) + "  Üstte gösterme izni\n" +
            mark(GameDetector.hasAccess(this)) + "  Kullanım erişimi\n" +
            mark(micOk) + "  Mikrofon\n" +
            mark(!prefs.getString("api_key", "").isNullOrBlank()) + "  Groq anahtarı\n" +
            (if (OverlayService.running) "🟢  Çalışıyor" else "⚪  Kapalı")
        startBtn.text = if (OverlayService.running) "■  Durdur" else "▶  Başlat"
    }
}
