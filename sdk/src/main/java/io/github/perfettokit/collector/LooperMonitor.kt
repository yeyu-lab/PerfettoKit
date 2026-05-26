package io.github.perfettokit.collector

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Printer

/**
 * Looper 消息监控器 — 精确测量主线程每条消息的处理耗时。
 *
 * 原理:
 *   Looper.setMessageLogging(Printer) 会在每条消息分发前后回调:
 *     >>>>> Dispatching to Handler (xxx) {xxx} callback/what
 *     <<<<< Finished to Handler (xxx) {xxx} callback/what
 *
 * 对比栈采样的优势:
 *   - 100% 覆盖，不存在"漏掉"的问题
 *   - 精确的消息级别耗时（触摸事件、动画回调、Traversal 分别计时）
 *   - 当消息超时时，通过 watchdog 线程抓取真实堆栈
 */
class LooperMonitor {

    private val slowMessages = mutableListOf<SlowMessage>()
    private val allMessages = mutableListOf<MessageTiming>()
    private var running = false
    private var originalPrinter: Printer? = null
    private var watchdogThread: HandlerThread? = null
    private var watchdogHandler: Handler? = null
    private var thresholdMs: Long = 8L  // 默认 8ms (120Hz 帧预算)
    private var totalMessageCount: Long = 0  // 总消息数（所有消息，不只是超时的）

    // 当前消息的状态
    private var msgStartTimeMs: Long = 0L
    private var msgTarget: String = ""
    private var pendingStackCapture: Runnable? = null
    private var capturedStacks: MutableList<Array<StackTraceElement>> = mutableListOf()
    private val mainThread: Thread = Looper.getMainLooper().thread

    private val printer = object : Printer {
        override fun println(msg: String) {
            if (!running) return

            if (msg.startsWith(">>>>>")) {
                onMessageStart(msg)
            } else if (msg.startsWith("<<<<<")) {
                onMessageEnd(msg)
            }
        }
    }

    /**
     * 启动监控。
     * @param thresholdMs 慢消息阈值，超过该时间的消息会被记录。默认 8ms (适配 120Hz)。
     */
    fun start(thresholdMs: Long = 8L) {
        if (running) return
        running = true
        this.thresholdMs = thresholdMs

        // watchdog 线程: 在消息开始后延迟抓栈
        val thread = HandlerThread("PerfettoKit-LooperWatchdog").also { it.start() }
        watchdogThread = thread
        watchdogHandler = Handler(thread.looper)

        // 设置 Looper Printer
        Looper.getMainLooper().setMessageLogging(printer)
    }

    fun stop(): LooperMonitorResult {
        running = false
        Looper.getMainLooper().setMessageLogging(null)
        watchdogHandler?.removeCallbacksAndMessages(null)
        watchdogThread?.quitSafely()
        watchdogThread = null
        watchdogHandler = null
        return LooperMonitorResult(
            slowMessages = synchronized(slowMessages) { slowMessages.toList() },
            allMessages = synchronized(allMessages) { allMessages.toList() },
            totalMessageCount = totalMessageCount
        )
    }

    private fun onMessageStart(msg: String) {
        msgStartTimeMs = SystemClock.elapsedRealtime()
        msgTarget = parseMessageTarget(msg)
        capturedStacks.clear()

        // 调度 watchdog: 在 thresholdMs/2 后抓第一次栈，thresholdMs 后抓第二次
        val handler = watchdogHandler ?: return
        val halfThreshold = thresholdMs / 2

        val capture1 = Runnable { captureStack() }
        val capture2 = Runnable { captureStack() }
        pendingStackCapture = capture1

        handler.postDelayed(capture1, halfThreshold)
        handler.postDelayed(capture2, thresholdMs)
    }

    private fun onMessageEnd(msg: String) {
        val duration = SystemClock.elapsedRealtime() - msgStartTimeMs
        totalMessageCount++

        // 记录所有消息的时间信息（轻量，仅两个 Long）
        synchronized(allMessages) {
            allMessages.add(MessageTiming(startTimeMs = msgStartTimeMs, durationMs = duration))
        }

        // 取消未触发的 watchdog
        watchdogHandler?.removeCallbacksAndMessages(null)

        if (duration >= thresholdMs) {
            // 如果 watchdog 没来得及抓栈（消息刚好超阈值但低于 watchdog 延迟），立即补一次
            if (capturedStacks.isEmpty()) {
                captureStack()
            }

            val category = categorizeMessage(msgTarget)
            synchronized(slowMessages) {
                slowMessages.add(
                    SlowMessage(
                        startTimeMs = msgStartTimeMs,
                        durationMs = duration,
                        target = msgTarget,
                        category = category,
                        stackTraces = capturedStacks.toList()
                    )
                )
            }
        }

        capturedStacks.clear()
        pendingStackCapture = null
    }

    private fun captureStack() {
        if (!running) return
        try {
            val stack = mainThread.stackTrace
            if (stack.isNotEmpty()) {
                synchronized(capturedStacks) {
                    capturedStacks.add(stack)
                }
            }
        } catch (_: Exception) {
            // Thread may not be accessible
        }
    }

    /**
     * 从 Printer 消息中提取 Handler target 信息。
     * 格式: ">>>>> Dispatching to Handler (com.xxx.MyHandler) {hash} com.xxx.MyRunnable@hash"
     */
    private fun parseMessageTarget(msg: String): String {
        return try {
            // 去掉 ">>>>> Dispatching to " 前缀
            val content = msg.removePrefix(">>>>> Dispatching to ")
            content.take(200) // 限制长度
        } catch (_: Exception) {
            msg.take(100)
        }
    }

    /**
     * 根据 Looper message target 判断消息类别。
     */
    private fun categorizeMessage(target: String): MessageCategory {
        return when {
            target.contains("TraversalRunnable") || target.contains("performTraversals") ->
                MessageCategory.TRAVERSAL
            target.contains("Choreographer") || target.contains("FrameHandler") ->
                MessageCategory.ANIMATION
            target.contains("InputEvent") || target.contains("input") ||
                target.contains("MotionEvent") || target.contains("KeyEvent") ->
                MessageCategory.INPUT
            target.contains("ViewRootImpl") ->
                MessageCategory.VIEW
            else -> MessageCategory.OTHER
        }
    }

    /**
     * 计算统计。
     */
    fun computeStats(result: LooperMonitorResult): SlowMessageStats {
        val messages = result.slowMessages
        if (messages.isEmpty()) return SlowMessageStats(totalMessageCount = result.totalMessageCount)

        // 按方法分组统计
        val methodHits = mutableMapOf<String, MutableList<SlowMessage>>()
        for (msg in messages) {
            val appMethod = msg.topAppMethod()
            if (appMethod != null) {
                methodHits.getOrPut(appMethod) { mutableListOf() }.add(msg)
            }
        }

        val topSlowMethods = methodHits.entries
            .sortedByDescending { it.value.size }
            .take(10)
            .map { (method, msgs) ->
                SlowMethodEntry(
                    method = method,
                    hitCount = msgs.size,
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
                CategoryEntry(
                    category = cat,
                    count = msgs.size,
                    totalDurationMs = msgs.sumOf { it.durationMs },
                    avgDurationMs = msgs.map { it.durationMs }.average()
                )
            }
            .sortedByDescending { it.totalDurationMs }

        return SlowMessageStats(
            totalMessageCount = result.totalMessageCount,
            totalSlowMessages = messages.size,
            totalSlowDurationMs = messages.sumOf { it.durationMs },
            maxDurationMs = messages.maxOf { it.durationMs },
            topSlowMethods = topSlowMethods,
            categoryBreakdown = categoryBreakdown
        )
    }
}

/**
 * 慢消息记录。
 */
data class SlowMessage(
    val startTimeMs: Long,
    val durationMs: Long,
    val target: String,          // Handler/Runnable 信息
    val category: MessageCategory,
    val stackTraces: List<Array<StackTraceElement>>  // watchdog 抓取的栈
) {
    /**
     * 从抓取的栈中提取最有代表性的 app 方法。
     */
    fun topAppMethod(): String? {
        for (stack in stackTraces) {
            for (frame in stack) {
                if (isAppFrame(frame)) {
                    return "${frame.className.substringAfterLast('.')}.${frame.methodName}"
                }
            }
        }
        return null
    }

    /**
     * 提取最佳的 app 调用链（真实栈顺序）。
     */
    fun bestAppCallChain(): List<String> {
        // 取含 app 帧最多的那次捕获
        val best = stackTraces.maxByOrNull { stack ->
            stack.count { f -> isAppFrame(f) }
        } ?: return emptyList()

        return best.filter { f -> isAppFrame(f) }
            .take(5)
            .map { f -> "${f.className.substringAfterLast('.')}.${f.methodName}" }
    }
}

/**
 * 判断一个栈帧是否为 app 业务代码（排除系统/框架/三方库）。
 */
private fun isAppFrame(frame: StackTraceElement): Boolean {
    val cls = frame.className
    return !cls.startsWith("android.") &&
        !cls.startsWith("com.android.") &&
        !cls.startsWith("java.") &&
        !cls.startsWith("javax.") &&
        !cls.startsWith("kotlin.") &&
        !cls.startsWith("kotlinx.") &&
        !cls.startsWith("dalvik.") &&
        !cls.startsWith("libcore.") &&
        !cls.startsWith("sun.") &&
        !cls.startsWith("androidx.") &&
        !cls.startsWith("com.google.") &&
        !cls.startsWith("io.github.perfettokit.")
}

/**
 * 消息类别。
 */
enum class MessageCategory(val label: String) {
    INPUT("触摸事件"),
    ANIMATION("动画回调"),
    TRAVERSAL("布局/绘制"),
    VIEW("视图操作"),
    OTHER("其他")
}

/**
 * 单条消息的时间信息（轻量，仅用于帧级归因）。
 */
data class MessageTiming(
    val startTimeMs: Long,
    val durationMs: Long
)

/**
 * LooperMonitor 采集结果。
 */
data class LooperMonitorResult(
    val slowMessages: List<SlowMessage>,
    val allMessages: List<MessageTiming>,
    val totalMessageCount: Long
)

/**
 * 慢消息统计。
 */
data class SlowMessageStats(
    val totalMessageCount: Long = 0,   // Session 期间主线程处理的总消息数
    val totalSlowMessages: Int = 0,    // 超时消息数
    val totalJankFrames: Int = 0,      // 总掉帧数（从帧数据关联）
    val jankFramesCoveredBySlowMsg: Int = 0, // 被慢消息覆盖的掉帧数
    val totalSlowDurationMs: Long = 0,
    val maxDurationMs: Long = 0,
    val topSlowMethods: List<SlowMethodEntry> = emptyList(),
    val categoryBreakdown: List<CategoryEntry> = emptyList(),
    val jankAttribution: JankFrameAttribution = JankFrameAttribution()
) {
    /** 超时率 */
    val slowRatio: Double get() = if (totalMessageCount > 0) totalSlowMessages.toDouble() / totalMessageCount else 0.0
    /** 掉帧覆盖率 — 慢消息能解释多少比例的掉帧 */
    val jankCoverageRatio: Double get() = if (totalJankFrames > 0) jankFramesCoveredBySlowMsg.toDouble() / totalJankFrames else 0.0
}

/**
 * 慢方法条目（由 Looper 消息超时 + 栈抓取确认）。
 */
data class SlowMethodEntry(
    val method: String,
    val hitCount: Int,            // 超时次数
    val jankFrameCount: Int = 0,  // 关联的掉帧数（由 TraceSession 关联计算）
    val avgDurationMs: Double,
    val maxDurationMs: Long,
    val category: MessageCategory,
    val callChain: List<String>
)

/**
 * 按消息类别的分布统计。
 */
data class CategoryEntry(
    val category: MessageCategory,
    val count: Int,
    val totalDurationMs: Long,
    val avgDurationMs: Double
)

/**
 * 帧级掉帧归因 — 每帧掉帧由什么消息组成。
 */
data class JankFrameAttribution(
    val totalJankFrames: Int = 0,
    val singleSlowMsgFrames: Int = 0,    // 单条慢消息(>8ms)导致的掉帧
    val multiMsgStackFrames: Int = 0,    // 多条正常消息累积导致的掉帧
    val avgMsgsPerJankFrame: Double = 0.0, // 掉帧帧平均包含的消息数
    val avgMsgsPerNormalFrame: Double = 0.0, // 正常帧平均包含的消息数
    val avgOverbudgetMs: Double = 0.0,   // 掉帧帧平均超预算多少 ms
    val topMultiMsgContributors: List<CumulativeJankContributor> = emptyList(), // 累积型掉帧中高频出现的方法
    val pureAccumulationFrames: Int = 0,  // 完全无慢消息的累积掉帧帧数
    val stackBasedContributors: List<StackBasedJankContributor> = emptyList() // 基于栈采样的掉帧归因
)

/**
 * 累积型掉帧贡献者 — 在多消息累积掉帧中高频出现的慢方法。
 */
data class CumulativeJankContributor(
    val method: String,           // 方法名
    val appearanceCount: Int,     // 出现在多少帧累积型掉帧中
    val appearanceRate: Double,   // 出现率 = appearanceCount / multiMsgStackFrames
    val avgDurationMs: Double,    // 该方法在累积帧中的平均耗时
    val callChain: List<String> = emptyList()  // 调用链
)
/**
 * 基于栈采样的掉帧方法归因 — 对比掉帧帧 vs 正常帧中方法的时间占比。
 * 使用占比(proportion)而非绝对采样数，消除帧长度偏差。
 */
data class StackBasedJankContributor(
    val method: String,               // 方法名 (ClassName.methodName)
    val jankProportion: Double,       // 掉帧帧中平均时间占比 (0~1)
    val normalProportion: Double,     // 正常帧中平均时间占比 (0~1)
    val proportionRatio: Double,      // 占比比值 = jankProportion / normalProportion (消除帧长偏差)
    val jankFrameAvgMs: Double,       // 掉帧帧中估计平均耗时 ms
    val maxEstimatedMs: Double,       // 单帧最大估计耗时
    val jankFrameAppearanceRate: Double, // 在掉帧帧中出现率
    val isAppMethod: Boolean = false, // 是否为 app/三方代码（非系统框架）
    val isPureSystemHotspot: Boolean = false // 无 app 代码时的系统热点
)