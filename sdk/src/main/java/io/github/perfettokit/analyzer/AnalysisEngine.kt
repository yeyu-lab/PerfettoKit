package io.github.perfettokit.analyzer

import io.github.perfettokit.collector.AllocSample
import io.github.perfettokit.collector.AllocationStats
import io.github.perfettokit.collector.FrameData
import io.github.perfettokit.collector.IOEvent
import io.github.perfettokit.collector.IOStats
import io.github.perfettokit.collector.IOType
import io.github.perfettokit.collector.MemoryStats
import io.github.perfettokit.collector.NetworkStats
import io.github.perfettokit.collector.ThreadStats
import io.github.perfettokit.skill.Skill
import io.github.perfettokit.skill.SkillMatcher
import kotlin.math.abs

/**
 * 分析引擎 — 将帧数据、堆栈采样、方法耗时三维数据关联，
 * 输出根因分析结果（不只是"帧慢了"，而是"为什么慢"）。
 *
 * 双引擎模式:
 * 1. YAML Skill 匹配（可扩展，用户可自定义规则）
 * 2. 内置模式识别（兜底，覆盖 Skill 未定义的场景）
 */
class AnalysisEngine {

    private val skillMatcher = SkillMatcher()

    /**
     * 对一个 Session 的数据进行根因分析。
     *
     * @param skills 已加载的 YAML Skills（为空则只用内置规则）
     */
    fun analyze(
        frames: List<FrameData>,
        stackSamples: List<StackSampler.StackSample>,
        methodRecords: List<MethodTracker.MethodRecord>,
        appPackagePrefix: String = "",
        scene: String = "",
        skills: List<Skill> = emptyList(),
        ioStats: IOStats = IOStats(),
        allocationStats: AllocationStats = AllocationStats(),
        memoryStats: MemoryStats = MemoryStats(),
        threadStats: ThreadStats = ThreadStats(),
        networkStats: NetworkStats = NetworkStats(),
        ioEvents: List<IOEvent> = emptyList(),
        allocSamples: List<AllocSample> = emptyList()
    ): AnalysisResult {
        val slowFrames = frames.filter { it.totalDurationMs > 16.67 }
        if (slowFrames.isEmpty()) {
            return AnalysisResult(rootCauses = emptyList(), hotMethods = emptyList())
        }

        // 1. 找到慢帧时间段内的堆栈采样，统计热点方法
        val hotMethods = findHotMethods(slowFrames, stackSamples, appPackagePrefix)

        // 2. 结合方法插桩数据，定位具体耗时方法
        val slowMethods = methodRecords
            .filter { it.durationMs > 8.0 }  // 超过半帧的方法
            .sortedByDescending { it.durationMs }

        // 2b. 方法统计聚合
        val methodStats = methodRecords.groupBy { it.method }.map { (method, records) ->
            val durations = records.map { it.durationMs }.sorted()
            MethodAggregateStats(
                method = method,
                count = durations.size,
                avgMs = durations.average(),
                maxMs = durations.last(),
                p95Ms = durations[(durations.size * 0.95).toInt().coerceAtMost(durations.size - 1)],
                overtimeCount = durations.count { it >= 8.0 }
            )
        }.sortedByDescending { it.maxMs }

        // 3. YAML Skill 匹配（优先）
        val skillCauses = if (skills.isNotEmpty()) {
            skillMatcher.match(skills, scene, hotMethods, frames)
        } else {
            emptyList()
        }

        // 4. 内置模式识别（综合堆栈 + 采集器信号 + 时间戳关联）
        val builtinCauses = deduceRootCauses(
            hotMethods, slowMethods, slowFrames,
            ioStats, allocationStats, memoryStats, threadStats,
            ioEvents, allocSamples, stackSamples, appPackagePrefix
        )

        // 合并：Skill 结果优先，内置结果去重后补充
        val skillTypes = skillCauses.map { it.type }.toSet()
        val combinedCauses = skillCauses + builtinCauses.filter { it.type !in skillTypes }

        return AnalysisResult(
            rootCauses = combinedCauses.sortedByDescending { it.confidence.ordinal },
            hotMethods = hotMethods,
            slowMethods = slowMethods,
            methodStats = methodStats,
            slowFrameCount = slowFrames.size,
            totalFrameCount = frames.size
        )
    }

    /**
     * 从堆栈采样中提取热点方法（出现频率最高 = 耗时最久）。
     */
    private fun findHotMethods(
        slowFrames: List<FrameData>,
        allSamples: List<StackSampler.StackSample>,
        appPackagePrefix: String
    ): List<HotMethod> {
        // 找出慢帧时间范围
        val slowRanges = slowFrames.map { frame ->
            val endMs = frame.timestampMs
            val startMs = endMs - frame.totalDurationMs.toLong()
            startMs..endMs
        }

        // 统计慢帧期间的堆栈采样中各方法出现次数
        val methodCount = mutableMapOf<String, Int>()
        val totalSamplesInSlowFrames: Int

        val relevantSamples = allSamples.filter { sample ->
            slowRanges.any { range -> sample.timestampMs in range }
        }
        totalSamplesInSlowFrames = relevantSamples.size

        for (sample in relevantSamples) {
            val appFrames = sample.topAppFrames(appPackagePrefix)
            // 取栈顶 3 层 app 方法
            appFrames.take(3).forEach { frame ->
                val key = "${frame.className}.${frame.methodName}"
                methodCount[key] = (methodCount[key] ?: 0) + 1
            }
        }

        return methodCount.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (method, count) ->
                HotMethod(
                    method = method,
                    sampleCount = count,
                    percentage = if (totalSamplesInSlowFrames > 0)
                        count.toDouble() / totalSamplesInSlowFrames * 100 else 0.0
                )
            }
    }

    /**
     * 综合分析，推断根因。
     * 数据来源: 堆栈采样热点方法 + 方法插桩 + IO检测 + 分配追踪 + 内存/线程统计
     */
    private fun deduceRootCauses(
        hotMethods: List<HotMethod>,
        slowMethods: List<MethodTracker.MethodRecord>,
        slowFrames: List<FrameData>,
        ioStats: IOStats = IOStats(),
        allocationStats: AllocationStats = AllocationStats(),
        memoryStats: MemoryStats = MemoryStats(),
        threadStats: ThreadStats = ThreadStats(),
        ioEvents: List<IOEvent> = emptyList(),
        allocSamples: List<AllocSample> = emptyList(),
        stackSamples: List<StackSampler.StackSample> = emptyList(),
        appPackagePrefix: String = ""
    ): List<RootCause> {
        val causes = mutableListOf<RootCause>()

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 基于采集器信号的根因（高可信度，有确切数据）
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        // IO 阻塞主线程（IODetector 有确切证据 + 完整调用链）
        if (ioStats.totalViolations > 0) {
            val detail = buildString {
                if (ioStats.diskReadCount > 0) append("${ioStats.diskReadCount}次磁盘读 ")
                if (ioStats.diskWriteCount > 0) append("${ioStats.diskWriteCount}次磁盘写 ")
                if (ioStats.networkOnMainCount > 0) append("${ioStats.networkOnMainCount}次网络 ")
            }

            // 从原始 IOEvent 提取完整业务调用链
            val callChains = extractIOCallChains(ioEvents, appPackagePrefix)

            causes.add(RootCause(
                type = RootCauseType.MAIN_THREAD_IO,
                confidence = RootCause.Confidence.HIGH,
                description = "主线程存在 IO 阻塞操作: $detail",
                evidence = "StrictMode 检测到 ${ioStats.totalViolations} 次违规",
                suggestion = "1. 将磁盘读写移到 Dispatchers.IO 协程\n" +
                        "2. SharedPreferences → DataStore (异步)\n" +
                        "3. 数据库查询使用 Room + Flow 异步接口",
                callChains = callChains
            ))
        }

        // 高频对象分配 → GC 压力（关联分配峰值时刻的栈采样）
        if (allocationStats.isHighPressure) {
            val allocCallChains = correlateAllocSpikesWithStacks(
                allocSamples, stackSamples, appPackagePrefix
            )
            causes.add(RootCause(
                type = RootCauseType.GC_PRESSURE,
                confidence = RootCause.Confidence.HIGH,
                description = "高频对象分配导致 GC 压力: 峰值 ${allocationStats.peakAllocPerSec} 次/秒",
                evidence = "Session 内共分配 ${allocationStats.totalAllocCount} 个对象, ${allocationStats.totalAllocKB}KB",
                suggestion = "1. 检查 onDraw()/onBindViewHolder() 中是否 new 对象\n" +
                        "2. String 拼接改用 StringBuilder\n" +
                        "3. 使用对象池 (Pools.SimplePool) 复用临时对象\n" +
                        "4. 避免 autoboxing (Int → Integer)",
                callChains = allocCallChains
            ))
        }

        // 内存泄漏/GC 频繁
        if (memoryStats.gcCount > 3 || memoryStats.memoryGrowthKb > 5000) {
            if (causes.none { it.type == RootCauseType.GC_PRESSURE }) {
                causes.add(RootCause(
                    type = RootCauseType.GC_PRESSURE,
                    confidence = if (memoryStats.gcCount > 5) RootCause.Confidence.HIGH else RootCause.Confidence.MEDIUM,
                    description = "GC 频繁 (${memoryStats.gcCount}次, 耗时${memoryStats.gcTotalTimeMs}ms) 且内存增长 ${memoryStats.memoryGrowthKb}KB",
                    evidence = "Heap 使用率 ${"%.0f".format(memoryStats.heapUsagePercent)}%",
                    suggestion = "1. 检查是否有内存泄漏 (LeakCanary)\n" +
                            "2. 大对象及时释放 (Bitmap.recycle)\n" +
                            "3. 减少临时对象的创建频率"
                ))
            }
        }

        // 线程暴增
        if (threadStats.threadCountGrowth > 10) {
            causes.add(RootCause(
                type = RootCauseType.THREAD_EXPLOSION,
                confidence = RootCause.Confidence.MEDIUM,
                description = "线程数暴增 +${threadStats.threadCountGrowth}，可能导致 CPU 调度压力",
                evidence = "线程从 ${threadStats.avgThreadCount - threadStats.threadCountGrowth} 增长到 ${threadStats.avgThreadCount + threadStats.threadCountGrowth}",
                suggestion = "1. 使用线程池 (Executors) 而非裸 Thread()\n" +
                        "2. 协程替代手动线程管理\n" +
                        "3. 检查第三方 SDK 是否频繁创建线程"
            ))
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 基于堆栈采样的模式识别（需要采样命中）
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        // 模式识别：GC 导致卡顿（堆栈证据）
        val gcMethods = hotMethods.filter {
            it.method.contains("GC", ignoreCase = true) ||
            it.method.contains("finalize", ignoreCase = true)
        }
        if (gcMethods.isNotEmpty() && causes.none { it.type == RootCauseType.GC_PRESSURE }) {
            causes.add(
                RootCause(
                    type = RootCauseType.GC_PRESSURE,
                    confidence = RootCause.Confidence.HIGH,
                    description = "主线程频繁 GC，导致 Stop-The-World 卡顿",
                    evidence = "堆栈采样中 GC 相关方法占比 " +
                            "%.1f%%".format(gcMethods.sumOf { it.percentage }),
                    suggestion = "1. 减少 onDraw/onBindViewHolder 中的对象创建\n" +
                            "2. 使用对象池复用临时对象\n" +
                            "3. 检查 Bitmap 是否及时回收"
                )
            )
        }

        // 模式识别：IO 阻塞主线程（堆栈证据）
        val ioMethods = hotMethods.filter {
            it.method.contains("read", ignoreCase = true) ||
            it.method.contains("write", ignoreCase = true) ||
            it.method.contains("query", ignoreCase = true) ||
            it.method.contains("openConnection", ignoreCase = true)
        }
        if (ioMethods.isNotEmpty() && causes.none { it.type == RootCauseType.MAIN_THREAD_IO }) {
            causes.add(
                RootCause(
                    type = RootCauseType.MAIN_THREAD_IO,
                    confidence = RootCause.Confidence.HIGH,
                    description = "主线程存在 IO 操作（文件/数据库/网络），阻塞渲染",
                    evidence = "慢帧期间检测到 IO 方法: ${ioMethods.joinToString { it.method }}",
                    suggestion = "1. 将 IO 操作移到子线程 (Coroutine/RxJava)\n" +
                            "2. 使用异步加载 + 缓存策略\n" +
                            "3. 数据库查询使用 Room + Flow"
                )
            )
        }

        // 模式识别：布局/测量耗时
        val layoutMethods = hotMethods.filter {
            it.method.contains("measure", ignoreCase = true) ||
            it.method.contains("layout", ignoreCase = true) ||
            it.method.contains("inflate", ignoreCase = true)
        }
        if (layoutMethods.isNotEmpty()) {
            causes.add(
                RootCause(
                    type = RootCauseType.HEAVY_LAYOUT,
                    confidence = RootCause.Confidence.MEDIUM,
                    description = "布局 measure/layout 耗时过长",
                    evidence = "慢帧期间布局相关方法占比 " +
                            "%.1f%%".format(layoutMethods.sumOf { it.percentage }),
                    suggestion = "1. 减少布局嵌套层级，使用 ConstraintLayout\n" +
                            "2. 避免 requestLayout() 频繁调用\n" +
                            "3. 复杂列表使用 AsyncLayoutInflater"
                )
            )
        }

        // 模式识别：绘制耗时（自定义 View）
        val drawMethods = hotMethods.filter {
            it.method.contains("draw", ignoreCase = true) ||
            it.method.contains("onDraw", ignoreCase = true) ||
            it.method.contains("canvas", ignoreCase = true)
        }
        if (drawMethods.isNotEmpty()) {
            causes.add(
                RootCause(
                    type = RootCauseType.HEAVY_DRAW,
                    confidence = RootCause.Confidence.MEDIUM,
                    description = "自定义 View 绘制逻辑耗时",
                    evidence = "慢帧期间 draw 相关方法占比 " +
                            "%.1f%%".format(drawMethods.sumOf { it.percentage }),
                    suggestion = "1. onDraw 中避免创建 Paint/Path 对象\n" +
                            "2. 使用 Canvas 离屏缓存 (Bitmap)\n" +
                            "3. 复杂绘制考虑 RenderThread 或 SurfaceView"
                )
            )
        }

        // 如果有手动插桩的慢方法，直接列出
        if (slowMethods.isNotEmpty() && causes.none { it.confidence == RootCause.Confidence.HIGH }) {
            val top = slowMethods.first()
            causes.add(
                RootCause(
                    type = RootCauseType.SLOW_METHOD,
                    confidence = RootCause.Confidence.HIGH,
                    description = "方法 ${top.method} 耗时 %.1fms，超过帧预算".format(top.durationMs),
                    evidence = "通过 MethodTracker 插桩记录",
                    suggestion = "请优化该方法的实现，或将其移到后台线程"
                )
            )
        }

        // 兜底：有慢帧但无法确定具体原因
        if (causes.isEmpty() && slowFrames.isNotEmpty()) {
            causes.add(
                RootCause(
                    type = RootCauseType.UNKNOWN,
                    confidence = RootCause.Confidence.LOW,
                    description = "检测到 ${slowFrames.size} 帧掉帧，但未匹配到已知模式",
                    evidence = "最慢帧: %.1fms".format(slowFrames.maxOf { it.totalDurationMs }),
                    suggestion = "建议:\n" +
                            "1. 使用 methodTracker.trace(\"methodName\") { } 对可疑方法插桩\n" +
                            "2. 或导出 trace 文件交给 SmartPerfetto 做深度 AI 分析"
                )
            )
        }

        return causes.sortedByDescending { it.confidence.ordinal }
    }

    /**
     * 从原始 IOEvent 中提取完整业务调用链。
     * 按 IO 类型分组，每组取最有代表性的调用链。
     */
    private fun extractIOCallChains(
        ioEvents: List<IOEvent>,
        appPackagePrefix: String
    ): List<String> {
        if (ioEvents.isEmpty()) return emptyList()

        // 按类型分组，每组提取业务调用链
        return ioEvents
            .groupBy { it.type }
            .flatMap { (type, events) ->
                // 对每种 IO 类型，按业务调用链分组去重
                events
                    .map { event -> buildIOCallChain(event, appPackagePrefix) }
                    .filter { it.isNotEmpty() }
                    .groupBy { it }
                    .map { (chain, occurrences) ->
                        val typeLabel = when (type) {
                            IOType.DISK_READ -> "磁盘读"
                            IOType.DISK_WRITE -> "磁盘写"
                            IOType.NETWORK -> "网络"
                        }
                        "$typeLabel ×${occurrences.size} — $chain"
                    }
                    .sortedByDescending { it.substringAfter("×").substringBefore(" ").toIntOrNull() ?: 0 }
                    .take(3)
            }
            .take(5)
    }

    /**
     * 从单个 IOEvent 的 stackTrace 构建业务调用链。
     * 格式: "底层IO方法() ← 中间方法() ← 业务入口()"（从栈顶到栈底）
     */
    private fun buildIOCallChain(event: IOEvent, appPackagePrefix: String): String {
        val frames = event.stackTrace
        if (frames.isEmpty()) return ""

        // 找出所有 app 包名的帧（业务代码）
        val appFrames = frames.filter { frame ->
            appPackagePrefix.isNotEmpty() && frame.contains(appPackagePrefix)
        }

        // 找出第一个有意义的系统 IO 帧（跳过 StrictMode 本身）
        val ioFrame = frames.firstOrNull { frame ->
            !frame.contains("StrictMode") &&
            !frame.contains("BlockGuard") &&
            (frame.contains("read") || frame.contains("write") ||
             frame.contains("open") || frame.contains("query") ||
             frame.contains("access") || frame.contains("stat") ||
             frame.contains("connect") || frame.contains("socket"))
        }

        if (appFrames.isEmpty()) {
            // 没有 app 帧，展示非系统的第一个帧
            val nonSystem = frames.firstOrNull { frame ->
                !frame.startsWith("android.os.StrictMode") &&
                !frame.startsWith("java.lang.") &&
                !frame.startsWith("dalvik.")
            }
            return nonSystem?.extractSimpleName() ?: ""
        }

        // 构建调用链: 取 app 帧的前 3 个（从栈顶到栈底 = 被调用方 → 调用方）
        val chain = appFrames.take(3).map { it.extractSimpleName() }
        return chain.joinToString(" ← ")
    }

    /**
     * 将分配峰值时刻与栈采样关联，找出"高频分配时主线程在执行什么"。
     */
    private fun correlateAllocSpikesWithStacks(
        allocSamples: List<AllocSample>,
        stackSamples: List<StackSampler.StackSample>,
        appPackagePrefix: String
    ): List<String> {
        if (allocSamples.isEmpty() || stackSamples.isEmpty()) return emptyList()

        // 找出分配速率 Top 3 的时刻
        val peakSamples = allocSamples
            .sortedByDescending { it.allocCountPerInterval }
            .take(3)

        return peakSamples.mapNotNull { peak ->
            val nearbyMethods = correlateWithStacks(
                peak.timestampMs, stackSamples, appPackagePrefix, windowMs = 150
            )
            if (nearbyMethods.isNotEmpty()) {
                "峰值 ${peak.allocCountPerInterval * 5}/s 时 — ${nearbyMethods.joinToString(" → ")}"
            } else null
        }
    }

    /**
     * 通用时间戳关联: 给定一个事件时间戳，在 StackSampler 中找到最近的栈采样，
     * 返回该时刻主线程正在执行的 app 方法。
     */
    private fun correlateWithStacks(
        eventTimestampMs: Long,
        stackSamples: List<StackSampler.StackSample>,
        appPackagePrefix: String,
        windowMs: Long = 100
    ): List<String> {
        val relevant = stackSamples.filter { abs(it.timestampMs - eventTimestampMs) <= windowMs }
        if (relevant.isEmpty()) return emptyList()

        // 统计窗口内各 app 方法出现频率
        val methodCounts = mutableMapOf<String, Int>()
        for (sample in relevant) {
            val appFrames = sample.topAppFrames(appPackagePrefix)
            appFrames.take(3).forEach { frame ->
                val key = "${frame.className.substringAfterLast('.')}.${frame.methodName}"
                methodCounts[key] = (methodCounts[key] ?: 0) + 1
            }
        }

        return methodCounts.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
    }
}

private fun String.extractSimpleName(): String {
    // "com.hualai.app.ui.HomeFragment.loadData(HomeFragment.kt:42)" → "HomeFragment.loadData()"
    val withoutLineInfo = substringBefore("(").trim()
    val parts = withoutLineInfo.split(".")
    return if (parts.size >= 2) {
        "${parts[parts.size - 2]}.${parts.last()}()"
    } else {
        this
    }
}

data class AnalysisResult(
    val rootCauses: List<RootCause>,
    val hotMethods: List<HotMethod>,
    val slowMethods: List<MethodTracker.MethodRecord> = emptyList(),
    val methodStats: List<MethodAggregateStats> = emptyList(),
    val slowFrameCount: Int = 0,
    val totalFrameCount: Int = 0
)

/**
 * 方法插桩统计聚合 — 同名方法的所有调用汇总。
 */
data class MethodAggregateStats(
    val method: String,
    val count: Int,
    val avgMs: Double,
    val maxMs: Double,
    val p95Ms: Double,
    val overtimeCount: Int   // 超过 8ms 的次数
)

data class HotMethod(
    val method: String,
    val sampleCount: Int,
    val percentage: Double  // 在慢帧采样中的占比
)

data class RootCause(
    val type: RootCauseType,
    val confidence: Confidence,
    val description: String,
    val evidence: String,
    val suggestion: String,
    val callChains: List<String> = emptyList()  // 精确业务调用链
) {
    enum class Confidence { LOW, MEDIUM, HIGH }
}

enum class RootCauseType {
    GC_PRESSURE,       // GC 压力
    MAIN_THREAD_IO,    // 主线程 IO
    HEAVY_LAYOUT,      // 布局复杂
    HEAVY_DRAW,        // 绘制耗时
    SLOW_METHOD,       // 具体慢方法
    LOCK_CONTENTION,   // 锁竞争
    BINDER_CALL,       // Binder 调用
    THREAD_EXPLOSION,  // 线程暴增
    HIGH_ALLOCATION,   // 高频分配
    UNKNOWN            // 未知
}
