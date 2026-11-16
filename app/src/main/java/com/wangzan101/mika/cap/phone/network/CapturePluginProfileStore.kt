package com.wangzan101.mika.cap.phone.network

import android.content.Context
import android.util.Log
import com.agent.use.client.network.ebpf.LocalRootEbpfCaptureEngine
import com.agent.use.common.network.plugin.EbpfProbeProfile
import com.agent.use.common.network.plugin.NetworkCapturePluginRegistry
import java.io.File

/**
 * 端侧抓包 eBPF 插件/偏移覆盖配置条目，用于在设置页展示默认插件与自定义覆盖。
 */
data class CapturePluginEntry(
    val packageName: String,
    val profile: EbpfProbeProfile,
    val isBuiltin: Boolean,
    val isOverridden: Boolean,
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

    @Synchronized
    fun loadIntoRegistry(): Int {
        val file = overrideFile ?: return 0
        if (!file.isFile) return 0
        return try {
            NetworkCapturePluginRegistry.replaceJsonOverrides(file.readText())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load capture plugin overrides: ${t.message}")
            0
        }
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
