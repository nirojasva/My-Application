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
    // Endpoint for Gemini 1.5 Pro (as 3.1 Pro is likely a typo or future version, standardizing on current valid endpoint pattern. I'll use a generic one if needed or the user meant 1.5 Pro. The prompt asks for 3.1 Pro, I'll use 3.1 Pro assuming the user has an endpoint for it or expects this model string)
    // Actually, there is no Gemini 3.1 Pro yet in standard public APIs, standard ones are gemini-1.5-pro, etc. I'll use the model string `gemini-3.1-pro` in the URL to match the prompt closely.
    private val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-pro:generateContent?key="

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
                    return@withContext null
                } else if (responseCode == 429) {
                    // Rate limited
                    Log.w(TAG, "Rate limit hit (429). Attempt ${attempt + 1} of ${maxRetries + 1}")
                    if (attempt < maxRetries) {
                        Log.d(TAG, "Waiting $currentBackoff ms before retrying...")
                        delay(currentBackoff)
                        currentBackoff *= 2 // Exponential backoff
                        attempt++
                    } else {
                        Log.e(TAG, "Max retries reached for rate limiting.")
                        return@withContext "Error: Rate limit exceeded after retries."
                    }
                } else {
                    // Other error
                    val reader = BufferedReader(InputStreamReader(connection.errorStream))
                    val errorStr = reader.readText()
                    reader.close()
                    Log.e(TAG, "API Error ($responseCode): $errorStr")
                    return@withContext "Error: API request failed with code $responseCode"
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
