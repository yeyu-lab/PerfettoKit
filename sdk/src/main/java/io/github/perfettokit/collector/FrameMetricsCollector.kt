package io.github.perfettokit.collector

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
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
 *
 * 增强能力:
 *   - 自动检测屏幕刷新率 (60Hz/90Hz/120Hz)，动态调整掉帧阈值
 *   - API 26+ 采集 GPU_DURATION (GPU 完成耗时)
 *   - 渲染管线瓶颈模式识别 (Texture Upload / GPU Saturation / Draw Call Explosion)
 */
class FrameMetricsCollector {

    private val phaseDataList = mutableListOf<FramePhaseData>()
    private var handlerThread: HandlerThread? = null
    private var listener: Any? = null  // Window.OnFrameMetricsAvailableListener (API 24+)
    private var window: Window? = null
    private var running = false
    private var frameIndex = 0

    /** 检测到的设备刷新率 (Hz) */
    var displayRefreshRate: Float = 60f
        private set

    /** 基于刷新率的帧预算 (ms) */
    val frameBudgetMs: Double get() = 1000.0 / displayRefreshRate

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
        detectRefreshRate(activity)
        startApi24(activity)
    }

    /**
     * 检测设备实际刷新率。
     * API 30+ 用 Display.getRefreshRate(), 更低版本用 WindowManager。
     */
    private fun detectRefreshRate(activity: Activity) {
        displayRefreshRate = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display?.refreshRate ?: 60f
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.refreshRate
            }
        } catch (_: Exception) {
            60f
        }
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

            // API 26+ 采集 GPU 完成耗时 (RenderThread 等待 GPU fence)
            val gpuMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nsToMs(copy.getMetric(FrameMetrics.GPU_DURATION))
            } else {
                0.0
            }

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
                        gpuMs = gpuMs,
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
    val drawMs: Double,         // draw (Canvas recording, 对应 Systrace 中 Record View#draw())
    val syncMs: Double,         // 同步到 RenderThread (bitmap/texture upload, 对应 syncFrameState)
    val commandMs: Double,      // GPU 命令发射 (对应 issueDrawCommands, 包含 TextureOp/FillRectOp 等)
    val gpuMs: Double = 0.0,    // GPU 完成耗时 (API 26+, RenderThread 等 GPU fence, 对应 GPU completion)
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
                FramePhase.COMMAND to commandMs,
                FramePhase.GPU to gpuMs
            )
            return phases.maxByOrNull { it.second }?.first ?: FramePhase.UNKNOWN
        }

    /** RenderThread 总耗时 = SYNC + COMMAND + GPU */
    val renderThreadMs: Double get() = syncMs + commandMs + gpuMs

    /** UI Thread 总耗时 = INPUT + ANIMATION + LAYOUT + DRAW */
    val uiThreadMs: Double get() = inputMs + animationMs + layoutMs + drawMs

    /** 该帧是否为 GPU 瓶颈 (RenderThread 占比 > 60% 且超帧预算) */
    fun isGpuBound(frameBudgetMs: Double): Boolean =
        totalMs > frameBudgetMs && renderThreadMs > uiThreadMs * 1.5

    /** 该帧是否有 Texture Upload 问题 (SYNC > 帧预算的 30%) */
    fun hasTextureUploadIssue(frameBudgetMs: Double): Boolean =
        syncMs > frameBudgetMs * 0.3

    /** 该帧是否有 GPU Command 过多问题 (COMMAND > 帧预算的 50%) */
    fun hasCommandSaturation(frameBudgetMs: Double): Boolean =
        commandMs > frameBudgetMs * 0.5
}

/**
 * 帧渲染阶段枚举。
 */
enum class FramePhase(val label: String) {
    INPUT("INPUT"),
    ANIMATION("ANIMATION"),
    LAYOUT("LAYOUT"),
    DRAW("DRAW"),
    SYNC("SYNC"),               // Texture upload (syncFrameState)
    COMMAND("GPU_COMMAND"),      // Draw command issuing (TextureOp, FillRectOp, etc.)
    GPU("GPU_COMPLETION"),      // GPU fence wait (API 26+)
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
    val avgCommandMs: Double = 0.0,
    val avgGpuMs: Double = 0.0,
    val displayRefreshRate: Float = 60f,
    val frameBudgetMs: Double = 16.67,
    val renderPipelineAnalysis: RenderPipelineAnalysis = RenderPipelineAnalysis()
)

/**
 * 渲染管线深度分析 — 识别 Texture Upload / GPU Saturation / Draw Call 等问题。
 * 这是诊断你上传的 Systrace 中 TextureOp、FillRectOp、SYNC 高耗时的核心数据结构。
 */
data class RenderPipelineAnalysis(
    // ━━━ Texture Upload 分析 (SYNC phase) ━━━
    val textureUploadFrames: Int = 0,           // SYNC > 30% 帧预算的帧数
    val avgSyncInJankMs: Double = 0.0,          // 掉帧中 SYNC 阶段的平均耗时
    val maxSyncMs: Double = 0.0,                // 最大 SYNC 耗时
    val consecutiveSyncSpikeCount: Int = 0,     // 连续 SYNC 超标的帧数 (指示 Bitmap 频繁创建)

    // ━━━ GPU Command 分析 (COMMAND phase → TextureOp, FillRectOp 等) ━━━
    val gpuCommandFrames: Int = 0,              // COMMAND > 50% 帧预算的帧数
    val avgCommandInJankMs: Double = 0.0,       // 掉帧中 COMMAND 阶段的平均耗时
    val maxCommandMs: Double = 0.0,             // 最大 COMMAND 耗时
    val consecutiveCommandSpikeCount: Int = 0,  // 连续 COMMAND 超标帧数 (指示 draw call 过多)

    // ━━━ GPU 完成等待分析 (GPU phase, API 26+) ━━━
    val gpuBoundFrames: Int = 0,                // GPU 完成时间 > 帧预算的帧数
    val avgGpuInJankMs: Double = 0.0,           // 掉帧中 GPU 阶段的平均耗时
    val maxGpuMs: Double = 0.0,                 // 最大 GPU 耗时

    // ━━━ Draw Call Explosion 分析 (DRAW phase) ━━━
    val heavyDrawFrames: Int = 0,               // DRAW > 帧预算的帧数
    val avgDrawInJankMs: Double = 0.0,          // 掉帧中 DRAW 阶段的平均耗时

    // ━━━ RenderThread 整体分析 ━━━
    val renderThreadBoundFrames: Int = 0,       // RenderThread 为瓶颈的帧数
    val renderThreadBoundPercent: Double = 0.0, // RenderThread 为瓶颈的帧占比

    // ━━━ 综合诊断 ━━━
    val dominantIssue: RenderPipelineIssue = RenderPipelineIssue.NONE,
    val issueConfidence: Double = 0.0           // 0.0~1.0 诊断置信度
) {
    val hasTextureUploadIssue: Boolean get() = textureUploadFrames > 3 || consecutiveSyncSpikeCount >= 3
    val hasGpuCommandIssue: Boolean get() = gpuCommandFrames > 3 || consecutiveCommandSpikeCount >= 3
    val hasGpuBoundIssue: Boolean get() = gpuBoundFrames > 3
    val hasAnyRenderIssue: Boolean get() = hasTextureUploadIssue || hasGpuCommandIssue || hasGpuBoundIssue
}

/**
 * 渲染管线问题类型枚举。
 * 对应 Systrace/Perfetto 中的典型 GPU 卡顿模式。
 */
enum class RenderPipelineIssue(val label: String, val description: String) {
    NONE("无", "渲染正常"),
    TEXTURE_UPLOAD_CHURN(
        "纹理频繁上传",
        "SYNC 阶段反复高耗时，指示新 Bitmap 频繁创建 → GPU 纹理重建 (glGenTextures + glTexImage2D)"
    ),
    GPU_COMMAND_SATURATION(
        "GPU 命令饱和",
        "COMMAND 阶段持续高耗时，指示单帧 draw call 过多 (TextureOp/FillRectOp/RoundRectOp 爆炸)"
    ),
    GPU_BOUND(
        "GPU 执行瓶颈",
        "GPU 完成等待时间过长，指示 GPU 过载 (shader 复杂/overdraw 严重/分辨率过高)"
    ),
    DRAW_CALL_EXPLOSION(
        "Draw Call 爆炸",
        "DRAW + COMMAND 双高，指示自定义 View 产生了过多的 Canvas 绘制命令"
    ),
    COMBINED_RENDER_THREAD(
        "RenderThread 复合瓶颈",
        "SYNC + COMMAND + GPU 全高，RenderThread 全面过载"
    )
}

/**
 * 掉帧瓶颈阶段分布条目。
 */
data class PhaseBottleneckEntry(
    val phase: FramePhase,
    val jankCount: Int,         // 该阶段作为瓶颈的掉帧数
    val percentage: Double,     // 该阶段作为瓶颈的掉帧占比
    val avgMs: Double           // 该阶段在瓶颈帧中的平均耗时
)
