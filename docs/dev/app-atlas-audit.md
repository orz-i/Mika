# App 图谱（App Atlas）现状体检

> 本文保留初次审计及当日修复过程，部分表格是修复前快照。后续隐私、输入框身份、启动入口、精确寻路与分享摘要治理见 [app-atlas-governance.md](app-atlas-governance.md)，以该文和当前源码为准；原始实测的长轮归因已在实测报告中校正。

> 结论先说：**代码不是毛坯，产品是毛坯。**
> 数据结构、签名算法、加密存储、离线学习、三级使用（提示 / 快捷 / 寻路）、导入导出都已落地，测试也有 54 个；
> 但「图谱」在产品里没有形状、不能纠错、没有冷启动、没有度量、没有文档，也没有在真实任务里验证过。
> 这篇按「能看见的证据 → 缺口 → 补法」写，最后给了分期。
>
> **2026-11-19 更新**：P0 五项已落地（弱成功边、入口边、页面改名/删除、提示中文化、架构文档），
> 见文末「P0 落地记录」。P1/P2 未动。

---

## 1. 实据

| 项 | 数字 / 事实 |
| --- | --- |
| 核心实现 | `core/atlas/` 11 个文件，3 309 行 |
| 单测 | `core/src/test/kotlin/com/wangzan101/mika/core/atlas/` 与 `app/src/test/java/com/wangzan101/mika/atlas/`，3 708 行，64 个 `@Test` |
| UI | `AppExperienceScreen.kt` 1 014 行 + `AppExperienceViewModel.kt` 859 行 |
| 提交历史 | 只有 2 个提交：`255d7069`（core 全套）、`b43dfe7a`（UI 全套），之后再没有迭代 |
| 文档 | `README.md`、`docs/features.md`、`docs/dev/architecture.md`（429 行）、`docs/dev/roadmap.md` **均未提及** atlas / 图谱 / 页面地图 |
| 评测 | `eval/tasks/` 下 8 个分类，**没有一条** atlas 相关用例 |
| 状态 | 与路线、App 知识、技能四套机制并存，互不引用 |

一次性堆完、没有第二轮打磨，这是"毛坯感"的直接来源。

---

## 2. 完成度矩阵

| 层 | 现状 | 打分 | 主要缺口 |
| --- | --- | --- | --- |
| 页面签名 | Activity + 64 位 SimHash + 静态锚点 / resourceId，列表内容剥离、角标剥离、底部导航降权、选中态加权 | ★★★★☆ | 无页面类型（弹窗/权限/WebView/Tab）；阈值 0.82 全局硬编码 |
| 数据模型 | `AppAtlas` / `AtlasScreen` / `AtlasControl` / `AtlasEdge` / `AtlasShortcut`，带版本、命中、无效果、LRU 上限 | ★★★★☆ | 边无类型；无"入口"；无"页面分组"；无缩略图 |
| 存储 | 按包名一文件，AES-GCM 整文件加密，临时文件 + fsync + 原子替换，坏文件隔离并上报 | ★★★★★ | 无导出全量备份；无跨设备同步 |
| 学习 | 任务结束后离线跑，`LearnGate.done` 门禁，版本升级标 `unverified`，控件计数、边计数、快捷挖掘 | ★★★☆☆ | **正向边只在 Done 时计**；跨包边直接丢；只用本次 run，不回放历史 |
| 命名 | 未命名页面批量调轻量模型，12s 超时，失败回退 Activity 短名 | ★★★☆☆ | 无用户纠正；无批量改名；无跨 App 术语统一 |
| L1 提示 | 匹配当前页 → 输出 1–3 行出边/快捷/控件，350 字符上限，`seenScreens` 去重 | ★★★☆☆ | 英文硬编码；只提示"出口"不提示"这一页该看什么"；无效果埋点 |
| L2 快捷 | `phone_run_shortcut`，逐步 `Origin.Replay` 核对，连续 3 次偏离自动停用 | ★★★★☆ | **需同一序列出现 ≥2 次才晋升**，日常使用几乎触发不到 |
| L3 寻路 | `phone_navigate_to`，Dijkstra 按成功率加权，最多 8 跳 | ★★★☆☆ | 只能在同一 App 内导航，**没有入口边**，从桌面无法寻路 |
| UI | App 列表 / 详情 / 页面展开（控件、出边）/ 路线 / 快捷 / 知识 / 分享 / 导入 / 清空 / 搜索 | ★★★☆☆ | **没有图**；无重命名；无页面级删除/合并；无筛选；无质量指标 |
| 度量 | 无 | ☆☆☆☆☆ | 没有命中率、节省步数、token 对比 |
| 文档 | 无 | ☆☆☆☆☆ | 用户和开发者都不知道这功能存在 |

---

## 3. 十个具体缺口（按严重度）

### G1 · 正向边被"强门禁"卡死 → 图谱长期没有边

`AtlasLearner.kt:282`：

```kotlin
if (learnDone) {
    upsertEdgeOk(edges, fromScreen.sigId, toScreen.sigId, step.tool, step.target, step.atMs, version)
}
```

`learnDone` 来自 `LearnGate.done`（`LearnGate.kt:13`），要求 `TaskState.Succeeded && outcome == Done && unconfirmed.isEmpty() && !doubtful`。
而提示只读 `ok > 0 && miss <= ok` 的边（`AtlasHintPolicy.kt:56`）。

净效果是一个死锁：
**没判定 Done → 边不计数 → 没有提示 → 仍然靠模型 → 判定依旧严格。**
新用户跑十次任务，图谱里很可能只有一堆孤立节点，L1 永远不触发。

> 补法：边计数分档。`strongOk`（Done）保持现状，新增 `weakOk`（`TaskState.Succeeded` 即可）。
> 提示时读 `strongOk + weakOk × 0.5` 且要求总成功 ≥2；导出时 `weakOk` 可剥离。
> 这是最小改动、收益最大的一处，`AtlasEdge` 加一个字段即可。

### G2 · 跨包边被丢弃 → 每个 App 都没有"入口"

`AtlasLearner.kt:276`：

```kotlin
val intraAfter = step.afterSig?.takeIf { step.afterPkg == pkg }
```

从桌面点开微信：before 是 Launcher、after 是微信首页，`afterPkg != pkg`，这条边不建。
于是 `phone_navigate_to` 只能"已在微信里"才有用（`PhoneAtlasTools.kt:221` 用当前前台包取 atlas）。

> 补法：加虚拟入口节点 `__entry__`。学习时若 `beforePkg != pkg && afterPkg == pkg`，建一条
> `__entry__ --launch--> 首页` 的边；`AtlasNavigator` 支持从入口起跳（先 `phone_launch_app`，再走内部边）。

### G3 · 「图谱」在 UI 里不是图

详情页是三层折叠列表：页面 → 控件 / 出边（`AppExperienceScreen.kt:428-443`、`:540-629`）。
没有拓扑、没有主路径、没有页面密度，用户看不出"这个 App 我摸清了没有"。

> 补法：详情页顶部一块 `Canvas` 拓扑缩略图。节点半径按 `hits`，边粗细按 `ok`，未验证节点虚线，点击节点滚到对应行。
> 分层布局即可（入口 / 一级 / 二级），不必上力导向，也不必引第三方库。

### G4 · 只能读，不能改 → 一次错命名永久错

数据模型留了 `TITLE_USER`（`AtlasModels.kt:100`），`AtlasNamer` 也遵守"用户命名不覆盖"（`:40`），
但 **`AppExperienceViewModel` 里没有任何写入标题的方法**，UI 也没有入口。
用户看到 `LauncherUI#3fa2c1` 只能整包清空。

> 补法：`AtlasRuntime.renameScreen(pkg, sigId, title)` + 详情页页面行右侧「⋯ → 重命名」；
> 顺带 `removeScreen`、`mergeScreens`（把 A 的边并到 B）。

### G5 · 冷启动没有种子，也没有"主动建图"

空手起步，只能等任务自然跑过。跑过的页面才有节点，节点有边才有提示。
没有任何"一键探索这个 App"的入口。

> 补法两条腿：
> 1. `AtlasLearner.learnFromHistory(runStore)`：把已有历史 run 全量回放一遍（用户升级后立刻有图）；
> 2. 「探索此 App」：内置一个受控探索任务（打开 → 遍历底部 Tab → 各 Tab 首屏 → 返回），一次性建出骨架图。
> 探索必须走现有 `ActionExecutor` 安全链，不新增权限。

### G6 · L2 快捷的挖掘门槛，日常使用达不到

`AtlasShortcutMiner.kt:28` `MIN_OCCURRENCES = 2`：同一入口、同一 skeleton 序列要出现两次才晋升。
且只在 `learnDone` 的 run 里挖。真实用户一周也未必重复同一串操作两次。

> 补法：候选池已经存在（`candidatePatterns`，首次 `seenCount=1`）。把"晋升"改成
> **1 次即可用、2 次才推荐**：候选也允许 `phone_run_shortcut` 调用，只是提示里标注「试用」。
> 连续 2 次偏离直接降级，安全链不变。

### G7 · 页面类型缺失 → 弹窗和权限框污染图谱

签名只看结构，不区分「这是首页 / 这是对话框 / 这是系统权限弹窗 / 这是 WebView」。
一次系统弹窗就会建一个 `#a1b2c3` 节点，还带一条边；下次不再出现，永久占坑（200 页上限里）。

> 补法：`AtlasScreen.kind`（home / page / dialog / permission / webview / keyboard），
> 由 `windowType + 节点特征（是否有 dim 背景、是否只有一个确认按钮）+ role` 推断；
> dialog / permission 不建独立节点，记成父页面的一条"临时出口"，7 天无命中自动清理。

### G8 · 自指边没有语义

点 Tab、开弹窗、开关设置：页面签名不变但 `effective = true`，会建一条 `from == to` 的边。
UI 里显示成「→ 当前页」，提示里显示「→ current screen」（`AtlasHintPolicy.kt:111`），信息量为零。

> 补法：`AtlasEdge.kind`（navigate / in-page / back / tab / dialog），自指边默认 `in-page`，
> 提示里改写成「切换 Tab：消息」「打开：设置弹窗」这种可读形式。

### G9 · 没有度量，无法证明有用

`eval/tasks/` 没有 atlas 用例；运行时也没有「提示命中 / 被采纳 / 省了几步」的埋点。
现在无法回答唯一重要的问题：**这东西到底省了多少 token 和几步？**

> 补法：先在 `RunEvent` 里记「提示已展示」（包名、页面、边数）与「提示被采纳」
> （下一步动作命中提示里的边）；再在 eval 里加一组对照：
> 同一批 App 任务，`atlasHints=on/off` 各跑 N 次，比较步数、模型往返、token、完成率。
> 没有这组数，P2/P3 的投入就是盲投。

### G10 · 文档与开关都不完整

- ~~`architecture.md` 没有图谱章节，新人读架构根本不知道有这层~~（P0 已补）；
- `features.md` 没有面向用户的说明；
- ~~设置里只有一个开关「App 经验提示」，没有「学习开关」「探索入口」「数据用量」~~
  （「使用页面经验」「记录页面经验」两个开关已放在 App 经验页顶部；「探索」「用量」仍缺）；
- ~~提示文案是英文硬编码~~（P0 已中文化，仍未走多语言资源）。

> 已修（2026-11-19）：`atlasHintsEnabled` 与 `atlasLearnEnabled` 分开，`effectiveAtlasHints` 不再连带
> `speedFlags().replay`；`AgentLoop` 新增 `atlasLearn`，`captureTargets = flags.replay || atlasLearn`。
> 关提示只停提示与 L2/L3 工具，仍照常记录；关记录只停采集与离线学习，已学内容照常可用。
> 评测里两项都默认关（`EvalHooks.atlasHints/atlasLearn`），跑 warm 模式或 cold 模式时才显式打开。

> 补法：用户文档加「App 经验是什么、会存什么、怎么关、怎么清」；提示文案走多语言资源。

---

## 4. 分期

### P0 · 止血（已完成，2026-11-19）

| # | 改动 | 文件 |
| --- | --- | --- |
| 1 | 边计数分档，`weakOk` 放开到 `TaskState.Succeeded` | `AtlasModels.kt`、`AtlasLearner.kt`、`AtlasHintPolicy.kt` |
| 2 | 虚拟入口节点 + `launch` 边，L3 支持从任意位置进 App | `AtlasModels.kt`、`AtlasLearner.kt`、`AtlasNavigator.kt` |
| 3 | 页面重命名 / 删除 | `AtlasRuntime.kt`、`AppExperienceViewModel.kt`、`AppExperienceScreen.kt` |
| 4 | 提示文案中文化 + 已有效边时也附 1 条关键控件 | `AtlasHintPolicy.kt` |
| 5 | 架构文档补一节，roadmap 记现状 | `docs/dev/architecture.md`、`docs/dev/roadmap.md` |

验收：跑 3 个真实任务后，`App 经验` 里任一 App 至少有 1 条边、能改名、`phone_navigate_to` 能从桌面进到目标页。

### P1 · 可用（3–5 天）

| # | 改动 |
| --- | --- |
| 6 | 详情页拓扑缩略图（Compose Canvas，分层布局） |
| 7 | 页面列表：搜索 / 筛选（未验证、无出边、无控件）/ 行内显示「出边 n · 控件 m · 最近」 |
| 8 | 页面级删除、合并；`unverified` 批量清理 |
| 9 | 冷启动：历史 run 回放 + 「探索此 App」 |
| 10 | 页面类型（dialog / permission / webview）识别，垃圾节点 7 天自清 |
| 11 | 边类型（navigate / in-page / back / tab / dialog），自指边可读化 |
| 12 | 设置页再加「探索」「用量」入口（「使用 / 记录」两个开关已在 App 经验页顶部） |

### P2 · 闭环（1–2 周）

| # | 改动 |
| --- | --- |
| 13 | 埋点：提示展示 / 采纳 / 省步；设置里显示"本月命中 n 次" |
| 14 | eval 对照实验（hints on/off），产出第一组自有数据 |
| 15 | L2 候选可用化（1 次试用、2 次推荐） |
| 16 | 跨任务共享前缀（补 G1/G2 之外的"组合"能力） |
| 17 | 全量导出 / 备份 / 恢复 |

---

## 5. P0 落地记录（2026-11-19）

五项全部完成，`core` 与 `app` 的图谱测试全绿（新增 `AtlasP0Test` 9 个用例）。

| # | 改动 | 落点 |
| --- | --- | --- |
| 1 | 边计数分档：`ok`（Done）与 `weakOk`（整体成功），新增 `strength()` / `isReliable()` | `AtlasModels.kt`、`AtlasLearner.kt`、`AtlasHintPolicy.kt`、`AtlasNavigator.kt` |
| 2 | 入口边：`__entry__ --phone_launch_app--> 首页`，学习跨包进入；寻路可从 App 外起跳；淘汰与分享导入都放行入口边 | `AtlasModels.kt`、`AtlasLearner.kt`、`AtlasNavigator.kt`、`PhoneAtlasTools.kt`、`AtlasShare.kt` |
| 3 | 页面重命名 / 删除；用户标题不被模型覆盖；删页面连带清边与快捷 | `AtlasModels.kt`、`AtlasRuntime.kt`、`AppExperienceViewModel.kt`、`AppExperienceScreen.kt` |
| 4 | 提示中文化；有边时补一条关键控件；自指边写成「当前页面（页内跳转）」；统计改用「成功/失败」 | `AtlasHintPolicy.kt` |
| 5 | 架构文档新增 App 页面地图一节；路线图记入已完成；工具描述补充「可从入口启动」 | `architecture.md`、`roadmap.md`、`prompts/tools/phone_navigate_to.md` |

界面新增：页面展开后的「重命名 / 删除」，详情页 meta 显示「从外部进入 n 条」，边统计显示弱成功次数。

随后又拆开 G10 的两件事：App 经验页顶部新增「使用页面经验」「记录页面经验」两个开关，
分别控制提示/L2/L3 工具与采集/离线学习，互不影响，也不再依赖「照着上次的做法做」。

下一步仍是 P1 的六项（拓扑缩略图、筛选、合并、冷启动、页面类型、边类型）与 P2 的度量闭环。

---

## 6. 一条判断

图谱这条路的方向是对的（MobileGPT / AutoDroid / AppAgentX 都验证过），
但现在的实现是**把论文里的三层结构一次性铺完，却没有一行产品反馈回路**。

真正缺的不是更多模型调用，而是三件很朴素的事：

1. **让边能长出来**（G1、G2、G6）—— 否则图永远是空的；
2. **让用户能纠正**（G4、G8、G10）—— 否则错一次就废；
3. **让效果能被看见**（G3、G9）—— 否则不知道该继续投还是砍。

这三件做完，「毛坯房」才有资格谈装修。

---

## 7. 真机实测记录（2026-11-19 下午）

以 BOSS 直聘跑通「学 → 看 → 用」，过程中又修掉 6 个只有真机才暴露的缺陷
（启动步骤目标为空、冷启动入口观测被挡、只认 Done 不记弱边、评测不等学习收工、
命名 12 秒超时、同 Activity 不同状态撞名）。修完后一次冷启动任务能学到 5 个页面、8 条边，
页面有可辨识的中文名，入口边与返回边齐全。

详见 `docs/dev/app-atlas-eval-report.md`（含原始数据与对照表）。

一句结论：**学习链路是通的，提示的收益还没量出来**——现在这条题只有 5 跳，
模型靠无障碍快照就能看清能点什么，提示不构成新增信息量。下一步要换一条 7 跳以上的题。
