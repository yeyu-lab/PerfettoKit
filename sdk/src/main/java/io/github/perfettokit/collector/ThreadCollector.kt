package io.github.perfettokit.collector

import android.os.SystemClock
import java.io.File

/**
 * 线程活动采集器。
 *
 * 监控:
 * - 进程线程总数变化
 * - 主线程运行/等待/阻塞状态
 * - 线程创建爆发检测
 */
class ThreadCollector(
    private val intervalMs: Long = 200L
) {
    private val pid = android.os.Process.myPid()
    private val samples = mutableListOf<ThreadSample>()
    private var running = false
    private var samplerThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        samples.clear()

        samplerThread = Thread({
            while (running) {
                try {
                    val sample = takeSample()
                    if (sample != null) {
                        synchronized(samples) { samples.add(sample) }
                    }
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "PerfettoKit-Thread").also { it.isDaemon = true; it.start() }
    }

    fun stop(): List<ThreadSample> {
        running = false
        samplerThread?.interrupt()
        samplerThread = null
        return synchronized(samples) { samples.toList() }
    }

    fun computeStats(samples: List<ThreadSample>): ThreadStats {
        if (samples.isEmpty()) return ThreadStats()

        val threadCounts = samples.map { it.threadCount }
        val mainRunnable = samples.count { it.mainThreadState == "R" }
        val avgRunning = samples.map { it.runningThreadCount }.average().toInt()
        val maxRunning = samples.maxOf { it.runningThreadCount }

        return ThreadStats(
            avgThreadCount = threadCounts.average().toInt(),
            maxThreadCount = threadCounts.maxOrNull() ?: 0,
            minThreadCount = threadCounts.minOrNull() ?: 0,
            threadCountGrowth = if (samples.size >= 2)
                samples.last().threadCount - samples.first().threadCount else 0,
            mainThreadRunnableRatio = if (samples.isNotEmpty())
                mainRunnable.toDouble() / samples.size else 0.0,
            avgRunningThreadCount = avgRunning,
            maxRunningThreadCount = maxRunning,
            sampleCount = samples.size
        )
    }

    private var sampleCount = 0

    private fun takeSample(): ThreadSample? {
        return try {
            val taskDir = File("/proc/$pid/task")
            val threadCount = taskDir.list()?.size ?: Thread.activeCount()

            // 读取主线程状态（轻量：只读1个文件）
            val mainState = readThreadState(pid)

            // 活跃线程数：每5次采样才全量扫描一次（降低开销）
            sampleCount++
            val runningCount = if (sampleCount % 5 == 0) {
                countRunningThreads(taskDir)
            } else {
                // 非扫描轮次用上次的值
                synchronized(samples) { samples.lastOrNull()?.runningThreadCount ?: 0 }
            }

            ThreadSample(
                timestampMs = SystemClock.elapsedRealtime(),
                threadCount = threadCount,
                mainThreadState = mainState,
                runningThreadCount = runningCount
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 读取线程状态: R(running), S(sleeping), D(disk sleep), T(stopped)
     */
    private fun readThreadState(tid: Int): String {
        return try {
            val stat = File("/proc/$pid/task/$tid/stat").readText()
            val afterParen = stat.substringAfter(") ")
            afterParen.firstOrNull()?.toString() ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    /**
     * 全量扫描所有线程状态统计 R 态数量（开销大，降频调用）。
     */
    private fun countRunningThreads(taskDir: File): Int {
        return try {
            val dirs = taskDir.listFiles() ?: return 0
            dirs.count { dir ->
                try {
                    val stat = File(dir, "stat").readText()
                    val afterParen = stat.substringAfter(") ")
                    afterParen.firstOrNull() == 'R'
                } catch (_: Exception) { false }
            }
        } catch (_: Exception) { 0 }
    }
}

data class ThreadSample(
    val timestampMs: Long,
    val threadCount: Int,
    val mainThreadState: String,   // R, S, D, T
    val runningThreadCount: Int = 0  // 处于 R 状态的线程数
)

data class ThreadStats(
    val avgThreadCount: Int = 0,
    val maxThreadCount: Int = 0,
    val minThreadCount: Int = 0,
    val threadCountGrowth: Int = 0,
    val mainThreadRunnableRatio: Double = 0.0,  // 主线程 "可运行" 状态占比
    val avgRunningThreadCount: Int = 0,  // 平均活跃线程数（R状态）
    val maxRunningThreadCount: Int = 0,  // 峰值活跃线程数
    val sampleCount: Int = 0
) {
    // 线程爆发: Session 期间线程数增长超过 10
    val isThreadBurst: Boolean get() = threadCountGrowth > 10
    // 主线程争抢: R 状态占比低说明经常被阻塞
    val isMainThreadStarved: Boolean get() = mainThreadRunnableRatio < 0.3 && sampleCount > 5
}
