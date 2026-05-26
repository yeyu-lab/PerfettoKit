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
        appendLine("你是 Android 性能优化专家。请基于以下诊断数据给出具体的优化建议和代码示例。")
        appendLine()
        appendLine("## 场景: $scene")
        appendLine("## 帧统计")
        appendLine("- 总帧数: ${frameStats.totalFrames}")
        appendLine("- 平均帧耗时: ${"%.1f".format(frameStats.avgMs)}ms")
        appendLine("- 最大帧耗时: ${"%.1f".format(frameStats.maxMs)}ms")
        appendLine("- 掉帧数: ${frameStats.jankCount}")
        appendLine()
        if (rootCauses.isNotEmpty()) {
            appendLine("## 已识别根因")
            rootCauses.forEach {
                appendLine("- [${it.type}] ${it.description}")
                appendLine("  证据: ${it.evidence}")
            }
            appendLine()
        }
        if (hotMethods.isNotEmpty()) {
            appendLine("## 热点方法")
            hotMethods.forEach { appendLine("- $it") }
            appendLine()
        }
        if (slowMethods.isNotEmpty()) {
            appendLine("## 慢方法 (插桩)")
            slowMethods.forEach { appendLine("- ${it.method}: ${"%.1f".format(it.durationMs)}ms") }
            appendLine()
        }
        if (customContext.isNotEmpty()) {
            appendLine("## 开发者补充")
            appendLine(customContext)
            appendLine()
        }
        appendLine("请给出:")
        appendLine("1. 根因解释（用一句话总结）")
        appendLine("2. 具体优化步骤（按优先级排列）")
        appendLine("3. 对应的 Kotlin 代码示例")
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
