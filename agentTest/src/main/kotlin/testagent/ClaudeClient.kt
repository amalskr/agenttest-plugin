package testagent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration

class ClaudeClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-6",
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
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", user)
                )
            )
            .toString()

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: error("Empty response from Claude API")
            if (!resp.isSuccessful) error("Claude API error ${resp.code}: ${text.take(500)}")

            val content = JSONObject(text).getJSONArray("content")
            return (0 until content.length())
                .map { content.getJSONObject(it) }
                .filter { it.getString("type") == "text" }
                .joinToString("\n") { it.getString("text") }
        }
    }
}
