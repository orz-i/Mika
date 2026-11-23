# 抓包插件：从「改代码」到「放配置」

这份文档讲一件事：**接入或修复一个 App 的抓包，什么时候需要重编 aau，什么时候不需要。**

结论先给：新增 App、换响应容器、改解码入口，现在都不需要重编。只有「自定义加密 / 有状态解码」
这类本质是代码的东西才需要插件包里的 dex。

---

## 一、三层边界

| 层 | 解决的问题 | 载体 | 改它要重编吗 |
|---|---|---|---|
| L1 探测规则 | 偏移变了、JNI 方法改名 | `aau_capture_plugins.json` | 不要 |
| L2 解码管道 | 响应体是自有容器 / 压缩 / 编码 | 同一个 JSON 的 `responseCodecs` | 不要 |
| L3 插件包 | 自定义 AES、分段、有状态解码 | `capture_plugins/<名字>/plugin.json` + 可选 dex | 纯配置不要，带 dex 才要重新打包（不是重编 aau） |

底层（`aau-ebpf`、TLS 解析、`NetworkTrafficMiddleware` 接口）只负责「把字节读出来」和
「按契约过一遍中间件」。**读到之后怎么读**是配置的事。

---

## 二、L1：探测规则的数据化

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

`aau_capture_plugins.json` 每个包的对象里可以写：

```json
"com.hpbr.bosszhipin": {
  "hostFix": {
    "headerPriority": ["x-bz-host", ":authority", "host"],
    "defaultHost": "www.zhipin.com"
  },
  "responseCodecs": [
    {
      "id": "bzp-block",
      "when": [{ "header": "zp-compressing", "equals": "2" }],
      "steps": [
        { "op": "container", "magic": "BZPBlock", "headerSize": 24,
          "payloadLen": { "at": 12, "width": 4, "endian": "le" },
          "rawLen":     { "at": 16, "width": 4, "endian": "le" } },
        { "op": "decompress", "algo": "lz4-block" }
      ]
    }
  ]
}
```

同一段 JSON 也可以直接放进 L3 的 `plugin.json`，字段名完全一致。

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
- `magic`（响应体前缀）

不写 `when` 表示无条件匹配。多条规则按顺序尝试，**任何一步失败就换下一条**，
全都失败则保留原始字节——宁可让模型看到密文，也不能贴错明文。

### 3.4 `hostFix`

抓包链路在没有 SNI 或连接复用时拿不到域名，URL 会退化成 `://unknown-host`。
`hostFix` 按 `headerPriority` 依次取第一个非空头，兜底用 `defaultHost`，把域名补回去。
三个内置插件各自手写过这段逻辑，现在是一条配置。

### 3.5 怎么看解码结果

解出明文后响应头会多一个 `x-aau-decode-hint`，形如 `bzp-block>lz4-block:643->512`：
左边是走过的步骤，右边是原始长度和解出长度。没有这个头就是「没解」，不是「解错了」。

---

## 四、L3：外部插件包

### 4.1 目录

```
filesDir/capture_plugins/
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
  "enabled": true,
  "packages": ["com.acme.market"],
  "dex": "plugin.dex",
  "sha256": "……",
  "middlewareClass": "com.acme.capture.AcmeMiddleware",
  "hostFix": { "headerPriority": ["x-acme-host"], "defaultHost": "api.acme.com" },
  "responseCodecs": [ /* 同 L2 */ ],
  "ebpfProfile": {
    "jniHookRules": [
      { "libSubstring": "libacme.so", "methodNames": ["nativeDecode"], "magic": "ACME" }
    ]
  }
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`，注册为 `external.<id>` |
| `apiVersion` | 否（默认 1） | 必须等于宿主支持的版本，否则直接拒绝 |
| `packages` | 是 | 匹配的包名；`*` 仅建议给内置兜底用 |
| `dex` | 否 | 只能是包目录内的文件名，不允许 `/`、`..` |
| `sha256` | 否 | dex 的 SHA-256，写了就校验 |
| `entryClass` | 否 | 实现 `NetworkCapturePlugin` 的类 |
| `middlewareClass` | 否 | 实现 `NetworkTrafficMiddleware` 的类 |
| `hostFix` / `responseCodecs` | 否 | 同 L2 |
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

---

## 六、排查顺序

1. **有没有字节**：看响应长度是不是 0。没有字节是偏移 / JNI 入口的问题（L1）。
2. **是不是明文**：`Content-Type` 与首字节。`{` / `[` 开头就是明文，别再解。
3. **有没有 `x-aau-decode-hint`**：没有说明规则没命中或某一步失败；有说明解过，
   看 `trace` 判断走了哪几层。
4. **`when` 是否命中**：把条件临时删掉试试，能解就是条件写错了（常见：大小写、
   头的名字带了 `:`）。
5. **容器头部字段位置**：`payloadLen` / `rawLen` 的 `at` 是相对响应体起点，不是相对头部。
