package io.github.perfettokit.rule

import io.github.perfettokit.i18n.I18n
import io.github.perfettokit.report.DiagnosisReport

/**
 * 内存 & GC 检测规则。
 */
class MemoryRule(
    private val heapUsageHighThreshold: Double = 80.0,
    private val gcFrequentThreshold: Long = 5,
    private val memoryGrowthThresholdKb: Long = 5 * 1024  // 5MB
) : Rule {

    override val name = "Memory"

    override fun evaluate(context: RuleContext): List<DiagnosisReport.Issue> {
        val stats = context.memoryStats
        if (stats.sampleCount < 2) return emptyList()

        val issues = mutableListOf<DiagnosisReport.Issue>()

        // 堆内存压力
        if (stats.heapUsagePercent > heapUsageHighThreshold) {
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.HIGH,
                    rule = name,
                    message = I18n.tr(
                        "Java Heap 使用率 %.0f%% (已用 %dMB / 上限 %dMB)，接近 OOM"
                            .format(stats.heapUsagePercent, stats.javaHeapMaxKb / 1024, stats.javaHeapMaxLimitKb / 1024),
                        "Java heap usage is %.0f%% (used %dMB / limit %dMB), close to OOM"
                            .format(stats.heapUsagePercent, stats.javaHeapMaxKb / 1024, stats.javaHeapMaxLimitKb / 1024)
                    ),
                    suggestion = I18n.tr(
                        "内存压力大:\n1. 检查 Bitmap/大数组是否及时释放\n2. 使用 WeakReference 持有缓存\n3. 列表场景检查 RecyclerView 缓存数量",
                        "High memory pressure:\n1. Ensure bitmaps/large arrays are released in time.\n2. Use WeakReference for cache holders when appropriate.\n3. Review RecyclerView cache size in list scenarios."
                    )
                )
            )
        }

        // 频繁 GC
        if (stats.gcCount > gcFrequentThreshold) {
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.MEDIUM,
                    rule = name,
                    message = I18n.tr(
                        "Session 期间触发 ${stats.gcCount} 次 GC (耗时 ${stats.gcTotalTimeMs}ms)",
                        "${stats.gcCount} GC events occurred during session (total ${stats.gcTotalTimeMs}ms)"
                    ),
                    suggestion = I18n.tr(
                        "GC 频繁会导致卡顿:\n1. 减少 onDraw/onBind 中的对象创建\n2. 使用对象池 (Pools.SimplePool)\n3. String 拼接使用 StringBuilder",
                        "Frequent GC can cause jank:\n1. Reduce object allocations in onDraw/onBind.\n2. Use object pools (Pools.SimplePool).\n3. Use StringBuilder for string concatenation."
                    )
                )
            )
        }

        // 可能内存泄漏
        if (stats.memoryGrowthKb > memoryGrowthThresholdKb) {
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.MEDIUM,
                    rule = name,
                    message = I18n.tr(
                        "Session 期间内存增长 %dMB，可能存在泄漏".format(stats.memoryGrowthKb / 1024),
                        "Memory grew by %dMB during session, possible leak".format(stats.memoryGrowthKb / 1024)
                    ),
                    suggestion = I18n.tr(
                        "内存持续增长:\n1. 使用 LeakCanary 检测泄漏\n2. 检查 static 引用是否持有 Activity/View\n3. 注意 Handler/Runnable 导致的间接持有",
                        "Sustained memory growth detected:\n1. Use LeakCanary to detect leaks.\n2. Check static references retaining Activity/View.\n3. Watch for indirect retention via Handler/Runnable."
                    )
                )
            )
        }

        return issues
    }
}
