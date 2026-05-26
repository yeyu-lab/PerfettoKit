package io.github.perfettokit.ai

import android.util.Log
import io.github.perfettokit.report.DiagnosisReport
import kotlinx.coroutines.*

/**
 * LLM 增强器 — 将本地诊断结果发给 Cloud LLM，获取更具体的代码修复建议。
 *
 * 设计原则：
 * - LLM 是增强，不是依赖。没有 LLM 照样输出完整本地诊断。
 * - 异步执行，不阻塞 session.end() 的返回。
 * - 只发结构化摘要，不发原始 trace/堆栈（隐私保护）。
 */
class LLMEnhancer(
    private val provider: AIProvider,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "PerfettoKit.AI"
    }

    /**
     * 同步增强 — 阻塞等待 LLM 返回。
     * 适合 CI/CD 自动化场景。
     */
    fun enhanceSync(report: DiagnosisReport): AIResponse? {
        if (!provider.isAvailable()) return null
        return runBlocking {
            enhance(report)
        }
    }

    /**
     * 异步增强 — 不阻塞调用者，通过回调返回结果。
     * 适合 App 开发期使用。
     */
    fun enhanceAsync(report: DiagnosisReport, callback: (AIResponse?) -> Unit) {
        if (!provider.isAvailable()) {
            callback(null)
            return
        }
        scope.launch {
            val response = enhance(report)
            withContext(Dispatchers.Main) {
                callback(response)
            }
        }
    }

    private suspend fun enhance(report: DiagnosisReport): AIResponse? {
        val request = buildRequest(report)
        return try {
            provider.enhance(request)
        } catch (e: Exception) {
            Log.w(TAG, "LLM enhance failed: ${e.message}")
            null
        }
    }

    private fun buildRequest(report: DiagnosisReport): AIRequest {
        return AIRequest(
            scene = report.scene,
            summary = report.summary,
            rootCauses = report.rootCauses.map {
                AIRequest.RootCauseInfo(
                    type = it.type.name,
                    description = it.description,
                    evidence = it.evidence
                )
            },
            hotMethods = report.hotMethods.map {
                "${it.method} (%.1f%%)".format(it.percentage)
            },
            slowMethods = (report.analysis?.slowMethods ?: emptyList()).map {
                AIRequest.SlowMethodInfo(method = it.method, durationMs = it.durationMs)
            },
            frameStats = AIRequest.FrameStatsInfo(
                totalFrames = report.totalFrames,
                avgMs = 0.0,  // 从 summary 中已有
                maxMs = 0.0,
                jankCount = 0
            ),
            customContext = buildExtraContext(report)
        )
    }

    private fun buildExtraContext(report: DiagnosisReport): String {
        return buildString {
            if (report.cpuStats.sampleCount > 0) {
                appendLine("CPU: 系统 %.0f%%, 进程 %.0f%%".format(
                    report.cpuStats.avgSystemCpuPercent,
                    report.cpuStats.avgProcessCpuPercent
                ))
            }
            if (report.memoryStats.sampleCount > 0) {
                appendLine("Memory: Heap %.0f%%, GC %d次 %dms, 增长 %dKB".format(
                    report.memoryStats.heapUsagePercent,
                    report.memoryStats.gcCount,
                    report.memoryStats.gcTotalTimeMs,
                    report.memoryStats.memoryGrowthKb
                ))
            }
            if (report.threadStats.sampleCount > 0) {
                appendLine("Threads: avg ${report.threadStats.avgThreadCount}, " +
                        "growth +${report.threadStats.threadCountGrowth}")
            }
        }
    }
}
