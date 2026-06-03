# PerfettoKit

[English](README_EN.md) | **简体中文**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-API%2024%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7f52ff.svg)](https://kotlinlang.org)
[![AI Enhanced](https://img.shields.io/badge/AI-Enhanced%20%F0%9F%A7%A0-ff6b6b.svg)](#-ai-智能诊断)

> 🧠 **AI 加持的 Android 性能检测与根因分析 SDK**
>
> 多维数据采集 + 规则引擎 + **LLM 智能归因**，从检测到修复建议一步到位。
> 输出"哪里慢、为什么慢、如何修"的结构化诊断报告，并由 AI 生成**可执行的代码级修复方案**。

---

## ✨ 特性

- **零侵入接入**：通过 `ContentProvider` 自动初始化，导入即用。
- **手动 + 自动双模式**：精准标记关键路径，自动兜底覆盖未标记场景（Activity 启动、列表滑动等）。
- **多维数据采集**：帧率、CPU、内存、线程、网络、IO、Bitmap、对象分配、Looper 慢消息。
- **方法级根因定位**：5ms 周期栈采样 + Choreographer 慢消息抓栈 + FrameMetrics 渲染阶段统计。
- **规则引擎 + Skill 库**：内置 5 套规则 + 10 条 YAML 卡顿模式（GC 抖动、主线程 IO、Binder 阻塞、图片解码等）。
- **历史回归检测**：本地 `SessionStore` 记录历史指标，自动检测劣化。
- **🧠 AI 智能诊断**：接入任意 OpenAI 兼容 LLM（GPT / Claude / 本地 Ollama），自动输出**根因一句话 + 优化步骤 + 代码示例**。
- **Logcat 友好输出**：总览 → 卡顿元凶 Top → 耗时归因 → 详细数据分层展示。

---

## 📦 模块结构

```
PerfettoKit/
├── sdk/        # 核心 SDK 库（io.github.perfettokit）
└── sample/     # 接入示例 App（io.github.perfettokit.sample）
```

### SDK 关键包

| 包 | 作用 |
|---|---|
| `PerfettoKit` / `PerfettoKitInitializer` | 入口对象 + 自动初始化 Provider |
| `session.TraceSession` | 一次检测会话的生命周期管理 |
| `collector/` | 多维数据采集：Frame / Cpu / Memory / Thread / Network / IO / Bitmap / Allocation / Looper / MethodTracer |
| `rule/` | 规则引擎：SlowFrame / ScrollJank / CpuUsage / Memory / Thread |
| `analyzer/` | 栈采样聚合 + 根因分析 + 热点方法识别 |
| `skill/` + `assets/perfettokit/skills/*.yaml` | 可扩展的卡顿模式知识库 |
| `auto/` | `AutoSceneDetector`（自动场景识别）+ `AnomalyDetector`（回归检测） |
| `history/SessionStore` | 历史会话本地存储 |
| `ai/` | 可选的 LLM 增强（OpenAI 兼容协议） |
| `report/` | `DiagnosisReport` 数据结构 + `LogcatReporter` 输出 |

---

## 🚀 快速接入

### 1. 引入 SDK

在你的 `settings.gradle.kts` 中加入本仓库的 `:sdk` 模块（或后续发布到 Maven 后通过坐标引入）：

```kotlin
include(":sdk")
```

App 模块依赖：
```kotlin
dependencies {
    implementation(project(":sdk"))
}
```

最低支持版本：`minSdk = 24`，`compileSdk = 34`。

### 2. 自动初始化（默认）

SDK 通过 `PerfettoKitInitializer` ContentProvider 在 `Application.onCreate` 之前自动调用 `PerfettoKit.init()`，**无需任何代码**即可工作。

### 3. 自定义初始化（可选）

如需自定义规则 / Reporter / AI Provider，在 `Application.onCreate` 中显式调用即可（自动 init 会被防重入跳过）：

```kotlin
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PerfettoKit.init(this, PerfettoKit.Config(
            reporter = LogcatReporter(),
            appPackagePrefix = "io.github.perfettokit.sample"
            // 可选: aiProvider = OpenAICompatProvider(apiKey = "...", baseUrl = "...")
        ))

        // 开启自动场景检测（Activity 启动 / 列表滑动）
        PerfettoKit.enableAutoDetect(AutoSceneDetector.Config(
            detectLaunch = true,
            detectScroll = false
        ))
    }
}
```

---

## 🔧 三种使用姿势

### 姿势一：`measure {}` 块级检测

```kotlin
PerfettoKit.measure("inflate_complex_layout") {
    setContentView(R.layout.activity_advanced)
}
```

### 姿势二：手动 `beginSession` / `end`

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

### 姿势三：方法级插桩 `MethodTracer.trace`

```kotlin
override fun onBindViewHolder(holder: VH, position: Int) {
    MethodTracer.trace("SampleAdapter.onBind") {
        holder.textView.text = "Item #$position"
        // ... 怀疑慢的代码
    }
}
```

被 `MethodTracer.trace` 包裹的方法会在报告里与慢帧关联。

### 自定义规则阈值

```kotlin
val strictRules = listOf(
    SlowFrameRule(thresholdMs = 8.0, severeThresholdMs = 16.67)  // 120fps 标准
)
val session = PerfettoKit.beginSession("high_fps_animation", rules = strictRules)
```

---

## 📱 Sample Demo 介绍

`sample/` 模块演示了三种典型接入方式，并**故意制造多种卡顿**以验证检测能力。

### 运行

```bash
./gradlew :sample:installDebug
```

打开 App 后滑动列表，观察 Logcat：
```bash
adb logcat -s PerfettoKit:I JankDemo:W
```

### `MainActivity` — 列表滑动检测

- 使用 `OnScrollListener` 在 `DRAGGING` 时 `beginSession("list_scroll")`，在 `IDLE` 时 `end()`。
- `onBindViewHolder` 内通过 `MethodTracer.trace("SampleAdapter.onBind")` 做方法级插桩。
- 故意制造 4 类卡顿场景以触发不同规则与 Skill 命中：

| 触发条件 | 模拟场景 | 预期检测结果 |
|---|---|---|
| `position % 7 == 0` | `Thread.sleep(20)` | 主线程 IO / 同步阻塞 |
| `position % 11 == 0` | 5000 次字符串拼接 + 正则 | CPU 密集 |
| `position % 13 == 0` | 8 万元素 IntArray 排序 | CPU 重计算 |
| `position % 17 == 0` | 分配 1024x1024 ARGB_8888 Bitmap | 内存抖动 / 图片解码 |

### `AdvancedUsageActivity` — 进阶用法

- `PerfettoKit.measure {}` 测量 `setContentView` 的耗时。
- 自定义 `SlowFrameRule(thresholdMs = 8.0)` 适配 120fps 高刷场景。
- 演示 `postDelayed` 定时结束 Session。

### `SampleApp` — 初始化配置

完整展示了 `init` + `enableAutoDetect` 的搭配方式。

---

## 📊 检测结果案例

下面是滑动 `MainActivity` 列表约 3 秒后的典型 Logcat 输出（已脱敏简化）：

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📊 [list_scroll] 检测到 2 项问题，耗时 3120ms
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

【总览】
   实际渲染: 28/187帧 (15.0%) [FrameMetrics, 用户感知]
   主线程回调: 31/192 (16.1%) [Choreographer, 含非渲染阻塞]
   主线程CPU: 72.4% | 耗时: 3120ms
   慢消息: 14条/521条 (2.7%) | 累计阻塞: 612ms | 最慢: 41ms

【卡顿元凶 Top 5】(消息超时时抓栈确认 + 掉帧聚合)
   🎯 JankDemo.heavyCompute
      8次超时, 影响 11/28帧掉帧, 累计 264ms, 均 33.0ms, 峰值 41ms [App]
      链: SampleAdapter.onBind → JankDemo.heavyCompute → IntArray.sort
   🎯 JankDemo.bitmapAlloc
      5次超时, 影响 7/28帧掉帧, 累计 145ms, 均 29.0ms, 峰值 35ms [Bitmap]
      链: SampleAdapter.onBind → Bitmap.createBitmap
   🎯 JankDemo.stringBuild
      6次超时, 影响 6/28帧掉帧, 累计 132ms, 均 22.0ms, 峰值 28ms [CPU]
   🎯 JankDemo.fakeSyncIO
      9次超时, 影响 4/28帧掉帧, 累计 180ms, 均 20.0ms, 峰值 22ms [IO]

【掉帧耗时归因】(基于 5ms 栈采样, 按时间占比)
   📱 JankDemo.heavyCompute — 占比 38.2% (正常 0.4%, 95.5x), 出现率 39.3%, 峰值 41ms
   📱 JankDemo.bitmapAlloc — 占比 19.1% (正常 0.2%, 95.5x), 出现率 25.0%, 峰值 35ms
   🔧 nativePollOnce — 无 App 代码热点, 出现率 11.2%, 均 6.3ms, 峰值 18ms

【问题列表】
 [HIGH] ScrollJank — 滑动过程中检测到 4 次连续掉帧 (连续 >=3 帧超时)，用户可明显感知卡顿
        建议:
        1. RecyclerView: 检查 onBindViewHolder 耗时，避免同步加载图片
        2. 自定义 View: 减少 onDraw 中复杂计算，使用 Canvas 缓存
        3. 检查是否有滑动时触发的网络/数据库操作
 [MED]  SlowFrame — 检测到 17 帧轻微掉帧 (>16.67ms)，占比 9.1%

【Skill 命中】
   ✔ cpu_intensive       — 命中 JankDemo.heavyCompute（CPU 密集排序）
   ✔ image_decode_main_thread — 命中 JankDemo.bitmapAlloc
   ✔ main_thread_io      — 命中 JankDemo.fakeSyncIO (Thread.sleep)
```

### 报告解读

1. **总览**给出"实际渲染掉帧 / 主线程回调掉帧 / 主线程 CPU / 慢消息"四个核心指标，区分**用户感知**与**潜在阻塞**。
2. **卡顿元凶 Top** 基于"消息超时时抓栈"，直接给出**方法名 + 调用链 + 影响掉帧数**，定位精度到方法级。
3. **掉帧耗时归因**通过对比"掉帧期间"与"正常期间"栈采样占比，识别**真正变慢的方法**（避免把高频低耗时方法误判为元凶）。
4. **Skill 命中**把检测到的现象映射到已知卡顿模式库（YAML 规则），直接给出修复建议方向。
5. **历史回归**：若该 `scene` 历史指标存在显著劣化，`AnomalyDetector` 会在报告中追加 `REGRESSION` 标签。

---

## 🧩 内置规则与 Skill

### Rules（`sdk/.../rule/`）

| 规则 | 检测点 |
|---|---|
| `SlowFrameRule` | 单帧耗时 > 16.67ms / 33.33ms |
| `ScrollJankRule` | 连续掉帧 + 平均 FPS 稳定性 |
| `CpuUsageRule` | 主线程 / 进程 CPU 占用 |
| `MemoryRule` | Java/Native 堆增长、GC 频率 |
| `ThreadRule` | 线程数量异常、主线程阻塞 |

### Skills（`assets/perfettokit/skills/*.yaml`）

`binder_block` · `cpu_intensive` · `gc_pressure` · `heavy_draw` · `heavy_layout` ·
`image_decode_main_thread` · `lock_contention` · `main_thread_io` ·
`memory_leak_gc` · `recycler_scroll`

可通过 `PerfettoKit.Config(extraSkills = listOf(...))` 注入自定义 Skill。

---

## � AI 智能诊断

> **从性能数据到修复代码，一步到位。**

PerfettoKit 可接入任意 OpenAI 兼容的 LLM 服务，将采集到的性能数据（热点方法、慢消息、帧耗时）自动构建为结构化 prompt，由 AI 输出：

| 输出项 | 说明 |
|--------|------|
| 🎯 根因（一句话） | 直接定位卡顿核心原因 |
| 📋 优化步骤 | 分步骤的具体修复指引 |
| 💻 代码示例 | 可直接参考的 Kotlin 修复代码 |

### 接入示例

```kotlin
// 云端 LLM（GPT-4o / Claude 等）
PerfettoKit.init(this, PerfettoKit.Config(
    aiProvider = OpenAICompatProvider(
        apiKey = BuildConfig.LLM_API_KEY,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini"
    )
))

// 本地 LLM（Ollama / LM Studio 等，无需 API Key）
PerfettoKit.init(this, PerfettoKit.Config(
    aiProvider = OpenAICompatProvider(
        apiKey = "ollama",
        baseUrl = "http://your-pc-ip:11434/v1",
        model = "qwen2.5-coder:7b"
    )
))
```

### AI 输出示例

```
━━━ AI 增强建议 ━━━
### 根因（一句话）
   1. 在 SampleAdapter.onBind 方法中减少对象创建和字符串拼接操作。
   2. 使用线程池处理可能的同步 I/O 操作。
━━━ 代码示例 ━━━
// 1. 减少对象创建和字符串拼接操作
class SampleAdapter : RecyclerView.Adapter<SampleAdapter.ViewHolder>() {
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sb = StringBuilder()
        sb.append("Name: ").append(data.name)
        holder.itemView.textView.text = sb.toString()
    }
}
```

### 支持的 LLM 服务

| 服务 | baseUrl | 推荐模型 |
|------|---------|----------|
| OpenAI | `https://api.openai.com/v1` | gpt-4o-mini |
| Ollama (本地) | `http://localhost:11434/v1` | qwen2.5-coder:7b |
| LM Studio (本地) | `http://localhost:1234/v1` | 任意 GGUF 模型 |
| DeepSeek | `https://api.deepseek.com/v1` | deepseek-coder |

---

## 🛠️ 构建

```bash
./gradlew :sdk:assembleRelease       # 打 SDK aar
./gradlew :sample:assembleDebug      # 打 Demo apk
./gradlew :sample:installDebug       # 安装并运行
```

---

## 📄 License

[Apache License 2.0](LICENSE)
