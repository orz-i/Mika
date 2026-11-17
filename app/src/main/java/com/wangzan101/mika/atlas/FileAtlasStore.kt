package com.wangzan101.mika.atlas

import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.atlas.AppAtlas
import com.wangzan101.mika.core.atlas.AtlasCodec
import com.wangzan101.mika.core.atlas.AtlasLearner
import com.wangzan101.mika.core.atlas.AtlasShare
import com.wangzan101.mika.core.atlas.AtlasStore
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteStore
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
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
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 按包名分文件加密落盘的 [AtlasStore] 实现：`filesDir/atlas/<sanitizedPkg>.json`。
 *
 * 与 [com.wangzan101.mika.memory.EncryptedMemoryFile] 保持同一套安全策略：
 * - AES-GCM 整文件加密，写临时文件并 `fsync` 后原子替换；
 * - 密钥库一时不可用（未解锁、繁忙）时直接抛出，绝不覆盖或移走原文件；
 * - 密文截断、认证失败或 JSON 损坏时另存为 `*.broken-<timestamp>` 并发布 [brokenNotice]，避免后续写入覆盖现场；
 * - 按文件粒度的并发锁 + 全局读写锁，保证单包原子读改写及多包并发安全。
 */
class FileAtlasStore(
    private val dir: File,
    private val cipher: KeyCipher,
    private val clock: () -> Long = System::currentTimeMillis,
) : AtlasStore {
    private val storeLock = ReentrantReadWriteLock()
    private val fileLocks = ConcurrentHashMap<String, Any>()
    private val cache = ConcurrentHashMap<String, AppAtlas>()

    private val _changes = MutableStateFlow(0L)
    /** 每次地图数据发生增删改时递增的修订号，供界面订阅刷新。 */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private val _brokenNotice = MutableStateFlow<String?>(null)
    /** 最近一次另存的坏文件名；null 表示未发生损坏。 */
    val brokenNotice: StateFlow<String?> = _brokenNotice.asStateFlow()

    override fun listPackages(): List<String> = all().map { it.packageName }

    override fun get(packageName: String): AppAtlas? {
        if (packageName.isBlank()) return null
        val target = fileFor(packageName)
        return storeLock.read {
            synchronized(lockFor(target)) {
                val cached = cache[target.name]
                if (cached != null && target.isFile) {
                    return@synchronized cached.takeIf { it.packageName == packageName }
                }
                cache.remove(target.name)
                readLocked(target)?.also { cache[target.name] = it }
                    ?.takeIf { it.packageName == packageName }
            }
        }
    }

    override fun all(): List<AppAtlas> = storeLock.read {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT_JSON) }
            ?.sortedBy { it.name }
            .orEmpty()
        files.mapNotNull { f ->
            synchronized(lockFor(f)) {
                cache[f.name] ?: readLocked(f)?.also { cache[f.name] = it }
            }
        }.sortedByDescending { it.lastUsedMs }
    }

    override fun put(atlas: AppAtlas) {
        require(atlas.packageName.isNotBlank()) { "packageName must not be blank" }
        val target = fileFor(atlas.packageName)
        storeLock.read {
            synchronized(lockFor(target)) {
                writeLocked(target, atlas)
                cache[target.name] = atlas
                _changes.update { it + 1 }
            }
        }
    }

    override fun remove(packageName: String) {
        clearPackage(packageName)
    }

    /** 删除指定包名的页面地图文件（含残留临时文件）；若实际删除了文件返回 true。 */
    fun clearPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val target = fileFor(packageName)
        return storeLock.read {
            synchronized(lockFor(target)) {
                tempFileFor(target).delete()
                cache.remove(target.name)
                val deleted = target.exists() && target.delete()
                if (deleted) _changes.update { it + 1 }
                deleted
            }
        }
    }

    override fun clear() {
        storeLock.write {
            cache.clear()
            dir.deleteRecursively()
            _brokenNotice.value = null
            _changes.update { it + 1 }
        }
    }

    /** 清空内存缓存并通知订阅者重新从磁盘加密文件读取（供外部备份恢复后刷新状态）。 */
    fun invalidateCache() {
        storeLock.write {
            cache.clear()
            _brokenNotice.value = null
            _changes.update { it + 1 }
        }
    }

    /** 「全部清除」：删掉全部地图文件（含坏文件），并按需删除 AndroidKeyStore 中的加密密钥。 */
    fun wipeAll(deleteKeyAlias: Boolean = true) {
        storeLock.write {
            cache.clear()
            dir.deleteRecursively()
            _brokenNotice.value = null
            if (deleteKeyAlias) {
                runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
            }
            _changes.update { it + 1 }
        }
    }

    override fun update(packageName: String, change: (AppAtlas?) -> AppAtlas?): AppAtlas? {
        if (packageName.isBlank()) return null
        val target = fileFor(packageName)
        return storeLock.read {
            synchronized(lockFor(target)) {
                val cached = cache[target.name]?.takeIf { target.isFile }
                val current = (cached ?: run {
                    cache.remove(target.name)
                    readLocked(target)?.also { cache[target.name] = it }
                })?.takeIf { it.packageName == packageName }
                val next = change(current) ?: return@synchronized null
                require(next.packageName == packageName) { "update must keep packageName ($packageName != ${next.packageName})" }
                writeLocked(target, next)
                cache[target.name] = next
                _changes.update { it + 1 }
                next
            }
        }
    }

    /** 导出指定包名的脱敏分享包；包不存在时返回 null。 */
    fun exportPackage(
        packageName: String,
        routes: List<Route> = emptyList(),
        exportedAtMs: Long = clock(),
    ): AtlasShare? {
        val atlas = get(packageName) ?: return null
        return AtlasShare.export(atlas, routes, exportedAtMs)
    }

    /** 导出指定包名的脱敏分享 JSON 字符串；包不存在时返回 null。 */
    fun exportPackageJson(
        packageName: String,
        routes: List<Route> = emptyList(),
        exportedAtMs: Long = clock(),
    ): String? = exportPackage(packageName, routes, exportedAtMs)?.encode()

    /** 校验并导入 JSON 格式的单 App 经验包。 */
    fun importShare(
        raw: String,
        routeStore: RouteStore? = null,
        tools: ToolIndex = knownTools(),
        legacy: LegacyNames = legacyNames(),
        conflictMode: AtlasShare.ConflictMode = AtlasShare.ConflictMode.Merge,
        selfPackage: String = AtlasLearner.DEFAULT_SELF_PACKAGE,
    ): AtlasShare.ImportResult = AtlasShare.importInto(
        raw = raw,
        atlasStore = this,
        routeStore = routeStore,
        tools = tools,
        legacy = legacy,
        conflictMode = conflictMode,
        nowMs = clock(),
        selfPackage = selfPackage,
    )

    private fun lockFor(file: File): Any = fileLocks.computeIfAbsent(file.name) { Any() }

    private fun fileFor(packageName: String): File = File(dir, "${sanitizePackageName(packageName)}$EXT_JSON")

    private fun tempFileFor(target: File): File = File(target.parentFile, "${target.name}.tmp")

    private fun readLocked(file: File): AppAtlas? {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        return try {
            val plain = cipher.decrypt(bytes).toString(Charsets.UTF_8)
            AtlasCodec.decode(plain)
        } catch (e: Exception) {
            if (e is UserNotAuthenticatedException || e is KeyStoreException || e is ProviderException) {
                throw e
            }
            moveAsideLocked(file)
            null
        }
    }

    private fun writeLocked(target: File, atlas: AppAtlas) {
        dir.mkdirs()
        val plain = AtlasCodec.encode(atlas).toByteArray(Charsets.UTF_8)
        val encrypted = cipher.encrypt(plain)
        val tmp = tempFileFor(target)
        FileOutputStream(tmp).use { out ->
            out.write(encrypted)
            out.fd.sync()
        }
        atomicReplace(tmp, target)
    }

    private fun moveAsideLocked(file: File) {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(clock())
        var target = File(dir, "${file.name}.broken-$stamp")
        var n = 1
        while (target.exists()) target = File(dir, "${file.name}.broken-$stamp-${n++}")
        atomicReplace(file, target)
        _brokenNotice.value = target.name
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

    /** 给出进程里那一份的 Application。 */
    interface Owner {
        val atlas: FileAtlasStore
    }

    companion object {
        const val DIR = "atlas"
        const val KEY_ALIAS = "mika-atlas"
        private const val EXT_JSON = ".json"
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")

        /** 将包名清洗为安全单层文件名（不含路径分隔符或特殊穿越段）。 */
        fun sanitizePackageName(pkg: String): String {
            val cleaned = UNSAFE.replace(pkg.trim(), "_").trimStart('.')
            return cleaned.ifEmpty { "_" }.let { if (it == "." || it == "..") "_" else it }
        }

        /** 产品里的那份（App 建）：位于 `filesDir/atlas/`。 */
        fun create(context: Context): FileAtlasStore =
            FileAtlasStore(File(context.applicationContext.filesDir, DIR), KeystoreKeyCipher(KEY_ALIAS))

        /** 全进程共用的那份（经 [Owner] 给出）。 */
        fun shared(context: Context): FileAtlasStore = (context.applicationContext as? Owner)?.atlas
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement FileAtlasStore.Owner to give the atlas store")
    }
}
