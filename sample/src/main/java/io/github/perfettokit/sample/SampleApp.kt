package io.github.perfettokit.sample

import android.app.Application
import io.github.perfettokit.PerfettoKit
import io.github.perfettokit.ai.OpenAICompatProvider
import io.github.perfettokit.auto.AutoSceneDetector
import io.github.perfettokit.report.LogcatReporter

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 初始化 PerfettoKit
        // Skills 从 assets/perfettokit/skills/ 自动加载

        val ollamaBase = "http://10.1xxxx:11434/v1"         // 真机：换成你 Mac 的 IP


        PerfettoKit.init(this, PerfettoKit.Config(
            reporter = LogcatReporter(),
            appPackagePrefix = "io.github.perfettokit.sample",
            aiProvider = OpenAICompatProvider(
                apiKey = "ollama",                  // Ollama 不校验，填任意非空字符串
                baseUrl = ollamaBase,
                model = "qwen2.5-coder:7b"
            )
        ))
        // 启用自动场景检测（与手动 beginSession 共存）
        // 自动检测 Activity 启动 + RecyclerView 滑动
        PerfettoKit.enableAutoDetect(AutoSceneDetector.Config(
            detectLaunch = true,
            detectScroll = false  // 已在 MainActivity 中手动 beginSession(t"list_scroll")，避免重复
        ))
    }
}
