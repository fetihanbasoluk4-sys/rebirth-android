package com.rebirth.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale
import android.provider.ContactsContract
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.content.ComponentName
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject
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
    private lateinit var chatBox: LinearLayout
    private lateinit var mic: TextView
    private lateinit var speakBtn: TextView
    private lateinit var input: EditText
    private var panelOpen = false
    private val history = mutableListOf<Pair<String, String>>()
    private var game: String? = null
    private var busy = false
    private var notif: Notification? = null
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var recording = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var liveBad = true
    private var baseGame: String? = null
    private var identToken = 0
    private var recStart = 0L
    private var lastLoud = 0L
    private var heardVoice = false
    private var micAnim: ValueAnimator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakOn = false
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
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("tr", "TR")
                ttsReady = true
            }
        }
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
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        try { recorder?.release() } catch (e: Exception) { }
        try { recognizer?.destroy() } catch (e: Exception) { }
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
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(1, n, t)
            } catch (e: Exception) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
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
        val size = dp(62)
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

    private fun chip(label: String, color: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 14f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(10), dp(14), dp(10))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor(color))
        bg.cornerRadius = dp(20).toFloat()
        t.background = bg
        t.setOnClickListener { onClick() }
        return t
    }

    private fun ovalBg(color: String): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(Color.parseColor(color))
        return g
    }

    private fun micStyle(rec: Boolean) {
        mic.background = if (rec) ovalBg("#FFD0D0") else null
        mic.text = if (rec) "⏹" else "🎤"
        bubble?.mode = if (rec) 1 else 0
        micAnim?.cancel()
        mic.scaleX = 1f
        mic.scaleY = 1f
        if (rec) {
            val a = ValueAnimator.ofFloat(1f, 1.18f)
            a.duration = 550
            a.repeatMode = ValueAnimator.REVERSE
            a.repeatCount = ValueAnimator.INFINITE
            a.addUpdateListener {
                mic.scaleX = it.animatedValue as Float
                mic.scaleY = it.animatedValue as Float
            }
            a.start()
            micAnim = a
        }
    }

    private fun iconBtn(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 20f
        t.gravity = Gravity.CENTER
        t.setOnClickListener { onClick() }
        return t
    }

    private fun buildPanel() {
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(12), dp(12), dp(12), dp(12))
        val pbg = GradientDrawable()
        pbg.setColor(Color.parseColor("#F2E6E6EA"))
        pbg.cornerRadius = dp(28).toFloat()
        panel.background = pbg

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(6), 0, 0, 0)
        title = TextView(this)
        title.text = "Rebirth"
        title.setTextColor(Color.parseColor("#1A1A1F"))
        title.textSize = 17f
        title.setTypeface(null, Typeface.BOLD)
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        speakBtn = TextView(this)
        speakBtn.text = "🔊"
        speakBtn.textSize = 19f
        speakBtn.alpha = 0.4f
        speakBtn.setPadding(dp(10), 0, dp(10), 0)
        speakBtn.setOnClickListener {
            speakOn = !speakOn
            speakBtn.alpha = if (speakOn) 1f else 0.4f
            if (!speakOn) tts?.stop()
            toast(if (speakOn) "Tüm cevaplar sesli okunacak" else "Sadece sesli sorulara sesli cevap")
        }
        head.addView(speakBtn)
        val close = TextView(this)
        close.text = "✕"
        close.setTextColor(Color.parseColor("#1A1A1F"))
        close.textSize = 19f
        close.setPadding(dp(10), 0, dp(6), 0)
        close.setOnClickListener { togglePanel() }
        head.addView(close)
        panel.addView(head)

        chatBox = LinearLayout(this)
        chatBox.orientation = LinearLayout.VERTICAL
        scroll = ScrollView(this)
        scroll.addView(chatBox)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        pendingLabel = TextView(this)
        pendingLabel.text = "📎 Ekran görüntüsü ekli (kaldırmak için dokun)"
        pendingLabel.setTextColor(Color.parseColor("#2962FF"))
        pendingLabel.textSize = 13f
        pendingLabel.visibility = View.GONE
        pendingLabel.setPadding(dp(8), dp(6), 0, dp(6))
        pendingLabel.setOnClickListener { clearPending() }
        panel.addView(pendingLabel)

        // beyaz yazi karti (Figma sablonu gibi)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val cbg = GradientDrawable()
        cbg.setColor(Color.WHITE)
        cbg.cornerRadius = dp(26).toFloat()
        card.background = cbg
        card.setPadding(dp(16), dp(10), dp(10), dp(8))

        input = EditText(this)
        input.hint = "Ne öğrenmek istersin?"
        input.setTextColor(Color.parseColor("#111111"))
        input.setHintTextColor(Color.parseColor("#9A9AA5"))
        input.textSize = 16f
        input.setSingleLine(true)
        input.setBackgroundColor(Color.TRANSPARENT)
        input.setPadding(0, dp(6), 0, dp(10))
        card.addView(input, LinearLayout.LayoutParams(-1, -2))

        val icons = LinearLayout(this)
        icons.orientation = LinearLayout.HORIZONTAL
        icons.gravity = Gravity.CENTER_VERTICAL
        icons.addView(iconBtn("🖼") { captureScreen() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        icons.addView(iconBtn("✨") { answerScreen() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        mic = TextView(this)
        mic.textSize = 20f
        mic.gravity = Gravity.CENTER
        mic.setOnClickListener { toggleMic() }
        icons.addView(mic, LinearLayout.LayoutParams(dp(44), dp(44)))
        icons.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val sendBtn = TextView(this)
        sendBtn.text = "↑"
        sendBtn.textSize = 22f
        sendBtn.setTextColor(Color.WHITE)
        sendBtn.setTypeface(null, Typeface.BOLD)
        sendBtn.gravity = Gravity.CENTER
        sendBtn.background = ovalBg("#C9C9D0")
        sendBtn.setOnClickListener { sendChat() }
        icons.addView(sendBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        card.addView(icons)
        val cl = LinearLayout.LayoutParams(-1, -2)
        cl.topMargin = dp(8)
        panel.addView(card, cl)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                sendBtn.background = ovalBg(if (s.isNullOrBlank()) "#C9C9D0" else "#111111")
            }
        })

        micStyle(false)
        append("Rebirth", "Merhaba! 🖼 ile ekranı al, 🎤 ile konuş ya da yaz. Telefonu da kontrol ederim: 'YouTube'u aç', 'sesi kıs' de.")
    }

    private fun togglePanel() {
        if (panelOpen) {
            wm.removeView(panel)
            panelOpen = false
        } else {
            val m = resources.displayMetrics
            val lp = params((m.widthPixels * 0.94).toInt(), (m.heightPixels * 0.64).toInt(), true)
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = dp(40)
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            wm.addView(panel, lp)
            panelOpen = true
        }
    }

    private fun append(who: String, text: String) {
        val ai = who == "Rebirth"
        val t = TextView(this)
        t.text = text
        t.textSize = 15f
        t.setTextColor(Color.parseColor("#1A1A1F"))
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
        val lp = LinearLayout.LayoutParams(-2, -2)
        if (ai) {
            lp.gravity = Gravity.START
            lp.rightMargin = dp(30)
        } else {
            val bg = GradientDrawable()
            bg.setColor(Color.WHITE)
            bg.cornerRadius = dp(18).toFloat()
            t.background = bg
            lp.gravity = Gravity.END
            lp.leftMargin = dp(40)
        }
        lp.topMargin = dp(6)
        chatBox.addView(t, lp)
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
        ask(t, img, false, img == null)
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

    private val ACTIONS = """ Kullanıcı telefonda bir işlem yapmanı isterse (uygulama aç, arama, yol tarifi, alarm, zamanlayıcı, ses, el feneri, ayar, müzik kontrolü), SADECE tek satır JSON döndür, başka hiçbir şey yazma. Biçimler: {"action":"open_app","app":"uygulama adı"} {"action":"web_search","query":"..."} {"action":"youtube_search","query":"..."} {"action":"navigate","place":"..."} {"action":"call","name":"kişi adı"} (numara söylenirse {"action":"call","number":"..."}) {"action":"alarm","hour":7,"minute":30} {"action":"timer","seconds":300} {"action":"settings","page":"wifi|bluetooth|display|sound|battery|apps|general"} {"action":"volume","dir":"up|down|mute"} {"action":"flashlight","on":true} {"action":"media","cmd":"play_pause|next|previous"} {"action":"open_url","url":"https://..."} {"action":"remember","note":"hatırlanacak bilgi"} (kullanıcı hatırla/aklında tut derse). Komut değilse normal cevap ver."""

    private fun ask(text: String, img: String? = null, byVoice: Boolean = false, allowActions: Boolean = false) {
        history.add("user" to (if (img != null) "[ekran görüntüsü] " + text else text))
        busy = true
        bubble?.mode = 2
        val key = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("model", "qwen/qwen3.8-27b") ?: "qwen/qwen3.8-27b"
        val sys = "Sen Rebirth: telefondaki akıllı oyun ve telefon asistanısın. Türkçe konuş, kısa ve net ol (en fazla 6 cümle ya da 5 madde). " +
            "Mobil oyunlarda uzman bir koç gibi davran: strateji, build, ayar ve hata çözümü için somut, uygulanabilir tavsiye ver. " +
            "Emin olmadığın bilgiyi uydurma, bilmiyorsan açıkça söyle. " +
            "Oyuncunun şu an oynadığı oyun: " + (game ?: "bilinmiyor") + "." + memoryText() +
            (if (allowActions) ACTIONS + appHint() else "")
        val think = !(allowActions && looksLikeCommand(text))
        if (think) toast("Düşünüyorum...")
        val ctx = history.takeLast(20).toList()
        Thread {
            var out = Gemini.ask(key, model, ctx, sys, img, think)
            if (think && out.startsWith("Uygun model")) out = Gemini.ask(key, model, ctx, sys, img, false)
            handler.post {
                val done = (if (allowActions) runAction(out) else null) ?: out
                history.add("model" to done)
                append("Rebirth", done)
                busy = false
                bubble?.mode = 0
                if (byVoice || speakOn) speak(done)
                if (!panelOpen) {
                    Toast.makeText(this, "Rebirth: cevap hazır, daireye dokun", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------------- akilli yardimcilar ----------------
    private fun memoryText(): String {
        val m = prefs.getString("memory", "") ?: ""
        return if (m.isBlank()) "" else " Kullanıcı hakkında bildiklerin: " + m.replace("\n", "; ") + "."
    }

    private fun looksLikeCommand(t: String): Boolean {
        if (t.trim().split(" ").size > 9) return false
        val n = norm(t)
        val keys = listOf("ac", "ara", "kapat", "kur", "baslat", "sesi", "fener", "alarm", "zamanlayici",
            "yol", "git", "oynat", "durdur", "sonraki", "onceki", "ayar", "hatirla", "aklinda")
        return keys.any { n.contains(it) }
    }

    private fun findContact(name: String): Pair<String, String>? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val q = norm(name)
        if (q.isEmpty()) return null
        var best: Pair<String, String>? = null
        var bestScore = 0
        var bestD = 99
        val c = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        ) ?: return null
        c.use {
            while (it.moveToNext()) {
                val dn = it.getString(0) ?: continue
                val num = it.getString(1) ?: continue
                val n = norm(dn)
                if (n.isEmpty()) continue
                val score = when {
                    n == q -> 4
                    n.startsWith(q) || q.startsWith(n) -> 3
                    n.contains(q) || q.contains(n) -> 2
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = Pair(dn, num)
                } else if (score == 0 && bestScore == 0) {
                    val d = lev(q, n)
                    if (d < bestD && d <= Math.max(1, q.length / 3)) {
                        bestD = d
                        best = Pair(dn, num)
                    }
                }
            }
        }
        return best
    }

    private fun identifyGame(label: String, tok: Int, retry: Boolean = true) {
        if (tok != identToken || vd == null) return
        val key = prefs.getString("api_key", "") ?: ""
        if (key.isBlank()) return
        Thread {
            try {
                val b64 = grabFrame()
                if (b64 != null) {
                    val q = "Bu ekran görüntüsü '" + label + "' uygulamasından. Hangi oyun, mod ya da harita olduğunu tespit et. " +
                        "SADECE oyunun adını yaz (en fazla 5 kelime). Emin değilsen sadece 'bilinmiyor' yaz."
                    val out = Gemini.ask(key, "qwen/qwen3.8-27b", listOf("user" to q), "Sen bir oyun tanıma uzmanısın.", b64)
                        .trim().trim('"', '.', '*')
                    val bad = out.isEmpty() || out.length > 50 || out.lowercase().contains("bilinmiyor") ||
                        out.startsWith("Hata") || out.startsWith("Limit") || out.startsWith("API") ||
                        out.startsWith("Bağlantı") || out.startsWith("Uygun") || out.startsWith("Önce")
                    handler.post {
                        if (tok == identToken) {
                            if (!bad) {
                                game = if (norm(out).contains(norm(label))) out else label + " — " + out
                                title.text = "🎮 " + game
                            } else if (retry) {
                                handler.postDelayed({ identifyGame(label, tok, false) }, 25000)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    // ---------------- telefon kontrolu ----------------
    private val aliases = mapOf(
        "ayarlar" to "settings", "kamera" to "camera", "rehber" to "contacts",
        "telefon" to "phone", "mesajlar" to "messages", "saat" to "clock",
        "takvim" to "calendar", "galeri" to "gallery", "hesapmakinesi" to "calculator",
        "harita" to "maps", "haritalar" to "maps", "tarayici" to "chrome"
    )

    private fun norm(s: String): String {
        var t = s.lowercase(Locale("tr", "TR"))
        t = t.replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        return t.replace(Regex("[^a-z0-9]"), "")
    }

    private fun findApp(query: String): Pair<String, String>? {
        val q = norm(query)
        if (q.isEmpty()) return null
        val qs = listOfNotNull(q, aliases[q])
        val pm = packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var best: Pair<String, String>? = null
        var bestScore = 0
        for (ri in list) {
            val label = ri.loadLabel(pm).toString()
            val n = norm(label)
            if (n.isEmpty()) continue
            for (cand in qs) {
                val score = when {
                    n == cand -> 3
                    n.startsWith(cand) || (cand.startsWith(n) && n.length > 2) -> 2
                    n.contains(cand) -> 1
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = Pair(ri.activityInfo.packageName, label)
                }
            }
        }
        if (best == null) {
            var bestD = 99
            for (ri in list) {
                val n = norm(ri.loadLabel(pm).toString())
                if (n.isEmpty()) continue
                val d = lev(q, n)
                if (d < bestD && d <= Math.max(2, q.length / 3)) {
                    bestD = d
                    best = Pair(ri.activityInfo.packageName, ri.loadLabel(pm).toString())
                }
            }
        }
        return best
    }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = Math.min(Math.min(dp[j] + 1, dp[j - 1] + 1), prev + (if (a[i - 1] == b[j - 1]) 0 else 1))
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun appNames(): List<String> {
        val pm = packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return list.map { it.loadLabel(pm).toString() }.filter { it.isNotBlank() }.distinct().sorted()
    }

    private fun appHint(): String {
        return " Kullanıcı sesle konuşmuş olabilir ve yazı yanlış duyulmuş olabilir (örn. 'tubueyi' = YouTube). " +
            "open_app için aşağıdaki listeden en yakın uygulamanın tam adını yaz. Yüklü uygulamalar: " +
            appNames().joinToString(", ").take(1500)
    }

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun runAction(out: String): String? {
        var s = out.trim()
        if (s.startsWith("```")) s = s.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (!s.startsWith("{") || !s.endsWith("}")) return null
        val j = try { JSONObject(s) } catch (e: Exception) { return null }
        val a = j.optString("action")
        if (a.isEmpty()) return null
        return try {
            doAction(a, j)
        } catch (e: Exception) {
            "Yapamadım: " + e.message
        }
    }

    private fun doAction(a: String, j: JSONObject): String {
        when (a) {
            "open_app" -> {
                val q = j.optString("app")
                val app = findApp(q) ?: return "'" + q + "' adlı uygulamayı bulamadım."
                val li = packageManager.getLaunchIntentForPackage(app.first) ?: return "Açamadım: " + q
                launch(li)
                return "✅ " + app.second + " açılıyor"
            }
            "web_search" -> {
                val q = j.optString("query")
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
                return "🔎 Aranıyor: " + q
            }
            "youtube_search" -> {
                val q = j.optString("query")
                try {
                    launch(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q))
                } catch (e: Exception) {
                    launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
                }
                return "▶ YouTube'da aranıyor: " + q
            }
            "navigate" -> {
                val p = j.optString("place")
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(p))))
                return "🧭 Haritada açılıyor: " + p
            }
            "call" -> {
                var n = j.optString("number")
                var who = ""
                val nm = j.optString("name")
                if (n.isBlank() && nm.isNotBlank()) {
                    val c = findContact(nm) ?: return "'" + nm + "' adında kişi bulamadım (Rehber izni verdin mi?)"
                    n = c.second
                    who = c.first
                }
                if (n.isBlank()) return "Kimi arayayım?"
                launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))
                return "📞 " + (if (who.isNotEmpty()) who + " " else "") + n + " çeviriciye yazıldı, aramak için yeşil tuşa bas"
            }
            "alarm" -> {
                val h = j.optInt("hour", 7)
                val m = j.optInt("minute", 0)
                launch(
                    Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h)
                        .putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                )
                return "⏰ Alarm kuruldu: " + String.format("%02d:%02d", h, m)
            }
            "timer" -> {
                val sec = j.optInt("seconds", 60)
                launch(
                    Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                )
                return "⏱ Zamanlayıcı başladı: " + sec + " saniye"
            }
            "settings" -> {
                val act = when (j.optString("page")) {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                launch(Intent(act))
                return "⚙ Ayarlar açıldı"
            }
            "volume" -> {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                val d = when (j.optString("dir")) {
                    "up" -> AudioManager.ADJUST_RAISE
                    "down" -> AudioManager.ADJUST_LOWER
                    else -> AudioManager.ADJUST_MUTE
                }
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI)
                return "🔊 Ses ayarlandı"
            }
            "flashlight" -> {
                val on = j.optBoolean("on", true)
                val cm = getSystemService(CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return "Bu telefonda el feneri yok"
                cm.setTorchMode(id, on)
                return if (on) "🔦 El feneri açıldı" else "🔦 El feneri kapandı"
            }
            "media" -> {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                val code = when (j.optString("cmd")) {
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                return "🎵 Müzik komutu gönderildi"
            }
            "remember" -> {
                val note = j.optString("note")
                if (note.isBlank()) return "Ne hatırlayayım?"
                val cur = (prefs.getString("memory", "") ?: "").split("\n").filter { it.isNotBlank() }
                prefs.edit().putString("memory", (cur + note).takeLast(20).joinToString("\n")).apply()
                return "🧠 Hatırlayacağım: " + note
            }
            "open_url" -> {
                val u = j.optString("url")
                if (!u.startsWith("http")) return "Sadece web adreslerini açabilirim"
                launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                return "🌐 Açılıyor: " + u
            }
        }
        return "Bu komutu yapamıyorum."
    }

    // ---------------- ses ----------------
    private fun speak(text: String) {
        if (!ttsReady) return
        val clean = text.replace(Regex("[*#_`>]"), "").take(700)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "rebirth")
    }

    private val silenceWatcher = object : Runnable {
        override fun run() {
            if (!recording) return
            val amp = try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }
            val now = System.currentTimeMillis()
            if (amp > 2000) {
                heardVoice = true
                lastLoud = now
            }
            if (heardVoice && now - lastLoud > 1300) {
                stopAndTranscribe()
                return
            }
            if (!heardVoice && now - recStart > 6000) {
                cancelRecording()
                return
            }
            if (now - recStart > 12000) {
                stopAndTranscribe()
                return
            }
            handler.postDelayed(this, 150)
        }
    }

    private fun cancelRecording() {
        recording = false
        micStyle(false)
        val r = recorder
        recorder = null
        try { r?.stop() } catch (e: Exception) { }
        try { r?.release() } catch (e: Exception) { }
        toast("Ses duyulmadı, tekrar dene")
    }

    private fun toggleMic() {
        if (listening) {
            recognizer?.stopListening()
            return
        }
        if (recording) stopAndTranscribe() else startRecording()
    }

    private fun submitVoice(text: String) {
        if (busy) return
        val img = pending
        clearPending()
        input.setText("")
        append("Sen", "🎤 " + text)
        ask(text, img, true, img == null)
    }

    private fun startLive(useGoogle: Boolean = false) {
        try {
            recognizer?.destroy()
            val r = if (useGoogle) {
                SpeechRecognizer.createSpeechRecognizer(
                    this,
                    ComponentName(
                        "com.google.android.googlequicksearchbox",
                        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
                    )
                )
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onPartialResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank()) {
                        input.setText(t)
                        input.setSelection(input.text.length)
                    }
                }

                override fun onResults(b: Bundle?) {
                    listening = false
                    micStyle(false)
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) {
                        toast("Anlayamadım, tekrar dene")
                        return
                    }
                    liveBad = false
                    input.setText(t)
                    submitVoice(t)
                }

                override fun onError(e: Int) {
                    listening = false
                    micStyle(false)
                    if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        toast("Ses duyulmadı, tekrar dene")
                    } else if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        toast("Mikrofon izni yok")
                    } else {
                        if (!useGoogle) {
                            handler.postDelayed({ startLive(true) }, 300)
                        } else {
                            liveBad = true
                            toast("Canlı ses tanıma çalışmadı (" + e + "), yedek yönteme geçildi")
                            handler.postDelayed({ startWhisper() }, 300)
                        }
                    }
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            input.setText("")
            r.startListening(i)
            recognizer = r
            listening = true
            micStyle(true)
        } catch (e: Exception) {
            listening = false
            startWhisper()
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Mikrofon izni yok. Rebirth uygulamasında 'Mikrofon izni' düğmesine bas.")
            return
        }
        if (busy) {
            toast("Bekle, cevap hazırlanıyor")
            return
        }
        tts?.stop()
        if (!liveBad && SpeechRecognizer.isRecognitionAvailable(this)) startLive() else startWhisper()
    }

    private fun startWhisper() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Mikrofon izni yok. Rebirth uygulamasında 'Mikrofon izni' düğmesine bas.")
            return
        }
        if (busy) {
            toast("Bekle, cevap hazırlanıyor")
            return
        }
        tts?.stop()
        try {
            val f = File(cacheDir, "voice.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(96000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            audioFile = f
            recording = true
            micStyle(true)
            recStart = System.currentTimeMillis()
            lastLoud = recStart
            heardVoice = false
            input.setText("")
            input.hint = "🎤 Dinliyorum... konuş"
            handler.postDelayed(silenceWatcher, 400)
        } catch (e: Exception) {
            recorder = null
            toast("Mikrofon başlatılamadı: " + e.message)
        }
    }

    private fun stopAndTranscribe() {
        if (!recording) return
        recording = false
        input.hint = "Ne öğrenmek istersin?"
        micStyle(false)
        val r = recorder
        recorder = null
        try {
            r?.stop()
        } catch (e: Exception) {
            try { r?.release() } catch (x: Exception) { }
            toast("Kayıt çok kısa, tekrar dene")
            return
        }
        r?.release()
        val f = audioFile ?: return
        val key = prefs.getString("api_key", "") ?: ""
        busy = true
        toast("Dinliyorum...")
        val hint = "Telefon komutları: YouTube'u aç, WhatsApp'ı aç. Uygulamalar: " + appNames().joinToString(", ").take(600)
        Thread {
            val res = Gemini.transcribe(key, f, hint)
            handler.post {
                busy = false
                if (res == null || res.isBlank() || res.startsWith("ERR:")) {
                    toast(if (res != null && res.startsWith("ERR:")) res.substring(4) else "Sesi anlayamadım, tekrar dene")
                } else {
                    input.setText(res)
                    input.setSelection(input.text.length)
                    handler.postDelayed({ submitVoice(res) }, 900)
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
                    if (g != baseGame) {
                        baseGame = g
                        game = g
                        identToken++
                        bubble?.active = g != null
                        title.text = if (g != null) "🎮 " + g else "🔥 Rebirth"
                        if (g != null) {
                            history.clear()
                            val tok = identToken
                            handler.postDelayed({ identifyGame(g, tok) }, 10000)
                        }
                    }
                }
            }
            handler.postDelayed(this, 5000)
        }
    }
}
