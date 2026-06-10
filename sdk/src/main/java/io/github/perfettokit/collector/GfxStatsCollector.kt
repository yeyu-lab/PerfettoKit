package io.github.perfettokit.collector

import android.util.Log

/**
 * GfxInfo 风格帧渲染统计 — 输出与 `adb shell dumpsys gfxinfo` 等价的百分位统计。
 *
 * dumpsys gfxinfo 输出示例:
 * ```
 * Total frames rendered: 82189
 * Janky frames: 35335 (42.99%)
 * 50th percentile: 5ms
 * 90th percentile: 34ms
 * 95th percentile: 150ms
 * 99th percentile: 816ms
 * Number Missed Vsync: 4
 * Number High input latency: 6
 * Number Slow UI thread: 17
 * Number Slow bitmap uploads: 1
 * Number Slow issue draw commands: 4
 * ```
 *
 * 本类直接从 FrameMetrics 数据计算，不依赖 adb，可在运行时实时获取。
 * 优势:
 *   - 不需要 adb 连接
 *   - 可精确到每个 Scene/Session
 *   - 自动适配刷新率 (120Hz/90Hz/60Hz)
 *   - 增加 RenderThread 分阶段归因 (dumpsys gfxinfo 不提供)
 */
class GfxStatsCollector {

    companion object {
        private const val TAG = "PerfettoKit"
    }

    /**
     * 从 FrameMetrics 阶段数据计算 gfxinfo 风格统计。
     *
     * @param phaseData FrameMetricsCollector 采集的逐帧数据
     * @param frameBudgetMs 帧预算 (自动适配刷新率)
     * @param displayRefreshRate 实际刷新率
     */
    fun compute(
        phaseData: List<FramePhaseData>,
        frameBudgetMs: Double = 16.67,
        displayRefreshRate: Float = 60f
    ): GfxFrameStats {
        if (phaseData.isEmpty()) return GfxFrameStats(frameBudgetMs = frameBudgetMs, displayRefreshRate = displayRefreshRate)

        val totalFrames = phaseData.size
        val durations = phaseData.map { it.totalMs }.sorted()
        val jankFrames = durations.count { it > frameBudgetMs }
        val jankPercent = jankFrames.toDouble() / totalFrames * 100

        // 百分位计算
        val p50 = percentile(durations, 50)
        val p90 = percentile(durations, 90)
        val p95 = percentile(durations, 95)
        val p99 = percentile(durations, 99)

        // 分类统计 (对齐 dumpsys gfxinfo 分类)
        var missedVsync = 0          // INPUT + ANIMATION > 帧预算 (即 UI Thread 错过 VSync deadline)
        var highInputLatency = 0     // INPUT > 帧预算的 50%
        var slowUiThread = 0         // LAYOUT + DRAW > 帧预算
        var slowBitmapUploads = 0    // SYNC > 帧预算的 25% (texture upload)
        var slowDrawCommands = 0     // COMMAND > 帧预算的 25% (GPU draw call)
        var frameDeadlineMissed = 0  // totalMs > 帧预算 (最终 deadline)

        for (frame in phaseData) {
            if (frame.totalMs > frameBudgetMs) frameDeadlineMissed++
            if (frame.inputMs + frame.animationMs > frameBudgetMs) missedVsync++
            if (frame.inputMs > frameBudgetMs * 0.5) highInputLatency++
            if (frame.layoutMs + frame.drawMs > frameBudgetMs) slowUiThread++
            if (frame.syncMs > frameBudgetMs * 0.25) slowBitmapUploads++
            if (frame.commandMs > frameBudgetMs * 0.25) slowDrawCommands++
        }

        // 额外统计: GPU 阶段 (dumpsys gfxinfo 没有，我们增强)
        var slowGpuCompletion = 0
        for (frame in phaseData) {
            if (frame.gpuMs > frameBudgetMs * 0.5) slowGpuCompletion++
        }

        return GfxFrameStats(
            totalFrames = totalFrames,
            jankFrames = jankFrames,
            jankPercent = jankPercent,
            percentile50Ms = p50,
            percentile90Ms = p90,
            percentile95Ms = p95,
            percentile99Ms = p99,
            missedVsync = missedVsync,
            highInputLatency = highInputLatency,
            slowUiThread = slowUiThread,
            slowBitmapUploads = slowBitmapUploads,
            slowDrawCommands = slowDrawCommands,
            frameDeadlineMissed = frameDeadlineMissed,
            slowGpuCompletion = slowGpuCompletion,
            frameBudgetMs = frameBudgetMs,
            displayRefreshRate = displayRefreshRate
        )
    }

    /**
     * 打印到 Logcat，格式对齐 `dumpsys gfxinfo`。
     */
    fun printToLogcat(stats: GfxFrameStats, scene: String = "") {
        val header = if (scene.isNotEmpty()) "[$scene] " else ""
        Log.i(TAG, "")
        Log.i(TAG, "═══════════════ ${header}Frame Stats ═══════════════")
        Log.i(TAG, "Display: %.0fHz (budget: %.2fms)".format(stats.displayRefreshRate, stats.frameBudgetMs))
        Log.i(TAG, "Total frames rendered: ${stats.totalFrames}")
        Log.i(TAG, "Janky frames: ${stats.jankFrames} (%.2f%%)".format(stats.jankPercent))
        Log.i(TAG, "50th percentile: %.0fms".format(stats.percentile50Ms))
        Log.i(TAG, "90th percentile: %.0fms".format(stats.percentile90Ms))
        Log.i(TAG, "95th percentile: %.0fms".format(stats.percentile95Ms))
        Log.i(TAG, "99th percentile: %.0fms".format(stats.percentile99Ms))
        Log.i(TAG, "Number Missed Vsync: ${stats.missedVsync}")
        Log.i(TAG, "Number High input latency: ${stats.highInputLatency}")
        Log.i(TAG, "Number Slow UI thread: ${stats.slowUiThread}")
        Log.i(TAG, "Number Slow bitmap uploads: ${stats.slowBitmapUploads}")
        Log.i(TAG, "Number Slow issue draw commands: ${stats.slowDrawCommands}")
        Log.i(TAG, "Number Frame deadline missed: ${stats.frameDeadlineMissed}")
        if (stats.slowGpuCompletion > 0) {
            Log.i(TAG, "Number Slow GPU completion: ${stats.slowGpuCompletion}")
        }
        Log.i(TAG, "═══════════════════════════════════════════════════")
    }

    private fun percentile(sorted: List<Double>, p: Int): Double {
        if (sorted.isEmpty()) return 0.0
        val index = (sorted.size * p / 100.0).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}

/**
 * 帧渲染统计 — 等价于 `dumpsys gfxinfo` 的输出，外加 GPU 增强。
 */
data class GfxFrameStats(
    val totalFrames: Int = 0,
    val jankFrames: Int = 0,
    val jankPercent: Double = 0.0,

    // 百分位 (越低越好)
    val percentile50Ms: Double = 0.0,
    val percentile90Ms: Double = 0.0,
    val percentile95Ms: Double = 0.0,
    val percentile99Ms: Double = 0.0,

    // 分类计数 (对齐 dumpsys gfxinfo)
    val missedVsync: Int = 0,            // UI Thread 没在 VSync 前完成
    val highInputLatency: Int = 0,        // 触摸事件处理慢
    val slowUiThread: Int = 0,            // measure + layout + draw 慢
    val slowBitmapUploads: Int = 0,       // 纹理上传慢 (SYNC)
    val slowDrawCommands: Int = 0,        // GPU 命令发射慢 (COMMAND)
    val frameDeadlineMissed: Int = 0,     // 总帧超时

    // 增强 (dumpsys gfxinfo 没有)
    val slowGpuCompletion: Int = 0,       // GPU 执行完成慢 (API 26+)

    // 设备信息
    val frameBudgetMs: Double = 16.67,
    val displayRefreshRate: Float = 60f
) {
    /** FPS 估算 (基于 P50) */
    val estimatedFps: Double get() = if (percentile50Ms > 0) (1000.0 / percentile50Ms).coerceAtMost(displayRefreshRate.toDouble()) else displayRefreshRate.toDouble()

    /** 流畅度评分 (0~100, 越高越好) */
    val smoothnessScore: Int get() {
        if (totalFrames == 0) return 100
        // 基于 jank 比例 + P95
        val jankPenalty = (jankPercent * 0.8).coerceAtMost(60.0)
        val p95Penalty = ((percentile95Ms - frameBudgetMs).coerceAtLeast(0.0) / frameBudgetMs * 10).coerceAtMost(30.0)
        return (100 - jankPenalty - p95Penalty).toInt().coerceIn(0, 100)
    }

    /** 主要瓶颈描述 */
    val dominantBottleneck: String get() {
        val max = maxOf(slowUiThread, slowBitmapUploads, slowDrawCommands, slowGpuCompletion)
        return when {
            max == 0 -> "None"
            max == slowBitmapUploads -> "Bitmap Uploads (Texture)"
            max == slowDrawCommands -> "Draw Commands (GPU Ops)"
            max == slowGpuCompletion -> "GPU Completion"
            max == slowUiThread -> "UI Thread (Layout/Draw)"
            else -> "Unknown"
        }
    }

    /** 格式化输出 (等价 dumpsys gfxinfo 格式) */
    override fun toString(): String = buildString {
        appendLine("Total frames rendered: $totalFrames")
        appendLine("Janky frames: $jankFrames (%.2f%%)".format(jankPercent))
        appendLine("50th percentile: %.0fms".format(percentile50Ms))
        appendLine("90th percentile: %.0fms".format(percentile90Ms))
        appendLine("95th percentile: %.0fms".format(percentile95Ms))
        appendLine("99th percentile: %.0fms".format(percentile99Ms))
        appendLine("Number Missed Vsync: $missedVsync")
        appendLine("Number High input latency: $highInputLatency")
        appendLine("Number Slow UI thread: $slowUiThread")
        appendLine("Number Slow bitmap uploads: $slowBitmapUploads")
        appendLine("Number Slow issue draw commands: $slowDrawCommands")
        appendLine("Number Frame deadline missed: $frameDeadlineMissed")
        if (slowGpuCompletion > 0) {
            appendLine("Number Slow GPU completion: $slowGpuCompletion")
        }
        appendLine("---")
        appendLine("Display: %.0fHz | Budget: %.2fms | Est.FPS: %.1f | Score: %d/100".format(
            displayRefreshRate, frameBudgetMs, estimatedFps, smoothnessScore
        ))
        appendLine("Dominant bottleneck: $dominantBottleneck")
    }
}
