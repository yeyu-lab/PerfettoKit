package io.github.perfettokit.collector

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer

/**
 * 基于 Choreographer 的帧数据采集器。
 * 在 Session 存活期间逐帧记录每帧耗时。
 */
class FrameCollector {

    private val frames = mutableListOf<FrameData>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastFrameTimeNanos = 0L
    private var onFirstFrame: (() -> Unit)? = null
    private var firstFrameFired = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return

            if (lastFrameTimeNanos > 0) {
                val durationNanos = frameTimeNanos - lastFrameTimeNanos
                val durationMs = durationNanos / 1_000_000.0

                synchronized(frames) {
                    frames.add(
                        FrameData(
                            timestampMs = SystemClock.elapsedRealtime(),
                            totalDurationMs = durationMs,
                            frameTimeNanos = frameTimeNanos
                        )
                    )
                }

                // 第一帧数据产生后触发回调
                if (!firstFrameFired) {
                    firstFrameFired = true
                    onFirstFrame?.invoke()
                }
            }
            lastFrameTimeNanos = frameTimeNanos

            // 注册下一帧回调
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * 设置第一帧就绪回调（在主线程中执行）。
     * 用于延迟启动依赖帧时序的采集器（如 StackSampler）。
     */
    fun setOnFirstFrameListener(listener: () -> Unit) {
        onFirstFrame = listener
    }

    fun start() {
        if (running) return
        running = true
        lastFrameTimeNanos = 0L
        firstFrameFired = false
        mainHandler.post {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    fun stop(): List<FrameData> {
        running = false
        return synchronized(frames) { frames.toList() }
    }
}

/**
 * 单帧数据。
 */
data class FrameData(
    val timestampMs: Long,
    val totalDurationMs: Double,
    val frameTimeNanos: Long
)
