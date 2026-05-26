package io.github.perfettokit.history

import io.github.perfettokit.report.DiagnosisReport

/**
 * 回归检测器 — 对比当前 Session 和历史数据，发现性能退化。
 */
class RegressionDetector(private val store: SessionStore) {

    /**
     * 分析当前结果相对历史的趋势。
     *
     * @param current 当前诊断报告
     * @param currentStats 当前帧统计
     * @return 回归分析结果，null 表示没有足够历史数据
     */
    fun detect(current: DiagnosisReport, currentStats: FrameStats): RegressionResult? {
        val history = store.getHistory(current.scene, limit = 10)
        if (history.size < 2) return null  // 至少需要 2 条历史对比

        val recentAvg = history.take(5).map { it.avgFrameMs }.average()
        val recentJankRatio = history.take(5).map { it.jankRatio }.average()

        val avgDelta = currentStats.avgMs - recentAvg
        val avgDeltaPercent = if (recentAvg > 0) avgDelta / recentAvg * 100 else 0.0

        val jankDelta = currentStats.jankRatio - recentJankRatio

        // 判断趋势
        val trend = when {
            avgDeltaPercent > 20 -> Trend.REGRESSION
            avgDeltaPercent > 10 -> Trend.SLIGHT_REGRESSION
            avgDeltaPercent < -10 -> Trend.IMPROVED
            else -> Trend.STABLE
        }

        return RegressionResult(
            trend = trend,
            currentAvgMs = currentStats.avgMs,
            historicalAvgMs = recentAvg,
            deltaPercent = avgDeltaPercent,
            currentJankRatio = currentStats.jankRatio,
            historicalJankRatio = recentJankRatio,
            historyCount = history.size,
            message = buildMessage(trend, avgDeltaPercent, currentStats, recentAvg, recentJankRatio)
        )
    }

    private fun buildMessage(
        trend: Trend,
        deltaPercent: Double,
        current: FrameStats,
        histAvg: Double,
        histJank: Double
    ): String {
        return when (trend) {
            Trend.REGRESSION ->
                "⚠️ 性能回归: 帧耗时增加 %.0f%% (%.1fms → %.1fms)，掉帧率 %.1f%% → %.1f%%"
                    .format(deltaPercent, histAvg, current.avgMs, histJank * 100, current.jankRatio * 100)
            Trend.SLIGHT_REGRESSION ->
                "📉 轻微退化: 帧耗时增加 %.0f%% (%.1fms → %.1fms)"
                    .format(deltaPercent, histAvg, current.avgMs)
            Trend.IMPROVED ->
                "📈 性能提升: 帧耗时减少 %.0f%% (%.1fms → %.1fms)"
                    .format(-deltaPercent, histAvg, current.avgMs)
            Trend.STABLE ->
                "✅ 性能稳定: 帧耗时 %.1fms (历史均值 %.1fms)"
                    .format(current.avgMs, histAvg)
        }
    }
}

data class RegressionResult(
    val trend: Trend,
    val currentAvgMs: Double,
    val historicalAvgMs: Double,
    val deltaPercent: Double,
    val currentJankRatio: Double,
    val historicalJankRatio: Double,
    val historyCount: Int,
    val message: String
)

enum class Trend {
    IMPROVED,          // 性能提升
    STABLE,            // 稳定
    SLIGHT_REGRESSION, // 轻微退化
    REGRESSION         // 明显回归
}
