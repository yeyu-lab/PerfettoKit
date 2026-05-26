package io.github.perfettokit.skill

import io.github.perfettokit.analyzer.AnalysisResult
import io.github.perfettokit.analyzer.HotMethod
import io.github.perfettokit.analyzer.RootCause
import io.github.perfettokit.analyzer.RootCauseType
import io.github.perfettokit.collector.FrameData

/**
 * Skill 匹配器 — 将采集的数据和 YAML Skill 规则进行匹配，输出根因。
 */
class SkillMatcher {

    /**
     * 用加载的 Skills 对分析数据做匹配。
     *
     * @param skills 已加载的 Skill 列表
     * @param scene 当前场景名称
     * @param hotMethods 热点方法列表
     * @param frames 帧数据
     * @return 匹配到的根因列表
     */
    fun match(
        skills: List<Skill>,
        scene: String,
        hotMethods: List<HotMethod>,
        frames: List<FrameData>
    ): List<RootCause> {
        val results = mutableListOf<RootCause>()

        for (skill in skills) {
            // 场景过滤：如果 skill 指定了 scene，必须匹配
            if (skill.scene.isNotEmpty() && !matchScene(skill.scene, scene)) {
                continue
            }

            // 检查所有 match rules
            for (rule in skill.matchRules) {
                if (evaluateRule(rule, hotMethods, frames)) {
                    results.add(
                        RootCause(
                            type = mapRootCauseType(skill.rootCauseType),
                            confidence = mapConfidence(skill.confidence),
                            description = skill.description.ifEmpty { skill.name },
                            evidence = buildEvidence(rule, hotMethods, frames),
                            suggestion = skill.suggestions.joinToString("\n")
                        )
                    )
                    break  // 一个 skill 只触发一次
                }
            }
        }

        return results
    }

    private fun evaluateRule(
        rule: MatchRule,
        hotMethods: List<HotMethod>,
        frames: List<FrameData>
    ): Boolean {
        var matched = false

        // 检查热点方法模式
        if (rule.hotMethodPattern.isNotEmpty()) {
            val patterns = rule.hotMethodPattern.split("|").map { it.trim() }
            val matchingMethods = hotMethods.filter { hot ->
                patterns.any { pattern -> globMatch(pattern, hot.method) }
            }

            if (matchingMethods.isEmpty()) return false

            // 如果还有百分比条件，检查匹配的方法占比
            if (rule.percentageCondition.isNotEmpty()) {
                val totalPercentage = matchingMethods.sumOf { it.percentage }
                if (!evaluateCondition(totalPercentage, rule.percentageCondition)) return false
            }
            matched = true
        }

        // 检查帧平均耗时
        if (rule.frameAvgMsCondition.isNotEmpty() && frames.isNotEmpty()) {
            val avgMs = frames.map { it.totalDurationMs }.average()
            if (!evaluateCondition(avgMs, rule.frameAvgMsCondition)) return false
            matched = true
        }

        // 检查帧最大耗时
        if (rule.frameMaxMsCondition.isNotEmpty() && frames.isNotEmpty()) {
            val maxMs = frames.maxOf { it.totalDurationMs }
            if (!evaluateCondition(maxMs, rule.frameMaxMsCondition)) return false
            matched = true
        }

        return matched
    }

    /**
     * 简单 glob 匹配: * 匹配任意字符。
     */
    private fun globMatch(pattern: String, text: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
        return Regex(regex, RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    /**
     * 解析条件表达式: ">20", ">=15.5", "<10"
     */
    private fun evaluateCondition(value: Double, condition: String): Boolean {
        val cleaned = condition.replace("%", "").trim()
        return when {
            cleaned.startsWith(">=") -> value >= cleaned.removePrefix(">=").toDouble()
            cleaned.startsWith("<=") -> value <= cleaned.removePrefix("<=").toDouble()
            cleaned.startsWith(">") -> value > cleaned.removePrefix(">").toDouble()
            cleaned.startsWith("<") -> value < cleaned.removePrefix("<").toDouble()
            cleaned.startsWith("==") -> value == cleaned.removePrefix("==").toDouble()
            else -> false
        }
    }

    private fun matchScene(skillScene: String, currentScene: String): Boolean {
        // 支持通配符: "list_*" 匹配 "list_scroll", "list_fling"
        return globMatch(skillScene, currentScene)
    }

    private fun buildEvidence(
        rule: MatchRule,
        hotMethods: List<HotMethod>,
        frames: List<FrameData>
    ): String {
        val parts = mutableListOf<String>()
        if (rule.hotMethodPattern.isNotEmpty()) {
            val patterns = rule.hotMethodPattern.split("|").map { it.trim() }
            val matched = hotMethods.filter { hot ->
                patterns.any { p -> globMatch(p, hot.method) }
            }
            if (matched.isNotEmpty()) {
                parts.add("匹配方法: ${matched.joinToString { "${it.method}(%.1f%%)".format(it.percentage) }}")
            }
        }
        if (frames.isNotEmpty()) {
            parts.add("帧率: avg=%.1fms, max=%.1fms".format(
                frames.map { it.totalDurationMs }.average(),
                frames.maxOf { it.totalDurationMs }
            ))
        }
        return parts.joinToString("; ")
    }

    private fun mapRootCauseType(type: String): RootCauseType {
        return when (type.uppercase()) {
            "GC_PRESSURE" -> RootCauseType.GC_PRESSURE
            "MAIN_THREAD_IO" -> RootCauseType.MAIN_THREAD_IO
            "HEAVY_LAYOUT" -> RootCauseType.HEAVY_LAYOUT
            "HEAVY_DRAW" -> RootCauseType.HEAVY_DRAW
            "SLOW_METHOD" -> RootCauseType.SLOW_METHOD
            "LOCK_CONTENTION" -> RootCauseType.LOCK_CONTENTION
            "BINDER_CALL" -> RootCauseType.BINDER_CALL
            else -> RootCauseType.UNKNOWN
        }
    }

    private fun mapConfidence(c: MatchConfidence): RootCause.Confidence {
        return when (c) {
            MatchConfidence.HIGH -> RootCause.Confidence.HIGH
            MatchConfidence.MEDIUM -> RootCause.Confidence.MEDIUM
            MatchConfidence.LOW -> RootCause.Confidence.LOW
        }
    }
}
