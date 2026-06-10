package io.github.perfettokit.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.perfettokit.PerfettoKit
import io.github.perfettokit.rule.SlowFrameRule

/**
 * 示例：更多使用模式。
 */
class AdvancedUsageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 用法1：measure{} 块级检测 — 测量一段代码的帧性能
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        PerfettoKit.measure("inflate_complex_layout") {
            setContentView(R.layout.activity_advanced)
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 用法2：自定义规则参数 — 对特定场景放宽/收紧阈值
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        val strictRules = listOf(
            SlowFrameRule(thresholdMs = 8.0, severeMultiplier = 2.0)  // 120fps 标准
        )
        val session = PerfettoKit.beginSession("high_fps_animation", rules = strictRules)

        // 执行动画 ...
        window.decorView.postDelayed({
            session.end()  // 2 秒后结束检测
        }, 2000)
    }
}
