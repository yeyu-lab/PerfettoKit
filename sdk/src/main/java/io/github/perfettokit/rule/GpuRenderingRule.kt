package io.github.perfettokit.rule

import io.github.perfettokit.analyzer.RenderPipelineAnalyzer
import io.github.perfettokit.collector.RenderPipelineIssue
import io.github.perfettokit.report.DiagnosisReport

/**
 * GPU 渲染规则 — 检测渲染管线各阶段的性能问题。
 *
 * 覆盖场景:
 *   1. Texture Upload 频繁 → SYNC 阶段 spike (Bitmap 频繁创建)
 *   2. GPU Command 饱和 → COMMAND 阶段 spike (draw call 过多: TextureOp/FillRectOp)
 *   3. GPU Bound → GPU 阶段超时 (shader/overdraw/分辨率)
 *   4. Draw Call 爆炸 → DRAW + COMMAND 双高 (自定义 View 绘制复杂)
 *
 * 对应 Systrace 现象:
 *   - 大量 TextureOp → 检查 SYNC phase
 *   - 大量 FillRectOp/RoundRectOp → 检查 COMMAND phase
 *   - RenderThread Self 时间长 → 检查 GPU phase
 */
class GpuRenderingRule : Rule {

    override val name = "GpuRendering"

    private val renderPipelineAnalyzer = RenderPipelineAnalyzer()

    override fun evaluate(context: RuleContext): List<DiagnosisReport.Issue> {
        if (context.framePhaseData.isEmpty()) return emptyList()

        val analysis = renderPipelineAnalyzer.analyze(context.framePhaseData, context.frameBudgetMs)
        if (!analysis.hasAnyRenderIssue) return emptyList()

        val issues = mutableListOf<DiagnosisReport.Issue>()

        // ━━━ Texture Upload 问题 ━━━
        if (analysis.hasTextureUploadIssue) {
            val severity = if (analysis.consecutiveSyncSpikeCount >= 5 || analysis.textureUploadFrames > 10)
                DiagnosisReport.Severity.HIGH else DiagnosisReport.Severity.MEDIUM

            issues.add(DiagnosisReport.Issue(
                severity = severity,
                rule = name,
                message = "纹理上传频繁: ${analysis.textureUploadFrames} 帧 SYNC 阶段超标 " +
                        "(avg %.1fms, max %.1fms), 连续 ${analysis.consecutiveSyncSpikeCount} 帧 spike。" +
                        "对应 Systrace 中的 TextureOp + syncFrameState 耗时。".format(
                            analysis.avgSyncInJankMs, analysis.maxSyncMs),
                suggestion = "建议:\n" +
                        "1. Bitmap 复用: 固定分配 + Canvas.drawBitmap 更新像素，避免新建纹理\n" +
                        "2. Glide 开启内存缓存 (skipMemoryCache=false)\n" +
                        "3. 调用 Bitmap.prepareToDraw() 预上传纹理\n" +
                        "4. 滚动时限制每帧更新的图片数量"
            ))
        }

        // ━━━ GPU Command 饱和 ━━━
        if (analysis.hasGpuCommandIssue) {
            val severity = if (analysis.consecutiveCommandSpikeCount >= 5 || analysis.gpuCommandFrames > 10)
                DiagnosisReport.Severity.HIGH else DiagnosisReport.Severity.MEDIUM

            issues.add(DiagnosisReport.Issue(
                severity = severity,
                rule = name,
                message = "GPU 命令饱和: ${analysis.gpuCommandFrames} 帧 COMMAND 阶段超标 " +
                        "(avg %.1fms, max %.1fms), 连续 ${analysis.consecutiveCommandSpikeCount} 帧 spike。" +
                        "对应 Systrace 中大量 FillRectOp/RoundRectOp/ShadowOp。".format(
                            analysis.avgCommandInJankMs, analysis.maxCommandMs),
                suggestion = "建议:\n" +
                        "1. 自定义 View 静态部分绘制到离屏 Bitmap (缓存不变内容)\n" +
                        "2. 缩放时合并相邻小元素 (< 2px 的不独立绘制)\n" +
                        "3. 减少 CardView/MaterialCard 的 elevation (每个阴影 = 多个 ShadowOp)\n" +
                        "4. 对不可见区域做 Canvas.clipRect() 裁剪"
            ))
        }

        // ━━━ GPU Bound ━━━
        if (analysis.hasGpuBoundIssue) {
            issues.add(DiagnosisReport.Issue(
                severity = DiagnosisReport.Severity.MEDIUM,
                rule = name,
                message = "GPU 执行瓶颈: ${analysis.gpuBoundFrames} 帧 GPU 完成时间超标 " +
                        "(avg %.1fms, max %.1fms)。" +
                        "GPU 无法在帧预算 (%.1fms) 内完成渲染。".format(
                            analysis.avgGpuInJankMs, analysis.maxGpuMs, context.frameBudgetMs),
                suggestion = "建议:\n" +
                        "1. 开启 GPU overdraw 可视化检查叠加层数\n" +
                        "2. 降低 Bitmap 分辨率 (match 显示尺寸即可)\n" +
                        "3. 减少透明 View 叠加 (alpha blend GPU 开销大)\n" +
                        "4. 考虑对复杂区域开启 renderEffect 硬件层"
            ))
        }

        // ━━━ 综合诊断 summary ━━━
        if (analysis.dominantIssue != RenderPipelineIssue.NONE &&
            analysis.renderThreadBoundPercent > 50) {
            issues.add(DiagnosisReport.Issue(
                severity = DiagnosisReport.Severity.HIGH,
                rule = name,
                message = "RenderThread 为主要瓶颈: %.0f%% 的掉帧是 RenderThread 主导 (非 UI Thread)。" +
                        "主导问题: ${analysis.dominantIssue.label} — ${analysis.dominantIssue.description}".format(
                            analysis.renderThreadBoundPercent),
                suggestion = "RenderThread 过载意味着 UI Thread 优化 (布局/逻辑) 对帧率改善有限，" +
                        "需要重点优化渲染管线:\n" +
                        "• 减少纹理上传 → Bitmap 复用方案\n" +
                        "• 减少 GPU 命令 → 离屏缓存 + 元素合并\n" +
                        "• 减少 GPU 负载 → 降低 overdraw + 合理分辨率"
            ))
        }

        return issues
    }
}
