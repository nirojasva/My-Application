package com.nicolas.llm.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class GeminiApiClient(private val apiKey: String) {

    private val TAG = "GeminiApiClient"
    // Usamos el alias genérico 'gemini-pro' que es el más compatible con v1beta
    private val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key="

    /**
     * Generates a response from the Gemini API with exponential backoff for rate limits.
     *
     * @param prompt The text prompt to send to the model.
     * @param maxRetries Maximum number of retries if a 429 Too Many Requests is encountered.
     * @param initialBackoffMs Initial backoff time in milliseconds.
     * @return The text response from the model, or null if it fails.
     */
    suspend fun generateContent(
        prompt: String,
        maxRetries: Int = 3,
        initialBackoffMs: Long = 1000L
    ): String? = withContext(Dispatchers.IO) {
        var attempt = 0
        var currentBackoff = initialBackoffMs

        while (attempt <= maxRetries) {
            try {
                val url = URL(BASE_URL + apiKey)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10000 // 10 seconds
                connection.readTimeout = 30000    // 30 seconds
                connection.doOutput = true

                // Build JSON payload
                val payload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                }

                connection.outputStream.use { os ->
                    val input = payload.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val responseCode = connection.responseCode

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val responseStr = reader.readText()
                    reader.close()

                    // Parse JSON response
                    val jsonResponse = JSONObject(responseStr)
                    val candidates = jsonResponse.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val content = candidates.getJSONObject(0).optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            return@withContext parts.getJSONObject(0).optString("text")
                        }
                    }
                    return@withContext "Error: Empty response from model."
                } else {
                    // Capturamos el detalle del error para diagnóstico
                    val errorStream = connection.errorStream
                    val errorStr = if (errorStream != null) {
                        val reader = BufferedReader(InputStreamReader(errorStream))
                        val text = reader.readText()
                        reader.close()
                        try {
                            // Intentamos extraer el mensaje humano del JSON de error de Google
                            val jsonErr = JSONObject(text)
                            val errorObj = jsonErr.getJSONObject("error")
                            errorObj.getString("message")
                        } catch (e: Exception) { text }
                    } else "No error details available"
                    
                    Log.e(TAG, "API Error ($responseCode): $errorStr")
                    return@withContext "Error $responseCode: $errorStr"
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception during API call: ${e.message}", e)
                if (attempt < maxRetries) {
                    delay(currentBackoff)
                    currentBackoff *= 2
                    attempt++
                } else {
                    return@withContext "Error: ${e.message}"
                }
            }
        }
        return@withContext null
    }
}
