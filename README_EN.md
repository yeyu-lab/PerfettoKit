# PerfettoKit

**English** | [简体中文](README.md)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-API%2024%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7f52ff.svg)](https://kotlinlang.org)
[![AI Enhanced](https://img.shields.io/badge/AI-Enhanced%20%F0%9F%A7%A0-ff6b6b.svg)](#-ai-powered-diagnosis)

> 🧠 **AI-Powered Android Performance Detection & Root-Cause Analysis SDK**
>
> Multi-dimensional data collection + Rule engine + **LLM-driven attribution** — from detection to fix suggestions in one step.
> Produces a structured diagnosis report: **what is slow, why it is slow, and how to fix it** — with AI-generated code-level repair suggestions.

---

## ✨ Features

- **Zero-config setup**: auto-initialized via a `ContentProvider` — works out of the box.
- **Manual + automatic modes**: instrument critical paths precisely, auto-cover the rest (Activity launch, list scrolling, …).
- **Multi-dimensional collection**: frames, CPU, memory, threads, network, IO, Bitmaps, allocations, Looper slow-messages.
- **Method-level root cause**: 5 ms sampling profiler + Choreographer slow-message stack capture + FrameMetrics phase breakdown.
- **Rule engine + Skill library**: 5 built-in rules + 10 YAML jank patterns (GC pressure, main-thread IO, Binder block, image decode, …).
- **Historical regression detection**: a local `SessionStore` records past metrics, anomalies are flagged automatically.
- **🧠 AI-Powered Diagnosis**: plug in any OpenAI-compatible LLM (GPT / Claude / local Ollama) to get **root cause + fix steps + code examples** automatically.
- **Logcat-friendly output**: Overview → Top offenders → Time attribution → Detailed metrics, layered for fast triage.

---

## 📦 Project Layout

```
PerfettoKit/
├── sdk/        # Core SDK library (io.github.perfettokit)
└── sample/     # Integration sample app (io.github.perfettokit.sample)
```

### SDK Packages

| Package | Responsibility |
|---|---|
| `PerfettoKit` / `PerfettoKitInitializer` | Entry object + auto-init Provider |
| `session.TraceSession` | Lifecycle of one detection session |
| `collector/` | Data collectors: Frame / Cpu / Memory / Thread / Network / IO / Bitmap / Allocation / Looper / MethodTracer |
| `rule/` | Rule engine: SlowFrame / ScrollJank / CpuUsage / Memory / Thread |
| `analyzer/` | Stack sample aggregation, root-cause analysis, hot-method ranking |
| `skill/` + `assets/perfettokit/skills/*.yaml` | Extensible knowledge base of jank patterns |
| `auto/` | `AutoSceneDetector` (auto scene recognition) + `AnomalyDetector` (regression detection) |
| `history/SessionStore` | Local persistence of past sessions |
| `ai/` | Optional LLM enhancement (OpenAI-compatible) |
| `report/` | `DiagnosisReport` data model + `LogcatReporter` |

---

## 🚀 Getting Started

### 1. Add the SDK

**Option A: JitPack (Recommended)**

Add JitPack repository to your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your app module:

```kotlin
dependencies {
    implementation("com.github.yeyu-lab:PerfettoKit:1.0.0")
}
```

[![](https://jitpack.io/v/yeyu-lab/PerfettoKit.svg)](https://jitpack.io/#yeyu-lab/PerfettoKit)

**Option B: Source Module**

```kotlin
include(":sdk")
// App module
dependencies {
    implementation(project(":sdk"))
}
```

Requirements: `minSdk = 24`, `compileSdk = 34`.

### 2. Auto Initialization (default)

The SDK ships a `PerfettoKitInitializer` ContentProvider that calls `PerfettoKit.init()` before `Application.onCreate`. **No code is required** to get the default configuration running.

### 3. Custom Initialization (optional)

To customize rules / reporter / AI provider, call `init` explicitly in `Application.onCreate` — the auto-init call is then skipped by a re-entry guard:

```kotlin
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PerfettoKit.init(this, PerfettoKit.Config(
            reporter = LogcatReporter(),
            appPackagePrefix = "io.github.perfettokit.sample"
            // optional: aiProvider = OpenAICompatProvider(apiKey = "...", baseUrl = "...")
        ))

        // Enable automatic scene detection (Activity launch / list scroll)
        PerfettoKit.enableAutoDetect(AutoSceneDetector.Config(
            detectLaunch = true,
            detectScroll = false
        ))
    }
}
```

---

## 🔧 Three Usage Patterns

### Pattern 1 — `measure {}` block

```kotlin
PerfettoKit.measure("inflate_complex_layout") {
    setContentView(R.layout.activity_advanced)
}
```

### Pattern 2 — Manual `beginSession` / `end`

```kotlin
recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
    private var session: TraceSession? = null
    override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
        when (newState) {
            RecyclerView.SCROLL_STATE_DRAGGING ->
                session = PerfettoKit.beginSession("list_scroll")
            RecyclerView.SCROLL_STATE_IDLE -> {
                session?.end(); session = null
            }
        }
    }
})
```

### Pattern 3 — Method-level `MethodTracer.trace`

```kotlin
override fun onBindViewHolder(holder: VH, position: Int) {
    MethodTracer.trace("SampleAdapter.onBind") {
        holder.textView.text = "Item #$position"
        // ... suspect-slow code
    }
}
```

Methods wrapped by `MethodTracer.trace` are correlated with jank frames in the report.

### Custom Rule Thresholds

```kotlin
val strictRules = listOf(
    SlowFrameRule(thresholdMs = 8.0, severeThresholdMs = 16.67)  // 120fps standard
)
val session = PerfettoKit.beginSession("high_fps_animation", rules = strictRules)
```

---

## 📱 Sample Demo

The `sample/` module showcases the three integration patterns and **deliberately injects jank** to validate the detection pipeline.

### Run

```bash
./gradlew :sample:installDebug
```

Open the app, scroll the list, then watch Logcat:
```bash
adb logcat -s PerfettoKit:I JankDemo:W
```

### `MainActivity` — List scroll detection

- `OnScrollListener` calls `beginSession("list_scroll")` on `DRAGGING` and `end()` on `IDLE`.
- `onBindViewHolder` is wrapped with `MethodTracer.trace("SampleAdapter.onBind")`.
- Four classes of jank are injected to trigger different rules / Skills:

| Trigger | Simulated workload | Expected detection |
|---|---|---|
| `position % 7 == 0` | `Thread.sleep(20)` | Main-thread IO / sync block |
| `position % 11 == 0` | 5 000 string concatenations + regex | CPU intensive |
| `position % 13 == 0` | Sorting an 80 000-element IntArray | CPU heavy compute |
| `position % 17 == 0` | Allocating a 1024×1024 ARGB_8888 Bitmap | Memory churn / image decode |

### `AdvancedUsageActivity` — Advanced usage

- `PerfettoKit.measure {}` around `setContentView`.
- Custom `SlowFrameRule(thresholdMs = 8.0)` for 120 fps targets.
- Demonstrates ending a session via `postDelayed`.

### `SampleApp` — Initialization

A complete reference for `init` + `enableAutoDetect`.

---

## 📊 Detection Report Example

Below is a representative Logcat snippet after scrolling the `MainActivity` list for ~3 seconds (simplified for clarity):

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 [list_scroll] 2 issues detected, duration 3120ms
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

[Overview]
   Rendering: 28/187 frames (15.0%) [FrameMetrics, user-perceived]
   Main callback: 31/192 (16.1%) [Choreographer, includes non-render blocks]
   Main-thread CPU: 72.4% | duration: 3120ms
   Slow msgs: 14/521 (2.7%) | total block: 612ms | max: 41ms

[Top Offenders] (stacks captured on message timeout + jank aggregation)
   🎯 JankDemo.heavyCompute
      8 timeouts, impacted 11/28 jank frames, total 264ms, avg 33.0ms, peak 41ms [App]
      chain: SampleAdapter.onBind → JankDemo.heavyCompute → IntArray.sort
   🎯 JankDemo.bitmapAlloc
      5 timeouts, impacted 7/28 jank frames, total 145ms, avg 29.0ms, peak 35ms [Bitmap]
      chain: SampleAdapter.onBind → Bitmap.createBitmap
   🎯 JankDemo.stringBuild
      6 timeouts, impacted 6/28 jank frames, total 132ms, avg 22.0ms, peak 28ms [CPU]
   🎯 JankDemo.fakeSyncIO
      9 timeouts, impacted 4/28 jank frames, total 180ms, avg 20.0ms, peak 22ms [IO]

[Jank Time Attribution] (based on 5ms stack sampling, ranked by time share)
   📱 JankDemo.heavyCompute — share 38.2% (normal 0.4%, 95.5x), appearance 39.3%, peak 41ms
   📱 JankDemo.bitmapAlloc — share 19.1% (normal 0.2%, 95.5x), appearance 25.0%, peak 35ms
   🔧 nativePollOnce — no app hot method, appearance 11.2%, avg 6.3ms, peak 18ms

[Issues]
 [HIGH] ScrollJank — 4 consecutive jank runs detected (>=3 frames each); user-visible stutter
        Suggestions:
        1. RecyclerView: profile onBindViewHolder, avoid sync image loading
        2. Custom View: trim work in onDraw, cache to a Canvas
        3. Check for network/database calls triggered while scrolling
 [MED]  SlowFrame — 17 mild jank frames (>16.67ms), 9.1%

[Skill Matches]
   ✔ cpu_intensive       — matched JankDemo.heavyCompute (CPU heavy sort)
   ✔ image_decode_main_thread — matched JankDemo.bitmapAlloc
   ✔ main_thread_io      — matched JankDemo.fakeSyncIO (Thread.sleep)
```

### How to read it

1. **Overview** gives four headline metrics — *rendering jank / main-callback jank / main-thread CPU / slow messages* — separating **user-perceived** stutter from **potential blocking**.
2. **Top Offenders** is built from "stacks captured on message timeout"; you get **method name + call chain + impacted jank frames** at method-level precision.
3. **Jank Time Attribution** compares stack-sample share during jank vs. normal periods, so a **truly-slow method** is surfaced (high-frequency but cheap methods aren't misclassified).
4. **Skill Matches** map detected symptoms to known jank patterns (YAML skills), pointing you at the fix direction directly.
5. **Regression**: if historical metrics for this `scene` have meaningfully degraded, `AnomalyDetector` appends a `REGRESSION` tag to the report.

---

## 🧩 Built-in Rules & Skills

### Rules (`sdk/.../rule/`)

| Rule | What it detects |
|---|---|
| `SlowFrameRule` | Single-frame duration > 16.67 ms / 33.33 ms |
| `ScrollJankRule` | Consecutive jank runs + average FPS stability |
| `CpuUsageRule` | Main-thread / process CPU usage |
| `MemoryRule` | Java/Native heap growth, GC frequency |
| `ThreadRule` | Thread-count anomalies, main-thread block |

### Skills (`assets/perfettokit/skills/*.yaml`)

`binder_block` · `cpu_intensive` · `gc_pressure` · `heavy_draw` · `heavy_layout` ·
`image_decode_main_thread` · `lock_contention` · `main_thread_io` ·
`memory_leak_gc` · `recycler_scroll`

Inject your own via `PerfettoKit.Config(extraSkills = listOf(...))`.

---

## � AI-Powered Diagnosis

> **From performance data to fix code — in one step.**

PerfettoKit integrates with any OpenAI-compatible LLM service. It automatically constructs a structured prompt from collected performance data (hot methods, slow messages, frame timings) and the AI outputs:

| Output | Description |
|--------|-------------|
| 🎯 Root Cause (one line) | Pinpoints the core jank reason |
| 📋 Fix Steps | Step-by-step remediation guide |
| 💻 Code Example | Ready-to-use Kotlin fix code |

### Setup

```kotlin
// Cloud LLM (GPT-4o / Claude etc.)
PerfettoKit.init(this, PerfettoKit.Config(
    aiProvider = OpenAICompatProvider(
        apiKey = BuildConfig.LLM_API_KEY,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini"
    )
))

// Local LLM (Ollama / LM Studio — no API key needed)
PerfettoKit.init(this, PerfettoKit.Config(
    aiProvider = OpenAICompatProvider(
        apiKey = "ollama",
        baseUrl = "http://your-pc-ip:11434/v1",
        model = "qwen2.5-coder:7b"
    )
))
```

### Sample AI Output

```
━━━ AI Enhanced Suggestions ━━━
### Root Cause
   1. Reduce object creation and string concatenation in SampleAdapter.onBind.
   2. Move synchronous I/O to a thread pool.
━━━ Code Example ━━━
class SampleAdapter : RecyclerView.Adapter<SampleAdapter.ViewHolder>() {
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sb = StringBuilder()
        sb.append("Name: ").append(data.name)
        holder.itemView.textView.text = sb.toString()
    }
}
```

### Supported LLM Services

| Service | baseUrl | Recommended Model |
|---------|---------|-------------------|
| OpenAI | `https://api.openai.com/v1` | gpt-4o-mini |
| Ollama (local) | `http://localhost:11434/v1` | qwen2.5-coder:7b |
| LM Studio (local) | `http://localhost:1234/v1` | Any GGUF model |
| DeepSeek | `https://api.deepseek.com/v1` | deepseek-coder |

---

## 🛠️ Build

```bash
./gradlew :sdk:assembleRelease       # SDK aar
./gradlew :sample:assembleDebug      # Demo apk
./gradlew :sample:installDebug       # install & run
```

---

## 📄 License

[Apache License 2.0](LICENSE)
