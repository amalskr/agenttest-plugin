package testagent

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Usage:
 *   # Auto mode — discovers changed files from git and decides HOW to test each.
 *   # Targets with a passing suite and unchanged source are skipped (up to date).
 *   # Targets with a passing suite and CHANGED source get INCREMENTAL updates:
 *   # existing tests preserved, new tests added for the diff.
 *   ./gradlew :app:agentTest --llm=gemini
 *
 *   # Explicit mode (forces regeneration even if up to date):
 *   ./gradlew :app:agentTest --target=com.example.MyClass --llm=gemini
 */
abstract class AgentTestTask : DefaultTask() {

    @get:Input
    @get:Optional
    @get:Option(option = "target", description = "Optional: fully qualified class name. Omit to auto-detect from git changes")
    abstract val targetClass: Property<String>

    @get:Input
    abstract val maxRepairAttempts: Property<Int>

    @get:Input
    @get:Option(option = "llm", description = "LLM provider: gemini or claude (default: auto-detect from env)")
    abstract val llmProvider: Property<String>

    @get:Input
    @get:Option(option = "advise", description = "Also generate architecture advice for UI files with embedded logic")
    abstract val advise: Property<Boolean>

    @get:Input
    @get:Option(option = "strict", description = "Fail the build when any target ends with errors/failures (for CI)")
    abstract val strict: Property<Boolean>

    @get:Input
    @get:Option(option = "mutate", description = "After tests pass, run mutation analysis to score suite strength (UNIT/VIEWMODEL targets)")
    abstract val mutate: Property<Boolean>

    @get:Input
    abstract val maxMutants: Property<Int>

    // Captured at configuration time (configuration-cache friendly)
    @get:Internal
    val moduleDir: File = project.projectDir

    @get:Internal
    val gradleRootDir: File = project.rootDir

    @get:Internal
    val modulePath: String = project.path

    init {
        maxRepairAttempts.convention(3)
        llmProvider.convention("auto")
        advise.convention(false)
        strict.convention(false)
        mutate.convention(false)
        maxMutants.convention(8)
        outputs.upToDateWhen { false } // always re-run when invoked
    }

    private enum class Kind { UNIT, COMPOSE, VIEWMODEL }
    private enum class Status { PASSED, UP_TO_DATE, FAILED_TESTS, COMPILE_FAILED, NEEDS_SETUP, ERROR }

    private data class Target(val fqcn: String, val kind: Kind, val sourceFile: File)

    private data class TestHeader(val hash: String, val status: String)

    private data class PipelineResult(
        val fqcn: String,
        val status: Status,
        val detail: String,
        val testFile: File? = null,
    )

    @TaskAction
    fun run() {
        val llm = resolveLlmClient()

        val explicit = targetClass.orNull?.trim()
        val forceRegenerate = !explicit.isNullOrEmpty()
        val adviceCandidates = mutableListOf<File>()
        val targets: List<Target> = if (forceRegenerate) {
            val src = findSourceFile(explicit!!)
                ?: error("Source for $explicit not found under src/main in $modulePath")
            listOf(Target(explicit, classifyKind(src.readText()), src))
        } else {
            logger.lifecycle("🔍 No --target given — auto-detecting from git changes...")
            val discovered = discoverTargets(adviceCandidates)
            if (discovered.isEmpty() && adviceCandidates.isEmpty()) {
                logger.lifecycle("ℹ️  No testable changed classes found in git diff. Nothing to do.")
                return
            }
            if (discovered.isNotEmpty()) {
                logger.lifecycle("🎯 Targets:")
                discovered.forEach {
                    val strategy = when (it.kind) {
                        Kind.COMPOSE -> "Compose UI test (Robolectric)"
                        Kind.VIEWMODEL -> "ViewModel test (coroutines-test)"
                        Kind.UNIT -> "JUnit unit test"
                    }
                    logger.lifecycle("   • ${it.fqcn}  →  $strategy")
                }
            }
            discovered
        }

        // Architecture advice for UI files with embedded logic (opt-in)
        if (adviceCandidates.isNotEmpty() && advise.get()) {
            adviceCandidates.forEach { generateAdvice(llm, it) }
        }

        if (targets.isEmpty()) return

        // Pre-clean: remove BROKEN generated tests for this run's targets so a
        // bad file from a previous run can't block an earlier target's compile.
        // Files whose header says status=passed are kept — they compile, and
        // incremental mode needs them.
        targets.forEach { t ->
            val f = testFileFor(t.fqcn)
            if (f.exists()) {
                val header = readTestHeader(f)
                if (header == null || header.status != "passed") {
                    f.delete()
                    logger.lifecycle("🧹 Removed broken/legacy ${f.name} from previous run")
                }
            }
        }

        val results = targets.map { target ->
            logger.lifecycle("")
            logger.lifecycle("━━━ ${target.fqcn} [${target.kind}] ━━━")
            try {
                runPipelineFor(target, llm, forceRegenerate)
            } catch (e: Exception) {
                PipelineResult(target.fqcn, Status.ERROR, e.message ?: e.toString())
            }
        }

        printSummary(results)

        val problems = results.count {
            it.status == Status.ERROR || it.status == Status.COMPILE_FAILED || it.status == Status.FAILED_TESTS
        }
        if (problems > 0) {
            if (strict.get()) {
                throw RuntimeException("agentTest: $problems target(s) with failures — see summary above")
            } else {
                logger.lifecycle("")
                logger.lifecycle("⚠️  $problems target(s) need attention (build not failed — use --strict for CI)")
            }
        }
    }

    // ---------- per-target pipeline ----------

    private fun runPipelineFor(target: Target, llm: LlmClient, force: Boolean): PipelineResult {
        val fqcn = target.fqcn
        val pkg = fqcn.substringBeforeLast('.')
        val simpleName = fqcn.substringAfterLast('.')
        val testClassName = "${simpleName}GeneratedTest"
        val testFile = testFileFor(fqcn)
        testFile.parentFile.mkdirs()

        // Deterministic dependency preflights — fail fast with paste-ready fixes.
        if (target.kind == Kind.COMPOSE) {
            val missing = missingComposeTestDeps()
            if (missing.isNotEmpty()) {
                return PipelineResult(
                    fqcn, Status.NEEDS_SETUP,
                    "Compose UI testing needs one-time setup in ${modulePath.removePrefix(":")}/build.gradle.kts:\n" +
                        missing.joinToString("\n") { "      $it" },
                )
            }
        }
        if (target.kind == Kind.VIEWMODEL) {
            val missing = missingViewModelTestDeps()
            if (missing.isNotEmpty()) {
                return PipelineResult(
                    fqcn, Status.NEEDS_SETUP,
                    "ViewModel testing needs one-time setup in ${modulePath.removePrefix(":")}/build.gradle.kts:\n" +
                        missing.joinToString("\n") { "      $it" },
                )
            }
        }

        val resultsDir = File(moduleDir, "build/test-results/testDebugUnitTest")
        val sourceCode = target.sourceFile.readText()
        val sourceHash = sha256(sourceCode)

        // ---- Mode decision: UP_TO_DATE / INCREMENTAL / FULL ----
        val existingHeader = if (testFile.exists()) readTestHeader(testFile) else null
        val existingPassing = existingHeader?.status == "passed"

        if (!force && existingPassing && existingHeader!!.hash == sourceHash) {
            logger.lifecycle("⏩ Source unchanged since last passing run — skipping")
            return PipelineResult(
                fqcn, Status.UP_TO_DATE,
                "up to date — use --target=$fqcn to force regeneration", testFile,
            )
        }

        val incremental = existingPassing && existingHeader!!.hash != sourceHash
        val existingTests = if (incremental) stripHeader(testFile.readText()) else null

        val kindLabel = when (target.kind) {
            Kind.COMPOSE -> "Compose UI"
            Kind.VIEWMODEL -> "ViewModel"
            Kind.UNIT -> "JUnit"
        }
        if (incremental) {
            logger.lifecycle("♻️  Source changed — incrementally updating existing passing suite...")
        }
        logger.lifecycle("🤖 Generating $kindLabel tests with ${llm.javaClass.simpleName}...")

        val basePrompt = if (incremental) {
            incrementalPrompt(
                pkg, testClassName, sourceCode, existingTests!!,
                gitDiffFor(target.sourceFile), target.kind,
            )
        } else {
            // Deterministic static-analysis plan → minimum coverage contract
            val plan = if (target.kind != Kind.COMPOSE) TestPlanner.plan(sourceCode) else null
            if (plan != null) logger.lifecycle("📋 Static analysis plan: ${plan.lines().size} coverage items derived")

            when (target.kind) {
                Kind.UNIT -> unitPrompt(pkg, testClassName, sourceCode, plan)
                Kind.COMPOSE -> {
                    // Metamorphic dimension: stateful composables get state-restoration MR tests
                    val stateful = Regex("\\bremember(Saveable)?\\s*\\{|mutableStateOf").containsMatchIn(sourceCode)
                    if (stateful) logger.lifecycle("🔄 Stateful composable — adding state-restoration (metamorphic) requirements")
                    composePrompt(pkg, testClassName, sourceCode, stateful)
                }
                Kind.VIEWMODEL -> viewModelPrompt(pkg, testClassName, sourceCode, plan)
            }
        }

        var testCode = generateValidated(llm, target.kind, basePrompt)
        writeTestFile(testFile, testCode, sourceHash, "untested")
        logger.lifecycle("📝 Wrote ${testFile.relativeTo(moduleDir)}")

        val maxAttempts = maxRepairAttempts.get()
        var lastUnresolved: Set<String> = emptySet()

        for (attempt in 1..maxAttempts) {
            logger.lifecycle("🧪 Running tests (attempt $attempt/$maxAttempts)...")
            JUnitXmlParser.clearStaleResults(resultsDir, testClassName)
            val logFile = runGradleTests("$pkg.$testClassName", simpleName, attempt)
            val result = JUnitXmlParser.parse(resultsDir, testClassName)

            // No XML at all → almost certainly a compilation failure
            if (result.total == 0) {
                val logTail = logFile.readText().takeLast(4000)

                // Guard: are the compile errors actually in OUR file?
                val errorLines = logTail.lines().filter { it.trimStart().startsWith("e:") }
                val foreignErrors = errorLines.filter { !it.contains("$testClassName.kt") }
                if (foreignErrors.isNotEmpty() && errorLines.none { it.contains("$testClassName.kt") }) {
                    return PipelineResult(
                        fqcn, Status.ERROR,
                        "Compilation failed in OTHER test files — clean these up first:\n" +
                            foreignErrors.joinToString("\n") { "    $it" },
                        testFile,
                    )
                }

                logger.lifecycle("💥 Compile error in generated test. Repairing...")
                if (attempt == maxAttempts) {
                    writeTestFile(testFile, testCode, sourceHash, "broken")
                    return PipelineResult(
                        fqcn, Status.COMPILE_FAILED,
                        "Generated test failed to compile after $maxAttempts attempts. See $logFile",
                        testFile,
                    )
                }

                // Hallucination circuit breaker: same unresolved API failing twice
                // means the API does not exist — force a strategy change.
                val unresolved = Regex("Unresolved reference '([\\w.]+)'")
                    .findAll(logTail).map { it.groupValues[1] }.toSet()
                val repeated = unresolved.intersect(lastUnresolved)
                lastUnresolved = unresolved
                val breaker = if (repeated.isNotEmpty()) {
                    """

                    CRITICAL: The following APIs DO NOT EXIST — they have now failed
                    twice: ${repeated.joinToString()}. Do NOT retry variations of them.
                    Restructure the tests to avoid those APIs entirely — test only
                    behaviors reachable through the class's public parameters. If a
                    behavior cannot be tested without those APIs, drop that test and
                    add: // UNTESTABLE: <reason>
                    """.trimIndent()
                } else ""

                testCode = generateValidated(
                    llm, target.kind,
                    compileFixPrompt(sourceCode, testCode, logTail, target.kind) + breaker,
                )
                writeTestFile(testFile, testCode, sourceHash, "untested")
                continue
            }

            printCaseResults(result, testFile)

            if (result.passed) {
                writeTestFile(testFile, testCode, sourceHash, "passed")
                logger.lifecycle("✅ ${result.total}/${result.total} tests passed for $testClassName")
                val mode = if (incremental) " (incremental update)" else ""
                var detail = "${result.total} tests passed$mode"

                // Surface SUSPECTED CODE BUG markers left by the repair loop
                val bugMarkers = testFile.readLines().count { it.contains("SUSPECTED CODE BUG") }
                if (bugMarkers > 0) detail += " · 🐛 $bugMarkers suspected source bug(s) flagged in test file"

                // Mutation analysis — trust score for the suite (opt-in, non-Compose)
                if (mutate.get()) {
                    detail += if (target.kind == Kind.COMPOSE) {
                        " · 🧬 mutation n/a for Compose targets"
                    } else {
                        " · " + runMutationAnalysis(target, llm, pkg, simpleName, testClassName, testFile, resultsDir, sourceHash)
                    }
                }
                return PipelineResult(fqcn, Status.PASSED, detail, testFile)
            }

            logger.lifecycle("❌ ${result.failed.size}/${result.total} tests failed")

            if (attempt == maxAttempts) {
                writeTestFile(testFile, testCode, sourceHash, "failed")
                return PipelineResult(
                    fqcn, Status.FAILED_TESTS,
                    "${result.failed.size}/${result.total} failing after $maxAttempts attempts — " +
                        "possible genuine bug in $simpleName (agent never modifies source)",
                    testFile,
                )
            }

            logger.lifecycle("🔧 Asking ${llm.javaClass.simpleName} to repair the failing tests...")
            testCode = generateValidated(
                llm, target.kind,
                repairPrompt(sourceCode, testCode, result, target.kind),
            )
            writeTestFile(testFile, testCode, sourceHash, "untested")
        }

        return PipelineResult(fqcn, Status.ERROR, "Unexpected pipeline exit", testFile)
    }

    // ---------- mutation analysis (suite trust score) ----------

    private fun runMutationAnalysis(
        target: Target,
        llm: LlmClient,
        pkg: String,
        simpleName: String,
        testClassName: String,
        testFile: File,
        resultsDir: File,
        sourceHash: String,
    ): String {
        val original = target.sourceFile.readText()
        val mutants = MutationEngine.generateMutants(original, maxMutants.get())
        if (mutants.isEmpty()) return "🧬 no mutable patterns found"

        logger.lifecycle("🧬 Mutation analysis: ${mutants.size} mutant(s), one nested test run each...")
        var killed = 0
        var invalid = 0
        val survivors = mutableListOf<Mutant>()

        mutants.forEach { m ->
            when (testAgainstMutant(target.sourceFile, original, m, "$pkg.$testClassName", testClassName, simpleName, resultsDir)) {
                "killed" -> { killed++; logger.lifecycle("   💀 killed    ${m.description}") }
                "survived" -> { survivors += m; logger.lifecycle("   🚨 SURVIVED  ${m.description}") }
                else -> { invalid++; logger.lifecycle("   ⚪ invalid   ${m.description}") }
            }
        }

        val valid = killed + survivors.size
        if (valid == 0) return "🧬 all mutants invalid (non-compiling) — no score"
        var finalKilled = killed

        // Close the loop: strengthen the suite against survivors, once.
        if (survivors.isNotEmpty()) {
            logger.lifecycle("🧬 ${survivors.size} survivor(s) — asking ${llm.javaClass.simpleName} to strengthen the suite...")
            val previous = stripHeader(testFile.readText())
            try {
                val strengthened = generateValidated(llm, target.kind, strengthenPrompt(original, previous, survivors))
                writeTestFile(testFile, strengthened, sourceHash, "untested")
                JUnitXmlParser.clearStaleResults(resultsDir, testClassName)
                runGradleTests("$pkg.$testClassName", "$simpleName-strengthen", 1)
                val res = JUnitXmlParser.parse(resultsDir, testClassName)
                if (res.passed) {
                    writeTestFile(testFile, strengthened, sourceHash, "passed")
                    logger.lifecycle("   💪 strengthened suite passes (${res.total} tests) — re-running survivors...")
                    val stillAlive = mutableListOf<Mutant>()
                    survivors.forEach { m ->
                        val outcome = testAgainstMutant(target.sourceFile, original, m, "$pkg.$testClassName", testClassName, simpleName, resultsDir)
                        if (outcome == "killed") {
                            finalKilled++
                            logger.lifecycle("   💀 now killed ${m.description}")
                        } else stillAlive += m
                    }
                    val score = finalKilled * 100 / valid
                    return "🧬 mutation score $finalKilled/$valid ($score%) after strengthening" +
                        if (stillAlive.isNotEmpty()) " — ${stillAlive.size} blind spot(s) remain" else ""
                } else {
                    writeTestFile(testFile, previous, sourceHash, "passed")
                    logger.lifecycle("   strengthened suite failed on original source — reverted to previous suite")
                }
            } catch (e: Exception) {
                writeTestFile(testFile, previous, sourceHash, "passed")
                logger.lifecycle("   ⚠️ strengthening failed (${e.message}) — reverted")
            }
        }

        val score = finalKilled * 100 / valid
        return "🧬 mutation score $finalKilled/$valid ($score%)" +
            if (survivors.isNotEmpty()) " — ${survivors.size} blind spot(s), see console" else ""
    }

    /** Runs the suite against one mutant. ALWAYS restores the original source. */
    private fun testAgainstMutant(
        sourceFile: File,
        originalSource: String,
        mutant: Mutant,
        testFilter: String,
        testClassName: String,
        simpleName: String,
        resultsDir: File,
    ): String {
        return try {
            JUnitXmlParser.clearStaleResults(resultsDir, testClassName)
            sourceFile.writeText(mutant.mutatedSource)
            runGradleTests(testFilter, "$simpleName-mut${mutant.id}", 1)
            val res = JUnitXmlParser.parse(resultsDir, testClassName)
            when {
                res.total == 0 -> "invalid"          // mutant broke compilation
                res.failed.isNotEmpty() -> "killed"  // suite caught the bug
                else -> "survived"                    // blind spot
            }
        } catch (e: Exception) {
            "invalid"
        } finally {
            sourceFile.writeText(originalSource)     // never leave a mutant behind
        }
    }

    // ---------- state header (self-contained incremental tracking) ----------

    private fun testFileFor(fqcn: String): File {
        val pkg = fqcn.substringBeforeLast('.')
        val simple = fqcn.substringAfterLast('.')
        return File(moduleDir, "src/test/java/${pkg.replace('.', '/')}/${simple}GeneratedTest.kt")
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    private fun readTestHeader(file: File): TestHeader? {
        val first = file.useLines { it.firstOrNull() } ?: return null
        val match = Regex("// agenttest: hash=(\\w+) status=(\\w+)").find(first) ?: return null
        return TestHeader(match.groupValues[1], match.groupValues[2])
    }

    private fun stripHeader(code: String): String =
        code.lines().filterNot { it.startsWith("// agenttest:") }.joinToString("\n").trim()

    private fun writeTestFile(file: File, code: String, hash: String, status: String) {
        val clean = stripHeader(code)
        file.writeText("// agenttest: hash=$hash status=$status\n$clean\n")
    }

    // ---------- git discovery + deterministic triage ----------

    private fun discoverTargets(adviceCandidates: MutableList<File>): List<Target> {
        val changed = (
            execGit("git", "diff", "--name-only", "HEAD") +
                execGit("git", "ls-files", "--others", "--exclude-standard")
            ).distinct()

        val moduleMain = File(moduleDir, "src/main").absolutePath + File.separator
        return changed
            .filter { it.endsWith(".kt") }
            .map { File(gradleRootDir, it) }
            .filter { it.exists() }
            // Only THIS module's src/main — excludes the agent's own plugin
            // sources, other modules, and build outputs.
            .filter { it.absolutePath.startsWith(moduleMain) }
            .mapNotNull { triage(it, adviceCandidates) }
    }

    /**
     * The agent's own decision logic — fully deterministic, zero LLM calls:
     *   pure Kotlin class/object          -> UNIT
     *   extends ViewModel                 -> VIEWMODEL (coroutines-test, JVM)
     *   file with @Composable functions   -> COMPOSE (Robolectric, still JVM)
     *   Activity/Fragment (no composables)-> skip (needs instrumented test)
     *   android.*-dependent non-UI code   -> skip (advice candidate)
     */
    private fun triage(file: File, adviceCandidates: MutableList<File>): Target? {
        val code = file.readText()
        val pkg = Regex("^package\\s+([\\w.]+)", RegexOption.MULTILINE)
            .find(code)?.groupValues?.get(1) ?: return null
        val className = file.nameWithoutExtension
        val fqcn = "$pkg.$className"

        if (className.endsWith("GeneratedTest")) return null

        val hasComposables = Regex("@Composable\\s+(?:\\w+\\s+)*fun\\s+\\w+").containsMatchIn(code)
        val isActivityOrFragment = Regex(":\\s*(Component)?Activity\\b|:\\s*Fragment\\b").containsMatchIn(code)

        if (hasComposables && !isActivityOrFragment) {
            if (hasEmbeddedLogic(code)) {
                adviceCandidates += file
                logger.lifecycle("📐 $className — Compose UI target ⚠️ embedded logic detected (--advise for extraction plan)")
            }
            return Target(fqcn, Kind.COMPOSE, file)
        }

        if (Regex(":\\s*(Android)?ViewModel\\b").containsMatchIn(code)) {
            return Target(fqcn, Kind.VIEWMODEL, file)
        }

        val skipReason = when {
            isActivityOrFragment ->
                "Activity/Fragment (needs instrumented test)"
            Regex("^import android\\.", RegexOption.MULTILINE).containsMatchIn(code) ->
                "android.* dependency (not JVM-testable)"
            !Regex("\\b(class|object)\\s+$className\\b").containsMatchIn(code) ->
                "no class/object matching filename"
            else -> null
        }

        if (skipReason == null) return Target(fqcn, Kind.UNIT, file)

        if (hasEmbeddedLogic(code)) adviceCandidates += file
        logger.lifecycle("⏭️  Skipping $className — $skipReason")
        return null
    }

    private fun classifyKind(code: String): Kind = when {
        Regex("@Composable\\s+(?:\\w+\\s+)*fun\\s+\\w+").containsMatchIn(code) -> Kind.COMPOSE
        Regex(":\\s*(Android)?ViewModel\\b").containsMatchIn(code) -> Kind.VIEWMODEL
        else -> Kind.UNIT
    }

    private fun hasEmbeddedLogic(code: String): Boolean {
        val signals = listOf(
            Regex("\\bwhen\\s*\\{"),
            Regex("Regex\\(|\\.matches\\(|Patterns\\."),
            Regex("\\.length\\s*[<>=]"),
            Regex("isBlank\\(\\)|isEmpty\\(\\)"),
            Regex("\\bif\\s*\\(.*(==|!=|<|>).*\\)"),
        )
        return signals.count { it.containsMatchIn(code) } >= 2
    }

    private fun execGit(vararg cmd: String): List<String> {
        return try {
            val proc = ProcessBuilder(*cmd)
                .directory(gradleRootDir)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor(30, TimeUnit.SECONDS)
            if (proc.exitValue() != 0) emptyList()
            else out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            logger.lifecycle("⚠️  git command failed: ${e.message}")
            emptyList()
        }
    }

    /** Unified zero-context diff of one file vs HEAD — the "what changed" for incremental prompts. */
    private fun gitDiffFor(sourceFile: File): String {
        val rel = sourceFile.relativeTo(gradleRootDir).path
        val lines = try {
            val proc = ProcessBuilder("git", "diff", "-U0", "HEAD", "--", rel)
                .directory(gradleRootDir).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor(30, TimeUnit.SECONDS)
            out
        } catch (e: Exception) {
            ""
        }
        return lines.take(4000).ifBlank { "(diff unavailable — treat all source as potentially changed)" }
    }

    // ---------- deterministic guards ----------

    private fun missingComposeTestDeps(): List<String> {
        val buildFile = listOf("build.gradle.kts", "build.gradle")
            .map { File(moduleDir, it) }.firstOrNull { it.exists() }?.readText().orEmpty()
        val catalog = File(gradleRootDir, "gradle/libs.versions.toml")
            .takeIf { it.exists() }?.readText().orEmpty()
        val combined = buildFile + catalog

        val missing = mutableListOf<String>()
        if (!combined.contains("robolectric")) {
            missing += """testImplementation("org.robolectric:robolectric:4.14.1")"""
        }
        if (!combined.contains("ui-test-junit4")) {
            missing += """testImplementation("androidx.compose.ui:ui-test-junit4") // + testImplementation(platform(<your compose BOM>))"""
        }
        if (!combined.contains("ui-test-manifest")) {
            missing += """debugImplementation("androidx.compose.ui:ui-test-manifest")"""
        }
        if (!buildFile.contains("isIncludeAndroidResources") && !buildFile.contains("includeAndroidResources")) {
            missing += "android { testOptions { unitTests { isIncludeAndroidResources = true } } }"
        }
        return missing
    }

    private fun missingViewModelTestDeps(): List<String> {
        val buildFile = listOf("build.gradle.kts", "build.gradle")
            .map { File(moduleDir, it) }.firstOrNull { it.exists() }?.readText().orEmpty()
        val catalog = File(gradleRootDir, "gradle/libs.versions.toml")
            .takeIf { it.exists() }?.readText().orEmpty()
        val combined = buildFile + catalog

        val missing = mutableListOf<String>()
        if (!combined.contains("kotlinx-coroutines-test")) {
            missing += """testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")"""
        }
        return missing
    }

    /** Generates code and retries immediately (no build) if constraints are violated. */
    private fun generateValidated(llm: LlmClient, kind: Kind, prompt: String, maxValidationRetries: Int = 2): String {
        var currentPrompt = prompt
        repeat(maxValidationRetries + 1) { attempt ->
            val code = stripFences(llm.complete(systemPromptFor(kind), currentPrompt))
            val violation = validateGeneratedCode(code, kind)
            if (violation == null) return code
            if (attempt == maxValidationRetries) {
                error("LLM kept violating constraints after $maxValidationRetries retries: $violation")
            }
            logger.lifecycle("🛡️  Rejected before build: $violation — asking LLM to rewrite...")
            currentPrompt = """
                Your previous answer violated a constraint:
                $violation

                Rewrite the complete test file fixing this.
                ${structureReminder(kind)}

                Previous answer:
                ${code.take(6000)}
            """.trimIndent()
        }
        error("unreachable")
    }

    private fun validateGeneratedCode(code: String, kind: Kind): String? {
        val imports = code.lines().filter { it.trimStart().startsWith("import ") }
        val forbidden = when (kind) {
            Kind.UNIT -> listOf("org.mockito", "io.mockk", "org.robolectric", "android.", "androidx.", "kotlinx.coroutines.test")
            Kind.COMPOSE -> listOf("org.mockito", "io.mockk")
            Kind.VIEWMODEL -> listOf("org.mockito", "io.mockk", "org.robolectric", "android.")
        }
        imports.firstOrNull { line -> forbidden.any { line.contains(it) } }
            ?.let { return "forbidden import for $kind test: ${it.trim()}" }

        if (kind == Kind.COMPOSE && !code.contains("createComposeRule")) {
            return "Compose test must use createComposeRule()"
        }
        return null
    }

    // ---------- helpers ----------

    private fun resolveLlmClient(): LlmClient {
        val geminiKey = System.getenv("GEMINI_API_KEY") ?: localPropsKey("GEMINI_API_KEY")
        val claudeKey = System.getenv("ANTHROPIC_API_KEY") ?: localPropsKey("ANTHROPIC_API_KEY")

        return when (llmProvider.get().lowercase()) {
            "gemini" -> GeminiClient(geminiKey ?: error("Set GEMINI_API_KEY in local.properties or as an environment variable"))
            "claude" -> ClaudeClient(claudeKey ?: error("Set ANTHROPIC_API_KEY in local.properties or as an environment variable"))
            "auto" -> when {
                geminiKey != null -> GeminiClient(geminiKey)
                claudeKey != null -> ClaudeClient(claudeKey)
                else -> error("Set GEMINI_API_KEY or ANTHROPIC_API_KEY in local.properties or as an environment variable")
            }
            else -> error("Unknown --llm value '${llmProvider.get()}'. Use: gemini | claude")
        }
    }

    /** Reads a key from the project root local.properties (gitignored by default in Android projects). */
    private fun localPropsKey(name: String): String? {
        val lp = File(gradleRootDir, "local.properties")
        if (!lp.exists()) return null
        return lp.readLines()
            .firstOrNull { it.trim().startsWith("$name=") }
            ?.substringAfter("=")?.trim()?.ifEmpty { null }
    }

    private fun findSourceFile(fqcn: String): File? {
        val relPath = fqcn.replace('.', '/')
        return listOf("src/main/java", "src/main/kotlin").flatMap { root ->
            listOf("$root/$relPath.kt", "$root/$relPath.java")
        }.map { File(moduleDir, it) }.firstOrNull { it.exists() }
    }

    private fun runGradleTests(testFilter: String, simpleName: String, attempt: Int): File {
        val logFile = File(moduleDir, "build/agenttest/run-$simpleName-$attempt.log")
        logFile.parentFile.mkdirs()

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val gradlew = File(gradleRootDir, if (isWindows) "gradlew.bat" else "gradlew")

        val cmd = listOf(
            gradlew.absolutePath,
            "$modulePath:testDebugUnitTest",
            "--tests", testFilter,
            "--project-cache-dir",
            File(moduleDir, "build/agenttest/gradle-cache").absolutePath,
            "--console=plain",
        )

        val process = ProcessBuilder(cmd)
            .directory(gradleRootDir)
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .start()

        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            error("Test run timed out after 15 minutes. See $logFile")
        }
        return logFile
    }

    private fun printCaseResults(result: TestRunResult, testFile: File) {
        result.cases.sortedBy { it.failure != null }.forEach { case ->
            val line = findTestLine(testFile, case.name)
            val link = "file://${testFile.absolutePath}" + (line?.let { ":$it" } ?: "")
            if (case.failure == null) {
                logger.lifecycle("   ✅ PASS  ${case.name}")
            } else {
                logger.lifecycle("   ❌ FAIL  ${case.name}")
                logger.lifecycle("            ${case.failure.message.take(200)}")
            }
            logger.lifecycle("            $link")
        }
    }

    private fun findTestLine(testFile: File, testName: String): Int? {
        if (!testFile.exists()) return null
        val pattern = Regex("fun\\s+`?${Regex.escape(testName)}`?\\s*\\(")
        testFile.readLines().forEachIndexed { index, lineText ->
            if (pattern.containsMatchIn(lineText)) return index + 1
        }
        return null
    }

    private fun generateAdvice(llm: LlmClient, file: File) {
        logger.lifecycle("💡 Generating extraction advice for ${file.name}...")
        val adviceFile = File(moduleDir, "build/agenttest/advice-${file.nameWithoutExtension}.md")
        adviceFile.parentFile.mkdirs()
        try {
            val advice = llm.complete(ADVICE_SYSTEM_PROMPT, advicePrompt(file.readText()))
            adviceFile.writeText(advice)
            logger.lifecycle("   📄 file://${adviceFile.absolutePath}")
        } catch (e: Exception) {
            logger.lifecycle("   ⚠️ advice generation failed: ${e.message}")
        }
    }

    private fun stripFences(raw: String): String {
        val text = raw.trim()
        val fenceRegex = Regex("```(?:kotlin)?\\s*\\n([\\s\\S]*?)```")
        fenceRegex.find(text)?.let { return it.groupValues[1].trim() }
        val pkgIndex = text.indexOf("package ")
        return if (pkgIndex > 0) text.substring(pkgIndex).trim() else text
    }

    private fun printSummary(results: List<PipelineResult>) {
        logger.lifecycle("")
        logger.lifecycle("━━━━━━━━━━ agentTest summary ━━━━━━━━━━")
        results.forEach { r ->
            val icon = when (r.status) {
                Status.PASSED -> "✅"
                Status.UP_TO_DATE -> "⏩"
                Status.FAILED_TESTS -> "❌"
                Status.COMPILE_FAILED -> "💥"
                Status.NEEDS_SETUP -> "🔧"
                Status.ERROR -> "⚠️"
            }
            logger.lifecycle("$icon ${r.fqcn}")
            logger.lifecycle("   ${r.detail}")
            r.testFile?.let { logger.lifecycle("   ${it.relativeTo(moduleDir)}") }
        }
        logger.lifecycle("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    private fun systemPromptFor(kind: Kind): String = when (kind) {
        Kind.UNIT -> UNIT_SYSTEM_PROMPT
        Kind.COMPOSE -> COMPOSE_SYSTEM_PROMPT
        Kind.VIEWMODEL -> VIEWMODEL_SYSTEM_PROMPT
    }

    // ---------- prompts ----------

    companion object {
        private val UNIT_SYSTEM_PROMPT = """
            You are an expert Android test engineer writing JUnit 4 unit tests in Kotlin.
            Output ONLY the complete contents of a single Kotlin test file.
            No markdown fences, no explanations, no comments outside the code.
        """.trimIndent()

        private val COMPOSE_SYSTEM_PROMPT = """
            You are an expert Android test engineer writing Jetpack Compose UI tests in Kotlin
            that run on the JVM using Robolectric (no emulator).
            Output ONLY the complete contents of a single Kotlin test file.
            No markdown fences, no explanations, no comments outside the code.
        """.trimIndent()

        private val VIEWMODEL_SYSTEM_PROMPT = """
            You are an expert Android test engineer writing JUnit 4 tests for ViewModels
            in Kotlin using kotlinx-coroutines-test. ViewModels are plain JVM classes here.
            Output ONLY the complete contents of a single Kotlin test file.
            No markdown fences, no explanations, no comments outside the code.
        """.trimIndent()

        private fun structureReminder(kind: Kind): String = when (kind) {
            Kind.COMPOSE -> """
                STRUCTURE REMINDERS (must all hold in your output):
                - @RunWith(RobolectricTestRunner::class) on the class
                - @org.robolectric.annotation.Config(sdk = [34]) on the class
                - @get:Rule val composeRule = createComposeRule()
                - Same package and test class name as before
            """.trimIndent()
            Kind.VIEWMODEL -> """
                STRUCTURE REMINDERS (must all hold in your output):
                - Dispatchers.setMain in @Before, Dispatchers.resetMain in @After
                - runTest + advanceUntilIdle() before state assertions
                - Same package and test class name as before
            """.trimIndent()
            Kind.UNIT -> """
                STRUCTURE REMINDERS (must all hold in your output):
                - Plain JUnit 4, org.junit.Test / org.junit.Assert only
                - Same package and test class name as before
            """.trimIndent()
        }

        private fun unitPrompt(pkg: String, testClassName: String, sourceCode: String, plan: String?) = """
            Write JUnit 4 unit tests for the class below.

            Rules:
            - Package: $pkg
            - Test class name: $testClassName
            - Pure JVM unit tests only: no Robolectric, no android.* imports, no mocking libraries
            - Use org.junit.Test and org.junit.Assert
            - Only test public API
            - Cover edge cases: null inputs (for nullable params), empty collections/strings,
              boundary values, whitespace handling, case sensitivity where relevant
            - If a constructor dependency cannot be trivially instantiated, skip methods that need it
            - Every test must have a meaningful assertion — no trivially-true tests
            ${plan?.let { "\nDETERMINISTIC TEST PLAN (from static analysis) — cover EVERY item below as a MINIMUM, then add more if valuable:\n$it\n" } ?: ""}
            Source class:
            $sourceCode
        """.trimIndent()

        private fun composePrompt(pkg: String, testClassName: String, sourceCode: String, stateful: Boolean) = """
            Write Compose UI tests (Robolectric-based, JVM) for the public @Composable
            functions in the file below. Decide yourself which composables are worth
            testing and what user-visible behaviors matter.
            ${if (stateful) METAMORPHIC_BLOCK else ""}
            Structure requirements:
            - Package: $pkg
            - Test class name: $testClassName
            - Annotate the class with @RunWith(RobolectricTestRunner::class)
            - Add @org.robolectric.annotation.Config(sdk = [34]) on the class
            - @get:Rule val composeRule = createComposeRule()
            - Render with composeRule.setContent { ... }, passing sensible fake arguments;
              pass empty lambdas {} for callbacks you don't assert on

            Behavior requirements:
            - Test what the USER sees and does: initial state, text shown, input → error
              messages, button clicks → callbacks fired (capture into local vars), state changes
            - For any parameter that is displayed on screen (e.g. an email or name), write
              at least TWO tests with DIFFERENT values to prove the display isn't hardcoded
            - Use onNodeWithText / performTextInput / performClick etc.
            - Use substring = true in onNodeWithText when the value appears inside longer text
            - If the SAME text can legitimately appear in MULTIPLE nodes at once (e.g. an
              error shown both inline and in a banner), use
              composeRule.onAllNodesWithText(...).onFirst() or assert the collection is
              not empty — a single-node matcher will fail with "found 2 nodes"
            - Prefer assertExists()/assertDoesNotExist() over assertIsDisplayed() —
              especially with AnimatedVisibility, where mid-animation nodes exist but
              may not report as displayed; and for content that may sit below the fold
              on the default-size test window (or call performScrollTo() first)
            - For composables using delay(), LaunchedEffect timers, or animations:
              control time explicitly with composeRule.mainClock:
                composeRule.mainClock.autoAdvance = false
                // trigger the state change, then advance in small steps:
                composeRule.mainClock.advanceTimeBy(100)
                composeRule.onAllNodesWithText("...").onFirst().assertExists()
                composeRule.mainClock.advanceTimeBy(4_000)  // safely past the delay
                composeRule.onNodeWithText("...").assertDoesNotExist()
              Remember AnimatedVisibility exit animations also consume clock time —
              advance generously past (delay + animation duration)
            - No Mockito/MockK
            - Skip private composables and pure preview functions
            - Every test must have a meaningful assertion

            Source file:
            $sourceCode
        """.trimIndent()

        private val METAMORPHIC_BLOCK = """

            METAMORPHIC (state-restoration) requirements — this file holds UI state, so ALSO write:
            - Use androidx.compose.ui.test.junit4.StateRestorationTester:
                val restorationTester = StateRestorationTester(composeRule)
                restorationTester.setContent { ... }
                // enter user input / change visible state, then:
                restorationTester.emulateSavedInstanceStateRestore()
                // assert the state SURVIVED restoration
            - Write ONE restoration test per user-editable field or visible UI state
              (e.g. typed text in each text field must still be shown after restore)
            - These tests detect remember{} vs rememberSaveable{} bugs. If state does
              NOT survive, that is a GENUINE SOURCE BUG — do not weaken the assertion
              to make it pass. The repair step may delete such a test and add:
              // SUSPECTED CODE BUG: state lost on recreation — use rememberSaveable
        """.trimIndent().prependIndent("            ").trimStart()

        private fun viewModelPrompt(pkg: String, testClassName: String, sourceCode: String, plan: String?) = """
            Write JUnit 4 unit tests for the Android ViewModel below. ViewModels are
            plain JVM-testable — no Robolectric needed.

            Structure requirements:
            - Package: $pkg
            - Test class name: $testClassName
            - Use kotlinx-coroutines-test: create a StandardTestDispatcher, call
              Dispatchers.setMain(dispatcher) in a @Before method and
              Dispatchers.resetMain() in an @After method
            - Wrap coroutine-dependent tests in runTest { ... }, and advance the
              scheduler with advanceUntilIdle() before asserting

            Behavior requirements:
            - Test STATE TRANSITIONS: initial state, then each public function call
              → assert the resulting uiState/StateFlow/LiveData value
            - Read StateFlow state with .value after advanceUntilIdle()
            - If the ViewModel has constructor dependencies that are interfaces, write
              simple FAKE implementations inline in the test file (no mocking libraries).
              If a dependency is a concrete class that can't be faked, skip behaviors
              needing it and add a comment explaining why
            - Cover: success paths, validation errors, edge inputs
            - Every test must have a meaningful assertion
            ${plan?.let { "\nDETERMINISTIC TEST PLAN (from static analysis) — cover EVERY item below as a MINIMUM:\n$it\n" } ?: ""}
            Source class:
            $sourceCode
        """.trimIndent()

        private fun strengthenPrompt(sourceCode: String, testCode: String, survivors: List<Mutant>) = """
            Your test suite PASSES but FAILED to detect the following artificial bugs
            (surviving mutants). This means the suite has blind spots. Add or strengthen
            tests so each mutation below would cause at least one test to FAIL — without
            breaking any existing test on the original (unmutated) source.

            Surviving mutants (each is a one-line change that no test caught):
            ${survivors.joinToString("\n") { "• ${it.description}" }}

            Typical fixes: assert exact values instead of null/not-null, add boundary
            tests at the exact compared constants, assert exact message strings.

            Current test file:
            $testCode

            Source (read-only, unmutated):
            $sourceCode

            Output the complete updated test file.
        """.trimIndent()

        private fun incrementalPrompt(
            pkg: String,
            testClassName: String,
            sourceCode: String,
            existingTests: String,
            diff: String,
            kind: Kind,
        ) = """
            The source below was MODIFIED. An existing test suite already PASSED against
            the previous version. Update the test file incrementally:

            - PRESERVE every existing test method UNCHANGED unless it now contradicts
              the modified source
            - ADD new tests covering ONLY the changed/new behavior shown in the git diff
            - If an existing test contradicts the change, update just that test
            - Do NOT rename, reorder, or rewrite passing tests for style reasons
            - Output the COMPLETE updated test file (package $pkg, class $testClassName)

            ${structureReminder(kind)}

            Git diff of the change (unified, zero context):
            $diff

            Existing test file (previously all passing — preserve these):
            $existingTests

            Current full source:
            $sourceCode
        """.trimIndent()

        private fun repairPrompt(sourceCode: String, testCode: String, result: TestRunResult, kind: Kind) = """
            The following tests failed. Fix the TEST code only — the source
            must be treated as read-only and correct unless proven otherwise.
            Fix ONLY the failing tests — do not modify, rename, or remove the
            passing tests.

            If a failure clearly reveals a genuine bug in the source (not a wrong
            expectation in the test), DELETE that test method and add this comment in its
            place: // SUSPECTED CODE BUG: <one-line explanation>

            ${structureReminder(kind)}

            Failures:
            ${result.failed.joinToString("\n\n") { "• ${it.testName}\n  ${it.message}\n  ${it.stackTrace.take(600)}" }}

            Current test file:
            $testCode

            Source (read-only):
            $sourceCode

            Output the corrected complete test file.
        """.trimIndent()

        private fun compileFixPrompt(sourceCode: String, testCode: String, buildLog: String, kind: Kind) = """
            The following Kotlin test file failed to COMPILE. Fix the compilation errors
            and output the corrected complete test file.

            ${structureReminder(kind)}

            Build log (tail):
            $buildLog

            Current test file:
            $testCode

            Source (read-only):
            $sourceCode
        """.trimIndent()

        private val ADVICE_SYSTEM_PROMPT = """
            You are a senior Android architect reviewing Jetpack Compose code for testability.
            Respond in well-structured markdown.
        """.trimIndent()

        private fun advicePrompt(sourceCode: String) = """
            This Kotlin UI file contains business logic embedded inside UI code that COULD
            be extracted into a pure Kotlin class and unit-tested far more cheaply than
            through UI tests.

            Produce a concise refactoring plan:

            1. **What logic is trapped in the UI** — list each piece (validation, decisions,
               transformations) with the line context
            2. **Proposed extraction** — a complete, ready-to-paste pure Kotlin object/class
               (no android.* imports; if android.util.Patterns is used, replace with an
               equivalent pure Kotlin Regex)
            3. **UI diff** — show exactly which lines in the Composable change to call the
               extracted class
            4. **What becomes testable** — the edge cases a test generator could now cover

            Keep it practical and short. The developer will apply this manually.

            Source file:
            $sourceCode
        """.trimIndent()
    }
}
