package io.github.perfettokit.rule

import io.github.perfettokit.report.DiagnosisReport

/**
 * 慢帧检测规则。
 * 检测超过阈值的帧，并给出统计信息。
 */
class SlowFrameRule(
    private val thresholdMs: Double = 16.67,
    private val severeThresholdMs: Double = 33.33
) : Rule {

    override val name = "SlowFrame"

    override fun evaluate(context: RuleContext): List<DiagnosisReport.Issue> {
        if (context.frames.isEmpty()) return emptyList()

        val issues = mutableListOf<DiagnosisReport.Issue>()
        val slowFrames = context.frames.filter { it.totalDurationMs > thresholdMs }
        val severeFrames = context.frames.filter { it.totalDurationMs > severeThresholdMs }

        if (severeFrames.isNotEmpty()) {
            val maxMs = severeFrames.maxOf { it.totalDurationMs }
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.HIGH,
                    rule = name,
                    message = "检测到 ${severeFrames.size} 帧严重卡顿 (>${severeThresholdMs}ms)，" +
                            "最慢帧: %.1fms".format(maxMs),
                    suggestion = "建议:\n" +
                            "1. 检查主线程是否有耗时操作 (IO/计算)\n" +
                            "2. 使用 Choreographer 回调验证帧率\n" +
                            "3. 检查是否有频繁 GC (对象分配过多)"
                )
            )
        } else if (slowFrames.isNotEmpty()) {
            val ratio = slowFrames.size.toDouble() / context.frames.size * 100
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.MEDIUM,
                    rule = name,
                    message = "检测到 ${slowFrames.size} 帧轻微掉帧 (>${thresholdMs}ms)，" +
                            "占比 %.1f%%".format(ratio),
                    suggestion = "建议:\n" +
                            "1. 减少布局层级，使用 ConstraintLayout\n" +
                            "2. 避免 onDraw 中创建对象\n" +
                            "3. 图片加载使用合适的分辨率"
                )
            )
        }

        return issues
    }
}
