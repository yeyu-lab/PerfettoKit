package io.github.perfettokit

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量级方法耗时追踪器 — 每次调用都记录，支持历史统计和超阈值告警。
 *
 * 用法:
 * ```kotlin
 * // 方式一：inline lambda（推荐）
 * val result = MethodTracer.trace("MyAdapter.onBind") {
 *     bindData(holder, position)
 * }
 *
 * // 方式二：手动 begin/end（跨作用域）
 * val token = MethodTracer.begin("loadImage")
 * // ... async work ...
 * MethodTracer.end(token)
 *
 * // 查看统计
 * MethodTracer.dump()       // 输出所有方法统计到 Logcat
 * MethodTracer.getStats()   // 获取原始数据
 * MethodTracer.reset()      // 清空历史
 * ```
 */
object MethodTracer {

    private const val TAG = "PerfettoKit.Trace"

    /**
     * 告警阈值（毫秒），超过此值打 WARN 日志。默认 16ms（一帧）。
     */
    @Volatile
    var warnThresholdMs: Long = 16L

    /**
     * 是否启用。关闭后 trace 几乎零开销（仅一次 if 判断）。
     */
    @Volatile
    var enabled: Boolean = true

    /**
     * 最大记录条数（每个 tag），防止内存无限增长。默认保留最近 200 条。
     */
    var maxRecordsPerTag: Int = 200

    private val stats = ConcurrentHashMap<String, MethodStats>()

    /**
     * 追踪一个代码块的耗时。每次调用都会记录。
     */
    inline fun <T> trace(tag: String, block: () -> T): T {
        if (!enabled) return block()
        val start = SystemClock.elapsedRealtimeNanos()
        val result = block()
        val durationNs = SystemClock.elapsedRealtimeNanos() - start
        record(tag, durationNs)
        return result
    }

    /**
     * 手动开始计时，返回 token。
     */
    fun begin(tag: String): TraceToken {
        return TraceToken(tag, SystemClock.elapsedRealtimeNanos())
    }

    /**
     * 手动结束计时。
     */
    fun end(token: TraceToken) {
        if (!enabled) return
        val durationNs = SystemClock.elapsedRealtimeNanos() - token.startNs
        record(token.tag, durationNs)
    }

    /**
     * 记录一次耗时（纳秒）。
     */
    fun record(tag: String, durationNs: Long) {
        val durationMs = durationNs / 1_000_000.0
        val stat = stats.getOrPut(tag) { MethodStats(tag) }
        stat.add(durationNs)

        if (durationMs > warnThresholdMs) {
            Log.w(TAG, "⚠️ [$tag] ${String.format("%.2f", durationMs)}ms (超过阈值 ${warnThresholdMs}ms)" +
                    " | 累计${stat.count}次, 均${String.format("%.2f", stat.avgMs())}ms, 峰值${String.format("%.2f", stat.maxMs())}ms")
        } else {
            Log.v(TAG, "[$tag] ${String.format("%.2f", durationMs)}ms")
        }
    }

    /**
     * 获取某个 tag 的统计。
     */
    fun getStats(tag: String): MethodStats? = stats[tag]

    /**
     * 获取所有统计。
     */
    fun getStats(): Map<String, MethodStats> = stats.toMap()

    /**
     * 输出所有方法统计到 Logcat（按平均耗时降序）。
     */
    fun dump() {
        if (stats.isEmpty()) {
            Log.i(TAG, "MethodTracer: 暂无记录")
            return
        }
        Log.i(TAG, "━━━ 方法耗时统计 ━━━")
        stats.values
            .sortedByDescending { it.avgMs() }
            .forEach { stat ->
                Log.i(TAG, "  ${stat.tag}: " +
                        "调用${stat.count}次, " +
                        "均${String.format("%.2f", stat.avgMs())}ms, " +
                        "峰值${String.format("%.2f", stat.maxMs())}ms, " +
                        "总${String.format("%.1f", stat.totalMs())}ms" +
                        if (stat.warnCount > 0) " ⚠️超阈值${stat.warnCount}次" else "")
            }
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━")
    }

    /**
     * 清空所有记录。
     */
    fun reset() {
        stats.clear()
    }

    /**
     * 清空某个 tag 的记录。
     */
    fun reset(tag: String) {
        stats.remove(tag)
    }

    data class TraceToken(val tag: String, val startNs: Long)

    /**
     * 单个方法的统计数据。
     */
    class MethodStats(val tag: String) {
        @Volatile var count: Long = 0L
            private set
        @Volatile var totalNs: Long = 0L
            private set
        @Volatile var maxNs: Long = 0L
            private set
        @Volatile var minNs: Long = Long.MAX_VALUE
            private set
        @Volatile var warnCount: Long = 0L
            private set

        // 最近 N 条记录（环形缓冲）
        private val recentRecords = LongArray(200)
        private var writeIndex = 0

        @Synchronized
        fun add(durationNs: Long) {
            count++
            totalNs += durationNs
            if (durationNs > maxNs) maxNs = durationNs
            if (durationNs < minNs) minNs = durationNs
            if (durationNs / 1_000_000 > MethodTracer.warnThresholdMs) warnCount++

            recentRecords[writeIndex % recentRecords.size] = durationNs
            writeIndex++
        }

        fun avgMs(): Double = if (count == 0L) 0.0 else (totalNs.toDouble() / count / 1_000_000)
        fun maxMs(): Double = maxNs.toDouble() / 1_000_000
        fun minMs(): Double = if (minNs == Long.MAX_VALUE) 0.0 else minNs.toDouble() / 1_000_000
        fun totalMs(): Double = totalNs.toDouble() / 1_000_000

        /**
         * 获取最近的记录（毫秒）。
         */
        fun recentMs(): List<Double> {
            val size = minOf(count.toInt(), recentRecords.size)
            return (0 until size).map { i ->
                recentRecords[(writeIndex - size + i + recentRecords.size) % recentRecords.size].toDouble() / 1_000_000
            }
        }
    }
}
