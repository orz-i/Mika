package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.memory.SensitiveFilter
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.core.replay.Selector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Atlas 隐私与敏感信息统一过滤门禁（供 Learner / Namer / Share 共用）：
 * 拦截密码框输入、受系统防截屏保护屏、API Key / 身份证号 / 银行卡号 / 验证码等敏感文本及 Mika 自身包名。
 */
internal object AtlasSanitizer {
    const val MAX_ANCHOR_LEN = 40
    const val MAX_TITLE_LEN = 24
    const val MAX_MEANING_LEN = 32
    const val MAX_SELECTOR_NAME_LEN = 24
    private val WHITESPACE = Regex("\\s+")

    /** 判断包名是否合法且不属于 Mika 自身。 */
    fun cleanPackage(pkg: String?, selfPackage: String): String? {
        val p = pkg?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isSelfPackage(p, selfPackage)) return null
        return p
    }

    fun isSelfPackage(pkg: String?, selfPackage: String): Boolean {
        val p = pkg?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return p == selfPackage || p.startsWith("$selfPackage.")
    }

    /** 校验文本不触发敏感信息过滤器与脱敏规则。 */
    fun isSafeText(text: String): Boolean {
        if (text.isBlank()) return true
        return SensitiveFilter.hit(text) == null &&
            Redactor.text(text) == text &&
            Redactor.evidence(text) == text
    }

    fun cleanSingleLine(text: String?, maxLen: Int): String? {
        val norm = text?.replace('\r', ' ')?.replace('\n', ' ')?.let { WHITESPACE.replace(it, " ") }?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= maxLen } ?: return null
        return norm.takeIf(::isSafeText)
    }

    fun cleanAnchors(anchors: List<String>, max: Int = MAX_ATLAS_ANCHORS): List<String> =
        anchors.mapNotNull { cleanSingleLine(it, MAX_ANCHOR_LEN) }.distinct().take(max)

    fun cleanResourceIds(ids: List<String>, max: Int = MAX_ATLAS_RESOURCE_IDS): List<String> =
        ids.mapNotNull { it.trim().takeIf { id -> id.isNotEmpty() && id.length <= 64 && isSafeText(id) } }
            .distinct()
            .take(max)

    fun cleanSelector(sel: Selector, isTypeAction: Boolean = false): Selector {
        val safeName = sel.name.trim().let { n ->
            if (n.isEmpty() || n.length > MAX_SELECTOR_NAME_LEN || n == Redactor.MASK || !isSafeText(n)) ""
            else if (isTypeAction && n == Redactor.MASK) ""
            else n
        }
        val safeRid = sel.resourceId?.trim()?.takeIf { it.isNotEmpty() && isSafeText(it) }
        return sel.copy(
            name = safeName,
            resourceId = safeRid,
            uniqueAtRecord = if (safeName.isEmpty() && sel.name.isNotEmpty()) false else sel.uniqueAtRecord,
        )
    }

    /** 判断动作事件是否涉及受保护页面或密码/敏感输入。 */
    fun isSecretOrProtectedAction(event: RunEvent.ActionTarget, pairedResult: RunEvent.ToolResult?): Boolean {
        if (event.protected) return true
        if (pairedResult != null) {
            val resObj = parseJsonObject(pairedResult.resultJson)
            if (resObj != null) {
                if ((resObj["secret"] as? JsonPrimitive)?.booleanOrNull == true) return true
                if ((resObj["protected"] as? JsonPrimitive)?.booleanOrNull == true) return true
            }
        }
        val argsObj = parseJsonObject(event.argsJson)
        if (argsObj != null) {
            val textVal = (argsObj["text"] as? JsonPrimitive)?.contentOrNull
            if (textVal == Redactor.MASK) return true
            if (textVal != null && !isSafeText(textVal)) return true
        } else if (event.argsJson.contains(Redactor.MASK)) {
            return true
        }
        if (SensitiveFilter.hit(event.argsJson) != null || Redactor.text(event.argsJson) != event.argsJson) {
            return true
        }
        return false
    }

    /** 清洗单步可复用动作（导出与挖掘共用），含敏感字样或遮罩符时返回 null。 */
    fun sanitizeRouteStep(step: RouteStep): RouteStep? {
        if (step.argsJson.contains(Redactor.MASK) || !isSafeText(step.argsJson)) return null
        val isType = step.tool.endsWith("_type_text")
        val target = step.target?.let { t ->
            if (!isSafeText(t.name) || t.name == Redactor.MASK) return null
            cleanSelector(t, isTypeAction = isType)
        }
        return step.copy(
            target = target,
            before = step.before.copy(texts = cleanAnchors(step.before.texts)),
            after = step.after.copy(texts = cleanAnchors(step.after.texts)),
        )
    }

    /** 清洗并脱敏一个快捷流程供存储或导出；任一文本/步骤含敏感信息则返回 null。 */
    fun sanitizeShortcut(shortcut: AtlasShortcut, stripRuntimeStats: Boolean = false): AtlasShortcut? {
        val cleanName = cleanSingleLine(shortcut.name, 48) ?: return null
        val cleanDesc = cleanSingleLine(shortcut.description, 120).orEmpty()
        val cleanSteps = shortcut.steps.map { sanitizeRouteStep(it) ?: return null }
        if (cleanSteps.isEmpty() || cleanSteps.size > MAX_SHORTCUT_STEPS) return null
        return shortcut.copy(
            name = cleanName,
            description = cleanDesc,
            steps = cleanSteps,
            successes = if (stripRuntimeStats) 0 else shortcut.successes.coerceAtLeast(0),
            consecutiveMisses = if (stripRuntimeStats) 0 else shortcut.consecutiveMisses.coerceAtLeast(0),
            missStep = if (stripRuntimeStats) null else shortcut.missStep,
            disabled = if (stripRuntimeStats) false else shortcut.disabled,
            lastUsedMs = if (stripRuntimeStats) 0L else shortcut.lastUsedMs.coerceAtLeast(0L),
        )
    }

    private fun parseJsonObject(raw: String): JsonObject? = try {
        Json.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    }
}
