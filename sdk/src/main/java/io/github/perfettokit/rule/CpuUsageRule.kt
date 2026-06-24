package io.github.perfettokit.rule

import io.github.perfettokit.i18n.I18n
import io.github.perfettokit.report.DiagnosisReport

/**
 * CPU 使用率检测规则。
 */
class CpuUsageRule(
    private val systemHighThreshold: Double = 80.0,
    private val processHighThreshold: Double = 60.0
) : Rule {

    override val name = "CpuUsage"

    override fun evaluate(context: RuleContext): List<DiagnosisReport.Issue> {
        val stats = context.cpuStats
        if (stats.sampleCount < 2) return emptyList()

        val issues = mutableListOf<DiagnosisReport.Issue>()

        if (stats.avgSystemCpuPercent > systemHighThreshold) {
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.HIGH,
                    rule = name,
                    message = I18n.tr(
                        "系统 CPU 使用率过高: 平均 %.0f%%, 峰值 %.0f%% (%d 核心)"
                            .format(stats.avgSystemCpuPercent, stats.maxSystemCpuPercent, stats.coreCount),
                        "System CPU usage is too high: avg %.0f%%, peak %.0f%% (%d cores)"
                            .format(stats.avgSystemCpuPercent, stats.maxSystemCpuPercent, stats.coreCount)
                    ),
                    suggestion = I18n.tr(
                        "系统整体负载高，可能有后台进程抢占 CPU。\n1. 检查是否有其他 App 消耗大量 CPU\n2. 考虑降低自身工作线程优先级\n3. 减少并行任务数量",
                        "High system-wide load may indicate CPU contention from background processes.\n1. Check whether other apps are consuming large CPU.\n2. Consider lowering your worker thread priorities.\n3. Reduce parallel task count."
                    )
                )
            )
        }

        if (stats.avgProcessCpuPercent > processHighThreshold) {
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.MEDIUM,
                    rule = name,
                    message = I18n.tr(
                        "本进程 CPU 占用高: 平均 %.0f%%, 峰值 %.0f%%"
                            .format(stats.avgProcessCpuPercent, stats.maxProcessCpuPercent),
                        "Process CPU usage is high: avg %.0f%%, peak %.0f%%"
                            .format(stats.avgProcessCpuPercent, stats.maxProcessCpuPercent)
                    ),
                    suggestion = I18n.tr(
                        "本进程 CPU 密集，建议:\n1. 检查是否有计算密集操作在主线程\n2. 大量计算使用 Dispatchers.Default 协程\n3. 考虑分帧处理 (分多帧完成)",
                        "This process is CPU-intensive.\n1. Check for compute-heavy work on the main thread.\n2. Run heavy computation on Dispatchers.Default.\n3. Consider frame-splitting (spread work across multiple frames)."
                    )
                )
            )
        }

        return issues
    }
}
