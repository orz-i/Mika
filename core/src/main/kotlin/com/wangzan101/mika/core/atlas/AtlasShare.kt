package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.memory.SensitiveFilter
import com.wangzan101.mika.core.record.Redactor
import com.agent.use.ui.Route
import com.wangzan101.mika.core.replay.RouteFingerprint
import com.wangzan101.mika.core.replay.RouteShare
import com.wangzan101.mika.core.replay.RouteShareLint
import com.wangzan101.mika.core.replay.RouteStore
import com.agent.use.ui.involvesPackage
import com.wangzan101.mika.core.replay.withCurrentToolNames
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.core.tool.ToolIndex
import com.agent.use.ui.ScreenSig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.UUID
import com.agent.use.ui.AtlasEviction
import com.agent.use.ui.MAX_ATLAS_SCREENS
import com.agent.use.ui.MAX_ATLAS_EDGES
import com.agent.use.ui.MAX_SCREEN_CONTROLS
import com.agent.use.ui.MAX_ATLAS_ANCHORS
import com.agent.use.ui.MAX_ATLAS_RESOURCE_IDS
import com.agent.use.ui.MAX_ATLAS_VERSIONS
import com.agent.use.ui.MAX_ATLAS_SHORTCUTS
import com.agent.use.ui.MAX_SHORTCUT_STEPS
import com.agent.use.ui.ENTRY_SIG_ID

/**
 * 可分享与导出的单 App 经验包（包含脱敏后的 [AppAtlas] 与可选的关联路线 [RouteShare]）。
 * 导出时剥离本机时间戳、运行计数噪声与任何潜在敏感字段，并附带内容指纹 [fingerprint]；
 * 导入时先过 [lint] 校验格式、包名、容量上限、敏感信息与指纹一致性，再按 [ConflictMode] 合并或替换。
 */
@Serializable
data class AtlasShare(
    val schema: Int = SCHEMA,
    val atlas: AppAtlas,
    val routes: List<RouteShare> = emptyList(),
    val exportedAtMs: Long = 0L,
    val fingerprint: String,
) {
    init {
        require(schema == SCHEMA) { "unsupported atlas share schema: $schema; re-export with the current app" }
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    /** 导入冲突处置模式。 */
    enum class ConflictMode {
        /** 清空该包已有图谱与关联路线，用导入包完整替换。 */
        Replace,
        /** 将导入页面、控件、边与路线温和合并入本机现有数据（保留用户手工命名）。 */
        Merge,
    }

    sealed interface LintResult {
        data class Valid(val share: AtlasShare) : LintResult
        data class Rejected(val problems: List<String>) : LintResult
    }

    sealed interface ImportResult {
        data class Imported(val atlas: AppAtlas, val routesImported: Int) : ImportResult
        data class Rejected(val problems: List<String>) : ImportResult
    }

    companion object {
        // v1 指纹漏掉了执行字段，不能降级到旧校验；本机存储格式不受影响。
        const val SCHEMA = 2
        const val MAX_SHARE_BYTES = 1024 * 1024
        const val MAX_EXPORTED_ROUTES = 100
        private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
        private val SLOT_REF_REGEX = Regex("\\{(\\d+)\\}")
        private const val MAX_SHORTCUT_SLOTS = 10
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(raw: String): AtlasShare = json.decodeFromString(serializer(), raw)

        /**
         * 生成可分享的脱敏导出包：
         * 1. 剥离本机时间戳（归零）与本地升级待核实标记；
         * 2. 过滤任何命中 [SensitiveFilter] / [Redactor] 的锚点、标题、控件名或含义；
         * 3. 可选附带该包关联且未停用的路线（转为可移植 [RouteShare]）。
         */
        fun export(
            atlas: AppAtlas,
            routes: List<Route> = emptyList(),
            exportedAtMs: Long = 0L,
        ): AtlasShare {
            val cleanAtlas = sanitizeAtlasForExport(atlas.privacyCleaned())
            val pkg = cleanAtlas.packageName
            val cleanRoutes = routes
                .filter { !it.disabled && it.involvesPackage(pkg) }
                .take(MAX_EXPORTED_ROUTES)
                .mapNotNull { r -> sanitizeRouteForExport(r, pkg) }
            val fp = AtlasFingerprint.of(cleanAtlas, cleanRoutes)
            return AtlasShare(
                schema = SCHEMA,
                atlas = cleanAtlas,
                routes = cleanRoutes,
                exportedAtMs = exportedAtMs,
                fingerprint = fp,
            )
        }

        /** 对已解析的 [AtlasShare] 做全面安全与结构校验，返回问题列表（空列表表示通过）。 */
        fun lint(
            share: AtlasShare,
            tools: ToolIndex? = null,
            selfPackage: String = AtlasLearner.DEFAULT_SELF_PACKAGE,
        ): List<String> {
            val problems = mutableListOf<String>()
            val a = share.atlas
            val pkg = a.packageName.trim()
            if (pkg.isEmpty() || !PACKAGE_NAME_REGEX.matches(pkg)) {
                problems += "invalid packageName: ${a.packageName}"
            }
            if (AtlasSanitizer.isSelfPackage(pkg, selfPackage)) {
                problems += "cannot import self package: $pkg"
            }
            if (share.encode().toByteArray(Charsets.UTF_8).size > MAX_SHARE_BYTES) {
                problems += "share exceeds $MAX_SHARE_BYTES bytes"
            }
            if (AtlasFingerprint.of(a, share.routes) != share.fingerprint) {
                problems += "fingerprint does not match content"
            }
            if (a.screens.size > MAX_ATLAS_SCREENS) {
                problems += "too many screens: ${a.screens.size} > $MAX_ATLAS_SCREENS"
            }
            if (a.edges.size > MAX_ATLAS_EDGES) {
                problems += "too many edges: ${a.edges.size} > $MAX_ATLAS_EDGES"
            }
            if (!AtlasSanitizer.isSafeText(a.appLabel)) {
                problems += "sensitive content in appLabel"
            }
            val screenIds = a.screens.mapTo(HashSet()) { it.sigId }
            if (screenIds.size != a.screens.size) {
                problems += "duplicate screen sigId"
            }
            for (s in a.screens) {
                if (s.sigId.isBlank()) problems += "blank screen sigId"
                if (s.hits < 0) problems += "negative hits on screen ${s.sigId}"
                if (s.controls.size > MAX_SCREEN_CONTROLS) {
                    problems += "too many controls on screen ${s.sigId}: ${s.controls.size} > $MAX_SCREEN_CONTROLS"
                }
                if (!AtlasSanitizer.isSafeText(s.title)) problems += "sensitive content in title of ${s.sigId}"
                if (s.anchors.any { !AtlasSanitizer.isSafeText(it) }) problems += "sensitive content in anchors of ${s.sigId}"
                for (c in s.controls) {
                    if (c.used < 0 || c.noEffect < 0) problems += "negative control stats on ${s.sigId}"
                    if (!AtlasSanitizer.isSafeText(c.meaning)) problems += "sensitive content in control meaning on ${s.sigId}"
                    if (!AtlasSanitizer.isSafeText(c.selector.name)) problems += "sensitive content in control selector on ${s.sigId}"
                }
            }
            for (e in a.edges) {
                val fromMapped = e.fromSigId == ENTRY_SIG_ID || e.fromSigId in screenIds
                if (!fromMapped || e.toSigId !in screenIds) {
                    problems += "dangling edge ${e.fromSigId} -> ${e.toSigId}"
                }
                if (e.ok < 0 || e.weakOk < 0 || e.miss < 0) problems += "negative edge stats ${e.fromSigId} -> ${e.toSigId}"
                val edgeTarget = e.target
                if (edgeTarget != null && !AtlasSanitizer.isSafeText(edgeTarget.name)) {
                    problems += "sensitive content in edge target ${e.fromSigId} -> ${e.toSigId}"
                }
            }
            if (a.candidatePatterns.isNotEmpty()) {
                problems += "share must not contain candidatePatterns"
            }
            if (a.shortcuts.size > MAX_ATLAS_SHORTCUTS) {
                problems += "too many shortcuts: ${a.shortcuts.size} > $MAX_ATLAS_SHORTCUTS"
            }
            val shortcutIds = HashSet<String>()
            for (sc in a.shortcuts) {
                if (sc.id.isBlank()) problems += "blank shortcut id"
                else if (!shortcutIds.add(sc.id)) problems += "duplicate shortcut id ${sc.id}"
                if (sc.name.isBlank()) problems += "shortcut ${sc.id}: name is blank"
                if (sc.slots !in 0..MAX_SHORTCUT_SLOTS) problems += "shortcut ${sc.id}: invalid slot count ${sc.slots}"
                if (sc.successes < 0 || sc.consecutiveMisses < 0) problems += "shortcut ${sc.id}: negative stats"
                if (sc.entrySigId !in screenIds) problems += "shortcut ${sc.id}: dangling entrySigId ${sc.entrySigId}"
                if (sc.steps.isEmpty() || sc.steps.size > MAX_SHORTCUT_STEPS) {
                    problems += "shortcut ${sc.id}: invalid step count ${sc.steps.size}"
                }
                if (!AtlasSanitizer.isSafeText(sc.name) || !AtlasSanitizer.isSafeText(sc.description)) {
                    problems += "shortcut ${sc.id}: sensitive content in name or description"
                }
                sc.steps.forEachIndexed { idx, st ->
                    if (AtlasSanitizer.sanitizeRouteStep(st) == null) {
                        problems += "shortcut ${sc.id} step ${idx + 1}: unsafe or non-parameterized input"
                    }
                    if (st.argsJson.contains(Redactor.MASK) || !AtlasSanitizer.isSafeText(st.argsJson)) {
                        problems += "shortcut ${sc.id} step ${idx + 1}: sensitive content or redacted mask in args"
                    }
                    val stepTarget = st.target
                    if (stepTarget != null && (stepTarget.name == Redactor.MASK || !AtlasSanitizer.isSafeText(stepTarget.name))) {
                        problems += "shortcut ${sc.id} step ${idx + 1}: sensitive content in target"
                    }
                    if (st.before.texts.any { !AtlasSanitizer.isSafeText(it) } || st.after.texts.any { !AtlasSanitizer.isSafeText(it) }) {
                        problems += "shortcut ${sc.id} step ${idx + 1}: sensitive content in anchors"
                    }
                    val slotRefs = SLOT_REF_REGEX.findAll(st.argsJson + "\u0000" + st.target?.name.orEmpty())
                        .mapNotNull { it.groupValues[1].toIntOrNull() }
                    if (slotRefs.any { it !in 1..sc.slots }) {
                        problems += "shortcut ${sc.id} step ${idx + 1}: out-of-range slot placeholder"
                    }
                    if (tools != null) {
                        val info = tools[st.tool]
                        when {
                            info == null -> problems += "shortcut ${sc.id} step ${idx + 1}: unknown tool ${st.tool}"
                            !info.routable -> problems += "shortcut ${sc.id} step ${idx + 1}: ${st.tool} is not replayable"
                        }
                    }
                }
            }
            if (share.routes.size > MAX_EXPORTED_ROUTES) {
                problems += "too many bundled routes: ${share.routes.size} > $MAX_EXPORTED_ROUTES"
            }
            for ((idx, rs) in share.routes.withIndex()) {
                if (rs.title.isBlank()) problems += "route ${idx + 1}: title is blank"
                if (rs.route.template.isBlank()) problems += "route ${idx + 1}: template is blank"
                if (rs.route.disabled) problems += "route ${idx + 1}: route is disabled"
                if (RouteFingerprint.of(rs.route) != rs.fingerprint) {
                    problems += "route ${idx + 1}: fingerprint does not match content"
                }
                if (SensitiveFilter.hit(rs.title) != null || SensitiveFilter.hit(rs.route.template) != null) {
                    problems += "route ${idx + 1}: sensitive content in route template or title"
                }
                if (tools != null) {
                    RouteShareLint.check(rs, tools).forEach { problems += "route ${idx + 1}: $it" }
                }
                if (rs.route.steps.any { AtlasSanitizer.sanitizeRouteStep(it) == null }) {
                    problems += "route ${idx + 1}: unsafe or non-parameterized input"
                }
                val pkgs = rs.route.packages + RouteShareLint.touchedPackages(rs)
                if (pkg.isNotEmpty() && pkg !in pkgs) {
                    problems += "route ${idx + 1}: does not match package $pkg"
                }
            }
            return problems
        }

        /** 解析原始 JSON 并执行完整校验。 */
        fun decodeAndLint(
            raw: String,
            tools: ToolIndex? = null,
            selfPackage: String = AtlasLearner.DEFAULT_SELF_PACKAGE,
        ): LintResult {
            if (raw.toByteArray(Charsets.UTF_8).size > MAX_SHARE_BYTES) {
                return LintResult.Rejected(listOf("share exceeds $MAX_SHARE_BYTES bytes"))
            }
            val parsed = try {
                decode(raw)
            } catch (e: Exception) {
                return LintResult.Rejected(listOf("invalid json: ${e.message ?: "decode failed"}"))
            }
            val problems = lint(parsed, tools, selfPackage)
            return if (problems.isEmpty()) LintResult.Valid(parsed) else LintResult.Rejected(problems)
        }

        /** 解析、校验并导入 [atlasStore]（及可选 [routeStore]）。 */
        fun importInto(
            raw: String,
            atlasStore: AtlasStore,
            routeStore: RouteStore? = null,
            tools: ToolIndex? = null,
            legacy: LegacyNames? = null,
            conflictMode: ConflictMode = ConflictMode.Merge,
            nowMs: Long = System.currentTimeMillis(),
            selfPackage: String = AtlasLearner.DEFAULT_SELF_PACKAGE,
        ): ImportResult {
            return when (val checked = decodeAndLint(raw, tools, selfPackage)) {
                is LintResult.Rejected -> ImportResult.Rejected(checked.problems)
                is LintResult.Valid -> importShare(checked.share, atlasStore, routeStore, tools, legacy, conflictMode, nowMs, selfPackage)
            }
        }

        /** 将已解码的 [share] 校验后导入存储。 */
        fun importShare(
            share: AtlasShare,
            atlasStore: AtlasStore,
            routeStore: RouteStore? = null,
            tools: ToolIndex? = null,
            legacy: LegacyNames? = null,
            conflictMode: ConflictMode = ConflictMode.Merge,
            nowMs: Long = System.currentTimeMillis(),
            selfPackage: String = AtlasLearner.DEFAULT_SELF_PACKAGE,
        ): ImportResult {
            val problems = lint(share, tools, selfPackage)
            if (problems.isNotEmpty()) return ImportResult.Rejected(problems)

            val sanitized = sanitizeAtlasForImport(share.atlas, nowMs)
            val saved = atlasStore.update(sanitized.packageName) { existing ->
                when {
                    existing == null || conflictMode == ConflictMode.Replace -> sanitized.compacted()
                    else -> mergeAtlases(existing, sanitized, nowMs)
                }
            } ?: sanitized

            var routesImported = 0
            if (routeStore != null) {
                if (conflictMode == ConflictMode.Replace) {
                    routeStore.removePackage(saved.packageName)
                }
                for (rs in share.routes) {
                    val base = rs.route.copy(
                        id = UUID.randomUUID().toString(),
                        lastUsedMs = nowMs,
                        disabled = false,
                    )
                    val resolved = if (legacy != null && tools != null) base.withCurrentToolNames(legacy, tools) else base
                    if (conflictMode == ConflictMode.Replace) routeStore.put(resolved)
                    else routeStore.putReplacing(resolved)
                    routesImported++
                }
            }
            return ImportResult.Imported(saved, routesImported)
        }

        private fun sanitizeAtlasForExport(atlas: AppAtlas): AppAtlas {
            val pkg = atlas.packageName.trim()
            val cleanScreens = atlas.screens.map { s ->
                val cleanTitle = AtlasSanitizer.cleanSingleLine(s.title, AtlasSanitizer.MAX_TITLE_LEN)
                val title = cleanTitle ?: s.fallbackTitle()
                val titleSource = if (cleanTitle != null) s.titleSource else AtlasScreen.TITLE_ACTIVITY
                s.copy(
                    anchors = AtlasSanitizer.cleanAnchors(s.anchors),
                    resourceIds = AtlasSanitizer.cleanResourceIds(s.resourceIds),
                    title = title,
                    titleSource = titleSource,
                    lastSeenMs = 0L,
                    unverifiedAfterUpgrade = false,
                    controls = s.controls.map { c ->
                        c.copy(
                            selector = AtlasSanitizer.cleanSelector(c.selector),
                            meaning = AtlasSanitizer.cleanSingleLine(c.meaning, AtlasSanitizer.MAX_MEANING_LEN).orEmpty(),
                            used = c.used.coerceAtLeast(0),
                            noEffect = c.noEffect.coerceAtLeast(0),
                            lastUsedMs = 0L,
                        )
                    },
                )
            }
            val cleanEdges = atlas.edges.map { e ->
                e.copy(
                    target = e.target?.let { AtlasSanitizer.cleanSelector(it, isTypeAction = AtlasSanitizer.isTextInputTool(e.tool)) },
                    ok = e.ok.coerceAtLeast(0),
                    weakOk = e.weakOk.coerceAtLeast(0),
                    miss = e.miss.coerceAtLeast(0),
                    lastOkMs = 0L,
                    lastAttemptMs = 0L,
                    launchObserved = false,
                )
            }
            val cleanShortcuts = atlas.shortcuts
                .filter { !it.disabled }
                .mapNotNull { AtlasSanitizer.sanitizeShortcut(it, stripRuntimeStats = true) }
            return atlas.copy(
                packageName = pkg,
                appLabel = AtlasSanitizer.cleanSingleLine(atlas.appLabel, 48).orEmpty(),
                versionsSeen = atlas.versionsSeen.distinct().takeLast(MAX_ATLAS_VERSIONS),
                screens = cleanScreens,
                edges = cleanEdges,
                shortcuts = cleanShortcuts,
                candidatePatterns = emptyList(),
                lastUsedMs = 0L,
            ).compacted()
        }

        private fun sanitizeAtlasForImport(atlas: AppAtlas, nowMs: Long): AppAtlas {
            val clean = sanitizeAtlasForExport(atlas.privacyCleaned())
            return clean.copy(
                // 别人机器上整段跑过，不等于这台机器上跑得通：能力算执行证据，导入时一并清掉，
                // 与本机的边计数同一个口径（保留页面和标题，不保留「这页能干这件事」的说法）。
                screens = clean.screens.map { it.copy(lastSeenMs = nowMs, capabilities = emptyList()) },
                // 外部宣称的次数不是本机验证。保留图结构，执行证据等实际观测后再积累。
                edges = clean.edges.map { it.copy(ok = 0, weakOk = 0, miss = 0, launchObserved = false) },
                shortcuts = clean.shortcuts.map { it.copy(disabled = true) },
                lastUsedMs = nowMs,
            )
        }

        private fun sanitizeRouteForExport(r: Route, pkg: String): RouteShare? {
            if (SensitiveFilter.hit(r.template) != null) return null
            val cleanSteps = r.steps.map { AtlasSanitizer.sanitizeRouteStep(it) ?: return null }
            val portable = r.copy(
                packages = (listOf(pkg) + r.packages).distinct(),
                successes = 0,
                consecutiveMisses = 0,
                missStep = null,
                disabled = false,
                lastUsedMs = 0L,
                steps = cleanSteps,
            )
            return RouteShare.of(portable, title = r.template)
        }

        private fun mergeAtlases(existing: AppAtlas, imported: AppAtlas, nowMs: Long): AppAtlas {
            val pkg = existing.packageName
            val screens = existing.screens.toMutableList()
            val idRemap = HashMap<String, String>()

            for (imp in imported.screens) {
                val matchIdx = screens.indexOfFirst { cur ->
                    (cur.sigId == imp.sigId && ScreenSig.activitiesCompatible(pkg, cur.activity, imp.activity)) ||
                        cur.toScreenSig(pkg).matches(imp.toScreenSig(pkg))
                }
                if (matchIdx >= 0) {
                    val cur = screens[matchIdx]
                    idRemap[imp.sigId] = cur.sigId
                    val (nextTitle, nextSource) = mergeTitle(cur, imp)
                    screens[matchIdx] = cur.copy(
                        activity = cur.activity ?: imp.activity,
                        shortActivity = cur.shortActivity ?: imp.shortActivity,
                        anchors = (cur.anchors + imp.anchors).distinct().take(MAX_ATLAS_ANCHORS),
                        resourceIds = (cur.resourceIds + imp.resourceIds).distinct().take(MAX_ATLAS_RESOURCE_IDS),
                        title = nextTitle,
                        titleSource = nextSource,
                        hits = maxOf(cur.hits, imp.hits),
                        lastSeenMs = maxOf(cur.lastSeenMs, imp.lastSeenMs),
                        controls = mergeControls(cur.controls, imp.controls),
                    )
                } else {
                    idRemap[imp.sigId] = imp.sigId
                    screens += imp.copy(lastSeenMs = imp.lastSeenMs.takeIf { it > 0L } ?: nowMs)
                }
            }

            val edges = existing.edges.toMutableList()
            for (impEdge in imported.edges) {
                val fromId = idRemap[impEdge.fromSigId] ?: impEdge.fromSigId
                val toId = idRemap[impEdge.toSigId] ?: impEdge.toSigId
                val idx = edges.indexOfFirst {
                    it.fromSigId == fromId &&
                        it.toSigId == toId &&
                        it.tool == impEdge.tool &&
                        AtlasEviction.sameEdgeTarget(it.tool, it.target, impEdge.target)
                }
                if (idx >= 0) {
                    val cur = edges[idx]
                    edges[idx] = cur.copy(
                        ok = maxOf(cur.ok, impEdge.ok),
                        weakOk = maxOf(cur.weakOk, impEdge.weakOk),
                        miss = maxOf(cur.miss, impEdge.miss),
                        lastOkMs = maxOf(cur.lastOkMs, impEdge.lastOkMs),
                        lastAttemptMs = maxOf(cur.lastAttemptMs, impEdge.lastAttemptMs),
                    )
                } else {
                    edges += impEdge.copy(fromSigId = fromId, toSigId = toId)
                }
            }

            val shortcuts = existing.shortcuts.toMutableList()
            for (impSc in imported.shortcuts) {
                val mappedEntry = idRemap[impSc.entrySigId] ?: impSc.entrySigId
                val remapped = impSc.copy(entrySigId = mappedEntry)
                val idx = shortcuts.indexOfFirst { cur ->
                    cur.id == remapped.id || (cur.entrySigId == mappedEntry && shortcutStepsEqual(cur.steps, remapped.steps))
                }
                if (idx >= 0) {
                    val cur = shortcuts[idx]
                    shortcuts[idx] = cur.copy(
                        name = cur.name.ifBlank { remapped.name },
                        description = cur.description.ifBlank { remapped.description },
                        appVersion = cur.appVersion ?: remapped.appVersion,
                    )
                } else {
                    shortcuts += remapped.copy(lastUsedMs = remapped.lastUsedMs.takeIf { it > 0L } ?: nowMs)
                }
            }

            return existing.copy(
                appLabel = existing.appLabel.ifBlank { imported.appLabel },
                versionsSeen = (existing.versionsSeen + imported.versionsSeen).distinct().takeLast(MAX_ATLAS_VERSIONS),
                screens = screens,
                edges = edges,
                shortcuts = shortcuts,
                lastUsedMs = maxOf(existing.lastUsedMs, nowMs),
            ).compacted()
        }

        private fun shortcutStepsEqual(a: List<com.wangzan101.mika.core.replay.RouteStep>, b: List<com.wangzan101.mika.core.replay.RouteStep>): Boolean =
            a.size == b.size && a.zip(b).all { (x, y) ->
                x.tool == y.tool && x.argsJson == y.argsJson && AtlasEviction.sameOptionalTarget(x.target, y.target)
            }

        private fun mergeTitle(cur: AtlasScreen, imp: AtlasScreen): Pair<String, String> = when {
            cur.titleSource == AtlasScreen.TITLE_USER -> cur.title to cur.titleSource
            cur.titleSource == AtlasScreen.TITLE_LLM && cur.title.isNotBlank() -> cur.title to cur.titleSource
            imp.titleSource == AtlasScreen.TITLE_LLM && imp.title.isNotBlank() -> imp.title to imp.titleSource
            cur.title.isNotBlank() -> cur.title to cur.titleSource
            imp.title.isNotBlank() -> imp.title to imp.titleSource
            else -> cur.fallbackTitle() to AtlasScreen.TITLE_ACTIVITY
        }

        private fun mergeControls(cur: List<AtlasControl>, imp: List<AtlasControl>): List<AtlasControl> {
            val out = cur.toMutableList()
            for (ic in imp) {
                val idx = out.indexOfFirst { AtlasEviction.sameControl(it.selector, ic.selector) }
                if (idx >= 0) {
                    val c = out[idx]
                    out[idx] = c.copy(
                        meaning = c.meaning.ifBlank { ic.meaning },
                        used = maxOf(c.used, ic.used),
                        noEffect = maxOf(c.noEffect, ic.noEffect),
                        lastUsedMs = maxOf(c.lastUsedMs, ic.lastUsedMs),
                    )
                } else {
                    out += ic
                }
            }
            return out
        }
    }
}

/** v2 完整内容摘要；对象键与无序图集合规范化，步骤顺序保留。哈希不提供来源认证。 */
object AtlasFingerprint {
    private val json = Json { encodeDefaults = true }

    fun of(atlas: AppAtlas, routes: List<RouteShare> = emptyList()): String {
        val raw = json.encodeToJsonElement(AppAtlas.serializer(), atlas) as JsonObject
        val ordered = JsonObject(raw.mapValues { (key, value) ->
            when (key) {
                "screens" -> sortedArray((value as JsonArray).map { screen ->
                    val obj = screen as JsonObject
                    JsonObject(obj + ("controls" to sortedArray(obj.getValue("controls") as JsonArray)))
                })
                "edges", "shortcuts", "candidatePatterns" -> sortedArray(value as JsonArray)
                else -> value
            }
        })
        val payload = JsonObject(mapOf(
            "schema" to JsonPrimitive(AtlasShare.SCHEMA),
            "atlas" to ordered,
            "routes" to sortedArray(routes.map { json.encodeToJsonElement(RouteShare.serializer(), it) }),
        ))
        val bytes = canonical(payload).toString().toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sortedArray(items: List<JsonElement>): JsonArray = JsonArray(items.map(::canonical).sortedBy { it.toString() })

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}
