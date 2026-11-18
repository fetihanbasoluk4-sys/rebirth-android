package com.rebirth.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
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
    private lateinit var toggleBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.parseColor("#111111"))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#FF7A00"))
        bg.cornerRadius = dp(14).toFloat()
        b.background = bg
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(10)
        b.layoutParams = lp
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
        root.setPadding(dp(20), dp(40), dp(20), dp(30))

        val t = TextView(this)
        t.text = "🔥 REBIRTH"
        t.textSize = 30f
        t.setTextColor(Color.parseColor("#FF7A00"))
        root.addView(t)

        status = TextView(this)
        status.setTextColor(Color.parseColor("#CCCCCC"))
        status.textSize = 15f
        status.setPadding(0, dp(12), 0, dp(8))
        root.addView(status)

        root.addView(button("1) Üstte gösterme izni ver") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
            )
        })
        root.addView(button("2) Kullanım erişimi ver (Rebirth'i bul, aç)") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })

        keyBox = EditText(this)
        keyBox.hint = "Gemini API anahtarı"
        keyBox.setTextColor(Color.WHITE)
        keyBox.setHintTextColor(Color.GRAY)
        keyBox.setSingleLine(true)
        keyBox.setText(prefs.getString("api_key", ""))
        val klp = LinearLayout.LayoutParams(-1, -2)
        klp.topMargin = dp(14)
        root.addView(keyBox, klp)
        root.addView(button("3) Anahtarı kaydet") {
            prefs.edit().putString("api_key", keyBox.text.toString().trim()).apply()
            Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
            refresh()
        })

        toggleBtn = button("▶ Başlat") {
            if (OverlayService.running) {
                stopService(Intent(this, OverlayService::class.java))
            } else if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Önce 1. izni ver", Toast.LENGTH_LONG).show()
            } else {
                startForegroundService(Intent(this, OverlayService::class.java))
            }
            status.postDelayed({ refresh() }, 700)
        }
        root.addView(toggleBtn)

        val sw = Switch(this)
        sw.text = "Telefon açılınca otomatik başlat"
        sw.setTextColor(Color.WHITE)
        sw.isChecked = prefs.getBoolean("autostart", false)
        sw.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("autostart", v).apply() }
        val slp = LinearLayout.LayoutParams(-1, -2)
        slp.topMargin = dp(18)
        root.addView(sw, slp)

        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#0E0E15"))
        sv.addView(root)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        fun mark(ok: Boolean) = if (ok) "✓" else "✗"
        status.text = "Üstte gösterme izni: " + mark(Settings.canDrawOverlays(this)) +
            "\nKullanım erişimi: " + mark(GameDetector.hasAccess(this)) +
            "\nAPI anahtarı: " + mark(!prefs.getString("api_key", "").isNullOrBlank()) +
            "\nDurum: " + (if (OverlayService.running) "çalışıyor" else "kapalı")
        toggleBtn.text = if (OverlayService.running) "■ Durdur" else "▶ Başlat"
    }
}
