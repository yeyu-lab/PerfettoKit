package io.github.perfettokit.ai

import io.github.perfettokit.analyzer.AnalysisResult
import io.github.perfettokit.report.DiagnosisReport

/**
 * AI Provider 接口 — 可选的云端 LLM 增强分析。
 *
 * SDK 先完成本地分析（规则引擎 + Skill 匹配），
 * 再将结构化结果发给 LLM 做自然语言增强（代码修复建议、上下文解释等）。
 *
 * 实现类只需关注 HTTP 请求，SDK 负责构造 prompt。
 */
interface AIProvider {

    /**
     * 是否可用（有 API Key、有网络等）。
     */
    fun isAvailable(): Boolean

    /**
     * 发送结构化分析数据给 LLM，获取增强建议。
     *
     * @param request 包含帧数据摘要 + 根因 + 热点方法
     * @return AI 补充的自然语言建议，null 表示调用失败
     */
    suspend fun enhance(request: AIRequest): AIResponse?
}

/**
 * 发给 LLM 的请求（结构化数据，非原始 trace）。
 */
data class AIRequest(
    val scene: String,
    val summary: String,
    val rootCauses: List<RootCauseInfo>,
    val hotMethods: List<String>,
    val slowMethods: List<SlowMethodInfo>,
    val frameStats: FrameStatsInfo,
    val customContext: String = ""  // 开发者可附加上下文
) {
    data class RootCauseInfo(
        val type: String,
        val description: String,
        val evidence: String
    )

    data class SlowMethodInfo(
        val method: String,
        val durationMs: Double
    )

    data class FrameStatsInfo(
        val totalFrames: Int,
        val avgMs: Double,
        val maxMs: Double,
        val jankCount: Int
    )

    /**
     * 构造发给 LLM 的 prompt。
     */
    fun toPrompt(): String = buildString {
        appendLine("你是 Android 性能优化专家。以下是 PerfettoKit SDK 的自动检测报告。")
        appendLine("请基于已有数据给出**针对性**的优化建议，不要给出与数据无关的通用建议。")
        appendLine()
        appendLine("## 场景: $scene")
        appendLine("## 概览: $summary")
        appendLine()
        appendLine("## 帧统计")
        appendLine("- 总帧数: ${frameStats.totalFrames}, 掉帧: ${frameStats.jankCount}")
        appendLine("- 平均帧耗时: ${"%.1f".format(frameStats.avgMs)}ms, 最慢帧: ${"%.1f".format(frameStats.maxMs)}ms")
        appendLine()
        if (rootCauses.isNotEmpty()) {
            appendLine("## 已识别根因（SDK 规则引擎输出）")
            rootCauses.forEach {
                appendLine("- [${it.type}] ${it.description}")
                appendLine("  证据: ${it.evidence}")
            }
            appendLine()
        }
        if (hotMethods.isNotEmpty()) {
            appendLine("## 主线程热点方法（栈采样 Top，占比越高说明耗时越多）")
            hotMethods.forEach { appendLine("- $it") }
            appendLine()
        }
        if (slowMethods.isNotEmpty()) {
            appendLine("## 慢方法（MethodTracer / Looper Monitor 抓到的超时方法）")
            slowMethods.forEach { appendLine("- ${it.method}: ${"%.1f".format(it.durationMs)}ms") }
            appendLine()
        }
        if (customContext.isNotEmpty()) {
            appendLine("## 系统指标")
            appendLine(customContext)
            appendLine()
        }
        appendLine("---")
        appendLine("请严格按以下格式输出（不要输出其他内容）：")
        appendLine()
        appendLine("### 根因（一句话）")
        appendLine("<一句话总结卡顿根因，引用具体方法名>")
        appendLine()
        appendLine("### 优化步骤（按优先级）")
        appendLine("1. <具体操作 — 必须包含类名/方法名>")
        appendLine("2. ...")
        appendLine()
        appendLine("### 代码示例")
        appendLine("```kotlin")
        appendLine("<针对上面第 1 步的修改代码>")
        appendLine("```")
    }
}

/**
 * LLM 返回的增强建议。
 */
data class AIResponse(
    val summary: String,          // 一句话总结
    val suggestions: List<String>, // 优化建议列表
    val codeSnippet: String? = null, // 代码示例
    val rawResponse: String = ""     // 原始 LLM 回复
)
