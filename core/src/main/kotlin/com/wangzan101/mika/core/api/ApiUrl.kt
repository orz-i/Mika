package com.wangzan101.mika.core.api

import java.net.URI
import java.util.Locale

/** 每个端点保留的 query 键上限。 */
const val MAX_API_QUERY_KEYS = 24

/**
 * URL 归一化后的视图。
 *
 * **只保留键，不保留值**：`path` 里的 id 已被模板化，`queryKeys` 只有键名。
 * 这是接口图谱的隐私底线——值会变（时间戳、任务 id、token），键不会，
 * 而判断「这个接口能拿到什么」只需要键与字段类型。
 *
 * @property scheme 小写协议，仅 `http` / `https` 会出现在这里
 * @property host 小写主机名，已去掉默认端口
 * @property path 已模板化的路径，形如 `/argus/api/v2/user/{id}/orders`
 * @property queryKeys 已排序去重的查询键名，不含值
 */
data class ApiUrlView(
    val scheme: String,
    val host: String,
    val path: String,
    val queryKeys: List<String> = emptyList(),
)

/**
 * URL 归一化与端点主键生成。
 *
 * 纯函数，不碰网络也不碰磁盘，因此可以脱离设备单测。
 */
object ApiUrl {

    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val HEX_ID_RE = Regex("^[0-9a-fA-F]{8,}$")
    private val DIGITS_RE = Regex("^[0-9]{2,}$")
    private val VERSION_RE = Regex("^[vV][0-9]{1,2}$")

    /**
     * 解析并归一化 URL。无法解析、或协议不是 http(s) 时返回 null。
     */
    fun normalize(rawUrl: String): ApiUrlView? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null

        val rawHost = uri.host
            ?: uri.authority?.substringBefore('@')?.substringBefore(':')
            ?: return null
        if (rawHost.isEmpty()) return null
        val host = buildHost(rawHost, uri.port, scheme)

        return ApiUrlView(
            scheme = scheme,
            host = host,
            path = templatePath(uri.rawPath ?: ""),
            queryKeys = queryKeys(uri.rawQuery),
        )
    }

    /**
     * 端点主键：`METHOD host/path` 加上排序后的查询键。
     *
     * 同一条路径带不同值但键集合相同 → 同一个端点；键集合不同 → 不同端点，
     * 因为这通常意味着接口有可选分支，值得分开记。
     */
    fun endpointId(method: String, view: ApiUrlView): String {
        val upper = method.uppercase(Locale.ROOT)
        val base = "$upper ${view.host}${view.path}"
        if (view.queryKeys.isEmpty()) return base
        return "$base?${view.queryKeys.joinToString("&")}"
    }

    /**
     * 主机名小写化，默认端口去掉、非默认端口保留。
     *
     * 保留非默认端口是因为同一域名上不同端口可能真是两套服务；默认端口带着只是噪音。
     */
    private fun buildHost(rawHost: String, port: Int, scheme: String): String {
        val lower = rawHost.lowercase(Locale.ROOT)
        val isDefaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        return if (port == -1 || isDefaultPort) lower else "$lower:$port"
    }

    /**
     * 逐段模板化路径。
     *
     * 版本段必须保住：`v2` 与 `v3` 是两个接口，把它当 id 会把版本信息抹掉。
     */
    private fun templatePath(rawPath: String): String {
        val cleaned = rawPath.trim()
        if (cleaned.isEmpty() || cleaned == "/") return "/"
        val segments = cleaned.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return "/"
        val mapped = segments.map { segment ->
            when {
                VERSION_RE.matches(segment) -> segment.lowercase(Locale.ROOT)
                UUID_RE.matches(segment) -> "{uuid}"
                DIGITS_RE.matches(segment) -> "{id}"
                HEX_ID_RE.matches(segment) -> "{uuid}"
                else -> segment
            }
        }
        return "/" + mapped.joinToString("/")
    }

    /**
     * 只解析出键名，值一律丢弃；键名排序去重后截断到 [MAX_API_QUERY_KEYS]。
     */
    private fun queryKeys(rawQuery: String?): List<String> {
        if (rawQuery.isNullOrBlank()) return emptyList()
        val keys = rawQuery.split('&')
            .asSequence()
            .mapNotNull { pair ->
                val name = pair.substringBefore('=').trim()
                name.takeIf { it.isNotEmpty() }
            }
            .distinct()
            .sorted()
            .take(MAX_API_QUERY_KEYS)
            .toList()
        return keys
    }
}
