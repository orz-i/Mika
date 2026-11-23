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
