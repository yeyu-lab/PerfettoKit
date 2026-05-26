package io.github.perfettokit.rule

import io.github.perfettokit.collector.CpuStats
import io.github.perfettokit.collector.FrameData
import io.github.perfettokit.collector.MemoryStats
import io.github.perfettokit.collector.ThreadStats
import io.github.perfettokit.report.DiagnosisReport

/**
 * 规则引擎上下文 — 包含一个 Session 采集到的所有多维度数据。
 */
data class RuleContext(
    val scene: String,
    val frames: List<FrameData>,
    val durationMs: Long,
    val cpuStats: CpuStats = CpuStats(),
    val memoryStats: MemoryStats = MemoryStats(),
    val threadStats: ThreadStats = ThreadStats()
)

/**
 * 诊断规则接口。每个规则检查采集数据并输出 0~N 个 Issue。
 */
interface Rule {
    val name: String
    fun evaluate(context: RuleContext): List<DiagnosisReport.Issue>
}
