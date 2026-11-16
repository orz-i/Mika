package com.wangzan101.mika.core.custody

import com.wangzan101.mika.core.uistate.Hant

/** 跳转目标别名映射规则的来源：自动学习（用户首次手动放行后记住）或用户手动配置。 */
enum class JumpProfileSource {
    LEARNED,
    MANUAL,
}

/**
 * 方案 2 兜底路由映射条目：将「发起应用 + 弹窗目标展示文案」映射到实际落地的目标包名。
 * 即使命中映射，最终仍严格受 `JumpConfirmPolicy`（应用访问、按钮校验、敏感拦截等）护栏约束。
 */
data class JumpConfirmProfile(
    val id: String,
    val initiatorPackage: String,
    val targetLabel: String,
    val targetPackage: String,
    val enabled: Boolean = true,
    val source: JumpProfileSource = JumpProfileSource.LEARNED,
    val updatedAtMs: Long = 0L,
)

/** 全部别名映射与自动学习开关的只读快照。 */
data class JumpConfirmProfileState(
    val profiles: List<JumpConfirmProfile> = emptyList(),
    val autoLearnEnabled: Boolean = true,
    val available: Boolean = true,
)

/** `:core` 与 `DisplayCustodian` 共用的映射策略接口（解耦 Android 持久化实现）。 */
interface JumpConfirmProfilePolicy {
    fun current(): JumpConfirmProfileState

    fun recordLearned(
        initiatorPackage: String,
        targetLabel: String,
        targetPackage: String,
        nowMs: Long = System.currentTimeMillis(),
    )

    companion object {
        val EMPTY: JumpConfirmProfilePolicy = object : JumpConfirmProfilePolicy {
            override fun current(): JumpConfirmProfileState = JumpConfirmProfileState()
            override fun recordLearned(
                initiatorPackage: String,
                targetLabel: String,
                targetPackage: String,
                nowMs: Long,
            ) = Unit
        }
    }
}

/** 别名文案归一化与规则匹配工具。 */
object JumpConfirmProfileMatcher {
    private val QUOTES_REGEX = Regex("""[“”"'「」『』]""")
    private val TRAILING_PUNCT_REGEX = Regex("""[？?！!。．.:：]+$""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")
    private val CJK_INNER_SPACE_REGEX = Regex("""(?<=[\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])""")

    fun normalizeLabel(raw: String): String {
        val folded = Hant.fold(raw)
        val noQuotes = QUOTES_REGEX.replace(folded, "")
        val trimmed = TRAILING_PUNCT_REGEX.replace(noQuotes.trim(), "").trim()
        val collapsed = MULTI_SPACE_REGEX.replace(trimmed, " ")
        return CJK_INNER_SPACE_REGEX.replace(collapsed, "").lowercase()
    }

    fun profileId(initiatorPackage: String, targetLabel: String): String =
        "${initiatorPackage.trim().lowercase()}|${normalizeLabel(targetLabel)}"

    fun match(
        profiles: List<JumpConfirmProfile>,
        initiatorPackage: String?,
        targetLabel: String?,
        isPackageInstalled: (String) -> Boolean = { true },
    ): JumpConfirmProfile? {
        val cleanInitiator = initiatorPackage?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normLabel = targetLabel?.let(::normalizeLabel)?.takeIf { it.isNotEmpty() } ?: return null
        val targetId = profileId(cleanInitiator, normLabel)

        return profiles
            .asSequence()
            .filter { p ->
                p.enabled &&
                    p.targetPackage.isNotBlank() &&
                    !p.targetPackage.equals(cleanInitiator, ignoreCase = true) &&
                    (p.id == targetId ||
                        (p.initiatorPackage.equals(cleanInitiator, ignoreCase = true) &&
                            normalizeLabel(p.targetLabel) == normLabel)) &&
                    isPackageInstalled(p.targetPackage)
            }
            .sortedWith(
                compareByDescending<JumpConfirmProfile> { it.source == JumpProfileSource.MANUAL }
                    .thenByDescending { it.updatedAtMs },
            )
            .firstOrNull()
    }
}
