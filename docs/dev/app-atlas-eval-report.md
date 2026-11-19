# App 图谱真机实测：BOSS 直聘

> **结论先说**：图谱现在能学到了——冷启动 5 个页面、8 条跳转边，页面有中文名字，入口边也在；提示也确实注入到了模型眼前（每轮 5 次，原文见第 5 节）。
> 但「学到之后有没有让任务变快」这件事，**在这套题上量不出来**：开/关两组各 2 轮、共 4 轮，步数均值 24.5 对 25.0，两组的分布完全重合。
> 真正被这次实测修掉的，是图谱**根本写不进盘**的那几个缺陷（第 3 节），不是性能。

- 机型：真机（3B15AN00MNB00000），Android
- App：BOSS 直聘 `com.hpbr.bosszhipin`
- 渠道：只用 App，题目里 `"channel": "app"` 并在 goal 里写明「不要用网页版」
- 模型：`gpt-6.1-terra`
- 判分：`answer_grounded`（回答里的引文、带单位数字、日期要能在运行中看到的屏上找到）
- 全部数据在 `output/atlas-device/`，图谱快照在 `output/atlas-device/atlas-probe-*.txt`

---

## 1. 怎么测的

评测走真实路径：`am instrument` → `EvalRunTest` → `TaskService` → `TaskRun`，和用户点一下跑任务是同一条链路，不是模拟。

三个开关分开传：

| 参数 | 作用 |
| --- | --- |
| `-e evalAtlasHints true/false` | 要不要在每一步注入「页面经验」提示 |
| `-e evalAtlasLearn true/false` | 要不要在任务结束后把这一轮学进图谱 |
| `-e evalAtlasMode cold-keep` | 先清空图谱，跑一轮学出来，跑完等学习收工 |

看图谱用只读探针 `AtlasProbeTest`，导出到 `files/eval/atlas-probe-<包名>.txt`。

```bash
# 学一轮（清图谱 → 跑 → 等学习与命名完成）
adb shell am instrument -w -e class com.wangzan101.mika.eval.EvalRunTest \
  -e evalRun boss10-cold -e evalSet boss-two-jobs \
  -e evalAtlasMode cold-keep -e evalAtlasHints false \
  com.wangzan101.mika.test/androidx.test.runner.AndroidJUnitRunner

# 看图谱
adb shell am instrument -w -e class com.wangzan101.mika.eval.AtlasProbeTest \
  -e atlasPkg com.hpbr.bosszhipin \
  com.wangzan101.mika.test/androidx.test.runner.AndroidJUnitRunner
```

题目 `eval/tasks/read/content/boss-two-jobs.json`：搜索「Android 开发」→ 打开前两个职位 → 报名称/公司/薪资 → 回到结果页。这条题有 5 跳，够长。

---

## 2. 图谱：从 2 个页面到 6 个页面、11 条边

**修之前**（`boss4` 阶段，跑了 4 轮 27 步的任务之后）：

```
screens=2 edges=2 shortcuts=0
- GeekSearchActivity#040d... | GeekSearchActivity | hits=2
- BossJobPagerActivity#2658... | BossJobPagerActivity | hits=1
- GeekSearchActivity -> BossJobPager | phone_tap | ok=1
- __entry__ -> GeekSearchActivity | phone_launch_app | ok=1
```

一个搜索任务有「首页 → 搜索输入 → 搜索建议 → 结果列表 → 职位详情 → 返回」，图谱里只剩两头，中间全丢。

**修之后**（`boss16`，六轮 BOSS 任务之后）：

```
screens=6 edges=11 shortcuts=5
- 职位搜索结果页        GeekSearchActivity#040d...   hits=33 controls=2
- 职位详情页            BossJobPagerActivity#2e58... hits=28 controls=1
- 职位搜索页            GeekSearchActivity#cd42...   hits=5  controls=1
- 职位搜索输入页        GeekSearchActivity#9c42...   hits=5  controls=3
- 职位推荐首页          MainActivity#3838...         hits=10 controls=1
- 职位详情页 #2648df    BossJobPagerActivity#2648... hits=2  controls=1

__entry__ → 职位推荐首页              phone_launch_app          ok=5
职位推荐首页 → 职位搜索输入页          phone_tap                 ok=5
职位搜索输入页 → 职位搜索页            phone_type_text et_search ok=5
职位搜索输入页 → 职位搜索输入页        phone_clear_text et_search ok=2
职位搜索页 → 职位搜索结果页            phone_tap '搜索'           ok=5
职位搜索结果页 → 职位详情页            phone_tap                 ok=12 / ok=16
职位详情页 → 职位搜索结果页            phone_back                ok=15
职位详情页 → 职位搜索结果页            phone_tap iv_back         ok=11
职位详情页 → 职位详情页 #2648df        phone_scroll              ok=1
```

这条链条从「冷启动」到「打开某个职位详情」是完整的，返回边有两条（`back` 键和页面上的返回箭头各一条），模型拿到的是一张能走的图，不是两个孤岛。

`shortcuts=5`，都是「在结果页点开一条职位再返回」这类循环，例如 `职位搜索结果页: tap LinearLayout -> back`（4 步）。名字是机械拼的，语义还得靠人看——这也说明 L2 的命名比 L1 的页面命名更缺。

**一处已知冗余**：结果页上有多个职位条目，点每一条都是一条独立的 `phone_tap` 边（`ok=12` 和 `ok=16` 两条），因为目标元素的文字是职位名、被清洗后变成空，形状却一样。提示里已经按行去重，所以模型看到的是同一句话一次。

---

## 3. 一路修掉的 8 个缺陷

真机上的问题都不是读代码能读出来的，全是「跑一遍、看图谱、发现不对」。

### 3.1 `phone_launch_app` 的目标是空的

启动 App 这一步 `ActionTarget` 里 `incomplete=true`、before/after 都为空，学习器直接跳过——**入口边根本长不出来**。

根因：启动前 Mika 在自己的界面上，拿不到目标 App 的任何信息，事后才填。修法：`ActionExecutor.routeTarget()` 在动作落地前 `peek()` 预读一次前屏签名，落地后再补 after。

### 3.2 冷启动时拿不到「前一个包」，入口边被判定条件挡住

入口观测要求 `beforePkg != null`，冷启动时前屏是桌面、Mika 读不到，永远是 null。修法：`afterPkg != null && (launchedFromOutside || afterPkg != beforePkg)`，`phone_launch_app` 且没有前屏也算从外部进入。

### 3.3 学习只认「完全办成」，办成的任务也不留边

`LearnGate.done` 要求 `Succeeded && outcome==Done && 无未确认 && 不doubtful`。实测里绝大多数成功轮的 `outcome` 是 `Partial`（模型自己说「只做了查看」也算 Partial），于是一条边都不记。

修法：拆两级——任务整体 `Succeeded` 就记 `weakOk`（0.5 分边），`LearnGate.done` 才记 `ok`（1.0 分边）。`isReliable()` 按总强度判，弱边能用但排在强边后面。

### 3.4 学习根本没等到，进程就退了

评测跑完，`instrumentation` 结束，App 进程被杀。学习是「结果通知之后」的后台活，还没跑完就没了。

日志证据：

```
15:32:09  atlas learn start  runId=...1  end=Succeeded outcome=Done
15:32:21  atlas learn done   screens=5 edges=9     ← 12 秒整，正好是命名超时
```

修法：评测侧 `awaitAtlasSettled` 从「等 3 秒 + 空闲 15 秒」改成「等 30 秒开工 + 空闲 180 秒」。

### 3.5 页面命名 12 秒超时，永远退化成 Activity 名

图谱里三个页面全叫 GeekSearchActivity（BOSS 的搜索页 Activity），提示注入时模型看到的是三个同名页面，等于没提示。

根因：`AtlasNamer.DEFAULT_TIMEOUT_MS = 12_000`，模型一次调用就要 8–15 秒，经常整好卡在超时上。修法：30 秒（这是任务结束后的离线活，等得起）。修完命名立刻正常：

```
职位推荐首页 / 职位搜索输入页 / 职位搜索页 / 职位搜索结果页 / 职位详情页
```

### 3.6 同一个 Activity 的不同状态会撞名

即使模型命名，也可能把「搜索输入」「搜索建议」「搜索结果」都叫成「职位搜索页」——同一个 Activity（GeekSearchActivity）的三个状态，提示里分不出「从这里点进去是哪一个」。

修法：`AtlasNamer.disambiguateTitles`，同包内标题重复时，用「这一屏有、同组其他屏没有」的屏幕文字做后缀，`职位搜索页（历史搜索）`；没有独有文字就退回短哈希。带数字的文字不当后缀（`15-30K` 是动态内容）。重复跑幂等，不会越接越长。

### 3.7 用户输入的搜索词被当成「页面经验」存下来

修好命名后打开提示原文，看到这样一行：

```
页面经验（历史记录，以当前屏幕为准）： 职位搜索输入页
- type_text '安卓开发' [输入搜索关键词] → 职位搜索页 (成功 1)
```

`安卓开发` 是这一轮模型打进去的搜索词，被当成这个输入框的「名字」记进了图谱。两个坏处：一是违反「不存输入文字」的隐私承诺（搜索词、账号都可能走到这条路上），二是会误导下一轮——提示看起来像在说「这个框里只能打这几个字」。

修法：`AtlasSanitizer.cleanSelector` 对输入类动作（`*_type_text`、`*_clear_text`）直接清空元素文字，只留 `resourceId`；同时 `AtlasLearner` 在合并时会顺手把已经存下来的旧边也洗一遍（只清文字，计数保留），并把同一页的输入边并成一条——输入框位置差几个像素、输入的字每次不同，都不该让同一条经验裂成好几条。修完提示里那一行变成 `type_text [et_search] → 职位搜索页`。

### 3.8 列表页的同一个出口，提示里出两行一样的

结果页上有 20 条职位，点每一条都是一条独立的 `phone_tap` 边（成功次数不同）。提示只给 2 行，两行都是「tap LinearLayout → 职位详情页」，只有括号里的成功次数不一样——等于浪费了一半位置。

修法：`AtlasHintPolicy` 按「动作 + 目的地」去重，只留最强的那条（边已按强度降序排好，第一条就是它）。修完结果页那一屏只出一行「tap LinearLayout → 职位详情页 (成功 16)」。

---

## 4. 数据：提示到底有没有用

同一条题（`boss-two-jobs`），图谱固定（`learn=false`），每轮前 `force-stop` 重置 App 冷启动。

### 4.1 判分修好之后（结论看这一组）

| 轮次 | 提示 | 步数 | 耗时(秒) | 通过 |
| --- | --- | --- | --- | --- |
| boss12-on-1 | **开** | 12 | 138.5 | 是 |
| boss12-on-2 | **开** | 37 | 532.6 | 是 |
| boss18-final | **开** | 20 | 246.1 | 是 |
| boss12-off-1 | 关 | 13 | 173.5 | 是 |
| boss12-off-2 | 关 | 37 | 532.1 | 是 |

| 组 | 步数中位数 | 步数均值 | 耗时均值 | 通过 |
| --- | --- | --- | --- | --- |
| 提示开 | 20 | 23.0 | 305.7 s | 3/3 |
| 提示关 | 25 | 25.0 | 352.8 s | 2/2 |

**开和关一样。** 而且两组的分布完全重合：都有一个 12–13 步的快轮，都有一个 37 步 / 532 秒的长轮——长轮跟提示无关，是模型陷进「反复读屏、不落地」的行为模式（`boss12-on-2` 的 36 次工具结果里一次 `action_target` 都没有，全是读屏）。

### 4.2 判分修好之前（说明噪声有多大）

| 轮次 | 提示 | 步数 | 耗时(秒) | 通过 | 备注 |
| --- | --- | --- | --- | --- | --- |
| boss5 | 关 | 28 | 417 | 是 | 图谱只有 2 页，App 状态未重置 |
| boss7 | 关 | 12 | 173 | 是 | 已经学会一次，App 停在已知页 |
| boss8-on-1 | 开 | 11 | 134.7 | 是 | 注入 5 次 |
| boss8-off-1 | 关 | 17 | 304.8 | 是 | |
| boss8-on-2 | 开 | 17 | 212.3 | 否 | 回答多说了细节，`answer_grounded` 误杀 |
| boss8-off-2 | 关 | 11 | 150.7 | 是 | |
| boss9-cold | 关 | 17 | 204.6 | 是 | 冷图谱冷启动 |
| boss10-cold | 关 | 12 | 182.8 | 是 | 冷图谱，命名修好后 |
| boss11-on-1 | 开 | 12 | 145.8 | 否 | 回答完全正确，被判 `answer_grounded` 不过 |
| boss11-on-2 | 开 | 37 | 478 | 是 | |
| boss11-on-3 | 开 | 23 | 288.7 | 否 | 同上，回答正确但判分挂 |
| boss11-off-1 | 关 | 12 | 143.2 | 是 | |
| boss11-off-2 | 关 | 4 | 63.6 | 否 | 模型 upstream 协议错误：`malformed tool-call arguments` |
| boss11-off-3 | 关 | 38 | 409.9 | 是 | |

这一组里 3 次「不通过」没有一次是模型做错了：

- 2 次是 `answer_grounded` 误杀。回答写「12-15K」「15-30K」这种薪资，能被计数的事实只有两个薪资里的四个数（职位名、公司名不带单位数字，不参与计数），题目原来要求 `minItems: 4`，措辞一变就掉到 2–3 条，直接判假成功。**改成 `minItems: 2` 后 4/4 全过。**
- 1 次是模型服务端的 tool-call 流解析失败，跟图谱无关。

> 一句话：**在这条题上量不出提示的收益。** 这条题只有 5 跳，模型靠无障碍快照就能看清当前页面能点什么，提示给的信息不构成新增信息量；而单轮的步数方差（12 ↔ 37）比任何可能的收益都大。

---

## 5. 现在能确认的 / 还不能确认的

**能确认**

- 学习链路修通了：跑一轮 → 图谱从 2 页长到 5 页，含入口边、返回边、跨页跳转。
- 页面命名可用：模型命名 + 撞名消歧，提示里的页面名能分辨。
- 弱边机制有效：`outcome=Partial` 的成功轮也能留下边，图不会因为判分严格而永远空着。
- 两个开关互不牵连：`learn=false` 时日志明确 `atlas learn skipped: switch off`，跑完图谱不变。
- 快捷流程（L2）能挖出来：连续跑够轮次后图谱里长出 3 条（`shortcuts=3`），例如「职位搜索结果页: tap LinearLayout -> back」，`steps` 2–4。
- 提示真的被注入了，而且内容是对的。打开提示时每轮注入 5 次，最后一轮「冷启动 → 搜索结果」全程读到的是这样（`atlas_hint_text` 事件，原文）：

  ```
  页面经验（历史记录，以当前屏幕为准）： 职位推荐首页
  - tap RelativeLayout → 职位搜索输入页 (成功 5)

  页面经验（历史记录，以当前屏幕为准）： 职位搜索输入页
  - type_text [et_search] → 职位搜索页 (成功 5)
  - clear_text [et_search] → 当前页面（页内跳转） (成功 2)

  页面经验（历史记录，以当前屏幕为准）： 职位搜索页
  - tap '搜索' [执行搜索] → 职位搜索结果页 (成功 5)

  页面经验（历史记录，以当前屏幕为准）： 职位搜索结果页
  - tap LinearLayout → 职位详情页 (成功 16)

  页面经验（历史记录，以当前屏幕为准）： 职位详情页
  - back → 职位搜索结果页 (成功 15)
  - tap [iv_back] → 职位搜索结果页 (成功 11)
  ```

  这四步是该走的路，而且第 5 屏给了两条返回路径（物理返回键、页面上的返回箭头）。

**不能确认**

- 提示能不能省步。需要一个「探索成本高」的题（比如要先切城市、再设筛选、再排序才能到目标页），现在这条太短，且单轮方差 12↔37 步把任何收益都盖住。
- 快捷流程能不能真的省事。挖出来的 3 条 `successes=0`——还没有一次「模型主动用了它并且走通」的观测。名字是机械拼接的（实际语义是「点开一个职位再返回」），人看不出来它干嘛，也还没有改名入口。
- 三跳以上的寻路（L3）有没有被真正调用。`planFromEntry` 有了入口边，但还没有一次「模型主动要路径」的观测。

---

## 6. 下一步

1. **换一条真·长路径的题**：搜索 → 城市切到成都 → 经验筛选 3-5 年 → 排序按最新 → 打开第 3 条 → 返回。7 跳以上，提示才有话可说。
2. **每组至少 8 轮再做结论**：现在 2 轮一组的均值会被一个长轮完全带偏；只看中位数，或者用配对同题重复测。
3. **让快捷流程有得挖**：同一 App 连续跑 2 轮不清状态，第 2 轮应该能命中 L2。
4. 补「探索」「用量」两个设置入口，加上提示命中/采纳埋点（审计文档 P1 剩余项）。

---

## 7. 复现用的脚本

| 脚本 | 作用 |
| --- | --- |
| `tools/atlas-ab.sh` | 跑提示开/关对照，每轮前 `force-stop` 重置 App；`bash tools/atlas-ab.sh 3` 表示每组 3 轮 |
| `tools/atlas-ab-summary.py` | 汇总 `output/atlas-device/` 下所有轮次的步数/耗时/通过，按开关注分组打均值 |
