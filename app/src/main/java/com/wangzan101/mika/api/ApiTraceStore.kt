package com.wangzan101.mika.api

import android.content.Context
import android.util.Log
import com.wangzan101.mika.core.api.ApiTrace
import com.wangzan101.mika.core.io.AtomicFile
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 影子采集的 trace 落盘。
 *
 * 和页面图谱的 `atlas/<pkg>.json` 分开：这里是**一次运行的原始素材**，一次一个文件，
 * 用来核对「这次跑出来的端点集合对不对」。P1 的学习器读这些 trace 产图，
 * 图本身另存 `api_atlas/`，两者职责不混。
 */
class ApiTraceStore(context: Context, private val keep: Int = DEFAULT_KEEP) {

    companion object {
        private const val TAG = "ApiTraceStore"

        /** 一次运行的 trace 上限条数按 [ApiTraceCollector] 控制，这里只控制保留多少个文件。 */
        const val DEFAULT_KEEP = 20

        private const val DIR_NAME = "api_traces"
    }

    private val root = File(context.applicationContext.filesDir, DIR_NAME)

    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    /** trace 是否存在（开关没开时应当始终为 true，这是零副作用验收的一条）。 */
    fun isEmpty(): Boolean {
        if (!root.exists()) return true
        val files = root.listFiles() ?: return true
        return files.isEmpty()
    }

    fun write(runId: String, trace: ApiTrace): File? = runCatching {
        root.mkdirs()
        val target = File(root, "$runId.json")
        val temp = File(root, "$runId.json.tmp")
        temp.writeText(json.encodeToString(ApiTrace.serializer(), trace))
        AtomicFile.replace(temp, target)
        prune()
        target
    }.onFailure {
        Log.w(TAG, "api trace not saved for $runId", it)
    }.getOrNull()

    /** 最近一份 trace，用于核对端点集合。 */
    fun readLatest(): ApiTrace? = runCatching {
        val newest = root.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        json.decodeFromString(ApiTrace.serializer(), newest.readText())
    }.onFailure {
        Log.w(TAG, "api trace unreadable", it)
    }.getOrNull()

    fun clear(): Boolean = root.deleteRecursively()

    private fun prune() {
        val files = root.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size <= keep) return
        files.sortedBy { it.lastModified() }
            .take(files.size - keep)
            .forEach { it.delete() }
    }
}
