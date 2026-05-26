package io.github.perfettokit.collector

import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log

/**
 * 主线程 IO 操作检测器。
 *
 * 原理: 利用 StrictMode 的 VmPolicy/ThreadPolicy 检测主线程磁盘读写。
 * 同时通过堆栈采样关联 IO 操作的调用源。
 *
 * 用于发现: 主线程读写 SharedPreferences、文件、数据库等阻塞操作。
 */
class IODetector {

    private var detecting = false
    private val ioEvents = mutableListOf<IOEvent>()
    private var originalThreadPolicy: StrictMode.ThreadPolicy? = null

    /**
     * 开始检测主线程 IO。
     *
     * 注意: 会临时修改 StrictMode ThreadPolicy，end() 时恢复。
     */
    fun start() {
        detecting = true
        ioEvents.clear()

        // 保存原始 policy
        originalThreadPolicy = StrictMode.getThreadPolicy()

        // 设置自定义 penalty 来捕获 IO 违规
        val customListener = StrictMode.OnThreadViolationListener { violation ->
            if (!detecting) return@OnThreadViolationListener
            val stackTrace = violation.stackTrace
            val ioType = classifyViolation(violation)
            if (ioType != null) {
                synchronized(ioEvents) {
                    ioEvents.add(IOEvent(
                        timestampMs = SystemClock.elapsedRealtime(),
                        type = ioType,
                        stackTrace = stackTrace.take(10).map { it.toString() },
                        message = violation.message ?: ""
                    ))
                }
            }
        }

        // API 28+ 支持 OnThreadViolationListener
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyListener(Runnable::run, customListener)
                    .build()
            )
        } else {
            // API < 28: 使用 penaltyLog + 解析 logcat（降级方案）
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
        }
    }

    fun stop(): List<IOEvent> {
        detecting = false
        // 恢复原始 policy
        originalThreadPolicy?.let { StrictMode.setThreadPolicy(it) }
        return synchronized(ioEvents) { ioEvents.toList() }
    }

    fun computeStats(events: List<IOEvent>): IOStats {
        val diskReads = events.count { it.type == IOType.DISK_READ }
        val diskWrites = events.count { it.type == IOType.DISK_WRITE }
        val networkOnMain = events.count { it.type == IOType.NETWORK }

        return IOStats(
            diskReadCount = diskReads,
            diskWriteCount = diskWrites,
            networkOnMainCount = networkOnMain,
            totalViolations = events.size,
            topOffenders = findTopOffenders(events)
        )
    }

    private fun classifyViolation(violation: Throwable): IOType? {
        val msg = violation.toString().lowercase()
        return when {
            "diskread" in msg || "disk read" in msg -> IOType.DISK_READ
            "diskwrite" in msg || "disk write" in msg -> IOType.DISK_WRITE
            "network" in msg -> IOType.NETWORK
            else -> IOType.DISK_READ  // 默认归为磁盘读
        }
    }

    /**
     * 找出最频繁触发 IO 的调用位置。
     */
    private fun findTopOffenders(events: List<IOEvent>): List<IOOffender> {
        return events
            .mapNotNull { event ->
                // 找到第一个非系统类的调用帧
                event.stackTrace.firstOrNull { frame ->
                    !frame.startsWith("android.") &&
                    !frame.startsWith("java.") &&
                    !frame.startsWith("dalvik.") &&
                    !frame.startsWith("com.android.")
                }
            }
            .groupBy { it }
            .map { (frame, occurrences) ->
                IOOffender(location = frame, count = occurrences.size)
            }
            .sortedByDescending { it.count }
            .take(5)
    }
}

enum class IOType {
    DISK_READ,
    DISK_WRITE,
    NETWORK
}

data class IOEvent(
    val timestampMs: Long,
    val type: IOType,
    val stackTrace: List<String>,
    val message: String
)

data class IOStats(
    val diskReadCount: Int = 0,
    val diskWriteCount: Int = 0,
    val networkOnMainCount: Int = 0,
    val totalViolations: Int = 0,
    val topOffenders: List<IOOffender> = emptyList()
)

data class IOOffender(
    val location: String,  // 调用位置
    val count: Int         // 触发次数
)
