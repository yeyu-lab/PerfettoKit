package io.github.perfettokit.skill

import android.content.Context
import android.util.Log
import java.io.InputStream

/**
 * YAML Skill 加载器。
 *
 * 从 assets/skills/ 目录加载 .yaml 规则文件，解析为 Skill 对象。
 * 开发者也可以从自定义路径加载。
 *
 * 不依赖 SnakeYAML，使用轻量级自定义解析（YAML 子集）。
 */
class SkillLoader {

    companion object {
        private const val TAG = "PerfettoKit.Skill"
        private const val SKILLS_DIR = "perfettokit/skills"
    }

    /**
     * 从 assets 目录加载所有内置 Skills。
     */
    fun loadFromAssets(context: Context): List<Skill> {
        val skills = mutableListOf<Skill>()
        try {
            val files = context.assets.list(SKILLS_DIR) ?: return emptyList()
            for (file in files) {
                if (file.endsWith(".yaml") || file.endsWith(".yml")) {
                    val input = context.assets.open("$SKILLS_DIR/$file")
                    val skill = parse(input, file)
                    if (skill != null) {
                        skills.add(skill)
                        Log.d(TAG, "Loaded skill: ${skill.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load skills from assets: ${e.message}")
        }
        return skills
    }

    /**
     * 从 InputStream 解析单个 Skill 文件。
     */
    fun parse(input: InputStream, fileName: String = "unknown"): Skill? {
        return try {
            val content = input.bufferedReader().readText()
            parseYaml(content, fileName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse skill $fileName: ${e.message}")
            null
        }
    }

    /**
     * 从 YAML 字符串解析 Skill。
     * 支持的 YAML 子集：顶层 key-value、match 块、列表。
     */
    fun parseYaml(content: String, fileName: String = "unknown"): Skill? {
        val lines = content.lines()
        var name = fileName.removeSuffix(".yaml").removeSuffix(".yml")
        var description = ""
        var scene = ""
        val matchRules = mutableListOf<MatchRule>()
        var rootCauseType = ""
        var confidence = MatchConfidence.MEDIUM
        val suggestions = mutableListOf<String>()

        var inMatch = false
        var inSuggestion = false
        var currentMatchMethod = ""
        var currentMatchPercentage = ""
        var currentMatchFrameAvg = ""
        var currentMatchFrameMax = ""

        for (line in lines) {
            val trimmed = line.trim()

            // 跳过注释和空行
            if (trimmed.startsWith("#") || trimmed.isEmpty()) continue

            // 顶层 key: value
            when {
                trimmed.startsWith("name:") -> {
                    name = extractValue(trimmed)
                    inMatch = false; inSuggestion = false
                }
                trimmed.startsWith("description:") -> {
                    description = extractValue(trimmed)
                    inMatch = false; inSuggestion = false
                }
                trimmed.startsWith("scene:") -> {
                    scene = extractValue(trimmed)
                    inMatch = false; inSuggestion = false
                }
                trimmed.startsWith("rootCause:") -> {
                    rootCauseType = extractValue(trimmed)
                    inMatch = false; inSuggestion = false
                }
                trimmed.startsWith("confidence:") -> {
                    confidence = when (extractValue(trimmed).uppercase()) {
                        "HIGH" -> MatchConfidence.HIGH
                        "LOW" -> MatchConfidence.LOW
                        else -> MatchConfidence.MEDIUM
                    }
                    inMatch = false; inSuggestion = false
                }
                trimmed == "match:" -> {
                    inMatch = true; inSuggestion = false
                }
                trimmed == "suggestion:" || trimmed.startsWith("suggestion:") -> {
                    inSuggestion = true; inMatch = false
                    val inline = extractValue(trimmed)
                    if (inline.isNotEmpty() && !inline.startsWith("|")) {
                        suggestions.add(inline)
                    }
                }
                inMatch -> {
                    when {
                        trimmed.startsWith("hotMethod:") -> currentMatchMethod = extractValue(trimmed)
                        trimmed.startsWith("percentage:") -> currentMatchPercentage = extractValue(trimmed)
                        trimmed.startsWith("frameAvgMs:") -> currentMatchFrameAvg = extractValue(trimmed)
                        trimmed.startsWith("frameMaxMs:") -> currentMatchFrameMax = extractValue(trimmed)
                        trimmed.startsWith("-") -> {
                            // 新的 match block，保存上一个
                            flushMatchRule(
                                currentMatchMethod, currentMatchPercentage,
                                currentMatchFrameAvg, currentMatchFrameMax, matchRules
                            )
                            currentMatchMethod = ""; currentMatchPercentage = ""
                            currentMatchFrameAvg = ""; currentMatchFrameMax = ""
                        }
                    }
                }
                inSuggestion -> {
                    if (trimmed.startsWith("-")) {
                        suggestions.add(trimmed.removePrefix("-").trim())
                    } else if (!trimmed.startsWith("|")) {
                        suggestions.add(trimmed)
                    }
                }
            }
        }

        // 最后一个 match rule
        flushMatchRule(
            currentMatchMethod, currentMatchPercentage,
            currentMatchFrameAvg, currentMatchFrameMax, matchRules
        )

        if (matchRules.isEmpty() && rootCauseType.isEmpty()) return null

        return Skill(
            name = name,
            description = description,
            scene = scene,
            matchRules = matchRules,
            rootCauseType = rootCauseType,
            confidence = confidence,
            suggestions = suggestions
        )
    }

    private fun flushMatchRule(
        method: String, percentage: String,
        frameAvg: String, frameMax: String,
        list: MutableList<MatchRule>
    ) {
        if (method.isNotEmpty() || percentage.isNotEmpty() ||
            frameAvg.isNotEmpty() || frameMax.isNotEmpty()
        ) {
            list.add(
                MatchRule(
                    hotMethodPattern = method,
                    percentageCondition = percentage,
                    frameAvgMsCondition = frameAvg,
                    frameMaxMsCondition = frameMax
                )
            )
        }
    }

    private fun extractValue(line: String): String {
        val idx = line.indexOf(':')
        if (idx < 0) return ""
        return line.substring(idx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
    }
}
