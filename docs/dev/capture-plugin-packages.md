# 抓包插件：所有 App 规则都是数据

这份文档讲一件事：**接入或修复一个 App 的抓包，什么时候需要重编 aau，什么时候不需要。**

结论先给：**不需要了。** 内置的 BOSS / 抖音 / 小红书 / cronet 兜底本身就是插件包，
和用户自己放的插件包走同一份 `plugin.json`、同一个加载器。只有「自定义加密 / 有状态解码」
这类本质是代码的东西才需要插件包里的 dex。

---

## 一、三种来源，一条加载路径

| 来源 | 位置 | id 前缀 | 谁维护 |
|---|---|---|---|
| 内置插件包 | `assets/aau_capture_plugins/` → 安装到 `filesDir/capture_plugins_builtin/` | `builtin.` | 随 APK 发布，按 `version` 升级 |
| 外部插件包 | `filesDir/capture_plugins/<名字>/plugin.json` | `external.` | 用户 / 现场 |
| 覆盖配置 | `filesDir/aau_capture_plugins.json` | — | 设置页保存，优先级最高 |

三者最后都进 `NetworkCapturePluginRegistry`，解析（`resolveEbpfProfile` / `resolveMiddlewares`）
不区分来源。**注册表启动时是空的**——没有「编译进 aau 的那一份」，所以不会出现
「配置改了但代码里那份还在赢」的情况。

内置包安装到磁盘而不是直接从 assets 读，是为了：用户能看见它、能对比、能覆盖；
加载器只需要认识目录，不必再写一套 `AssetManager` 分支。

### 内置包的升级规则

`plugin.json` 里的 `version`（整数）决定覆盖：

- 磁盘上没有 → 安装
- 磁盘上的 `version` < APK 里的 `version` → 覆盖
- 否则跳过

等于也跳过，是为了让「我手动调过的内置包」在一次无关的 APK 升级后还留着。
**要改规则请走覆盖配置**（设置页保存），别直接改内置包目录——那份目录归版本管理。

---

## 二、L1：探测规则

设置 › 高级与其他 › 端侧网络抓包 › eBPF 探测规则里可以直接改：

- 库名子串、SSL / Cronet 偏移、Cronet ABI；
- 进程内解码入口（每行 `lib | 方法名, 方法名`），对应 JSON 的 `jniHookRules`。

保存后写入 `filesDir/aau_capture_plugins.json`，下次抓包生效。规则里界面上没展示的字段
（`magic` / `maxBytes` / `captureReturn`）会从原规则继承——否则改个方法名就会把容器魔数弄丢。

**「替换内置」只在真的给了偏移时成立。** 只补进程内入口时不会动内置 SSL 规则，
否则为了拿明文反而把抓包本身拆了。

**覆盖是合并，不是替换。** 覆盖文件里写什么就只改什么，没写的继续用插件包那份：
老设备上存过的「只补了一个 JNI 方法名」的配置，升级到插件包版本后仍然有效，内置包的
SSL 偏移、BZPBlock 管道、加密诊断都还在。反过来说，编辑框里显示的是**覆盖那一版**——
因此覆盖过的条目会多一行提示，免得看到偏移全空就以为配置丢了。

设置页的来源徽标按优先级显示：`偏移已停用` → `已修改` → `外部插件` → `内置`。
判断来源只看插件包声明的包名，不看兜底包（`packages: ["*"]`），否则放一个兜底外部包
会把列表里每一项都说成"外部"。

---

## 三、L2：声明式解码管道

### 3.1 形态

每个 `plugin.json`（或覆盖配置的每个包对象）里可以写：

```json
{
  "id": "bosszhipin",
  "apiVersion": 1,
  "version": 1,
  "packages": ["com.hpbr.bosszhipin", "com.hpbr.bosszhipin.lite"],
  "hostFix": {
    "headerPriority": ["x-bz-host", ":authority", "host"],
    "defaultHost": "www.zhipin.com"
  },
  "responseCodecs": [
    {
      "id": "bzp-block",
      "when": [{ "magic": "BZPBlock" }],
      "steps": [
        { "op": "container", "magic": "BZPBlock", "headerSize": 24,
          "payloadLen": { "at": 12, "width": 4, "endian": "le" },
          "rawLen":     { "at": 16, "width": 4, "endian": "le" },
          "label": "bzp" },
        { "op": "decompress", "algo": "lz4-block", "label": "lz4" }
      ]
    }
  ],
  "diagnose": [
    { "when": [{ "header": "zp-encrypting", "equals": "1" }], "label": "aes" }
  ],
  "ebpfProfile": {
    "sslOffsetRules": [ /* … */ ],
    "jniHookRules": [ /* … */ ]
  }
}
```

这就是 `assets/aau_capture_plugins/bosszhipin/plugin.json` 的骨架。BOSS 的整套行为
（容器 + LZ4、gzip/zstd/br/deflate 兜底、`unknown-host` 补全、加密诊断）全部在这份文件里，
`aau-common` 里没有一行 BOSS 专用 Kotlin。

### 3.2 原子操作

只有四个，够用且稳定：

| `op` | 参数 | 说明 |
|---|---|---|
| `container` | `magic` / `magicHex`、`magicOffset`、`headerSize`、`payloadLen`、`rawLen` | 校验魔数，按头部声明裁出载荷。`payloadLen` 越界时按实际长度处理；`rawLen` 交给下一步（LZ4 block 需要预知输出尺寸） |
| `decompress` | `algo` = `gzip` / `zlib` / `lz4-block` / `zstd` / `br` | 标准解压。解不开返回失败，不会把半截乱码当明文 |
| `base64` | 无 | 标准或 URL-safe base64，自动忽略空白 |
| `stripprefix` | `prefixBytes` | 去掉固定前缀（有的 App 在 JSON 前拼随机盐） |

可选字段：`label`（trace 里显示的名字）、`contentType`（解出来后覆盖 mimeType）、
`stopWhenJson: false`（默认在输入已是明文 JSON 时跳过整条规则；设成 false 表示无论如何都跑一遍）。

### 3.3 条件

`when` 是「全部成立才应用」的数组，元素支持：

- `header` + `equals` / `contains` / `absent`
- `magic`（响应体前缀，UTF-8 文本）
- `magicHex`（二进制魔数，如 `1f8b08` = gzip、`28b52ffd` = zstd）

不写 `when` 表示无条件匹配。多条规则之间的语义是「谁会解谁上」：

1. 依次尝试命中的规则；
2. 某条规则成功且**解出的是明文 JSON** → 立刻返回；
3. 都解不出 JSON → 返回第一条成功的（解压出来但不是 JSON 也算结果）。

这样一份配置里可以同时写「容器 + lz4 + gzip」和「容器 + lz4」，不必让调用方枚举组合。
全都没成功则保留原始字节——宁可让模型看到密文，也不能贴错明文。

### 3.4 `hostFix`

抓包链路在没有 SNI 或连接复用时拿不到域名，URL 会退化成 `://unknown-host`。
`hostFix` 按 `headerPriority` 依次取第一个非空头，兜底用 `defaultHost`，把域名补回去。

- `defaultHostPathContains`：兜底域名只在 URL 含该片段时使用。小红书只在 `/api/sns/`、
  抖音只在 `/aweme/v` 下省略 Host，对这些之外硬补一个域名会编造出不存在的主机。
- `onRequest: false`：只在响应阶段补。请求阶段补会抢在证据之前——解析器本来会从响应的
  `Set-Cookie: Domain=` 学真实域名，再回填下一次请求；提前补等于把这条路堵死。

### 3.5 `diagnose`

解不开时不能只是「什么都没发生」。`diagnose` 命中第一条就把
`x-aau-decode-hint: undecoded:<label>[:k=v,...]:<size>` 写进响应头：

| 场景 | hint |
|---|---|
| 解开了 | `bzp>lz4:643->512` |
| 需要 App 的密钥 | `undecoded:aes:42` |
| 未知容器，但 App 自报了加工方式 | `undecoded:raw:zp-compressing=3:64` |
| zstd 带字典 | `undecoded:zstd-dict:10` |

有 `undecoded:` 就是「抓到了，但解不开」，不是「没抓到」。

---

## 四、L3：插件包

### 4.1 目录

```
filesDir/capture_plugins/            # 用户的包
filesDir/capture_plugins_builtin/    # 内置包，由 assets 安装
  acme-market/
    plugin.json      # 必需
    plugin.dex       # 可选，仅当需要自定义逻辑
```

启动抓包时扫描一次（设置页打开时也会扫），单个包失败只记日志，不影响其它包。

### 4.2 `plugin.json`

```json
{
  "id": "acme.market",
  "apiVersion": 1,
  "version": 1,
  "enabled": true,
  "packages": ["com.acme.market"],
  "dex": "plugin.dex",
  "sha256": "……",
  "middlewareClass": "com.acme.capture.AcmeMiddleware",
  "hostFix": { "headerPriority": ["x-acme-host"], "defaultHost": "api.acme.com" },
  "responseCodecs": [ /* 同 L2 */ ],
  "diagnose": [ /* 同 L2 */ ],
  "ebpfProfile": {
    "jniHookRules": [
      { "libSubstring": "libacme.so", "methodNames": ["nativeDecode"], "magic": "ACME" }
    ]
  }
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`，注册为 `external.<id>` 或 `builtin.<id>` |
| `apiVersion` | 否（默认 1） | 必须等于宿主支持的版本，否则直接拒绝 |
| `version` | 否（默认 1） | 内置包的覆盖判据，只有内置包用得上 |
| `enabled` | 否（默认 true） | 设 false 等于停用这个包 |
| `fallback` | 否（默认 false） | 兜底包：匹配所有包，用户要求「替换内置偏移」时不会被拆掉 |
| `packages` | 是 | 匹配的包名；`*` 表示匹配所有包（兜底包的唯一表达方式） |
| `dex` | 否 | 只能是包目录内的文件名，不允许 `/`、`..` |
| `sha256` | 否 | dex 的 SHA-256，写了就校验 |
| `entryClass` | 否 | 实现 `NetworkCapturePlugin` 的类 |
| `middlewareClass` | 否 | 实现 `NetworkTrafficMiddleware` 的类 |
| `hostFix` / `responseCodecs` / `diagnose` | 否 | 同 L2 |
| `ebpfProfile` | 否 | 偏移 / ABI / JNI 入口，字段同 `aau_capture_plugins.json` |

**纯配置包（没有 dex）就足够覆盖绝大多数新 App。** 只有自定义解密才需要写代码。

### 4.3 边界与安全

插件代码与宿主同进程，JVM 里没有真正的沙箱。这里能做的是「把口子收窄」而不是「事后审计」：

- 只扫描应用私有目录，不认外部存储；
- 校验 `apiVersion`，接口变了旧包会被明确拒绝而不是神秘崩溃；
- 可选 `sha256` 校验 dex 完整性；
- 拒绝路径穿越（`dex` 只能落在包目录内）；
- 接口本身只有两个方法（`onRequest` / `onResponse`），插件拿不到 UI、文件系统、
  网络之外的宿主能力；
- 需要「完全不执行外部代码」时，调用方可以 `allowCode = false`，只加载纯配置包。

---

## 五、兼容性

- `responseCodecs` 的字段名与 `op` 取值是**对外契约**。加字段可以，改语义要慎重；
  宁可多一个 op，也不要让已有配置悄悄变味。
- 原子操作刻意保持少而稳。想解 AES？那是插件包的活，不要往 DSL 里堆。
- `apiVersion` 只在「插件与宿主的接口」变化时 +1，不是每次加字段都 +1。
- 内置包的 `version` 在自己那份规则变了（App 升级、偏移重算）时 +1。

---

## 六、排查顺序

1. **包有没有加载**：`adb logcat | grep CapturePluginStore`，看 `Rejected capture plugin …`。
   内置包被拒说明 `plugin.json` 写坏了或 `apiVersion` 对不上。
2. **有没有字节**：看响应长度是不是 0。没有字节是偏移 / JNI 入口的问题（L1）。
3. **是不是明文**：`Content-Type` 与首字节。`{` / `[` 开头就是明文，别再解。
4. **`x-aau-decode-hint`**：`undecoded:*` = 抓到了但解不开，看 label 判断原因；
   有 `trace` = 解过，看走了哪几层。
5. **`when` 是否命中**：把条件临时删掉试试，能解就是条件写错了（常见：大小写、
   头的名字带了 `:`、二进制魔数该用 `magicHex`）。
6. **容器头部字段位置**：`payloadLen` / `rawLen` 的 `at` 是相对响应体起点，不是相对头部。

---

## 七、真机验证记录（2026-11-24）

环境：`3B15AN00MNB00000`，KSU root，Mika 0.1.39 debug（`app-debug.apk`，versionCode 见 `app/build.gradle.kts`）。
样本：BOSS 直聘（`com.hpbr.bosszhipin`），场景为「成都 全栈开发 / AI Agent 开发」岗位卡片列表。
**结论：抓包链路通过。**

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| 内置包已安装 | 4/4 | `files/capture_plugins_builtin/` 下 `bosszhipin`(2490B) `douyin`(1286B) `generic-cronet`(366B) `xiaohongshu`(1001B) 的 `plugin.json` 齐全 |
| 用户覆盖仍在（升级安装后） | 3 条 | `Loaded 3 JSON capture plugin override(s) from …/aau_capture_plugins.json`；文件 mtime 仍是 11-21，说明没被安装覆盖 |
| 进程内通道判断 | 通过 | `prepareCapture(com.hpbr.bosszhipin): enabled=true root=true bridge=true` → 随后 `capturing=true`。这一行是「先加载插件源、再判断要不要 bridge」修复的直接证据——修复前这里恒为 `false`，预热走暖启、错过 native 方法注册的时机 |
| SSL 点位：规则命中 | 通过 | `ssl-offsets …/libzp-sdk-transport.so source=rule write=0x1e1ec8 read=0x1e1a6c`，紧接着 `native attached` |
| SSL 点位：启发式兜底 | 通过 | `libzp-sdk-im.so source=openssl_heuristic write=0x1e1f3c`、`libzp-sdk-rtc.so source=openssl_heuristic`，均 `native attached` |
| ART JNI 探针 | 通过 | `jni_register_probe` / `jni_region_probe` / `jni_array_len_probe` / `jni_array_get_probe`，偏移全部落在 `/apex/com.android.art/lib64/libart.so` |
| 明文 | 通过 | `decoded jni body method=2 bytes=35402`；从磁盘响应体缓存取出该 `/api/zpgeek/app/geek/search/cardlist` 响应，解析出 15 条岗位卡片（含薪资、公司、区域、HR 在线状态）。另一轮 68 步里累计 `decoded jni` 20 次 |

覆盖文件的兼容性单测同时通过：
`:aau-common:testDebugUnitTest --tests '*BuiltinPluginPackagesTest*'` 8/8、
`:app:testDebugUnitTest --tests '*CapturePluginProfileStoreTest*'` 10/10，
含 `replacingOffsetsKeepsTheDecoderPipeline`（只换偏移不动解码管道）、
`replacingOffsetsKeepsTheBuiltinJniSignatures`（只换偏移不动内置签名）、
`oldOverrideStillMergesWithTheBuiltinPackage`（旧格式 override 与内置包合并）、
`wildcardExternalPackageDoesNotRelabelEveryBuiltinEntry`。

### 7.1 三条用户 override 实测（抖音 / 小红书 / 起点）

上表是 BOSS 的整链路；这一节单独跑设备上那三条 override，绕开 Agent 直接用 Mika 自带的
`libaau_ebpf.so` 抓指定 uid，参数与 Mika 运行时一致（`--ssl-offsets` / `--cronet-offsets` /
`--cronet-abi` / `--cronet-slots` / `--cronet-sig`），stdout 按 `wire_tls_hdr`
（magic `AAUE`，60 字节头 + 原始明文）解析。这样出来的结论不再受 Agent 跑偏影响。

| App | 版本 | 目标库 | 解析来源 | 抓到什么 |
| --- | --- | --- | --- | --- |
| 抖音 `com.ss.android.ugc.aweme` | 41.1.0 | `libttboringssl.so` | `dynsym` | 10,859 帧 / 31.7 MB：`HTTP/1.1 200` 响应头、`ftypheic` 图片、`ftypisom`+`moov/mvhd` 视频 |
| 小红书 `com.xingin.xhs` | 9.54.0 | `libcronet.114.0.5735.38.so` | **`rule`** | 1,090 帧 / 3.7 MB：cronet 路径（dir=4/6）90–100% 为可读 JSON，含真实 feed（`{"code":0,...,"data":[{"id":"6ae16d…","name":"成都60天线下新版AIGC实操班来咯！"…`） |
| 起点 `com.qidian.QDReader` | 7.9.486 | `libssl.so` | `dynsym` | 104 帧 / 420 KB：完整 `POST /ywslogcpt/… HTTP/1.1` 请求明文、`HTTP/1.1 200 … application/json` 响应 |

三条的点位证据：

```
# 抖音
INFO:ssl-offsets …/libttboringssl.so source=dynsym write=0x44bb0 read=0x4494c sni=0x45ec4
ATTACHED:…/libttboringssl.so
# 小红书（唯一真正走规则分支的）
INFO:ssl-offsets …/libcronet.114.0.5735.38.so source=rule write=0x4ea720 read=0x4ea438 sni=0x4eb0c8
ATTACHED:…/libcronet.114.0.5735.38.so
ATTACHED_CRONET:…/libcronet.114.0.5735.38.so
# 起点
INFO:ssl-offsets …/libssl.so source=dynsym write=0x3d364 read=0x3d2b8 sni=0x0
ATTACHED:…/libssl.so
```

**偏移语义**：引擎给 uprobe PMU 的是**文件偏移**（`attr.config2`）。抖音符号表里
`SSL_write=0x48bb0`，可执行段 `p_vaddr - p_offset = 0x4000`，所以文件偏移是 `0x44bb0` ——
与覆盖值一致；`resolve_elf_symbol_offset()` 里也做了这层换算（`(vaddr - p_vaddr) + p_offset`），
符号路径和规则路径因此是同一套坐标。起点 `libssl.so` 的可执行段 `p_offset == p_vaddr`，
两者相等，所以两种写法看起来一样。

**两条记在案的结论**：

1. **有符号表的库上 `sslOffsetRules` 走不到。** 解析顺序是 dynsym → rule → heuristic
   （见 `inspect_library_offsets`，注释说明了原因：规则按子串匹配，一条 `libssl.so:…`
   会同时命中 `/apex` 和 `/system` 下偏移完全不同的同名库）。抖音、起点都带 `.dynsym`，
   所以这两条 override 属于「值正确但未被使用」。要让覆盖真正生效，只能针对无符号表的库
   （如小红的 `libcronet`）。
2. **覆盖不能顺手把内置签名一起吃掉。** 抖音内置包给了两条 cronet JNI 签名，用户覆盖只写了
   一条；`replaceBuiltinOffsets` 跳过内置包时若只保留 `declarative`，签名会剩一条。
   已改为把被跳过插件的 `responseCodecs` / `diagnose` / `hostFix` /
   `cronetAbiRule.jniSignatures` 都并进来，只让 ABI **布局**听覆盖的。

Mika 自身链路回归（装新包后跑「打开抖音，往下刷三条视频」）：
`prepareCapture(com.ss.android.ugc.aweme): enabled=true root=true bridge=false`
（抖音没有 `jniHookRules`，不需要进程内通道）→ `Loaded 3 JSON capture plugin override(s)`
→ `READY:…/libttboringssl.so` → `TlsStreamParser: no wait … already plaintext`。


---

## 八、插件包自己声明，用户自己开关

前七节都在讲"一个包能干什么"。这一节讲两件更基本的事：**它自己说它是干什么的**，
以及**用户能不能在不改它的情况下把它关掉**。

### 8.1 `declaration`：包自述，给人看

`plugin.json` 多一个可选段：

```json
"declaration": {
  "name": "BOSS 直聘",
  "summary": "把 BOSS 的加密/压缩响应还原成明文，并按 x-bz-host 补全请求域名。",
  "author": "Mika",
  "notes": [
    "读 libzp-sdk-transport.so 的 SSL 写/读偏移",
    "观察 libyzwg.so 的 nativeDecodeContent 返回值（只读，不改目标进程）"
  ]
}
```

它**不参与任何判定**——写错了不影响抓包，`inspect` 也不会因为缺了它就拒绝。
设置页原样显示：`name` 是标题、`summary` 是副标题、`author` 一行、`notes` 逐条列出。

四个内置包都补了，版本从 1 升到 2（不升版本，设备上已安装的副本不会被覆盖，
声明就永远看不见）。

### 8.2 声明分两栏：自述 + 实测

`PackInfo` 同时带两样东西：

| 字段 | 来源 | 用途 |
|---|---|---|
| `declares` | `declaration.notes` | 包自己说它做什么 |
| `sslRules` / `cronetRules` / `jniRules` / `codecs` / `hostFix` / `hasCode` | 解析 `ebpfProfile` 与 `declarative` 得出 | 它实际会碰什么 |

界面上拼成一行「会碰：SSL 偏移 ×1 · 进程内入口 ×1 · 响应解码 ×6 · 域名补全 · dex 代码」。
**只信自述**，一个包可以写"只读响应体"却挂 JNI；**只看计数**，用户又不知道为什么它要挂。
两栏并排，撒谎的成本就落到明面上。

`PackInfo.channels` 是给程序用的稳定编码（`ssl:1`、`jni:2`、`codec:6`…），
文案在 app 侧按语言拼，aau 不认识语言。

### 8.3 开关不写进插件包

关一个包的开关存在 `filesDir/capture_plugin_state.txt`，一行一个 id：

```
# 被关掉的抓包插件包，每行一个 id（带 builtin./external. 前缀）。
builtin.xiaohongshu
external.acme.market
```

**为什么不改 `plugin.json` 的 `"enabled": false`**：包是随 APK 分发的货或者别人给的货，
改它等于改坏它——下次升级覆盖、下一个人拿到的是被改过的副本，而且用户看不出"我关过"。
id 带前缀是为了内置包和外部包同名时不互相影响。

配套的三处改动：

- `ExternalCapturePluginLoader.load(..., disabledIds)` 跳过被关的包：不注册、不告警。
  它们进 `Report.packs` 与 `Report.skipped`，**不再进 `rejected`**——"用户关掉的"不是错误。
  文件里 `"enabled": false` 同理，以前报 `disabled` 错误，现在也只是 skipped。
- `loadIntoRegistry()` 先摘掉上一次注册的所有 `builtin.` / `external.` 包再重装。
  不摘的话，关掉一个包之后它还挂在注册表里，等于没关。
- `inspect()` 是只读版：不实例化 dex、不碰 dalvik、不写注册表，设置页可以直接调。
  坏包用目录名占位（`external.<目录名>`）留在列表里，否则它会从界面上凭空消失。

### 8.4 设置页

设置 › 高级与其他 › 端侧网络抓包 › eBPF 探测规则，顶部多一组「插件包」：

- 每行：标题（没有 `declaration.name` 就用 id）、作用范围（兜底包写"全部应用"）、
  通道摘要、来源胶囊（内置 / 外部 / 已关 / 有错）、右侧开关。
- 点行打开声明弹窗：版本与接口号、`summary`、作者、作用范围、通道、`notes` 逐条，
  以及为什么没生效（`plugin.json` 自己关的 / 在这里关的 / 校验失败的原因）。
- 开关立刻生效：`setPackEnabled` 写完状态文件就重装注册表，不用等下一次抓包 attach。

### 8.5 从设置页导入

8.4 之前，装一个外部包只有一条路：把目录推进
`/data/data/com.wangzan101.mika/files/capture_plugins/`。手机上没有文件管理器能进私有目录，
所以那条路实际上只有 adb 能走。现在设置页里有「导入插件包」：

```
选文件 → 落暂存 → 解包 + 校验 → 声明确认框 → 搬进 capture_plugins/<id> → 重装注册表
```

**支持两种文件**：`.zip`（包可以再套一层目录，`acme/plugin.json` 也认）和单份 `plugin.json`
（纯配置包）。判断是不是 zip 看头四个字节 `PK`，不看扩展名——文件和它的名字经常对不上。

**为什么要两次确认**：解包和校验都在暂存区（`capture_plugins_staging/`）做，`capture_plugins/`
一个字节都不动。用户在确认框里看到的是这个包自己的声明和实测通道（和 8.2 的详情弹窗同一套），
带 dex 的包额外警告一行「会跑在 Mika 的进程里」。点「安装」才搬。取消就把暂存目录删掉。

**目录名用裸 id**（`external.acme.market` → `acme.market`），所以再导入同一个包是覆盖升级而不是
并排两份。覆盖不是原地写：先落到 `.install-<id>` 再改名，中途失败最多留一个 `.` 开头的残骸，
加载器不扫它，`loadIntoRegistry` 下次会清掉。

**校验的分寸**：只保证路径不越界（绝对路径、`..`、盘符）、大小有上限（单包 8 MiB、单片 4 MiB、
512 条）、接口版本与 sha256 对得上。**没有做代码签名**——插件代码与宿主同进程，这件事从
`allowCode` 那一侧管，不在这里假装解决。

**一个坑**：SAF 给的 `content://` uri 只在回调前后有效，而解包可能很慢，所以先整份复制到暂存区
再解。复制这一层换来的是：解包失败时文件已经在私有目录里，不需要再向系统要一次读权限。

**删除**：外部包的详情弹窗底部有「删除这个插件包」，确认后删目录并重装注册表。
内置包不给删——它在 assets 里，删了下次启动又回来；对它该做的是关掉开关。

失败原因按类别给（没有 `plugin.json` / 一个包里有多份 / 太大 / 路径越界 / 读不出来 / 校验不过），
最后一种额外把加载器的原话（`api_version_mismatch:99!=1`）照原样显示：用户要拿它去改包，
转述一遍只会丢信息。
