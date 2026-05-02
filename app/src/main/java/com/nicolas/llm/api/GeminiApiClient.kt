package com.nicolas.llm.api

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Simplified Gemini Client following the "genai.Client()" logic.
 */
class GeminiApiClient(private val apiKey: String) {

    private val TAG = "GeminiApiClient"
    private val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"

    val models = Models()

    inner class Models {
        suspend fun generateContent(model: String, contents: String, image: Bitmap? = null): String = withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL$model:generateContent?key=${apiKey.trim()}")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 30000
                connection.doOutput = true

                val payload = JSONObject().apply {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", "You are a precise and helpful assistant. Use **Markdown** for bold text.") })
                        })
                    })
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", contents) })
                                image?.let {
                                    val stream = ByteArrayOutputStream()
                                    it.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                                    put(JSONObject().apply {
                                        put("inline_data", JSONObject().apply {
                                            put("mime_type", "image/jpeg")
                                            put("data", Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP))
                                        })
                                    })
                                }
                            })
                        })
                    })
                }

                connection.outputStream.use { it.write(payload.toString().toByteArray()) }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseStr = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(responseStr)
                    return@withContext json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                } else {
                    val errText = connection.errorStream?.bufferedReader()?.readText() ?: "No detail"
                    val message = try { JSONObject(errText).getJSONObject("error").getString("message") } catch (e: Exception) { "HTTP ${connection.responseCode}" }
                    return@withContext "Error: $message"
                }
            } catch (e: Exception) {
                return@withContext "Error: ${e.message}"
            }
        }
    }
}
