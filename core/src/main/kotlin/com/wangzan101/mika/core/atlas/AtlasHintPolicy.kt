package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.uistate.ScreenSig
import com.wangzan101.mika.core.uistate.ScreenSnapshot

/**
 * L1 页面提示注入策略（纯匹配与格式化逻辑）：
 * 在模型每一步读屏或操作落地后，仅将匹配到的历史成功路径（`ok > 0 && miss <= ok`）
 * 格式化为简短的 Zone 3 提示文本（1–3 行，<= 350 字符），严禁进入 Zone 0/1/2 以保持前缀缓存稳定。
 */
class AtlasHintPolicy(
    private val matchThreshold: Double = ScreenSig.DEFAULT_MATCH_THRESHOLD,
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    init {
        require(maxLines >= 2) { "maxLines must be at least 2" }
        require(maxChars >= HEADER.length + 16) { "maxChars too small" }
    }

    /** 一次成功匹配并经过脱敏与长度裁剪的 L1 页面提示。 */
    data class Hint(
        val packageName: String,
        val screenSigId: String,
        val screenTitle: String,
        val unverifiedAfterUpgrade: Boolean,
        val text: String,
    )

    /**
     * 基于当前屏幕快照 [snapshot] 从 [store] 检索并格式化历史导航提示。
     * - 自动忽略受防截屏保护（`protected`）或空白签名的快照；
     * - 忽略 `ok == 0` 或 `miss > ok` 的不可靠边；
     * - 若 [seenScreens] 中已记录过同包同页面，返回 null 避免重复注入；
     * - 返回非空提示时将 `"$packageName|$screenSigId"` 写入 [seenScreens]。
     */
    fun hintFor(
        snapshot: ScreenSnapshot?,
        store: AtlasStore?,
        seenScreens: MutableSet<String>? = null,
    ): Hint? {
        if (snapshot == null || store == null || snapshot.protected) return null
        val sig = snapshot.resolvedScreenSig() ?: return null
        val pkg = (sig.packageName ?: snapshot.packageName)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val appAtlas = store.get(pkg) ?: return null

        val idx = AtlasEviction.findMatchingScreenIndex(appAtlas.screens, pkg, sig, matchThreshold)
        if (idx < 0) return null
        val matched = appAtlas.screens[idx]

        val dedupKey = "$pkg|${matched.sigId}"
        if (seenScreens != null && dedupKey in seenScreens) return null

        val validEdges = appAtlas.outgoingEdges(matched.sigId)
            .filter { it.ok > 0 && it.miss <= it.ok }
            .sortedWith(
                compareByDescending<AtlasEdge> { it.ok - it.miss }
                    .thenByDescending { it.ok }
                    .thenByDescending { it.lastOkMs },
            )

        val activeShortcuts = appAtlas.activeShortcutsFor(matched.sigId)
            .sortedWith(
                compareByDescending<AtlasShortcut> { it.successes - it.consecutiveMisses }
                    .thenByDescending { it.successes }
                    .thenByDescending { it.lastUsedMs },
            )

        val detailLines = buildDetailLines(appAtlas, matched, validEdges, activeShortcuts)
        if (detailLines.isEmpty()) return null

        val screenTitle = AtlasSanitizer.cleanSingleLine(matched.displayTitle(), AtlasSanitizer.MAX_TITLE_LEN)
            ?: matched.fallbackTitle()
        val upgradeNote = if (matched.unverifiedAfterUpgrade) " [unverified after update]" else ""
        val header = "$HEADER $screenTitle$upgradeNote"

        val text = assembleClipped(header, detailLines)
        seenScreens?.add(dedupKey)
        return Hint(
            packageName = pkg,
            screenSigId = matched.sigId,
            screenTitle = screenTitle,
            unverifiedAfterUpgrade = matched.unverifiedAfterUpgrade,
            text = text,
        )
    }

    private fun buildDetailLines(
        atlas: AppAtlas,
        matched: AtlasScreen,
        validEdges: List<AtlasEdge>,
        activeShortcuts: List<AtlasShortcut>,
    ): List<String> {
        val maxDetails = (maxLines - 1).coerceAtLeast(1)
        val out = mutableListOf<String>()

        for (sc in activeShortcuts) {
            if (out.size >= maxDetails) break
            val cleanName = AtlasSanitizer.cleanSingleLine(sc.name, 28) ?: continue
            val slotNote = if (sc.slots > 0) ", slots:${sc.slots}" else ""
            out += "- shortcut '$cleanName' [id:${sc.id}$slotNote, steps:${sc.steps.size}]"
            break
        }

        for (edge in validEdges) {
            if (out.size >= maxDetails) break
            val verb = edge.tool.removePrefix("phone_").removePrefix("web_")
            val targetDesc = describeTarget(edge.target, matched.controls)
            val dest = if (edge.toSigId == matched.sigId) {
                "current screen"
            } else {
                atlas.findScreen(edge.toSigId)?.let { destScreen ->
                    AtlasSanitizer.cleanSingleLine(destScreen.displayTitle(), AtlasSanitizer.MAX_TITLE_LEN)
                        ?: destScreen.fallbackTitle()
                } ?: ScreenSig.shortHashTag(edge.toSigId)
            }
            val stats = if (edge.miss > 0) "(ok:${edge.ok}, miss:${edge.miss})" else "(ok:${edge.ok})"
            val actionPart = listOfNotNull(verb.takeIf { it.isNotBlank() }, targetDesc.takeIf { it.isNotBlank() }).joinToString(" ")
            out += "- $actionPart -> $dest $stats"
        }

        if (out.isEmpty()) {
            val reliableControls = matched.controls
                .filter { it.used > 0 && it.noEffect * 2 <= it.used && it.meaning.isNotBlank() }
                .sortedWith(compareByDescending<AtlasControl> { it.used - it.noEffect }.thenByDescending { it.used })
            for (ctrl in reliableControls) {
                if (out.size >= maxDetails) break
                val meaning = AtlasSanitizer.cleanSingleLine(ctrl.meaning, AtlasSanitizer.MAX_MEANING_LEN) ?: continue
                val desc = describeTarget(ctrl.selector, emptyList()).ifBlank { ctrl.selector.role }
                out += "- control $desc: $meaning"
            }
        }
        return out
    }

    private fun describeTarget(target: Selector?, controls: List<AtlasControl>): String {
        if (target == null) return ""
        val safeName = AtlasSanitizer.cleanSingleLine(target.name, 20)
        val safeRid = target.resourceId?.trim()
            ?.takeIf { it.isNotBlank() && AtlasSanitizer.isSafeText(it) }
            ?.substringAfterLast('/')
            ?.take(24)
        val meaning = controls.firstOrNull { AtlasEviction.sameControl(it.selector, target) }
            ?.meaning
            ?.let { AtlasSanitizer.cleanSingleLine(it, 24) }

        return when {
            safeName != null -> buildString {
                append('\'').append(safeName).append('\'')
                val extra = meaning?.takeIf { it != safeName } ?: safeRid
                if (extra != null) append(" [").append(extra).append(']')
            }
            safeRid != null -> if (meaning != null) "[$safeRid] ($meaning)" else "[$safeRid]"
            else -> if (meaning != null) "${target.role} ($meaning)" else target.role
        }
    }

    private fun assembleClipped(header: String, detailLines: List<String>): String {
        val kept = detailLines.toMutableList()
        while (kept.size > 1) {
            val joined = (listOf(header) + kept).joinToString("\n")
            if (joined.length <= maxChars) return joined
            kept.removeAt(kept.lastIndex)
        }
        val single = (listOf(header) + kept).joinToString("\n")
        return if (single.length <= maxChars) single else single.take(maxChars - 1).trimEnd() + "…"
    }

    companion object {
        const val DEFAULT_MAX_LINES = 3
        const val DEFAULT_MAX_CHARS = 350
        const val HEADER = "Atlas hint (historical, verify on current screen):"
        val Default = AtlasHintPolicy()
    }
}
