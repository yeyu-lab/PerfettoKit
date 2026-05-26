package io.github.perfettokit.collector

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock

/**
 * 内存与 GC 采集器。
 *
 * 采集维度:
 * - Java Heap 使用量 / 最大值
 * - Native Heap 使用量
 * - 系统可用内存
 * - GC 次数与耗时 (通过 Debug.getRuntimeStat)
 */
class MemoryCollector(
    private val context: Context? = null,
    private val intervalMs: Long = 500L
) {
    private val samples = mutableListOf<MemorySample>()
    private var running = false
    private var samplerThread: Thread? = null
    private var startGcCount = 0L
    private var startGcTimeMs = 0L

    fun start() {
        if (running) return
        running = true
        samples.clear()
        startGcCount = getGcCount()
        startGcTimeMs = getGcTimeMs()

        samplerThread = Thread({
            while (running) {
                try {
                    val sample = takeSample()
                    synchronized(samples) { samples.add(sample) }
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "PerfettoKit-Memory").also { it.isDaemon = true; it.start() }
    }

    fun stop(): List<MemorySample> {
        running = false
        samplerThread?.interrupt()
        samplerThread = null
        return synchronized(samples) { samples.toList() }
    }

    /**
     * 计算 Session 期间的内存统计。
     */
    fun computeStats(samples: List<MemorySample>): MemoryStats {
        if (samples.isEmpty()) return MemoryStats()

        val javaHeapUsages = samples.map { it.javaHeapUsedKb }
        val nativeHeapUsages = samples.map { it.nativeHeapUsedKb }

        val gcCount = getGcCount() - startGcCount
        val gcTimeMs = getGcTimeMs() - startGcTimeMs

        return MemoryStats(
            javaHeapAvgKb = javaHeapUsages.average().toLong(),
            javaHeapMaxKb = javaHeapUsages.maxOrNull() ?: 0L,
            javaHeapMaxLimitKb = samples.firstOrNull()?.javaHeapMaxKb ?: 0L,
            nativeHeapAvgKb = nativeHeapUsages.average().toLong(),
            nativeHeapMaxKb = nativeHeapUsages.maxOrNull() ?: 0L,
            gcCount = gcCount,
            gcTotalTimeMs = gcTimeMs,
            memoryGrowthKb = if (samples.size >= 2)
                samples.last().javaHeapUsedKb - samples.first().javaHeapUsedKb else 0L,
            sampleCount = samples.size
        )
    }

    private fun takeSample(): MemorySample {
        val runtime = Runtime.getRuntime()
        val javaMax = runtime.maxMemory() / 1024
        val javaTotal = runtime.totalMemory() / 1024
        val javaFree = runtime.freeMemory() / 1024
        val javaUsed = javaTotal - javaFree

        // 通过 freeMemory 变化检测 GC
        detectGcFromFreeMem(runtime.freeMemory())

        val nativeHeap = Debug.getNativeHeapAllocatedSize() / 1024

        val systemAvailKb = getSystemAvailableMemory()

        return MemorySample(
            timestampMs = SystemClock.elapsedRealtime(),
            javaHeapUsedKb = javaUsed,
            javaHeapMaxKb = javaMax,
            nativeHeapUsedKb = nativeHeap,
            systemAvailableKb = systemAvailKb
        )
    }

    private fun getSystemAvailableMemory(): Long {
        val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.availMem / 1024
    }

    private var lastFreeMemory: Long = 0L
    private var gcCountByFreeMemory: Long = 0L

    private fun getGcCount(): Long {
        // 方案 1: Debug.getRuntimeStat (ART, API 23+)
        try {
            val stat = Debug.getRuntimeStat("art.gc.gc-count")
            if (!stat.isNullOrEmpty()) {
                val value = stat.toLongOrNull()
                if (value != null && value > 0) return value
            }
        } catch (_: Exception) {}

        // 方案 2: 通过 freeMemory 突增检测 GC（free 突然变大 = GC 回收了对象）
        return gcCountByFreeMemory
    }

    private fun getGcTimeMs(): Long {
        // 方案 1: Debug.getRuntimeStat
        try {
            val stat = Debug.getRuntimeStat("art.gc.gc-time")
            if (!stat.isNullOrEmpty()) {
                val value = stat.toLongOrNull()
                if (value != null && value > 0) return value
            }
        } catch (_: Exception) {}

        // 无法获取精确 GC 时间时，用 GC 次数 * 估算平均时间
        return gcCountByFreeMemory * 5  // 估算每次 GC 约 5ms
    }

    /**
     * 在每次采样时检测 GC 是否发生。
     * 原理: GC 后 freeMemory 会突然增大（回收了垃圾对象释放了空间）。
     */
    internal fun detectGcFromFreeMem(currentFree: Long) {
        if (lastFreeMemory > 0 && currentFree > lastFreeMemory + 512 * 1024) {
            // freeMemory 增加超过 512KB → 很可能发生了 GC
            gcCountByFreeMemory++
        }
        lastFreeMemory = currentFree
    }
}

data class MemorySample(
    val timestampMs: Long,
    val javaHeapUsedKb: Long,
    val javaHeapMaxKb: Long,
    val nativeHeapUsedKb: Long,
    val systemAvailableKb: Long
)

data class MemoryStats(
    val javaHeapAvgKb: Long = 0L,
    val javaHeapMaxKb: Long = 0L,
    val javaHeapMaxLimitKb: Long = 0L,
    val nativeHeapAvgKb: Long = 0L,
    val nativeHeapMaxKb: Long = 0L,
    val gcCount: Long = 0L,
    val gcTotalTimeMs: Long = 0L,
    val memoryGrowthKb: Long = 0L,   // 正值 = 内存增长（可能泄漏）
    val sampleCount: Int = 0
) {
    val heapUsagePercent: Double
        get() = if (javaHeapMaxLimitKb > 0)
            javaHeapMaxKb.toDouble() / javaHeapMaxLimitKb * 100 else 0.0

    val isMemoryPressure: Boolean get() = heapUsagePercent > 80
    val isLikelyLeak: Boolean get() = memoryGrowthKb > 5 * 1024  // 增长超过 5MB
    val isFrequentGc: Boolean get() = gcCount > 5  // Session 内超过 5 次 GC
}
