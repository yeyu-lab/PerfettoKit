package io.github.perfettokit.report

import android.util.Log
import io.github.perfettokit.analyzer.RootCause
import io.github.perfettokit.collector.FramePhase

/**
 * Logcat 输出 Reporter — 总览优先 + 卡顿元凶前置 + 详细数据后置。
 */
class LogcatReporter(
    private val tag: String = "PerfettoKit"
) : Reporter {

    override fun report(report: DiagnosisReport) {
        Log.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(tag, "📊 ${report.summary}")
        Log.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // ═══════════ 总览 ═══════════
        val msgStats = report.slowMessageStats
        val jankFrames = msgStats.jankAttribution.totalJankFrames
        val jankRate = if (report.totalFrames > 0) jankFrames.toDouble() / report.totalFrames * 100 else 0.0
        val mainCpu = report.mainThreadStats.cpuPercent

        // FrameMetrics 真实渲染帧（与 Android Profiler 同源）
        val fmStats = report.framePhaseStats
        val fmJankRate = if (fmStats.totalFrames > 0) fmStats.jankFrames.toDouble() / fmStats.totalFrames * 100 else 0.0

        Log.i(tag, "")
        Log.i(tag, "【总览】")
        if (fmStats.totalFrames > 0) {
            Log.i(tag, "   实际渲染: %d/%d帧 (%.1f%%) [FrameMetrics, 用户感知]".format(
                fmStats.jankFrames, fmStats.totalFrames, fmJankRate
            ))
            Log.i(tag, "   主线程回调: %d/%d (%.1f%%) [Choreographer, 含非渲染阻塞]".format(
                jankFrames, report.totalFrames, jankRate
            ))
        } else {
            Log.i(tag, "   掉帧: %d/%d帧 (%.1f%%)".format(jankFrames, report.totalFrames, jankRate))
        }
        Log.i(tag, "   主线程CPU: %.1f%% | 耗时: %dms".format(mainCpu, report.durationMs))
        Log.i(tag, "   慢消息: %d条/%d条 (%.1f%%) | 累计阻塞: %dms | 最慢: %dms".format(
            msgStats.totalSlowMessages, msgStats.totalMessageCount, msgStats.slowRatio * 100,
            msgStats.totalSlowDurationMs, msgStats.maxDurationMs
        ))

        // ═══════════ 卡顿元凶 Top (最有价值，前置) ═══════════
        if (msgStats.topSlowMethods.isNotEmpty()) {
            Log.i(tag, "")
            Log.i(tag, "【卡顿元凶 Top %d】(消息超时时抓栈确认 + 掉帧聚合)".format(
                minOf(5, msgStats.topSlowMethods.size)
            ))
            msgStats.topSlowMethods.take(5).forEach { entry ->
                val totalMs = (entry.avgDurationMs * entry.hitCount).toLong()
                Log.e(tag, "   🎯 ${entry.method}")
                Log.e(tag, "      ${entry.hitCount}次超时, 影响${entry.jankFrameCount}/${jankFrames}帧掉帧, 累计${totalMs}ms, 均%.1fms, 峰值${entry.maxDurationMs}ms [${entry.category.label}]".format(entry.avgDurationMs))
                if (entry.callChain.isNotEmpty()) {
                    Log.w(tag, "      链: ${entry.callChain.joinToString(" → ")}")
                }
            }
        }

        // 基于栈采样的掉帧耗时归因
        val attr = msgStats.jankAttribution
        if (attr.stackBasedContributors.isNotEmpty()) {
            Log.i(tag, "")
            Log.i(tag, "【掉帧耗时归因】(基于5ms栈采样, 按时间占比)")
            attr.stackBasedContributors.forEach { c ->
                val icon = when {
                    c.isPureSystemHotspot -> "🔧"
                    c.isAppMethod -> "📱"
                    else -> "⚙️"
                }
                if (c.isPureSystemHotspot) {
                    Log.w(tag, "   $icon ${c.method} — 无App代码热点, 出现率%.1f%%, 均%.1fms, 峰值%.0fms".format(
                        c.jankFrameAppearanceRate * 100, c.jankFrameAvgMs, c.maxEstimatedMs
                    ))
                } else {
                    Log.w(tag, "   $icon ${c.method} — 占比%.1f%% (正常%.1f%%, %.1fx), 出现率%.1f%%, 峰值%.0fms".format(
                        c.jankProportion * 100, c.normalProportion * 100, c.proportionRatio, c.jankFrameAppearanceRate * 100, c.maxEstimatedMs
                    ))
                }
            }
        }

        // ═══════════ 分隔线：以下为详细分析 ═══════════
        Log.i(tag, "")
        Log.i(tag, "──────────────── 详细分析 ────────────────")

        // 性能指标
        Log.i(tag, "")
        Log.i(tag, "【性能指标】")
        val coreCount = report.cpuStats.coreCount
        val trimmedAvg = report.cpuStats.trimmedAvgProcessCpuPercent
        val maxCpu = report.cpuStats.maxProcessCpuPercent
        val deviceCpuPercent = if (coreCount > 0) trimmedAvg / coreCount else 0.0
        val cpuLine = if (report.cpuStats.systemCpuAvailable) {
            "   CPU: 平均%.0f%% 峰值%.0f%% | 占整机%.1f%%/%d核 | 系统%.0f%%".format(
                trimmedAvg, maxCpu, deviceCpuPercent, coreCount, report.cpuStats.avgSystemCpuPercent
            )
        } else {
            "   CPU: 平均%.0f%% 峰值%.0f%% | 占整机%.1f%%/%d核".format(
                trimmedAvg, maxCpu, deviceCpuPercent, coreCount
            )
        }
        Log.i(tag, cpuLine)
        Log.i(tag, "   内存: Heap %.0f%% (%dMB/%dMB) | GC %d次 %dms | 增长 %+dKB".format(
            report.memoryStats.heapUsagePercent,
            report.memoryStats.javaHeapMaxKb / 1024,
            report.memoryStats.javaHeapMaxLimitKb / 1024,
            report.memoryStats.gcCount,
            report.memoryStats.gcTotalTimeMs,
            report.memoryStats.memoryGrowthKb
        ))
        Log.i(tag, "   线程: %d (活跃 %d/%d核) | 峰值 %d | 增长 %+d".format(
            report.threadStats.avgThreadCount,
            report.threadStats.avgRunningThreadCount,
            coreCount,
            report.threadStats.maxThreadCount,
            report.threadStats.threadCountGrowth
        ))

        // 主线程专项
        val mainPressure = when {
            mainCpu > 80 -> "🔴 极高"
            mainCpu > 60 -> "🟡 偏高"
            mainCpu > 40 -> "🟢 正常"
            else -> "⚪ 空闲"
        }
        Log.i(tag, "")
        Log.i(tag, "【主线程】")
        Log.i(tag, "   CPU: %.1f%% | 活跃率: %.0f%% | 压力: %s".format(
            mainCpu,
            report.threadStats.mainThreadRunnableRatio * 100,
            mainPressure
        ))

        val mainMethods = report.mainThreadStats.topMethods
        val breakdown = report.mainThreadStats.samplingBreakdown
        if (mainMethods.isNotEmpty()) {
            Log.i(tag, "   时间消耗 Top:")
            mainMethods.take(5).forEach { m ->
                val icon = when (m.category) {
                    "app" -> "📱"
                    "rendering" -> "🎨"
                    "thirdparty" -> "📦"
                    else -> "⚙️"
                }
                Log.w(tag, "     $icon ${m.method} (%.1f%%)".format(m.percentage))
            }
            if (breakdown.totalSamples > 0) {
                val busySamples = breakdown.totalSamples - breakdown.idleSamples - breakdown.emptyStackSamples
                val busyPercent = busySamples.toDouble() / breakdown.totalSamples * 100
                val idlePercent = breakdown.idleSamples.toDouble() / breakdown.totalSamples * 100
                Log.i(tag, "   采样: 共%d次 | 空闲%.0f%% | 繁忙%.0f%% (📱%d ⚙️%d 🎨%d 📦%d) | 方法%d个".format(
                    breakdown.totalSamples, idlePercent, busyPercent,
                    breakdown.appMethodSamples, breakdown.systemMethodSamples,
                    breakdown.renderingMethodSamples, breakdown.thirdpartyMethodSamples,
                    breakdown.uniqueMethodCount
                ))
            }
        }

        // 帧阶段分析
        val phaseStats = report.framePhaseStats
        if (phaseStats.totalFrames > 0 && phaseStats.phaseBreakdown.isNotEmpty()) {
            Log.i(tag, "")
            Log.i(tag, "【帧阶段】(FrameMetrics, %d帧, 掉帧%d)".format(phaseStats.totalFrames, phaseStats.jankFrames))
            Log.i(tag, "   平均: INPUT %.1f | ANIM %.1f | LAYOUT %.1f | DRAW %.1f | SYNC %.1f | GPU %.1fms".format(
                phaseStats.avgInputMs, phaseStats.avgAnimationMs, phaseStats.avgLayoutMs,
                phaseStats.avgDrawMs, phaseStats.avgSyncMs, phaseStats.avgCommandMs
            ))
            Log.i(tag, "   掉帧瓶颈:")
            phaseStats.phaseBreakdown.forEach { entry ->
                val hint = when (entry.phase) {
                    FramePhase.INPUT -> "检查 onTouchEvent/onScrollChange"
                    FramePhase.ANIMATION -> "检查动画回调阻塞"
                    FramePhase.LAYOUT -> "检查 measure/layout 复杂度"
                    FramePhase.DRAW -> "检查 onDraw 绑定操作"
                    FramePhase.SYNC -> "检查大图加载时机"
                    FramePhase.COMMAND -> "可能过度绘制"
                    else -> ""
                }
                Log.e(tag, "     %.0f%% %s (均%.1fms) → $hint".format(
                    entry.percentage, entry.phase.label, entry.avgMs
                ))
            }
        }

        // 消息耗时分析
        if (msgStats.totalSlowMessages > 0) {
            Log.i(tag, "")
            Log.i(tag, "【消息分析】(Looper Monitor)")

            // 帧级归因
            if (attr.totalJankFrames > 0) {
                Log.i(tag, "   帧级归因 (%d帧掉帧):".format(attr.totalJankFrames))
                Log.i(tag, "     单条慢消息: %d帧 | 多条累积: %d帧 (帧内均%.1f条, 正常%.1f条)".format(
                    attr.singleSlowMsgFrames, attr.multiMsgStackFrames,
                    attr.avgMsgsPerJankFrame, attr.avgMsgsPerNormalFrame
                ))
                Log.i(tag, "     平均超预算: %.1fms/帧".format(attr.avgOverbudgetMs))

                // 累积型掉帧归因
                if (attr.topMultiMsgContributors.isNotEmpty()) {
                    Log.i(tag, "   累积型归因 (%d帧, 其中%d帧无慢消息):".format(
                        attr.multiMsgStackFrames, attr.pureAccumulationFrames
                    ))
                    attr.topMultiMsgContributors.forEach { c ->
                        Log.w(tag, "     ${c.method} — 出现率%.1f%% (%d/%d帧), 均%.1fms".format(
                            c.appearanceRate * 100, c.appearanceCount, attr.multiMsgStackFrames, c.avgDurationMs
                        ))
                        if (c.callChain.isNotEmpty()) {
                            Log.d(tag, "       链: ${c.callChain.joinToString(" → ")}")
                        }
                    }
                }
            }

            // 类别分布
            if (msgStats.categoryBreakdown.isNotEmpty()) {
                val catLine = msgStats.categoryBreakdown.joinToString(" | ") { entry ->
                    "${entry.category.label} ${entry.count}次/${entry.totalDurationMs}ms"
                }
                Log.i(tag, "   类别: $catLine")
            }
        }

        // 方法插桩结果
        val analysis = report.analysis
        if (analysis != null && analysis.methodStats.isNotEmpty()) {
            Log.i(tag, "")
            Log.i(tag, "【方法插桩】(MethodTracer 精确计时)")
            analysis.methodStats.take(8).forEach { s ->
                Log.w(tag, "   ⏱ ${s.method} — ${s.count}次, 均%.1fms, p95=%.1fms, 峰值%.1fms, 超时${s.overtimeCount}次".format(
                    s.avgMs, s.p95Ms, s.maxMs
                ))
            }
        }

        // 检测结果
        if (report.issues.isNotEmpty()) {
            Log.i(tag, "")
            Log.i(tag, "【检测结果】(${report.issues.size} 项)")
            report.issues.forEach { issue ->
                val icon = when (issue.severity) {
                    DiagnosisReport.Severity.HIGH -> "🔴"
                    DiagnosisReport.Severity.MEDIUM -> "🟡"
                    DiagnosisReport.Severity.LOW -> "🟢"
                }
                Log.w(tag, "$icon [${issue.rule}] ${issue.message}")
            }
        }

        // 根因分析
        if (analysis != null && analysis.rootCauses.isNotEmpty()) {
            Log.i(tag, "")
            Log.i(tag, "【根因分析】")
            analysis.rootCauses.forEach { cause ->
                val icon = when (cause.confidence) {
                    RootCause.Confidence.HIGH -> "🎯"
                    RootCause.Confidence.MEDIUM -> "🔍"
                    RootCause.Confidence.LOW -> "❓"
                }
                Log.e(tag, "$icon [${cause.type}] ${cause.description}")
                Log.d(tag, "   证据: ${cause.evidence}")
                if (cause.callChains.isNotEmpty()) {
                    Log.w(tag, "   📍 调用链:")
                    cause.callChains.forEach { chain ->
                        Log.w(tag, "      ├ $chain")
                    }
                }
                cause.suggestion.lines().forEach { line ->
                    Log.d(tag, "   $line")
                }
            }
        }

        Log.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }
}
