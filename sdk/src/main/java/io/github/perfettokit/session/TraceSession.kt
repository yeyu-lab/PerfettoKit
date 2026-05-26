package io.github.perfettokit.session

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.perfettokit.ai.LLMEnhancer
import io.github.perfettokit.analyzer.AnalysisEngine
import io.github.perfettokit.analyzer.MethodTracker
import io.github.perfettokit.analyzer.StackSampler
import io.github.perfettokit.auto.AnomalyDetector
import io.github.perfettokit.collector.AllocSample
import io.github.perfettokit.collector.AllocationTracker
import io.github.perfettokit.collector.BitmapDetector
import io.github.perfettokit.collector.BitmapIssue
import io.github.perfettokit.collector.CpuCollector
import io.github.perfettokit.collector.CpuSample
import io.github.perfettokit.collector.CpuStats
import io.github.perfettokit.collector.FrameCollector
import io.github.perfettokit.collector.FrameData
import io.github.perfettokit.collector.FrameMetricsCollector
import io.github.perfettokit.collector.FramePhaseData
import io.github.perfettokit.collector.FramePhaseStats
import io.github.perfettokit.collector.FramePhase
import io.github.perfettokit.collector.PhaseBottleneckEntry
import io.github.perfettokit.collector.IODetector
import io.github.perfettokit.collector.IOEvent
import io.github.perfettokit.collector.LooperMonitor
import io.github.perfettokit.collector.LooperMonitorResult
import io.github.perfettokit.collector.CumulativeJankContributor
import io.github.perfettokit.collector.JankFrameAttribution
import io.github.perfettokit.collector.MessageTiming
import io.github.perfettokit.collector.SlowMessage
import io.github.perfettokit.collector.SlowMethodEntry
import io.github.perfettokit.collector.SlowMessageStats
import io.github.perfettokit.collector.StackBasedJankContributor
import io.github.perfettokit.collector.MemoryCollector
import io.github.perfettokit.collector.MemorySample
import io.github.perfettokit.collector.MemoryStats
import io.github.perfettokit.collector.MethodTracer
import io.github.perfettokit.collector.NetworkCollector
import io.github.perfettokit.collector.NetworkSample
import io.github.perfettokit.collector.ThreadCollector
import io.github.perfettokit.collector.ThreadSample
import io.github.perfettokit.collector.ThreadStats
import io.github.perfettokit.history.FrameStats
import io.github.perfettokit.report.DiagnosisReport
import io.github.perfettokit.report.JankFrameDetail
import io.github.perfettokit.report.MainThreadMethod
import io.github.perfettokit.report.MainThreadStats
import io.github.perfettokit.report.SamplingBreakdown
import io.github.perfettokit.report.Reporter
import io.github.perfettokit.rule.Rule
import io.github.perfettokit.rule.RuleContext
import io.github.perfettokit.skill.Skill

/**
 * 一次检测会话。由 beginSession() 创建，end() 结束并输出诊断结果。
 *
 * 采集维度:
 *   - 帧率 (Choreographer)
 *   - CPU 使用率 (/proc/stat)
 *   - 内存 & GC (Runtime + Debug)
 *   - 线程状态 (/proc/[pid]/task)
 *   - 主线程堆栈采样
 *   - 方法耗时插桩 (开发者手动)
 *   - 网络流量 (TrafficStats)
 *   - 主线程 IO (StrictMode)
 *   - 对象分配速率 (Debug.getAllocCount)
 *   - 大图检测 (ImageView Bitmap)
 */
class TraceSession internal constructor(
    val scene: String,
    private val rules: List<Rule>,
    private val reporter: Reporter,
    private val enableStackSampling: Boolean = true,
    private val appPackagePrefix: String = "",
    private val skills: List<Skill> = emptyList(),
    private val context: Context? = null,
    private val anomalyDetector: AnomalyDetector? = null,
    private val llmEnhancer: LLMEnhancer? = null
) {
    private val startTimeMs = SystemClock.elapsedRealtime()
    private val stackSampler = StackSampler(context = context)
    private val frameCollector = FrameCollector()
    private val frameMetricsCollector = FrameMetricsCollector()
    private val cpuCollector = CpuCollector()
    private val memoryCollector = MemoryCollector(context)
    private val threadCollector = ThreadCollector()
    private val networkCollector = NetworkCollector()
    private val ioDetector = IODetector()
    private val allocationTracker = AllocationTracker()
    private val looperMonitor = LooperMonitor()
    private val bitmapDetector = BitmapDetector()
    private val analysisEngine = AnalysisEngine()
    private var ended = false
    @Volatile private var lastReport: DiagnosisReport? = null

    /**
     * 方法耗时追踪器 — 开发者可对可疑方法手动插桩。
     */
    val methodTracker = MethodTracker()

    init {
        // 绑定全局 MethodTracer → 当前 session
        MethodTracer.bind(methodTracker)

        // StackSampler 延迟到第一帧渲染后再启动，避免捕获 session 创建前的残留操作
        if (enableStackSampling) {
            frameCollector.setOnFirstFrameListener {
                stackSampler.start(scene)
            }
        }
        frameCollector.start()
        if (context is Activity) {
            frameMetricsCollector.start(context as Activity)
        }
        cpuCollector.start()
        memoryCollector.start()
        threadCollector.start()
        networkCollector.start()
        ioDetector.start()
        allocationTracker.start()
        looperMonitor.start()
    }

    /**
     * 结束本次检测 Session（异步版本，推荐）。
     * 停止采集在调用线程（快速），分析+报告在后台线程。
     */
    fun endAsync(callback: (DiagnosisReport) -> Unit) {
        if (ended) {
            lastReport?.let { callback(it) }
            return
        }
        val rawData = stopCollectors()
        Thread({
            val report = analyzeAndBuildReport(rawData)
            lastReport = report
            callback(report)
        }, "PerfettoKit-Analyze").start()
    }

    /**
     * 结束本次检测 Session（同步版本）。
     * 停止采集在调用线程，分析在后台线程，阻塞等待结果。
     */
    fun end(): DiagnosisReport {
        lastReport?.let { return it }
        val rawData = stopCollectors()
        val report = analyzeAndBuildReport(rawData)
        lastReport = report
        return report
    }

    private data class RawCollectorData(
        val frames: List<FrameData>,
        val framePhaseData: List<FramePhaseData>,
        val cpuSamples: List<CpuSample>,
        val memorySamples: List<MemorySample>,
        val threadSamples: List<ThreadSample>,
        val networkSamples: List<NetworkSample>,
        val ioEvents: List<IOEvent>,
        val allocSamples: List<AllocSample>,
        val stackSamples: List<StackSampler.StackSample>,
        val methodRecords: List<MethodTracker.MethodRecord>,
        val bitmapIssues: List<BitmapIssue>,
        val looperResult: LooperMonitorResult,
        val durationMs: Long
    )

    /**
     * 快速停止所有采集器（主线程安全，只是停止+收集原始数据）。
     */
    private fun stopCollectors(): RawCollectorData {
        check(!ended) { "Session '$scene' already ended" }
        ended = true

        // 解绑全局 MethodTracer
        MethodTracer.unbind()

        val frames = frameCollector.stop()
        val framePhaseData = frameMetricsCollector.stop()
        val cpuSamples = cpuCollector.stop()
        val memorySamples = memoryCollector.stop()
        val threadSamples = threadCollector.stop()
        val networkSamples = networkCollector.stop()
        val ioEvents = ioDetector.stop()
        val allocSamples = allocationTracker.stop()
        val looperResult = looperMonitor.stop()
        val stackSamples = if (enableStackSampling) stackSampler.stop() else emptyList()
        val methodRecords = methodTracker.getRecords()
        val durationMs = SystemClock.elapsedRealtime() - startTimeMs

        val bitmapIssues = if (context is Activity) {
            val rootView = (context as Activity).window?.decorView
            bitmapDetector.scan(rootView)
        } else emptyList()

        return RawCollectorData(
            frames = frames,
            framePhaseData = framePhaseData,
            cpuSamples = cpuSamples,
            memorySamples = memorySamples,
            threadSamples = threadSamples,
            networkSamples = networkSamples,
            ioEvents = ioEvents,
            allocSamples = allocSamples,
            stackSamples = stackSamples,
            methodRecords = methodRecords,
            bitmapIssues = bitmapIssues,
            looperResult = looperResult,
            durationMs = durationMs
        )
    }

    /**
     * 重度分析（可在后台线程执行）：统计 + 规则 + 根因 + 异常检测 + 报告。
     */
    @Suppress("UNCHECKED_CAST")
    private fun analyzeAndBuildReport(data: RawCollectorData): DiagnosisReport {
        val frames = data.frames
        val cpuSamples = data.cpuSamples
        val stackSamples = data.stackSamples
        val ioEvents = data.ioEvents
        val allocSamples = data.allocSamples
        val durationMs = data.durationMs

        // 空 session 短路：无帧 + 无插桩 + 无 Looper 慢消息 → 不打印完整报告，只一行提示
        val hasLooperData = data.looperResult.slowMessages.isNotEmpty() ||
            data.looperResult.totalMessageCount > 0
        if (frames.isEmpty() && data.methodRecords.isEmpty() && !hasLooperData) {
            Log.d("PerfettoKit", "⚪ Session '$scene' skipped (no frames / no data, ${durationMs}ms)")
            return DiagnosisReport(
                scene = scene,
                durationMs = durationMs,
                totalFrames = 0,
                summary = "Skipped: no data",
                issues = emptyList()
            )
        }

        // 计算各维度统计
        val cpuStats = cpuCollector.computeStats(cpuSamples)
        val memoryStats = memoryCollector.computeStats(data.memorySamples)
        val threadStats = threadCollector.computeStats(data.threadSamples)
        val networkStats = networkCollector.computeStats(data.networkSamples)
        val ioStats = ioDetector.computeStats(ioEvents)
        val allocStats = allocationTracker.computeStats(allocSamples)
        val bitmapStats = bitmapDetector.computeStats(data.bitmapIssues)

        // 主线程 CPU 占用率 = 主线程CPU时间增量 / 实际经过时间
        val mainThreadStats = computeMainThreadStats(cpuSamples, durationMs, threadStats, stackSamples)

        // 帧阶段分析 (FrameMetrics API 24+)
        val framePhaseStats = computeFramePhaseStats(data.framePhaseData, frames)

        // Looper 消息监控统计 — 关联掉帧数据
        val slowMessageStats = computeSlowMessageStats(data.looperResult, frames, stackSamples)

        // 掉帧归因 — 每一帧掉帧时到底是什么导致的
        val jankFrameDetails = attributeJankFrames(frames, stackSamples, ioEvents, allocSamples)

        // 第一层：规则引擎 — 检测
        val ruleContext = RuleContext(
            scene = scene,
            frames = frames,
            durationMs = durationMs,
            cpuStats = cpuStats,
            memoryStats = memoryStats,
            threadStats = threadStats
        )
        val issues = rules.flatMap { rule -> rule.evaluate(ruleContext) }.toMutableList()

        // 新增维度的 Issue 检测
        if (ioStats.totalViolations > 0) {
            issues.add(DiagnosisReport.Issue(
                severity = if (ioStats.totalViolations > 5) DiagnosisReport.Severity.HIGH else DiagnosisReport.Severity.MEDIUM,
                rule = "IODetector",
                message = "主线程 IO: ${ioStats.diskReadCount} 次磁盘读, ${ioStats.diskWriteCount} 次磁盘写, ${ioStats.networkOnMainCount} 次网络",
                suggestion = "将 IO 操作移到子线程 (Dispatchers.IO / AsyncTask)"
            ))
        }

        if (allocStats.isHighPressure) {
            issues.add(DiagnosisReport.Issue(
                severity = DiagnosisReport.Severity.MEDIUM,
                rule = "AllocationTracker",
                message = "高频对象分配: 峰值 ${allocStats.peakAllocPerSec} 次/秒, 共 ${allocStats.totalAllocKB}KB",
                suggestion = "检查是否在 onDraw/onBindViewHolder 中创建对象，考虑对象池复用"
            ))
        }

        if (bitmapStats.hasIssues) {
            issues.add(DiagnosisReport.Issue(
                severity = DiagnosisReport.Severity.MEDIUM,
                rule = "BitmapDetector",
                message = "${bitmapStats.oversizeBitmapCount} 张大图, 浪费约 ${"%.1f".format(bitmapStats.totalWastedMB)}MB 内存",
                suggestion = "使用 inSampleSize 缩放或 Glide/Coil 的 override(width, height)"
            ))
        }

        if (networkStats.totalKB > 500) {
            issues.add(DiagnosisReport.Issue(
                severity = DiagnosisReport.Severity.LOW,
                rule = "NetworkCollector",
                message = "Session 期间网络流量: ${networkStats.totalKB}KB (↓${networkStats.totalRxBytes/1024}KB ↑${networkStats.totalTxBytes/1024}KB)",
                suggestion = "检查是否有不必要的网络请求或未压缩的数据传输"
            ))
        }

        // 第二层：分析引擎 — 根因定位
        val analysis = analysisEngine.analyze(
            frames = frames,
            stackSamples = stackSamples,
            methodRecords = data.methodRecords,
            appPackagePrefix = appPackagePrefix,
            scene = scene,
            skills = skills,
            ioStats = ioStats,
            allocationStats = allocStats,
            memoryStats = memoryStats,
            threadStats = threadStats,
            networkStats = networkStats,
            ioEvents = ioEvents,
            allocSamples = allocSamples
        )

        // 帧统计
        val frameStats = if (frames.isNotEmpty()) {
            val avgMs = frames.map { it.totalDurationMs }.average()
            val maxMs = frames.maxOf { it.totalDurationMs }
            val threshold = detectJankThreshold(frames)
            val jankCount = frames.count { it.totalDurationMs > threshold }
            FrameStats(
                avgMs = avgMs,
                maxMs = maxMs,
                jankCount = jankCount,
                jankRatio = if (frames.isNotEmpty()) jankCount.toDouble() / frames.size else 0.0
            )
        } else {
            FrameStats(avgMs = 0.0, maxMs = 0.0, jankCount = 0, jankRatio = 0.0)
        }

        // 第三层：异常自学习 — 与基线对比
        val report = DiagnosisReport(
            scene = scene,
            durationMs = durationMs,
            totalFrames = frames.size,
            issues = issues,
            summary = buildSummary(frames, issues),
            analysis = analysis,
            cpuStats = cpuStats,
            memoryStats = memoryStats,
            threadStats = threadStats,
            networkStats = networkStats,
            ioStats = ioStats,
            allocationStats = allocStats,
            bitmapStats = bitmapStats,
            mainThreadStats = mainThreadStats,
            jankFrameDetails = jankFrameDetails,
            framePhaseStats = framePhaseStats,
            slowMessageStats = slowMessageStats
        )

        anomalyDetector?.let { detector ->
            val anomaly = detector.detect(report, frameStats)
            if (anomaly != null) {
                issues.add(DiagnosisReport.Issue(
                    severity = when (anomaly.severity) {
                        io.github.perfettokit.auto.AnomalySeverity.CRITICAL -> DiagnosisReport.Severity.HIGH
                        io.github.perfettokit.auto.AnomalySeverity.WARNING -> DiagnosisReport.Severity.MEDIUM
                    },
                    rule = "AnomalyDetector",
                    message = anomaly.message,
                    suggestion = "与历史基线 (${anomaly.baselineSamples} 次) 对比发现异常偏离，建议检查近期代码变更"
                ))
                Log.w("PerfettoKit", anomaly.message)
            }
            detector.recordBaseline(report, frameStats)
        }

        // 重建包含异常 Issue 的最终报告
        val finalReport = report.copy(issues = issues.toList())

        reporter.report(finalReport)

        // 第四层：LLM 增强（异步，不阻塞）
        llmEnhancer?.enhanceAsync(finalReport) { aiResponse ->
            if (aiResponse != null) {
                Log.i("PerfettoKit", "━━━ AI 增强建议 ━━━")
                Log.i("PerfettoKit", aiResponse.suggestions.joinToString("\n"))
            }
        }

        return finalReport
    }

    private fun buildSummary(frames: List<FrameData>, issues: List<DiagnosisReport.Issue>): String {
        if (frames.isEmpty()) return "No frames captured in session '$scene'"

        val avgMs = frames.map { it.totalDurationMs }.average()
        val maxMs = frames.maxOf { it.totalDurationMs }
        val threshold = detectJankThreshold(frames)
        val jankCount = frames.count { it.totalDurationMs > threshold }

        return buildString {
            append("Scene: $scene | ")
            append("Frames: ${frames.size} | ")
            append("Avg: %.1fms | ".format(avgMs))
            append("Max: %.1fms | ".format(maxMs))
            append("Jank: $jankCount | ")
            append("Issues: ${issues.size}")
        }
    }

    /**
     * 计算主线程 CPU 统计 + 全量栈采样分析（主线程到底在忙什么）。
     * 主线程 CPU% = 主线程CPU时间增量 / 经过时间 * 100
     */
    private fun computeMainThreadStats(
        cpuSamples: List<CpuSample>,
        durationMs: Long,
        threadStats: ThreadStats,
        stackSamples: List<StackSampler.StackSample>
    ): MainThreadStats {
        if (cpuSamples.size < 2 || durationMs <= 0) return MainThreadStats()

        val firstSample = cpuSamples.first()
        val lastSample = cpuSamples.last()
        val mainThreadCpuDeltaMs = lastSample.mainThreadCpuTimeMs - firstSample.mainThreadCpuTimeMs
        val cpuPercent = (mainThreadCpuDeltaMs.toDouble() / durationMs) * 100.0

        // 全量栈采样分析：主线程时间都花在哪些方法上
        val (topMethods, breakdown) = analyzeMainThreadMethods(stackSamples)

        return MainThreadStats(
            cpuPercent = cpuPercent,
            busyRatio = threadStats.mainThreadRunnableRatio,
            avgMessageDelayMs = 0.0,
            maxMessageDelayMs = 0.0,
            topMethods = topMethods,
            samplingBreakdown = breakdown
        )
    }

    /**
     * 全量分析主线程栈采样 — 不限于掉帧时段，统计整个 Session 主线程时间分布。
     * 回答："主线程被谁占用了？"
     * 同时返回 SamplingBreakdown 用于诚实展示采样去向。
     */
    private fun analyzeMainThreadMethods(
        stackSamples: List<StackSampler.StackSample>
    ): Pair<List<MainThreadMethod>, SamplingBreakdown> {
        if (stackSamples.isEmpty()) return Pair(emptyList(), SamplingBreakdown())

        val totalSamples = stackSamples.size
        val methodCount = mutableMapOf<String, Int>()
        val methodCategory = mutableMapOf<String, String>()
        var idleSamples = 0
        var appMethodSamples = 0
        var systemMethodSamples = 0
        var renderingMethodSamples = 0
        var thirdpartyMethodSamples = 0
        var emptyStackSamples = 0

        for (sample in stackSamples) {
            val frames = sample.stackTrace

            // 栈为空 = 真正在 native 层不可见
            if (frames.isEmpty()) {
                emptyStackSamples++
                continue
            }

            // 空闲状态（栈顶为阻塞方法 = 主线程在等待消息）
            if (isIdleStack(frames)) {
                idleSamples++
                continue
            }

            // 优先取 app 方法
            val appFrames = sample.topAppFrames(appPackagePrefix)
            if (appFrames.isNotEmpty()) {
                val f = appFrames.first()
                val key = "${f.className.substringAfterLast('.')}.${f.methodName}"
                methodCount[key] = (methodCount[key] ?: 0) + 1
                methodCategory[key] = "app"
                appMethodSamples++
            } else {
                // 没有 app 方法，归类为 rendering/thirdparty/system
                val meaningful = frames.firstOrNull { !isIdleFrame(it) }
                if (meaningful != null) {
                    val key = "${meaningful.className.substringAfterLast('.')}.${meaningful.methodName}"
                    methodCount[key] = (methodCount[key] ?: 0) + 1
                    val cat = categorizeFrame(meaningful)
                    methodCategory[key] = cat
                    when (cat) {
                        "rendering" -> renderingMethodSamples++
                        "thirdparty" -> thirdpartyMethodSamples++
                        else -> systemMethodSamples++
                    }
                } else {
                    systemMethodSamples++
                }
            }
        }

        val breakdown = SamplingBreakdown(
            totalSamples = totalSamples,
            idleSamples = idleSamples,
            appMethodSamples = appMethodSamples,
            systemMethodSamples = systemMethodSamples,
            renderingMethodSamples = renderingMethodSamples,
            thirdpartyMethodSamples = thirdpartyMethodSamples,
            emptyStackSamples = emptyStackSamples,
            uniqueMethodCount = methodCount.size
        )

        val methods = methodCount.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (method, count) ->
                MainThreadMethod(
                    method = method,
                    sampleCount = count,
                    percentage = count.toDouble() / totalSamples * 100,
                    category = methodCategory[method] ?: "system"
                )
            }

        return Pair(methods, breakdown)
    }

    private fun categorizeFrame(frame: StackTraceElement): String {
        val cls = frame.className
        return when {
            cls.startsWith("android.view.") || cls.contains("Choreographer") ||
                frame.methodName.let { it == "performTraversals" || it == "draw" || it == "measure" || it == "layout" } ->
                "rendering"
            cls.startsWith("android.") || cls.startsWith("com.android.") ||
                cls.startsWith("java.") || cls.startsWith("kotlin.") ||
                cls.startsWith("dalvik.") || cls.startsWith("libcore.") ||
                cls.startsWith("jdk.") || cls.startsWith("org.json.") ||
                cls.startsWith("sun.") -> "system"
            else -> "thirdparty"
        }
    }

    private fun isIdleFrame(frame: StackTraceElement): Boolean {
        // 保留单帧级别判断（用于 analyzeMainThreadMethods 中的 fallback）
        return isBlockingLeafMethod(frame)
    }

    /**
     * 判断整个栈是否处于"空闲等待"状态。
     * 核心逻辑: 栈顶（叶帧）是已知的阻塞方法 = 线程正在等待。
     * 不需要硬编码大量方法名，只关注栈顶的语义。
     */
    private fun isIdleStack(stackTrace: Array<StackTraceElement>): Boolean {
        if (stackTrace.isEmpty()) return true
        val leaf = stackTrace.first()  // stackTrace[0] = 栈顶 = 正在执行的方法
        return isBlockingLeafMethod(leaf)
    }

    /**
     * 栈顶帧是否为"阻塞等待"方法 — 这些方法出现在栈顶说明线程在等。
     * 只需要极少的核心方法：OS 级别的阻塞原语。
     */
    private fun isBlockingLeafMethod(frame: StackTraceElement): Boolean {
        return when {
            // epoll/poll — Looper 等待下一条消息
            frame.methodName == "nativePollOnce" -> true
            // Thread.park / Object.wait — 线程挂起
            frame.methodName == "park" && frame.className.contains("Unsafe") -> true
            frame.methodName == "wait" && frame.className == "java.lang.Object" -> true
            // native 方法 epoll_wait (有些 ROM stackTrace 会显示)
            frame.methodName == "epollWait" -> true
            else -> false
        }
    }

    /**
     * 根据实际帧间隔自动检测刷新率，返回对应的掉帧阈值。
     * 取帧间隔的中位数来判断 VSync 周期：
     *   ≤10ms → 120Hz (阈值 ~10ms, 即 1.2 × VSync)
     *   ≤12ms → 90Hz  (阈值 ~13ms)
     *   其他  → 60Hz  (阈值 ~20ms)
     * 阈值 = VSync × 1.2（允许 20% 波动，避免正常帧被误判）
     */
    private fun detectJankThreshold(frames: List<FrameData>): Double {
        if (frames.size < 10) return 16.67

        // 取中位数帧间隔来推断 VSync 周期
        val sorted = frames.map { it.totalDurationMs }.sorted()
        val median = sorted[sorted.size / 2]

        return when {
            median <= 10.0 -> 10.0   // 120Hz: VSync 8.33ms, 阈值 ~10ms
            median <= 13.0 -> 13.0   // 90Hz: VSync 11.1ms, 阈值 ~13ms
            else -> 20.0             // 60Hz: VSync 16.67ms, 阈值 ~20ms
        }
    }

    /**
     * 掉帧归因 — 对每一帧超时(>16.67ms)的帧，关联当时的栈采样、IO事件、分配量，推断原因。
     *
     * 栈归因策略:
     * 1. 优先显示 app 包名前缀的方法（用户自己的代码）
     * 2. 找出该帧期间出现频率最高的 app 方法（热点）
     * 3. 保留完整调用链: app方法 ← 调用者
     */
    private fun attributeJankFrames(
        frames: List<FrameData>,
        stackSamples: List<StackSampler.StackSample>,
        ioEvents: List<IOEvent>,
        allocSamples: List<AllocSample>
    ): List<JankFrameDetail> {
        // 根据实际帧间隔自动检测刷新率，适配 60Hz/90Hz/120Hz
        val jankThresholdMs = detectJankThreshold(frames)
        val details = mutableListOf<JankFrameDetail>()

        // 计算 session 整体分配密度基线（次/ms），用于判断某帧是否异常高
        val baselineAllocPerMs = if (allocSamples.size >= 2) {
            val totalAlloc = allocSamples.sumOf { it.allocCountPerInterval }
            val spanMs = allocSamples.last().timestampMs - allocSamples.first().timestampMs
            if (spanMs > 0) totalAlloc.toDouble() / spanMs else 0.0
        } else 0.0

        frames.forEachIndexed { index, frame ->
            if (frame.totalDurationMs <= jankThresholdMs) return@forEachIndexed

            // timestampMs 是帧结束时间，实际帧开始 = 前一帧的 timestampMs（墙钟）
            val frameEnd = frame.timestampMs
            val frameStart = if (index > 0) frames[index - 1].timestampMs
                else frameEnd - frame.totalDurationMs.toLong()

            // 找该帧期间的所有栈采样
            val frameSamples = stackSamples.filter { it.timestampMs in frameStart..frameEnd }

            // 从帧内采样中取真实调用链 — 选择"最具代表性"的单个采样（最接近帧中点）
            // 而不是频率拼合，保证展示的调用链是真实栈顺序
            val bestSample = if (frameSamples.isNotEmpty()) {
                val frameMid = frameStart + (frameEnd - frameStart) / 2
                frameSamples.minByOrNull { kotlin.math.abs(it.timestampMs - frameMid) }
            } else null

            // 从 bestSample 中提取真实栈顺序的方法列表
            val stackMethods: List<String>
            if (bestSample != null) {
                val appFrames = bestSample.topAppFrames(appPackagePrefix)
                if (appFrames.isNotEmpty()) {
                    // 有 app 方法: 展示真实栈顺序（栈顶 = 正在执行的方法 → 调用者）
                    stackMethods = appFrames.take(5).map { f ->
                        "${f.className.substringAfterLast('.')}.${f.methodName}"
                    }
                } else {
                    // 没有 app 方法: 展示系统方法（draw/measure/layout 等也有价值）
                    val meaningfulFrames = bestSample.stackTrace.filter { f ->
                        !isIdleStack(bestSample.stackTrace) &&
                        !f.className.startsWith("dalvik.") &&
                        !f.className.startsWith("io.github.perfettokit.") &&
                        f.className != "java.lang.Thread" &&
                        f.className != "dalvik.system.VMStack"
                    }
                    stackMethods = meaningfulFrames.take(5).map { f ->
                        "${f.className.substringAfterLast('.')}.${f.methodName}"
                    }
                }
            } else {
                stackMethods = emptyList()
            }

            // 2) 该帧期间是否有 IO 事件
            val hasIO = ioEvents.any { it.timestampMs in frameStart..frameEnd }

            // 3) 该帧期间的分配密度是否显著高于基线（>3倍）
            val allocCount = allocSamples
                .filter { it.timestampMs in frameStart..frameEnd }
                .sumOf { it.allocCountPerInterval }
            val frameAllocPerMs = if (frame.totalDurationMs > 0) allocCount.toDouble() / frame.totalDurationMs else 0.0
            val isAllocAnomaly = baselineAllocPerMs > 0 && frameAllocPerMs > baselineAllocPerMs * 3

            // 4) 推断原因 — 方法优先，IO/分配作为补充标签
            // 采样置信度：帧内采样点少于 3 个时标注低置信度
            val sampleCount = frameSamples.size
            val lowConfidence = sampleCount < 3

            val cause = buildString {
                // 主因：优先展示具体方法
                when {
                    stackMethods.isNotEmpty() -> {
                        val topMethod = stackMethods.first()
                        when {
                            topMethod.contains("measure") || topMethod.contains("layout") ||
                                topMethod.contains("onMeasure") || topMethod.contains("onLayout") ->
                                append("布局耗时 ($topMethod)")
                            topMethod.contains("draw") || topMethod.contains("onDraw") ->
                                append("绘制耗时 ($topMethod)")
                            else -> append("方法耗时 ($topMethod)")
                        }
                    }
                    else -> {
                        if (frame.totalDurationMs < 25) {
                            append("短卡顿(${frame.totalDurationMs.toLong()}ms)")
                        } else {
                            append("未归因(${frame.totalDurationMs.toLong()}ms)")
                        }
                    }
                }
                // 补充标签：IO 和异常分配
                if (hasIO) append(" | IO阻塞")
                if (isAllocAnomaly) append(" | 高频分配${allocCount}次")
                // 低置信度标注
                if (lowConfidence && stackMethods.isNotEmpty()) {
                    append(" ⚠采样点少(${sampleCount}),归因可能不准")
                }
            }

            details.add(JankFrameDetail(
                frameIndex = index,
                durationMs = frame.totalDurationMs,
                stackMethods = stackMethods,
                hasIO = hasIO,
                allocCountDuringFrame = allocCount,
                cause = cause
            ))
        }

        return details
    }

    private fun isSystemFrame(frame: StackTraceElement): Boolean {
        val cls = frame.className
        return cls.startsWith("android.") ||
            cls.startsWith("com.android.") ||
            cls.startsWith("java.") ||
            cls.startsWith("javax.") ||
            cls.startsWith("kotlin.") ||
            cls.startsWith("kotlinx.") ||
            cls.startsWith("dalvik.") ||
            cls.startsWith("sun.") ||
            cls.startsWith("jdk.") ||
            cls.startsWith("org.json.") ||
            cls.startsWith("libcore.") ||
            cls.startsWith("io.github.perfettokit.")
    }

    /**
     * 关联慢消息与掉帧 — 找出每条慢消息落在哪一帧，统计每个方法影响了多少帧掉帧。
     */
    private fun computeSlowMessageStats(
        looperResult: LooperMonitorResult,
        frames: List<FrameData>,
        stackSamples: List<StackSampler.StackSample>
    ): SlowMessageStats {
        val messages = looperResult.slowMessages
        if (messages.isEmpty()) return SlowMessageStats(totalMessageCount = looperResult.totalMessageCount)

        val jankThresholdMs = detectJankThreshold(frames)

        // 预计算帧的实际墙钟开始时间
        val sortedFrames = frames.sortedBy { it.timestampMs }
        val frameStartMap = mutableMapOf<Long, Long>()
        for (i in sortedFrames.indices) {
            val end = sortedFrames[i].timestampMs
            frameStartMap[end] = if (i > 0) sortedFrames[i - 1].timestampMs
                else end - sortedFrames[i].totalDurationMs.toLong()
        }

        // 构建掉帧帧的时间窗口列表 [frameStart, frameEnd]，使用墙钟帧边界
        val jankWindows = sortedFrames
            .filter { it.totalDurationMs > jankThresholdMs }
            .map { frame ->
                val end = frame.timestampMs
                val start = frameStartMap[end] ?: (end - frame.totalDurationMs.toLong())
                start to end
            }

        val totalJankFrames = jankWindows.size

        // 按方法分组
        val methodHits = mutableMapOf<String, MutableList<SlowMessage>>()
        for (msg in messages) {
            val appMethod = msg.topAppMethod()
            if (appMethod != null) {
                methodHits.getOrPut(appMethod) { mutableListOf() }.add(msg)
            }
        }

        // 对每个方法，计算其慢消息关联了多少帧掉帧
        val methodJankFrames = mutableMapOf<String, Int>()
        for ((method, msgs) in methodHits) {
            // 统计该方法的慢消息覆盖了多少个不同的掉帧帧
            val coveredJankFrames = jankWindows.count { (frameStart, frameEnd) ->
                msgs.any { msg ->
                    val msgEnd = msg.startTimeMs + msg.durationMs
                    // 消息与帧时间窗有重叠
                    msg.startTimeMs < frameEnd && msgEnd > frameStart
                }
            }
            methodJankFrames[method] = coveredJankFrames
        }

        // 统计所有慢消息总共覆盖了多少帧掉帧
        val allCoveredJankFrames = jankWindows.count { (frameStart, frameEnd) ->
            messages.any { msg ->
                val msgEnd = msg.startTimeMs + msg.durationMs
                msg.startTimeMs < frameEnd && msgEnd > frameStart
            }
        }

        // 按出现次数排序
        val topSlowMethods = methodHits.entries
            .sortedByDescending { it.value.size }
            .take(10)
            .map { (method, msgs) ->
                SlowMethodEntry(
                    method = method,
                    hitCount = msgs.size,
                    jankFrameCount = methodJankFrames[method] ?: 0,
                    avgDurationMs = msgs.map { it.durationMs }.average(),
                    maxDurationMs = msgs.maxOf { it.durationMs },
                    category = msgs.first().category,
                    callChain = msgs.maxByOrNull { it.stackTraces.size }
                        ?.bestAppCallChain() ?: emptyList()
                )
            }

        // 按类别分布
        val categoryBreakdown = messages.groupBy { it.category }
            .map { (cat, msgs) ->
                io.github.perfettokit.collector.CategoryEntry(
                    category = cat,
                    count = msgs.size,
                    totalDurationMs = msgs.sumOf { it.durationMs },
                    avgDurationMs = msgs.map { it.durationMs }.average()
                )
            }
            .sortedByDescending { it.totalDurationMs }

        // 帧级归因 — 用全量消息计时数据分析每帧掉帧的消息组成
        val jankAttribution = computeJankFrameAttribution(
            allMessages = looperResult.allMessages,
            slowMessages = messages,
            frames = frames,
            jankThresholdMs = jankThresholdMs,
            stackSamples = stackSamples
        )

        return SlowMessageStats(
            totalMessageCount = looperResult.totalMessageCount,
            totalSlowMessages = messages.size,
            totalJankFrames = totalJankFrames,
            jankFramesCoveredBySlowMsg = allCoveredJankFrames,
            totalSlowDurationMs = messages.sumOf { it.durationMs },
            maxDurationMs = messages.maxOf { it.durationMs },
            topSlowMethods = topSlowMethods,
            categoryBreakdown = categoryBreakdown,
            jankAttribution = jankAttribution
        )
    }

    /**
     * 帧级消息归因 — 对每帧掉帧，统计该帧时间窗内所有消息的耗时组成。
     * 解答: "每帧掉帧由几条消息组成？是单条慢消息撑爆还是多条正常消息累积？"
     */
    private fun computeJankFrameAttribution(
        allMessages: List<MessageTiming>,
        slowMessages: List<SlowMessage>,
        frames: List<FrameData>,
        jankThresholdMs: Double,
        stackSamples: List<StackSampler.StackSample>
    ): JankFrameAttribution {
        if (allMessages.isEmpty() || frames.isEmpty()) return JankFrameAttribution()

        val jankFrames = frames.filter { it.totalDurationMs > jankThresholdMs }
        val normalFrames = frames.filter { it.totalDurationMs <= jankThresholdMs }
        if (jankFrames.isEmpty()) return JankFrameAttribution()

        // 预计算帧的实际墙钟开始时间（相邻帧 timestampMs 差）
        val sortedFrames = frames.sortedBy { it.timestampMs }
        val frameActualStart = mutableMapOf<Long, Long>()
        for (i in sortedFrames.indices) {
            val frameEnd = sortedFrames[i].timestampMs
            frameActualStart[frameEnd] = if (i > 0) sortedFrames[i - 1].timestampMs
                else frameEnd - sortedFrames[i].totalDurationMs.toLong()
        }

        var singleSlowMsgFrames = 0
        var multiMsgStackFrames = 0
        var totalMsgsInJankFrames = 0
        var totalOverbudgetMs = 0.0
        var pureAccumulationFrames = 0

        // 按方法索引慢消息，便于快速查找
        val slowMsgsByMethod = mutableMapOf<String, MutableList<SlowMessage>>()
        for (msg in slowMessages) {
            val method = msg.topAppMethod() ?: continue
            slowMsgsByMethod.getOrPut(method) { mutableListOf() }.add(msg)
        }

        // 统计每个方法在多消息累积帧中的出现次数
        val methodMultiFrameCount = mutableMapOf<String, Int>()
        val methodMultiFrameDurations = mutableMapOf<String, MutableList<Long>>()

        for (frame in jankFrames) {
            val frameEnd = frame.timestampMs
            val frameStart = frameActualStart[frameEnd] ?: (frameEnd - frame.totalDurationMs.toLong())

            // 找该帧时间窗内的所有消息数
            val msgsInFrame = allMessages.count { msg ->
                val msgEnd = msg.startTimeMs + msg.durationMs
                msg.startTimeMs < frameEnd && msgEnd > frameStart
            }

            // 该帧内的慢消息
            val slowMsgsInFrame = slowMessages.filter { msg ->
                val msgEnd = msg.startTimeMs + msg.durationMs
                msg.startTimeMs < frameEnd && msgEnd > frameStart
            }
            val hasSlowMsg = slowMsgsInFrame.isNotEmpty()

            totalMsgsInJankFrames += msgsInFrame
            totalOverbudgetMs += (frame.totalDurationMs - jankThresholdMs)

            if (hasSlowMsg && msgsInFrame <= 2) {
                singleSlowMsgFrames++
            } else {
                multiMsgStackFrames++

                // 对累积型掉帧帧，统计哪些慢方法出现在其中
                if (slowMsgsInFrame.isEmpty()) {
                    pureAccumulationFrames++
                } else {
                    // 记录该帧中每个方法（去重，一帧只算一次）
                    val methodsInThisFrame = mutableSetOf<String>()
                    for (msg in slowMsgsInFrame) {
                        val method = msg.topAppMethod() ?: continue
                        methodsInThisFrame.add(method)
                    }
                    for (method in methodsInThisFrame) {
                        methodMultiFrameCount[method] = (methodMultiFrameCount[method] ?: 0) + 1
                    }
                    // 记录各方法在此帧中的耗时
                    for (msg in slowMsgsInFrame) {
                        val method = msg.topAppMethod() ?: continue
                        methodMultiFrameDurations.getOrPut(method) { mutableListOf() }.add(msg.durationMs)
                    }
                }
            }
        }

        // 计算累积型掉帧贡献者排名（按出现帧数排序）
        val topContributors = if (multiMsgStackFrames > 0) {
            methodMultiFrameCount.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { (method, count) ->
                    val durations = methodMultiFrameDurations[method] ?: emptyList()
                    val avgDur = if (durations.isNotEmpty()) durations.average() else 0.0
                    val callChain = slowMsgsByMethod[method]
                        ?.maxByOrNull { it.stackTraces.size }
                        ?.bestAppCallChain() ?: emptyList()
                    CumulativeJankContributor(
                        method = method,
                        appearanceCount = count,
                        appearanceRate = count.toDouble() / multiMsgStackFrames,
                        avgDurationMs = avgDur,
                        callChain = callChain
                    )
                }
        } else emptyList()

        // 正常帧平均消息数
        val avgMsgsPerNormalFrame = if (normalFrames.isNotEmpty()) {
            var totalMsgsInNormalFrames = 0
            for (frame in normalFrames.take(200)) { // 采样前 200 帧避免性能问题
                val frameEnd = frame.timestampMs
                val frameStart = frameActualStart[frameEnd] ?: (frameEnd - frame.totalDurationMs.toLong())
                totalMsgsInNormalFrames += allMessages.count { msg ->
                    val msgEnd = msg.startTimeMs + msg.durationMs
                    msg.startTimeMs < frameEnd && msgEnd > frameStart
                }
            }
            totalMsgsInNormalFrames.toDouble() / minOf(normalFrames.size, 200)
        } else 0.0

        // 基于栈采样的掉帧归因 — 对比掉帧帧 vs 正常帧中每个方法的采样密度
        val stackBasedContributors = computeStackBasedAttribution(
            frames, jankThresholdMs, stackSamples
        )

        return JankFrameAttribution(
            totalJankFrames = jankFrames.size,
            singleSlowMsgFrames = singleSlowMsgFrames,
            multiMsgStackFrames = multiMsgStackFrames,
            avgMsgsPerJankFrame = totalMsgsInJankFrames.toDouble() / jankFrames.size,
            avgMsgsPerNormalFrame = avgMsgsPerNormalFrame,
            avgOverbudgetMs = totalOverbudgetMs / jankFrames.size,
            topMultiMsgContributors = topContributors,
            pureAccumulationFrames = pureAccumulationFrames,
            stackBasedContributors = stackBasedContributors
        )
    }

    /**
     * 基于栈采样的掉帧归因 — 从每个栈样本中提取所有层级的方法，
     * 对比掉帧帧 vs 正常帧中各方法的出现频率。
     * 包含系统关键方法（doFrame/performTraversals/performDraw）和 app 方法。
     */
    private fun computeStackBasedAttribution(
        frames: List<FrameData>,
        jankThresholdMs: Double,
        stackSamples: List<StackSampler.StackSample>
    ): List<StackBasedJankContributor> {
        if (stackSamples.isEmpty() || frames.size < 2) return emptyList()

        val samplingIntervalMs = 5.0

        // 按时间排序帧，计算真实墙钟帧边界（用相邻 doFrame 时间戳差）
        val sortedFrames = frames.sortedBy { it.timestampMs }
        val jankFrames = sortedFrames.filter { it.totalDurationMs > jankThresholdMs }
        val normalFrames = sortedFrames.filter { it.totalDurationMs <= jankThresholdMs }
        if (jankFrames.isEmpty()) return emptyList()

        // 预计算帧的实际开始时间: frame[i] 的实际开始 = 前一帧的 timestampMs
        // 因为 timestampMs = doFrame 被调用的墙钟时间，帧 i 覆盖 [prev.timestampMs, this.timestampMs]
        val frameActualStart = mutableMapOf<Long, Long>() // frameEnd -> actualStart
        for (i in sortedFrames.indices) {
            val frameEnd = sortedFrames[i].timestampMs
            val actualStart = if (i > 0) {
                sortedFrames[i - 1].timestampMs
            } else {
                frameEnd - sortedFrames[i].totalDurationMs.toLong()
            }
            frameActualStart[frameEnd] = actualStart
        }

        // 按时间排序采样，用于二分查找
        val sortedSamples = stackSamples.sortedBy { it.timestampMs }
        val sampleTimestamps = sortedSamples.map { it.timestampMs }.toLongArray()

        // 系统关键方法白名单（只保留阶段级方法，去掉泛化的 draw）
        val systemKeyMethods = setOf(
            "doFrame", "performTraversals", "performDraw", "performLayout",
            "nSyncAndDrawFrame", "performMeasure"
        )

        // 记录哪些方法是 app 代码
        val appMethodSet = mutableSetOf<String>()

        // 从一个栈样本中提取**叶子方法**（最深处的有意义方法）
        // 并标记该 sample 是否包含 app 代码
        data class SampleExtraction(val leafMethod: String?, val hasAppCode: Boolean)

        fun extractLeafMethod(sample: StackSampler.StackSample): SampleExtraction {
            var hasAppCode = false
            var deepestApp: String? = null
            var deepestSystemKey: String? = null

            // stackTrace 通常从 top（最深）到 bottom（Thread.run）
            for (frame in sample.stackTrace) {
                val cls = frame.className
                // 跳过基础底层帧
                if (cls == "dalvik.system.VMStack" || cls == "java.lang.Thread" ||
                    cls.startsWith("android.os.Looper") || cls.startsWith("android.os.Handler") ||
                    cls == "com.android.internal.os.ZygoteInit" ||
                    cls.startsWith("com.android.internal.os.RuntimeInit") ||
                    cls.startsWith("java.lang.reflect.")) continue

                val method = frame.methodName
                val shortClass = cls.substringAfterLast('.')

                // App / 三方代码判断（排除系统框架 + JDK + 标准库 + perfettokit）
                val isApp = !cls.startsWith("android.") && !cls.startsWith("androidx.") &&
                    !cls.startsWith("com.android.") && !cls.startsWith("com.google.") &&
                    !cls.startsWith("java.") && !cls.startsWith("javax.") &&
                    !cls.startsWith("kotlin.") && !cls.startsWith("kotlinx.") &&
                    !cls.startsWith("dalvik.") && !cls.startsWith("libcore.") &&
                    !cls.startsWith("sun.") && !cls.startsWith("jdk.") &&
                    !cls.startsWith("org.json.") &&
                    !cls.startsWith("io.github.perfettokit.")

                if (isApp) {
                    hasAppCode = true
                    if (deepestApp == null) {
                        val key = "$shortClass.$method"
                        deepestApp = key
                        appMethodSet.add(key)
                    }
                }

                // 系统关键方法（排除 perfettokit 自身的同名方法）
                if (method in systemKeyMethods && deepestSystemKey == null &&
                    !cls.startsWith("io.github.perfettokit.")) {
                    deepestSystemKey = "$shortClass.$method"
                }
            }

            // 优先返回 app 方法；没有则返回系统关键方法
            val leaf = deepestApp ?: deepestSystemKey
            return SampleExtraction(leaf, hasAppCode)
        }

        // 二分查找帧时间窗内的采样索引范围
        fun findSamplesInWindow(start: Long, end: Long): IntRange {
            var lo = sampleTimestamps.binarySearch(start)
            if (lo < 0) lo = -(lo + 1)
            var hi = sampleTimestamps.binarySearch(end)
            if (hi < 0) hi = -(hi + 1) - 1
            return lo..hi
        }

        // 数据结构：每个方法在每帧中的占比列表
        data class FrameMethodStats(
            val proportions: MutableList<Double> = mutableListOf(), // 在出现帧中的占比
            val absMs: MutableList<Double> = mutableListOf()        // 在出现帧中的绝对耗时
        )

        val jankMethodStats = mutableMapOf<String, FrameMethodStats>()
        val normalMethodStats = mutableMapOf<String, FrameMethodStats>()

        // 追踪无 app 代码的掉帧帧中系统方法统计
        val pureSystemJankMethods = mutableMapOf<String, Int>() // method -> 出现帧数
        var pureSystemJankFrameCount = 0

        // 统计掉帧帧
        var jankFramesWithSamples = 0
        for (frame in jankFrames) {
            val frameEnd = frame.timestampMs
            val frameStart = frameActualStart[frameEnd] ?: (frameEnd - frame.totalDurationMs.toLong())
            val range = findSamplesInWindow(frameStart, frameEnd)
            if (range.isEmpty()) continue

            val validIndices = range.filter { it in sortedSamples.indices }
            val totalSamplesInFrame = validIndices.size
            if (totalSamplesInFrame == 0) continue
            jankFramesWithSamples++

            val methodCountsInFrame = mutableMapOf<String, Int>()
            var frameHasAppCode = false

            for (i in validIndices) {
                val extraction = extractLeafMethod(sortedSamples[i])
                if (extraction.hasAppCode) frameHasAppCode = true
                val leaf = extraction.leafMethod ?: continue
                methodCountsInFrame[leaf] = (methodCountsInFrame[leaf] ?: 0) + 1
            }

            // 记录每个方法的占比
            for ((method, count) in methodCountsInFrame) {
                val proportion = count.toDouble() / totalSamplesInFrame
                val ms = count * samplingIntervalMs
                val stats = jankMethodStats.getOrPut(method) { FrameMethodStats() }
                stats.proportions.add(proportion)
                stats.absMs.add(ms)
            }

            // 无 app 代码的掉帧帧 → 记录系统热点
            if (!frameHasAppCode) {
                pureSystemJankFrameCount++
                for (method in methodCountsInFrame.keys) {
                    pureSystemJankMethods[method] = (pureSystemJankMethods[method] ?: 0) + 1
                }
            }
        }

        // 统计正常帧（最多200帧）
        val normalSampleSize = minOf(normalFrames.size, 200)
        var normalFramesWithSamples = 0
        for (frame in normalFrames.take(normalSampleSize)) {
            val frameEnd = frame.timestampMs
            val frameStart = frameActualStart[frameEnd] ?: (frameEnd - frame.totalDurationMs.toLong())
            val range = findSamplesInWindow(frameStart, frameEnd)
            if (range.isEmpty()) continue

            val validIndices = range.filter { it in sortedSamples.indices }
            val totalSamplesInFrame = validIndices.size
            if (totalSamplesInFrame == 0) continue
            normalFramesWithSamples++

            val methodCountsInFrame = mutableMapOf<String, Int>()
            for (i in validIndices) {
                val extraction = extractLeafMethod(sortedSamples[i])
                val leaf = extraction.leafMethod ?: continue
                methodCountsInFrame[leaf] = (methodCountsInFrame[leaf] ?: 0) + 1
            }

            for ((method, count) in methodCountsInFrame) {
                val proportion = count.toDouble() / totalSamplesInFrame
                val ms = count * samplingIntervalMs
                val stats = normalMethodStats.getOrPut(method) { FrameMethodStats() }
                stats.proportions.add(proportion)
                stats.absMs.add(ms)
            }
        }

        if (jankFramesWithSamples == 0) return emptyList()

        // 计算每个方法的掉帧/正常占比比值
        val results = mutableListOf<StackBasedJankContributor>()

        for ((method, jankStats) in jankMethodStats) {
            if (jankStats.proportions.size < 3) continue // 至少出现3帧

            val jankProportion = jankStats.proportions.sum() / jankFramesWithSamples
            val jankAvgMs = jankStats.absMs.sum() / jankFramesWithSamples
            val maxMs = jankStats.absMs.max()

            val normalStats = normalMethodStats[method]
            val normalProportion = if (normalStats != null && normalFramesWithSamples > 0) {
                normalStats.proportions.sum() / normalFramesWithSamples
            } else 0.0

            // 占比比值：消除帧长度偏差
            val proportionRatio = if (normalProportion > 0.01) {
                jankProportion / normalProportion
            } else if (jankProportion > 0.02) {
                // 正常帧几乎不出现但掉帧帧有明显占比 → 高嫌疑
                jankProportion / 0.01
            } else {
                1.0 // 两边都很少，无意义
            }

            val appearanceRate = jankStats.proportions.size.toDouble() / jankFramesWithSamples
            val isApp = method in appMethodSet

            results.add(StackBasedJankContributor(
                method = method,
                jankProportion = jankProportion,
                normalProportion = normalProportion,
                proportionRatio = proportionRatio,
                jankFrameAvgMs = jankAvgMs,
                maxEstimatedMs = maxMs,
                jankFrameAppearanceRate = appearanceRate,
                isAppMethod = isApp
            ))
        }

        // 纯系统热点：在无 app 代码的掉帧帧中高频出现的系统方法
        val pureSystemHotspots = if (pureSystemJankFrameCount >= 3) {
            pureSystemJankMethods.entries
                .filter { it.value >= 3 && it.key !in appMethodSet }
                .sortedByDescending { it.value }
                .take(3)
                .map { (method, count) ->
                    val jankStats = jankMethodStats[method]
                    val avgMs = jankStats?.absMs?.average() ?: 0.0
                    val maxMs = jankStats?.absMs?.max() ?: 0.0
                    StackBasedJankContributor(
                        method = method,
                        jankProportion = count.toDouble() / pureSystemJankFrameCount,
                        normalProportion = 0.0,
                        proportionRatio = 0.0,
                        jankFrameAvgMs = avgMs,
                        maxEstimatedMs = maxMs,
                        jankFrameAppearanceRate = count.toDouble() / jankFramesWithSamples,
                        isAppMethod = false,
                        isPureSystemHotspot = true
                    )
                }
        } else emptyList()

        // 筛选：占比比值 > 1.5 (方法在掉帧时占比明显高于正常帧)
        // 排序：按掉帧帧中的时间占比 × 出现率 (实际影响权重)
        val filtered = results
            .filter { it.proportionRatio > 1.5 && it.jankProportion > 0.03 }
            .sortedByDescending { it.jankProportion * it.jankFrameAppearanceRate }
            .take(8)

        return (filtered + pureSystemHotspots).distinctBy { it.method }
    }

    /**
     * 计算帧阶段统计 — 基于 FrameMetrics API 数据，分析掉帧瓶颈在哪个阶段。
     */
    private fun computeFramePhaseStats(
        phaseData: List<FramePhaseData>,
        frames: List<FrameData>
    ): FramePhaseStats {
        if (phaseData.isEmpty()) return FramePhaseStats()

        val jankThresholdMs = detectJankThreshold(frames)

        // 筛出掉帧帧 (FrameMetrics 自己的 totalMs > 阈值)
        val jankPhaseFrames = phaseData.filter { it.totalMs > jankThresholdMs }

        // 统计各阶段平均耗时 (所有帧)
        val avgInput = phaseData.map { it.inputMs }.average()
        val avgAnimation = phaseData.map { it.animationMs }.average()
        val avgLayout = phaseData.map { it.layoutMs }.average()
        val avgDraw = phaseData.map { it.drawMs }.average()
        val avgSync = phaseData.map { it.syncMs }.average()
        val avgCommand = phaseData.map { it.commandMs }.average()

        // 掉帧帧的瓶颈阶段分布
        val phaseBreakdown = if (jankPhaseFrames.isNotEmpty()) {
            val grouped = jankPhaseFrames.groupBy { it.bottleneckPhase }
            FramePhase.values()
                .filter { it != FramePhase.UNKNOWN }
                .mapNotNull { phase ->
                    val group = grouped[phase] ?: return@mapNotNull null
                    val avgMs = when (phase) {
                        FramePhase.INPUT -> group.map { it.inputMs }.average()
                        FramePhase.ANIMATION -> group.map { it.animationMs }.average()
                        FramePhase.LAYOUT -> group.map { it.layoutMs }.average()
                        FramePhase.DRAW -> group.map { it.drawMs }.average()
                        FramePhase.SYNC -> group.map { it.syncMs }.average()
                        FramePhase.COMMAND -> group.map { it.commandMs }.average()
                        else -> 0.0
                    }
                    PhaseBottleneckEntry(
                        phase = phase,
                        jankCount = group.size,
                        percentage = group.size.toDouble() / jankPhaseFrames.size * 100,
                        avgMs = avgMs
                    )
                }
                .sortedByDescending { it.percentage }
        } else emptyList()

        return FramePhaseStats(
            totalFrames = phaseData.size,
            jankFrames = jankPhaseFrames.size,
            phaseBreakdown = phaseBreakdown,
            avgInputMs = avgInput,
            avgAnimationMs = avgAnimation,
            avgLayoutMs = avgLayout,
            avgDrawMs = avgDraw,
            avgSyncMs = avgSync,
            avgCommandMs = avgCommand
        )
    }
}
