package io.github.perfettokit.skill

/**
 * 一个 YAML Skill 定义。
 */
data class Skill(
    val name: String,
    val description: String = "",
    val scene: String = "",          // 适用场景，空 = 通用
    val matchRules: List<MatchRule>,  // 匹配条件（任一命中即触发）
    val rootCauseType: String,       // 对应的根因类型
    val confidence: MatchConfidence,
    val suggestions: List<String>
)

/**
 * 单条匹配规则。
 */
data class MatchRule(
    val hotMethodPattern: String = "",      // glob 模式: "*GC*|*finalize*"
    val percentageCondition: String = "",   // ">20%", ">=15%"
    val frameAvgMsCondition: String = "",   // ">16.67"
    val frameMaxMsCondition: String = ""    // ">33.33"
)

enum class MatchConfidence { LOW, MEDIUM, HIGH }
