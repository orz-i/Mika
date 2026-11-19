package com.wangzan101.mika.atlas

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.wangzan101.mika.chat.AttachFilesProvider
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.atlas.AppAtlas
import com.wangzan101.mika.core.atlas.AtlasLearner
import com.wangzan101.mika.core.atlas.AtlasNamer
import com.wangzan101.mika.core.atlas.AtlasShare
import com.wangzan101.mika.core.atlas.AtlasShortcut
import com.wangzan101.mika.core.atlas.AtlasStore
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteStore
import com.wangzan101.mika.core.replay.involvesPackage
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.replay.FileRouteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 按包名聚合的 App 经验摘要（供「App 经验」列表与详情页展示）。 */
data class AppExperienceSummary(
    val packageName: String,
    val appLabel: String,
    val screenCount: Int,
    val routeCount: Int,
    val enabledRouteCount: Int,
    val lastUsedMs: Long,
    val versionsSeen: List<String> = emptyList(),
    val shortcutCount: Int = 0,
    val enabledShortcutCount: Int = 0,
)

/** 全部 App 经验总览统计（供「设置 › 记忆与技能」入口副标题展示）。 */
data class ExperienceOverview(
    val appCount: Int,
    val screenCount: Int,
    val routeCount: Int,
    val shortcutCount: Int = 0,
)

/**
 * App 页面地图运行时协调器：
 * 1. 任务结束后在后台从持久化 [RunEvent] 与已有路线离线学习页面节点、控件统计、跳转边及可复用子流程，
 *    按 [com.wangzan101.mika.core.agent.LearnGate.done] 门禁控制边成功计数与快捷流程挖掘，并调用轻量模型批量命名新增页面；
 * 2. 解析系统包名对应的显示名称与版本号；
 * 3. 提供按 App 汇总、快捷流程与路线管理、清理、及经 JSON / [ContentResolver] / [FileProvider] 导出分享与导入的统一入口。
 */
class AtlasRuntime(
    private val atlasStore: AtlasStore,
    private val routeStore: RouteStore,
    private val selfPackage: String = AtlasLearner.DEFAULT_SELF_PACKAGE,
    private val appLabelOf: (String) -> String? = { null },
    private val appVersionOf: (String) -> String? = { null },
    private val tools: ToolIndex = knownTools(),
    private val legacy: LegacyNames = legacyNames(),
    private val yieldToForeground: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val namerTimeoutMs: Long = AtlasNamer.DEFAULT_TIMEOUT_MS,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _activeLearnCount = MutableStateFlow(0)
    /** 当前正在后台执行的离线学习/起名任务数（供真机测试等待后台落盘完成）。 */
    val activeLearnCount: StateFlow<Int> = _activeLearnCount.asStateFlow()

    /** 等待正在进行的离线学习任务全部结束；超时返回 false。 */
    suspend fun awaitLearningIdle(timeoutMs: Long = 15_000L): Boolean =
        withTimeoutOrNull(timeoutMs) { _activeLearnCount.first { it == 0 } } != null

    /** 数据变更流（合并图谱与路线存储的修订计数），供界面订阅刷新。 */
    val changes: Flow<Long> = combine(
        (atlasStore as? FileAtlasStore)?.changes ?: emptyFlow(),
        (routeStore as? FileRouteStore)?.changes ?: emptyFlow(),
    ) { a, b -> a + b }

    /**
     * 从一次已落盘的运行事件离线学习页面地图并在需要时使用轻量模型批量命名。
     * 在执行学习与模型命名之前均会避让前台活跃任务，异常不会向上影响任务结局。
     */
    suspend fun learnAfterRun(
        events: List<RunEvent>,
        end: TaskState? = null,
        outcome: RunOutcome? = null,
        lightModel: ChatModel? = null,
        goal: String? = null,
    ): AtlasLearner.LearnResult {
        _activeLearnCount.update { it + 1 }
        try {
            if (events.isEmpty()) return AtlasLearner.LearnResult(emptyMap(), emptyMap())
            yieldToForeground()

            val pkgs = extractCandidatePackages(events)
            if (pkgs.isEmpty()) return AtlasLearner.LearnResult(emptyMap(), emptyMap())

            val appVersions = pkgs.mapNotNull { pkg ->
                runCatching { appVersionOf(pkg)?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()?.let { pkg to it }
            }.toMap()
            val appLabels = pkgs.mapNotNull { pkg ->
                runCatching { appLabelOf(pkg)?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()?.let { pkg to it }
            }.toMap()
            val pkgSet = pkgs.toSet()
            val routes = runCatching {
                routeStore.all().filter { !it.disabled && it.packages.any { p -> p in pkgSet } }
            }.getOrDefault(emptyList())

            val learnResult = if (end != null) {
                AtlasLearner.learn(
                    events = events,
                    end = end,
                    outcome = outcome ?: RunOutcome.Unknown,
                    store = atlasStore,
                    appVersions = appVersions,
                    appLabels = appLabels,
                    selfPackage = selfPackage,
                    nowMs = events.lastOrNull()?.atMs ?: clock(),
                    goal = goal,
                    tools = tools,
                    routes = routes,
                )
            } else {
                AtlasLearner.learn(
                    events = events,
                    store = atlasStore,
                    appVersions = appVersions,
                    appLabels = appLabels,
                    selfPackage = selfPackage,
                    nowMs = events.lastOrNull()?.atMs ?: clock(),
                    goal = goal,
                    tools = tools,
                    routes = routes,
                )
            }

            if (learnResult.unnamedScreenIds.isNotEmpty() && lightModel != null) {
                yieldToForeground()
                try {
                    AtlasNamer(model = lightModel, fallback = null, timeoutMs = namerTimeoutMs)
                        .nameStoreScreens(atlasStore, learnResult.unnamedScreenIds)
                    runCatching {
                        val titles = learnResult.unnamedScreenIds.keys.flatMap { pkg ->
                            atlasStore.get(pkg)?.screens.orEmpty()
                                .filter { it.sigId in learnResult.unnamedScreenIds.getValue(pkg) }
                                .map { "${it.sigId}=${it.displayTitle()}/${it.titleSource}" }
                        }
                        Log.i(TAG, "atlas naming done: $titles")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runCatching { Log.w(TAG, "atlas screen naming failed, keeping activity fallback", e) }
                }
                val refreshed = learnResult.updatedByPackage.keys.mapNotNull { pkg ->
                    atlasStore.get(pkg)?.let { pkg to it }
                }.toMap()
                return learnResult.copy(updatedByPackage = refreshed.ifEmpty { learnResult.updatedByPackage })
            }

            return learnResult
        } finally {
            _activeLearnCount.update { (it - 1).coerceAtLeast(0) }
        }
    }

    /** 读取指定 [runId] 的事件并执行离线学习。 */
    suspend fun learnRun(
        runId: String,
        eventsProvider: suspend (String) -> List<RunEvent>,
        end: TaskState? = null,
        outcome: RunOutcome? = null,
        lightModel: ChatModel? = null,
        goal: String? = null,
    ): AtlasLearner.LearnResult {
        val events = try {
            eventsProvider(runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "could not load run events for atlas learning: $runId", e) }
            return AtlasLearner.LearnResult(emptyMap(), emptyMap())
        }
        return learnAfterRun(events = events, end = end, outcome = outcome, lightModel = lightModel, goal = goal)
    }

    /** 在进程级后台作用域异步触发一次离线学习，不阻塞调用方。 */
    fun launchLearnAfterRun(
        runId: String,
        eventsProvider: suspend (String) -> List<RunEvent>,
        end: TaskState? = null,
        outcome: RunOutcome? = null,
        lightModel: ChatModel? = null,
        goal: String? = null,
    ): Job = scope.launch {
        try {
            learnRun(runId, eventsProvider, end, outcome, lightModel, goal)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "atlas background learning failed for $runId", e) }
        }
    }

    /** 总览统计：累计 App 数（地图与路线去重合并）、页面总数、路线总数与快捷流程总数。 */
    fun overview(): ExperienceOverview {
        val atlases = atlasStore.all()
        val routes = routeStore.all()
        val apps = linkedSetOf<String>()
        atlases.forEach { if (it.packageName.isNotBlank()) apps += it.packageName }
        routes.forEach { r -> involvedPackages(r).forEach { apps += it } }
        return ExperienceOverview(
            appCount = apps.size,
            screenCount = atlases.sumOf { it.screens.size },
            routeCount = routes.size,
            shortcutCount = atlases.sumOf { it.shortcuts.size },
        )
    }

    /** 按包名聚合并返回所有已知 App 的经验概况（按最近使用时刻倒序）。 */
    fun appSummaries(): List<AppExperienceSummary> {
        val atlases = atlasStore.all().associateBy { it.packageName }
        val routesByPkg = linkedMapOf<String, MutableList<Route>>()
        for (route in routeStore.all()) {
            for (pkg in involvedPackages(route)) {
                routesByPkg.getOrPut(pkg) { mutableListOf() }.add(route)
            }
        }
        val allPackages = (atlases.keys + routesByPkg.keys).distinct()
        return allPackages.map { pkg ->
            val atlas = atlases[pkg]
            val routes = routesByPkg[pkg].orEmpty()
            val label = atlas?.appLabel?.takeIf { it.isNotBlank() }
                ?: resolveInstalledLabel(pkg)
                ?: pkg
            val lastUsed = maxOf(
                atlas?.lastUsedMs ?: 0L,
                atlas?.shortcuts?.maxOfOrNull { it.lastUsedMs } ?: 0L,
                routes.maxOfOrNull { it.lastUsedMs } ?: 0L,
            )
            AppExperienceSummary(
                packageName = pkg,
                appLabel = label,
                screenCount = atlas?.screens?.size ?: 0,
                routeCount = routes.size,
                enabledRouteCount = routes.count { !it.disabled },
                lastUsedMs = lastUsed,
                versionsSeen = atlas?.versionsSeen.orEmpty(),
                shortcutCount = atlas?.shortcuts?.size ?: 0,
                enabledShortcutCount = atlas?.shortcuts?.count { !it.disabled } ?: 0,
            )
        }.sortedWith(compareByDescending<AppExperienceSummary> { it.lastUsedMs }.thenBy { it.appLabel.lowercase() })
    }

    fun getAtlas(packageName: String): AppAtlas? = atlasStore.get(packageName)

    /** 返回指定 App 下已挖掘到的可复用快捷流程列表（启用优先，再按最近使用时间倒序）。 */
    fun shortcutsForPackage(packageName: String): List<AtlasShortcut> =
        if (packageName.isBlank()) emptyList()
        else atlasStore.get(packageName)?.shortcuts.orEmpty()
            .sortedWith(compareBy<AtlasShortcut> { it.disabled }.thenByDescending { it.lastUsedMs }.thenBy { it.name })

    /** 切换指定 App 下快捷流程的启停状态（重新启用时清零连续失败计数与失败步号）。 */
    fun toggleShortcutDisabled(packageName: String, shortcutId: String, disabled: Boolean): AtlasShortcut? {
        if (packageName.isBlank() || shortcutId.isBlank()) return null
        val updated = atlasStore.update(packageName) { existing ->
            val cur = existing ?: return@update null
            if (cur.findShortcut(shortcutId) == null) return@update null
            cur.withShortcutDisabled(shortcutId, disabled)
        }
        return updated?.findShortcut(shortcutId)
    }

    /** 删除指定 App 下的一条快捷流程；实际删除时返回 true。 */
    fun removeShortcut(packageName: String, shortcutId: String): Boolean {
        if (packageName.isBlank() || shortcutId.isBlank()) return false
        var removed = false
        atlasStore.update(packageName) { existing ->
            val cur = existing ?: return@update null
            if (cur.findShortcut(shortcutId) == null) return@update null
            removed = true
            cur.withoutShortcut(shortcutId)
        }
        return removed
    }

    fun routesForPackage(packageName: String): List<Route> =
        if (routeStore is FileRouteStore) routeStore.routesForPackage(packageName)
        else routeStore.forPackage(packageName).sortedByDescending { it.lastUsedMs }

    /** 手动重命名页面：写入用户标题，之后的模型命名不会再覆盖；返回是否真的改到。 */
    fun renameScreen(packageName: String, sigId: String, title: String): Boolean {
        if (packageName.isBlank() || sigId.isBlank() || title.isBlank()) return false
        var renamed = false
        atlasStore.update(packageName) { existing ->
            val cur = existing ?: return@update null
            if (cur.findScreen(sigId) == null) return@update null
            val next = cur.withScreenTitle(sigId, title)
            if (next !== cur) renamed = true
            next
        }
        return renamed
    }

    /** 删除单个页面节点，连同它的边、以它为入口的快捷流程与候选序列。 */
    fun removeScreen(packageName: String, sigId: String): Boolean {
        if (packageName.isBlank() || sigId.isBlank()) return false
        var removed = false
        atlasStore.update(packageName) { existing ->
            val cur = existing ?: return@update null
            if (cur.findScreen(sigId) == null) return@update null
            removed = true
            cur.withoutScreen(sigId)
        }
        return removed
    }

    fun toggleRouteDisabled(id: String, disabled: Boolean): Route? {
        if (routeStore is FileRouteStore) return routeStore.toggleDisabled(id, disabled)
        routeStore.setDisabled(id, disabled)
        return routeStore.all().firstOrNull { it.id == id }
    }

    fun removeRoute(id: String) {
        routeStore.remove(id)
    }

    /** 解析应用显示名称（优先取地图缓存的干净名称，否则查 PackageManager，最后退回包名）。 */
    fun resolveAppLabel(packageName: String): String =
        atlasStore.get(packageName)?.appLabel?.takeIf { it.isNotBlank() }
            ?: resolveInstalledLabel(packageName)
            ?: packageName

    /** 清空单个 App 的页面地图与关联路线。 */
    fun clearApp(packageName: String) {
        if (packageName.isBlank()) return
        atlasStore.remove(packageName)
        routeStore.removePackage(packageName)
    }

    /** 清空全部 App 的页面地图与路线。 */
    fun clearAll() {
        if (atlasStore is FileAtlasStore) atlasStore.wipeAll() else atlasStore.clear()
        routeStore.clear()
    }

    /** 构造单个 App 的脱敏经验分享包（含页面地图与该包未停用路线）；两者均为空时返回 null。 */
    fun exportPackageBundle(packageName: String): AtlasShare? {
        if (packageName.isBlank()) return null
        val existingAtlas = atlasStore.get(packageName)
        val routes = routesForPackage(packageName).filter { !it.disabled }
        if (existingAtlas == null && routes.isEmpty()) return null
        val atlas = existingAtlas ?: AppAtlas(
            packageName = packageName,
            appLabel = resolveInstalledLabel(packageName).orEmpty(),
            versionsSeen = listOfNotNull(appVersionOf(packageName)?.trim()?.takeIf { it.isNotEmpty() }),
        )
        return AtlasShare.export(atlas, routes, exportedAtMs = clock())
    }

    /** 导出单个 App 脱敏经验包为 JSON 字符串。 */
    fun exportPackageJson(packageName: String): String? = exportPackageBundle(packageName)?.encode()

    /** 校验导入 JSON 字符串但不写入存储，供确认弹窗预览。 */
    fun inspectImportJson(raw: String): AtlasShare.LintResult =
        AtlasShare.decodeAndLint(raw, tools = tools, selfPackage = selfPackage)

    /** 校验并导入 JSON 字符串格式的单 App 经验包。 */
    fun importFromJson(
        raw: String,
        conflictMode: AtlasShare.ConflictMode = AtlasShare.ConflictMode.Merge,
    ): AtlasShare.ImportResult = AtlasShare.importInto(
        raw = raw,
        atlasStore = atlasStore,
        routeStore = routeStore,
        tools = tools,
        legacy = legacy,
        conflictMode = conflictMode,
        nowMs = clock(),
        selfPackage = selfPackage,
    )

    /** 经 [ContentResolver] 将指定 App 的脱敏经验包写入用户选择的文档 URI。 */
    fun exportToUri(contentResolver: ContentResolver, uri: Uri, packageName: String): Result<Unit> = runCatching {
        val out = contentResolver.openOutputStream(uri) ?: error("cannot open output stream for $uri")
        out.use { exportToStream(it, packageName).getOrThrow() }
    }

    fun exportToUri(context: Context, uri: Uri, packageName: String): Result<Unit> =
        exportToUri(context.contentResolver, uri, packageName)

    /** 将指定 App 的脱敏经验包写入输出流。 */
    fun exportToStream(output: OutputStream, packageName: String): Result<Unit> = runCatching {
        val encoded = exportPackageJson(packageName) ?: error("no atlas or routes for $packageName")
        output.write(encoded.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /** 从文档 URI 读取并预检（限制最大字节数防 OOM）。 */
    fun inspectImportUri(contentResolver: ContentResolver, uri: Uri): AtlasShare.LintResult {
        val raw = runCatching { contentResolver.openInputStream(uri)?.use(::readUtf8Bounded) }.getOrNull()
            ?: return AtlasShare.LintResult.Rejected(listOf("unable to read file or exceeds ${AtlasShare.MAX_SHARE_BYTES} bytes"))
        return inspectImportJson(raw)
    }

    fun inspectImportUri(context: Context, uri: Uri): AtlasShare.LintResult =
        inspectImportUri(context.contentResolver, uri)

    /** 从输入流读取并预检。 */
    fun inspectImportStream(input: InputStream): AtlasShare.LintResult {
        val raw = readUtf8Bounded(input)
            ?: return AtlasShare.LintResult.Rejected(listOf("unable to read stream or exceeds ${AtlasShare.MAX_SHARE_BYTES} bytes"))
        return inspectImportJson(raw)
    }

    /** 经 [ContentResolver] 从文档 URI 读取、校验并导入单 App 经验包。 */
    fun importFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
        conflictMode: AtlasShare.ConflictMode = AtlasShare.ConflictMode.Merge,
    ): AtlasShare.ImportResult {
        val raw = runCatching { contentResolver.openInputStream(uri)?.use(::readUtf8Bounded) }.getOrNull()
            ?: return AtlasShare.ImportResult.Rejected(listOf("unable to read file or exceeds ${AtlasShare.MAX_SHARE_BYTES} bytes"))
        return importFromJson(raw, conflictMode)
    }

    fun importFromUri(
        context: Context,
        uri: Uri,
        conflictMode: AtlasShare.ConflictMode = AtlasShare.ConflictMode.Merge,
    ): AtlasShare.ImportResult = importFromUri(context.contentResolver, uri, conflictMode)

    /** 从输入流读取、校验并导入单 App 经验包。 */
    fun importFromStream(
        input: InputStream,
        conflictMode: AtlasShare.ConflictMode = AtlasShare.ConflictMode.Merge,
    ): AtlasShare.ImportResult {
        val raw = readUtf8Bounded(input)
            ?: return AtlasShare.ImportResult.Rejected(listOf("unable to read stream or exceeds ${AtlasShare.MAX_SHARE_BYTES} bytes"))
        return importFromJson(raw, conflictMode)
    }

    /** 导出文档默认文件名（供 `CreateDocument` 与分享文件使用）。 */
    fun suggestedExportFileName(packageName: String): String =
        "${FileAtlasStore.sanitizePackageName(packageName)}-atlas.json"

    /**
     * 将脱敏经验包暂存到受 [AttachFilesProvider] 开放的缓存子目录中，返回本地文件供分享。
     * 顺手清理超过 24 小时的旧分享临时文件。
     */
    fun stageShareFile(context: Context, packageName: String): File? =
        stageShareFile(context.cacheDir, packageName)

    /** 在指定缓存根目录 [cacheDir] 下暂存脱敏经验包并清理过期分享文件。 */
    fun stageShareFile(cacheDir: File, packageName: String): File? {
        val encoded = exportPackageJson(packageName) ?: return null
        val shareDir = File(cacheDir, SHARE_CACHE_SUBDIR)
        shareDir.mkdirs()
        val nowMs = clock()
        val staleCutoff = nowMs - SHARE_CACHE_TTL_MS
        shareDir.listFiles()?.filter { it.isFile && it.lastModified() < staleCutoff }?.forEach { it.delete() }
        val file = File(shareDir, suggestedExportFileName(packageName))
        file.writeText(encoded, Charsets.UTF_8)
        file.setLastModified(nowMs)
        return file
    }

    /** 暂存脱敏经验包并返回只读 `content://` URI（配合 `Intent.ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION`）。 */
    fun createShareUri(context: Context, packageName: String): Uri? {
        val file = stageShareFile(context, packageName) ?: return null
        return FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), file)
    }

    private fun extractCandidatePackages(events: List<RunEvent>): List<String> =
        events.asSequence()
            .filterIsInstance<RunEvent.ActionTarget>()
            .filterNot { it.incomplete || it.protected }
            .flatMap { e ->
                listOfNotNull(
                    e.beforeSig?.packageName ?: e.before.packageName,
                    e.afterSig?.packageName ?: e.after.packageName,
                )
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != selfPackage && !it.startsWith("$selfPackage.") }
            .distinct()
            .toList()

    private fun involvedPackages(route: Route): List<String> =
        (route.packages + route.steps.flatMap { listOfNotNull(it.before.packageName, it.after.packageName) })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun resolveInstalledLabel(pkg: String): String? =
        runCatching { appLabelOf(pkg)?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

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

    /** 给出进程里那一份的 Application。 */
    interface Owner {
        val atlasRuntime: AtlasRuntime
    }

    companion object {
        private const val TAG = "MikaAtlas"
        /** 与 res/xml/attach_files.xml 中的 `library-viewing` 路径对应。 */
        private const val SHARE_CACHE_SUBDIR = "library-viewing/atlas-share"
        private const val SHARE_CACHE_TTL_MS = 24 * 60 * 60_000L

        /** 基于 Android [Context] 构造运行时，接入系统 `PackageManager` 解析名称与版本号。 */
        fun create(
            context: Context,
            atlasStore: AtlasStore,
            routeStore: RouteStore,
            yieldToForeground: suspend () -> Unit = {},
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): AtlasRuntime {
            val app = context.applicationContext
            val pm = app.packageManager
            return AtlasRuntime(
                atlasStore = atlasStore,
                routeStore = routeStore,
                selfPackage = app.packageName,
                appLabelOf = { pkg ->
                    runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim().ifEmpty { null }
                    }.getOrNull()
                },
                appVersionOf = { pkg ->
                    runCatching {
                        pm.getPackageInfo(pkg, 0).versionName?.trim()?.ifEmpty { null }
                    }.getOrNull()
                },
                yieldToForeground = yieldToForeground,
                scope = scope,
            )
        }

        /** 全进程共用的那份（经 [Owner] 给出）。 */
        fun shared(context: Context): AtlasRuntime = (context.applicationContext as? Owner)?.atlasRuntime
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement AtlasRuntime.Owner to give the atlas runtime")
    }
}
