package testagent

/**
 * Lightweight source-level mutation engine — no bytecode, no pitest.
 * Generates single-line mutants (operator swaps, boundary shifts, condition
 * inversions). The agent runs the generated suite against each mutant:
 *   mutant makes tests FAIL  -> killed  (suite detects the bug class)
 *   mutant passes all tests  -> SURVIVED (blind spot — suite too weak)
 *   mutant doesn't compile   -> invalid (discarded)
 * Kill-rate = the suite's trust score.
 */
data class Mutant(
    val id: Int,
    val description: String,
    val line: Int,
    val mutatedSource: String,
)

object MutationEngine {

    private data class Op(val pattern: Regex, val replacement: String, val label: String)

    // Space-delimited comparison forms avoid mangling generics (List<String>) and lambdas (->).
    private val ops = listOf(
        Op(Regex("(?<= )<(?= )"), "<=", "'<' → '<='"),
        Op(Regex("(?<= )<=(?= )"), "<", "'<=' → '<'"),
        Op(Regex("(?<= )>(?= )"), ">=", "'>' → '>='"),
        Op(Regex("(?<= )>=(?= )"), ">", "'>=' → '>'"),
        Op(Regex("(?<![!=<>])==(?!=)"), "!=", "'==' → '!='"),
        Op(Regex("!=(?!=)"), "==", "'!=' → '=='"),
        Op(Regex("&&"), "||", "'&&' → '||'"),
        Op(Regex("\\|\\|"), "&&", "'||' → '&&'"),
        Op(Regex("\\.isBlank\\(\\)"), ".isNotBlank()", "isBlank → isNotBlank"),
        Op(Regex("\\.isNotBlank\\(\\)"), ".isBlank()", "isNotBlank → isBlank"),
        Op(Regex("\\.isEmpty\\(\\)"), ".isNotEmpty()", "isEmpty → isNotEmpty"),
        Op(Regex("\\.isNotEmpty\\(\\)"), ".isEmpty()", "isNotEmpty → isEmpty"),
        Op(Regex("return true\\b"), "return false", "return true → false"),
        Op(Regex("return false\\b"), "return true", "return false → true"),
    )

    private val numericConstOrComparison =
        Regex("(?:(?:const\\s+)?val\\s+\\w+\\s*=\\s*\\d+)|(?:[<>]=?\\s*\\d)|(?:\\d\\s*[<>]=?)")

    fun generateMutants(source: String, max: Int): List<Mutant> {
        val lines = source.lines()
        val mutants = mutableListOf<Mutant>()
        var id = 1

        lines.forEachIndexed { idx, line ->
            if (mutants.size >= max) return mutants.take(max)
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                trimmed.startsWith("import ") || trimmed.startsWith("package ") ||
                trimmed.startsWith("@")
            ) return@forEachIndexed

            // Operator mutant — at most one per line for diversity
            for (op in ops) {
                val m = op.pattern.find(line) ?: continue
                val mutatedLine = line.replaceRange(m.range, op.replacement)
                mutants += Mutant(
                    id = id++,
                    description = "L${idx + 1} ${op.label}: ${trimmed.take(60)}",
                    line = idx + 1,
                    mutatedSource = rebuiltWith(lines, idx, mutatedLine),
                )
                break
            }

            // Numeric boundary mutant (N → N+1) on constants and comparisons
            if (mutants.size < max &&
                numericConstOrComparison.containsMatchIn(line) &&
                mutants.none { it.line == idx + 1 }
            ) {
                val num = Regex("\\b(\\d+)\\b").find(line) ?: return@forEachIndexed
                val newVal = (num.groupValues[1].toIntOrNull() ?: return@forEachIndexed) + 1
                val mutatedLine = line.replaceRange(num.range, newVal.toString())
                mutants += Mutant(
                    id = id++,
                    description = "L${idx + 1} ${num.groupValues[1]} → $newVal: ${trimmed.take(60)}",
                    line = idx + 1,
                    mutatedSource = rebuiltWith(lines, idx, mutatedLine),
                )
            }
        }
        return mutants.take(max)
    }

    private fun rebuiltWith(lines: List<String>, index: Int, newLine: String): String =
        lines.toMutableList().also { it[index] = newLine }.joinToString("\n")
}
