package com.wangzan101.mika.replay

import android.content.Context
import com.wangzan101.mika.core.replay.MAX_ROUTE_BYTES
import com.wangzan101.mika.core.replay.MAX_ROUTE_STEPS
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteCodec
import com.wangzan101.mika.core.replay.RouteStore
import com.wangzan101.mika.core.replay.involvesPackage
import com.wangzan101.mika.core.replay.replacing
import com.wangzan101.mika.core.replay.withCurrentToolNames
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.LegacyNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 路线存成 `<dir>/<首个包名>/<id>.json`;超过 [max] 条时删最久没用的。
 * 读不了的文件直接删掉(开发期格式变了不做兼容)。产品路径用 [shared],全进程一个实例、一把锁。
 */
class FileRouteStore(
    private val dir: File, private val max: Int = 200,
    /** 改名前记下的路线读出时把旧工具名换成现在的。 */
    private val legacy: LegacyNames = legacyNames(), private val tools: ToolIndex = knownTools(),
) : RouteStore {
    init { require(max > 0) { "max must be positive" } }

    private val lock = Any()
    private val _changes = MutableStateFlow(0L)

    /** 每次路线集合发生变化时递增的修订号，供界面订阅刷新。 */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    override fun all(): List<Route> = synchronized(lock) { load().map { it.second } }

    override fun put(r: Route) = synchronized(lock) {
        putLocked(r, load())
        _changes.update { it + 1 }
    }

    override fun putReplacing(r: Route) = synchronized(lock) {
        val existing = load()
        val old = existing.filter { it.second.template == r.template }
        putLocked(replacing(r, old.map { it.second }), existing)
        old.filter { it.second.id != r.id }.forEach { it.first.delete() }
        pruneEmptyDirs()
        _changes.update { it + 1 }
    }

    override fun update(id: String, change: (Route) -> Route?) = synchronized(lock) {
        val existing = load()
        existing.firstOrNull { it.second.id == id }?.second?.let(change)?.let {
            putLocked(it, existing)
            _changes.update { v -> v + 1 }
        }
        Unit
    }

    override fun remove(id: String) = synchronized(lock) {
        var removed = false
        load().filter { it.second.id == id }.forEach {
            if (it.first.delete()) removed = true
        }
        pruneEmptyDirs()
        if (removed) _changes.update { it + 1 }
    }

    override fun forPackage(packageName: String): List<Route> = routesForPackage(packageName)

    override fun setDisabled(id: String, disabled: Boolean) {
        toggleDisabled(id, disabled)
    }

    override fun removePackage(packageName: String): Int = clearPackage(packageName)

    /** 查询涉及指定包名 [pkg] 的全部路线，按最近使用时刻倒序排列。 */
    fun routesForPackage(pkg: String): List<Route> = synchronized(lock) {
        if (pkg.isBlank()) return emptyList()
        load().map { it.second }.filter { it.involvesPackage(pkg) }.sortedByDescending { it.lastUsedMs }
    }

    /** 切换指定路线的启用/停用状态；找不到该路线时返回 null。 */
    fun toggleDisabled(id: String, disabled: Boolean): Route? = synchronized(lock) {
        val existing = load()
        val current = existing.firstOrNull { it.second.id == id }?.second ?: return null
        if (current.disabled == disabled) return current
        return current.copy(disabled = disabled).also {
            putLocked(it, existing)
            _changes.update { v -> v + 1 }
        }
    }

    /** 删除涉及指定包名 [pkg] 的全部路线，返回实际删除的路线条数。 */
    fun clearPackage(pkg: String): Int = synchronized(lock) {
        if (pkg.isBlank()) return 0
        var removed = 0
        load().filter { it.second.involvesPackage(pkg) }.forEach { (f, _) ->
            if (f.delete()) removed++
        }
        pruneEmptyDirs()
        if (removed > 0) _changes.update { it + 1 }
        removed
    }

    /** 已有路线的全部包名集合（去重、不含空白，含步骤前后锚点中的包名）。 */
    fun packages(): Set<String> = synchronized(lock) {
        load().flatMap { involvedPackages(it.second) }.toSet()
    }

    /** 按包名分组的全部路线，各包内按最近使用时刻倒序排列。 */
    fun routesByPackage(): Map<String, List<Route>> = synchronized(lock) {
        groupByPackage(load().map { it.second })
    }

    /** 按包名汇总的路线概况，按最近使用时刻倒序排列。 */
    fun packageSummaries(): List<PackageSummary> = synchronized(lock) {
        groupByPackage(load().map { it.second }).map { (pkg, routes) ->
            PackageSummary(
                packageName = pkg,
                routeCount = routes.size,
                enabledCount = routes.count { !it.disabled },
                lastUsedMs = routes.maxOf { it.lastUsedMs },
            )
        }.sortedByDescending { it.lastUsedMs }
    }

    override fun clear() = synchronized(lock) {
        dir.deleteRecursively()
        _changes.update { it + 1 }
    }

    override fun count(): Int = synchronized(lock) { load().size }

    private fun involvedPackages(route: Route): List<String> =
        (route.packages + route.steps.flatMap { listOfNotNull(it.before.packageName, it.after.packageName) })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun groupByPackage(routes: List<Route>): Map<String, List<Route>> {
        val grouped = linkedMapOf<String, MutableList<Route>>()
        for (route in routes) {
            for (pkg in involvedPackages(route)) {
                grouped.getOrPut(pkg) { mutableListOf() }.add(route)
            }
        }
        return grouped.mapValues { (_, list) -> list.sortedByDescending { it.lastUsedMs } }
    }

    private fun putLocked(r: Route, existing: List<Pair<File, Route>>) {
        val bytes = RouteCodec.encode(r).toByteArray()
        require(r.steps.size <= MAX_ROUTE_STEPS && bytes.size <= MAX_ROUTE_BYTES) { "route ${r.id} too large" }
        val f = fileOf(r)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
        // 原子替换:旧文件在新文件就位前一直完好
        atomicReplace(tmp, f)
        // 同 id 换了首个包名:旧位置的那份删掉
        existing.filter { it.second.id == r.id && it.first.absoluteFile != f.absoluteFile }.forEach { it.first.delete() }
        val kept = existing.filter { it.second.id != r.id } + (f to r)
        kept.sortedBy { it.second.lastUsedMs }.take((kept.size - max).coerceAtLeast(0)).forEach { it.first.delete() }
        pruneEmptyDirs()
    }

    private fun fileOf(r: Route) = File(File(dir, safe(r.packages.firstOrNull() ?: NO_PACKAGE)), "${safe(r.id)}.json")

    private fun load(): List<Pair<File, Route>> =
        (dir.listFiles()?.filter { it.isDirectory } ?: emptyList()).flatMap { d ->
            (d.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: emptyList()).mapNotNull { f ->
                try {
                    f to RouteCodec.decode(f.readText()).withCurrentToolNames(legacy, tools)
                } catch (e: Exception) {
                    f.delete()
                    null
                }
            }
        }

    private fun pruneEmptyDirs() {
        dir.listFiles()?.filter { it.isDirectory && it.list()?.isEmpty() == true }?.forEach { it.delete() }
    }

    private fun atomicReplace(src: File, dst: File) {
        var attempt = 0
        while (true) {
            try {
                try {
                    Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                return
            } catch (e: AccessDeniedException) {
                if (++attempt >= 6) throw e
                Thread.sleep(attempt * 5L)
            }
        }
    }

    /** 按包名汇总的路线概况：总数、启用数与最近使用时刻。 */
    data class PackageSummary(
        val packageName: String,
        val routeCount: Int,
        val enabledCount: Int,
        val lastUsedMs: Long,
    )

    /** 给出进程里那一份的 Application。 */
    interface Owner {
        val routes: FileRouteStore
    }

    companion object {
        const val DIR = "routes"
        private const val NO_PACKAGE = "_"
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")
        private fun safe(s: String) = UNSAFE.replace(s, "_").ifEmpty { "_" }.let { if (it == "." || it == "..") "_" else it }

        /** 产品里的那份(App 建):files/routes 下。 */
        fun create(context: Context): FileRouteStore = FileRouteStore(File(context.applicationContext.filesDir, DIR))

        /** App 内共用的那份(经 [Owner] 给出;任务结束记录、设置页清除都用它):全进程一个实例、一把锁。 */
        fun shared(context: Context): FileRouteStore = (context.applicationContext as? Owner)?.routes
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement FileRouteStore.Owner to give the route store")
    }
}
