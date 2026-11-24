# AgentLoop 治理：回合边界、调用配对与有界上下文

2026-11-19。对应本轮审计 AL-01–AL-07；压缩不新增模型调用，轻量提议被拒时沿用主模型重问。不改变付款审批、能力衰减、只读装配或默认提速开关。

## 已收紧的不变量

| 审计项 | 实现 |
| --- | --- |
| AL-01 插话后继续旧修改 | `RunInbox.claimToolStart` 与投递共用锁，作为单次调用的逻辑启动边界。排队中的调用获准之前再次检查；新消息到达后跳过未启动调用，补 `skipped_for_new_message` 结果。已启动调用继续按执行入口的原有取消/完成契约收口 |
| AL-02 轻量路由漏检查 | 审查轻量回复的所有调用，按实际 `ToolTraits.modelTier` 而不是通用 Verb 判断。任一调用需主模型时整条提议不执行并改派，事件记录实际触发改派的工具 |
| AL-03 收尾重答悬空调用 | 重答前给所有未执行调用补结果，再追加插话；只执行选中的一个终结工具，其余调用明确跳过。文本核对/终结工具执行期间到达的补充同样在可重答次数内接上，收尾仍最多 3 次尝试 |
| AL-04 状态与事件不一致 | 外层取消及异常也经过统一 finally。先关闭收件箱和交互，再发布公开终态与结束事件；保留原异常/取消向上传播，不伪装成功 |
| AL-05 折叠丢业务数据 | 从原始折叠段承接有预算的历史观察节选，保留来源、调用/观察编号与数据。真实运行优先取证据账本里已脱敏、去回显的观察，不从失效观察的原始结果恢复；模型申报进展随新尾部上下文追加并明确标注未核实 |
| AL-06 并发无背压 | 单回复最多 64 个调用，超限整条不执行而不是执行半条计划。普通并发波次最多 4 个 worker，同能力/端点最多 2 次在途；结果仍按原调用顺序合并 |
| AL-07 Discuss 空工具不构成约束 | 检查所有提议，不能用首个本地检索遮住后续写入。Discuss 空工具重问后若仍返回结构化调用，在进入执行入口前结束为模型错误，不执行它们；此兼容开关默认仍关闭 |

`skippedCall` 为普通回合与收尾共用的未执行结果构造：`executed=false`、`counts_as_failure=false`，不计入操作失败熔断。并发波次中已经执行的真实结果先全部收齐，再停止后续波次，不把真实执行误标成未执行。

## 有界历史数据不是无限记忆或新的指令

实现位于 `core/src/main/kotlin/com/wangzan101/mika/core/agent/CompactedObservations.kt`，由 `ContextCompressor` 在实际折叠时调用。

- 每份历史数据附注至多 **1024 个粗估 Token、24 条节选**，每条正文至多 96 个粗估 Token；实际额度还受剩余可用窗口的四分之一限制，计入校准倍率与固定开销。这里是上下文估算预算，不是供应商的精确分词保证。
- 只有一次压缩重写时生成；两次压缩之间继续沿用已有视图并只追加新消息，不每步重写前缀。
- 有 `ReadTag` 时只查 `EvidenceLedger.byIds`，沿用同一套输入回显过滤；观察失效则省略，不从原始工具结果补回可能已被过滤的文字。
- 没有观察标签的兼容历史只接受已知只读工具的明确数据字段；不把写入参数、模型叙述或完成声明保存成观察。
- 失败、受保护、密码输入和人机交互结果不进入这份附注；URL 来源中的凭据查询参数脱敏。节选及来源置于不可信数据边界内，结束标记转义，不提升为控制指令；这不是对真实模型抗提示注入能力的验证。
- 生成的附注会标注省略/截断，完整材料仍需按来源重读；若附注导致请求仍超过可用窗口，先省去它，不能挤掉原目标、用户增量或最新结果。**不保证任意长任务的全部事实永久保留，也未证明某个真实任务因此一定提效。**
- `ContextRetention.PinReference` 工具往返与用户交互往返不被改成单纯的 `applied`；原目标、补充和纠正消息保留。两种摘要模式共用“原目标 + 按顺序应用用户增量”的提醒，不再把最新一句补充误说成完整任务。
- `<task_progress>` 仍从用户可见叙述取走，但当前计划以有限 JSON 节选放到新尾部运行时状态；标为模型申报，不自动将 `complete=true` 或数量到达视作核验通过。

## 任务不会无限跑下去：计数停滞与抓包窗口

2026-11-23。起因是一次真机端到端：BOSS 直聘按「成都全栈开发和 AI Agent 开发」采集岗位，明文解出来了、15 条岗位已经读到手，但模型申报的 `done` 一直是 0，完成条件永远不到达，最后是人工在 69 步、17 分 27 秒、约 199 万 Token 时掐掉的。三处一起改：

- **申报计数停滞提醒**：`TurnPolicy.metricStalled`，由 `AgentLoop` 每步开头比对 `metric.done` 最后一次变化的步数；连续 8 步没动、步数上限 ≥12 时提醒一次（`metric_stalled`，来源 `progress count`），让它按已读到的条目更新计数或就此交出结果。只提醒、不叫停：真在长任务里推进时，一句话的代价远小于一个跑不完的任务。用 `boundary` 描述、没有数可数的开放采集不在此列。
- **提示词**：`total` 只在用户给了数量或来源有已知总数时写，否则用 `boundary`；`done` 按已读到的条目即时更新，读完条目仍留 0 算误报；来源确实没有那么多时下调 `total`；`done` 不再变化就交出结果。`system/principles.md` 另加一条：任务要在给定步数内收尾，列表不能靠一直滚动「完成」，到用户要的那个点、或同样的条目反复出现时就停，并说清覆盖到哪儿。
- **把「停」和「失败」分开**：`phone_collect` 的 `no_new_content`、到达上限、滚动失败都表示那一边没有更多文字，已返回的行仍是内容，只能在它到达的位置往下继续，不能回来重读同一屏；`phone_inspect_network` / `phone_get_network_response` 的 `count:0` 与 `not_found` 表示当前窗口里没有，不表示这个 App 没有这样的接口。

抓包留存（`PhoneNetworkManager`）：

- 内存历史 `MAX_HISTORY_ENTRIES` 250 → 600。超窗口时先挤掉早于 `captureStartedAtMs`（本轮抓包开始）的条目，吃完才动本轮最早的——以前直接 `removeLast`，长任务里正在查的那条会被上一轮残留挤掉。
- 磁盘响应体 500 个文件 → 800 个文件 + 128 MB 双限，都按最久没写过的先删；只按条数限会在个别大响应上把缓存撑到 GB 级，只按字节限会让大量小响应把目录撑成几十万个文件。
- `CaptureRetention{kept, capacity, trimmed, oldestKeptAt}` 随两个查询工具返回，空结果带 `hint`：窗口里确实没有，和「有，但已经被挤掉」必须能分开。以前两者都答成「没有」，调用方只能一直重查。

这些改动只证明代码路径和提示词口径变了；真机上能不能提前收尾仍须按原场景重跑确认。

## 真机复跑记录（2026-11-24）：抓包通过，Agent 侧另有四个问题

同一场景（BOSS 直聘，成都全栈/AI Agent 岗位）在 `3B15AN00MNB00000`（KSU root，Mika 0.1.39）上复跑了两轮。**抓包链路结论是通过的**，不再作为待办；Agent 自身的四个问题记在下面。

aau 抓包侧的证据（两轮一致）：

- 插件包加载：`Installed builtin capture plugins: bosszhipin, douyin, generic-cronet, xiaohongshu`，用户覆盖文件 `aau_capture_plugins.json` 在升级安装后仍在；
- 预热与通道：`prepareCapture(com.hpbr.bosszhipin): enabled=true root=true bridge=true` → `capturing=true`，说明「先加载插件源再判断要不要进程内通道」的修复在真机上生效；
- SSL 点位：`libzp-sdk-transport.so source=rule write=0x1e1ec8 read=0x1e1a6c`（规则命中），`libzp-sdk-im.so` / `libzp-sdk-rtc.so` 走 `openssl_heuristic` 兜底，三个 so 均 `native attached`；
- 明文：同一份 cardlist 响应解出 35,402 字节、15 条岗位卡片；另一轮 68 步里 `decoded jni` 20 次。

该场景**无法端到端跑完**的原因不在抓包：BOSS 直聘本轮是未登录态，只有登录页（微信登录 / 一键登录 / 其他方式登录），模型按规则 `hand_to_user` 交回人工，不再自行换 App。这条记为场景阻塞，不是缺陷。

Agent 侧问题：

| # | 现象 | 根因 | 处理 |
| --- | --- | --- | --- |
| 1 | 任务能一直跑：68 步、26 分钟、30 次 scroll 仍未收口 | `FeatureFlags.taskStepLimit()` 默认返回 `null`，`TaskRun` 落到 `AgentLoop.UNLIMITED_STEPS`——**默认根本没有步数上限**，那个「跑满上限会收口」的兜底从没生效过 | 默认值改走 `DEFAULT_TASK_STEP_LIMIT = 120`；用户显式选「不限制」仍存 0，语义不变 |
| 2 | 模型把开放采集的边界写进 `total`（`"metric":{"done":0,"total":"boundary"}`），整个进度块被丢，任务失去完成条件 | `total` 只接受整数；非整数直接抛错，连 `criteria` 一起丢 | `totalOrBoundary()`：非数字的 `total` 当 `boundary` 用；带数字的（`"共 20 条"`）照旧取数 |
| 3 | 换端自由发挥：BOSS 撞登录墙 → 自己开猎聘 → 又一次直接开 `zhipin.com` 网页版 | 「内容属于哪个端」这条规则写在 `capabilities/phone.md`，而该段落**只在那组工具出现在工具列表时才注入**——决定「用 App 还是用网页」的那一刻它还不存在 | 规则上移到 `capabilities/general.md`（通用工具一直在）：内容在 App 里的任务就在 App 里做、不许拿同品牌网页版顶替、首句报出平台名、中途只允许用户要求时切换 |
| 4 | 采集类任务不肯用 `phone_collect` / 接口查询：59 次 tap+scroll，0 次 collect、0 次接口调用 | 提示词只说明了这两个工具的存在和停止语义，没说「列表该用它们」 | `phone.md` 补使用引导：列表优先 `phone_collect`，屏幕读不动改走 `phone_inspect_network`；`phone` 预算 23_000 → 23_400 |
| 5 | （#4 修好后暴露）`phone_collect` 只读到第一屏就 `stoppedBecause=action_failed`，多屏采集靠模型手工 `phone_scroll` 补 | `Scroll.execute` 在 `amount == 3` 时走无障碍控件滚动，`AndroidScreen.scroll` 只 `verified { node.performAction(...) }`——动作返回 false 直接 `action_failed`，没有手势兜底；而 `phone_collect` 内部正好把 `amount` 硬编码成 3。App 自绘的信息流（小红书推荐流）不接受控件滚动 | `AndroidScreen.scroll` 给控件滚动配 `fallback = fingerScroll(down)`（屏幕中部纵向手势，与「找不到可滚控件」同一条路），`retryWhenUnchanged = false` 保住「滚到头 → atEnd」语义；找不到控件的分支也改走同一个手势，少一层 `verified` |
| 6 | 模型把进度块当工具调：`task_progress` → `unknown_tool` + 一长串可用工具清单 | 进度块是回复里的 `<task_progress>` 文本，从不在工具表里；通用的 unknown_tool 文案只会列出工具名，没说明「这不是工具、该怎么写」 | `ToolDispatcher.unknown()` 对 `task_progress` / `progress` / `update_progress` / `report_progress` 回专门文案（`unknown_tool_progress`）：不是工具，按 `<task_progress>{criteria,metric,complete}</task_progress>` 写进回复，不占步数、不额外发请求，本次调用什么都没记 |

真机复核结论：#1、#2 有单测与安装后行为覆盖；#3 在改后同一场景生效（首句即报「BOSS直聘已安装，我将用它来搜集…」，登录墙按 `hand_to_user` 交回）；#4、#5 在已登录的小红书上闭环，见下节。

### 第 4 / 5 条复核：已登录 App 的列表采集（2026-11-24）

场景固定为小红书 9.54.0（已登录，首页「发现」推荐流，成都本地内容），任务原文：「打开小红书（已在前台），从首页推荐流里收集 10 条笔记，列出标题、作者、点赞数。」三条流水号可在 `files/runs/` 下对照。

| 轮次 | 目录 | 关键观测 | 结论 |
| --- | --- | --- | --- |
| 改前 | `20261124-104934-ltcfqo` | 7 步、**0 次 tap**、1 次 `phone_collect`；第 2 步模型自己 `tool_activate(["phone_collect"], reason=读取推荐流列表)`；但 collect 返回 `pagesInspected=1, scrolls=0, stoppedBecause=action_failed`，靠模型两次手工 `phone_scroll` 补齐 | 第 4 条的「不再逐条点」成立；采集本身只吃到第一屏 |
| 改后 | `20261124-105803-9ugcpy` | 5 步、0 tap、0 collect；连续 3 次 `phone_scroll amount=3` 全部 `ok=true changed=true`（此前同参数直接 `action_failed`），靠滚动返回的屏幕文字逐屏累加 | 控件滚动的兜底生效 |
| 改后 | `20261124-110210-39ti19` | 新对话重置工具面后重跑：`tool_search → tool_activate(["phone_collect"])`，collect 返回 **`pagesInspected=6, scrolls=5, items=91, stoppedBecause=page_limit`**，7 步收口 `judged=done` | 多屏采集闭环 |
| 改后 | `20261124-110910-30en50` | 装上手势兜底再跑：4 步收口，`phone_scroll amount=4` 两次全 `ok=true changed=true` | 手势路径无回归 |
| 改后 | `20261124-111134-wneapc` | 任务改「20 条」逼多屏：collect 返回 `pagesInspected=5, scrolls=4, items=72`，回答里写「读到 28 条后停在第 5 屏」，7 步 `judged=done`；同轮模型试图用工具调用报进度，拿到的是 `unknown_tool` 加一长串工具清单 | 采集闭环；顺带暴露第 6 条 |

三条流水共同点：`stepLimit=120`（第 1 条修复在真机上生效）、首句即锚定 App、全程 0 次 tap（对比 BOSS 那轮 59 次）。数据真实性也做过对照：返回的标题/作者/点赞数与屏上卡片一致，第一条卡片只显示「赞」没有数字时模型如实标注，没有补造。

一个口径提醒：`phone_scroll` 的返回里本来就带滚动后的屏幕文字，工具面里已有 `scroll` 时模型常直接用它累加列表，而不是先 `tool_search` 去开 `phone_collect`（`105803` 那一轮就是这样）。两种做法都算合格——collect 的增量价值在合并去重多屏文本、并在「两屏没有新内容」时自己停；判断第 4 条要看的是「有没有老老实实读列表」，不是「必须调用名字里带 collect 的那个工具」。

本轮单测：`:app:testDebugUnitTest`、`:app:compileDebugKotlin` 通过；`:core:test` 除 `HttpServerTest.keepsServingWhenConnectionOptionsDoNotIncludeClose`（代理环境既有失败，见下文「本机验证结果」）外全绿；新增 `progressBlockCalledAsToolIsToldItIsNotATool` 覆盖第 6 条。提示词体积快照按新文案重录（`feedback` 52710 → 54112 字节）。


## 验证

新增仓库内回归：

- `AgentLoopGovernanceTest`：收尾多调用配对、轻量全提议改派、MCP 自定义层级、串行插话、外层取消与异常、排队任务的插话/取消、全局及端点并发额度、超限回复整条拒绝、Discuss 加固、结束前关闭输入；
- `AgentLoopContextRetentionTest`、`TaskProgressTest`：跨回合进展及真实分发器账本的数据承接、未核实标记与有界数据边界；
- `ContextRetentionGovernanceTest`：两种摘要模式、真实账本优先、观察失效不恢复原文、去回显、预算及校准倍率、敏感/保护过滤、来源 URL、提示注入边界、参考资料及增量目标保留。

```powershell
.\gradlew.bat :core:test --tests "com.wangzan101.mika.core.agent.*" --tests "*AgentLoop*" --tests "com.wangzan101.mika.core.context.*" --tests "com.wangzan101.mika.core.run.*" --tests "com.wangzan101.mika.core.verify.EvidenceLedgerTest" --tests "com.wangzan101.mika.core.tool.ToolDispatcherParallelIsolationTest" --tests "com.wangzan101.mika.core.child.*"
```

### 本机验证结果（2026-11-19）

- 新增 **31 项回归全部通过，无跳过**。
- 最后一轮 `:core:test` 共发现 3356 项：**3334 通过、22 跳过、0 失败/错误**。跳过项包含 Windows 下的 POSIX/链接能力条件、没有输入记录的重评分任务及本机代理相关网络条件，不算作通过。
- `:app:compileDebugUnitTestKotlin` 成功；这是 Android 测试源码编译，不是真机执行或全部 Android 单测通过的声明。
- 前一轮 HTTP 保活用例曾失败；复核及最后一轮被既有代理拦截检测跳过。本轮未修改 HTTP 实现，也未把这条网络用例标为已修复。
- 本机使用的仓库外 Gradle init 仅关闭 core 测试源码的增量编译并隐藏编译警告，规避之前跨盘探针的缓存干扰；没有注入外部测试源码，不加入提交。

上下文溢出场景的金样只更新了摘要提醒内容对应的哈希，不全量重录其他场景。测试使用假模型/端口，不执行真实付款、发送或设置修改；真机、供应商协议与长期资源压力仍须独立验证。
