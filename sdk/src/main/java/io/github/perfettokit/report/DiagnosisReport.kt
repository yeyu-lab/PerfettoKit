package io.github.perfettokit.report

import io.github.perfettokit.analyzer.AnalysisResult
import io.github.perfettokit.analyzer.HotMethod
import io.github.perfettokit.analyzer.RootCause
import io.github.perfettokit.collector.AllocationStats
import io.github.perfettokit.collector.BitmapStats
import io.github.perfettokit.collector.CpuStats
import io.github.perfettokit.collector.FramePhaseStats
import io.github.perfettokit.collector.IOStats
import io.github.perfettokit.collector.MemoryStats
import io.github.perfettokit.collector.NetworkStats
import io.github.perfettokit.collector.SlowMessageStats
import io.github.perfettokit.collector.ThreadStats

/**
 * 诊断报告 — 一个 Session 结束后的完整分析结果。
 *
 * 包含:
 * 1. issues: 规则引擎的多维度检测结果
 * 2. analysis: 根因分析结果（堆栈采样 + Skill 匹配）
 * 3. 各维度统计: CPU / 内存 / 线程 / 帧率 / 网络 / IO / 分配 / Bitmap
 */
data class DiagnosisReport(
    val scene: String,
    val durationMs: Long,
    val totalFrames: Int,
    val issues: List<Issue>,
    val summary: String,
    val analysis: AnalysisResult? = null,
    val cpuStats: CpuStats = CpuStats(),
    val memoryStats: MemoryStats = MemoryStats(),
    val threadStats: ThreadStats = ThreadStats(),
    val networkStats: NetworkStats = NetworkStats(),
    val ioStats: IOStats = IOStats(),
    val allocationStats: AllocationStats = AllocationStats(),
    val bitmapStats: BitmapStats = BitmapStats(),
    val mainThreadStats: MainThreadStats = MainThreadStats(),
    val jankFrameDetails: List<JankFrameDetail> = emptyList(),
    val framePhaseStats: FramePhaseStats = FramePhaseStats(),
    val slowMessageStats: SlowMessageStats = SlowMessageStats()
) {
    val hasIssues: Boolean get() = issues.isNotEmpty()
    val rootCauses: List<RootCause> get() = analysis?.rootCauses ?: emptyList()
    val hotMethods: List<HotMethod> get() = analysis?.hotMethods ?: emptyList()

    enum class Severity { LOW, MEDIUM, HIGH }

    data class Issue(
        val severity: Severity,
        val rule: String,
        val message: String,
        val suggestion: String
    )
}

/**
 * 主线程专项统计。
 */
data class MainThreadStats(
    val cpuPercent: Double = 0.0,        // 主线程 CPU 时间占比
    val busyRatio: Double = 0.0,         // 主线程繁忙比例 (R 状态)
    val avgMessageDelayMs: Double = 0.0, // 平均消息队列延迟
    val maxMessageDelayMs: Double = 0.0, // 最大消息队列延迟
    val topMethods: List<MainThreadMethod> = emptyList(),  // 主线程时间消耗 Top 方法
    val samplingBreakdown: SamplingBreakdown = SamplingBreakdown()  // 采样分布统计
) {
    val isHighPressure: Boolean get() = cpuPercent > 60
    val isCritical: Boolean get() = cpuPercent > 80
}

/**
 * 栈采样分布统计 — 让数据自己说话，不靠猜。
 */
data class SamplingBreakdown(
    val totalSamples: Int = 0,         // 总采样数
    val idleSamples: Int = 0,          // 空闲(nativePollOnce)采样数
    val appMethodSamples: Int = 0,     // 命中 app 方法的采样数
    val systemMethodSamples: Int = 0,  // 只有系统方法的采样数
    val renderingMethodSamples: Int = 0, // 渲染相关的采样数
    val thirdpartyMethodSamples: Int = 0, // 三方库的采样数
    val emptyStackSamples: Int = 0,    // 栈为空的采样数(真正的native不可见)
    val uniqueMethodCount: Int = 0     // 去重后方法总数(判断长尾程度)
)

/**
 * 主线程耗时方法 — 基于全量栈采样统计（不限于掉帧期间）。
 */
data class MainThreadMethod(
    val method: String,           // 类名.方法名
    val sampleCount: Int,         // 出现次数
    val percentage: Double,       // 占总采样百分比
    val category: String          // 分类: app/rendering/thirdparty/system
)

/**
 * 单帧掉帧详情 — 精确到每一帧在掉帧时发生了什么。
 */
data class JankFrameDetail(
    val frameIndex: Int,
    val durationMs: Double,
    val stackMethods: List<String>,    // 该帧期间的栈顶方法
    val hasIO: Boolean,                // 该帧期间是否有 IO
    val allocCountDuringFrame: Int,    // 该帧期间的对象分配数
    val cause: String                  // 推断的掉帧原因
)
