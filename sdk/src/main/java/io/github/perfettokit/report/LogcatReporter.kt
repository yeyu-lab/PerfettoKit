package io.github.perfettokit.report

import android.util.Log
import io.github.perfettokit.analyzer.RootCause
import io.github.perfettokit.collector.FramePhase
import io.github.perfettokit.i18n.I18n

/**
 * Logcat 输出 Reporter — 总览优先 + 卡顿元凶前置 + 详细数据后置。
 */
class LogcatReporter(
    private val tag: String = "PerfettoKit"
) : Reporter {

    override fun report(report: DiagnosisReport) {
        if (!I18n.isChinese()) {
            reportEn(report)
            return
        }

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

        // ═══════════ 主线程瓶颈 Top (统一排名，最有价值) ═══════════
        val unifiedRanking = report.unifiedMethodRanking
        if (unifiedRanking.isNotEmpty()) {
            Log.i(tag, "")
            Log.i(tag, "【主线程瓶颈 Top %d】(proportionRatio × 掉帧帧数 排名)".format(unifiedRanking.size))
            unifiedRanking.forEach { entry ->
                val slowTag = if (entry.slowHitCount > 0) "慢${entry.slowHitCount}次" else ""
                val warmTag = if (entry.warmHitCount > 0) "温${entry.warmHitCount}次" else ""
                val countDesc = listOf(slowTag, warmTag).filter { it.isNotEmpty() }.joinToString(" | ").ifEmpty { "影响" }
                val ratioDesc = "%.1fx".format(entry.proportionRatio)
                Log.e(tag, "   🎯 ${entry.displayName}")
                Log.e(tag, "      $countDesc | 影响${entry.jankFrameCount}/${entry.totalJankFrames}帧 | 比值$ratioDesc | 均%.1fms, 峰值${entry.maxDurationMs}ms [${entry.category.label}]".format(entry.avgDurationMs))
                if (entry.childMethods.isNotEmpty()) {
                    // 按基类分组展示子方法 (lambda内部类归属到基类), 按时间贡献排序
                    val parentBaseCls = entry.method.substringBefore('.').substringBefore('$')
                    // 构建 method → contribution 映射
                    val contribMap = entry.childMethods.zip(entry.childContributions).toMap()

                    val grouped = entry.childMethods.groupBy {
                        it.substringBefore('.').substringBefore('$')
                    }
                    // 按组内最大贡献度排序
                    val sortedGroups = grouped.entries
                        .sortedByDescending { (_, methods) -> methods.maxOfOrNull { contribMap[it] ?: 0f } ?: 0f }

                    // 先展示跨类调用 (不同于 parent 的类), 限制 Top 5 组
                    var groupCount = 0
                    for ((cls, methods) in sortedGroups) {
                        if (cls == parentBaseCls) continue
                        if (groupCount >= 5) break
                        // 取该组贡献最大的方法名 + 百分比
                        val sortedMethods = methods.sortedByDescending { contribMap[it] ?: 0f }
                        val display = sortedMethods.take(3).map { m ->
                            val pct = ((contribMap[m] ?: 0f) * 100).toInt()
                            val name = m.substringAfter('.')
                            if (pct > 0) "$name(${pct}%)" else name
                        }
                        Log.w(tag, "      └ $cls: ${display.joinToString(", ")}")
                        groupCount++
                    }
                    // 再展示同类子方法
                    val sameClassMethods = grouped[parentBaseCls]
                    if (sameClassMethods != null && sameClassMethods.isNotEmpty()) {
                        val sortedMethods = sameClassMethods.sortedByDescending { contribMap[it] ?: 0f }
                        val display = sortedMethods.take(3).map { m ->
                            val pct = ((contribMap[m] ?: 0f) * 100).toInt()
                            val name = m.substringAfter('.')
                            if (pct > 0) "$name(${pct}%)" else name
                        }
                        Log.w(tag, "      └ $parentBaseCls: ${display.joinToString(", ")}")
                    }
                }
                if (entry.callChain.isNotEmpty()) {
                    Log.w(tag, "      链: ${entry.callChain.joinToString(" → ")}")
                }
            }
        } else if (msgStats.topSlowMethods.isNotEmpty()) {
            // fallback: 没有统一排名时用旧的慢方法列表
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

        // 基于栈采样的掉帧耗时归因 (精简版: 只展示 pipeline 级别概况)
        val attr = msgStats.jankAttribution
        if (attr.stackBasedContributors.isNotEmpty()) {
            Log.i(tag, "")
            Log.i(tag, "【掉帧耗时归因】(基于5ms栈采样, 按时间占比)")
            // 只展示系统管线 + app 热点方法, 限制 Top 4
            attr.stackBasedContributors.take(4).forEach { c ->
                val icon = when {
                    c.isPureSystemHotspot -> "🔧"
                    c.isAppMethod -> "📱"
                    else -> "⚙️"
                }
                if (c.isPureSystemHotspot) {
                    Log.w(tag, "   $icon ${c.method} — 出现率%.1f%%, 峰值%.0fms".format(
                        c.jankFrameAppearanceRate * 100, c.maxEstimatedMs
                    ))
                } else {
                    Log.w(tag, "   $icon ${c.method} — 占比%.1f%% (%.1fx), 出现率%.1f%%".format(
                        c.jankProportion * 100, c.proportionRatio, c.jankFrameAppearanceRate * 100
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
        val breakdown = report.mainThreadStats.samplingBreakdown
        if (breakdown.totalSamples > 0) {
            val busySamples = breakdown.totalSamples - breakdown.idleSamples - breakdown.emptyStackSamples
            val busyPercent = busySamples.toDouble() / breakdown.totalSamples * 100
            val idlePercent = breakdown.idleSamples.toDouble() / breakdown.totalSamples * 100
            Log.i(tag, "   CPU: %.1f%% | 活跃率: %.0f%% | 压力: %s | 采样%d次 (📱%d ⚙️%d 🎨%d 📦%d)".format(
                mainCpu,
                report.threadStats.mainThreadRunnableRatio * 100,
                mainPressure,
                breakdown.totalSamples,
                breakdown.appMethodSamples, breakdown.systemMethodSamples,
                breakdown.renderingMethodSamples, breakdown.thirdpartyMethodSamples
            ))
        } else {
            Log.i(tag, "   CPU: %.1f%% | 活跃率: %.0f%% | 压力: %s".format(
                mainCpu,
                report.threadStats.mainThreadRunnableRatio * 100,
                mainPressure
            ))
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

        // GfxInfo 风格统计 (等价 dumpsys gfxinfo 输出)
        val gfx = report.gfxFrameStats
        if (gfx.totalFrames > 0) {
            Log.i(tag, "")
            Log.i(tag, "【帧统计】(等价 dumpsys gfxinfo, %.0fHz)".format(gfx.displayRefreshRate))
            Log.i(tag, "   Total frames: ${gfx.totalFrames} | Janky: ${gfx.jankFrames} (%.2f%%)".format(gfx.jankPercent))
            Log.i(tag, "   P50: %.0fms | P90: %.0fms | P95: %.0fms | P99: %.0fms".format(
                gfx.percentile50Ms, gfx.percentile90Ms, gfx.percentile95Ms, gfx.percentile99Ms
            ))
            Log.i(tag, "   Missed Vsync: ${gfx.missedVsync} | High input: ${gfx.highInputLatency} | Slow UI: ${gfx.slowUiThread}")
            Log.i(tag, "   Slow bitmap uploads: ${gfx.slowBitmapUploads} | Slow draw cmds: ${gfx.slowDrawCommands}")
            if (gfx.slowGpuCompletion > 0) {
                Log.i(tag, "   Slow GPU completion: ${gfx.slowGpuCompletion}")
            }
            Log.i(tag, "   Score: ${gfx.smoothnessScore}/100 | Est.FPS: %.1f | Bottleneck: ${gfx.dominantBottleneck}".format(gfx.estimatedFps))
        }

        Log.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    private fun reportEn(report: DiagnosisReport) {
        Log.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(tag, "📊 ${report.summary}")
        Log.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val msgStats = report.slowMessageStats
        val jankFrames = msgStats.jankAttribution.totalJankFrames
        val jankRate = if (report.totalFrames > 0) jankFrames.toDouble() / report.totalFrames * 100 else 0.0
        val fmStats = report.framePhaseStats
        val fmJankRate = if (fmStats.totalFrames > 0) fmStats.jankFrames.toDouble() / fmStats.totalFrames * 100 else 0.0

        Log.i(tag, "")
        Log.i(tag, "[Overview]")
        if (fmStats.totalFrames > 0) {
            Log.i(tag, "   Render jank: %d/%d frames (%.1f%%) [FrameMetrics]".format(
                fmStats.jankFrames, fmStats.totalFrames, fmJankRate
            ))
            Log.i(tag, "   Main-thread callback jank: %d/%d (%.1f%%) [Choreographer]".format(
                jankFrames, report.totalFrames, jankRate
            ))
        } else {
            Log.i(tag, "   Jank: %d/%d frames (%.1f%%)".format(jankFrames, report.totalFrames, jankRate))
        }
        Log.i(tag, "   Main thread CPU: %.1f%% | Duration: %dms".format(report.mainThreadStats.cpuPercent, report.durationMs))
        Log.i(tag, "   Slow messages: %d/%d (%.1f%%) | Blocked: %dms | Max: %dms".format(
            msgStats.totalSlowMessages,
            msgStats.totalMessageCount,
            msgStats.slowRatio * 100,
            msgStats.totalSlowDurationMs,
            msgStats.maxDurationMs
        ))

        if (report.unifiedMethodRanking.isNotEmpty()) {
            Log.i(tag, "")
            Log.i(tag, "[Main Thread Bottlenecks Top %d]".format(report.unifiedMethodRanking.size))
            report.unifiedMethodRanking.forEach { entry ->
                val slowTag = if (entry.slowHitCount > 0) "slow:${entry.slowHitCount}" else ""
                val warmTag = if (entry.warmHitCount > 0) "warm:${entry.warmHitCount}" else ""
                val countDesc = listOf(slowTag, warmTag).filter { it.isNotEmpty() }.joinToString(" | ").ifEmpty { "impact" }
                Log.e(tag, "   🎯 ${entry.displayName}")
                Log.e(tag, "      $countDesc | jank ${entry.jankFrameCount}/${entry.totalJankFrames} | ratio %.1fx | avg %.1fms, max ${entry.maxDurationMs}ms [${entry.category.name}]".format(
                    entry.proportionRatio, entry.avgDurationMs
                ))
            }
        }

        Log.i(tag, "")
        Log.i(tag, "[Metrics]")
        val coreCount = report.cpuStats.coreCount
        val trimmedAvg = report.cpuStats.trimmedAvgProcessCpuPercent
        val maxCpu = report.cpuStats.maxProcessCpuPercent
        val deviceCpuPercent = if (coreCount > 0) trimmedAvg / coreCount else 0.0
        val cpuLine = if (report.cpuStats.systemCpuAvailable) {
            "   CPU: avg %.0f%% peak %.0f%% | process/device %.1f%%/%d cores | system %.0f%%".format(
                trimmedAvg, maxCpu, deviceCpuPercent, coreCount, report.cpuStats.avgSystemCpuPercent
            )
        } else {
            "   CPU: avg %.0f%% peak %.0f%% | process/device %.1f%%/%d cores".format(
                trimmedAvg, maxCpu, deviceCpuPercent, coreCount
            )
        }
        Log.i(tag, cpuLine)
        Log.i(tag, "   Memory: Heap %.0f%% (%dMB/%dMB) | GC %d times %dms | Growth %+dKB".format(
            report.memoryStats.heapUsagePercent,
            report.memoryStats.javaHeapMaxKb / 1024,
            report.memoryStats.javaHeapMaxLimitKb / 1024,
            report.memoryStats.gcCount,
            report.memoryStats.gcTotalTimeMs,
            report.memoryStats.memoryGrowthKb
        ))
        Log.i(tag, "   Threads: %d (running %d/%d cores) | Peak %d | Growth %+d".format(
            report.threadStats.avgThreadCount,
            report.threadStats.avgRunningThreadCount,
            coreCount,
            report.threadStats.maxThreadCount,
            report.threadStats.threadCountGrowth
        ))

        if (report.issues.isNotEmpty()) {
            Log.i(tag, "")
            Log.i(tag, "[Detected Issues] (${report.issues.size})")
            report.issues.forEach { issue ->
                val icon = when (issue.severity) {
                    DiagnosisReport.Severity.HIGH -> "🔴"
                    DiagnosisReport.Severity.MEDIUM -> "🟡"
                    DiagnosisReport.Severity.LOW -> "🟢"
                }
                val issueLabel = when (issue.rule) {
                    "SlowFrame" -> "Slow frame detected"
                    "ScrollJank" -> "Scroll jank detected"
                    "CpuUsage" -> "High CPU usage"
                    "Memory" -> "Memory/GC pressure"
                    "Thread" -> "Thread scheduling issue"
                    "GpuRendering" -> "GPU/render pipeline issue"
                    "IODetector" -> "Main-thread IO detected"
                    "AllocationTracker" -> "High allocation pressure"
                    "BitmapDetector" -> "Oversized bitmap detected"
                    "NetworkCollector" -> "High network traffic"
                    "AnomalyDetector" -> "Anomaly vs baseline"
                    else -> "Issue detected"
                }
                Log.w(tag, "$icon [${issue.rule}] $issueLabel")
            }
        }

        val analysis = report.analysis
        if (analysis != null && analysis.rootCauses.isNotEmpty()) {
            Log.i(tag, "")
            Log.i(tag, "[Root Cause Analysis]")
            analysis.rootCauses.forEach { cause ->
                val icon = when (cause.confidence) {
                    RootCause.Confidence.HIGH -> "🎯"
                    RootCause.Confidence.MEDIUM -> "🔍"
                    RootCause.Confidence.LOW -> "❓"
                }
                Log.e(tag, "$icon [${cause.type}] ${cause.description}")
                Log.d(tag, "   Evidence: ${cause.evidence}")
            }
        }

        Log.i(tag, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }
}
