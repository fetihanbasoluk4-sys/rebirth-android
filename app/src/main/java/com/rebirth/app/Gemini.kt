package com.rebirth.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Groq kullaniyor (dosya adi eski kaldi). Gorsel destekli.
object Gemini {
    private const val MODEL = "qwen/qwen3.8-27b"

    private fun post(key: String, body: JSONObject): Pair<Int, String> {
        val c = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer " + key)
            c.setRequestProperty("User-Agent", "Rebirth-Android/1.0")
            c.doOutput = true
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return Pair(code, stream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    fun ask(
        key: String, model: String, history: List<Pair<String, String>>,
        system: String, imageB64: String? = null
    ): String {
        if (key.isBlank()) return "Önce Rebirth uygulamasını açıp Groq API anahtarını gir."
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        history.forEachIndexed { i, (role, text) ->
            val r = if (role == "model") "assistant" else "user"
            if (i == history.size - 1 && imageB64 != null && r == "user") {
                val parts = JSONArray()
                parts.put(JSONObject().put("type", "text").put("text", text))
                parts.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64," + imageB64)
                    )
                )
                messages.put(JSONObject().put("role", r).put("content", parts))
            } else {
                messages.put(JSONObject().put("role", r).put("content", text))
            }
        }
        val first = if (model.startsWith("gemini") || model.contains("llama-4") || model.isBlank()) MODEL else model
        val cands = listOf(first, "qwen/qwen3.8-27b", "qwen/qwen3.6-27b").distinct()
        var last = ""
        try {
            for (m in cands) {
                for (noThink in listOf(true, false)) {
                    if (noThink && !m.startsWith("qwen")) continue
                    val body = JSONObject().put("model", m).put("messages", messages).put("max_tokens", 1024)
                    if (noThink) body.put("reasoning_effort", "none")
                    val (code, txt) = post(key, body)
                    if (code in 200..299) {
                        val out = JSONObject(txt).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", "")
                        val clean = out.replace(Regex("(?s)<think>.*?</think>"), "").trim()
                        if (clean.isNotEmpty()) return clean
                        continue
                    }
                    if (code == 429) return "Limit doldu, 1 dakika bekleyip tekrar dene."
                    if (code == 401) return "API anahtarı yanlış. Uygulamadan Groq anahtarını gir."
                    if (code == 400 || code == 404) {
                        last = txt.take(200)
                        continue
                    }
                    return "Hata " + code + ": " + txt.take(200)
                }
            }
        } catch (e: Exception) {
            return "Bağlantı hatası: " + e.message
        }
        return "Uygun model bulunamadı. Son hata: " + last
    }

    // Ses dosyasini yaziya cevirir. Hata olursa "ERR:" ile baslayan metin doner.
    fun transcribe(key: String, file: java.io.File, hint: String = ""): String? {
        if (key.isBlank()) return "ERR:Önce Groq API anahtarını gir."
        val boundary = "----rebirth" + System.currentTimeMillis()
        val bytes = file.readBytes()
        for (m in listOf("whisper-large-v3-turbo", "whisper-large-v3")) {
            try {
                val c = URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Authorization", "Bearer " + key)
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
                c.setRequestProperty("User-Agent", "Rebirth-Android/1.0")
                c.connectTimeout = 20000
                c.readTimeout = 60000
                val out = c.outputStream
                fun field(name: String, value: String) {
                    out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").toByteArray())
                }
                field("model", m)
                field("language", "tr")
                field("response_format", "text")
                if (hint.isNotBlank()) field("prompt", hint)
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n").toByteArray())
                out.write(bytes)
                out.write(("\r\n--" + boundary + "--\r\n").toByteArray())
                out.flush()
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val txt = stream.bufferedReader().use { it.readText() }
                c.disconnect()
                if (code in 200..299) return txt.trim()
                if (code == 429) return "ERR:Limit doldu, biraz bekle."
                if (code == 401) return "ERR:API anahtarı yanlış."
            } catch (e: Exception) {
            }
        }
        return null
    }
}
