package io.github.perfettokit.collector

import android.os.Process
import android.os.SystemClock
import java.io.File

/**
 * CPU 使用率采集器。
 *
 * 方案（兼容 Android 8+）:
 * - 进程 CPU: /proc/self/stat (utime + stime) — 不需要额外权限
 * - 系统 CPU: /proc/stat — 如果被限制则 fallback 为仅进程级统计
 * - 主线程 CPU: SystemClock.currentThreadTimeMillis()
 *
 * 这和 Android Studio Profiler 的 Sample-based CPU 监控一致：
 * Profiler 也是通过 /proc/[pid]/stat 计算进程 CPU 占比。
 */
class CpuCollector(
    private val intervalMs: Long = 500L
) {
    private val pid = Process.myPid()
    private val samples = mutableListOf<CpuSample>()
    private var running = false
    private var samplerThread: Thread? = null
    private var systemCpuAvailable = true  // 标记 /proc/stat 是否可读

    fun start() {
        if (running) return
        running = true
        samples.clear()
        systemCpuAvailable = true

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
        }, "PerfettoKit-CPU").also { it.isDaemon = true; it.start() }
    }

    fun stop(): List<CpuSample> {
        running = false
        samplerThread?.interrupt()
        samplerThread = null
        return synchronized(samples) { samples.toList() }
    }

    fun computeStats(samples: List<CpuSample>): CpuStats {
        if (samples.size < 2) return CpuStats()

        val cpuUsages = mutableListOf<Double>()
        val processUsages = mutableListOf<Double>()
        val numCores = Runtime.getRuntime().availableProcessors()

        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val elapsedMs = curr.elapsedRealtimeMs - prev.elapsedRealtimeMs
            if (elapsedMs <= 0) continue

            // 进程 CPU 使用率 = (进程CPU时间增量 / 实际经过时间) * 100
            val processTimeDeltaMs = curr.processCpuTimeMs - prev.processCpuTimeMs
            if (processTimeDeltaMs >= 0) {
                val processCpu = processTimeDeltaMs.toDouble() / elapsedMs * 100.0
                processUsages.add(processCpu.coerceIn(0.0, 100.0 * numCores))
            }

            // 系统 CPU 使用率
            if (curr.totalCpuTime > 0 && prev.totalCpuTime > 0) {
                val totalDelta = curr.totalCpuTime - prev.totalCpuTime
                val idleDelta = curr.idleTime - prev.idleTime
                if (totalDelta > 0) {
                    cpuUsages.add(((1.0 - idleDelta.toDouble() / totalDelta) * 100).coerceIn(0.0, 100.0))
                }
            }
        }

        // 去极值平均: 去掉最高和最低后求平均
        val trimmedAvg = if (processUsages.size > 2) {
            val sorted = processUsages.sorted()
            sorted.subList(1, sorted.size - 1).average()
        } else {
            processUsages.average()
        }

        return CpuStats(
            avgSystemCpuPercent = if (cpuUsages.isNotEmpty()) cpuUsages.average() else -1.0,
            maxSystemCpuPercent = cpuUsages.maxOrNull() ?: -1.0,
            avgProcessCpuPercent = if (processUsages.isNotEmpty()) processUsages.average() else 0.0,
            maxProcessCpuPercent = processUsages.maxOrNull() ?: 0.0,
            trimmedAvgProcessCpuPercent = if (processUsages.isNotEmpty()) trimmedAvg else 0.0,
            coreCount = numCores,
            sampleCount = samples.size
        )
    }

    private fun takeSample(): CpuSample? {
        val processCpuMs = readProcessCpuTimeMs()
        val systemStat = if (systemCpuAvailable) readSystemCpu() else null
        val mainThreadCpuMs = readMainThreadCpuTimeMs()

        return CpuSample(
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            processCpuTimeMs = processCpuMs,
            mainThreadCpuTimeMs = mainThreadCpuMs,
            totalCpuTime = systemStat?.total ?: 0L,
            idleTime = systemStat?.idle ?: 0L
        )
    }

    /**
     * 读取主线程 CPU 时间 — /proc/<pid>/task/<pid>/stat
     * 主线程的 TID == PID，所以读 /proc/<pid>/task/<pid>/stat 的 utime+stime。
     */
    private fun readMainThreadCpuTimeMs(): Long {
        return try {
            val stat = File("/proc/$pid/task/$pid/stat").readText()
            val closeParen = stat.lastIndexOf(')')
            if (closeParen < 0) return 0L
            val fields = stat.substring(closeParen + 2).split(' ')
            val utime = fields[11].toLong()
            val stime = fields[12].toLong()
            (utime + stime) * 10  // clock ticks → ms
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 读取进程 CPU 时间 — /proc/self/stat 始终可读。
     *
     * 这是 Android Profiler 获取进程 CPU 的方式。
     * utime + stime = 进程用户态 + 内核态 CPU 时间 (clock ticks)
     * 转为毫秒: ticks / clockTicksPerSec * 1000
     */
    private fun readProcessCpuTimeMs(): Long {
        return try {
            val stat = File("/proc/self/stat").readText()
            // Format: pid (comm) state ... field14=utime field15=stime
            val closeParen = stat.lastIndexOf(')')
            if (closeParen < 0) return 0L
            val fields = stat.substring(closeParen + 2).split(' ')
            // After ") X ", fields[0]=state, [11]=utime, [12]=stime
            val utime = fields[11].toLong()
            val stime = fields[12].toLong()
            // Clock ticks → ms (typically 100 ticks/sec on Linux)
            (utime + stime) * 10  // 1000ms / 100ticks = 10ms/tick
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 读取系统整体 CPU — /proc/stat。
     * Android 8+ 可能读不到（SELinux 限制），失败后标记不再尝试。
     */
    private fun readSystemCpu(): SystemCpuReading? {
        return try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: run {
                systemCpuAvailable = false
                return null
            }
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 5 || parts[0] != "cpu") {
                systemCpuAvailable = false
                return null
            }

            val user = parts[1].toLong()
            val nice = parts[2].toLong()
            val system = parts[3].toLong()
            val idle = parts[4].toLong()
            val iowait = if (parts.size > 5) parts[5].toLong() else 0L
            val irq = if (parts.size > 6) parts[6].toLong() else 0L
            val softirq = if (parts.size > 7) parts[7].toLong() else 0L

            val total = user + nice + system + idle + iowait + irq + softirq
            if (total == 0L) {
                // /proc/stat 返回全零 = 被限制
                systemCpuAvailable = false
                return null
            }
            SystemCpuReading(total = total, idle = idle + iowait)
        } catch (_: Exception) {
            systemCpuAvailable = false
            null
        }
    }

    private data class SystemCpuReading(val total: Long, val idle: Long)
}

data class CpuSample(
    val elapsedRealtimeMs: Long,
    val processCpuTimeMs: Long,     // 进程累计 CPU 时间 (ms)
    val mainThreadCpuTimeMs: Long,  // 主线程累计 CPU 时间 (ms)
    val totalCpuTime: Long,         // 系统 CPU 总 ticks (0 = 不可用)
    val idleTime: Long              // 系统 idle ticks
)

data class CpuStats(
    val avgSystemCpuPercent: Double = 0.0,   // -1 表示不可用
    val maxSystemCpuPercent: Double = 0.0,
    val avgProcessCpuPercent: Double = 0.0,
    val maxProcessCpuPercent: Double = 0.0,
    val trimmedAvgProcessCpuPercent: Double = 0.0,  // 去极值平均（去掉最高最低）
    val coreCount: Int = 0,
    val sampleCount: Int = 0
) {
    val systemCpuAvailable: Boolean get() = avgSystemCpuPercent >= 0
    val isHighCpuLoad: Boolean get() = avgSystemCpuPercent > 80
    val isProcessCpuHeavy: Boolean get() = avgProcessCpuPercent > 60
}
