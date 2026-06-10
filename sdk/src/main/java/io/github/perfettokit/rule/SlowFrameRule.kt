package io.github.perfettokit.rule

import io.github.perfettokit.report.DiagnosisReport

/**
 * 慢帧检测规则。
 * 检测超过阈值的帧，并给出统计信息。
 * 支持动态帧预算 (120Hz=8.33ms, 90Hz=11.11ms, 60Hz=16.67ms)。
 */
class SlowFrameRule(
    private val thresholdMs: Double = 0.0,  // 0 = 自动使用 frameBudgetMs
    private val severeMultiplier: Double = 2.0
) : Rule {

    override val name = "SlowFrame"

    override fun evaluate(context: RuleContext): List<DiagnosisReport.Issue> {
        if (context.frames.isEmpty()) return emptyList()

        // 动态阈值: 优先使用构造函数指定值，否则使用设备实际帧预算
        val effectiveThreshold = if (thresholdMs > 0) thresholdMs else context.frameBudgetMs
        val severeThreshold = effectiveThreshold * severeMultiplier

        val issues = mutableListOf<DiagnosisReport.Issue>()
        val slowFrames = context.frames.filter { it.totalDurationMs > effectiveThreshold }
        val severeFrames = context.frames.filter { it.totalDurationMs > severeThreshold }

        if (severeFrames.isNotEmpty()) {
            val maxMs = severeFrames.maxOf { it.totalDurationMs }
            val refreshInfo = if (context.frameBudgetMs < 16.0)
                " (设备 ${(1000.0 / context.frameBudgetMs).toInt()}Hz, 帧预算 %.1fms)".format(context.frameBudgetMs)
            else ""
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.HIGH,
                    rule = name,
                    message = "检测到 ${severeFrames.size} 帧严重卡顿 (>${"%.1f".format(severeThreshold)}ms)，" +
                            "最慢帧: ${"%.1f".format(maxMs)}ms$refreshInfo",
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
                    message = "检测到 ${slowFrames.size} 帧轻微掉帧 (>${"%.1f".format(effectiveThreshold)}ms)，" +
                            "占比 ${"%.1f".format(ratio)}%",
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
