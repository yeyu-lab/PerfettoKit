package io.github.perfettokit.rule

import io.github.perfettokit.i18n.I18n
import io.github.perfettokit.report.DiagnosisReport

/**
 * 滑动卡顿检测规则。
 * 专门针对滑动场景，检测连续掉帧和帧率稳定性。
 */
class ScrollJankRule(
    private val targetFps: Int = 60,
    private val jankThresholdMs: Double = 16.67,
    private val consecutiveJankThreshold: Int = 3
) : Rule {

    override val name = "ScrollJank"

    override fun evaluate(context: RuleContext): List<DiagnosisReport.Issue> {
        if (context.frames.size < 5) return emptyList()

        val issues = mutableListOf<DiagnosisReport.Issue>()

        // 检测连续掉帧（用户感知最明显）
        val consecutiveJanks = findConsecutiveJanks(context)
        if (consecutiveJanks > 0) {
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.HIGH,
                    rule = name,
                    message = I18n.tr(
                        "滑动过程中检测到 $consecutiveJanks 次连续掉帧 (连续 >=${consecutiveJankThreshold} 帧超时)，用户可明显感知卡顿",
                        "Detected $consecutiveJanks consecutive jank events while scrolling (>=${consecutiveJankThreshold} timed-out frames in a row), users can clearly perceive stutter"
                    ),
                    suggestion = I18n.tr(
                        "建议:\n1. RecyclerView: 检查 onBindViewHolder 耗时，避免同步加载图片\n2. 自定义 View: 减少 onDraw 中复杂计算，使用 Canvas 缓存\n3. 检查是否有滑动时触发的网络/数据库操作",
                        "Suggestions:\n1. RecyclerView: profile onBindViewHolder and avoid sync image loading.\n2. Custom View: reduce heavy work in onDraw and use Canvas caching.\n3. Check whether scrolling triggers network/database operations."
                    )
                )
            )
        }

        // 帧率稳定性分析
        val avgFps = 1000.0 / context.frames.map { it.totalDurationMs }.average()
        if (avgFps < targetFps * 0.8) {
            issues.add(
                DiagnosisReport.Issue(
                    severity = DiagnosisReport.Severity.MEDIUM,
                    rule = name,
                    message = I18n.tr(
                        "滑动平均帧率 %.1ffps，低于目标 ${targetFps}fps 的 80%%".format(avgFps),
                        "Average scrolling FPS is %.1f, below 80%% of target ${targetFps}fps".format(avgFps)
                    ),
                    suggestion = I18n.tr(
                        "建议:\n1. 使用 RecyclerView.setHasFixedSize(true)\n2. 预加载/缓存列表项: recyclerView.setItemViewCacheSize()\n3. 复杂布局考虑异步 inflate",
                        "Suggestions:\n1. Use RecyclerView.setHasFixedSize(true).\n2. Preload/cache list items: recyclerView.setItemViewCacheSize().\n3. Consider async inflate for complex layouts."
                    )
                )
            )
        }

        return issues
    }

    private fun findConsecutiveJanks(context: RuleContext): Int {
        var maxConsecutive = 0
        var current = 0
        var count = 0

        for (frame in context.frames) {
            if (frame.totalDurationMs > jankThresholdMs) {
                current++
                if (current >= consecutiveJankThreshold) {
                    count++
                }
            } else {
                maxConsecutive = maxOf(maxConsecutive, current)
                current = 0
            }
        }
        return count
    }
}
