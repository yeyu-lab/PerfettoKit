package io.github.perfettokit.collector

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 对象分配追踪器 — 统计 Session 期间的对象分配情况。
 *
 * 原理: Debug.getGlobalAllocCount() / getGlobalAllocSize()
 * 用于发现: 高频对象创建 → GC 压力 → 卡顿
 *
 * 典型问题:
 * - onDraw() 里 new Paint/Path
 * - 列表滑动时频繁创建临时对象
 * - String 拼接导致大量短命对象
 */
class AllocationTracker {

    private var startAllocCount: Int = 0
    private var startAllocSize: Int = 0
    private var tracking = false
    private val samples = mutableListOf<AllocSample>()
    private var scheduledFuture: ScheduledFuture<*>? = null

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "PerfettoKit-Alloc").apply { isDaemon = true }
    }

    fun start() {
        // 重置全局计数器
        Debug.resetGlobalAllocCount()
        Debug.resetGlobalAllocSize()

        // 启用分配计数（低开销）
        Debug.startAllocCounting()

        startAllocCount = Debug.getGlobalAllocCount()
        startAllocSize = Debug.getGlobalAllocSize()
        tracking = true
        samples.clear()

        // 每 200ms 采样一次分配速率
        var lastCount = startAllocCount
        var lastSize = startAllocSize

        scheduledFuture = executor.scheduleAtFixedRate({
            if (!tracking) return@scheduleAtFixedRate
            try {
                val currentCount = Debug.getGlobalAllocCount()
                val currentSize = Debug.getGlobalAllocSize()
                val deltaCount = currentCount - lastCount
                val deltaSize = currentSize - lastSize
                lastCount = currentCount
                lastSize = currentSize

                samples.add(AllocSample(
                    timestampMs = SystemClock.elapsedRealtime(),
                    allocCountPerInterval = deltaCount,
                    allocBytesPerInterval = deltaSize
                ))
            } catch (e: Exception) {
                // Debug.getGlobalAllocCount 在某些 ROM 上可能不可用
            }
        }, 200, 200, TimeUnit.MILLISECONDS)
    }

    fun stop(): List<AllocSample> {
        tracking = false
        scheduledFuture?.cancel(false)
        Debug.stopAllocCounting()
        return samples.toList()
    }

    fun computeStats(samples: List<AllocSample>): AllocationStats {
        val totalCount = Debug.getGlobalAllocCount() - startAllocCount
        val totalSize = Debug.getGlobalAllocSize() - startAllocSize

        val peakAllocRate = if (samples.isNotEmpty()) {
            samples.maxOf { it.allocCountPerInterval } * 5  // 200ms → per sec
        } else 0

        val avgAllocRate = if (samples.isNotEmpty()) {
            (samples.sumOf { it.allocCountPerInterval } / samples.size) * 5
        } else 0

        return AllocationStats(
            totalAllocCount = totalCount,
            totalAllocBytes = totalSize,
            peakAllocPerSec = peakAllocRate,
            avgAllocPerSec = avgAllocRate,
            sampleCount = samples.size
        )
    }
}

data class AllocSample(
    val timestampMs: Long,
    val allocCountPerInterval: Int,  // 该采样间隔的分配次数
    val allocBytesPerInterval: Int   // 该采样间隔的分配字节
)

data class AllocationStats(
    val totalAllocCount: Int = 0,     // 总分配次数
    val totalAllocBytes: Int = 0,     // 总分配字节
    val peakAllocPerSec: Int = 0,     // 峰值分配速率 (次/秒)
    val avgAllocPerSec: Int = 0,      // 平均分配速率 (次/秒)
    val sampleCount: Int = 0
) {
    val totalAllocKB: Int get() = totalAllocBytes / 1024
    val isHighPressure: Boolean get() = peakAllocPerSec > 10000  // 每秒万次以上算高压
}
