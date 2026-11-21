# App 接口图谱（Api Atlas）补齐方案

> **结论先说**：页面图谱解决的是「这个 App 有哪些页、怎么走」，接口图谱要解决的是「这一页的数据从哪来、字段叫什么、能不能再拿一次」。
> 现有的抓包能力只在任务进行中活着（`phone_wait_network` / `phone_get_network_response` / `phone_inspect_network`），任务一结束就全部丢掉，
> 于是同一个 App 每次都要重新猜接口。这份方案把抓到的流量沉淀成按包名的图谱，与 `AppAtlas` 平行，不改动现有页面图谱、分享格式与回放链路。

- 目标版本：Mika 当前主线（`core/src/main/kotlin/com/wangzan101/mika/core/atlas` 已落地，`aau` 已支持 WebView 内嵌库偏移）
- 2026-11-21
- **P0 已实施并真机验收**（2026-11-21）：`core/api/` 纯函数层、交换级 sink、影子采集器、trace 落盘，
  83 例单测通过；起点福利中心实跑三轮，`mainPage` 端点与 220 条字段路径全部抓到。见 §11.1、§11.2。

---

## 1. 缺口：网络能力只活在任务里

| 现有能力 | 生命周期 | 任务结束后 |
| --- | --- | --- |
| `PhoneNetworkManager.waitForResponse` | 单次调用 | 结果只留在模型上下文 |
| `getResponseBody(requestId/urlPattern)` | 环形缓存 250 条 + 1MB/条 | 进程退出即失 |
| `inspectNetwork` | 最近 250 条摘要 | 同上 |
| `phone_probe_ebpf` | 探测偏移 | 不记录命中结果 |

也就是说，**模型每次都要靠 `urlPattern` 猜**。猜错了就超时，猜对了也只拿到这一次的响应体，下次重来。
起点福利中心这个案例把代价暴露得很清楚：完成度不在屏幕上，而在 `GET /argus/api/v2/video/adv/mainPage` 的 `TaskList[].IsFinished` 里；
不知道这件事的 Agent 只能反复滚动、截图、OCR，还得不到准确条数。

页面图谱的五个部件——数据模型、采集、离线学习、加密存储、三级使用——接口层一个都没有。这是要补齐的部分。

---

## 2. 接口图谱要回答的四个问题

1. **哪个页面、哪个操作会打哪个接口**（触发关系）
2. **这个接口返回什么结构，我要的字段在哪**（字段索引）
3. **这个接口能不能重放**（登录态 / 疑似签名参数）
4. **它最近成功还是失败**（状态码与耗时统计）

明确**不回答**的问题：

- 不存任何响应值、请求值（见第 9 节）。
- 不做请求重放。起点 `ibex` 签名的实证是重放返回 `402 {"Result":-3,"Message":"签名错误"}`，重放路线在签名接口上是死路。
- 不推断「这个接口喂养了哪个页面」。抓包只能证明时间相邻，证明不了因果；按页面图谱的治理口径，这种边一律不造。

---

## 3 目标设计：数据模型

新增包 `core/src/main/kotlin/com/wangzan101/mika/core/api/`，与 `core/atlas/` 平行。

```kotlin
const val MAX_API_ENDPOINTS = 200          // 每 App 上限
const val MAX_API_EDGES = 1000
const val MAX_API_FIELDS = 240            // 每端点保留的字段路径
const val MAX_API_SHAPE_DEPTH = 6
const val MAX_API_TRACE_ENTRIES = 500      // 每次运行最多记多少条交换
const val MAX_API_QUERY_KEYS = 24
const val MAX_API_BODY_KEYS = 40
const val MAX_API_VERSIONS = 16
```

```kotlin
@Serializable
data class ApiField(
    /** 形如 "Data.DailyBenefitModule.TaskList[].IsFinished"；动态键折叠成 "*"。 */
    val path: String,
    /** int / long / double / bool / string / object / array / null / mixed */
    val type: String,
    val seen: Int = 1,
)

@Serializable
data class ApiEndpoint(
    /** 稳定主键："GET h5.if.qidian.com/argus/api/v2/video/adv/mainPage" */
    val id: String,
    val host: String,
    /** 已模板化：/user/{id}/orders */
    val path: String,
    val method: String,
    val name: String = "",                 // 用户或模型命名：「福利中心聚合数据」
    val nameSource: String = NAME_NONE,     // none | llm | user
    val queryKeys: List<String> = emptyList(),   // 只有键，没有值
    val bodyKeys: List<String> = emptyList(),
    val authTraits: Int = 0,                // 位掩码，见下
    val statusCounts: Map<String, Int> = emptyMap(), // "200" -> 11, "402" -> 1
    val fields: List<ApiField> = emptyList(),
    val fieldsTruncated: Boolean = false,
    val calls: Int = 0,                     // 总调用次数
    val runs: Int = 0,                      // 出现在多少次运行里（去重口径不同）
    val avgDurationMs: Int = 0,
    val lastStatus: Int = 0,
    val firstSeenMs: Long = 0,
    val lastSeenMs: Long = 0,
    val appVersion: String? = null,
    val unverifiedAfterUpgrade: Boolean = false,
)

@Serializable
data class ApiEdge(
    val fromSigId: String,                  // 触发页（页面图谱的 sigId）
    val endpointId: String,
    /** 触发它的上一个动作的工具名，可为空（页面自己加载）。 */
    val tool: String? = null,
    val ok: Int = 0,                        // 2xx 且任务过 LearnGate.done
    val weakOk: Int = 0,                    // 2xx 且任务整体成功但未过门禁
    val miss: Int = 0,                      // 4xx / 5xx
    val lastMs: Long = 0,
)

@Serializable
data class ApiAtlas(
    val packageName: String,
    val appLabel: String = "",
    val versionsSeen: List<String> = emptyList(),
    val endpoints: List<ApiEndpoint> = emptyList(),
    val edges: List<ApiEdge> = emptyList(),
    val lastUsedMs: Long = 0,
)
```

`authTraits` 位掩码（**只记「有没有」，不记值**）：

| 位 | 含义 | 判定 |
| --- | --- | --- |
| 1 | 带 Cookie | 请求头有 `cookie` 且非空 |
| 2 | 带 Authorization | 请求头有 `authorization` |
| 4 | 疑似签名参数 | query / body 键名**或请求头名**命中 `sign|signature|_sign|sig|ibex|borgus|tstamp|sdksign`（**线索，不是结论**） |
| 8 | 带 Referer | 请求头有 `referer` |
| 16 | 自定义头 | 存在 `x-` 前缀且不在上述集合的头 |

> `authTraits` 只是给模型的提示词，不能当作「必须签名」的证明。起点那次是先看到 `ibex` 参数、再实测重放 402，才算证实；图谱里只写「疑似」。
> 位 4 要把**请求头名**也算进去：真机实测里 `tstamp` / `ibex` / `borgus` / `sdksign` 全在头部，query 一个都没有，只看键名会把这类接口判成「不用签名」。

---

## 4 目标设计：采集

### 4.1 挂点

不订阅 `PhoneNetworkManager.responseFlow`——那个 flow 在 chunk 可能还没组装完时就会发出。
在 `PhoneNetworkManager` 里加一个交换级回调，在 `handleResponseEvent` 已经把 body 拼好之后触发：

```kotlin
// app/src/main/java/com/wangzan101/mika/cap/phone/network/PhoneNetworkManager.kt
fun addExchangeSink(sink: (req: HttpRequestEvent?, entry: CapturedTrafficEntry, body: ByteArray?) -> Unit): AutoCloseable
```

`handleResponseEvent` 末尾调用它。这样 collector 拿到的 body 已经是 `HttpContentDecoder` 解过 chunked + gzip 的明文
（`aau-common/.../parser/Http1PlaintextParser.kt:371` 已经做了 `decompressIfNeeded`，WebView 的 H5 响应同样走这条路）。

### 4.2 采集时机与归属

```kotlin
// app/src/main/java/com/wangzan101/mika/api/ApiTraceCollector.kt
class ApiTraceCollector(
    private val packageName: String,
    private val now: () -> Long = System::currentTimeMillis,
)
```

- 任务开始时装 sink（`TaskRun` 里与 `phoneServices.network.attachToPackage` 同一段），任务结束卸载。
- 只收**目标包名**的流量：`PhoneNetworkManager` 已经按 uid 抓，但仍然按 `entry` 里的信息再过一道，避免混入 Mika 自己的请求。
- 每次交换产出 `ApiObservation`：`startMs` / `endMs` / `method` / `url` / `status` / `mime` / `durationMs` / `reqHeaderNames` / `postKeys` / `fields`。
- **屏幕归因不在采集时做**：trace 只记时间戳，任务结束后由学习器用运行记录里的 `ActionTarget`（`beforeSig` / `afterSig` / `atMs`）对齐。
  理由见 4.5。

### 4.3 过滤：什么算「接口」

| 保留 | 说明 |
| --- | --- |
| `content-type` 含 `json` / `problem+json` | 主力 |
| path 命中 `/api/`、`/v\d/`、`/graphql`、`.json` | 兜底 |
| `POST/PUT/PATCH` 且 body 可解析为 JSON / form | 有请求体的多半是业务接口 |

| 丢弃 | 说明 |
| --- | --- |
| 静态资源后缀（js/css/png/jpg/webp/svg/woff/mp4/zip/apk） | 噪音主体 |
| 非 JSON 的短响应（< 64 字节），或 204 / 304 | 无信息。**JSON 不设长度门槛**，见下 |
| 埋点：带分隔符的段 `/log/`、`/track/`、`/beacon`、`/metrics`、`/analytics`、`qreport`，以及埋点域名 | 起点抓到过整包 JSON 上报，会污染字段索引 |
| 首包即 4xx/5xx 且此前未见过的端点 | 采集阶段照收（402 正是要看的证据），**建图阶段**才只在已知端点上记 miss |

实施时改了三处规则，都是实测出来的：

- **JSON 响应不设最小长度**。原规则「响应体 < 64 字节丢弃」会把 `{"Result":0}` 这类成功标记接口全部砍掉，
  而「这个接口返回什么字段」恰恰是要学的。现在只对非 JSON 短响应按长度丢。
- **埋点判定要求带分隔符的段**。裸子串会把 `/bookshelf/collect`（收藏）当成上报丢掉；
  同理 `/report` 也不单独判，交给建图阶段用调用频次与参数稳定性再筛。
- **埋点判定改成「段名词元化」**（真机验收后修）。只认 `/log/`、`/report/` 这种带分隔符的段，
  会漏掉真实形态：起点那次 54 条观测里有 41 条是
  `zhiyan.monitor.tencent-cloud.net/access_v1.http_service/HttpCurveReportRpc`——
  驼峰里藏着 Curve 与 Report，两头顶着埋点却一条规则都没命中，把 54 条观测挤成「41 条噪音 + 13 条有效」。
  现在把每个路径段按非字母数字与大写字母切成词（`HttpCurveReportRpc` → http/curve/report/rpc），
  整词命中 `log/report/track/beacon/metrics/analytics/qreport/monitor/curve/stat` 才丢；
  host 任意一段等于埋点词、或含 `report/analytics/metrics/beacon/monitor/track` 这类长词也丢
  （`blog`、`logistics`、`statics` 不会被 `log`/`stat` 误伤）。

另外，**丢掉的每一条都记原因**（`ApiTrace.droppedBy`：`static` / `tracking` / `empty` / `html` / `other` / `invalid-url`）。
验收时要回答的是「这个接口是没抓到，还是被规则丢了」，只记一个总数回答不了这个问题；这次就是靠这个分桶才看清 41 条噪音全在 `tracking` 里。

一次开屏通常 50–120 个请求，过滤后剩 5–15 个。单条形状提取预算 < 5ms，单次运行内存 < 1MB。

### 4.4 归一化：`ApiUrl`

| 规则 | 例 |
| --- | --- |
| host 小写、去默认端口 | `H5.if.qidian.com:443` → `h5.if.qidian.com` |
| ≥2 位纯数字段 → `{id}` | `/user/12345/orders` → `/user/{id}/orders` |
| 版本段保留 | `/api/v2/...` 不变（不能把 `v2` 当成 id） |
| UUID / ≥8 位 hex → `{uuid}` | `/task/a1b2c3d4e5` → `/task/{uuid}` |
| query 只留键、按键名排序、值丢弃 | `?positions=newcheckin&_t=17` → `?_t&positions` |
| 去尾斜杠 | `/mainPage/` → `/mainPage` |

主键 = `METHOD host path?keys`。**路径里的 id 与 query 的值都不入库**，这是隐私底线，也顺带解决了「每次 id 不同就是新端点」的膨胀问题。

### 4.5 屏幕归因：为什么放在学习阶段

`ActionTarget.atMs` 是动作**结束**后写的，`afterSig` 是动作后那一屏。于是「请求开始前最后一条 `ActionTarget` 的 `afterSig`」正好是请求发起时屏幕上的内容：

- 页面自己加载的请求 → 归到这一页（正确）；
- 点击触发的请求 → 归到点击后到达的那一页（也是用户看到的页）。

这个口径可解释、可单测，不需要在采集线程里去读 `ScreenSession`，也不增加一次读屏开销。
代价是：没有 `ActionTarget` 的导航（外链、通知唤起、App 自刷新）会归因到更早的一页。这类观测不建边，计入 `LearnResult.unattributed`，不猜。
如果后续实测发现误差不可接受，再在 P2 加「采集时读最近一帧签名」作为补充来源——但那是补充，不是前提。

### 4.6 形状提取：`ApiShape`

只用 `kotlinx.serialization.json`，纯函数：

```
{"Result":0,"Data":{"TaskList":[{"IsFinished":1,"Desc":"..."}]}}
→  Result:int
    Data:object
    Data.TaskList:array
    Data.TaskList[].IsFinished:int
    Data.TaskList[].Desc:string
```

规则：

- 深度上限 6，端点字段上限 240，超出置 `fieldsTruncated=true`；
- 数组取前 20 个元素求并集，类型冲突记 `mixed`；
- 对象键数 > 32 且键名像动态值（纯数字 / 长 hex）时，折叠为 `*`，只保留一个样本路径，避免任务 id 把字段表撑爆；
- **值一律丢弃**，包括字符串长度都不记。

---

## 5 目标设计：学习

纯函数，签名与 `AtlasLearner` 对齐：

```kotlin
object ApiLearner {
    data class LearnResult(
        val updated: ApiAtlas?,
        val unattributed: Int,
        val newEndpointIds: Set<String>,   // 供后续命名（P3）
    )

    fun learn(
        observations: List<ApiObservation>,
        events: List<RunEvent>,            // 取 ActionTarget 做归因
        existing: ApiAtlas?,
        appAtlas: AppAtlas?,               // 只为校验 sigId 是否存在
        appVersion: String?,
        learnDone: Boolean,                // LearnGate.done
        succeeded: Boolean,                // TaskState.Succeeded
        nowMs: Long,
    ): LearnResult
}
```

合并规则：

| 对象 | 规则 |
| --- | --- |
| 端点 | 不存在则建；存在则 `calls++`、状态码计数、耗时 EWMA(0.3)、`lastSeenMs` |
| 字段 | 按 `path` 求并集，`seen++`；类型冲突降为 `mixed`；超过 240 条按「浅层优先 + seen 降序」截断 |
| 边 | 键 `(fromSigId, endpointId, tool)`；2xx 且 `learnDone` → `ok++`；2xx 且 `succeeded` → `weakOk++`；4xx/5xx → `miss++` |
| 版本 | 端点带 `appVersion`；App 升级后，旧版本端点标 `unverifiedAfterUpgrade`，被新版本命中后转回 |
| 淘汰 | 超过 200 端点 / 1000 边：复用 `AtlasEviction.evict`（近因 0.4 + log 命中 0.6），入口边不淘汰 |

去重口径要分清：`calls` 是调用次数（轮询接口会很高），`runs` 是出现过的运行次数。淘汰评分用 `runs`，展示用 `calls`，否则一个 3 秒轮询的接口会把真正的业务接口挤掉。

`unattributed`：请求开始前找不到任何带签名的 `ActionTarget`，或算出的 `sigId` 在 `AppAtlas` 里不存在。
这类观测**只更新端点，不建边**——宁可少一条边，也不能造一条假边。

---

## 6 目标设计：存储

`app/src/main/java/com/wangzan101/mika/api/FileApiAtlasStore.kt`，逐条对齐 `FileAtlasStore`：

- 路径 `filesDir/api_atlas/<sanitizedPkg>.json`
- AES-GCM 整文件加密（复用 `KeyCipher` / `KeystoreKeyCipher`）
- 写临时文件 → `fsync` → 原子替换；密钥库不可用时抛错，不覆盖原文件
- 密文损坏另存 `*.broken-<ts>` 并发布 `brokenNotice`
- `changes: StateFlow<Long>` 供界面刷新

**为什么不塞进 `AppAtlas`**：

1. `AtlasShare.SCHEMA = 2` 的导出/导入是逐字节可复现的，加字段就得升 schema 3，旧包全部拒绝，代价与收益不成比例；
2. 接口图谱比页面图谱敏感（能看出用了什么后端、要不要签名），用户可能只想清掉它；
3. 体积不同：页面图谱几十 KB，接口图谱可能到 MB 级，只有详情页才需要加载。

---

## 7 目标设计：界面

`App 经验` 详情页（`app/src/main/java/com/wangzan101/mika/ui/AppExperienceScreen.kt`）新增第 2 组，插在「页面与跳转」之后：

```
── 接口与数据 · 6 ──────────────
GET  /argus/api/v2/video/adv/mainPage        h5.if.qidian.com
     触发 1 页 · 12 次 / 3 次运行 · 200×11 402×1
  ▸ 字段（38）      展开后按层缩进，只显示路径与类型
  ▸ 触发页          福利中心
  ▸ 线索            Cookie · 疑似签名参数 ibex, borgus
```

- 行首 `GET/POST` 徽标；path 单行省略，host 其次要色。
- 默认折叠，展开才渲染字段树（实测 220 条、上限 240，长列表分页到「显示全部」）。
- 操作：改名（写 `nameSource=user`）、删除单条、清空该 App 的接口图谱。
- 列表页每个 App 的摘要行加「N 接口」，`appSummaryCountsText()` 同步改。
- 空态分两种：没学过 vs 抓包没开。后者给一句「接口图谱依赖流量捕获」，不做跳转，避免设置页耦合。
- 详情页顶部加一行抓包状态（`captureStatus()` 的 `activeMode` / `targetPackage` / `error`）：H5 页面抓不到时，用户要能立刻知道是探针没挂上，而不是以为这个 App 没有接口。

字符串新增 8 个键，写 `values/strings_settings.xml` + `values-zh` 等 10 个语言目录（缺翻译时 Android 回退默认值，不会崩）。

---

## 8 目标设计：给模型的入口

| 工具 | 用途 | 数据来源 | 风险 |
| --- | --- | --- | --- |
| `phone_find_api`（新） | 「完成度在哪个接口」 | 图谱（磁盘） | Read |
| `phone_wait_network`（已有） | 等一个新响应 | 实时抓包 | Read |
| `phone_get_network_response`（已有） | 拿完整响应体 | 实时缓存 | Read |
| `phone_read_api_field`（新） | 只取某几个字段 | 实时缓存 + JSON 路径 | Read |

`phone_find_api` 参数：`query`（自由词，如「福利中心 完成度」）、`field`（精确字段名，如 `IsFinished`）、`package`、`limit`。
匹配是确定性的：字段名精确 > 路径/名称分词命中 > 触发页标题命中；排序用命中数与 `ok` 次数。**不引入向量检索**，先看规则够不够。

`phone_read_api_field` 参数：`urlPattern` 或 `endpointId`、`path`（`Data.X.TaskList[].IsFinished`）、`limit`。
行为：从最近一次捕获的响应体里按路径取值，`[]` 表示遍历数组；没有缓存就返回 `no_captured_body`，让 Agent 先去开页面。
返回值必须经 `Untrusted.wrapWithin(...)` 包裹（与 `PhoneGetNetworkResponse` 同一口径），并且**只在内存里存在**。

v1 **不做提示注入**。`AtlasHintPolicy` 那种每步 1–3 行的注入在接口层还没有验证过收益，先只给工具，等 P3 的配对试验说话。

---

## 9 目标设计：隐私与安全

写盘的内容只有「结构」，没有「内容」：

| 落盘 | 不落盘 |
| --- | --- |
| host、模板化 path、method | 路径里的真实 id |
| query / body 的**键名** | query / body 的**值** |
| 字段路径与类型 | 字段值（含字符串长度） |
| 状态码、耗时、调用次数 | 完整 URL、Referer 值 |
| 触发页 sigId | 请求头值、Cookie、Token |
| `authTraits` 位掩码 | 任何请求体/响应体原文 |

- trace 文件写 `cacheDir/api_trace/<runId>.jsonl`，学习完成后立即删除；只有 debug 开关打开才保留，且不进 `files/runs/`（运行记录是明文的）。
- 复用 `AtlasSanitizer` 做包名清洗（排除 Mika 自身）与命名文本过滤。
- 端点/字段上限本身就是防泄漏阀：不设上限时，一个 `id` 字段就能把整张表撑成用户数据快照。

**这一条要写进测试**：用固定样本跑完后，在 `api_atlas/` 解密内容里 `grep` 任务名、手机号、token，必须 0 命中。

---

## 10 目标设计：覆盖率与盲区

| 场景 | 现状 | 影响 |
| --- | --- | --- |
| 原生 App API | 走自带 `libssl.so`，已验证 | 全覆盖 |
| WebView / H5（起点福利中心这类） | 依赖 `base.apk` 内嵌 chromium 的偏移 profile | **profile 不匹配时整页 XHR 都看不到**，且不报错；只能靠详情页的抓包状态提示 |
| HTTP/2 | `Http2PlaintextParser` 已解析 | 覆盖 |
| gRPC / protobuf | 端点可见，字段不可见 | 只记 host/path/状态 |
| 签名接口 | 只能读实时响应 | 不生成「可重放」结论 |
| WebSocket / SSE | 不覆盖 | 长连接数据不进图谱 |
| 同一接口多域名（CDN 别名） | 按 host 分会分成两条 | 已知限制，暂不合并 |

WebView 那条是**关键依赖**：刚做完的 `resolve_webview_apk_offsets` 是 H5 接口能被看见的前提。方案里不重复实现，只在详情页把 `phone_probe_ebpf` 的结果亮出来。

---

## 11 目标设计：分期

### P0 影子采集（不落图谱）—— ✅ 已实施

原计划与落地后的差异：

| 计划 | 实际 | 说明 |
| --- | --- | --- |
| `core/api/ApiUrl.kt` + `ApiShape.kt` | 另加 `ApiFilter.kt`、`ApiObservation.kt` | 过滤与 trace 模型独立成文件，P1 直接复用 |
| `app/api/ApiTraceCollector.kt` | 同名，输入改为 `ApiExchangeInput` | 不依赖网络层类型，可脱离 Android 单测与回放 |
| `PhoneNetworkManager.addExchangeSink` | 同签名，异常隔离 | 采集是旁路，坏一个订阅方不影响抓包 |
| `TaskRun` 起停 + 写 trace | 起停挂在 `watchTarget` 的目标包回调里，收尾在 `execute` 的 `finally` | 与 `attachToPackage` 同源，不额外读屏 |
| — | `FeatureFlags.apiGraphLearnEnabled()`，**默认关** | 保证「开关没开时无 trace、无目录、无磁盘写」 |
| — | `app/api/ApiTraceStore.kt` | 写 `files/api_traces/<runId>.json`，保留最近 20 份 |

单测 83 例（core 71 + app 12），覆盖：

- 归一化：host 小写/端口、数字段与 UUID 模板化、版本段保留、query 只留键并排序、尾斜杠、非法 URL；
- 形状：嵌套点路径、数组 `[]` 并集、类型冲突 `mixed`、int/long 分界、深度与字段上限、动态键折叠、BOM 嗅探；
- 过滤：静态资源、埋点（驼峰段、根路径 `/collect`、埋点域名段）、`UNKNOWN` 帧、204/304、HTML 页面、
  `/bookshelf/collect` 不被误伤、业务域名不被 `log`/`stat` 误伤、小 JSON 保留；
- 采集：端点与字段形状、**值与 query 值不进 trace**、body 键名保留、丢弃原因分桶、上限截断、超大响应跳过形状、耗时反推开始时刻。

**真机验收（2026-11-21 已完成，起点福利中心）**：见 §11.2。三次运行修掉了两处规则缺陷
（埋点过滤漏掉驼峰与根路径 `/collect`、字段上限 80 截断真实响应），并把「空 trace 不落盘」
改成「开关打开过就落盘 + 打日志」——否则无法区分「没抓到」与「全被过滤」。

开关默认关，两个打开方式：

- 评测跑器加参数 `evalApiGraphLearn=true`（走 `EvalHooks.apiGraphLearn`，不改本机设置）；
- 真机手测：直接调 `FeatureFlags.setApiGraphLearnEnabled(true)`（P2 才有界面开关）。

跑完一次任务后取 trace：

```bash
adb shell run-as com.wangzan101.mika ls files/api_traces/
adb exec-out run-as com.wangzan101.mika cat files/api_traces/<runId>.json
```

核对口径：把 trace 里的 `endpointId` 排重，与 `python tools/aaue_http.py <抓包> --dump` 导出的请求行对照，
差异逐条写清是「过滤规则丢掉」还是「没抓到」。

### 11.2 真机验收：起点福利中心（2026-11-21）

**环境**：真机 `3B15AN00MNB00000`；起点读书 7.9.486（`com.qidian.QDReader`）；模型 `gpt-6.1-terra`。
题是新加的 `eval/tasks/read/content/qidian-welfare-tasks.json`：打开起点 → 福利中心 → 激励任务 →
报「几项任务 / 已完成几项 / 第一项的奖励」。开关只经评测参数打开，本机设置没动。

```bash
adb shell am instrument -w -e class com.wangzan101.mika.eval.EvalRunTest \
  -e evalRun qidian-api-p3 -e evalSet qidian-welfare-tasks -e evalApiGraphLearn true \
  com.wangzan101.mika.test/androidx.test.runner.AndroidJUnitRunner
```

三轮数据（`output/api-graph/` 存了三份原始 trace）：

| 轮次 | 观测 | 端点 | 丢弃 | `mainPage` 字段 | 任务 |
| --- | --- | --- | --- | --- | --- |
| `qidian-api-p0` 修规则前 | 54 | 10 | 55 | 80（截断） | 8 步 / 152 秒 / 成功 |
| `qidian-api-p1` 只改了过滤 | — | — | — | — | 4 步 / 99 秒 / 成功，**没留下 trace** |
| `qidian-api-p2` 修过滤 + 上限 | 126 | 55 | 335（tracking 220） | 220（完整） | 4 步 / 99 秒 / 成功 |
| `qidian-api-p3` 补 `/collect`、`UNKNOWN` 帧 | 61 | 46 | 320（tracking 205、empty 90、invalid-url 18、html 1、other 3、static 3） | 220（4 次调用） | 14 步 / 231 秒 / 成功 |

`qidian-api-p1` 那一格空着，是这次最值钱的一条教训：当时还是「空 trace 不落盘」，而任务 4 步就结束、
页面已经停在福利中心，几乎没有新交换，于是目录里什么都没有——**分不清是探针没挂上、页面没发请求，还是全被过滤网掉了**。
现在开关打开过就落盘（空文件里也有 `dropped` / `droppedBy`），并在 `MikaTask` 打两行日志（挂上 / 收尾统计），
下一轮一眼能定位。

**修掉的三处缺陷（都是这一轮实测暴露，不是设计时想到的）**

1. **埋点过滤漏掉驼峰与根路径**。p0 的 54 条观测里 41 条是
   `zhiyan.monitor.tencent-cloud.net/access_v1.http_service/HttpCurveReportRpc`；p2 的 126 条里 47 条是
   `galileotelemetry.tencent.com/collect` 与 `publiclog.zhiyan.tencent-cloud.net/collect`。
   前者两头是埋点、驼峰里才看得见，后者是 SDK 上报的通用根路径，旧的「带分隔符的段」一条都没命中。
2. **字段上限 80 截断真实响应**。`mainPage` 一次 19.9KB 的响应能切出 220 条路径、24 个业务模块；
   上限 80 时只到第 10 个（`MoreRewardTab`），`PrivilegedTaskModule`、`ReadingPageTaskModule`、
   `ScoreDrawModule` 整块丢掉。提到 240 后 p2/p3 都是 220 条、`fieldsTruncated=false`。
3. **签名线索只看 query / body**。起点 `mainPage` 的 `tstamp` / `ibex` / `borgus` / `sdksign` 全在请求头，
   query 一个都没有；`authTraits` 当时算出 25（cookie + referer + 自定义头），补上头名后是 29（多了「疑似签名」）。

**端点集合与 `reverse/qidian/wv3_dump/`（46 份离线抓包）对照**

| wv3_dump 侧 | p3 trace | 结论 |
| --- | --- | --- |
| `023 .../video/adv/mainPage`、`020 .../checkin/getcurrentweekcheckininfo`、`025 .../readtime/readpage` | 都有（各 3 次） | 福利中心的核心三件全抓到 |
| `018`/`019 welfareCenter/*.html`、`017`/`020` 起一堆图片、`001` `qdclientlog`、`016` `monitor/report`、`026` `qreport` | 无 | 分别落在 `html` / `static` / `tracking`，符合规则 |
| `007`–`013`、`004`、`005`（「我的」页那条链路） | 无 | 这次任务没经过「我的」页，不是丢了 |
| — | `v4/bookcontent/getkey`、`v3/deeplink/geturl`、`teenmode/*`、`client/ostar`、`submit/deviceTokenV5`、`ywab.../experiments` | 冷启动链路，离线抓包那次从「我的」页开始，没覆盖到 |

**隐私复核**（验收第 3 条）：p3 的 trace 用 `grep` 搜任务名（`订阅券` / `惊喜礼包` / `白泽宇航员`）、
`Bearer`、token 片段，全部 0 命中；`IsFinished`、`Desc`、`RewardDesc` 这些**字段名**在（15 / 46 / 6 次）。
值确实没落盘。

**仍然留着、且认了的东西**

- `path.book.qq.com/collect`、`tangram.e.qq.com/updateSetting`、`xs.gdt.qq.com/style_factory/*`：广告 SDK 的配置与上报，只看名字判不出来，保留。
- `druidv6.if.qidian.com/argus/api/v1/client/ostar`：疑似行为上报，没有证据，先留。
- `invalid-url=18`：`method=UNKNOWN`、`host=unknown-host`、`path=/` 的 h2 优先帧/隧道帧，现在按无效丢。

**三次运行模型都答「3 项」，接口里是 9 条**

p0/p1/p3 三次都报「激励任务 3 项、已完成 1 项、第一项是 10点章节卡」；接口 `Data.DailyBenefitModule.TaskList`
是 9 条，`IsFinished` 只有 1 条为 1，第一条是 `10-3点订阅券`。屏幕上只露出 3 格，模型还把上面 1–9 格进度条
解释成「阶梯奖励，不是任务本身」。评分判过是因为题只要求一个能在屏上核实的条目（`answer_grounded` 的 `minItems=1`），
**这题是链路验收题，不是效果评测题**——但这条差异把「为什么要有接口图谱」讲清楚了：
它正好是 P3 那个 `phone_find_api` 要回答的问题，也说明该工具不是锦上添花。


### P1 学习与存储

- `core/api/ApiModels.kt`、`ApiLearner.kt`、`ApiStore.kt`（约 400 行）
- `app/api/FileApiAtlasStore.kt`（约 220 行）
- `AtlasRuntime` 的学习入口（`learnRun` / `learnAfterRun`）内先跑 `AtlasLearner`、再跑 `ApiLearner`（保证 sigId 已存在）
- 单测约 25 例：并集、门禁三档、版本升级、淘汰、坏文件、无归因不建边

验收：重启后图谱仍在；关开关时 `api_atlas/` 目录不创建。

### P2 界面

- `AppExperienceViewModel` 增加 `ApiEndpointItem` 与组装、改名/删除/清空
- `AppExperienceScreen` 新增分组与行、抓包状态行
- 字符串 8 键 × 11 语言
- 单测：ViewModel 组装与空态

### P3 消费与评测

- `core/cap/phone/PhoneApiTools.kt`：`phone_find_api`、`phone_read_api_field`，注册进 `PhoneGroup.tools`
- prompt 两条
- 固定问题集（起点 3 题、BOSS 3 题）配对试验 ≥8 轮

### P4 分享与导出（可延后）

- 独立 `ApiShare` schema 1，仅结构；导入默认只读、不计入本机执行证据

---

## 12 验收与评测

**功能验收（可复核）**

1. 起点福利中心：图谱含 `GET h5.if.qidian.com/argus/api/v2/video/adv/mainPage`，字段路径里要有「每日福利模块 → 任务列表 → 完成标记」这一条，触发页为「福利中心」。
2. 对问题「福利中心今天完成了几轮」：开启时从响应逐条取到 9 个完成标记（1 条已完成 / 8 条未完成），与 `wv3_dump` 里的 JSON 一致；关闭时走原读屏路径。
3. 隐私：固定样本跑完后，解密文件里搜不到任务名、token、手机号。
4. 零副作用：开关关闭时无 trace、无目录、无磁盘写。

**效果评测（不许含糊）**

- 同一批问题、同一模型、同一 App 版本，开/关各 ≥8 轮，配对比较步数与答案正确率；
- 8 轮以下只报描述统计，**不写「提效」**——这是页面图谱评测已经踩过的坑（3 对 2 时曾误当结论）。

**单测命令**

```powershell
.\gradlew.bat :core:test --tests "com.wangzan101.mika.core.api.*"
.\gradlew.bat :app:testDebugUnitTest --tests "com.wangzan101.mika.api.*" --tests "com.wangzan101.mika.ui.AppExperienceViewModelTest"
```

---

## 13 需要拍板的三件事

1. **隐私基线**：只存键名与类型、值一律不落盘——这条能不能作为前提？若要求连字段名也不存，字段索引就失去意义，方案要退化成「只有端点列表」。
2. **归因口径**：接受「请求前最后一条动作的 afterSig」这个近似，并把无法归因的观测只算端点、不建边？
3. **分享**：P4 延后（推荐），还是 P2 就要能导出？

---

## 14 与现有文档的关系

- `docs/dev/architecture.md` 第 298 节「App 页面地图」之后新增「App 接口图谱」小节，指向本文；
- `docs/dev/roadmap.md` 第 17 条补一句接口层；
- 页面图谱的隐私与正确性约束（`app-atlas-governance.md`）同样适用，且接口层更严：不存值。
