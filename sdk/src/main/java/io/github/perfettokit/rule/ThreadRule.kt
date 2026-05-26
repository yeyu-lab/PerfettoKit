package io.github.perfettokit.rule

import io.github.perfettokit.report.DiagnosisReport

/**
 * 线程检测规则。
 */
class ThreadRule(
    private val burstThreshold: Int = 10,
    private val starvationThreshold: Double = 0.3
) : Rule {

    override val name = "Thread"

    override fun evaluate(context: RuleContext): List<DiagnosisReport.Issue> {
        val stats = context.threadStats
        if (stats.sampleCount < 3) return emptyList()

        val issues = mutableListOf<DiagnosisReport.Issue>()

        // 线程爆发
        if (stats.isThreadBurst) {
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.MEDIUM,
                    rule = name,
                    message = "线程数爆发增长: ${stats.minThreadCount} → ${stats.maxThreadCount} (+${stats.threadCountGrowth})",
                    suggestion = "线程创建过多:\n" +
                            "1. 使用线程池 (Executors/CoroutineDispatcher) 而非 Thread()\n" +
                            "2. 检查是否有循环中创建线程\n" +
                            "3. OkHttp/Retrofit 配置合理的 maxRequests"
                )
            )
        }

        // 主线程饥饿
        if (stats.isMainThreadStarved) {
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.HIGH,
                    rule = name,
                    message = "主线程 Runnable 状态占比仅 %.0f%%，频繁被阻塞/等待"
                        .format(stats.mainThreadRunnableRatio * 100),
                    suggestion = "主线程被阻塞:\n" +
                            "1. 检查是否有主线程 synchronized 锁竞争\n" +
                            "2. 避免主线程等待子线程结果 (Future.get)\n" +
                            "3. Binder 调用是否过于频繁"
                )
            )
        }

        return issues
    }
}
