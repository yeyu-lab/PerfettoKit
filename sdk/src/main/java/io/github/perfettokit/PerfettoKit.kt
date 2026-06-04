package io.github.perfettokit

import android.app.Application
import android.util.Log
import io.github.perfettokit.ai.AIProvider
import io.github.perfettokit.ai.LLMEnhancer
import io.github.perfettokit.auto.AnomalyDetector
import io.github.perfettokit.auto.AutoSceneDetector
import io.github.perfettokit.history.SessionStore
import io.github.perfettokit.report.LogcatReporter
import io.github.perfettokit.report.Reporter
import io.github.perfettokit.rule.CpuUsageRule
import io.github.perfettokit.rule.MemoryRule
import io.github.perfettokit.rule.Rule
import io.github.perfettokit.rule.ScrollJankRule
import io.github.perfettokit.rule.SlowFrameRule
import io.github.perfettokit.rule.ThreadRule
import io.github.perfettokit.session.TraceSession
import io.github.perfettokit.skill.Skill
import io.github.perfettokit.skill.SkillLoader

/**
 * PerfettoKit — 开发者手动标记性能检测区间，SDK 自动采集 + 诊断。
 *
 * 用法:
 *   PerfettoKit.init(application)
 *
 *   // 手动标记（精准）
 *   val session = PerfettoKit.beginSession("my_custom_view_scroll")
 *   // ... 执行操作 ...
 *   val report = session.end()
 *
 *   // 自动检测（兜底）
 *   PerfettoKit.enableAutoDetect()
 */
object PerfettoKit {

    private const val TAG = "PerfettoKit"

    private var app: Application? = null
    private var initialized = false
    private val globalRules = mutableListOf<Rule>()
    private var reporter: Reporter = LogcatReporter()
    private var skills: List<Skill> = emptyList()
    private var appPackagePrefix: String = ""

    // 当前全局 Session（支持跨页面 begin/end）
    @Volatile
    private var currentSession: TraceSession? = null

    // AI / 自学习 / 自动检测
    private var aiProvider: AIProvider? = null
    private var llmEnhancer: LLMEnhancer? = null
    private var anomalyDetector: AnomalyDetector? = null
    private var autoSceneDetector: AutoSceneDetector? = null
    private var sessionStore: SessionStore? = null
    private var autoInitPending = false

    /**
     * 自动初始化（由 PerfettoKitInitializer ContentProvider 调用）。
     * 仅记录 Application 引用，延迟到首次使用时再以默认配置初始化。
     * 如果开发者在 Application.onCreate 中手动调用 init()，则 auto-init 不会执行。
     */
    internal fun autoInit(application: Application) {
        if (initialized) return
        app = application
        autoInitPending = true
        Log.d(TAG, "PerfettoKit: ContentProvider registered, waiting for manual init or first use")
    }

    /**
     * 如果开发者没有手动调用 init()，在首次使用 SDK（startSession 等）时
     * 以默认配置完成初始化。
     */
    private fun ensureInitialized() {
        if (initialized) return
        if (autoInitPending && app != null) {
            Log.d(TAG, "PerfettoKit auto-initializing with default config (no manual init called)")
            init(app!!, Config())
        } else {
            throw IllegalStateException("PerfettoKit.init() must be called first")
        }
    }

    /**
     * 手动初始化 SDK — 开发者可传入自定义配置。
     *
     * 可在 Application.onCreate 中调用，无需禁用 ContentProvider。
     * 如果 ContentProvider 已注册，本方法会覆盖默认配置并完成初始化。
     */
    fun init(application: Application, config: Config = Config()) {
        if (initialized) return
        app = application
        autoInitPending = false
        reporter = config.reporter
        appPackagePrefix = config.appPackagePrefix.ifEmpty {
            application.packageName
        }
        globalRules.addAll(config.rules.ifEmpty { defaultRules() })

        // 加载 YAML Skills
        val loader = SkillLoader()
        skills = loader.loadFromAssets(application) + config.extraSkills

        // AI Provider（可选）
        aiProvider = config.aiProvider
        if (aiProvider != null) {
            llmEnhancer = LLMEnhancer(aiProvider!!)
        }

        // 历史存储 + 异常检测（始终启用）
        sessionStore = SessionStore(application)
        anomalyDetector = AnomalyDetector(sessionStore!!)

        Log.d(TAG, "PerfettoKit initialized: ${globalRules.size} rules, ${skills.size} skills" +
                (if (aiProvider != null) ", AI enabled" else ""))

        initialized = true
    }

    /**
     * 启动自动场景检测。
     *
     * 自动检测与手动 beginSession 共存:
     * - 自动检测覆盖未手动标记的场景（Activity启动、列表滑动等）
     * - 手动标记的 Session 不受影响，两者互不干扰
     */
    fun enableAutoDetect(config: AutoSceneDetector.Config = AutoSceneDetector.Config()) {
        ensureInitialized()
        val application = app ?: return
        autoSceneDetector = AutoSceneDetector(application, config).also {
            it.start { scene -> beginSession(scene) }
        }
        Log.d(TAG, "Auto scene detection enabled")
    }

    /**
     * 停止自动场景检测。
     */
    fun disableAutoDetect() {
        autoSceneDetector?.stop()
        autoSceneDetector = null
    }

    /**
     * 开始一个检测 Session。
     *
     * @param scene 场景名称，如 "home_list_scroll", "detail_page_render"
     * @param rules 可选，仅对此 session 生效的规则；为空则使用全局规则
     */
    fun beginSession(scene: String, rules: List<Rule>? = null): TraceSession {
        ensureInitialized()
        // 如果有旧 session 还在跑，先结束
        currentSession?.end()
        val effectiveRules = rules ?: globalRules
        val session = TraceSession(
            scene = scene,
            rules = effectiveRules.toList(),
            reporter = reporter,
            appPackagePrefix = appPackagePrefix,
            skills = skills,
            context = app,
            anomalyDetector = anomalyDetector,
            llmEnhancer = llmEnhancer
        )
        currentSession = session
        return session
    }

    /**
     * 获取当前正在运行的 Session（可在任意页面调用）。
     */
    fun currentSession(): TraceSession? = currentSession

    /**
     * 结束当前 Session（异步，推荐）。可在任意页面调用。
     * @return true 如果有 session 被结束，false 如果当前无 session
     */
    fun endCurrentSession(callback: (io.github.perfettokit.report.DiagnosisReport) -> Unit = {}): Boolean {
        val session = currentSession ?: return false
        currentSession = null
        session.endAsync(callback)
        return true
    }

    /**
     * 当前是否有 Session 在运行。
     */
    fun isSessionRunning(): Boolean = currentSession != null

    /**
     * 快捷方式：测量一个代码块的性能。
     *
     * PerfettoKit.measure("inflate_complex_layout") {
     *     setContentView(R.layout.complex)
     * }
     */
    inline fun <T> measure(scene: String, rules: List<Rule>? = null, block: () -> T): T {
        val session = beginSession(scene, rules)
        val result = block()
        session.end()
        return result
    }

    /**
     * 获取已加载的 Skills 列表（调试用）。
     */
    fun getLoadedSkills(): List<Skill> = skills

    private fun defaultRules(): List<Rule> = listOf(
        SlowFrameRule(),
        ScrollJankRule(),
        CpuUsageRule(),
        MemoryRule(),
        ThreadRule()
    )

    data class Config(
        val rules: List<Rule> = emptyList(),
        val reporter: Reporter = LogcatReporter(),
        val appPackagePrefix: String = "",
        val extraSkills: List<Skill> = emptyList(),  // 额外的编程式 Skill
        val aiProvider: AIProvider? = null           // 可选的 Cloud LLM 增强
    )
}
