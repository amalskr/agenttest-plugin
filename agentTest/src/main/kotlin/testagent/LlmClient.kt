package testagent

interface LlmClient {
    fun complete(system: String, user: String, maxTokens: Int = 8000): String

    /** Cumulative usage for this run — for the 💰 telemetry line and budget cap. */
    val callCount: Int
    val inputTokens: Long
    val outputTokens: Long
}
