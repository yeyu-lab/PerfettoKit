# 🧠 PerfettoKit：AI 加持的 Android 性能检测 SDK，让卡顿无处遁形

> 不止告诉你"哪里卡"，还能给你"怎么修"的代码。

---

## 痛点：你是否也在这样定位卡顿？

作为 Android 开发者，你可能经历过这样的场景：

- QA 说"列表滑动有点卡"，但你复现不了
- Systrace 抓了 20 秒数据，看了半天不知道从哪入手
- Profiler 里一堆调用栈，到底哪个才是掉帧的元凶？
- 好不容易定位到问题，优化方案还得自己翻文档找

**PerfettoKit** 就是为了解决这些问题而生的。

---

## 一句话介绍

**PerfettoKit** 是一个面向 Android 开发者的轻量级性能检测 SDK，集成了多维数据采集、规则引擎、方法级根因定位和 AI 智能诊断，从检测到修复建议一步到位。

[![GitHub](https://img.shields.io/badge/GitHub-yeyu--lab%2FPerfettoKit-181717?logo=github)](https://github.com/yeyu-lab/PerfettoKit)
[![AI Enhanced](https://img.shields.io/badge/AI-Enhanced%20🧠-ff6b6b.svg)](https://github.com/yeyu-lab/PerfettoKit#-ai-智能诊断)
[![JitPack](https://jitpack.io/v/yeyu-lab/PerfettoKit.svg)](https://jitpack.io/#yeyu-lab/PerfettoKit)

---

## 核心亮点

### 1. 🎯 方法级根因定位，不再大海捞针

传统工具告诉你"第 42 帧掉了"，PerfettoKit 直接告诉你：

```
🎯 JankDemo.heavyCompute
   8次超时, 影响 11/28帧掉帧, 累计 264ms, 均 33.0ms, 峰值 41ms
   链: SampleAdapter.onBind → JankDemo.heavyCompute → IntArray.sort
```

**怎么做到的？**

- **5ms 周期栈采样** — 高精度捕获方法热点
- **Choreographer 慢消息抓栈** — 在掉帧的精确时刻抓取调用链
- **对比归因算法** — 对比"掉帧期间"和"正常期间"的栈差异，找出**真正变慢的方法**（而不是高频但低耗时的方法）

### 2. 🧠 AI 智能诊断：从数据到代码修复

这是 PerfettoKit 最独特的功能 — 集成 LLM 智能分析：

```
━━━ AI 增强建议 ━━━
### 根因（一句话）
   1. 在 SampleAdapter.onBind 方法中减少对象创建和字符串拼接操作。
   2. 使用线程池处理可能的同步 I/O 操作。
━━━ 代码示例 ━━━
class SampleAdapter : RecyclerView.Adapter<SampleAdapter.ViewHolder>() {
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sb = StringBuilder()
        sb.append("Name: ").append(data.name)
        holder.itemView.textView.text = sb.toString()
    }
}
```

支持任意 OpenAI 兼容服务：GPT-4o、Ollama 本地模型、DeepSeek 等。**本地推理无需联网，隐私安全。**

### 3. 📊 多维数据，一次采集全搞定

| 采集维度 | 具体指标 |
|---------|---------|
| 帧率 | FrameMetrics 各阶段耗时、掉帧率、连续掉帧检测 |
| CPU | 主线程/进程 CPU 占用率 |
| 内存 | Java/Native 堆增长、GC 频率 |
| 线程 | 线程数异常、主线程阻塞 |
| IO/网络 | 主线程 IO、网络请求统计 |
| 对象分配 | Bitmap 大图、频繁 GC |
| Looper | 慢消息统计、超时方法 |

### 4. 📚 知识库驱动的模式识别

内置 10 种常见卡顿模式（YAML Skill 库），自动匹配并给出针对性建议：

```yaml
# cpu_intensive.yaml
name: "CPU 密集导致帧卡顿"
match:
  hotMethod: "*sort*|*compute*|*calculate*|*parse*"
  percentage: ">20%"
suggestion:
  - 将计算密集操作移到 Dispatchers.Default 协程
  - 大数据集排序使用分页或流式处理
```

覆盖：`cpu_intensive` · `gc_pressure` · `main_thread_io` · `binder_block` · `heavy_layout` · `image_decode_main_thread` · `lock_contention` · `recycler_scroll` · `heavy_draw` · `memory_leak_gc`

### 5. 🔄 历史回归检测

SDK 自动记录每次会话的性能基线，当指标出现**显著劣化**时自动标记 `REGRESSION`：

```
⚠️ REGRESSION: scroll_list 掉帧率从 5% → 18%（上次: 2024-01-15）
```

在 CI/CD 中集成，第一时间发现性能回退。

---

## 30 秒接入

**Step 1** — 添加 JitPack 仓库：
```kotlin
// settings.gradle.kts
maven { url = uri("https://jitpack.io") }
```

**Step 2** — 引入依赖：
```kotlin
implementation("com.github.yeyu-lab:PerfettoKit:1.0.0")
```

**Step 3** — 开始检测（零代码模式）：
```kotlin
// 什么都不用写，SDK 通过 ContentProvider 自动初始化
// 自动检测 Activity 启动、列表滑动等场景
```

或者手动标记关键路径：
```kotlin
val session = PerfettoKit.beginSession("checkout_flow")
// ... 你的业务代码 ...
val report = session.end() // 自动输出诊断报告到 Logcat
```

---

## 启用 AI 诊断

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PerfettoKit.init(this, PerfettoKit.Config(
            aiProvider = OpenAICompatProvider(
                apiKey = "ollama",  // 本地模型无需真实 key
                baseUrl = "http://your-pc-ip:11434/v1",
                model = "qwen2.5-coder:7b"
            )
        ))
        PerfettoKit.enableAutoDetect()
    }
}
```

---

## 真实输出效果

一次完整的检测报告输出（Logcat）：

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
          PerfettoKit 性能诊断报告
  场景: auto_scroll_list | 时长: 3120ms
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

【总览】
   实际渲染: 28/187帧 (15.0%) [FrameMetrics, 用户感知]
   主线程CPU: 72.4% | 慢消息: 14条

【卡顿元凶 Top 5】
   🎯 JankDemo.heavyCompute — 影响 11帧, 累计 264ms
   🎯 JankDemo.bitmapAlloc — 影响 7帧, 累计 145ms
   🎯 JankDemo.fakeSyncIO — 影响 4帧, 累计 180ms

【Skill 命中】
   ✔ cpu_intensive — 命中 JankDemo.heavyCompute
   ✔ image_decode_main_thread — 命中 JankDemo.bitmapAlloc
   ✔ main_thread_io — 命中 JankDemo.fakeSyncIO

━━━ AI 增强建议 ━━━
   1. 将 IntArray.sort 移到后台线程
   2. Bitmap.createBitmap 改用 Glide 异步加载
   [附代码示例...]
```

---

## 技术架构

```
┌─────────────────────────────────────────────────────┐
│                    Application                       │
├─────────────────────────────────────────────────────┤
│  PerfettoKit.init()  →  beginSession()  →  end()   │
├─────────────────────────────────────────────────────┤
│              Collectors（多维采集层）                  │
│  Frame │ CPU │ Memory │ Thread │ IO │ Looper │ ...  │
├─────────────────────────────────────────────────────┤
│              Analysis（分析层）                       │
│  栈采样聚合 │ 对比归因 │ 热点方法识别                   │
├─────────────────────────────────────────────────────┤
│              Rules + Skills（规则层）                 │
│  SlowFrame │ ScrollJank │ YAML Skill 模式匹配        │
├─────────────────────────────────────────────────────┤
│              AI Enhancement（AI 层）                 │
│  OpenAI Compatible API → 根因+步骤+代码示例           │
├─────────────────────────────────────────────────────┤
│              Report（输出层）                         │
│  Logcat │ DiagnosisReport │ SessionStore             │
└─────────────────────────────────────────────────────┘
```

---

## 与同类工具对比

| 特性 | PerfettoKit | Android Profiler | Systrace | BlockCanary |
|------|:-----------:|:----------------:|:--------:|:-----------:|
| 方法级根因定位 | ✅ | ⚠️ 手动分析 | ⚠️ 手动分析 | ❌ |
| AI 修复建议 | ✅ | ❌ | ❌ | ❌ |
| 自动场景检测 | ✅ | ❌ | ❌ | ⚠️ 仅阻塞 |
| 多维数据采集 | ✅ 10维 | ✅ | ⚠️ 帧+CPU | ❌ 仅主线程 |
| 知识库模式匹配 | ✅ 10种 | ❌ | ❌ | ❌ |
| 历史回归检测 | ✅ | ❌ | ❌ | ❌ |
| 线上可用 | ✅ 轻量 | ❌ 开发工具 | ❌ 开发工具 | ✅ |
| 无需 PC 连接 | ✅ | ❌ | ❌ | ✅ |

---

## 适用场景

- **开发阶段**：手动标记关键路径，快速定位新引入的卡顿
- **QA 阶段**：自动检测覆盖所有场景，输出可读的诊断报告
- **CI/CD**：历史回归检测，PR 合入前自动卡性能门禁
- **线上灰度**：轻量采集 + 本地规则匹配，不依赖网络

---

## 开源协议

Apache License 2.0 — 商业项目可放心使用。

---

## 链接

- **GitHub**: https://github.com/yeyu-lab/PerfettoKit
- **JitPack**: https://jitpack.io/#yeyu-lab/PerfettoKit
- **技术文章 — 对比归因算法**: [contrastive-attribution.md](./contrastive-attribution.md)

---

> **一行代码接入，AI 帮你修 Bug。** 如果觉得有用，欢迎 Star ⭐

```kotlin
implementation("com.github.yeyu-lab:PerfettoKit:1.0.0")
```
