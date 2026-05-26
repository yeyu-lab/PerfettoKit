package io.github.perfettokit.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.github.perfettokit.PerfettoKit
import io.github.perfettokit.session.TraceSession

/**
 * 示例：自定义 View + 方法级插桩。
 *
 * 展示 PerfettoKit 如何通过堆栈采样 + YAML Skill 匹配
 * 定位 "自定义 View 绘制耗时" 根因。
 */
class CustomDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val path = Path()
    private var drawSession: TraceSession? = null
    private var pointCount = 0

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // ✅ 开始检测
                drawSession = PerfettoKit.beginSession("custom_view_draw")
                path.moveTo(event.x, event.y)
                pointCount = 0
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(event.x, event.y)
                pointCount++
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                // ✅ 结束检测 → YAML Skill "heavy_draw.yaml" 会自动匹配
                drawSession?.end()
                drawSession = null
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 用 methodTracker 对 onDraw 内部做精细分析
        drawSession?.methodTracker?.trace("CustomDrawingView.drawPath") {
            canvas.drawPath(path, paint)
        }

        // 模拟耗时绘制（触发 heavy_draw + gc_pressure skill）
        if (pointCount > 50) {
            drawSession?.methodTracker?.trace("CustomDrawingView.heavyDraw") {
                for (i in 0 until 100) {
                    val tempPaint = Paint()  // ❌ onDraw 中创建对象 → 触发 GC
                    tempPaint.color = Color.rgb(i, i * 2 % 255, 100)
                    canvas.drawCircle(i * 10f, i * 5f, 3f, tempPaint)
                }
            }
        }
    }
}
