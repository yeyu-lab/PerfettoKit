package io.github.perfettokit.rule

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
                    message = "Java Heap 使用率 %.0f%% (已用 %dMB / 上限 %dMB)，接近 OOM"
                        .format(stats.heapUsagePercent, stats.javaHeapMaxKb / 1024, stats.javaHeapMaxLimitKb / 1024),
                    suggestion = "内存压力大:\n" +
                            "1. 检查 Bitmap/大数组是否及时释放\n" +
                            "2. 使用 WeakReference 持有缓存\n" +
                            "3. 列表场景检查 RecyclerView 缓存数量"
                )
            )
        }

        // 频繁 GC
        if (stats.gcCount > gcFrequentThreshold) {
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.MEDIUM,
                    rule = name,
                    message = "Session 期间触发 ${stats.gcCount} 次 GC (耗时 ${stats.gcTotalTimeMs}ms)",
                    suggestion = "GC 频繁会导致卡顿:\n" +
                            "1. 减少 onDraw/onBind 中的对象创建\n" +
                            "2. 使用对象池 (Pools.SimplePool)\n" +
                            "3. String 拼接使用 StringBuilder"
                )
            )
        }

        // 可能内存泄漏
        if (stats.memoryGrowthKb > memoryGrowthThresholdKb) {
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.MEDIUM,
                    rule = name,
                    message = "Session 期间内存增长 %dMB，可能存在泄漏".format(stats.memoryGrowthKb / 1024),
                    suggestion = "内存持续增长:\n" +
                            "1. 使用 LeakCanary 检测泄漏\n" +
                            "2. 检查 static 引用是否持有 Activity/View\n" +
                            "3. 注意 Handler/Runnable 导致的间接持有"
                )
            )
        }

        return issues
    }
}
