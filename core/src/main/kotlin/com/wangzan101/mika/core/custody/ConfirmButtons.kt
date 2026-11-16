package com.wangzan101.mika.core.custody

import com.wangzan101.mika.core.uistate.Hant

/** 跳转确认弹窗中单个候选按钮的语义角色。 */
enum class ConfirmButtonRole {
    /** 非持久的肯定/放行按钮（如「允许」「打开」「仅本次允许」「Allow」）。 */
    POSITIVE,

    /** 含有持久授权词的肯定按钮（如「始终允许」「总是打开」「Always allow」），绝对禁止自动点击。 */
    PERSISTENT_POSITIVE,

    /** 否定/拒绝/取消按钮（如「拒绝」「取消」「不允许」「Deny」「Cancel」「Don't allow」）。 */
    NEGATIVE,

    /** 含持久授权词的其他按钮或标签（如「记住我的选择」「不再询问」「Remember」）。 */
    PERSISTENT_OTHER,

    /** 未能归类的按钮文案。 */
    UNKNOWN,
}

/** 传给 [ConfirmButtons] 的按钮输入项。 */
data class ConfirmButtonInput(
    val text: String,
    val id: String? = null,
    val enabled: Boolean = true,
    val clickable: Boolean = true,
)

/** 经 [ConfirmButtons] 分类后的按钮记录。 */
data class ConfirmButton(
    val index: Int,
    val id: String?,
    val rawText: String,
    val foldedText: String,
    val role: ConfirmButtonRole,
    val enabled: Boolean = true,
    val clickable: Boolean = true,
)

/** 按钮结构校验失败时的具体原因。 */
enum class ConfirmButtonRejectReason {
    /** 未识别到可用、可点的非持久肯定按钮（含持久肯定按钮被排除后无可用肯定按钮的情况）。 */
    NO_POSITIVE_BUTTON,

    /** 存在持久授权按钮或禁止自动点击的持久授权词（如「始终允许」「Always allow」）。 */
    PERSISTENT_BUTTON_FORBIDDEN,

    /** 存在多个肯定按钮，无法唯一确定应当点击哪一个。 */
    MULTIPLE_POSITIVE_BUTTONS,

    /** 缺少否定/拒绝/取消按钮，不满足安全弹窗结构要求。 */
    NO_NEGATIVE_BUTTON,
}

/** [ConfirmButtons.analyze] 的完整结构分析结果。 */
data class ConfirmButtonAnalysis(
    val valid: Boolean,
    val positiveButton: ConfirmButton?,
    val negativeButtons: List<ConfirmButton>,
    val persistentButtons: List<ConfirmButton>,
    val allButtons: List<ConfirmButton>,
    val rejectReason: ConfirmButtonRejectReason?,
)

/**
 * 跳转授权确认弹窗按钮词表与结构判定器。
 *
 * 利用 [Hant.fold] 统一简繁中文并兼容英文，满足：
 * - 严格排除「始终 / 总是 / 记住 / 不再询问 / Always / Remember」等持久授权词；
 * - 正确优先识别「不允许 / 不同意 / Don't allow」等否定词，避免被其中的肯定词干误匹配；
 * - 要求结构满足「**恰好一个肯定项且非持久授权项，且至少一个否定项**」。
 */
object ConfirmButtons {

    private val PERSISTENT_ZH = listOf(
        "不再询问",
        "不再提示",
        "不再提醒",
        "以后不再",
        "始终",
        "总是",
        "一律",
        "记住",
        "永久",
        "永远",
        "每次都",
        "每次",
        "一直允许",
        "默认",
    )

    private val PERSISTENT_EN_PHRASES = listOf(
        "always",
        "remember",
        "forever",
        "all the time",
        "never ask",
        "don't ask",
        "do not ask",
        "every time",
    )

    private val NEGATIVE_ZH_MULTI = listOf(
        "不允许",
        "不同意",
        "不打开",
        "不前往",
        "不确定",
        "不确认",
        "不继续",
        "不跳转",
        "不进入",
        "不开启",
        "不再允许",
        "暂不允许",
        "暂不打开",
        "暂不",
        "拒绝",
        "取消",
        "禁止",
        "放弃",
        "拦截",
        "阻止",
        "关闭",
        "返回",
        "退出",
        "不要",
        "不是",
        "不好",
        "算了",
        "下次再说",
        "以后再说",
        "忽略",
    )

    private val NEGATIVE_ZH_NEGATED_ACTION_REGEX =
        Regex("不(?:再|要)?(?:允许|同意|打开|前往|确定|确认|继续|跳转|进入|开启)")

    private val NEGATIVE_ZH_EXACT = setOf("否", "不")

    private val NEGATIVE_EN_PHRASES = listOf(
        "don't allow",
        "do not allow",
        "not allow",
        "disallow",
        "don't open",
        "do not open",
        "don't continue",
        "do not continue",
        "not now",
        "deny",
        "cancel",
        "reject",
        "decline",
        "refuse",
        "block",
        "forbid",
        "dismiss",
        "close",
        "never",
        "abort",
        "stop",
        "later",
        "no",
    )

    private val POSITIVE_ZH_MULTI = listOf(
        "立即打开",
        "立即前往",
        "仅此一次",
        "仅本次",
        "只这一次",
        "本次允许",
        "允许一次",
        "去打开",
        "去看看",
        "允许",
        "打开",
        "同意",
        "前往",
        "确定",
        "确认",
        "继续",
        "跳转",
        "进入",
        "开启",
        "放行",
    )

    private val POSITIVE_ZH_EXACT = setOf("好", "好的", "是")

    private val POSITIVE_EN_PHRASES = listOf(
        "allow once",
        "only this time",
        "just once",
        "allow",
        "open",
        "agree",
        "continue",
        "proceed",
        "confirm",
        "accept",
        "launch",
        "go",
        "ok",
        "yes",
    )

    private val APOSTROPHES_REGEX = Regex("[’`´]")
    private val PUNCT_BOUNDARY_REGEX = Regex("[()（）\\[\\]【】<>《》,，.。:：;；!！?？·/\\\\|\"“”]+")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /** 归一化文本（简繁折叠、弯引号统一、压缩空白），用于分类及勾选框文案判断。 */
    fun normalize(raw: String): String {
        val folded = Hant.fold(raw)
        val unifiedQuote = APOSTROPHES_REGEX.replace(folded, "'")
        return WHITESPACE_REGEX.replace(unifiedQuote.trim(), " ")
    }

    /** 判断某条文本（如勾选框文案）是否包含「始终/总是/记住/不再询问/Always/Remember」等持久授权词。 */
    fun containsPersistentKeyword(raw: String): Boolean {
        val norm = normalize(raw)
        if (norm.isEmpty()) return false
        return matchesPersistent(norm, norm.lowercase())
    }

    /** 对单个按钮文本分类。 */
    fun classify(raw: String): ConfirmButtonRole {
        val norm = normalize(raw)
        if (norm.isEmpty()) return ConfirmButtonRole.UNKNOWN
        val lower = norm.lowercase()

        val hasPersistent = matchesPersistent(norm, lower)
        val hasPositive = matchesPositive(norm, lower)
        val hasNegative = matchesNegative(norm, lower)

        if (hasPersistent) {
            return if (hasPositive && !hasNegative) {
                ConfirmButtonRole.PERSISTENT_POSITIVE
            } else {
                ConfirmButtonRole.PERSISTENT_OTHER
            }
        }

        // 否定优先于肯定，保证「不允许」「Don't allow」不会被「允许」「Allow」误判
        if (hasNegative) return ConfirmButtonRole.NEGATIVE
        if (hasPositive) return ConfirmButtonRole.POSITIVE
        return ConfirmButtonRole.UNKNOWN
    }

    /** 直接以字符串列表分析按钮结构。 */
    fun analyzeTexts(buttonTexts: List<String>): ConfirmButtonAnalysis =
        analyze(buttonTexts.map { ConfirmButtonInput(text = it) })

    /**
     * 分析弹窗中的全部按钮是否满足：
     * 「恰好一个肯定项且非持久授权项，且至少一个否定项」。
     */
    fun analyze(buttons: List<ConfirmButtonInput>): ConfirmButtonAnalysis {
        val classified = buttons.mapIndexed { index, item ->
            val folded = normalize(item.text)
            ConfirmButton(
                index = index,
                id = item.id,
                rawText = item.text,
                foldedText = folded,
                role = if (item.enabled && item.clickable) classify(item.text) else ConfirmButtonRole.UNKNOWN,
                enabled = item.enabled,
                clickable = item.clickable,
            )
        }

        val positives = classified.filter { it.role == ConfirmButtonRole.POSITIVE }
        val persistentPositives = classified.filter { it.role == ConfirmButtonRole.PERSISTENT_POSITIVE }
        val persistentAll = classified.filter {
            it.role == ConfirmButtonRole.PERSISTENT_POSITIVE || it.role == ConfirmButtonRole.PERSISTENT_OTHER
        }
        val negatives = classified.filter { it.role == ConfirmButtonRole.NEGATIVE }

        val totalPositiveLikeCount = positives.size + persistentPositives.size

        val rejectReason = when {
            persistentPositives.isNotEmpty() -> ConfirmButtonRejectReason.PERSISTENT_BUTTON_FORBIDDEN
            totalPositiveLikeCount == 0 && persistentAll.isNotEmpty() -> ConfirmButtonRejectReason.PERSISTENT_BUTTON_FORBIDDEN
            positives.isEmpty() -> ConfirmButtonRejectReason.NO_POSITIVE_BUTTON
            positives.size > 1 -> ConfirmButtonRejectReason.MULTIPLE_POSITIVE_BUTTONS
            negatives.isEmpty() -> ConfirmButtonRejectReason.NO_NEGATIVE_BUTTON
            else -> null
        }

        return ConfirmButtonAnalysis(
            valid = rejectReason == null,
            positiveButton = if (rejectReason == null) positives.single() else null,
            negativeButtons = negatives,
            persistentButtons = persistentAll,
            allButtons = classified,
            rejectReason = rejectReason,
        )
    }

    private fun matchesPersistent(norm: String, lower: String): Boolean {
        if (PERSISTENT_ZH.any { it in norm }) return true
        val cleaned = cleanEnglishText(lower)
        return PERSISTENT_EN_PHRASES.any { containsEnglishPhrase(cleaned, it) }
    }

    private fun matchesNegative(norm: String, lower: String): Boolean {
        val compactZh = norm.replace(" ", "")
        if (compactZh in NEGATIVE_ZH_EXACT) return true
        if (NEGATIVE_ZH_MULTI.any { it in compactZh }) return true
        if (NEGATIVE_ZH_NEGATED_ACTION_REGEX.containsMatchIn(compactZh)) return true
        val cleaned = cleanEnglishText(lower)
        return NEGATIVE_EN_PHRASES.any { containsEnglishPhrase(cleaned, it) }
    }

    private fun matchesPositive(norm: String, lower: String): Boolean {
        val compactZh = PUNCT_BOUNDARY_REGEX.replace(norm, "").replace(" ", "")
        if (compactZh in POSITIVE_ZH_EXACT) return true
        if (POSITIVE_ZH_MULTI.any { it in compactZh }) return true
        val cleaned = cleanEnglishText(lower)
        return POSITIVE_EN_PHRASES.any { containsEnglishPhrase(cleaned, it) }
    }

    private fun cleanEnglishText(lower: String): String {
        val stripped = PUNCT_BOUNDARY_REGEX.replace(lower, " ")
        return WHITESPACE_REGEX.replace(stripped.trim(), " ")
    }

    private fun containsEnglishPhrase(cleanedLower: String, phrase: String): Boolean {
        if (cleanedLower.isEmpty()) return false
        val escaped = Regex.escape(phrase)
        val pattern = Regex("(?<![a-z0-9'])" + escaped + "(?![a-z0-9'])")
        return pattern.containsMatchIn(cleanedLower)
    }
}
