package io.github.perfettokit.analyzer

import android.os.SystemClock

/**
 * 方法耗时追踪器。
 *
 * 开发者在可疑方法中手动插桩，SDK 自动关联到当前 Session 的帧数据。
 *
 * 用法:
 *   val tracker = session.methodTracker
 *   tracker.begin("MyAdapter.onBindViewHolder")
 *   // ... 执行逻辑 ...
 *   tracker.end("MyAdapter.onBindViewHolder")
 *
 * 或使用 inline 版:
 *   tracker.trace("loadBitmap") { ... }
 */
class MethodTracker {

    private val records = mutableListOf<MethodRecord>()
    private val pending = mutableMapOf<String, Long>()  // method → startTime

    data class MethodRecord(
        val method: String,
        val startMs: Long,
        val durationMs: Double,
        val threadName: String
    )

    fun begin(method: String) {
        pending[method] = SystemClock.elapsedRealtime()
    }

    fun end(method: String) {
        val startMs = pending.remove(method) ?: return
        val durationMs = (SystemClock.elapsedRealtime() - startMs).toDouble()
        synchronized(records) {
            records.add(
                MethodRecord(
                    method = method,
                    startMs = startMs,
                    durationMs = durationMs,
                    threadName = Thread.currentThread().name
                )
            )
        }
    }

    /**
     * 直接添加一条记录（供全局 MethodTracer 委托调用）。
     */
    fun addRecord(method: String, startMs: Long, durationMs: Double) {
        synchronized(records) {
            records.add(
                MethodRecord(
                    method = method,
                    startMs = startMs,
                    durationMs = durationMs,
                    threadName = Thread.currentThread().name
                )
            )
        }
    }

    inline fun <T> trace(method: String, block: () -> T): T {
        begin(method)
        val result = block()
        end(method)
        return result
    }

    fun getRecords(): List<MethodRecord> = synchronized(records) { records.toList() }

    /**
     * 获取耗时 Top N 的方法。
     */
    fun getTopMethods(n: Int = 5): List<MethodRecord> {
        return getRecords().sortedByDescending { it.durationMs }.take(n)
    }

    fun clear() {
        synchronized(records) { records.clear() }
        pending.clear()
    }
}
