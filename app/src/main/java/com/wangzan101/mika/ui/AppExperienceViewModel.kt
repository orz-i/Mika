package com.wangzan101.mika.ui

import android.content.ContentResolver
import android.net.Uri
import com.wangzan101.mika.R
import com.wangzan101.mika.atlas.AppExperienceSummary
import com.wangzan101.mika.atlas.AtlasRuntime
import com.wangzan101.mika.core.atlas.AppAtlas
import com.wangzan101.mika.core.atlas.AtlasControl
import com.wangzan101.mika.core.atlas.AtlasEdge
import com.wangzan101.mika.core.atlas.AtlasScreen
import com.wangzan101.mika.core.atlas.AtlasShare
import com.wangzan101.mika.core.atlas.AtlasShortcut
import com.wangzan101.mika.core.knowledge.AppKnowledgeDraft
import com.wangzan101.mika.core.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.core.knowledge.KnowledgeEntry
import com.wangzan101.mika.core.knowledge.Surface
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.skills.SkillStatus
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import com.wangzan101.mika.core.uistate.ScreenSig
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 设置 › 记忆与数据 入口行显示的汇总计数。 */
data class AppExperienceOverview(
    val appCount: Int = 0,
    val screenCount: Int = 0,
    val routeCount: Int = 0,
    val shortcutCount: Int = 0,
    val loading: Boolean = false,
)

/** App 经验列表页的单行摘要。 */
data class AppExperienceItem(
    val packageName: String,
    val appLabel: String,
    val screenCount: Int,
    val routeCount: Int,
    val enabledRouteCount: Int,
    val knowledgeCount: Int,
    val lastUsedMs: Long,
    val versionsSeen: List<String> = emptyList(),
    val shortcutCount: Int = 0,
    val enabledShortcutCount: Int = 0,
)

/** 单个页面里的关键控件展示模型。 */
data class AtlasControlItem(
    val label: String,
    val targetHint: String?,
    val used: Int,
    val noEffect: Int,
)

/** 单个页面向外跳转边的展示模型。 */
data class AtlasEdgeItem(
    val tool: String,
    val toolLabel: String,
    val targetDesc: String?,
    val toSigId: String,
    val toScreenTitle: String,
    val ok: Int,
    val miss: Int,
)

/** 单个 learned [AtlasScreen] 展示模型。 */
data class AtlasScreenItem(
    val sigId: String,
    val displayTitle: String,
    val shortActivity: String?,
    val hits: Int,
    val appVersion: String?,
    val lastSeenMs: Long,
    val unverifiedAfterUpgrade: Boolean,
    val controls: List<AtlasControlItem>,
    val outgoingEdges: List<AtlasEdgeItem>,
)

/** 单条操作路线展示模型。 */
data class AtlasRouteItem(
    val id: String,
    val template: String,
    val stepCount: Int,
    val successes: Int,
    val consecutiveMisses: Int,
    val missStep: Int?,
    val disabled: Boolean,
    val lastUsedMs: Long,
)

/** 单条子流程快捷展示模型。 */
data class AtlasShortcutItem(
    val id: String,
    val name: String,
    val description: String,
    val stepCount: Int,
    val slotCount: Int,
    val entryScreenTitle: String?,
    val successes: Int,
    val consecutiveMisses: Int,
    val missStep: Int?,
    val disabled: Boolean,
    val appVersion: String?,
    val lastUsedMs: Long,
)

enum class RelatedKnowledgeKind { PersonalSkill, AppKnowledge }

enum class RelatedKnowledgeStage { Active, Trial, Verified, Draft, Paused }

/** 与该 App 包名关联的个人技能或 App 知识补充。 */
data class RelatedKnowledgeItem(
    val id: String,
    val kind: RelatedKnowledgeKind,
    val title: String,
    val subtitle: String?,
    val stage: RelatedKnowledgeStage,
    val markdown: String,
)

/** 单个 App 详情页状态。 */
data class AppExperienceDetail(
    val summary: AppExperienceItem,
    val screens: List<AtlasScreenItem>,
    val routes: List<AtlasRouteItem>,
    val knowledgeItems: List<RelatedKnowledgeItem>,
    val canExport: Boolean,
    val shortcuts: List<AtlasShortcutItem> = emptyList(),
)

/** 导入弹窗里的解析预览结果。 */
data class ImportBundlePreview(
    val packageName: String,
    val appLabel: String,
    val screenCount: Int,
    val routeCount: Int,
    val hasLocalConflict: Boolean,
    val rawJson: String,
    val shortcutCount: Int = 0,
)

/** 导入弹窗状态。 */
data class AppExperienceImportState(
    val text: String = "",
    val fromFile: Boolean = false,
    val fileLabel: String? = null,
    val preview: ImportBundlePreview? = null,
    val error: String? = null,
    val conflictMode: AtlasShare.ConflictMode = AtlasShare.ConflictMode.Merge,
    val busy: Boolean = false,
)

/** App 经验列表与详情页整体 UI 状态。 */
data class AppExperienceUi(
    val loading: Boolean = true,
    val query: String = "",
    val overview: AppExperienceOverview = AppExperienceOverview(loading = true),
    val apps: List<AppExperienceItem> = emptyList(),
    val filteredApps: List<AppExperienceItem> = emptyList(),
    val selectedPackage: String? = null,
    val selectedDetail: AppExperienceDetail? = null,
    val brokenNotice: String? = null,
    val message: String? = null,
    val importState: AppExperienceImportState? = null,
)

/**
 * 「App 经验」页面与详情页的 ViewModel：
 * - 聚合 [AtlasRuntime]（页面地图 + 操作路线）、[AppKnowledgeDraftStore] 与绑定了包名的 [PersonalSkills]；
 * - 支持搜索过滤、启停/删除路线、导入经验包预检与合并/替换、导出与分享、清空单包/全部。
 */
class AppExperienceViewModel(
    private val runtime: AtlasRuntime,
    private val skills: PersonalSkills? = null,
    private val appDrafts: AppKnowledgeDraftStore? = null,
    private val bundledApps: List<KnowledgeEntry> = emptyList(),
    private val toolLabels: (String) -> String = { it },
    private val brokenNotice: StateFlow<String?> = MutableStateFlow(null),
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _ui = MutableStateFlow(AppExperienceUi())
    val ui: StateFlow<AppExperienceUi> = _ui.asStateFlow()

    init {
        refresh()
        scope.launch {
            runtime.changes.drop(1).collect { refresh() }
        }
        scope.launch {
            brokenNotice.collect { notice ->
                _ui.update { it.copy(brokenNotice = notice) }
            }
        }
    }

    fun search(query: String) {
        _ui.update { state ->
            state.copy(query = query, filteredApps = filterApps(state.apps, query))
        }
    }

    fun dismissMessage() {
        _ui.update { it.copy(message = null) }
    }

    fun openApp(packageName: String) {
        _ui.update { it.copy(selectedPackage = packageName) }
        refresh()
    }

    fun closeApp() {
        _ui.update { it.copy(selectedPackage = null, selectedDetail = null) }
    }

    fun refresh() {
        scope.launch {
            val snapshot = withContext(ioDispatcher) { loadSnapshot(_ui.value.selectedPackage) }
            _ui.update { state ->
                val filtered = filterApps(snapshot.apps, state.query)
                val updatedDetail = if (state.selectedPackage != null) snapshot.selectedDetail else null
                state.copy(
                    loading = false,
                    overview = snapshot.overview,
                    apps = snapshot.apps,
                    filteredApps = filtered,
                    selectedDetail = updatedDetail,
                    brokenNotice = brokenNotice.value,
                )
            }
        }
    }

    fun toggleRouteDisabled(routeId: String, disabled: Boolean) {
        scope.launch {
            withContext(ioDispatcher) { runtime.toggleRouteDisabled(routeId, disabled) }
            refresh()
        }
    }

    fun removeRoute(routeId: String) {
        scope.launch {
            withContext(ioDispatcher) { runtime.removeRoute(routeId) }
            refresh()
        }
    }

    fun toggleShortcutDisabled(packageName: String, shortcutId: String, disabled: Boolean) {
        scope.launch {
            withContext(ioDispatcher) { runtime.toggleShortcutDisabled(packageName, shortcutId, disabled) }
            refresh()
        }
    }

    fun removeShortcut(packageName: String, shortcutId: String) {
        scope.launch {
            withContext(ioDispatcher) { runtime.removeShortcut(packageName, shortcutId) }
            refresh()
        }
    }

    fun clearApp(packageName: String, onCleared: () -> Unit = {}) {
        scope.launch {
            withContext(ioDispatcher) { runtime.clearApp(packageName) }
            val snapshot = withContext(ioDispatcher) { loadSnapshot(packageName) }
            val stillHasOtherItems = snapshot.apps.any { it.packageName == packageName }
            _ui.update { state ->
                state.copy(
                    loading = false,
                    overview = snapshot.overview,
                    apps = snapshot.apps,
                    filteredApps = filterApps(snapshot.apps, state.query),
                    selectedPackage = if (stillHasOtherItems) packageName else null,
                    selectedDetail = if (stillHasOtherItems) snapshot.selectedDetail else null,
                )
            }
            if (!stillHasOtherItems) onCleared()
        }
    }

    fun clearAll() {
        scope.launch {
            withContext(ioDispatcher) { runtime.clearAll() }
            _ui.update { it.copy(selectedPackage = null, selectedDetail = null) }
            refresh()
        }
    }

    fun openImportDialog() {
        _ui.update { it.copy(importState = AppExperienceImportState()) }
    }

    fun dismissImportDialog() {
        _ui.update { it.copy(importState = null) }
    }

    fun setImportConflictMode(mode: AtlasShare.ConflictMode) {
        _ui.update { state ->
            val current = state.importState ?: return@update state
            state.copy(importState = current.copy(conflictMode = mode))
        }
    }

    fun setImportError(error: String) {
        _ui.update { state ->
            val current = state.importState ?: return@update state
            state.copy(importState = current.copy(error = error))
        }
    }

    fun updateImportText(raw: String) {
        val current = _ui.value.importState ?: AppExperienceImportState()
        _ui.update {
            it.copy(
                importState = current.copy(
                    text = raw,
                    fromFile = false,
                    fileLabel = null,
                    preview = null,
                    error = null,
                ),
            )
        }
        if (raw.isBlank()) return
        inspectImportRaw(raw, fromFile = false, fileLabel = null)
    }

    fun inspectImportStream(input: InputStream, fileLabel: String? = null) {
        _ui.update { state ->
            val cur = state.importState ?: AppExperienceImportState()
            state.copy(importState = cur.copy(busy = true, error = null, preview = null))
        }
        scope.launch {
            val raw = withContext(ioDispatcher) { readUtf8Bounded(input) }
            if (raw == null) {
                _ui.update { state ->
                    val cur = state.importState ?: return@update state
                    state.copy(
                        importState = cur.copy(
                            busy = false,
                            error = tr(R.string.app_exp_import_file_too_large),
                        ),
                    )
                }
                return@launch
            }
            inspectImportRaw(raw, fromFile = true, fileLabel = fileLabel)
        }
    }

    fun inspectImportUri(contentResolver: ContentResolver, uri: Uri, fileLabel: String? = null) {
        _ui.update { state ->
            val cur = state.importState ?: AppExperienceImportState()
            state.copy(importState = cur.copy(busy = true, error = null, preview = null))
        }
        scope.launch {
            val raw = withContext(ioDispatcher) {
                runCatching { contentResolver.openInputStream(uri)?.use(::readUtf8Bounded) }.getOrNull()
            }
            if (raw == null) {
                _ui.update { state ->
                    val cur = state.importState ?: return@update state
                    state.copy(
                        importState = cur.copy(
                            busy = false,
                            error = tr(R.string.app_exp_import_read_failed),
                        ),
                    )
                }
                return@launch
            }
            inspectImportRaw(raw, fromFile = true, fileLabel = fileLabel)
        }
    }

    private fun inspectImportRaw(raw: String, fromFile: Boolean, fileLabel: String?) {
        scope.launch {
            val inspected = withContext(ioDispatcher) {
                val lint = runtime.inspectImportJson(raw)
                val conflict = if (lint is AtlasShare.LintResult.Valid) {
                    val pkg = lint.share.atlas.packageName
                    runtime.getAtlas(pkg) != null || runtime.routesForPackage(pkg).isNotEmpty()
                } else {
                    false
                }
                lint to conflict
            }
            _ui.update { state ->
                val cur = state.importState ?: return@update state
                // 若用户已继续修改输入框且不是文件导入，丢弃过时预检结果
                if (!fromFile && cur.text != raw) return@update state
                val (lint, conflict) = inspected
                when (lint) {
                    is AtlasShare.LintResult.Valid -> {
                        val a = lint.share.atlas
                        val label = a.appLabel.ifBlank { runtime.resolveAppLabel(a.packageName) }
                        val shownText = if (fromFile && raw.length <= MAX_TEXT_FIELD_CHARS) raw else if (fromFile) "" else raw
                        state.copy(
                            importState = cur.copy(
                                text = shownText,
                                fromFile = fromFile,
                                fileLabel = fileLabel,
                                preview = ImportBundlePreview(
                                    packageName = a.packageName,
                                    appLabel = label,
                                    screenCount = a.screens.size,
                                    routeCount = lint.share.routes.size,
                                    hasLocalConflict = conflict,
                                    rawJson = raw,
                                    shortcutCount = a.shortcuts.size,
                                ),
                                error = null,
                                busy = false,
                            ),
                        )
                    }
                    is AtlasShare.LintResult.Rejected -> {
                        state.copy(
                            importState = cur.copy(
                                fromFile = fromFile,
                                fileLabel = fileLabel,
                                preview = null,
                                error = tr(R.string.app_exp_import_failed, lint.problems.firstOrNull().orEmpty()),
                                busy = false,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun confirmImport() {
        val imp = _ui.value.importState ?: return
        val raw = imp.preview?.rawJson ?: imp.text
        if (raw.isBlank()) return
        _ui.update { it.copy(importState = imp.copy(busy = true, error = null)) }
        scope.launch {
            val result = withContext(ioDispatcher) {
                runtime.importFromJson(raw, imp.conflictMode)
            }
            when (result) {
                is AtlasShare.ImportResult.Imported -> {
                    val label = result.atlas.appLabel.ifBlank { runtime.resolveAppLabel(result.atlas.packageName) }
                    val flowSummary = if (result.atlas.shortcuts.isNotEmpty()) {
                        "${trPlural(R.plurals.app_exp_routes_count, result.routesImported)} · ${trPlural(R.plurals.app_exp_shortcuts_count, result.atlas.shortcuts.size)}"
                    } else {
                        trPlural(R.plurals.app_exp_routes_count, result.routesImported)
                    }
                    val notice = tr(
                        R.string.app_exp_import_success,
                        label,
                        trPlural(R.plurals.app_exp_pages_count, result.atlas.screens.size),
                        flowSummary,
                    )
                    _ui.update { it.copy(importState = null, message = notice) }
                    refresh()
                }
                is AtlasShare.ImportResult.Rejected -> {
                    _ui.update { state ->
                        val cur = state.importState ?: return@update state
                        state.copy(
                            importState = cur.copy(
                                busy = false,
                                error = tr(R.string.app_exp_import_failed, result.problems.firstOrNull().orEmpty()),
                            ),
                        )
                    }
                }
            }
        }
    }

    suspend fun prepareSharePackage(packageName: String, stageShare: suspend (String) -> Uri?): Uri? =
        withContext(ioDispatcher) {
            try {
                stageShare(packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    fun exportToUri(contentResolver: ContentResolver, uri: Uri, packageName: String) {
        scope.launch {
            val res = withContext(ioDispatcher) { runtime.exportToUri(contentResolver, uri, packageName) }
            val msg = if (res.isSuccess) {
                tr(R.string.app_exp_export_ok)
            } else {
                tr(R.string.app_exp_export_failed, res.exceptionOrNull()?.message.orEmpty())
            }
            _ui.update { it.copy(message = msg) }
        }
    }

    fun showNotice(message: String) {
        _ui.update { it.copy(message = message) }
    }

    private data class Snapshot(
        val overview: AppExperienceOverview,
        val apps: List<AppExperienceItem>,
        val selectedDetail: AppExperienceDetail?,
    )

    private fun loadSnapshot(selectedPackage: String?): Snapshot {
        val runtimeSummaries = runtime.appSummaries().associateBy { it.packageName }
        val runtimeOverview = runtime.overview()
        val knowledgeByPkg = collectKnowledgeByPackage()

        val allPackages = (runtimeSummaries.keys + knowledgeByPkg.keys).distinct()
        val items = allPackages.map { pkg ->
            buildAppItem(pkg, runtimeSummaries[pkg], knowledgeByPkg[pkg].orEmpty())
        }.sortedWith(
            compareByDescending<AppExperienceItem> { it.lastUsedMs }
                .thenBy { it.appLabel.lowercase() },
        )

        val detail = selectedPackage?.takeIf { it.isNotBlank() }?.let { pkg ->
            buildDetail(pkg, items.firstOrNull { it.packageName == pkg }, knowledgeByPkg[pkg].orEmpty())
        }

        val overview = AppExperienceOverview(
            appCount = items.size,
            screenCount = runtimeOverview.screenCount,
            routeCount = runtimeOverview.routeCount,
            shortcutCount = runtimeOverview.shortcutCount,
            loading = false,
        )
        return Snapshot(overview = overview, apps = items, selectedDetail = detail)
    }

    private data class BoundKnowledge(
        val item: RelatedKnowledgeItem,
        val updatedAtMs: Long,
        val hintLabel: String?,
    )

    private fun buildAppItem(
        pkg: String,
        summary: AppExperienceSummary?,
        knowledge: List<BoundKnowledge>,
    ): AppExperienceItem {
        val label = summary?.appLabel?.takeIf { it.isNotBlank() && it != pkg }
            ?: runtime.resolveAppLabel(pkg).takeIf { it.isNotBlank() && it != pkg }
            ?: knowledge.firstNotNullOfOrNull { it.hintLabel?.takeIf(String::isNotBlank) }
            ?: catalogDisplayForPackage(pkg)
            ?: pkg
        val lastUsed = maxOf(
            summary?.lastUsedMs ?: 0L,
            knowledge.maxOfOrNull { it.updatedAtMs } ?: 0L,
        )
        return AppExperienceItem(
            packageName = pkg,
            appLabel = label,
            screenCount = summary?.screenCount ?: 0,
            routeCount = summary?.routeCount ?: 0,
            enabledRouteCount = summary?.enabledRouteCount ?: 0,
            knowledgeCount = knowledge.size,
            lastUsedMs = lastUsed,
            versionsSeen = summary?.versionsSeen.orEmpty(),
            shortcutCount = summary?.shortcutCount ?: 0,
            enabledShortcutCount = summary?.enabledShortcutCount ?: 0,
        )
    }

    private fun buildDetail(
        pkg: String,
        existingItem: AppExperienceItem?,
        knowledge: List<BoundKnowledge>,
    ): AppExperienceDetail {
        val atlas = runtime.getAtlas(pkg)
        val routes = runtime.routesForPackage(pkg)
        val shortcuts = runtime.shortcutsForPackage(pkg)
        val item = existingItem ?: buildAppItem(
            pkg,
            atlas?.let {
                AppExperienceSummary(
                    packageName = pkg,
                    appLabel = it.appLabel.ifBlank { runtime.resolveAppLabel(pkg) },
                    screenCount = it.screens.size,
                    routeCount = routes.size,
                    enabledRouteCount = routes.count { r -> !r.disabled },
                    lastUsedMs = maxOf(
                        it.lastUsedMs,
                        routes.maxOfOrNull { r -> r.lastUsedMs } ?: 0L,
                        shortcuts.maxOfOrNull { s -> s.lastUsedMs } ?: 0L,
                    ),
                    versionsSeen = it.versionsSeen,
                    shortcutCount = shortcuts.size,
                    enabledShortcutCount = shortcuts.count { s -> !s.disabled },
                )
            },
            knowledge,
        )
        val screenItems = buildScreenItems(atlas)
        val routeItems = routes.sortedWith(
            compareBy<Route> { it.disabled }.thenByDescending { it.lastUsedMs },
        ).map { r ->
            AtlasRouteItem(
                id = r.id,
                template = r.template,
                stepCount = r.steps.size,
                successes = r.successes,
                consecutiveMisses = r.consecutiveMisses,
                missStep = r.missStep,
                disabled = r.disabled,
                lastUsedMs = r.lastUsedMs,
            )
        }
        val shortcutItems = shortcuts.sortedWith(
            compareBy<AtlasShortcut> { it.disabled }.thenByDescending { it.lastUsedMs },
        ).map { sc ->
            val entryTitle = sc.entrySigId.takeIf { it.isNotBlank() }?.let { sigId ->
                atlas?.findScreen(sigId)?.displayTitle()
                    ?: ScreenSig.shortHashTag(sigId).takeIf { it.isNotBlank() }
            }
            AtlasShortcutItem(
                id = sc.id,
                name = sc.name.ifBlank { sc.id },
                description = sc.description,
                stepCount = sc.steps.size,
                slotCount = sc.slots,
                entryScreenTitle = entryTitle,
                successes = sc.successes,
                consecutiveMisses = sc.consecutiveMisses,
                missStep = sc.missStep,
                disabled = sc.disabled,
                appVersion = sc.appVersion?.takeIf { it.isNotBlank() },
                lastUsedMs = sc.lastUsedMs,
            )
        }
        return AppExperienceDetail(
            summary = item,
            screens = screenItems,
            routes = routeItems,
            knowledgeItems = knowledge.map { it.item },
            canExport = atlas != null || routes.any { !it.disabled },
            shortcuts = shortcutItems,
        )
    }

    private fun buildScreenItems(atlas: AppAtlas?): List<AtlasScreenItem> {
        if (atlas == null) return emptyList()
        val titleById = atlas.screens.associate { it.sigId to it.displayTitle() }
        return atlas.screens.sortedWith(
            compareByDescending<AtlasScreen> { it.hits }
                .thenByDescending { it.lastSeenMs }
                .thenBy { it.displayTitle() },
        ).map { screen ->
            val controls = screen.controls.sortedWith(
                compareByDescending<AtlasControl> { it.used }
                    .thenByDescending { it.noEffect }
                    .thenByDescending { it.lastUsedMs },
            ).map { c ->
                val rawTarget = selectorSummary(c.selector)
                val label = c.meaning.takeIf { it.isNotBlank() } ?: rawTarget.ifBlank { c.selector.role }
                val hint = rawTarget.takeIf { c.meaning.isNotBlank() && it.isNotBlank() && it != c.meaning }
                AtlasControlItem(
                    label = label,
                    targetHint = hint,
                    used = c.used,
                    noEffect = c.noEffect,
                )
            }
            val edges = atlas.outgoingEdges(screen.sigId).sortedWith(
                compareByDescending<AtlasEdge> { it.ok }
                    .thenByDescending { it.miss }
                    .thenByDescending { it.lastAttemptMs },
            ).map { e ->
                AtlasEdgeItem(
                    tool = e.tool,
                    toolLabel = toolLabels(e.tool),
                    targetDesc = e.target?.let(::selectorSummary)?.takeIf { it.isNotBlank() },
                    toSigId = e.toSigId,
                    toScreenTitle = titleById[e.toSigId] ?: ScreenSig.shortHashTag(e.toSigId),
                    ok = e.ok,
                    miss = e.miss,
                )
            }
            AtlasScreenItem(
                sigId = screen.sigId,
                displayTitle = screen.displayTitle(),
                shortActivity = screen.shortActivity?.takeIf { it.isNotBlank() },
                hits = screen.hits,
                appVersion = screen.appVersion?.takeIf { it.isNotBlank() },
                lastSeenMs = screen.lastSeenMs,
                unverifiedAfterUpgrade = screen.unverifiedAfterUpgrade,
                controls = controls,
                outgoingEdges = edges,
            )
        }
    }

    private fun collectKnowledgeByPackage(): Map<String, List<BoundKnowledge>> {
        val out = linkedMapOf<String, MutableList<BoundKnowledge>>()

        val drafts = appDrafts?.all().orEmpty().filter { it.stage != AppKnowledgeStage.Archived }
        for (d in drafts) {
            val pkg = d.packageName.trim()
            if (pkg.isEmpty()) continue
            val stage = when (d.stage) {
                AppKnowledgeStage.Draft -> RelatedKnowledgeStage.Draft
                AppKnowledgeStage.Trial -> RelatedKnowledgeStage.Trial
                AppKnowledgeStage.Verified -> RelatedKnowledgeStage.Verified
                AppKnowledgeStage.Archived -> continue
            }
            val title = d.display.ifBlank { d.appName }
            out.getOrPut(pkg) { mutableListOf() }.add(
                BoundKnowledge(
                    item = RelatedKnowledgeItem(
                        id = "draft:${d.id}",
                        kind = RelatedKnowledgeKind.AppKnowledge,
                        title = title,
                        subtitle = null,
                        stage = stage,
                        markdown = d.markdown,
                    ),
                    updatedAtMs = d.createdAt,
                    hintLabel = d.display.takeIf { it.isNotBlank() },
                ),
            )
        }

        val allSkills = mutableListOf<Pair<String, PersonalSkill>>()
        skills?.list()?.forEach { allSkills += "skill:${it.name}" to it }
        skills?.drafts()?.forEach { (id, d) -> allSkills += "skill-draft:$id" to d }

        for ((id, skill) in allSkills) {
            val meta = FrontMatter.parse(skill.markdown).first
            val pkgs = resolveSkillPackages(meta)
            if (pkgs.isEmpty()) continue
            val display = (meta["display"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: skill.name
            val desc = (meta["description"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val stage = when (skill.status) {
                SkillStatus.active -> RelatedKnowledgeStage.Active
                SkillStatus.trial -> RelatedKnowledgeStage.Trial
                SkillStatus.pending -> RelatedKnowledgeStage.Draft
                SkillStatus.suspended -> RelatedKnowledgeStage.Paused
            }
            val entry = BoundKnowledge(
                item = RelatedKnowledgeItem(
                    id = id,
                    kind = RelatedKnowledgeKind.PersonalSkill,
                    title = display,
                    subtitle = desc,
                    stage = stage,
                    markdown = skill.markdown,
                ),
                updatedAtMs = skill.createdAt,
                hintLabel = null,
            )
            for (pkg in pkgs) {
                out.getOrPut(pkg) { mutableListOf() }.add(entry)
            }
        }
        return out
    }

    private fun resolveSkillPackages(meta: Map<String, Any>): List<String> {
        val rawApps = when (val v = meta["apps"]) {
            is List<*> -> v.filterIsInstance<String>()
            is String -> listOf(v)
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
        if (rawApps.isEmpty()) return emptyList()

        val appRows = bundledApps.filter { it.surface == Surface.Android }
        val resolved = linkedSetOf<String>()
        for (raw in rawApps) {
            if (PACKAGE_NAME_RE.matches(raw)) {
                resolved += raw
                continue
            }
            val matched = appRows.firstOrNull { entry ->
                entry.name.equals(raw, ignoreCase = true) ||
                    entry.display.equals(raw, ignoreCase = true) ||
                    entry.aliases.any { it.equals(raw, ignoreCase = true) }
            }
            if (matched != null) {
                matched.packages.firstOrNull()?.takeIf { it.isNotBlank() }?.let { resolved += it }
            }
        }
        return resolved.toList()
    }

    private fun catalogDisplayForPackage(pkg: String): String? =
        bundledApps.firstOrNull { it.surface == Surface.Android && pkg in it.packages }?.display

    private fun filterApps(apps: List<AppExperienceItem>, query: String): List<AppExperienceItem> {
        val q = query.trim()
        if (q.isEmpty()) return apps
        return apps.filter { item ->
            item.appLabel.contains(q, ignoreCase = true) ||
                item.packageName.contains(q, ignoreCase = true) ||
                bundledApps.any { entry ->
                    entry.surface == Surface.Android &&
                        item.packageName in entry.packages &&
                        (entry.name.contains(q, ignoreCase = true) ||
                            entry.display.contains(q, ignoreCase = true) ||
                            entry.aliases.any { a -> a.contains(q, ignoreCase = true) })
                }
        }
    }

    companion object {
        private const val MAX_TEXT_FIELD_CHARS = 24_000

        /** 把控件选择器格式化为简洁、可读的目标描述。 */
        fun selectorSummary(s: Selector): String {
            val name = s.name.trim()
            val role = s.role.trim()
            val res = s.resourceId?.substringAfterLast('/')?.trim().orEmpty()
            return when {
                name.isNotBlank() && role.isNotBlank() -> "$name · $role"
                name.isNotBlank() -> name
                res.isNotBlank() && role.isNotBlank() -> "$role · $res"
                res.isNotBlank() -> res
                role.isNotBlank() -> "$role #${s.ordinal + 1}"
                else -> "#${s.ordinal + 1}"
            }
        }

        private fun readUtf8Bounded(input: InputStream): String? = runCatching {
            val buf = ByteArray(8192)
            val out = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                total += n
                if (total > AtlasShare.MAX_SHARE_BYTES) return@runCatching null
                out.write(buf, 0, n)
            }
            out.toByteArray().toString(Charsets.UTF_8)
        }.getOrNull()
    }
}
