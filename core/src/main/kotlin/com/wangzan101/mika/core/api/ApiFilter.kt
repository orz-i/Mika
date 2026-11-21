package com.wangzan101.mika.core.api

import java.util.Locale

/**
 * 判断一次网络交换是否值得记进接口 trace。
 *
 * 一次开屏通常有 50–120 个请求，其中静态资源、图片、埋点上报占绝大多数。
 * 这里只做**粗筛**：宁可多留几条，也不要把真接口丢掉——P0 是影子采集，漏收的代价
 * 是图谱缺一个端点，误收的代价只是字段表里多一行。
 *
 * 丢掉的每条都带一个原因（[DROP_STATIC] 等），trace 里按原因计数。真机验收时要能回答
 * 「这个接口是没抓到，还是被规则丢了」，只记一个总数回答不了。
 */
object ApiFilter {

    /** 无信息的响应体长度门槛。 */
    const val MIN_INTERESTING_BODY = 64

    // ===== 丢弃原因 =====

    const val DROP_STATIC = "static"
    const val DROP_TRACKING = "tracking"
    const val DROP_EMPTY = "empty"
    const val DROP_HTML = "html"
    const val DROP_UNINTERESTING = "other"
    /** 连 host / path 都解析不出来（抓包里的非 HTTP 帧）。 */
    const val DROP_INVALID = "invalid-url"

    private val STATIC_SUFFIXES = setOf(
        ".js", ".mjs", ".css", ".map",
        ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico", ".bmp", ".avif",
        ".woff", ".woff2", ".ttf", ".otf", ".eot",
        ".mp4", ".m3u8", ".ts", ".mp3", ".m4a", ".aac", ".ogg",
        ".zip", ".apk", ".gz", ".br", ".7z", ".rar", ".pdf",
    )

    /** 静态资源常用的路径前缀，命中就不必看后缀。 */
    private val STATIC_PREFIXES = listOf("/static/", "/assets/", "/res/", "/images/", "/img/", "/fonts/")

    /**
     * 埋点词。路径段先按 camelCase 与分隔符切成词，整词命中才丢。
     *
     * 用词而不是裸子串，是因为裸子串会把 `/bookshelf/collect`（收藏）当上报；
     * 而只认带分隔符的段又会漏掉真实形态——起点实测里 54 条观测有 41 条是
     * `zhiyan.monitor.tencent-cloud.net/access_v1.http_service/HttpCurveReportRpc`，
     * 段名里既没有 `/log/` 也没有 `/report/`。
     */
    private val TELEMETRY_WORDS = setOf(
        "log", "logs", "report", "reports", "track", "tracks", "beacon", "metrics", "analytics",
        "qreport", "monitor", "curve", "stat", "stats",
    )

    /** 埋点域名里常见的词：任意一段整段等于它就算上报通道。 */
    private val TELEMETRY_HOST_WORDS = setOf(
        "log", "logs", "report", "reports", "track", "stat", "stats", "analytics", "beacon", "monitor", "metrics",
        // `rpt.gdt.qq.com` 是腾讯广告的曝光上报，段名只有三个字母
        "rpt",
    )

    /**
     * 域名段里出现就算上报的长词。只放五个字母以上的词：`blog`、`logistics`、`statics`
     * 这类业务域名不会因为 `log` / `stat` 被误伤，而 `reportapi`、`sdklog` 又能认出来。
     */
    private val TELEMETRY_HOST_SUBSTRINGS = listOf("report", "analytics", "metrics", "beacon", "monitor", "track", "telemetry")

    /**
     * 标准方法白名单。抓包里会混进 `UNKNOWN` 的帧（HTTP/2 优先帧、隧道内帧）：
     * host 是 `unknown-host`、path 是 `/`、method 是 `UNKNOWN`，看着像 200 的成功交换，
     * 其实拿不到任何可复用信息，还会在图谱里造出一个假端点。
     */
    private val STANDARD_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

    private val API_PATH_RE = Regex("/(api|rest|graphql)/")
    private val VERSION_SEGMENT_RE = Regex("/v[0-9]{1,2}/")

    /**
     * @param view 已归一化的 URL
     * @param method 请求方法
     * @param status 响应状态码
     * @param contentType 响应 `content-type`
     * @param bodySize 响应体字节数（解压后）
     * @param hasStructuredRequest 请求体是否像 JSON / form
     */
    fun keep(
        view: ApiUrlView,
        method: String,
        status: Int,
        contentType: String?,
        bodySize: Int,
        hasStructuredRequest: Boolean,
    ): Boolean = classify(view, method, status, contentType, bodySize, hasStructuredRequest) == null

    /** 返回 null 表示保留，否则是丢弃原因（见 `DROP_*`）。 */
    fun classify(
        view: ApiUrlView,
        method: String,
        status: Int,
        contentType: String?,
        bodySize: Int,
        hasStructuredRequest: Boolean,
    ): String? {
        if (method.uppercase(Locale.ROOT) !in STANDARD_METHODS) return DROP_INVALID
        if (status == 0 || status == 204 || status == 304) return DROP_EMPTY

        val path = view.path.lowercase(Locale.ROOT)
        if (isStaticAsset(path)) return DROP_STATIC
        if (isTelemetry(view.host, path)) return DROP_TRACKING

        val upperMethod = method.uppercase(Locale.ROOT)
        val hasRequestBody = upperMethod == "POST" || upperMethod == "PUT" || upperMethod == "PATCH"
        val ct = contentType?.lowercase(Locale.ROOT).orEmpty()

        // JSON 响应不设最小长度门槛：`{"Result":0}` 只有 12 字节，
        // 但「这个接口返回什么字段」恰恰是要学的东西，按长度丢掉会把成功标记类接口全砍掉。
        if (ct.contains("json")) return null

        // 非 JSON 的短响应才是真的没信息（空体、1x1 像素、空 gif）
        if (bodySize < MIN_INTERESTING_BODY) {
            return if (hasRequestBody && hasStructuredRequest) null else DROP_EMPTY
        }

        if (apiPathReasons(path)) return null
        if (hasRequestBody && hasStructuredRequest) return null

        // text/html 的长响应是页面本身，不是接口
        if (ct.startsWith("text/html")) return DROP_HTML
        return DROP_UNINTERESTING
    }

    private fun apiPathReasons(path: String): Boolean {
        if (API_PATH_RE.containsMatchIn(path)) return true
        if (VERSION_SEGMENT_RE.containsMatchIn(path)) return true
        if (path.contains("graphql")) return true
        if (path.endsWith(".json")) return true
        return false
    }

    private fun isStaticAsset(path: String): Boolean {
        if (STATIC_PREFIXES.any { path.startsWith(it) }) return true
        val lastSegment = path.substringAfterLast('/')
        return STATIC_SUFFIXES.any { lastSegment.endsWith(it) }
    }

    private fun isTelemetry(host: String, path: String): Boolean {
        if (host.split('.').any { segment ->
                segment in TELEMETRY_HOST_WORDS || TELEMETRY_HOST_SUBSTRINGS.any { segment.contains(it) }
            }
        ) return true
        // 根路径上的 `/collect` 是 SDK 上报的通用写法（腾讯灯塔 galileotelemetry、
        // 起点 publiclog 都走这里）；业务接口的收藏是多级路径 `/bookshelf/collect`，不受影响。
        if (path == "/collect") return true
        return path.split('/').any { segment -> wordsOf(segment).any { it in TELEMETRY_WORDS } }
    }

    /**
     * 把一段名字切成词：非字母数字是分隔符，大写字母开头也另起一个词。
     * `HttpCurveReportRpc` → http / curve / report / rpc；`tts-report` → tts / report。
     */
    private fun wordsOf(segment: String): List<String> {
        if (segment.isEmpty()) return emptyList()
        val out = ArrayList<String>(4)
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                out += current.toString().lowercase(Locale.ROOT)
                current.setLength(0)
            }
        }
        for (ch in segment) {
            when {
                ch.isLetterOrDigit() -> {
                    if (ch.isUpperCase() && current.isNotEmpty()) flush()
                    current.append(ch)
                }
                else -> flush()
            }
        }
        flush()
        return out
    }
}
