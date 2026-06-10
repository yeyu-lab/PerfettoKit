package io.github.perfettokit.collector

import android.os.SystemClock
import android.util.Log
import io.github.perfettokit.analyzer.MethodTracker
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局方法插桩入口（static 单例）。
 *
 * 不需要持有 session 引用，自动归集到当前活跃 session 的 MethodTracker。
 *
 * 用法 1 — inline 块:
 *   MethodTracer.trace("drawTrackEvent") {
 *       // 原有逻辑
 *   }
 *
 * 用法 2 — 手动 begin/end:
 *   MethodTracer.begin("drawTrackEvent")
 *   // ...
 *   MethodTracer.end("drawTrackEvent")
 */
object MethodTracer {

    private const val TAG = "PerfettoKit.Trace"
    private const val MAX_RECORDS = 5000

    // 当有活跃 session 时，直接委托给它的 MethodTracker
    @Volatile
    internal var activeTracker: MethodTracker? = null

    // thread-local start time stack (支持嵌套)
    private val startStack = ThreadLocal<ArrayDeque<Pair<String, Long>>>()

    // 无 session 时的本地缓存（session 启动时会 drain）
    private val pendingRecords = mutableListOf<MethodTracker.MethodRecord>()
    private val pendingLock = Any()

    var enabled: Boolean = true

    /**
     * 全局默认告警阈值（毫秒）。超过此值打 WARN 日志。默认 16ms。
     */
    @Volatile
    var warnThresholdMs: Long = 16L

    /**
     * inline 方式：自动计时并返回原函数结果。
     * @param thresholdMs 可选告警阈值，不传则使用全局 warnThresholdMs。
     */
    inline fun <T> trace(tag: String, thresholdMs: Long? = null, block: () -> T): T {
        if (!enabled) return block()
        val startNs = System.nanoTime()
        val startMs = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            val durationMs = (System.nanoTime() - startNs) / 1_000_000.0
            record(tag, startMs, durationMs, thresholdMs)
        }
    }

    /**
     * 手动标记开始
     */
    fun begin(tag: String) {
        if (!enabled) return
        val stack = startStack.get() ?: ArrayDeque<Pair<String, Long>>().also { startStack.set(it) }
        stack.addLast(tag to System.nanoTime())
    }

    /**
     * 手动标记结束
     */
    fun end(tag: String) {
        if (!enabled) return
        val stack = startStack.get() ?: return
        val idx = stack.indexOfLast { it.first == tag }
        if (idx < 0) return
        val (_, startNs) = stack.removeAt(idx)
        val durationMs = (System.nanoTime() - startNs) / 1_000_000.0
        // 使用 elapsedRealtime() 作为 wall-clock startMs（与其他采集器对齐）
        val startMs = SystemClock.elapsedRealtime() - durationMs.toLong()
        record(tag, startMs, durationMs)
    }

    // 每个 tag 的调用次数和总耗时（用于计算平均值）
    private val callStats = ConcurrentHashMap<String, LongArray>() // [count, totalNs(x100)]

    @PublishedApi
    internal fun record(tag: String, startTimeMs: Long, durationMs: Double, thresholdMs: Long? = null) {
        // 更新统计
        val arr = callStats.getOrPut(tag) { LongArray(2) }
        synchronized(arr) {
            arr[0]++
            arr[1] += (durationMs * 100).toLong() // 保留2位小数精度
        }
        val count = arr[0]
        val avgMs = arr[1].toDouble() / (count * 100)

        val threshold = thresholdMs ?: warnThresholdMs
        if (durationMs > threshold) {
            Log.w(TAG, "⚠️ [$tag] ${"%.2f".format(durationMs)}ms (阈值${threshold}ms) | 第${count}次, 均${"%.2f".format(avgMs)}ms")
        } else {
            Log.v(TAG, "[$tag] ${"%.2f".format(durationMs)}ms | 第${count}次, 均${"%.2f".format(avgMs)}ms")
        }

        val tracker = activeTracker
        if (tracker != null) {
            // 委托给活跃 session 的 MethodTracker
            tracker.addRecord(tag, startTimeMs, durationMs)
        } else {
            // 暂存
            synchronized(pendingLock) {
                if (pendingRecords.size < MAX_RECORDS) {
                    pendingRecords.add(
                        MethodTracker.MethodRecord(
                            method = tag,
                            startMs = startTimeMs,
                            durationMs = durationMs,
                            threadName = Thread.currentThread().name
                        )
                    )
                }
            }
        }
    }

    /**
     * 绑定活跃 session — 将暂存记录转移并后续直接委托。
     */
    internal fun bind(tracker: MethodTracker) {
        synchronized(pendingLock) {
            pendingRecords.forEach { tracker.addRecord(it.method, it.startMs, it.durationMs) }
            pendingRecords.clear()
        }
        activeTracker = tracker
    }

    /**
     * 解绑 session。
     */
    internal fun unbind() {
        activeTracker = null
    }

    fun reset() {
        synchronized(pendingLock) { pendingRecords.clear() }
        callStats.clear()
    }
}
