package io.github.perfettokit.rule

import io.github.perfettokit.i18n.I18n
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
                    message = I18n.tr(
                        "线程数爆发增长: ${stats.minThreadCount} → ${stats.maxThreadCount} (+${stats.threadCountGrowth})",
                        "Thread count burst: ${stats.minThreadCount} -> ${stats.maxThreadCount} (+${stats.threadCountGrowth})"
                    ),
                    suggestion = I18n.tr(
                        "线程创建过多:\n1. 使用线程池 (Executors/CoroutineDispatcher) 而非 Thread()\n2. 检查是否有循环中创建线程\n3. OkHttp/Retrofit 配置合理的 maxRequests",
                        "Too many thread creations:\n1. Use thread pools (Executors/CoroutineDispatcher) instead of Thread().\n2. Check for thread creation inside loops.\n3. Configure reasonable OkHttp/Retrofit maxRequests."
                    )
                )
            )
        }

        // 主线程饥饿
        if (stats.isMainThreadStarved) {
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.HIGH,
                    rule = name,
                    message = I18n.tr(
                        "主线程 Runnable 状态占比仅 %.0f%%，频繁被阻塞/等待"
                            .format(stats.mainThreadRunnableRatio * 100),
                        "Main thread runnable ratio is only %.0f%%, frequently blocked/waiting"
                            .format(stats.mainThreadRunnableRatio * 100)
                    ),
                    suggestion = I18n.tr(
                        "主线程被阻塞:\n1. 检查是否有主线程 synchronized 锁竞争\n2. 避免主线程等待子线程结果 (Future.get)\n3. Binder 调用是否过于频繁",
                        "Main thread is blocked:\n1. Check synchronized lock contention on main thread.\n2. Avoid waiting for worker results on main thread (Future.get).\n3. Check whether Binder calls are too frequent."
                    )
                )
            )
        }

        return issues
    }
}
