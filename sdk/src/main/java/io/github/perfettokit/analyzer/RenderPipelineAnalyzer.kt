package io.github.perfettokit.analyzer

import io.github.perfettokit.collector.FramePhaseData
import io.github.perfettokit.collector.RenderPipelineAnalysis
import io.github.perfettokit.collector.RenderPipelineIssue

/**
 * 渲染管线深度分析器 — 针对 RenderThread 阶段数据做模式识别。
 *
 * 核心能力:
 * 1. 检测 Texture Upload 频繁 (SYNC 阶段 spike) → 对应 Systrace 中大量 TextureOp
 * 2. 检测 GPU Command 饱和 (COMMAND 阶段 spike) → 对应 FillRectOp/RoundRectOp 过多
 * 3. 检测 GPU Bound (GPU_DURATION 过长) → 对应 GPU fence 等待
 * 4. 检测 Draw Call 爆炸 (DRAW + COMMAND 双高) → 自定义 View canvas 操作过多
 * 5. 连续帧 spike 检测 → 区分"偶发问题" vs "系统性问题"
 *
 * 使用场景:
 *   你的 com.hualai 应用在 120Hz 设备上 Systrace 显示:
 *   - SYNC 高 → 每次新 Bitmap → glGenTextures + glTexImage2D
 *   - COMMAND 高 → 185 个 GPU ops (TextureOp + FillRectOp + ShadowCircularRRectOp)
 *   - RenderThread Self=231ms → GPU 指令排队等执行
 */
class RenderPipelineAnalyzer {

    /**
     * 分析帧阶段数据，输出渲染管线诊断结果。
     *
     * @param phaseData FrameMetricsCollector 采集到的逐帧阶段耗时
     * @param frameBudgetMs 帧预算 (120Hz=8.33, 90Hz=11.11, 60Hz=16.67)
     */
    fun analyze(phaseData: List<FramePhaseData>, frameBudgetMs: Double): RenderPipelineAnalysis {
        if (phaseData.isEmpty()) return RenderPipelineAnalysis()

        val jankFrames = phaseData.filter { it.totalMs > frameBudgetMs }
        if (jankFrames.isEmpty()) return RenderPipelineAnalysis()

        // ━━━ SYNC 阶段 (Texture Upload) 分析 ━━━
        val syncThreshold = frameBudgetMs * 0.3  // SYNC > 30% 帧预算 = 有 texture upload 问题
        val textureUploadFrames = jankFrames.filter { it.syncMs > syncThreshold }
        val consecutiveSyncSpike = findMaxConsecutiveSpike(phaseData) { it.syncMs > syncThreshold }

        // ━━━ COMMAND 阶段 (GPU Draw Call) 分析 ━━━
        val commandThreshold = frameBudgetMs * 0.5  // COMMAND > 50% 帧预算 = draw call 过多
        val gpuCommandFrames = jankFrames.filter { it.commandMs > commandThreshold }
        val consecutiveCommandSpike = findMaxConsecutiveSpike(phaseData) { it.commandMs > commandThreshold }

        // ━━━ GPU 完成阶段分析 (API 26+) ━━━
        val gpuThreshold = frameBudgetMs * 0.8  // GPU > 80% 帧预算 = GPU bound
        val gpuBoundFrames = jankFrames.filter { it.gpuMs > gpuThreshold }

        // ━━━ DRAW 阶段 (Canvas Recording) 分析 ━━━
        val drawThreshold = frameBudgetMs * 0.6  // DRAW > 60% = 绘制操作过多
        val heavyDrawFrames = jankFrames.filter { it.drawMs > drawThreshold }

        // ━━━ RenderThread 整体分析 ━━━
        val renderThreadBoundFrames = jankFrames.filter { it.isGpuBound(frameBudgetMs) }

        // ━━━ 确定主导问题 ━━━
        val dominantIssue = determineDominantIssue(
            textureUploadFrames.size,
            gpuCommandFrames.size,
            gpuBoundFrames.size,
            heavyDrawFrames.size,
            consecutiveSyncSpike,
            consecutiveCommandSpike,
            jankFrames.size
        )

        val issueConfidence = calculateConfidence(dominantIssue, jankFrames.size,
            textureUploadFrames.size, gpuCommandFrames.size, gpuBoundFrames.size)

        return RenderPipelineAnalysis(
            // Texture Upload
            textureUploadFrames = textureUploadFrames.size,
            avgSyncInJankMs = textureUploadFrames.map { it.syncMs }.averageOrZero(),
            maxSyncMs = phaseData.maxOfOrNull { it.syncMs } ?: 0.0,
            consecutiveSyncSpikeCount = consecutiveSyncSpike,

            // GPU Command
            gpuCommandFrames = gpuCommandFrames.size,
            avgCommandInJankMs = gpuCommandFrames.map { it.commandMs }.averageOrZero(),
            maxCommandMs = phaseData.maxOfOrNull { it.commandMs } ?: 0.0,
            consecutiveCommandSpikeCount = consecutiveCommandSpike,

            // GPU Bound
            gpuBoundFrames = gpuBoundFrames.size,
            avgGpuInJankMs = gpuBoundFrames.map { it.gpuMs }.averageOrZero(),
            maxGpuMs = phaseData.maxOfOrNull { it.gpuMs } ?: 0.0,

            // Draw Call
            heavyDrawFrames = heavyDrawFrames.size,
            avgDrawInJankMs = heavyDrawFrames.map { it.drawMs }.averageOrZero(),

            // RenderThread 整体
            renderThreadBoundFrames = renderThreadBoundFrames.size,
            renderThreadBoundPercent = if (jankFrames.isNotEmpty())
                renderThreadBoundFrames.size.toDouble() / jankFrames.size * 100 else 0.0,

            // 综合诊断
            dominantIssue = dominantIssue,
            issueConfidence = issueConfidence
        )
    }

    /**
     * 查找连续超标帧的最大长度。
     * 连续 spike 说明问题是"系统性的"而非"偶发的"。
     *
     * 例: 连续 5 帧 SYNC 超标 → 说明每帧都在创建新 Bitmap → texture upload churn
     */
    private fun findMaxConsecutiveSpike(
        frames: List<FramePhaseData>,
        predicate: (FramePhaseData) -> Boolean
    ): Int {
        var maxConsecutive = 0
        var current = 0
        for (frame in frames) {
            if (predicate(frame)) {
                current++
                maxConsecutive = maxOf(maxConsecutive, current)
            } else {
                current = 0
            }
        }
        return maxConsecutive
    }

    /**
     * 基于各维度数据确定主导问题。
     * 优先级:
     *   1. 如果 SYNC 和 COMMAND 同时高 → COMBINED
     *   2. DRAW + COMMAND 双高 → DRAW_CALL_EXPLOSION (自定义 View 产生过多 ops)
     *   3. 单维度连续 spike → 对应类型
     *   4. 占比最高的类型
     */
    private fun determineDominantIssue(
        syncCount: Int,
        commandCount: Int,
        gpuCount: Int,
        drawCount: Int,
        consecutiveSync: Int,
        consecutiveCommand: Int,
        totalJank: Int
    ): RenderPipelineIssue {
        if (totalJank == 0) return RenderPipelineIssue.NONE

        val syncRatio = syncCount.toDouble() / totalJank
        val commandRatio = commandCount.toDouble() / totalJank
        val gpuRatio = gpuCount.toDouble() / totalJank
        val drawRatio = drawCount.toDouble() / totalJank

        // 复合瓶颈: SYNC + COMMAND 同时 > 40% 的掉帧都受影响
        if (syncRatio > 0.4 && commandRatio > 0.4) {
            return RenderPipelineIssue.COMBINED_RENDER_THREAD
        }

        // Draw Call 爆炸: DRAW + COMMAND 双高
        if (drawRatio > 0.3 && commandRatio > 0.4) {
            return RenderPipelineIssue.DRAW_CALL_EXPLOSION
        }

        // 连续 SYNC spike (≥3帧) → 明确是 texture upload churn
        if (consecutiveSync >= 3 && syncRatio > 0.3) {
            return RenderPipelineIssue.TEXTURE_UPLOAD_CHURN
        }

        // 连续 COMMAND spike (≥3帧) → 明确是 GPU command saturation
        if (consecutiveCommand >= 3 && commandRatio > 0.3) {
            return RenderPipelineIssue.GPU_COMMAND_SATURATION
        }

        // 单维度最高
        val maxRatio = maxOf(syncRatio, commandRatio, gpuRatio)
        return when {
            maxRatio < 0.2 -> RenderPipelineIssue.NONE
            maxRatio == gpuRatio -> RenderPipelineIssue.GPU_BOUND
            maxRatio == commandRatio -> RenderPipelineIssue.GPU_COMMAND_SATURATION
            maxRatio == syncRatio -> RenderPipelineIssue.TEXTURE_UPLOAD_CHURN
            else -> RenderPipelineIssue.NONE
        }
    }

    /**
     * 计算诊断置信度 (0.0 ~ 1.0)。
     * 帧数越多 + 占比越高 → 置信度越高。
     */
    private fun calculateConfidence(
        issue: RenderPipelineIssue,
        totalJank: Int,
        syncCount: Int,
        commandCount: Int,
        gpuCount: Int
    ): Double {
        if (issue == RenderPipelineIssue.NONE) return 0.0
        if (totalJank < 3) return 0.3  // 样本不足

        val affectedCount = when (issue) {
            RenderPipelineIssue.TEXTURE_UPLOAD_CHURN -> syncCount
            RenderPipelineIssue.GPU_COMMAND_SATURATION -> commandCount
            RenderPipelineIssue.GPU_BOUND -> gpuCount
            RenderPipelineIssue.DRAW_CALL_EXPLOSION -> maxOf(syncCount, commandCount)
            RenderPipelineIssue.COMBINED_RENDER_THREAD -> maxOf(syncCount, commandCount)
            RenderPipelineIssue.NONE -> 0
        }
        val ratio = affectedCount.toDouble() / totalJank
        // 基础置信度 = 占比, 加上帧数加成
        val frameBonus = (totalJank.coerceAtMost(20) / 20.0) * 0.2
        return (ratio * 0.8 + frameBonus).coerceIn(0.0, 1.0)
    }

    private fun List<Double>.averageOrZero(): Double =
        if (isEmpty()) 0.0 else average()
}
