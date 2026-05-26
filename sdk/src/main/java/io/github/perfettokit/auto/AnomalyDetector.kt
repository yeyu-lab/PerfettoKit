package io.github.perfettokit.auto

import android.util.Log
import io.github.perfettokit.history.SessionStore
import io.github.perfettokit.history.FrameStats
import io.github.perfettokit.report.DiagnosisReport
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 异常自学习引擎。
 *
 * 原理：
 * 1. 每次 Session 结束后记录指标到 SessionStore
 * 2. 积累 N 次后计算该场景的"正常基线"（均值 + 标准差）
 * 3. 新的 Session 结果自动与基线对比
 * 4. 超出 2σ/3σ 自动告警
 *
 * 不需要训练数据，不需要 LLM。纯统计方法，跑的越久越准。
 */
class AnomalyDetector(private val store: SessionStore) {

    companion object {
        private const val TAG = "PerfettoKit.Anomaly"
        private const val MIN_SAMPLES = 5   // 至少 5 次才开始检测异常
        private const val SIGMA_WARN = 2.0  // 2σ = 告警
        private const val SIGMA_ALERT = 3.0 // 3σ = 严重异常
    }

    /**
     * 对当前 Session 结果进行异常检测。
     *
     * @return AnomalyResult 如果检测到异常；null 表示正常或数据不足
     */
    fun detect(report: DiagnosisReport, currentStats: FrameStats): AnomalyResult? {
        val history = store.getHistory(report.scene, limit = 50)
        if (history.size < MIN_SAMPLES) {
            Log.d(TAG, "Scene '${report.scene}': ${history.size} samples, need $MIN_SAMPLES to detect")
            return null
        }

        val anomalies = mutableListOf<AnomalyMetric>()

        // 帧耗时异常
        val frameAvgs = history.map { it.avgFrameMs }
        checkAnomaly("avgFrameMs", currentStats.avgMs, frameAvgs)?.let { anomalies.add(it) }

        // 掉帧率异常
        val jankRatios = history.map { it.jankRatio }
        checkAnomaly("jankRatio", currentStats.jankRatio, jankRatios)?.let { anomalies.add(it) }

        // 最大帧耗时异常
        val maxFrames = history.map { it.maxFrameMs }
        checkAnomaly("maxFrameMs", currentStats.maxMs, maxFrames)?.let { anomalies.add(it) }

        if (anomalies.isEmpty()) return null

        val maxSeverity = anomalies.maxOf { it.severity }
        return AnomalyResult(
            scene = report.scene,
            severity = maxSeverity,
            anomalies = anomalies,
            baselineSamples = history.size,
            message = buildMessage(report.scene, anomalies, maxSeverity)
        )
    }

    /**
     * 保存当前 Session 数据到基线（用于未来对比）。
     */
    fun recordBaseline(report: DiagnosisReport, stats: FrameStats) {
        store.save(report, stats)
    }

    private fun checkAnomaly(
        metric: String,
        currentValue: Double,
        historicalValues: List<Double>
    ): AnomalyMetric? {
        if (historicalValues.size < MIN_SAMPLES) return null

        val mean = historicalValues.average()
        val stdDev = standardDeviation(historicalValues, mean)

        if (stdDev < 0.001) return null  // 标准差接近0，数据完全一致，跳过

        val zScore = (currentValue - mean) / stdDev

        // 只检测"变差"的方向（值变大 = 变差）
        if (zScore <= SIGMA_WARN) return null

        val severity = when {
            zScore >= SIGMA_ALERT -> AnomalySeverity.CRITICAL
            zScore >= SIGMA_WARN -> AnomalySeverity.WARNING
            else -> return null
        }

        return AnomalyMetric(
            metric = metric,
            currentValue = currentValue,
            baselineMean = mean,
            baselineStdDev = stdDev,
            zScore = zScore,
            severity = severity,
            deviationPercent = (currentValue - mean) / mean * 100
        )
    }

    private fun standardDeviation(values: List<Double>, mean: Double): Double {
        if (values.size < 2) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(variance)
    }

    private fun buildMessage(
        scene: String,
        anomalies: List<AnomalyMetric>,
        severity: AnomalySeverity
    ): String {
        val icon = when (severity) {
            AnomalySeverity.CRITICAL -> "🚨"
            AnomalySeverity.WARNING -> "⚠️"
        }

        val details = anomalies.joinToString("; ") { m ->
            when (m.metric) {
                "avgFrameMs" -> "平均帧耗时 %.1fms (基线 %.1fms, +%.0f%%)".format(
                    m.currentValue, m.baselineMean, m.deviationPercent
                )
                "jankRatio" -> "掉帧率 %.1f%% (基线 %.1f%%)".format(
                    m.currentValue * 100, m.baselineMean * 100
                )
                "maxFrameMs" -> "最大帧耗时 %.1fms (基线 %.1fms)".format(
                    m.currentValue, m.baselineMean
                )
                else -> "${m.metric}: %.2f (基线 %.2f)".format(m.currentValue, m.baselineMean)
            }
        }

        return "$icon 场景 '$scene' 性能异常偏离基线: $details"
    }
}

data class AnomalyResult(
    val scene: String,
    val severity: AnomalySeverity,
    val anomalies: List<AnomalyMetric>,
    val baselineSamples: Int,
    val message: String
)

data class AnomalyMetric(
    val metric: String,
    val currentValue: Double,
    val baselineMean: Double,
    val baselineStdDev: Double,
    val zScore: Double,            // 偏离几个标准差
    val severity: AnomalySeverity,
    val deviationPercent: Double    // 偏离百分比
)

enum class AnomalySeverity {
    WARNING,   // 2σ 偏离
    CRITICAL   // 3σ 偏离
}
