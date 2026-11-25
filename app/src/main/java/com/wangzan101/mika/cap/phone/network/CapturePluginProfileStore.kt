package com.wangzan101.mika.cap.phone.network

import android.content.Context
import android.util.Log
import com.agent.use.client.network.ebpf.LocalRootEbpfCaptureEngine
import com.agent.use.common.network.plugin.BUILTIN_PLUGIN_ID_PREFIX
import com.agent.use.common.network.plugin.EXTERNAL_PLUGIN_ID_PREFIX
import com.agent.use.common.network.plugin.EbpfProbeProfile
import com.agent.use.common.network.plugin.NetworkCapturePluginRegistry
import com.agent.use.common.network.plugin.external.BuiltinCapturePluginPackages
import com.agent.use.common.network.plugin.external.ExternalCapturePluginLoader
import java.io.File

/**
 * 端侧抓包 eBPF 插件/偏移覆盖配置条目，用于在设置页展示默认插件与自定义覆盖。
 */
data class CapturePluginEntry(
    val packageName: String,
    val profile: EbpfProbeProfile,
    val isBuiltin: Boolean,
    val isOverridden: Boolean,
    /** 由 `filesDir/capture_plugins` 里的插件包提供（不是编译进 aau 的内置插件）。 */
    val isExternal: Boolean = false,
)

/** 设置页里的一行：一个插件包 + 它是随 APK 内置还是用户自己放的。 */
data class CapturePluginPack(
    val info: ExternalCapturePluginLoader.PackInfo,
    val isBuiltin: Boolean,
) {
    val id: String get() = info.id
    val isExternal: Boolean get() = !isBuiltin
    /** 文件没关、用户也没关、且校验通过。 */
    val enabled: Boolean get() = info.enabled

    /**
     * 列表里显示的标题。包没写 `declaration.name` 时用 id，但去掉 `builtin.` / `external.` 前缀——
     * 那是注册表用来区分来源的，不该当名字给人看。
     */
    val displayTitle: String
        get() = info.title ?: info.id
            .removePrefix(BUILTIN_PLUGIN_ID_PREFIX)
            .removePrefix(EXTERNAL_PLUGIN_ID_PREFIX)
}

/**
 * 持久化端侧 eBPF 插件偏移规则到 `aau_capture_plugins.json` 并同步到 [NetworkCapturePluginRegistry]。
 *
 * 三条来源，优先级从低到高：
 * 1. 内置插件包（assets 安装到 `capture_plugins_builtin/`）
 * 2. 用户放的插件包（`capture_plugins/`）
 * 3. `aau_capture_plugins.json` 覆盖配置（设置页保存）
 *
 * 另外它还管插件包的**启用开关**（[setPackEnabled]）：开关写在 `capture_plugin_state.txt`，
 * 不动插件包本身——包是随 APK 分发或别人给的货，改它就不是"关掉"而是"改坏"。
 */
class CapturePluginProfileStore(
    private val filesDir: File?,
    private val builtinPackages: List<BuiltinCapturePluginPackages.Package> = emptyList(),
) {

    companion object {
        private const val TAG = "CapturePluginStore"

        /** 被用户关掉的插件包 id，每行一个。放在这里而不是改 `plugin.json`：包是只读的货。 */
        const val STATE_FILE_NAME = "capture_plugin_state.txt"

        /**
         * 跨实例的锁。
         *
         * `loadIntoRegistry()` 是"清空注册表 → 逐个注册"，实例锁挡不住并发：调用方
         * （`PhoneNetworkManager`、设置页）每次都是 `forContext()` 新建一个 store。
         * 两次交错就会一边清掉另一边刚注册的包。
         */
        private val LOAD_LOCK = Any()

        fun forContext(context: Context?): CapturePluginProfileStore = CapturePluginProfileStore(
            runCatching { context?.filesDir }.getOrNull(),
            BuiltinCapturePluginPackages.fromAssets(context?.assets),
        )

        /** 测试与本地调试用：内置包从磁盘目录读，布局与 assets 一致。 */
        fun forDirectory(dir: File, builtinAssetDir: File? = null): CapturePluginProfileStore =
            CapturePluginProfileStore(dir, BuiltinCapturePluginPackages.fromDirectory(builtinAssetDir))
    }

    val overrideFile: File?
        get() = filesDir?.let { File(it, LocalRootEbpfCaptureEngine.PLUGIN_OVERRIDE_FILE_NAME) }

    /** 外部插件包目录：`filesDir/capture_plugins/<任意名>/plugin.json`。 */
    val pluginsDir: File?
        get() = filesDir?.let { File(it, ExternalCapturePluginLoader.PLUGINS_DIR_NAME) }

    /** 内置插件包安装目录：随 APK 分发，升级时按 version 覆盖。 */
    val builtinPluginsDir: File?
        get() = filesDir?.let { File(it, ExternalCapturePluginLoader.BUILTIN_PLUGINS_DIR_NAME) }

    /** 被关掉的插件包 id，每行一个；`#` 开头的行当注释。 */
    val stateFile: File?
        get() = filesDir?.let { File(it, STATE_FILE_NAME) }

    /** 导入插件包时的暂存根目录：和 `capture_plugins` 平级，加载器扫不到。 */
    val stagingDir: File?
        get() = filesDir?.let { File(it, CapturePluginImporter.STAGING_DIR_NAME) }

    fun ensurePluginsDir(): File? = pluginsDir?.apply { if (!isDirectory) mkdirs() }

    /**
     * 装一个已经解好、校验过、用户也确认过的包，然后立刻重装注册表。
     *
     * 装完不清禁用状态：同一个 id 之前被用户关掉过，这次导入不该把它悄悄打开——
     * 那是用户的决定，不是包的属性。
     */
    @Synchronized
    fun importPack(staged: CapturePluginImporter.Staged): CapturePluginImporter.InstallResult {
        val dir = pluginsDir ?: return CapturePluginImporter.InstallResult.Failed(
            CapturePluginImporter.Reason.Unreadable,
            "no files dir",
        )
        synchronized(LOAD_LOCK) {
            val result = CapturePluginImporter.install(staged, dir)
            if (result is CapturePluginImporter.InstallResult.Installed) {
                Log.i(TAG, "Imported capture plugin ${result.id} from ${staged.sourceName} (replaced=${result.replaced})")
                loadIntoRegistry()
            }
            return result
        }
    }

    /**
     * 删掉一个**外部**插件包。内置包不给删：它在 assets 里，删了下次启动又回来，
     * 对它该做的是关掉开关。
     *
     * 只删 `capture_plugins` 的直接子目录，不看注册表给的路径——注册表里的路径来自插件自己。
     */
    @Synchronized
    fun removePack(id: String): Boolean {
        val root = pluginsDir ?: return false
        val pack = try {
            ExternalCapturePluginLoader.inspect(root, EXTERNAL_PLUGIN_ID_PREFIX, verifyChecksum = false)
                .firstOrNull { it.id == id }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to inspect capture plugins: ${t.message}")
            return false
        } ?: return false
        val dir = File(pack.dir)
        if (dir.parentFile?.canonicalFile != root.canonicalFile) {
            Log.w(TAG, "Refusing to delete ${dir.absolutePath}: not a direct child of $root")
            return false
        }
        if (!dir.deleteRecursively()) return false
        synchronized(LOAD_LOCK) {
            val left = readDisabledPacks() - id
            writeDisabledPacks(left)
            loadIntoRegistry()
        }
        Log.i(TAG, "Removed capture plugin $id")
        return true
    }

    /** 清掉没走完的导入（进程被杀在确认框上时留下的暂存目录）。 */
    fun clearStaging() {
        CapturePluginImporter.clearStaging(stagingDir)
    }

    fun loadIntoRegistry(): Int = synchronized(LOAD_LOCK) {
        val disabled = readDisabledPacks()
        // 上一次导入被杀在改名前后留下的中间目录。加载器本来就不扫 `.` 开头的目录，
        // 但它会一直占着地方，而且用户看不见。
        pluginsDir?.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(CapturePluginImporter.INSTALL_TMP_PREFIX) }
            ?.forEach { it.deleteRecursively() }
        // 先把上一次注册的包摘掉：关掉一个包之后它要是还挂在注册表里，等于没关。
        for (id in NetworkCapturePluginRegistry.listPluginIds()) {
            if (id.startsWith(BUILTIN_PLUGIN_ID_PREFIX) || id.startsWith(EXTERNAL_PLUGIN_ID_PREFIX)) {
                NetworkCapturePluginRegistry.unregister(id)
            }
        }
        // 先装内置包、再装外部包，最后套覆盖配置：覆盖是"改参数"，本来就该后落。
        loadBuiltinPlugins(disabled)
        loadExternalPlugins(disabled)
        val file = overrideFile ?: return@synchronized 0
        if (!file.isFile) return@synchronized 0
        try {
            NetworkCapturePluginRegistry.replaceJsonOverrides(file.readText())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load capture plugin overrides: ${t.message}")
            0
        }
    }

    // ------------------------------------------------------------------ 插件包开关

    @Synchronized
    fun readDisabledPacks(): Set<String> {
        val file = stateFile ?: return emptySet()
        if (!file.isFile) return emptySet()
        return try {
            file.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read capture plugin state: ${t.message}")
            emptySet()
        }
    }

    /**
     * 打开/关掉一个插件包。写在状态文件里，不碰插件包自身的 `plugin.json`。
     *
     * 写完立刻重装注册表，否则要等下一次抓包 attach 才生效——用户在设置页点开关，
     * 期望的是马上生效。
     */
    @Synchronized
    fun setPackEnabled(id: String, enabled: Boolean) {
        val clean = id.trim()
        if (clean.isEmpty()) return
        synchronized(LOAD_LOCK) {
            val current = readDisabledPacks().toMutableSet()
            val changed = if (enabled) current.remove(clean) else current.add(clean)
            if (!changed) return
            writeDisabledPacks(current)
            loadIntoRegistry()
        }
    }

    /**
     * 列出磁盘上的所有插件包（内置 + 外部），带声明与开关状态。
     *
     * 只读：不注册、不加载 dex，可以在界面线程直接调。
     */
    @Synchronized
    fun snapshotPacks(): List<CapturePluginPack> {
        val disabled = readDisabledPacks()
        val builtin = ExternalCapturePluginLoader.inspect(
            rootDir = builtinPluginsDir,
            idPrefix = BUILTIN_PLUGIN_ID_PREFIX,
            disabledIds = disabled,
        )
        val external = ExternalCapturePluginLoader.inspect(
            rootDir = pluginsDir,
            idPrefix = EXTERNAL_PLUGIN_ID_PREFIX,
            disabledIds = disabled,
        )
        return builtin.map { CapturePluginPack(it, isBuiltin = true) } +
            external.map { CapturePluginPack(it, isBuiltin = false) }
    }

    private fun writeDisabledPacks(ids: Set<String>) {
        val file = stateFile ?: return
        file.parentFile?.mkdirs()
        val body = buildString {
            appendLine("# 被关掉的抓包插件包，每行一个 id（带 builtin./external. 前缀）。")
            appendLine("# 由设置 › 端侧网络抓包 写入；删掉某行等于重新打开这个包。")
            ids.sorted().forEach { appendLine(it) }
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            file.writeText(body)
            tmp.delete()
        }
    }

    /**
     * 安装并加载随 APK 分发的内置插件包。
     *
     * 内置包与外部包是同一份 `plugin.json` 结构，只是 id 前缀是 `builtin.`。
     */
    @Synchronized
    fun loadBuiltinPlugins(disabledIds: Set<String> = readDisabledPacks()): ExternalCapturePluginLoader.Report {
        val root = builtinPluginsDir ?: return ExternalCapturePluginLoader.Report()
        if (builtinPackages.isEmpty()) return ExternalCapturePluginLoader.Report()
        BuiltinCapturePluginPackages.install(builtinPackages, root).let { report ->
            if (report.installed.isNotEmpty()) {
                Log.i(TAG, "Installed builtin capture plugins: ${report.installed.joinToString()}")
            }
            report.failed.forEach { Log.w(TAG, "Failed to install builtin capture plugin: $it") }
        }
        val parent = javaClass.classLoader ?: return ExternalCapturePluginLoader.Report()
        val report = try {
            ExternalCapturePluginLoader.load(
                rootDir = root,
                parentClassLoader = parent,
                idPrefix = BUILTIN_PLUGIN_ID_PREFIX,
                disabledIds = disabledIds,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to scan builtin capture plugins: ${t.message}")
            return ExternalCapturePluginLoader.Report()
        }
        for (bad in report.rejected) {
            Log.w(TAG, "Rejected builtin capture plugin ${bad.path}: ${bad.reason}")
        }
        return report
    }

    /**
     * 加载外部插件包（L3）。与覆盖配置分开：一个是"改内置插件的参数"，一个是"新增一个插件"。
     *
     * 单个包坏掉只记日志，不影响其它包，也不影响抓包本身。
     */
    @Synchronized
    fun loadExternalPlugins(disabledIds: Set<String> = readDisabledPacks()): ExternalCapturePluginLoader.Report {
        val dir = pluginsDir
        val parent = javaClass.classLoader ?: return ExternalCapturePluginLoader.Report()
        val report = try {
            ExternalCapturePluginLoader.load(dir, parent, disabledIds = disabledIds)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to scan external capture plugins: ${t.message}")
            return ExternalCapturePluginLoader.Report()
        }
        if (report.loaded.isNotEmpty()) {
            Log.i(TAG, "Loaded external capture plugins: " + report.loaded.joinToString { it.id })
        }
        for (bad in report.rejected) {
            Log.w(TAG, "Rejected capture plugin ${bad.path}: ${bad.reason}")
        }
        return report
    }

    @Synchronized
    fun readSavedOverrides(): Map<String, EbpfProbeProfile> {
        val file = overrideFile
        if (file == null || !file.isFile) {
            return emptyMap()
        }
        return try {
            EbpfProbeProfile.fromJson(file.readText())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to parse capture plugin overrides: ${t.message}")
            emptyMap()
        }
    }

    @Synchronized
    fun snapshotEntries(): List<CapturePluginEntry> {
        val builtins = NetworkCapturePluginRegistry.listBuiltinProfiles()
        val fileOverrides = readSavedOverrides()
        val activeOverrides = if (fileOverrides.isNotEmpty() || overrideFile?.isFile == true) {
            fileOverrides
        } else {
            NetworkCapturePluginRegistry.listJsonProfileOverrides()
        }

        val allKeys = LinkedHashSet<String>().apply {
            if ("com.xingin.xhs" in builtins || "com.xingin.xhs" in activeOverrides) {
                add("com.xingin.xhs")
            }
            addAll(builtins.keys.filter { it != "*" && it != "com.xingin.xhs" }.sorted())
            addAll(activeOverrides.keys.filter { it != "*" && it != "com.xingin.xhs" }.sorted())
            if ("*" in builtins || "*" in activeOverrides) {
                add("*")
            }
        }

        return allKeys.mapNotNull { pkg ->
            val builtin = builtins[pkg]
            val override = activeOverrides[pkg]
            val effective = override ?: builtin ?: return@mapNotNull null
            CapturePluginEntry(
                packageName = pkg,
                profile = effective,
                // 来源按插件包声明的包名判断，不看 listBuiltinProfiles 的 key：
                // 后者把外部包也算进来，会让外部包显示成"内置"、编辑时还误触发「替换内置」。
                isBuiltin = NetworkCapturePluginRegistry.isBuiltinPluginPackage(pkg),
                isOverridden = override != null,
                isExternal = NetworkCapturePluginRegistry.isExternalPluginPackage(pkg),
            )
        }
    }

    @Synchronized
    fun saveProfile(packageName: String, profile: EbpfProbeProfile) {
        val cleanPkg = packageName.trim()
        require(cleanPkg.isNotEmpty()) { "Package name cannot be blank" }
        val updated = LinkedHashMap(readSavedOverrides())
        updated[cleanPkg] = profile
        persist(updated)
    }

    @Synchronized
    fun resetPackage(packageName: String) {
        val updated = LinkedHashMap(readSavedOverrides())
        if (updated.remove(packageName) == null) {
            NetworkCapturePluginRegistry.removeJsonProfileOverride(packageName)
            return
        }
        if (updated.isEmpty()) {
            resetAll()
        } else {
            persist(updated)
        }
    }

    @Synchronized
    fun resetAll() {
        val file = overrideFile
        if (file != null && file.exists()) {
            file.delete()
        }
        NetworkCapturePluginRegistry.clearJsonProfileOverrides()
    }

    private fun persist(profiles: Map<String, EbpfProbeProfile>) {
        val json = EbpfProbeProfile.toJson(profiles)
        overrideFile?.let { file ->
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        }
        NetworkCapturePluginRegistry.replaceJsonOverrides(json)
    }
}
