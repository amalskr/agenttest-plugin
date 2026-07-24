package testagent

interface LlmClient {
    fun complete(system: String, user: String, maxTokens: Int = 8000): String
}
