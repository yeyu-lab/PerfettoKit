package io.github.perfettokit.collector

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.lang.ref.WeakReference

/**
 * 大图检测器 — 检测 ImageView 中加载的过大 Bitmap。
 *
 * 原理: 遍历 View 树，检查 ImageView 的 drawable 实际像素 vs 显示尺寸。
 * 用于发现:
 * - 加载了 4000x3000 的图片但只显示在 100x100 的 ImageView 里
 * - 未压缩的相机照片直接加载到内存
 * - 忘记设置 inSampleSize 的 BitmapFactory 调用
 *
 * 轻量: 只在 session end() 时扫描一次，不持续采集。
 */
class BitmapDetector {

    companion object {
        // 像素面积比阈值: Bitmap 像素面积超过 ImageView 显示面积的 N 倍算超大
        private const val OVERSIZE_RATIO = 4.0
        // 单张 Bitmap 内存超过此值告警 (2MB)
        private const val LARGE_BITMAP_BYTES = 2 * 1024 * 1024
    }

    private val detectedBitmaps = mutableListOf<BitmapIssue>()

    /**
     * 扫描给定 View 树中的 ImageView，检查 Bitmap 尺寸是否合理。
     *
     * 建议在 Session.end() 时调用。
     */
    fun scan(rootView: View?): List<BitmapIssue> {
        detectedBitmaps.clear()
        if (rootView == null) return emptyList()
        scanViewTree(rootView)
        return detectedBitmaps.toList()
    }

    private fun scanViewTree(view: View) {
        if (view is ImageView) {
            checkImageView(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                scanViewTree(view.getChildAt(i))
            }
        }
    }

    private fun checkImageView(imageView: ImageView) {
        val drawable = imageView.drawable ?: return
        val bitmapWidth = drawable.intrinsicWidth
        val bitmapHeight = drawable.intrinsicHeight
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return

        val viewWidth = imageView.width
        val viewHeight = imageView.height
        if (viewWidth <= 0 || viewHeight <= 0) return

        val bitmapPixels = bitmapWidth.toLong() * bitmapHeight
        val viewPixels = viewWidth.toLong() * viewHeight
        val ratio = bitmapPixels.toDouble() / viewPixels

        // 估算内存 (ARGB_8888 = 4 bytes/pixel)
        val estimatedBytes = bitmapPixels * 4

        val issues = mutableListOf<String>()

        if (ratio > OVERSIZE_RATIO) {
            issues.add("Bitmap ${bitmapWidth}x${bitmapHeight} 在 ${viewWidth}x${viewHeight} 的 View 中显示 (${String.format("%.1f", ratio)}x 过大)")
        }

        if (estimatedBytes > LARGE_BITMAP_BYTES) {
            issues.add("Bitmap 占用约 ${estimatedBytes / 1024 / 1024}MB 内存")
        }

        if (issues.isNotEmpty()) {
            val viewId = try {
                imageView.resources.getResourceEntryName(imageView.id)
            } catch (e: Exception) {
                "unknown_id"
            }

            detectedBitmaps.add(BitmapIssue(
                viewId = viewId,
                viewClass = imageView.javaClass.simpleName,
                bitmapWidth = bitmapWidth,
                bitmapHeight = bitmapHeight,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                oversizeRatio = ratio,
                estimatedMemoryBytes = estimatedBytes,
                description = issues.joinToString("; ")
            ))
        }
    }

    fun computeStats(issues: List<BitmapIssue>): BitmapStats {
        val totalWastedBytes = issues.sumOf { issue ->
            val needed = issue.viewWidth.toLong() * issue.viewHeight * 4
            val actual = issue.estimatedMemoryBytes
            (actual - needed).coerceAtLeast(0)
        }

        return BitmapStats(
            oversizeBitmapCount = issues.size,
            totalWastedMemoryBytes = totalWastedBytes,
            worstOffender = issues.maxByOrNull { it.oversizeRatio },
            issues = issues
        )
    }
}

data class BitmapIssue(
    val viewId: String,
    val viewClass: String,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val viewWidth: Int,
    val viewHeight: Int,
    val oversizeRatio: Double,
    val estimatedMemoryBytes: Long,
    val description: String
)

data class BitmapStats(
    val oversizeBitmapCount: Int = 0,
    val totalWastedMemoryBytes: Long = 0,
    val worstOffender: BitmapIssue? = null,
    val issues: List<BitmapIssue> = emptyList()
) {
    val totalWastedMB: Double get() = totalWastedMemoryBytes / 1024.0 / 1024.0
    val hasIssues: Boolean get() = oversizeBitmapCount > 0
}
