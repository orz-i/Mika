# App 接口图谱（Api Atlas）补齐方案

> **结论先说**：页面图谱解决的是「这个 App 有哪些页、怎么走」，接口图谱要解决的是「这一页的数据从哪来、字段叫什么、能不能再拿一次」。
> 现有的抓包能力只在任务进行中活着（`phone_wait_network` / `phone_get_network_response` / `phone_inspect_network`），任务一结束就全部丢掉，
> 于是同一个 App 每次都要重新猜接口。这份方案把抓到的流量沉淀成按包名的图谱，与 `AppAtlas` 平行，不改动现有页面图谱、分享格式与回放链路。

- 目标版本：Mika 当前主线（`core/atlas` 已落地，`aau` 已支持 WebView 内嵌库偏移）
- 2026-11-21

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

## 3. 数据模型

新增包 `core/src/main/kotlin/com/wangzan101/mika/core/api/`，与 `core/atlas/` 平行。

```kotlin
const val MAX_API_ENDPOINTS = 200          // 每 App 上限
const val MAX_API_EDGES = 1000
const val MAX_API_FIELDS = 80              // 每端点保留的字段路径
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
| 4 | 疑似签名参数 | query / body 键名命中 `sign|signature|_sign|sig|ibex|borgus|tstamp|x-sign|x-t`（**线索，不是结论**） |
| 8 | 带 Referer | 请求头有 `referer` |
| 16 | 自定义头 | 存在 `x-` 前缀且不在上述集合的头 |

> `authTraits` 只是给模型的提示词，不能当作「必须签名」的证明。起点那次是先看到 `ibex` 参数、再实测重放 402，才算证实；图谱里只写「疑似」。

---

## 4. 采集：`ApiTraceCollector`

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
| 响应体 < 64 字节，或 204 / 304 | 无信息 |
| 埋点：`/log`、`/report`、`/track`、`/beacon`、`/collect`、`/metrics`、`/analytics`、`qreport` | 起点抓到过整包 JSON 上报，会污染字段索引 |
| 首包即 4xx/5xx 且此前未见过的端点 | 只更新已知端点，不从错误响应建图 |

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

- 深度上限 6，端点字段上限 80，超出置 `fieldsTruncated=true`；
- 数组取前 20 个元素求并集，类型冲突记 `mixed`；
- 对象键数 > 32 且键名像动态值（纯数字 / 长 hex）时，折叠为 `*`，只保留一个样本路径，避免任务 id 把字段表撑爆；
- **值一律丢弃**，包括字符串长度都不记。

---

## 5. 学习：`ApiLearner`

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
| 字段 | 按 `path` 求并集，`seen++`；类型冲突降为 `mixed`；超过 80 条按「浅层优先 + seen 降序」截断 |
| 边 | 键 `(fromSigId, endpointId, tool)`；2xx 且 `learnDone` → `ok++`；2xx 且 `succeeded` → `weakOk++`；4xx/5xx → `miss++` |
| 版本 | 端点带 `appVersion`；App 升级后，旧版本端点标 `unverifiedAfterUpgrade`，被新版本命中后转回 |
| 淘汰 | 超过 200 端点 / 1000 边：复用 `AtlasEviction.evict`（近因 0.4 + log 命中 0.6），入口边不淘汰 |

去重口径要分清：`calls` 是调用次数（轮询接口会很高），`runs` 是出现过的运行次数。淘汰评分用 `runs`，展示用 `calls`，否则一个 3 秒轮询的接口会把真正的业务接口挤掉。

`unattributed`：请求开始前找不到任何带签名的 `ActionTarget`，或算出的 `sigId` 在 `AppAtlas` 里不存在。
这类观测**只更新端点，不建边**——宁可少一条边，也不能造一条假边。

---

## 6. 存储：`FileApiAtlasStore`

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

## 7. 界面

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
- 默认折叠，展开才渲染字段树（38 条上限 80，长列表分页到「显示全部」）。
- 操作：改名（写 `nameSource=user`）、删除单条、清空该 App 的接口图谱。
- 列表页每个 App 的摘要行加「N 接口」，`appSummaryCountsText()` 同步改。
- 空态分两种：没学过 vs 抓包没开。后者给一句「接口图谱依赖流量捕获」，不做跳转，避免设置页耦合。
- 详情页顶部加一行抓包状态（`captureStatus()` 的 `activeMode` / `targetPackage` / `error`）：H5 页面抓不到时，用户要能立刻知道是探针没挂上，而不是以为这个 App 没有接口。

字符串新增 8 个键，写 `values/strings_settings.xml` + `values-zh` 等 10 个语言目录（缺翻译时 Android 回退默认值，不会崩）。

---

## 8. 给模型的入口

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

## 9. 隐私与安全

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

## 10. 覆盖率与盲区

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

## 11. 分期

### P0 影子采集（不落图谱）

- 新增 `core/api/ApiUrl.kt`、`core/api/ApiShape.kt`（纯函数，约 220 行）
- 新增 `app/api/ApiTraceCollector.kt`（约 180 行）
- `PhoneNetworkManager` 增加 `addExchangeSink`（约 30 行）
- `TaskRun` 起停 collector、写 trace（约 40 行）
- 单测：归一化 15 例、形状 12 例（动态键折叠、类型冲突、深度截断）

验收：起点福利中心一次打开、BOSS 一次搜索，trace 的端点集合与 `tools/aaue_http.py --dump` 手工核对一致，差异逐条解释。

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

## 12. 验收与评测

**功能验收（可复核）**

1. 起点福利中心：图谱含 `GET h5.if.qidian.com/argus/api/v2/video/adv/mainPage`，字段含 `Data.DailyBenefitModule.TaskList[].IsFinished`，触发页为「福利中心」。
2. 对问题「福利中心今天完成了几轮」：开启时从响应取到 9 条 `IsFinished`（1 条 true / 8 条 false），与 `wv3_dump` 的 JSON 一致；关闭时走原读屏路径。
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

## 13. 需要拍板的三件事

1. **隐私基线**：只存键名与类型、值一律不落盘——这条能不能作为前提？若要求连字段名也不存，字段索引就失去意义，方案要退化成「只有端点列表」。
2. **归因口径**：接受「请求前最后一条动作的 afterSig」这个近似，并把无法归因的观测只算端点、不建边？
3. **分享**：P4 延后（推荐），还是 P2 就要能导出？

---

## 14. 与现有文档的关系

- `docs/dev/architecture.md` 第 298 节「App 页面地图」之后新增「App 接口图谱」小节，指向本文；
- `docs/dev/roadmap.md` 第 17 条补一句接口层；
- 页面图谱的隐私与正确性约束（`app-atlas-governance.md`）同样适用，且接口层更严：不存值。
