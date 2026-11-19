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
}
