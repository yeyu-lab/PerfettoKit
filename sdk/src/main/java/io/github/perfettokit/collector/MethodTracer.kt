package io.github.perfettokit.collector

import android.os.SystemClock
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
     * inline 方式：自动计时并返回原函数结果
     */
    inline fun <T> trace(tag: String, block: () -> T): T {
        if (!enabled) return block()
        val startNs = System.nanoTime()
        val startMs = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            val durationMs = (System.nanoTime() - startNs) / 1_000_000.0
            record(tag, startMs, durationMs)
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

    @PublishedApi
    internal fun record(tag: String, startTimeMs: Long, durationMs: Double) {
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
    }
}
