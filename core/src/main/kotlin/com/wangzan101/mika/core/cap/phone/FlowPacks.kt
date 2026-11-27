package com.wangzan101.mika.core.cap.phone

import com.agent.use.flow.AppFlow
import com.agent.use.flow.AppFlowSpec
import java.io.File

/**
 * 从插件包目录里读**应用脚本**（`plugin.json` 的 `flows`）。
 *
 * 和抓包共用一份 `plugin.json`、同一批目录（内置包在 `capture_plugins_builtin/`，
 * 用户包在 `capture_plugins/`）：一个 App 一个包，包里既能写解码规则，也能写操作脚本。
 * 这里只读 `flows`，不碰抓包注册表——脚本跑不跑和抓包开不开是两件事。
 *
 * 每次按「目录里每份 plugin.json 的路径 + 修改时间 + 大小」判断要不要重读。插件包是
 * 用户能改的文件，缓存不能只认路径。
 */
internal object FlowPacks {

    data class Snapshot(
        val flows: List<AppFlow> = emptyList(),
        val errors: List<String> = emptyList(),
        val packs: Int = 0,
    )

    private var cacheKey: String = ""
    private var cache = Snapshot()

    fun load(roots: List<File>): Snapshot {
        val files = roots.filter { it.isDirectory }.flatMap { root ->
            root.listFiles().orEmpty()
                .filter { it.isDirectory }
                .mapNotNull { dir -> File(dir, "plugin.json").takeIf(File::isFile)?.let { dir to it } }
                .sortedBy { it.first.name }
        }
        val key = files.joinToString("|") { (dir, file) -> "${dir.absolutePath}:${file.lastModified()}:${file.length()}" }
        if (key == cacheKey) return cache

        val parsedFlows = ArrayList<AppFlow>()
        val errors = ArrayList<String>()
        for ((dir, file) in files) {
            val rootName = dir.parentFile?.name.orEmpty()
            val origin = if (rootName.endsWith("_builtin")) "builtin" else "external"
            val packId = "$origin.${dir.name}"
            val text = runCatching { file.readText() }.getOrNull()
            if (text == null) {
                errors += "$packId: unreadable"
                continue
            }
            val parsed = AppFlowSpec.parsePack(text, packId)
            parsedFlows += parsed.flows
            errors += parsed.errors
        }
        // 用户包覆盖内置包：同一个 flow id 以外部目录里的为准（外部目录排在后面）。
        // 这样「现场改 JSON 试新文案」不用先删内置包，也不会因为 id 重复被判 flow_ambiguous。
        val byId = LinkedHashMap<String, AppFlow>()
        parsedFlows.forEach { byId[it.id] = it }
        val flows = byId.values.toList()
        cache = Snapshot(flows, errors, files.size)
        cacheKey = key
        return cache
    }
}
