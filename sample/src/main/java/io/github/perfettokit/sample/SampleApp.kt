package io.github.perfettokit.sample

import android.app.Application
import io.github.perfettokit.PerfettoKit
import io.github.perfettokit.auto.AutoSceneDetector
import io.github.perfettokit.report.LogcatReporter

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 初始化 PerfettoKit
        // Skills 从 assets/perfettokit/skills/ 自动加载
        PerfettoKit.init(this, PerfettoKit.Config(
            reporter = LogcatReporter(),
            appPackagePrefix = "io.github.perfettokit.sample"
            // 可选: aiProvider = OpenAICompatProvider(apiKey = "...", baseUrl = "...")
        ))

        // 启用自动场景检测（与手动 beginSession 共存）
        // 自动检测 Activity 启动 + RecyclerView 滑动
        PerfettoKit.enableAutoDetect(AutoSceneDetector.Config(
            detectLaunch = true,
            detectScroll = false  // 已在 MainActivity 中手动 beginSession("list_scroll")，避免重复
        ))
    }
}
