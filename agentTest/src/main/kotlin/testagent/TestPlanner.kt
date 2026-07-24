package testagent

/**
 * Deterministic static analysis → required-test-case plan.
 * Regex-based signature/branch extraction ("AST-lite") — zero LLM calls.
 * The plan is injected into generation prompts as a minimum coverage contract,
 * which kills run-to-run variance and guarantees boundary/branch coverage.
 */
object TestPlanner {

    fun plan(sourceCode: String): String? {
        val sb = StringBuilder()

        // ---- public function signatures ----
        val fnRegex = Regex("fun\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?::\\s*([\\w?<>., ]+))?")
        fnRegex.findAll(sourceCode)
            .filter { fn ->
                val lineStart = sourceCode.lastIndexOf('\n', fn.range.first) + 1
                val prefix = sourceCode.substring(lineStart, fn.range.first)
                !prefix.contains("private") && !prefix.contains("@Composable")
            }
            .forEach { fn ->
                val name = fn.groupValues[1]
                if (name.firstOrNull()?.isUpperCase() == true) return@forEach // skip constructors/composables

                val params = fn.groupValues[2].split(',').mapNotNull { p ->
                    val parts = p.trim().split(':')
                    if (parts.size >= 2) parts[0].trim() to parts[1].trim().substringBefore('=').trim()
                    else null
                }
                val returnType = fn.groupValues[3].trim()

                sb.appendLine("Function $name(${params.joinToString { "${it.first}: ${it.second}" }})${if (returnType.isNotEmpty()) ": $returnType" else ""}")
                params.forEach { (pName, pType) ->
                    when {
                        pType.startsWith("String?") ->
                            sb.appendLine("  - $pName: null, empty \"\", blank whitespace-only, typical valid value")
                        pType.startsWith("String") ->
                            sb.appendLine("  - $pName: empty \"\", blank whitespace-only, typical valid value, value with leading/trailing spaces")
                        pType in listOf("Int", "Long", "Double", "Float", "Int?", "Long?") ->
                            sb.appendLine("  - $pName: zero, negative, and boundary values around any literal it is compared to (N-1, N, N+1)")
                        pType.startsWith("Boolean") ->
                            sb.appendLine("  - $pName: both true and false")
                        pType.startsWith("List") || pType.startsWith("Set") ||
                            pType.startsWith("Collection") || pType.startsWith("Map") ->
                            sb.appendLine("  - $pName: empty and non-empty")
                    }
                }
                if (returnType.endsWith("?")) {
                    sb.appendLine("  - include at least one input producing a null return and one producing non-null")
                }
            }

        // ---- when-branch coverage ----
        val whenCount = Regex("\\bwhen\\s*[({]").findAll(sourceCode).count()
        if (whenCount > 0) {
            sb.appendLine("Cover EVERY branch of the $whenCount when-expression(s), including else branches.")
        }

        // ---- exact message literals ----
        val messages = Regex("\"([^\"\\n]{8,90})\"").findAll(sourceCode)
            .map { it.groupValues[1] }
            .filter { it.contains(' ') && !it.contains("$") }
            .distinct().take(8).toList()
        if (messages.isNotEmpty()) {
            sb.appendLine("Assert the EXACT returned/shown message strings where applicable:")
            messages.forEach { sb.appendLine("  - \"$it\"") }
        }

        // ---- numeric boundary constants ----
        val bounds = Regex("(?:\\.length|\\.size|count\\(\\)|\\w+)\\s*[<>]=?\\s*(\\d+|[A-Z_]{2,})")
            .findAll(sourceCode).map { it.groupValues[1] }.distinct().take(6).toList()
        if (bounds.isNotEmpty()) {
            sb.appendLine("Boundary-test comparisons against: ${bounds.joinToString()} — test value-1, value, value+1.")
        }

        return sb.toString().trim().ifEmpty { null }
    }
}
