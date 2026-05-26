package io.github.perfettokit.auto

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import io.github.perfettokit.session.TraceSession

/**
 * 自动场景识别器。
 *
 * 支持的场景:
 * 1. Activity 启动 (onCreate → onResume)
 * 2. RecyclerView 滑动 (DRAGGING → IDLE)
 * 3. Fragment 切换 (attach → resume)
 * 4. 触摸响应延迟 (ACTION_DOWN → 下一帧渲染)
 * 5. App 冷启动 (首个 Activity onCreate → onResume)
 *
 * 与手动 beginSession 共存:
 * - 自动检测是"兜底"，覆盖开发者没有手动标记的地方
 * - 手动标记是"精确"，开发者对特定代码段的精准测量
 * - 同一时间可以有多个 Session 并行（互不干扰）
 */
class AutoSceneDetector(
    private val app: Application,
    private val config: Config = Config()
) {
    companion object {
        private const val TAG = "PerfettoKit.Auto"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentActivity: Activity? = null
    private var launchSession: TraceSession? = null
    private var scrollSession: TraceSession? = null
    private var fragmentSession: TraceSession? = null
    private var touchSession: TraceSession? = null
    private var coldStartSession: TraceSession? = null
    private var sessionFactory: ((String) -> TraceSession)? = null

    // 防止和手动 Session 冲突的标记
    private val activeAutoScenes = mutableSetOf<String>()

    // 冷启动标记
    private var isFirstActivity = true
    private var appCreateTimeMs: Long = SystemClock.elapsedRealtime()

    data class Config(
        val detectLaunch: Boolean = true,        // 自动检测 Activity 启动
        val detectScroll: Boolean = true,        // 自动检测列表滑动
        val detectFragment: Boolean = true,      // 自动检测 Fragment 切换
        val detectTouchResponse: Boolean = true, // 自动检测触摸响应延迟
        val detectColdStart: Boolean = true,     // 自动检测 App 冷启动
        val launchThresholdMs: Long = 3000,      // 超过此时间不计为启动
        val touchResponseThresholdMs: Long = 500,// 触摸响应超过此阈值才记录
        val ignoredActivities: Set<String> = emptySet()  // 忽略的 Activity
    )

    /**
     * 启动自动检测。
     *
     * @param factory Session 创建工厂（由 PerfettoKit 提供）
     */
    fun start(factory: (String) -> TraceSession) {
        sessionFactory = factory
        appCreateTimeMs = SystemClock.elapsedRealtime()
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        Log.d(TAG, "AutoSceneDetector started")
    }

    fun stop() {
        app.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        endAllAutoSessions()
        sessionFactory = null
    }

    private fun beginAutoSession(scene: String): TraceSession? {
        if (activeAutoScenes.contains(scene)) return null  // 已有同场景在跑
        val session = sessionFactory?.invoke("auto:$scene") ?: return null
        activeAutoScenes.add(scene)
        Log.d(TAG, "Auto session started: $scene")
        return session
    }

    private fun endAutoSession(scene: String, session: TraceSession?) {
        if (session == null) return
        // 先从 active 集合移除并清理，再调用 end()，避免分析期间的 lifecycle 重入导致双重 end
        if (!activeAutoScenes.remove(scene)) {
            return  // 已被其它路径结束
        }
        try {
            session.end()
        } catch (e: Throwable) {
            Log.w(TAG, "endAutoSession($scene) failed: ${e.message}")
        }
        Log.d(TAG, "Auto session ended: $scene")
    }

    private fun endAllAutoSessions() {
        launchSession?.let { endAutoSession("launch", it) }
        scrollSession?.let { endAutoSession("scroll", it) }
        fragmentSession?.let { endAutoSession("fragment", it) }
        touchSession?.let { endAutoSession("touch", it) }
        coldStartSession?.let { endAutoSession("cold_start", it) }
        launchSession = null
        scrollSession = null
        fragmentSession = null
        touchSession = null
        coldStartSession = null
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 场景 1: Activity 启动 (onCreate → onResume)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (config.ignoredActivities.contains(activity.javaClass.name)) return
            currentActivity = activity

            // 场景 5: App 冷启动
            if (config.detectColdStart && isFirstActivity) {
                isFirstActivity = false
                coldStartSession = beginAutoSession("cold_start")
            }

            // 场景 1: Activity 启动
            if (config.detectLaunch) {
                val activityName = activity.javaClass.simpleName
                launchSession = beginAutoSession("launch:$activityName")

                // 超时自动结束（防止 Session 泄漏）
                mainHandler.postDelayed({
                    if (launchSession != null) {
                        endAutoSession("launch:$activityName", launchSession)
                        launchSession = null
                    }
                }, config.launchThresholdMs)
            }

            // 场景 4: 触摸响应 — 注册 Window callback
            if (config.detectTouchResponse) {
                setupTouchDetection(activity)
            }
        }

        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityResumed(activity: Activity) {
            currentActivity = activity
            val activityName = activity.javaClass.simpleName

            // Activity 可见 → 启动完成
            if (config.detectLaunch) {
                launchSession?.let {
                    launchSession = null
                    endAutoSession("launch:$activityName", it)
                }
            }

            // 冷启动结束（首个 Activity 可见）
            coldStartSession?.let {
                coldStartSession = null  // 先清空字段，避免后续 onResume 重入
                endAutoSession("cold_start", it)
            }

            // 场景 2: RecyclerView 滑动检测
            if (config.detectScroll) {
                mainHandler.postDelayed({
                    detectScrollableViews(activity)
                }, 500)
            }

            // 场景 3: Fragment 切换检测
            if (config.detectFragment && activity is FragmentActivity) {
                registerFragmentDetection(activity)
            }
        }

        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivity == activity) currentActivity = null
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 场景 2: RecyclerView 滑动 (DRAGGING → IDLE)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            val activityName = currentActivity?.javaClass?.simpleName ?: "unknown"
            val scene = "scroll:$activityName"

            when (newState) {
                RecyclerView.SCROLL_STATE_DRAGGING -> {
                    if (scrollSession == null) {
                        scrollSession = beginAutoSession(scene)
                    }
                }
                RecyclerView.SCROLL_STATE_IDLE -> {
                    if (scrollSession != null) {
                        endAutoSession(scene, scrollSession)
                        scrollSession = null
                    }
                }
            }
        }
    }

    private fun detectScrollableViews(activity: Activity) {
        val rootView = activity.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
            ?: return
        findRecyclerViews(rootView).forEach { rv ->
            rv.removeOnScrollListener(scrollListener)
            rv.addOnScrollListener(scrollListener)
        }
    }

    private fun findRecyclerViews(viewGroup: ViewGroup): List<RecyclerView> {
        val result = mutableListOf<RecyclerView>()
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is RecyclerView) {
                result.add(child)
            } else if (child is ViewGroup) {
                result.addAll(findRecyclerViews(child))
            }
        }
        return result
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 场景 3: Fragment 切换 (attach → resume)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private val registeredFragmentManagers = mutableSetOf<Int>()

    private fun registerFragmentDetection(activity: FragmentActivity) {
        val fm = activity.supportFragmentManager
        val fmId = System.identityHashCode(fm)
        if (registeredFragmentManagers.contains(fmId)) return
        registeredFragmentManagers.add(fmId)

        fm.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentAttached(fm: FragmentManager, f: Fragment, context: android.content.Context) {
                val fragmentName = f.javaClass.simpleName
                val scene = "fragment:$fragmentName"
                fragmentSession = beginAutoSession(scene)
            }

            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                val fragmentName = f.javaClass.simpleName
                val scene = "fragment:$fragmentName"
                if (fragmentSession != null) {
                    endAutoSession(scene, fragmentSession)
                    fragmentSession = null
                }
            }
        }, false)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 场景 4: 触摸响应延迟 (ACTION_DOWN → 下一帧)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private var touchDownTimeMs: Long = 0

    private fun setupTouchDetection(activity: Activity) {
        val originalCallback = activity.window.callback
        activity.window.callback = object : Window.Callback by originalCallback {
            override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                if (event?.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchDownTimeMs = SystemClock.elapsedRealtime()
                    val activityName = activity.javaClass.simpleName
                    val scene = "touch_response:$activityName"
                    touchSession = beginAutoSession(scene)

                    // 在下一帧结束时检测响应时间
                    activity.window.decorView.viewTreeObserver.addOnPreDrawListener(
                        object : ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                                if (touchSession != null) {
                                    val responseTime = SystemClock.elapsedRealtime() - touchDownTimeMs
                                    if (responseTime > config.touchResponseThresholdMs) {
                                        // 响应慢，保留 session 让它采集完整数据
                                        mainHandler.postDelayed({
                                            endAutoSession(scene, touchSession)
                                            touchSession = null
                                        }, 100)
                                    } else {
                                        // 响应正常，立即结束（不产生噪音报告）
                                        endAutoSession(scene, touchSession)
                                        touchSession = null
                                    }
                                }
                                return true
                            }
                        }
                    )
                }
                return originalCallback.dispatchTouchEvent(event)
            }
        }
    }
}
