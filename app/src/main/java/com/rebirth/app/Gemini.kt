package com.rebirth.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Gemini {
    fun ask(key: String, model: String, history: List<Pair<String, String>>, system: String): String {
        if (key.isBlank()) return "Önce Rebirth uygulamasını açıp Gemini API anahtarını gir."
        val contents = JSONArray()
        for ((role, text) in history) {
            contents.put(
                JSONObject().put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        val body = JSONObject()
            .put("contents", contents)
            .put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + key)
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) return "Hata " + code + ": " + txt.take(200)
            return JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        } catch (e: Exception) {
            return "Bağlantı hatası: " + e.message
        } finally {
            c.disconnect()
        }
    }
}
