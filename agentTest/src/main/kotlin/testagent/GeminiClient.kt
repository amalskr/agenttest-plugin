package testagent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration

class GeminiClient(
    private val apiKey: String,
    private val model: String = "gemini-3.6-flash",
) : LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofMinutes(3))
        .writeTimeout(Duration.ofSeconds(60))
        .callTimeout(Duration.ofMinutes(4))
        .retryOnConnectionFailure(true)
        .build()

    override fun complete(system: String, user: String, maxTokens: Int): String {
        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))
                )
            )
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
            .toString()

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: error("Empty response from Gemini API")
            if (!resp.isSuccessful) error("Gemini API error ${resp.code}: ${text.take(500)}")

            val parts = JSONObject(text)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")

            return (0 until parts.length())
                .mapNotNull { parts.getJSONObject(it).optString("text").ifEmpty { null } }
                .joinToString("\n")
        }
    }
}
