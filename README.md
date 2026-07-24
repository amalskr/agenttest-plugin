# AgentTest

**AI-powered test generation agent for Android — writes the tests, proves they work, finds your bugs.**

AgentTest is a Gradle plugin that detects what changed in your project via git, decides *how* each file should be tested (JUnit / Compose UI / ViewModel), generates the tests with an LLM (Gemini or Claude), runs them, and reports honest per-test PASS/FAIL results — all from one command:

```bash
./gradlew :app:agentTest
```

Unlike chat-based test generation, AgentTest is a **verified pipeline**: every generated suite is compiled, executed, and reported with real results. Deterministic guards catch LLM hallucinations before they waste a build, a static-analysis planner guarantees boundary coverage, metamorphic tests catch real state-loss bugs, and an optional mutation engine scores how strong your suite actually is.

---

## Highlights

- 🔍 **Git-aware auto-discovery** — no arguments needed; changed/new files are found automatically
- 🎯 **Deterministic triage** — pure Kotlin → JUnit, `@Composable` → Robolectric Compose UI tests, `ViewModel` → coroutines-test; Activities/Fragments are skipped with a reason (zero LLM calls to decide)
- 📋 **Static-analysis test planning** — function signatures, nullability, `when` branches, message literals, and numeric boundaries are extracted deterministically and injected as a minimum coverage contract
- 🔄 **Metamorphic state-restoration tests** — stateful composables automatically get `StateRestorationTester` tests that catch `remember` vs `rememberSaveable` data-loss bugs (a real, non-crashing defect class)
- 🧬 **Mutation trust score** (`--mutate`) — a built-in source-level mutation engine scores your suite ("15 tests, mutation score 87%") and asks the LLM to strengthen it against blind spots
- ⏩ **Incremental engine** — unchanged sources are skipped (zero LLM calls); changed sources get incremental updates that preserve passing tests and add coverage for the git diff only
- ✅ **Honest reporting** — failing tests are *reported*, never silently "repaired" away; a failure may be a genuine source bug (AgentTest has found real ones)
- 🛡️ **Hallucination guards** — forbidden-import validation before any build, a circuit breaker for non-existent APIs, and compile-fix retries with structure reminders
- 💰 **Cost telemetry & budget cap** — every run prints real token usage; `maxLlmCalls` hard-stops runaway spending
- 🔌 **Multi-provider** — Gemini (`gemini-3.6-flash`) and Claude (`claude-sonnet-4-6`), auto-detected from whichever key you set

---

## Requirements

- Android project with Gradle Kotlin DSL (`build.gradle.kts`)
- JDK 17+ available to Gradle
- `git` on PATH (for auto-discovery mode)
- A Gemini or Anthropic API key
- For Compose UI test generation: Robolectric + Compose test dependencies (the agent tells you exactly what to add if missing — see [One-time setup](#one-time-setup-per-project))

---

## Installation

AgentTest is distributed as a compiled Gradle plugin — **no agent source code lives in your project.**

### Step 1 — Publish the plugin to your machine (one time)

```bash
cd /path/to/agenttest-plugin/agentTest
gradle publishToMavenLocal
```

Verify:

```bash
ls ~/.m2/repository/com/ceylonapz/
# → agenttest ...
```

### Step 2 — Add it to any project (2 lines)

**Root `settings.gradle.kts`** — add `mavenLocal()` as the FIRST repository inside `pluginManagement`:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()          // ← add this line first
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

**Module `app/build.gradle.kts`** — apply the plugin:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    // ... your existing plugins ...
    id("com.ceylonapz.agenttest") version "1.2.1"   // ← add this line
}
```

Sync. The `agentTest` task appears under **app → Tasks → verification**.

### Step 3 — API key

Add your key to the project root `local.properties` (gitignored by default in Android projects — never commit keys):

```properties
GEMINI_API_KEY=your-key
# or
ANTHROPIC_API_KEY=your-key
```

Environment variables also work and take priority. If both providers are configured, Gemini is preferred unless you pass `--llm=claude`.

---

## One-time setup per project

**Recommended for all projects** — protects Robolectric from unsupported-SDK crashes:

Create `app/src/test/resources/robolectric.properties`:

```properties
sdk=34
```

**For Compose UI test targets** (the agent preflights these and prints paste-ready lines if missing):

```kotlin
// app/build.gradle.kts
android {
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
dependencies {
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

**For ViewModel targets:**

```kotlin
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```

**.gitignore** — if you ever use the plugin as an included build, ignore its outputs:

```
agentTest/build/
agentTest/.gradle/
```

---

## How to use

### Daily workflow — auto mode

Finish a feature, then run with no arguments:

```bash
./gradlew :app:agentTest
```

The agent:
1. Reads `git diff HEAD` + untracked files
2. Triages each changed `.kt` file in this module's `src/main`
3. Skips up-to-date targets (⏩), incrementally updates changed ones (♻️), fully generates new ones
4. Runs everything and prints per-test results + a summary

```
🔍 No --target given — auto-detecting from git changes...
⏭️  Skipping MainActivity — Activity/Fragment (needs instrumented test)
🎯 Targets:
   • com.example.LoginValidator  →  JUnit unit test
   • com.example.LoginViewModel  →  ViewModel test (coroutines-test)
   • com.example.LoginScreen     →  Compose UI test (Robolectric)

━━━ com.example.LoginValidator [UNIT] ━━━
📋 Static analysis plan: 14 coverage items derived
🤖 Generating JUnit tests with GeminiClient...
📝 Wrote src/test/java/com/example/LoginValidatorGeneratedTest.kt
🧪 Running tests...
   ✅ PASS  validateEmail_emptyEmail_returnsRequiredError
   ✅ PASS  validatePassword_sixCharacters_returnsNull
   ❌ FAIL  validateEmail_uppercaseEmail_returnsNull

   FAILS
   validateEmail_uppercaseEmail_returnsNull
      expected:<null> but was:<Enter a valid email address>
      file:///.../LoginValidatorGeneratedTest.kt:52

━━━━━━━━━━ agentTest summary ━━━━━━━━━━
✅ com.example.LoginValidator
   15 tests passed
⏩ com.example.NetworkStatus
   up to date — use --target=... to force regeneration
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
💰 LLM usage: 2 call(s) · 6,183 in / 2,098 out tokens
```

### Test one specific class (forces regeneration)

```bash
./gradlew :app:agentTest --target=com.example.LoginValidator
```

Explicit mode bypasses the up-to-date skip — use it to force a fresh suite, test committed (unchanged) code, or demo.

### Mutation trust score

```bash
./gradlew :app:agentTest --target=com.example.LoginValidator --mutate
```

```
🧬 Mutation analysis: 8 mutant(s), one nested test run each...
   💀 killed    L12 '<' → '<=': password.length < MIN_PASSWORD_LENGTH
   🚨 SURVIVED  L8 6 → 7: const val MIN_PASSWORD_LENGTH = 6
🧬 1 survivor(s) — asking GeminiClient to strengthen the suite...
   💪 strengthened suite passes (17 tests) — re-running survivors...
   💀 now killed L8 6 → 7
✅ com.example.LoginValidator
   17 tests passed · 🧬 mutation score 8/8 (100%) after strengthening
```

Each mutant is one nested test run (~20-40s), which is why `--mutate` is opt-in. Your source files are always restored — mutants never leak into the working tree.

### CI mode

```bash
./gradlew :app:agentTest --strict
```

By default, failing generated tests do **not** fail the build (they're reported with a ⚠️ note — reviewing failures is your job, and a failure may be a real bug). `--strict` makes any COMPILE_FAILED / FAILED_TESTS / ERROR fail the build, for CI pipelines.

### Architecture advice for untestable UI files

```bash
./gradlew :app:agentTest --advise
```

Skipped UI files that contain embedded business logic (validation regexes, decision tables, length rules) get a concrete refactoring plan written to `build/agenttest/advice-<Class>.md`: what logic is trapped in the UI, a ready-to-paste extracted pure-Kotlin class, the exact UI diff, and what becomes testable.

### Choose the LLM provider

```bash
./gradlew :app:agentTest --llm=gemini
./gradlew :app:agentTest --llm=claude
```

### One-click runs from Android Studio (no terminal)

**Run → Edit Configurations → + → Gradle**, set Run to `:app:agentTest`, name it (e.g. `🤖 AgentTest`), save. It now lives in the ▶ dropdown next to your app configuration. The Gradle panel double-click (app → verification → agentTest) also works.

---

## All options

| Option | Default | Purpose |
|---|---|---|
| `--target=<fqcn>` | (auto) | Test one class; bypasses up-to-date skip |
| `--llm=gemini\|claude` | auto | Provider selection (env/local.properties key auto-detect) |
| `--mutate` | off | Mutation analysis + suite strengthening after pass |
| `--advise` | off | Refactoring plans for UI files with embedded logic |
| `--strict` | off | Fail the build on any target failure (CI) |

Configurable properties (in `app/build.gradle.kts`):

```kotlin
tasks.named<testagent.AgentTestTask>("agentTest") {
    maxRepairAttempts.set(2)   // compile-fix retries (default 2)
    maxMutants.set(8)          // mutants per --mutate run (default 8)
    maxLlmCalls.set(15)        // hard budget cap per run (default 15)
}
```

---

## How the agent decides (deterministic triage)

Each changed `.kt` file under this module's `src/main` is classified with zero LLM calls:

| File contains | Decision |
|---|---|
| Pure Kotlin class/object | 🎯 JUnit unit tests |
| extends `ViewModel` | 🎯 ViewModel tests (kotlinx-coroutines-test, inline fakes — no mocking libraries) |
| public `@Composable` functions | 🎯 Compose UI tests (Robolectric — JVM, no emulator) |
| Activity/Fragment subclass | ⏭️ skip (needs instrumented test) |
| `android.*` non-UI dependency | ⏭️ skip (consider extracting logic — see `--advise`) |
| `*GeneratedTest.kt` | ⏭️ skip (agent's own output) |

Stateful composables (`remember`/`mutableStateOf`) additionally get **metamorphic state-restoration tests**. UI tests respect the **layer boundary**: async/network success paths are tested at the ViewModel layer with fakes, never at the UI layer where real repositories can't run.

## Incremental engine

Every generated test file carries a state header on line 1:

```kotlin
// agenttest: hash=a3f8c2d1e9b4f7a2 status=passed
```

| Situation | Behavior |
|---|---|
| status=passed, source hash unchanged | ⏩ skipped — zero LLM calls |
| status=passed, source hash changed | ♻️ incremental — passing tests preserved, new tests added for the `git diff -U0` only |
| broken/failed suite | 🧹 deleted, full regeneration |
| no test file | full generation |

## Reading the results

- ✅ **PASSED** — suite green; with `--mutate`, check the 🧬 score for suite strength
- ❌ **FAILED_TESTS** — one or more tests fail. This is *information*, not noise. Three possibilities: **(a)** a genuine source bug (AgentTest's metamorphic tests have caught real state-loss and double-tap bugs), **(b)** a testability problem in the source (extract logic — see `--advise`), **(c)** a wrong LLM expectation. Review the FAILS section — links jump straight to the test method
- 💥 **COMPILE_FAILED** — generation couldn't produce a compilable suite; see the linked log
- 🔧 **NEEDS_SETUP** — missing test dependencies; paste the printed lines and re-run
- 🐛 **suspected source bug(s)** — the suite passed but contains `// SUSPECTED CODE BUG:` markers worth reading

---

## Troubleshooting

**`Plugin not found` on sync** — `mavenLocal()` is missing from `pluginManagement.repositories` in root `settings.gradle.kts` (it must be in `pluginManagement`, not `dependencyResolutionManagement`), or the plugin wasn't published (`ls ~/.m2/repository/com/ceylonapz/`).

**Changes to the plugin don't take effect** — Gradle daemon cached the old jar. Re-publish with a version bump, or `./gradlew --stop` then re-run.

**`Compilation failed in OTHER test files`** — a broken test file (not generated in this run) is blocking the module's test compilation. Fix or delete it; the agent only auto-cleans its own broken output.

**`LLM call budget exhausted`** — the run hit `maxLlmCalls` (default 15). Raise it in the task config or split the run.

**Robolectric `targetSdkVersion X > maxSdkVersion Y`** — add `app/src/test/resources/robolectric.properties` with `sdk=34`.

**Gemini 404 model error** — the default model string may have been deprecated; update `GeminiClient.kt` and re-publish.

**Nested build is slow on first run** — the agent runs tests in a nested Gradle build with a separate `--project-cache-dir` (avoids lock contention). The first run builds that cache; later runs reuse it.

---

## Cost

Typical steady-state run: **1–2 LLM calls per changed target, zero for unchanged ones** — a multi-target run lands around 10–20K tokens (fractions of a cent on Gemini Flash). Every run ends with a real usage line (`💰 LLM usage: ...`) parsed from the API response, and `maxLlmCalls` caps worst-case spend. There is no test-failure repair loop by design: failures are reported, not iterated away with paid calls.

## Privacy note

Source code of targeted files is sent to your configured LLM provider (Google or Anthropic) using **your** API key. Don't run it on code you can't share with those providers, and never commit API keys.

---

## Roadmap

- Instrumented tier: real device/emulator E2E tests (`--device`), deterministic smoke checks
- Espresso generation for XML/hybrid apps
- Gradle Plugin Portal publication (`id(...)` one-liner install, no mavenLocal)
- PSI-grade planning via Kotlin compiler analysis
- Provider A/B benchmarking reports

---

*Built by [CeylonAPZ](https://github.com/amalskr) — part of the Agent suite (HotFixAgent, AgentTest).*