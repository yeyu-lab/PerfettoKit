package io.github.perfettokit.sample

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.perfettokit.PerfettoKit
import io.github.perfettokit.collector.MethodTracer
import io.github.perfettokit.session.TraceSession

/**
 * 示例：开发者自定义开始/结束检测区间 + 方法级分析。
 *
 * 场景：RecyclerView 滑动时检测性能，滑动停止后输出根因报告。
 */
class MainActivity : AppCompatActivity() {

    private var scrollSession: TraceSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = SampleAdapter()
        }
        setContentView(recyclerView)

        // ✅ 核心用法：监听滑动状态，自定义开始/结束
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    // 开始滑动 → 开始检测 + 分析
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        scrollSession = PerfettoKit.beginSession("list_scroll")
                    }
                    // 滑动停止 → 结束检测，自动输出根因分析
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        scrollSession?.end()
                        scrollSession = null
                    }
                }
            }
        })
    }

    /** ViewHolder 必须是非 inner 的嵌套类（外层 SampleAdapter 是 inner 时不能再嵌套普通 class） */
    private class VH(val textView: TextView) : RecyclerView.ViewHolder(textView)

    /**
     * 示例 Adapter — 演示如何对 onBindViewHolder 做方法级插桩。
     * 当你怀疑某个方法慢时，用 MethodTracer.trace() 包裹它（全局入口，无需持有 session）。
     */
    private inner class SampleAdapter : RecyclerView.Adapter<VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            // ✅ 对可疑方法插桩 — 分析引擎会关联到慢帧
            MethodTracer.trace("SampleAdapter.onBind") {
                holder.textView.text = "Item #$position"
                simulateJank(position)
            }
        }

        override fun getItemCount() = 500
    }

    // ============================================================
    // 故意制造的几种卡顿场景 —— 滑动列表时观察 logcat
    // 过滤标签：JankDemo  以及  PerfettoKit 的方法插桩输出
    // ============================================================

    private fun simulateJank(position: Int) {
        when {
            // 1) 重计算：纯 CPU 排序（每 13 个 item 出现一次，约 25~40ms）
            position % 13 == 0 -> MethodTracer.trace("JankDemo.heavyCompute") {
                Log.w(TAG, "[JANK#$position] heavyCompute start")
                val arr = IntArray(80_000) { (it * 31 + position) and 0xFFFF }
                arr.sort()
                var s = 0; for (v in arr) s = s xor v
                Log.w(TAG, "[JANK#$position] heavyCompute done, checksum=$s")
            }

            // 2) 大 Bitmap 分配（每 17 个 item，触发 GC / native alloc 抖动）
            position % 17 == 0 -> MethodTracer.trace("JankDemo.bitmapAlloc") {
                Log.w(TAG, "[JANK#$position] bitmapAlloc start")
                val bmp = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
                bmp.recycle()
                Log.w(TAG, "[JANK#$position] bitmapAlloc done")
            }

            // 3) 字符串拼接 + 正则（每 11 个 item，约 15~30ms）
            position % 11 == 0 -> MethodTracer.trace("JankDemo.stringBuild") {
                Log.w(TAG, "[JANK#$position] stringBuild start")
                val sb = StringBuilder()
                repeat(5_000) { sb.append("item-").append(it).append(";") }
                val matches = Regex("item-\\d{3,4};").findAll(sb).count()
                Log.w(TAG, "[JANK#$position] stringBuild done, matches=$matches")
            }

            // 4) 主线程 sleep（每 7 个 item，模拟同步 IO/锁等待，~20ms）
            position % 7 == 0 -> MethodTracer.trace("JankDemo.fakeSyncIO") {
                Log.w(TAG, "[JANK#$position] fakeSyncIO sleep 20ms")
                Thread.sleep(20)
            }
        }
    }

    companion object {
        private const val TAG = "JankDemo"
    }
}
