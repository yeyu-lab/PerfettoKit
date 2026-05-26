package io.github.perfettokit.collector

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi

/**
 * 基于 FrameMetrics API (API 24+) 的帧阶段耗时采集器。
 * 提供每帧各阶段的精确耗时: INPUT / ANIMATION / LAYOUT+MEASURE / DRAW / SYNC / COMMAND_ISSUE / GPU。
 *
 * 与 FrameCollector (Choreographer) 互补:
 *   - FrameCollector: 帧间隔 (VSync 到 VSync)
 *   - FrameMetricsCollector: 帧内各阶段的精确耗时
 */
class FrameMetricsCollector {

    private val phaseDataList = mutableListOf<FramePhaseData>()
    private var handlerThread: HandlerThread? = null
    private var listener: Any? = null  // Window.OnFrameMetricsAvailableListener (API 24+)
    private var window: Window? = null
    private var running = false
    private var frameIndex = 0

    /**
     * 启动采集。需要 Activity 以获取 Window。
     * API < 24 时不采集，静默降级。
     */
    fun start(activity: Activity?) {
        if (activity == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (running) return
        running = true
        frameIndex = 0
        startApi24(activity)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun startApi24(activity: Activity) {
        val thread = HandlerThread("PerfettoKit-FrameMetrics").also { it.start() }
        handlerThread = thread
        val handler = Handler(thread.looper)
        window = activity.window

        val metricsListener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            val copy = FrameMetrics(frameMetrics)

            val inputMs = nsToMs(copy.getMetric(FrameMetrics.INPUT_HANDLING_DURATION))
            val animationMs = nsToMs(copy.getMetric(FrameMetrics.ANIMATION_DURATION))
            val layoutMs = nsToMs(copy.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
            val drawMs = nsToMs(copy.getMetric(FrameMetrics.DRAW_DURATION))
            val syncMs = nsToMs(copy.getMetric(FrameMetrics.SYNC_DURATION))
            val commandMs = nsToMs(copy.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION))
            val totalMs = nsToMs(copy.getMetric(FrameMetrics.TOTAL_DURATION))
            val timestampMs = System.currentTimeMillis()

            synchronized(phaseDataList) {
                phaseDataList.add(
                    FramePhaseData(
                        frameIndex = frameIndex++,
                        inputMs = inputMs,
                        animationMs = animationMs,
                        layoutMs = layoutMs,
                        drawMs = drawMs,
                        syncMs = syncMs,
                        commandMs = commandMs,
                        totalMs = totalMs,
                        timestampMs = timestampMs
                    )
                )
            }
        }
        listener = metricsListener
        activity.window.addOnFrameMetricsAvailableListener(metricsListener, handler)
    }

    fun stop(): List<FramePhaseData> {
        running = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopApi24()
        }
        return synchronized(phaseDataList) { phaseDataList.toList() }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun stopApi24() {
        val w = window
        val l = listener as? Window.OnFrameMetricsAvailableListener
        if (w != null && l != null) {
            try {
                w.removeOnFrameMetricsAvailableListener(l)
            } catch (_: Exception) {
                // Window may have been destroyed
            }
        }
        handlerThread?.quitSafely()
        handlerThread = null
        listener = null
        window = null
    }

    private fun nsToMs(ns: Long): Double = ns / 1_000_000.0
}

/**
 * 单帧各阶段耗时数据。
 */
data class FramePhaseData(
    val frameIndex: Int,
    val inputMs: Double,        // 触摸事件处理
    val animationMs: Double,    // 动画回调 (ValueAnimator, ObjectAnimator)
    val layoutMs: Double,       // measure + layout
    val drawMs: Double,         // draw (Canvas)
    val syncMs: Double,         // 同步到 RenderThread (bitmap upload)
    val commandMs: Double,      // GPU 命令发射
    val totalMs: Double,        // 帧总耗时
    val timestampMs: Long       // 采集时间戳
) {
    /** 该帧中最耗时的阶段 */
    val bottleneckPhase: FramePhase
        get() {
            val phases = listOf(
                FramePhase.INPUT to inputMs,
                FramePhase.ANIMATION to animationMs,
                FramePhase.LAYOUT to layoutMs,
                FramePhase.DRAW to drawMs,
                FramePhase.SYNC to syncMs,
                FramePhase.COMMAND to commandMs
            )
            return phases.maxByOrNull { it.second }?.first ?: FramePhase.UNKNOWN
        }
}

/**
 * 帧渲染阶段枚举。
 */
enum class FramePhase(val label: String) {
    INPUT("INPUT"),
    ANIMATION("ANIMATION"),
    LAYOUT("LAYOUT"),
    DRAW("DRAW"),
    SYNC("SYNC"),
    COMMAND("GPU_COMMAND"),
    UNKNOWN("UNKNOWN")
}

/**
 * 帧阶段统计汇总 — Session 级别。
 */
data class FramePhaseStats(
    val totalFrames: Int = 0,
    val jankFrames: Int = 0,
    val phaseBreakdown: List<PhaseBottleneckEntry> = emptyList(),
    val avgInputMs: Double = 0.0,
    val avgAnimationMs: Double = 0.0,
    val avgLayoutMs: Double = 0.0,
    val avgDrawMs: Double = 0.0,
    val avgSyncMs: Double = 0.0,
    val avgCommandMs: Double = 0.0
)

/**
 * 掉帧瓶颈阶段分布条目。
 */
data class PhaseBottleneckEntry(
    val phase: FramePhase,
    val jankCount: Int,         // 该阶段作为瓶颈的掉帧数
    val percentage: Double,     // 该阶段作为瓶颈的掉帧占比
    val avgMs: Double           // 该阶段在瓶颈帧中的平均耗时
)
