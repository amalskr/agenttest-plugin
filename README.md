# AgentTest v10 — standalone Gradle plugin

Same agent as v3 (git auto-discovery, deterministic triage, generate → run →
repair loop, Gemini + Claude), now packaged as a named **included build** —
`agentTest/` — instead of the magic `buildSrc` folder.

Benefits over buildSrc:
- Any folder name you like
- Changing agent code does NOT invalidate your whole build cache (buildSrc does)
- Reusable: drop the same folder into any project (AtharaMaga, Soho, ...)
- Task auto-registers via the plugin — no tasks.register block needed

## Install (3 steps)

1. **Delete the old `buildSrc/` folder** (if you added it in v2/v3) and remove
   the old `tasks.register<testagent.AgentTestTask>("agentTest")` block from
   `app/build.gradle.kts`.

2. Copy the `agentTest/` folder into your **project root**, then register it in
   your root `settings.gradle.kts` — inside `pluginManagement`:

```kotlin
pluginManagement {
    includeBuild("agentTest")   // <-- add this line
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

3. Apply the plugin in `app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    // ... your existing plugins ...
    id("com.ceylonapz.agenttest")   // <-- add this
}
```

Sync. The `agentTest` task appears under app → Tasks → verification.

## API key

```bash
export GEMINI_API_KEY=...       # Gemini (gemini-3.6-flash)
# or
export ANTHROPIC_API_KEY=...    # Claude (claude-sonnet-4-6)
```

## Usage

```bash
# AUTO MODE: discovers testable classes from git diff + untracked files
./gradlew :app:agentTest --llm=gemini

# EXPLICIT MODE: one specific class
./gradlew :app:agentTest --target=com.example.MyClass --llm=gemini
```

## How auto mode decides (deterministic triage — zero LLM calls)

| File looks like            | Decision                                    |
|----------------------------|---------------------------------------------|
| `*GeneratedTest.kt`        | ⏭️ skip (agent's own output)                 |
| Contains `@Composable`     | ⏭️ skip — needs Compose UI test              |
| Extends Activity/Fragment  | ⏭️ skip — needs UI/instrumented test         |
| Imports `android.*`        | ⏭️ skip — not JVM-testable (extract logic!)  |
| Pure Kotlin class/object   | 🎯 TARGET                                    |

## Pipeline per target

1. Generate JUnit 4 tests via LLM
2. Deterministic guard: forbidden imports rejected BEFORE any build
3. Nested `testDebugUnitTest --tests <filter>` run (separate project-cache-dir)
4. JUnit XML parsing
5. Compile errors → foreign-error guard, then repair loop (max 3)
6. Persistent failures → flagged as possible genuine source bug
7. Multi-target summary

Outputs: `app/src/test/java/<pkg>/<Class>GeneratedTest.kt`,
logs at `app/build/agenttest/run-<Class>-N.log`.
Grep generated tests for `// SUSPECTED CODE BUG:` markers.

## New in v5

### Per-test PASS/FAIL report with clickable links

Instead of a bare "All N tests passed", every test is listed individually:

```
   ✅ PASS  validateEmail_emptyEmail_returnsRequiredError
            file:///.../LoginValidatorGeneratedTest.kt:11
   ❌ FAIL  validatePassword_fiveCharacters_returnsMinLengthError
            expected:<...> but was:<...>
            file:///.../LoginValidatorGeneratedTest.kt:62
```

The `file://...:line` links are clickable in the Android Studio Build/Run
console — clicking jumps straight to that test method in the file.

### Refactoring advice for skipped UI files (--advise)

When a skipped Composable/Activity contains embedded logic (detected
deterministically: validation regexes, when-tables, length rules, comparisons),
it is flagged:

```
⏭️  Skipping LoginScreen — Composable (needs Compose UI test) ⚠️ embedded logic detected
💡 1 skipped file(s) contain embedded logic... Re-run with --advise for concrete refactoring plans.
```

Run with `--advise` and the agent writes a full extraction plan per file to
`build/agenttest/advice-<Class>.md`: what logic is trapped in the UI, a
ready-to-paste pure-Kotlin extracted class, the exact UI diff, and what
becomes testable afterwards.

```bash
./gradlew :app:agentTest --llm=gemini --advise
```

## New in v6 — the agent decides the test strategy itself

Triage no longer skips Compose files. Each changed file is classified
deterministically and tested with the right strategy:

| File contains                  | Agent's decision                              |
|--------------------------------|-----------------------------------------------|
| Pure Kotlin class/object       | 🎯 JUnit unit tests                            |
| public @Composable functions   | 🎯 Compose UI tests (Robolectric, JVM, no emulator) |
| Activity/Fragment subclass     | ⏭️ skip (needs instrumented test)              |
| android.* non-UI dependency    | ⏭️ skip (advice candidate)                     |

Compose test generation covers user-visible behavior autonomously: initial
state, inputs → error messages, clicks → callbacks, and displayed parameters
verified with TWO different values (proves nothing is hardcoded).

One-time setup for Compose targets is preflight-checked deterministically —
if deps are missing, the agent prints paste-ready lines instead of failing
with a cryptic compile error:

```
🔧 com.ceylonapz.hotfixagent.LoginSuccessScreen
   Compose UI testing needs one-time setup in app/build.gradle.kts:
      testImplementation("org.robolectric:robolectric:4.14.1")
      testImplementation("androidx.compose.ui:ui-test-junit4") // + compose BOM
      debugImplementation("androidx.compose.ui:ui-test-manifest")
      android { testOptions { unitTests { isIncludeAndroidResources = true } } }
```

Add those once, re-run, and Compose screens get tested automatically from then on.

## New in v7 — ViewModel support

Triage now recognizes ViewModels as a third strategy:

| File contains                  | Agent's decision                              |
|--------------------------------|-----------------------------------------------|
| Pure Kotlin class/object       | 🎯 JUnit unit tests                            |
| extends ViewModel              | 🎯 ViewModel tests (kotlinx-coroutines-test)   |
| public @Composable functions   | 🎯 Compose UI tests (Robolectric)              |
| Activity/Fragment subclass     | ⏭️ skip (needs instrumented test)              |

ViewModel test generation handles:
- Dispatchers.setMain / resetMain scaffolding with StandardTestDispatcher
- runTest + advanceUntilIdle() before state assertions
- StateFlow/LiveData state-transition testing per public function
- Inline FAKE implementations for interface dependencies (never mocking libraries)

One-time setup (preflight-checked, paste-ready if missing):

```
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```

## New in v8 — incremental testing (the agent tests only what changed)

Every generated test file now carries a state header on line 1:

```kotlin
// agenttest: hash=<source-sha> status=passed
```

That single line makes the agent stateful with zero external databases:

| Situation                                        | Agent behavior                                   |
|--------------------------------------------------|--------------------------------------------------|
| Test exists, status=passed, source hash UNCHANGED | ⏩ skipped — "up to date"                         |
| Test exists, status=passed, source hash CHANGED   | ♻️ INCREMENTAL — preserve passing tests, add tests for the git diff only |
| Test exists but broken/failed                     | 🧹 deleted, full regeneration                     |
| No test                                           | Full generation                                   |

Incremental updates feed the LLM: the existing (passing) tests, a `git diff -U0`
of exactly what changed, and the current source — with strict instructions to
preserve passing tests verbatim and add only delta coverage.

`--target=...` always forces regeneration (your "re-run this one" escape hatch).

### Also in v8

- **Hallucination circuit breaker**: if the same unresolved API fails in two
  consecutive compile attempts, the repair prompt escalates — "this API does
  not exist, restructure without it" — instead of looping on variations.
- **Structure reminders in every repair/compile-fix prompt**: repairs no longer
  drop createComposeRule, @Config, or dispatcher setup (a recurring failure).
- **Compose prompt hardening** from real failures: onAllNodesWithText for text
  that legitimately appears in multiple nodes ("found 2 nodes" fix), mainClock
  guidance that accounts for AnimatedVisibility exit durations, assertExists
  preference, below-the-fold scrolling.
- **Module-scoped discovery**: only this module's src/main is targeted — the
  agent can no longer try to test its own plugin code.
- **Header-aware pre-clean**: broken generated tests are removed up front;
  passing suites are kept for incremental mode.

Recommended one-time setup: `app/src/test/resources/robolectric.properties`
containing `sdk=34` (protects against Robolectric max-SDK crashes even if the
LLM drops the @Config annotation).

## New in v9 — lenient by default, --strict for CI

Failing generated tests no longer fail the build. The agent's job is to
generate, run, and REPORT — deciding what to do about failures is yours.

```bash
# Local dev (default): full summary, BUILD SUCCESSFUL even with failures
./gradlew :app:agentTest --llm=gemini
#   ⚠️  1 target(s) need attention (build not failed — use --strict for CI)

# CI mode: any COMPILE_FAILED / FAILED_TESTS / ERROR fails the build
./gradlew :app:agentTest --llm=gemini --strict
```

## All options

| Option            | Default | Purpose                                          |
|-------------------|---------|--------------------------------------------------|
| `--target=<fqcn>` | (auto)  | Test one class; forces regeneration              |
| `--llm=gemini\|claude` | auto  | Provider selection (env-key auto-detect)     |
| `--advise`        | off     | Extraction plans for UI files w/ embedded logic  |
| `--strict`        | off     | Fail build on any target failure (CI)            |

## New in v10 — hidden code, no-terminal workflow

### API key in local.properties (no more export)
Add to your project root `local.properties` (already gitignored in Android projects):
```
GEMINI_API_KEY=your-key
```
Env variables still work and take priority.

### Hide the agent code (binary plugin via mavenLocal)
1. While `agentTest/` is still in the project, publish it once:
   `./gradlew -p agentTest publishToMavenLocal`
2. In root `settings.gradle.kts`, remove `includeBuild("agentTest")` and add
   `mavenLocal()` FIRST in pluginManagement repositories.
3. In `app/build.gradle.kts`: `id("com.ceylonapz.agenttest") version "1.0.0"`
4. Delete the `agentTest/` folder from the project. Sync — code gone, task remains.

### One-click runs (no terminal)
Run → Edit Configurations → + → Gradle → Run: `:app:agentTest --llm=gemini`
→ appears in the ▶ dropdown next to your app config. Also: Gradle panel →
app → verification → agentTest (double-click).
