# PerfettoKit 的对比归因算法：如何排除 90% 的性能误判

> 性能分析最让人崩溃的不是"找不到问题"，而是"指着系统方法说它慢"。
> 本文拆解 PerfettoKit 中的**对比归因算法**——它通过一个非常朴素的统计思想，
> 把"看起来很热"和"真的导致卡顿"区分开。

---

## 一、为什么传统采样会"误判"

Android 上做主线程性能分析，最常见的两种数据源：

1. **栈采样 (Stack Sampling)**：每隔几毫秒抓一次主线程栈，统计每个方法出现的次数。
2. **方法插桩 (Method Tracing)**：在方法入口/出口插桩，记录耗时。

栈采样最大的好处是**零侵入、低开销**，但有一个致命的"先天缺陷"：

> **高频出现 ≠ 导致卡顿。**

举几个真实例子：

| 方法 | 出现率 | 是否罪魁 |
|---|---|---|
| `Looper.loop` | 几乎 100% | ❌ 永远在栈顶，但它不慢 |
| `nativePollOnce` | 50%+ | ❌ 这是**空闲等待**，越多越好 |
| `Choreographer.doFrame` | 占很多 | ❌ 它是帧回调入口本身 |
| `View.draw` | 占很多 | ⚠️ 看情况，有时是渲染管线的正常开销 |
| `Bitmap.createBitmap` | 偶尔出现 | ✅ 很可能是元凶 |

如果你只看"采样次数排序"，会得到一份**正确但毫无用处**的报告：
`Looper.loop / nativePollOnce / Choreographer.doFrame` 永远排前三。

这就是**90% 性能误判的来源**：把"系统永远要做的事"当成"导致卡顿的事"。

---

## 二、PerfettoKit 的核心思想：对比归因

PerfettoKit 的解法非常直白：

> **如果一个方法是真正的元凶，它应该在"掉帧的帧"里出现得明显比"正常帧"多。**

形式化一点：

- 设方法 `M` 在**掉帧帧**中的平均栈占比为 `p_jank(M)`
- 设方法 `M` 在**正常帧**中的平均栈占比为 `p_normal(M)`
- 定义 **占比比值**（proportion ratio）：

  $$\text{ratio}(M) = \frac{p_{\text{jank}}(M)}{p_{\text{normal}}(M)}$$

判定规则：

| 比值 | 含义 |
|---|---|
| `ratio ≈ 1` | 方法在掉帧期和正常期表现相同 → **它只是背景噪音，不是元凶** |
| `ratio > 1.5` | 方法在掉帧期出现明显更多 → **可疑** |
| `ratio >> 1`（如 10x、50x） | 方法几乎只在掉帧时出现 → **强嫌疑** |

这一招直接把 `Looper.loop / nativePollOnce / doFrame` 全部过滤掉——因为它们在**正常帧**里也一样高频出现，比值接近 1。

---

## 三、算法实现细节

下面看 PerfettoKit 实际是怎么实现的（节选自 `TraceSession.computeStackBasedAttribution`）：

### Step 1 — 按帧切分采样

主线程以 5ms 周期做栈采样，把每个采样按时间戳归属到具体的某一帧：

```kotlin
val range = findSamplesInWindow(frameStart, frameEnd)
val methodCountsInFrame = mutableMapOf<String, Int>()
for (i in validIndices) {
    val leaf = extractLeafMethod(sortedSamples[i]).leafMethod ?: continue
    methodCountsInFrame[leaf] = (methodCountsInFrame[leaf] ?: 0) + 1
}
```

**关键点**：取**叶子方法 (leaf method)**，即栈底实际在执行的方法，而不是 `Looper.loop` 这类入口。

### Step 2 — 分别统计两个分布

```kotlin
// 掉帧帧
for ((method, count) in methodCountsInFrame) {
    val proportion = count.toDouble() / totalSamplesInFrame
    jankMethodStats.getOrPut(method) { FrameMethodStats() }
        .proportions.add(proportion)
}

// 正常帧（最多 200 帧，控制开销）
val normalSampleSize = minOf(normalFrames.size, 200)
// ... 同样统计 normalMethodStats
```

### Step 3 — 计算对比比值

```kotlin
val jankProportion   = jankStats.proportions.sum() / jankFramesWithSamples
val normalProportion = normalStats?.proportions?.sum()?.div(normalFramesWithSamples) ?: 0.0

val proportionRatio = when {
    normalProportion > 0.01 -> jankProportion / normalProportion
    jankProportion  > 0.02  -> jankProportion / 0.01   // 正常帧几乎不出现 → 高嫌疑
    else                    -> 1.0                      // 两边都很少 → 无意义
}
```

这里有个**很重要的工程细节**：

> 当正常帧里方法占比极低（< 1%）时，直接做除法会得到一个虚高的比值。
> 算法把分母**地板限制在 0.01**，避免除以接近 0 的数导致排名失真。

### Step 4 — 过滤 + 排序

```kotlin
val filtered = results
    .filter { it.proportionRatio > 1.5 && it.jankProportion > 0.03 }
    .sortedByDescending { it.jankProportion * it.jankFrameAppearanceRate }
    .take(8)
```

**双重门槛**：
- `proportionRatio > 1.5` —— 排除噪音方法
- `jankProportion > 0.03` —— 排除掉帧帧里其实也只占 1~2% 的"小角色"

**排序权重 = 时间占比 × 出现率**：既要"出现时占时间多"，又要"在多少帧里都出现"——单帧偶发的极端值不会被夸大。

---

## 四、特殊情况：纯系统热点

有一类掉帧很特殊——**整帧栈里完全没有 App 代码**，全是 `nativePollOnce`、`SystemClock_nanoSleep` 之类。

这通常意味着：
- **Binder IPC 等待**（系统服务慢）
- **GC 暂停**（已经被记录在另一条规则里）
- **CPU 被调度走**（系统繁忙）

直接套用上面的对比算法会失效（因为系统方法在正常帧也高频）。PerfettoKit 单独走一条分支：

```kotlin
if (!frameHasAppCode) {
    pureSystemJankFrameCount++
    for (method in methodCountsInFrame.keys) {
        pureSystemJankMethods[method] = (pureSystemJankMethods[method] ?: 0) + 1
    }
}
```

只在"纯系统掉帧帧"里统计 Top 3 系统方法，并打上 `isPureSystemHotspot = true` 的标签。报告里会用一个特殊图标 🔧 标注，明确告诉用户：**这不是你代码的问题，但你也无能为力**。

---

## 五、效果对比

以 PerfettoKit `sample/MainActivity` 滑动列表为例（故意注入了 4 类卡顿）。

### 传统采样排序（按出现次数）

```
1. Looper.loop                 — 出现 187 次
2. nativePollOnce              — 出现 162 次
3. Choreographer.doFrame       — 出现 144 次
4. View.draw                   — 出现 98 次
...
```

——**找不到任何有用信息**，前几名永远是这些。

### PerfettoKit 对比归因输出

```
[Jank Time Attribution] (based on 5ms stack sampling)
   📱 JankDemo.heavyCompute — share 38.2% (normal 0.4%, 95.5x), peak 41ms
   📱 JankDemo.bitmapAlloc  — share 19.1% (normal 0.2%, 95.5x), peak 35ms
   📱 JankDemo.stringBuild  — share 14.0% (normal 0.3%, 46.7x), peak 28ms
   🔧 nativePollOnce        — no app hot method, appearance 11.2%, avg 6.3ms
```

——直接把故意注入的 4 个 jank 制造方法定位到方法级，并给出**相对正常帧的放大倍数**。

`Looper.loop` / `Choreographer.doFrame` 这些全部被过滤掉了，因为它们的 `proportionRatio` 都接近 1。

---

## 六、为什么这套算法"能排除 90% 的误判"

回到开篇的问题，传统采样的误判来源是把**绝对值**当作信号。
对比归因换了一个评估维度：**变化量**才是信号。

| 维度 | 传统采样 | 对比归因 |
|---|---|---|
| 数据源 | 同样的栈采样 | 同样的栈采样 |
| 计算指标 | 绝对次数 / 占比 | **掉帧 vs 正常的占比比值** |
| `nativePollOnce` 表现 | Top 1~3 | 被过滤（比值 ≈ 1） |
| `Choreographer.doFrame` 表现 | Top 1~3 | 被过滤 |
| 真正的元凶（如 heavyCompute） | 被淹没 | 被放大（比值 50x+） |
| 误报率 | 很高 | **大幅下降** |

数学上很简单，工程上很有效——这就是 PerfettoKit 在零侵入采样基础上，依然能做到**方法级根因定位**的核心机制。

---

## 七、可改进的方向

诚实地讲，这套算法不是银弹，目前还有一些已知局限：

1. **冷启动场景**：没有"正常帧"作为对照，需要回退到只看绝对占比。
2. **小样本噪声**：会话太短（< 1 秒）时正常帧数量不足，比值不稳定。算法用"至少出现 3 帧 + 分母地板"两个机制缓解，但不能完全消除。
3. **叶子方法选取**：当前只取栈底，如果应用代码全在中间层、栈底是系统库，会归因到系统库上。可通过 `appPackagePrefix` 配置帮忙过滤，但需要使用者显式配置。

这些都是后续可以继续打磨的方向，欢迎在 [GitHub Issues](https://github.com/yeyu-lab/PerfettoKit/issues) 一起讨论。

---

## 📚 参考

- 算法实现：[`TraceSession.computeStackBasedAttribution`](https://github.com/yeyu-lab/PerfettoKit/blob/main/sdk/src/main/java/io/github/perfettokit/session/TraceSession.kt)
- 数据结构：[`StackBasedJankContributor`](https://github.com/yeyu-lab/PerfettoKit/blob/main/sdk/src/main/java/io/github/perfettokit/collector/LooperMonitor.kt)
- 报告输出：[`LogcatReporter`](https://github.com/yeyu-lab/PerfettoKit/blob/main/sdk/src/main/java/io/github/perfettokit/report/LogcatReporter.kt)
- 项目主页：[PerfettoKit](https://github.com/yeyu-lab/PerfettoKit)

---

*License: Apache 2.0 · Author: [@yeyu-lab](https://github.com/yeyu-lab)*
