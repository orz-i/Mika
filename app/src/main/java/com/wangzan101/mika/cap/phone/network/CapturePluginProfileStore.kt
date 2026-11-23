package com.wangzan101.mika.cap.phone.network

import android.content.Context
import android.util.Log
import com.agent.use.client.network.ebpf.LocalRootEbpfCaptureEngine
import com.agent.use.common.network.plugin.EbpfProbeProfile
import com.agent.use.common.network.plugin.NetworkCapturePluginRegistry
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

/**
 * 持久化端侧 eBPF 插件偏移规则到 `aau_capture_plugins.json` 并同步到 [NetworkCapturePluginRegistry]。
 */
class CapturePluginProfileStore(private val filesDir: File?) {

    companion object {
        private const val TAG = "CapturePluginStore"

        fun forContext(context: Context?): CapturePluginProfileStore =
            CapturePluginProfileStore(runCatching { context?.filesDir }.getOrNull())
    }

    val overrideFile: File?
        get() = filesDir?.let { File(it, LocalRootEbpfCaptureEngine.PLUGIN_OVERRIDE_FILE_NAME) }

    /** 外部插件包目录：`filesDir/capture_plugins/<任意名>/plugin.json`。 */
    val pluginsDir: File?
        get() = filesDir?.let { File(it, ExternalCapturePluginLoader.PLUGINS_DIR_NAME) }

    fun ensurePluginsDir(): File? = pluginsDir?.apply { if (!isDirectory) mkdirs() }

    @Synchronized
    fun loadIntoRegistry(): Int {
        // 先装外部插件包，再套覆盖配置：覆盖是"改参数"，本来就该后落。
        loadExternalPlugins()
        val file = overrideFile ?: return 0
        if (!file.isFile) return 0
        return try {
            NetworkCapturePluginRegistry.replaceJsonOverrides(file.readText())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load capture plugin overrides: ${t.message}")
            0
        }
    }

    /**
     * 加载外部插件包（L3）。与覆盖配置分开：一个是"改内置插件的参数"，一个是"新增一个插件"。
     *
     * 单个包坏掉只记日志，不影响其它包，也不影响抓包本身。
     */
    @Synchronized
    fun loadExternalPlugins(): ExternalCapturePluginLoader.Report {
        val dir = pluginsDir
        val parent = javaClass.classLoader ?: return ExternalCapturePluginLoader.Report()
        val report = try {
            ExternalCapturePluginLoader.load(dir, parent)
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
                isBuiltin = builtin != null,
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
