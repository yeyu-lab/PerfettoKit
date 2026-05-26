package io.github.perfettokit.collector

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 网络流量采集器 — 统计 Session 期间 App 的上下行流量。
 *
 * 原理: TrafficStats.getUidRxBytes() / getUidTxBytes()
 * 用于发现: 启动时大量网络请求、大图下载阻塞 UI、频繁轮询
 */
class NetworkCollector {

    private val uid = Process.myUid()
    private var startRxBytes: Long = 0
    private var startTxBytes: Long = 0
    private var sampling = false
    private val samples = mutableListOf<NetworkSample>()
    private var scheduledFuture: ScheduledFuture<*>? = null

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "PerfettoKit-Network").apply { isDaemon = true }
    }

    fun start() {
        startRxBytes = TrafficStats.getUidRxBytes(uid)
        startTxBytes = TrafficStats.getUidTxBytes(uid)
        sampling = true
        samples.clear()

        // 每 500ms 采样一次流量增量
        var lastRx = startRxBytes
        var lastTx = startTxBytes

        scheduledFuture = executor.scheduleAtFixedRate({
            if (!sampling) return@scheduleAtFixedRate
            try {
                val currentRx = TrafficStats.getUidRxBytes(uid)
                val currentTx = TrafficStats.getUidTxBytes(uid)
                val deltaRx = currentRx - lastRx
                val deltaTx = currentTx - lastTx
                lastRx = currentRx
                lastTx = currentTx

                samples.add(NetworkSample(
                    timestampMs = SystemClock.elapsedRealtime(),
                    rxBytesPerInterval = deltaRx,
                    txBytesPerInterval = deltaTx
                ))
            } catch (e: Exception) {
                Log.w("PerfettoKit", "NetworkCollector sample failed: ${e.message}")
            }
        }, 500, 500, TimeUnit.MILLISECONDS)
    }

    fun stop(): List<NetworkSample> {
        sampling = false
        scheduledFuture?.cancel(false)
        return samples.toList()
    }

    fun computeStats(samples: List<NetworkSample>): NetworkStats {
        val totalRx = TrafficStats.getUidRxBytes(uid) - startRxBytes
        val totalTx = TrafficStats.getUidTxBytes(uid) - startTxBytes
        val peakRxPerSec = if (samples.isNotEmpty()) {
            samples.maxOf { it.rxBytesPerInterval } * 2  // 500ms interval → per sec
        } else 0L
        val peakTxPerSec = if (samples.isNotEmpty()) {
            samples.maxOf { it.txBytesPerInterval } * 2
        } else 0L

        return NetworkStats(
            totalRxBytes = totalRx,
            totalTxBytes = totalTx,
            peakRxBytesPerSec = peakRxPerSec,
            peakTxBytesPerSec = peakTxPerSec,
            sampleCount = samples.size
        )
    }
}

data class NetworkSample(
    val timestampMs: Long,
    val rxBytesPerInterval: Long,  // 该采样间隔内的下行字节
    val txBytesPerInterval: Long   // 该采样间隔内的上行字节
)

data class NetworkStats(
    val totalRxBytes: Long = 0,       // 总下行
    val totalTxBytes: Long = 0,       // 总上行
    val peakRxBytesPerSec: Long = 0,  // 峰值下行速率
    val peakTxBytesPerSec: Long = 0,  // 峰值上行速率
    val sampleCount: Int = 0
) {
    val totalBytes: Long get() = totalRxBytes + totalTxBytes
    val totalKB: Long get() = totalBytes / 1024
    val totalMB: Double get() = totalBytes / 1024.0 / 1024.0
}
