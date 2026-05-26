package io.github.perfettokit.analyzer

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * 主线程堆栈采样器 — 持续采样模式。
 *
 * 后台线程每 5ms 采样一次主线程堆栈，能完整捕获主线程时间分布。
 * 自动过滤 JVM 内部帧（VMStack/Thread.getStackTrace）和 SDK 自身帧。
 */
class StackSampler(
    private val intervalMs: Long = 5L,
    private val maxSamples: Int = 60_000,  // 5ms × 60000 = 5分钟 session 上限
    private val context: Context? = null
) {
    private val mainThread = Looper.getMainLooper().thread
    private val samples = mutableListOf<StackSample>()
    private var samplerThread: HandlerThread? = null
    private var samplerHandler: Handler? = null
    private var running = false
    private var sceneName: String = ""

    data class StackSample(
        val timestampMs: Long,
        val stackTrace: Array<StackTraceElement>
    ) {
        fun topAppFrames(appPackagePrefix: String = ""): List<StackTraceElement> {
            return stackTrace.filter { frame ->
                val cls = frame.className
                !cls.startsWith("android.") &&
                !cls.startsWith("androidx.") &&
                !cls.startsWith("com.android.") &&
                !cls.startsWith("com.google.") &&
                !cls.startsWith("java.") &&
                !cls.startsWith("javax.") &&
                !cls.startsWith("kotlin.") &&
                !cls.startsWith("kotlinx.") &&
                !cls.startsWith("dalvik.") &&
                !cls.startsWith("libcore.") &&
                !cls.startsWith("sun.") &&
                !cls.startsWith("jdk.") &&
                !cls.startsWith("org.json.") &&
                !cls.startsWith("io.github.perfettokit.") &&
                (appPackagePrefix.isEmpty() || cls.startsWith(appPackagePrefix))
            }
        }
    }

    fun start(scene: String = "") {
        if (running) return
        running = true
        sceneName = scene
        synchronized(samples) { samples.clear() }

        samplerThread = HandlerThread("PerfettoKit-Sampler").also { it.start() }
        samplerHandler = Handler(samplerThread!!.looper)
        scheduleSample()
    }

    fun stop(): List<StackSample> {
        running = false
        samplerThread?.quitSafely()
        samplerThread = null
        samplerHandler = null
        val result = synchronized(samples) { samples.toList() }
        saveToCacheAsync(result)
        return result
    }

    /**
     * 获取指定时间范围内的堆栈样本（用于和慢帧关联）。
     */
    fun getSamplesInRange(startMs: Long, endMs: Long): List<StackSample> {
        return synchronized(samples) {
            samples.filter { it.timestampMs in startMs..endMs }
        }
    }

    private fun scheduleSample() {
        samplerHandler?.postDelayed({
            if (!running) return@postDelayed
            try {
                val rawTrace = mainThread.stackTrace
                // 过滤 JVM 采样机制固有帧（VMStack.getThreadStackTrace / Thread.getStackTrace）
                val trace = rawTrace.dropWhile {
                    it.className == "dalvik.system.VMStack" ||
                    it.className == "java.lang.Thread"
                }.toTypedArray()
                if (trace.isNotEmpty()) {
                    // 跳过纯空闲采样（nativePollOnce）以节省内存
                    val isIdle = trace.any {
                        it.className.contains("MessageQueue") && it.methodName == "nativePollOnce"
                    }
                    if (!isIdle) {
                        synchronized(samples) {
                            if (samples.size >= maxSamples) {
                                // 安全上限: 丢弃最早的 10% 而非逐条移除（减少 ArrayList copy）
                                val dropCount = maxSamples / 10
                                samples.subList(0, dropCount).clear()
                            }
                            samples.add(StackSample(SystemClock.elapsedRealtime(), trace))
                        }
                    }
                }
            } catch (_: OutOfMemoryError) {
                // 内存紧张时跳过
            }
            scheduleSample()
        }, intervalMs)
    }

    /**
     * 加载上一次缓存的采样数据。
     */
    fun loadCachedSamples(scene: String): List<StackSample>? {
        val cacheDir = context?.cacheDir ?: return null
        val file = File(cacheDir, "perfettokit/samples_${scene}.txt")
        if (!file.exists()) return null

        return try {
            val loaded = mutableListOf<StackSample>()
            file.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith("T:")) {
                        val ts = line.substringAfter("T:").toLongOrNull() ?: 0L
                        val frames = mutableListOf<StackTraceElement>()
                        line = reader.readLine()
                        while (line != null && line.startsWith("  ")) {
                            val parts = line.trim().split("|")
                            if (parts.size == 4) {
                                frames.add(StackTraceElement(
                                    parts[0], parts[1], parts[2],
                                    parts[3].toIntOrNull() ?: -1
                                ))
                            }
                            line = reader.readLine()
                        }
                        loaded.add(StackSample(ts, frames.toTypedArray()))
                    } else {
                        line = reader.readLine()
                    }
                }
            }
            loaded
        } catch (_: Exception) {
            null
        }
    }

    fun listCachedSessions(): List<String> {
        val cacheDir = context?.cacheDir ?: return emptyList()
        val dir = File(cacheDir, "perfettokit")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("samples_") && it.name.endsWith(".txt") }
            ?.map { it.name.removePrefix("samples_").removeSuffix(".txt") }
            ?: emptyList()
    }

    private fun saveToCacheAsync(data: List<StackSample>) {
        val ctx = context ?: return
        Thread({
            try {
                val dir = File(ctx.cacheDir, "perfettokit")
                dir.mkdirs()
                val fileName = if (sceneName.isNotEmpty()) "samples_$sceneName.txt" else "samples_latest.txt"
                val file = File(dir, fileName)

                file.bufferedWriter().use { writer ->
                    for (sample in data) {
                        writer.write("T:${sample.timestampMs}")
                        writer.newLine()
                        for (frame in sample.stackTrace.take(15)) {
                            writer.write("  ${frame.className}|${frame.methodName}|${frame.fileName ?: ""}|${frame.lineNumber}")
                            writer.newLine()
                        }
                    }
                }
                Log.d("PerfettoKit", "采样缓存已保存: ${file.absolutePath} (${data.size}条)")
            } catch (e: Exception) {
                Log.w("PerfettoKit", "采样缓存写入失败: ${e.message}")
            }
        }, "PerfettoKit-CacheWriter").start()
    }
}
