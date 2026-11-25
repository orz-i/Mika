package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.memory.SensitiveFilter
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.agent.use.ui.RouteStep
import com.agent.use.ui.Selector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import com.agent.use.ui.AtlasEviction
import com.agent.use.ui.MAX_ATLAS_ANCHORS
import com.agent.use.ui.MAX_ATLAS_RESOURCE_IDS
import com.agent.use.ui.MAX_SHORTCUT_STEPS

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
    private val SLOT = Regex("\\{([1-9][0-9]*)\\}")
    private val TRANSIENT_ARGS = setOf("ref", "snapshotId", "target", "reason")
    private val INPUT_ROLES = setOf("edit", "edittext", "input", "textfield", "textinput")

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

    /**
     * 这一步是在往输入框里写字（或清掉里面的字）。这类动作的目标元素上带着用户这次输入的内容，
     * 既不是这一页的地标，也不该进图谱。
     */
    fun isTextInputTool(tool: String): Boolean = tool.endsWith("_type_text") || tool.endsWith("_clear_text")

    private fun isInputSelector(selector: Selector): Boolean = selector.role.substringAfterLast('.').lowercase() in INPUT_ROLES

    fun cleanSelector(sel: Selector, isTypeAction: Boolean = false): Selector {
        // 输入框里的字是用户这一次打进去的内容(搜索词、账号),不是这一页的地标:既不进图谱,也不该出现在提示里
        // 让人以为「这条路径只能在那个输入框里打那几个字」。
        val safeName = if (isTypeAction) "" else sel.name.trim().let { n ->
            if (n.isEmpty() || n.length > MAX_SELECTOR_NAME_LEN || n == Redactor.MASK || !isSafeText(n)) ""
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

    /** 可复用输入只能保存槽，普通搜索词也不是可以落盘/分享的常量。 */
    fun sanitizeRouteStep(step: RouteStep): RouteStep? {
        if (step.argsJson.contains(Redactor.MASK) || !isSafeText(step.argsJson)) return null
        val args = parseJsonObject(step.argsJson) ?: return null
        val isType = step.tool.endsWith("_type_text")
        if (isType) {
            val text = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (!SLOT.matches(text)) return null
        }
        val target = step.target?.let { t ->
            if (!isSafeText(t.name) || t.name == Redactor.MASK) return null
            cleanSelector(t, isTypeAction = isTextInputTool(step.tool))
        }
        return step.copy(
            argsJson = JsonObject(args.filterKeys { it !in TRANSIENT_ARGS }).toString(),
            target = target,
            before = step.before.copy(texts = cleanAnchors(step.before.texts)),
            after = step.after.copy(texts = cleanAnchors(step.after.texts)),
        )
    }

    /** 清洗并脱敏一个快捷流程供存储或导出；任一文本/步骤含敏感信息则返回 null。 */
    fun sanitizeShortcut(shortcut: AtlasShortcut, stripRuntimeStats: Boolean = false): AtlasShortcut? {
        val cleanName = cleanSingleLine(shortcut.name, 48) ?: return null
        val cleanDesc = cleanSingleLine(shortcut.description, 120).orEmpty()
        val cleanSteps = sanitizeSteps(shortcut.steps, shortcut.slots) ?: return null
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

    private fun sanitizeSteps(steps: List<RouteStep>, slots: Int): List<RouteStep>? {
        if (slots !in 0..10 || steps.isEmpty() || steps.size > MAX_SHORTCUT_STEPS) return null
        val clean = steps.map { sanitizeRouteStep(it) ?: return null }
        if (clean.any { step ->
                SLOT.findAll(step.argsJson + "\u0000" + step.target?.name.orEmpty())
                    .any { it.groupValues[1].toIntOrNull() !in 1..slots }
            }) return null
        return clean
    }

    /** 旧输入只清理受影响的数据；页面、计数与其他路线保留。供学习、存储迁移及导出共用。 */
    fun cleanStoredAtlas(atlas: AppAtlas, inputValues: Set<String> = emptySet()): AppAtlas {
        val inputEdges = atlas.edges.filter { isTextInputTool(it.tool) }
        val steps = atlas.shortcuts.flatMap { it.steps } + atlas.candidatePatterns.flatMap { it.steps }
        val inputNames = atlas.screens.flatMap { it.controls }.filter { isInputSelector(it.selector) }.map { it.selector.name }
        val privateValues = (inputValues + inputNames + inputEdges.mapNotNull { it.target?.name } + steps.mapNotNull { step ->
            if (!step.tool.endsWith("_type_text")) null
            else (parseJsonObject(step.argsJson)?.get("text") as? JsonPrimitive)?.content
        }).filter { it.isNotBlank() && !SLOT.matches(it) }.toSet()
        fun containsInput(text: String) = privateValues.any { it in text }

        val screens = atlas.screens.map { screen ->
            val inputs = inputEdges.filter { it.fromSigId == screen.sigId }.mapNotNull { it.target }
            val controls = screen.controls.map { control ->
                val selector = control.selector
                val input = isInputSelector(selector) ||
                    inputs.any { AtlasEviction.sameTextInputTarget(it, selector) }
                val cleaned = cleanSelector(selector, isTypeAction = input || containsInput(selector.name))
                control.copy(selector = cleaned, meaning = control.meaning.takeUnless(::containsInput).orEmpty())
            }
            // 用户主动命名不是意外回显，不因恰好与查询同名而覆盖；导出仍过敏感信息过滤。
            val privateTitle = screen.titleSource != AtlasScreen.TITLE_USER && containsInput(screen.title)
            screen.copy(
                anchors = screen.anchors.filterNot(::containsInput),
                title = if (privateTitle) screen.fallbackTitle() else screen.title,
                titleSource = if (privateTitle) AtlasScreen.TITLE_ACTIVITY else screen.titleSource,
                controls = controls,
            )
        }
        fun cleanStep(step: RouteStep): RouteStep? {
            val clean = sanitizeRouteStep(step) ?: return null
            // 不能删除一个执行参数后继续回放；已知输入残留在参数中时整段作废。
            if (parseJsonObject(clean.argsJson)!!.values.any { value ->
                    value is JsonPrimitive && value.isString && containsInput(value.content)
                }) return null
            return clean.copy(
                target = clean.target?.let { cleanSelector(it, isTypeAction = isTextInputTool(clean.tool) || containsInput(it.name)) },
                before = clean.before.copy(texts = clean.before.texts.filterNot(::containsInput)),
                after = clean.after.copy(texts = clean.after.texts.filterNot(::containsInput)),
            )
        }
        val cleaned = atlas.copy(
            screens = screens,
            edges = atlas.edges.map { edge -> edge.copy(target = edge.target?.let {
                cleanSelector(it, isTypeAction = isTextInputTool(edge.tool) || containsInput(it.name))
            }) },
            shortcuts = atlas.shortcuts.mapNotNull { shortcut ->
                if (containsInput(shortcut.name) || containsInput(shortcut.description)) return@mapNotNull null
                val cleanSteps = shortcut.steps.map { cleanStep(it) ?: return@mapNotNull null }
                sanitizeShortcut(shortcut.copy(steps = cleanSteps))
            },
            candidatePatterns = atlas.candidatePatterns.mapNotNull { candidate ->
                if (containsInput(candidate.signatureKey)) return@mapNotNull null
                val stepsWithoutInput = candidate.steps.map { cleanStep(it) ?: return@mapNotNull null }
                val cleanSteps = sanitizeSteps(stepsWithoutInput, candidate.slots) ?: return@mapNotNull null
                candidate.copy(steps = cleanSteps)
            },
        )
        return if (cleaned == atlas) atlas else cleaned
    }

    private fun parseJsonObject(raw: String): JsonObject? = try {
        Json.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    }
}
