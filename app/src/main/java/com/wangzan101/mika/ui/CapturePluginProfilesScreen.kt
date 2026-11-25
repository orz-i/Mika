package com.wangzan101.mika.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agent.use.client.network.ebpf.EbpfDiscoveryResult
import com.agent.use.client.network.ebpf.LocalRootEbpfCaptureEngine
import com.agent.use.common.network.plugin.DeclarativeConfigs
import com.agent.use.common.network.plugin.EbpfCronetAbiRule
import com.agent.use.common.network.plugin.EbpfCronetOffsetRule
import com.agent.use.common.network.plugin.EbpfProbeProfile
import com.agent.use.common.network.plugin.EbpfSslOffsetRule
import com.agent.use.common.network.plugin.JniHookRule
import com.agent.use.common.network.plugin.external.ExternalCapturePluginLoader
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.cap.phone.network.CapturePluginEntry
import com.wangzan101.mika.cap.phone.network.CapturePluginImporter
import com.wangzan101.mika.cap.phone.network.CapturePluginPack
import com.wangzan101.mika.cap.phone.network.CapturePluginProfileStore
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.mikaPress
import com.wangzan101.mika.ui.components.specularBorderBrush
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class CapturePluginDraft(
    val isNew: Boolean = true,
    val isBuiltinEntry: Boolean = false,
    val isOverridden: Boolean = false,
    val packageName: String = "",
    val quickOffsetsEnabled: Boolean = true,
    val libSubstring: String = "",
    val sslWrite: String = "",
    val sslRead: String = "",
    val sslSni: String = "",
    val crCreate: String = "",
    val crHeaders: String = "",
    val crBody: String = "",
    val crFinish: String = "",
    val abiRawHeaders: String = "0x20",
    val abiIoBufferData: String = "0x10",
    val abiStringLayoutMode: String = "0",
    val abiVtableSlots: String = "3,4,5",
    val abiJniSignatures: String = "(Ljava/lang/Object;JLjava/lang/String;IZZZIZIIJ)J",
    /** 进程内解码入口，每行一条：`lib 子串 | 方法名, 方法名`。留空表示这个 App 不需要进程内通道。 */
    val jniRules: String = "",
    /**
     * 编辑前的入口规则，用来继承界面上没有展示的字段（`magic`、`maxBytes`、`captureReturn`）。
     *
     * 不继承就会出事：BOSS 那条规则带 `magic = "BZPBlock"`，界面只显示库名和方法名，
     * 用户改一个方法名再保存，容器魔数就变成 null —— 带容器的返回会被 loader 当成误读丢掉，
     * 明面上配置还在，实际少了一半数据。
     */
    val sourceJniRules: List<JniHookRule> = emptyList(),
    /**
     * 响应解码管道（JSON）：`hostFix` + `responseCodecs`。
     *
     * 这是「新 App 不用重编 aau」的关键开关：容器魔数、头部字段位置、压缩算法都在这里声明，
     * 底层只负责把字节读出来。留空表示不处理响应体。
     */
    val decoderJson: String = "",
) {
    fun toProfile(): EbpfProbeProfile? {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return null

        var hasSsl = false
        val sslRules = if (quickOffsetsEnabled &&
            (sslWrite.isNotBlank() || sslRead.isNotBlank() || sslSni.isNotBlank())
        ) {
            hasSsl = true
            val lib = libSubstring.trim()
            if (lib.isEmpty()) return null
            val write = EbpfProbeProfile.parseOffset(sslWrite)?.takeIf { it > 0L } ?: return null
            val read = EbpfProbeProfile.parseOffset(sslRead)?.takeIf { it > 0L } ?: return null
            val sni = if (sslSni.isBlank()) {
                0L
            } else {
                EbpfProbeProfile.parseOffset(sslSni)?.takeIf { it >= 0L } ?: return null
            }
            listOf(EbpfSslOffsetRule(lib, write, read, sni))
        } else {
            emptyList()
        }

        var hasCronet = false
        val cronetRules = if (quickOffsetsEnabled &&
            (crCreate.isNotBlank() || crHeaders.isNotBlank() || crBody.isNotBlank() || crFinish.isNotBlank())
        ) {
            hasCronet = true
            val lib = libSubstring.trim()
            if (lib.isEmpty()) return null
            val create = EbpfProbeProfile.parseOffset(crCreate)?.takeIf { it > 0L } ?: return null
            val hdr = EbpfProbeProfile.parseOffset(crHeaders)?.takeIf { it > 0L } ?: return null
            val body = EbpfProbeProfile.parseOffset(crBody)?.takeIf { it > 0L } ?: return null
            val fin = EbpfProbeProfile.parseOffset(crFinish)?.takeIf { it > 0L } ?: return null
            listOf(EbpfCronetOffsetRule(lib, create, hdr, body, fin))
        } else {
            emptyList()
        }

        val hasAbi = abiRawHeaders.isNotBlank() ||
            abiIoBufferData.isNotBlank() ||
            abiStringLayoutMode.isNotBlank() ||
            abiVtableSlots.isNotBlank() ||
            abiJniSignatures.isNotBlank()

        val abiRule = if (hasAbi) {
            val rawHdr = if (abiRawHeaders.isBlank()) {
                0x20
            } else {
                val parsed = EbpfProbeProfile.parseOffset(abiRawHeaders) ?: return null
                if (parsed < 0L || parsed > 0x400L) return null
                parsed.toInt()
            }
            val ioBuf = if (abiIoBufferData.isBlank()) {
                0x10
            } else {
                val parsed = EbpfProbeProfile.parseOffset(abiIoBufferData) ?: return null
                if (parsed < 0L || parsed > 0x400L) return null
                parsed.toInt()
            }
            val strMode = if (abiStringLayoutMode.isBlank()) {
                0
            } else {
                val parsed = EbpfProbeProfile.parseOffset(abiStringLayoutMode) ?: return null
                if (parsed !in 0L..1L) return null
                parsed.toInt()
            }
            val slots = if (abiVtableSlots.isBlank()) {
                Triple(3, 4, 5)
            } else {
                val parts = abiVtableSlots.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size != 3) return null
                val ints = parts.map { p ->
                    val v = p.toIntOrNull() ?: return null
                    if (v !in 0..15) return null
                    v
                }
                Triple(ints[0], ints[1], ints[2])
            }
            val sigs = abiJniSignatures
                .split('\n', ',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            EbpfCronetAbiRule(
                rawHeadersOffset = rawHdr,
                ioBufferDataOffset = ioBuf,
                stringLayoutMode = strMode,
                vtableSlots = slots,
                jniSignatures = sigs,
            )
        } else {
            null
        }

        val jniHookRules = parseJniRules(jniRules, sourceJniRules) ?: return null
        val declarative = DeclarativeConfigs.parse(decoderJson) ?: return null

        // 全是空的才算无效。只有进程内解码入口也是有效配置——那种 App 的标准 TLS 偏移本来就由
        // 内置规则负责，用户要补的只是"明文在它自己的 decoder 里"这一个事实。
        if (quickOffsetsEnabled &&
            !hasSsl && !hasCronet && abiRule == null && jniHookRules.isEmpty() && declarative.isEmpty
        ) {
            return null
        }

        return EbpfProbeProfile(
            sslOffsetRules = sslRules,
            cronetOffsetRules = cronetRules,
            cronetAbiRule = abiRule,
            jniHookRules = jniHookRules,
            declarative = declarative,
            // 「替换内置」只在用户确实给了偏移时成立。一个偏移都没有、只有进程内入口时，
            // 内置那条 SSL/Cronet 规则是这个 App 能有流量的前提，丢掉它等于为了拿明文
            // 反而把抓包本身拆了。
            replaceBuiltinOffsets = when {
                !hasSsl && !hasCronet && abiRule == null -> false
                !quickOffsetsEnabled -> true
                else -> isBuiltinEntry
            },
        )
    }

    /**
     * 解析进程内解码入口，每行 `lib 子串 | 方法名, 方法名`。
     *
     * 格式不对返回 null，让保存直接失败——静默丢掉一行会让用户以为配好了，实际只有部分入口在挂。
     * 空行与 `#` 开头的行跳过，方便在界面上留注释。
     *
     * 同一条库的规则从 [inherited] 继承界面上没有展示的字段：界面只编辑「库 + 方法名」，
     * 而一条规则还带 `magic` / `maxBytes` / `captureReturn` 这些声明式常量，丢掉它们会让
     * 带容器的返回被判为误读。
     */
    private fun parseJniRules(raw: String, inherited: List<JniHookRule>): List<JniHookRule>? {
        val rules = ArrayList<JniHookRule>()
        for (line in raw.split('\n')) {
            val text = line.trim()
            if (text.isEmpty() || text.startsWith("#")) continue
            val sep = text.indexOf('|')
            if (sep <= 0) return null
            val lib = text.substring(0, sep).trim()
            val names = text.substring(sep + 1)
                .split(',', '|', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lib.isEmpty() || names.isEmpty()) return null
            val base = inherited.firstOrNull { it.libSubstring.equals(lib, ignoreCase = true) }
            rules += if (base != null) {
                base.copy(libSubstring = lib, methodNames = names)
            } else {
                JniHookRule(libSubstring = lib, methodNames = names)
            }
        }
        return rules
    }

    companion object {
        fun fromEntry(entry: CapturePluginEntry): CapturePluginDraft {
            val ssl = entry.profile.sslOffsetRules.firstOrNull()
            val cronet = entry.profile.cronetOffsetRules.firstOrNull()
            val abi = entry.profile.cronetAbiRule
            val lib = cronet?.libSubstring ?: ssl?.libSubstring.orEmpty()
            val hasOffsets = ssl != null || cronet != null
            return CapturePluginDraft(
                isNew = false,
                isBuiltinEntry = entry.isBuiltin,
                isOverridden = entry.isOverridden,
                packageName = entry.packageName,
                quickOffsetsEnabled = if (entry.packageName == "*") hasOffsets else hasOffsets,
                libSubstring = lib,
                sslWrite = ssl?.writeOffset?.let(EbpfProbeProfile::formatOffset).orEmpty(),
                sslRead = ssl?.readOffset?.let(EbpfProbeProfile::formatOffset).orEmpty(),
                sslSni = ssl?.sniOffset?.let(EbpfProbeProfile::formatOffset).orEmpty(),
                crCreate = cronet?.createOffset?.let(EbpfProbeProfile::formatOffset).orEmpty(),
                crHeaders = cronet?.responseStartedOffset?.let(EbpfProbeProfile::formatOffset).orEmpty(),
                crBody = cronet?.readCompletedOffset?.let(EbpfProbeProfile::formatOffset).orEmpty(),
                crFinish = cronet?.succeededOffset?.let(EbpfProbeProfile::formatOffset).orEmpty(),
                abiRawHeaders = abi?.rawHeadersOffset?.toLong()?.let(EbpfProbeProfile::formatOffset).orEmpty(),
                abiIoBufferData = abi?.ioBufferDataOffset?.toLong()?.let(EbpfProbeProfile::formatOffset).orEmpty(),
                abiStringLayoutMode = abi?.stringLayoutMode?.toString().orEmpty(),
                abiVtableSlots = abi?.vtableSlots?.let { "${it.first},${it.second},${it.third}" }.orEmpty(),
                abiJniSignatures = abi?.jniSignatures?.joinToString("\n").orEmpty(),
                jniRules = entry.profile.jniHookRules.joinToString("\n") { rule ->
                    "${rule.libSubstring} | ${rule.methodNames.joinToString(", ")}"
                },
                sourceJniRules = entry.profile.jniHookRules,
                decoderJson = DeclarativeConfigs.toJson(entry.profile.declarative),
            )
        }
    }
}

internal object CapturePluginCopy {
    fun pluginRowSummary(entries: List<CapturePluginEntry>): String {
        val builtinCount = entries.count { it.isBuiltin }
        val customCount = entries.count { it.isOverridden }
        val xhsLib = entries.firstOrNull { it.packageName == "com.xingin.xhs" }
            ?.let { it.profile.cronetOffsetRules.firstOrNull()?.libSubstring ?: it.profile.sslOffsetRules.firstOrNull()?.libSubstring }
            ?: "ELF 通用"
        return "$builtinCount / $customCount · $xhsLib"
    }

    fun formatDetail(profile: EbpfProbeProfile): String {
        val parts = ArrayList<String>()
        profile.sslOffsetRules.firstOrNull()?.let { ssl ->
            parts += "SSL: write=${EbpfProbeProfile.formatOffset(ssl.writeOffset)} · read=${EbpfProbeProfile.formatOffset(ssl.readOffset)} · sni=${EbpfProbeProfile.formatOffset(ssl.sniOffset)}"
        }
        profile.cronetOffsetRules.firstOrNull()?.let { cr ->
            parts += "Cronet: create=${EbpfProbeProfile.formatOffset(cr.createOffset)} · hdr=${EbpfProbeProfile.formatOffset(cr.responseStartedOffset)} · read=${EbpfProbeProfile.formatOffset(cr.readCompletedOffset)} · fin=${EbpfProbeProfile.formatOffset(cr.succeededOffset)}"
        }
        profile.cronetAbiRule?.let { abi ->
            parts += "ABI: rawHdr=${EbpfProbeProfile.formatOffset(abi.rawHeadersOffset.toLong())} · ioBuf=${EbpfProbeProfile.formatOffset(abi.ioBufferDataOffset.toLong())} · strMode=${abi.stringLayoutMode} · slots=${abi.vtableSlots.first},${abi.vtableSlots.second},${abi.vtableSlots.third}"
        }
        profile.jniHookRules.forEach { rule ->
            val magic = rule.magic?.takeIf { it.isNotBlank() }?.let { " · magic=$it" }.orEmpty()
            parts += "JNI: ${rule.libSubstring} → ${rule.methodNames.joinToString(", ")}$magic"
        }
        profile.declarative.hostFix?.let { fix ->
            val fallback = fix.defaultHost?.takeIf { it.isNotBlank() }?.let { " · fallback=$it" }.orEmpty()
            parts += "Host: ${fix.headerPriority.joinToString(" > ")}$fallback"
        }
        profile.declarative.responseCodecs.forEach { rule ->
            val prefix = rule.id?.takeIf { it.isNotBlank() }?.let { "$it: " }.orEmpty()
            parts += "Decode: $prefix" + rule.steps.joinToString(" → ") { it.traceName }
        }
        return parts.joinToString("\n")
    }

    fun formatDiscoverySummary(result: EbpfDiscoveryResult): String {
        val report = result.report ?: return result.error ?: "discover_failed"
        val sb = StringBuilder()
        sb.append("UID=${report.uid} · 架构=${report.arch} · 已扫描 ${report.scannedLibrariesCount} 个 .so\n")
        if (report.libraries.isEmpty()) {
            sb.append("未检测到匹配的 SSL / Cronet 钩子目标。\n")
        } else {
            for (lib in report.libraries) {
                val scopeTag = if (lib.inAppDir) "应用目录" else "系统库"
                sb.append("\n• ${lib.name} [$scopeTag]\n")
                if (lib.hasSsl) {
                    sb.append("  SSL(${lib.sslSource}): write=${lib.sslWriteOffset?.let(EbpfProbeProfile::formatOffset)} · read=${lib.sslReadOffset?.let(EbpfProbeProfile::formatOffset)} · sni=${lib.sslSniOffset?.let(EbpfProbeProfile::formatOffset) ?: "0x0"}\n")
                }
                if (lib.hasCronet) {
                    sb.append("  Cronet(${lib.cronetSource}): create=${lib.cronetCreateOffset?.let(EbpfProbeProfile::formatOffset)} · hdr=${lib.cronetResponseStartedOffset?.let(EbpfProbeProfile::formatOffset)} · read=${lib.cronetReadCompletedOffset?.let(EbpfProbeProfile::formatOffset)} · fin=${lib.cronetSucceededOffset?.let(EbpfProbeProfile::formatOffset)}\n")
                }
            }
        }
        if (report.unmatchedAppLibs.isNotEmpty()) {
            sb.append("\n未匹配的应用 SO (${report.unmatchedAppLibs.size}): ${report.unmatchedAppLibs.take(8).joinToString(", ")}")
            if (report.unmatchedAppLibs.size > 8) sb.append("…")
        }
        return sb.toString().trim()
    }
}

private enum class PluginAppPickMode { DraftPackage, ScanTarget }

/** 导入框的状态。两步之间只留暂存目录，装与不装都不影响已经在跑的抓包。 */
private sealed interface PackImportState {
    data class Confirm(val staged: CapturePluginImporter.Staged) : PackImportState

    data class Failed(
        val reason: CapturePluginImporter.Reason,
        val detail: String?,
        val sourceName: String,
    ) : PackImportState
}

/**
 * 设置 › 高级与其他 › 端侧网络抓包 › eBPF 探测规则：展示并配置小红书 `libcronet.114.0.5735.38.so` 等快速偏移与 ABI 参数，并支持 `--discover-only` 一键扫描。
 */
@Composable
fun CapturePluginProfilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { CapturePluginProfileStore.forContext(context) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<CapturePluginEntry>()) }
    var packs by remember { mutableStateOf(emptyList<CapturePluginPack>()) }
    var installedApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var importState by remember { mutableStateOf<PackImportState?>(null) }
    var importing by remember { mutableStateOf(false) }

    fun refreshEntries() {
        entries = store.snapshotEntries()
        packs = store.snapshotPacks()
    }

    LaunchedEffect(store) {
        store.loadIntoRegistry()
        refreshEntries()
        installedApps = withContext(Dispatchers.IO) {
            // 上一次导入要是被杀在确认框上，暂存目录还在；顺手清掉。
            store.clearStaging()
            runCatching { launchableApps(context.packageManager, context.packageName) }.getOrDefault(emptyList())
        }
    }

    // 选文件只是把字节落下来；装不装由后面的确认框决定。
    val pickPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = displayNameOf(context, uri)
            importing = true
            scope.launch {
                val outcome = withContext(Dispatchers.IO) { stagePickedPack(context, store, uri, name) }
                importing = false
                importState = when (outcome) {
                    is CapturePluginImporter.Outcome.Ready -> PackImportState.Confirm(outcome.staged)
                    is CapturePluginImporter.Outcome.Failed ->
                        PackImportState.Failed(outcome.reason, outcome.detail, name)
                }
            }
        }
    }

    CapturePluginProfilesContent(
        entries = entries,
        packs = packs,
        installedApps = installedApps,
        importing = importing,
        onBack = onBack,
        onSaveProfile = { pkg, profile ->
            store.saveProfile(pkg, profile)
            refreshEntries()
        },
        onResetPackage = { pkg ->
            store.resetPackage(pkg)
            refreshEntries()
        },
        onResetAll = {
            store.resetAll()
            refreshEntries()
        },
        onTogglePack = { id, enabled ->
            store.setPackEnabled(id, enabled)
            refreshEntries()
        },
        onImportPack = {
            pickPack.launch(
                arrayOf(
                    "application/zip",
                    "application/json",
                    "text/plain",
                    "application/octet-stream",
                ),
            )
        },
        onRemovePack = { id ->
            scope.launch {
                withContext(Dispatchers.IO) { store.removePack(id) }
                refreshEntries()
            }
        },
        onDiscoverPackage = { pkg ->
            withContext(Dispatchers.IO) {
                LocalRootEbpfCaptureEngine.discoverAppProfile(context, pkg, useHeuristicsOnly = true)
            }
        },
    )

    when (val s = importState) {
        is PackImportState.Confirm -> ImportPackDialog(
            staged = s.staged,
            installed = packs.firstOrNull { it.id == s.staged.info.id },
            onDismiss = {
                CapturePluginImporter.discard(s.staged)
                importState = null
            },
            onInstall = {
                val staged = s.staged
                importState = null
                importing = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { store.importPack(staged) }
                    importing = false
                    if (result is CapturePluginImporter.InstallResult.Failed) {
                        importState = PackImportState.Failed(result.reason, result.detail, staged.sourceName)
                    } else {
                        refreshEntries()
                    }
                }
            },
        )

        is PackImportState.Failed -> ImportFailedDialog(
            state = s,
            onDismiss = { importState = null },
        )

        null -> Unit
    }
}

/** 显示名只用来说明"在导入哪个文件"，取不到就退回 uri 的末段。 */
private fun displayNameOf(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
    }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "plugin"

/**
 * 把选中的文件落到暂存区再解包。复制这一层是必要的：
 * SAF 给的 uri 只在这次回调前后有效，而解包可能很慢，不能边读 uri 边解。
 */
private fun stagePickedPack(
    context: Context,
    store: CapturePluginProfileStore,
    uri: Uri,
    displayName: String,
): CapturePluginImporter.Outcome {
    val staging = store.stagingDir
        ?: return CapturePluginImporter.Outcome.Failed(CapturePluginImporter.Reason.Unreadable, "no files dir")
    if (!staging.isDirectory && !staging.mkdirs()) {
        return CapturePluginImporter.Outcome.Failed(CapturePluginImporter.Reason.Unreadable, "mkdirs")
    }
    val incoming = File(staging, "incoming-" + System.nanoTime().toString(36))
    return try {
        val opened = context.contentResolver.openInputStream(uri)
            ?: return CapturePluginImporter.Outcome.Failed(CapturePluginImporter.Reason.Unreadable, "open")
        opened.use { input ->
            incoming.outputStream().use { output -> input.copyTo(output) }
        }
        CapturePluginImporter.stage(incoming, displayName, staging)
    } catch (t: Throwable) {
        CapturePluginImporter.Outcome.Failed(
            CapturePluginImporter.Reason.Unreadable,
            t.message ?: t::class.java.simpleName,
        )
    } finally {
        incoming.delete()
    }
}

/**
 * 装之前的确认框：和插件包详情用的是同一套「自述 + 通道」两栏。
 *
 * 带 dex 的包单独警告一行——它会和 Mika 同进程跑，这句话不该藏在详情里。
 */
@Composable
private fun ImportPackDialog(
    staged: CapturePluginImporter.Staged,
    installed: CapturePluginPack?,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    val info = staged.info
    val c = MikaTheme.colors
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.network_capture_packs_import_title, info.title ?: staged.specId)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.network_capture_packs_meta, info.version, info.apiVersion),
                    style = MikaType.Caption,
                    color = c.ink3,
                )
                info.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, Modifier.padding(top = 8.dp), style = MikaType.Body, color = c.ink)
                }
                info.author?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        stringResource(R.string.network_capture_packs_author, it),
                        Modifier.padding(top = 6.dp),
                        style = MikaType.Hint,
                        color = c.ink3,
                    )
                }
                Text(
                    stringResource(R.string.network_capture_packs_scope, packScopeText(info)),
                    Modifier.padding(top = 8.dp),
                    style = MikaType.Caption,
                    color = c.ink2,
                )
                packChannelText(info).takeIf { it.isNotEmpty() }?.let {
                    Text(
                        stringResource(R.string.network_capture_packs_touches, it),
                        Modifier.padding(top = 4.dp),
                        style = MikaType.Caption,
                        color = c.ink2,
                    )
                }
                if (info.declares.isNotEmpty()) {
                    Text(
                        stringResource(R.string.network_capture_packs_notes_title),
                        Modifier.padding(top = 12.dp),
                        style = MikaType.Caption,
                        color = c.ink2,
                    )
                    info.declares.forEach { note ->
                        Text(
                            "• $note",
                            Modifier.padding(top = 4.dp),
                            style = MikaType.Hint,
                            color = c.ink3,
                        )
                    }
                }
                if (info.hasCode) {
                    Text(
                        stringResource(R.string.network_capture_packs_import_code),
                        Modifier.padding(top = 12.dp),
                        style = MikaType.Caption,
                        color = c.warn,
                    )
                }
                if (installed != null) {
                    Text(
                        stringResource(R.string.network_capture_packs_import_replace, installed.info.version),
                        Modifier.padding(top = 10.dp),
                        style = MikaType.Caption,
                        color = c.ink3,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text(stringResource(R.string.network_capture_packs_import_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ImportFailedDialog(state: PackImportState.Failed, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    val reason = when (state.reason) {
        CapturePluginImporter.Reason.NotAPackage -> stringResource(R.string.network_capture_packs_import_reason_no_manifest)
        CapturePluginImporter.Reason.Ambiguous -> stringResource(R.string.network_capture_packs_import_reason_ambiguous)
        CapturePluginImporter.Reason.TooLarge -> stringResource(R.string.network_capture_packs_import_reason_too_large)
        CapturePluginImporter.Reason.UnsafeEntry -> stringResource(R.string.network_capture_packs_import_reason_unsafe)
        CapturePluginImporter.Reason.Unreadable -> stringResource(R.string.network_capture_packs_import_reason_unreadable)
        CapturePluginImporter.Reason.Invalid -> stringResource(R.string.network_capture_packs_import_reason_invalid)
    }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.network_capture_packs_import_failed, state.sourceName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(reason, style = MikaType.Body, color = c.ink)
                // 具体哪一条校验没过（`api_version_mismatch:2!=1` 这种）照原样给出来：
                // 用户要拿它去改包，转述一遍只会丢信息。
                state.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, Modifier.padding(top = 8.dp), style = MikaType.Hint, color = c.ink3)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
    )
}

@Composable
fun CapturePluginProfilesContent(
    entries: List<CapturePluginEntry>,
    packs: List<CapturePluginPack> = emptyList(),
    installedApps: List<Pair<String, String>> = emptyList(),
    onBack: () -> Unit,
    importing: Boolean = false,
    onSaveProfile: (packageName: String, profile: EbpfProbeProfile) -> Unit = { _, _ -> },
    onResetPackage: (packageName: String) -> Unit = {},
    onResetAll: () -> Unit = {},
    onDiscoverPackage: (suspend (String) -> EbpfDiscoveryResult)? = null,
    /** 关/开一个插件包。关掉只是不加载，磁盘上的包不动。 */
    onTogglePack: (id: String, enabled: Boolean) -> Unit = { _, _ -> },
    /** 选一个 .zip / plugin.json 导入。真正装不装，由调用方再问一次。 */
    onImportPack: () -> Unit = {},
    /** 删掉一个外部插件包。内置包不给删，对它该做的是关掉开关。 */
    onRemovePack: (id: String) -> Unit = {},
) {
    val appLabels = remember(installedApps) { installedApps.toMap() }
    var draft by remember { mutableStateOf<CapturePluginDraft?>(null) }
    var invalidDraft by remember { mutableStateOf(false) }
    var appPickMode by remember { mutableStateOf<PluginAppPickMode?>(null) }
    var appSearch by remember { mutableStateOf("") }
    var confirmingResetAll by remember { mutableStateOf(false) }
    var packDetail by remember { mutableStateOf<CapturePluginPack?>(null) }
    var removingPack by remember { mutableStateOf<CapturePluginPack?>(null) }
    var scanInputOpen by remember { mutableStateOf(false) }
    var scanPackageInput by remember { mutableStateOf("com.xingin.xhs") }
    var scanningPackage by remember { mutableStateOf<String?>(null) }
    var discoveryOutcome by remember { mutableStateOf<Pair<String, EbpfDiscoveryResult>?>(null) }

    LaunchedEffect(scanningPackage) {
        val pkg = scanningPackage ?: return@LaunchedEffect
        val discoverer = onDiscoverPackage
        if (discoverer == null) {
            scanningPackage = null
            return@LaunchedEffect
        }
        val res = discoverer(pkg)
        if (scanningPackage == pkg) {
            scanningPackage = null
            discoveryOutcome = pkg to res
        }
    }

    val genericName = stringResource(R.string.network_capture_plugins_generic_name)
    fun displayTitle(entry: CapturePluginEntry): String = when (entry.packageName) {
        "*" -> genericName
        "com.xingin.xhs" -> (appLabels[entry.packageName]?.takeIf { it.isNotBlank() } ?: "小红书") + " (com.xingin.xhs)"
        else -> appLabels[entry.packageName]?.takeIf { it.isNotBlank() }?.let { "$it (${entry.packageName})" } ?: entry.packageName
    }

    PageScaffold(stringResource(R.string.network_capture_plugins_title), onBack) {
        // 插件包排在规则前面：用户先决定"这个包要不要装/要不要开"，再谈它里面的偏移。
        GroupLabel(stringResource(R.string.network_capture_packs_group, packs.size))
        GroupCard {
            if (packs.isEmpty()) {
                ListRow(
                    icon = MikaIcons.Apps,
                    title = stringResource(R.string.network_capture_packs_empty),
                    subtitle = stringResource(R.string.network_capture_packs_empty_sub),
                    first = true,
                )
            }
            packs.forEachIndexed { idx, pack ->
                val info = pack.info
                val (badge, tone) = when {
                    info.error != null -> stringResource(R.string.network_capture_packs_tag_error) to PillTone.Warn
                    !info.enabled -> stringResource(R.string.state_off) to PillTone.Warn
                    pack.isExternal -> stringResource(R.string.network_capture_plugins_tag_external) to PillTone.Ok
                    else -> stringResource(R.string.network_capture_plugins_tag_builtin) to PillTone.Neutral
                }
                ListRow(
                    icon = MikaIcons.Apps,
                    title = pack.displayTitle,
                    subtitle = packScopeSummary(info),
                    detail = packChannelText(info).takeIf { it.isNotEmpty() },
                    first = idx == 0,
                    onClick = { packDetail = pack },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusPill(badge, tone)
                        MikaSwitch(
                            checked = info.enabled,
                            onCheckedChange = { onTogglePack(pack.id, it) },
                            enabled = info.error == null,
                        )
                    }
                }
            }
        }

        GroupLabel(stringResource(R.string.network_capture_plugins_actions))
        GroupCard {
            // 导入排第一：现在装一个包不用再拿文件管理器往私有目录里塞。
            ListRow(
                icon = MikaIcons.Download,
                title = stringResource(R.string.network_capture_packs_import),
                subtitle = if (importing) {
                    stringResource(R.string.network_capture_packs_import_reading)
                } else {
                    stringResource(R.string.network_capture_packs_import_sub)
                },
                first = true,
                onClick = if (importing) null else onImportPack,
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 4.dp).size(16.dp),
                        strokeWidth = 2.dp,
                        color = MikaTheme.colors.ink3,
                    )
                } else {
                    RowChevron()
                }
            }
            if (onDiscoverPackage != null) {
                ListRow(
                    MikaIcons.Search,
                    stringResource(R.string.network_capture_plugins_auto_scan),
                    stringResource(R.string.network_capture_plugins_auto_scan_sub),
                    first = false,
                    onClick = {
                        scanPackageInput = "com.xingin.xhs"
                        scanInputOpen = true
                    },
                ) {
                    RowChevron()
                }
            }
            ListRow(
                MikaIcons.Plus,
                stringResource(R.string.network_capture_plugins_add),
                first = false,
                onClick = {
                    invalidDraft = false
                    draft = CapturePluginDraft()
                },
            ) {
                RowChevron()
            }
            if (entries.any { it.isOverridden }) {
                ListRow(
                    MikaIcons.Trash,
                    stringResource(R.string.network_capture_plugins_reset_all),
                    tone = TileTone.Bad,
                    titleColor = MikaTheme.colors.bad,
                    onClick = { confirmingResetAll = true },
                )
            }
        }

        GroupLabel(stringResource(R.string.network_capture_plugins_list_group, entries.size))
        GroupCard {
            entries.forEachIndexed { idx, entry ->
                val libName = entry.profile.cronetOffsetRules.firstOrNull()?.libSubstring
                    ?: entry.profile.sslOffsetRules.firstOrNull()?.libSubstring
                val offsetsDisabled = entry.packageName != "*" && libName == null && entry.profile.replaceBuiltinOffsets
                val subtitle = when {
                    libName != null -> libName
                    offsetsDisabled -> stringResource(R.string.network_capture_plugins_offsets_disabled_sub)
                    else -> stringResource(R.string.network_capture_plugins_generic_only_sub)
                }
                val (badge, tone) = when {
                    // 「我改过」比「它从哪来」更该被看见：内置包被覆盖后仍标记成"内置"，
                    // 用户会以为改动没生效，回头再改一遍。
                    offsetsDisabled -> stringResource(R.string.network_capture_plugins_tag_disabled) to PillTone.Warn
                    entry.isOverridden -> stringResource(R.string.network_capture_plugins_tag_custom) to PillTone.Ok
                    entry.isExternal -> stringResource(R.string.network_capture_plugins_tag_external) to PillTone.Ok
                    else -> stringResource(R.string.network_capture_plugins_tag_builtin) to PillTone.Neutral
                }
                ListRow(
                    icon = MikaIcons.Key,
                    title = displayTitle(entry),
                    subtitle = subtitle,
                    detail = CapturePluginCopy.formatDetail(entry.profile).takeIf { it.isNotEmpty() },
                    first = idx == 0,
                    onClick = {
                        invalidDraft = false
                        draft = CapturePluginDraft.fromEntry(entry)
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusPill(badge, tone)
                        RowChevron()
                    }
                }
            }
        }

        FooterNote(stringResource(R.string.network_capture_plugins_footer))
    }

    val currentDraft = draft
    val pickMode = appPickMode
    if (currentDraft != null && pickMode == null && !scanInputOpen && scanningPackage == null && discoveryOutcome == null) {
        MikaDialog(
            onDismissRequest = { draft = null },
            title = {
                Text(
                    stringResource(
                        if (currentDraft.isNew) {
                            R.string.network_capture_plugins_add_title
                        } else {
                            R.string.network_capture_plugins_edit_title
                        },
                    ),
                )
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = currentDraft.packageName,
                        onValueChange = {
                            invalidDraft = false
                            draft = currentDraft.copy(packageName = it)
                        },
                        enabled = currentDraft.isNew,
                        label = { Text(stringResource(R.string.network_capture_plugins_field_package)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("capture-plugin-input-package"),
                    )
                    if (currentDraft.isNew && installedApps.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = {
                                    appSearch = ""
                                    appPickMode = PluginAppPickMode.DraftPackage
                                },
                            ) {
                                Text(stringResource(R.string.settings_jump_confirm_profiles_pick_app))
                            }
                        }
                    }
                    if (onDiscoverPackage != null && currentDraft.packageName.trim().let { it.isNotEmpty() && it != "*" }) {
                        TextButton(
                            onClick = {
                                val pkg = currentDraft.packageName.trim()
                                scanningPackage = pkg
                            },
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Text(stringResource(R.string.network_capture_plugins_scan_this_app))
                        }
                    }

                    val c = MikaTheme.colors
                    // 覆盖文件里只有"用户改过的那一版"，内置包的规则仍然在下面合并。
                    // 不说清楚的话，打开一个只补了入口的覆盖会看到偏移全空，像是配置丢了。
                    if (!currentDraft.isNew && currentDraft.isOverridden) {
                        Text(
                            stringResource(R.string.network_capture_plugins_override_note),
                            style = MikaType.Caption,
                            color = c.ink3,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    val toggleShape = RoundedCornerShape(12.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(toggleShape)
                            .background(c.glassSubtle, toggleShape)
                            .border(0.5.dp, specularBorderBrush(c), toggleShape)
                            .clickable {
                                invalidDraft = false
                                draft = currentDraft.copy(quickOffsetsEnabled = !currentDraft.quickOffsetsEnabled)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                stringResource(R.string.network_capture_plugins_quick_toggle),
                                style = MikaType.Body,
                                color = c.ink,
                            )
                            Text(
                                stringResource(R.string.network_capture_plugins_quick_toggle_sub),
                                style = MikaType.Caption,
                                color = c.ink3,
                            )
                        }
                        MikaSwitch(
                            checked = currentDraft.quickOffsetsEnabled,
                            onCheckedChange = { on ->
                                invalidDraft = false
                                draft = currentDraft.copy(quickOffsetsEnabled = on)
                            },
                            modifier = Modifier.testTag("capture-plugin-quick-toggle"),
                        )
                    }

                    if (currentDraft.quickOffsetsEnabled) {
                        OutlinedTextField(
                            value = currentDraft.libSubstring,
                            onValueChange = {
                                invalidDraft = false
                                draft = currentDraft.copy(libSubstring = it)
                            },
                            label = { Text(stringResource(R.string.network_capture_plugins_field_lib)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("capture-plugin-input-lib"),
                        )
                        OutlinedTextField(
                            value = currentDraft.sslWrite,
                            onValueChange = { invalidDraft = false; draft = currentDraft.copy(sslWrite = it) },
                            label = { Text(stringResource(R.string.network_capture_plugins_field_ssl_write)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("capture-plugin-input-ssl-write"),
                        )
                        OutlinedTextField(
                            value = currentDraft.sslRead,
                            onValueChange = { invalidDraft = false; draft = currentDraft.copy(sslRead = it) },
                            label = { Text(stringResource(R.string.network_capture_plugins_field_ssl_read)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("capture-plugin-input-ssl-read"),
                        )
                        OutlinedTextField(
                            value = currentDraft.sslSni,
                            onValueChange = { invalidDraft = false; draft = currentDraft.copy(sslSni = it) },
                            label = { Text(stringResource(R.string.network_capture_plugins_field_ssl_sni)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("capture-plugin-input-ssl-sni"),
                        )
                        OutlinedTextField(
                            value = currentDraft.crCreate,
                            onValueChange = { invalidDraft = false; draft = currentDraft.copy(crCreate = it) },
                            label = { Text(stringResource(R.string.network_capture_plugins_field_cr_create)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("capture-plugin-input-cr-create"),
                        )
                        OutlinedTextField(
                            value = currentDraft.crHeaders,
                            onValueChange = { invalidDraft = false; draft = currentDraft.copy(crHeaders = it) },
                            label = { Text(stringResource(R.string.network_capture_plugins_field_cr_hdr)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("capture-plugin-input-cr-hdr"),
                        )
                        OutlinedTextField(
                            value = currentDraft.crBody,
                            onValueChange = { invalidDraft = false; draft = currentDraft.copy(crBody = it) },
                            label = { Text(stringResource(R.string.network_capture_plugins_field_cr_body)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("capture-plugin-input-cr-body"),
                        )
                        OutlinedTextField(
                            value = currentDraft.crFinish,
                            onValueChange = { invalidDraft = false; draft = currentDraft.copy(crFinish = it) },
                            label = { Text(stringResource(R.string.network_capture_plugins_field_cr_fin)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("capture-plugin-input-cr-fin"),
                        )
                    }

                    OutlinedTextField(
                        value = currentDraft.abiRawHeaders,
                        onValueChange = { invalidDraft = false; draft = currentDraft.copy(abiRawHeaders = it) },
                        label = { Text(stringResource(R.string.network_capture_plugins_field_abi_raw_hdr)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = currentDraft.abiIoBufferData,
                        onValueChange = { invalidDraft = false; draft = currentDraft.copy(abiIoBufferData = it) },
                        label = { Text(stringResource(R.string.network_capture_plugins_field_abi_iobuf)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = currentDraft.abiStringLayoutMode,
                        onValueChange = { invalidDraft = false; draft = currentDraft.copy(abiStringLayoutMode = it) },
                        label = { Text(stringResource(R.string.network_capture_plugins_field_abi_strmode)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = currentDraft.abiVtableSlots,
                        onValueChange = { invalidDraft = false; draft = currentDraft.copy(abiVtableSlots = it) },
                        label = { Text(stringResource(R.string.network_capture_plugins_field_abi_slots)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = currentDraft.abiJniSignatures,
                        onValueChange = { invalidDraft = false; draft = currentDraft.copy(abiJniSignatures = it) },
                        label = { Text(stringResource(R.string.network_capture_plugins_field_abi_sigs)) },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    // 进程内解码入口与偏移无关：关掉快速偏移也该能配，有些 App 的明文只在它自己
                    // 的 decoder 里，标准 TLS 偏移照旧由内置规则负责。
                    OutlinedTextField(
                        value = currentDraft.jniRules,
                        onValueChange = { invalidDraft = false; draft = currentDraft.copy(jniRules = it) },
                        label = { Text(stringResource(R.string.network_capture_plugins_field_jni_rules)) },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("capture-plugin-input-jni-rules"),
                    )
                    Text(
                        stringResource(R.string.network_capture_plugins_jni_hint),
                        style = MikaType.Caption,
                        color = MikaTheme.colors.ink3,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    OutlinedTextField(
                        value = currentDraft.decoderJson,
                        onValueChange = { invalidDraft = false; draft = currentDraft.copy(decoderJson = it) },
                        label = { Text(stringResource(R.string.network_capture_plugins_field_decoder)) },
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("capture-plugin-input-decoder"),
                    )
                    Text(
                        stringResource(R.string.network_capture_plugins_decoder_hint),
                        style = MikaType.Caption,
                        color = MikaTheme.colors.ink3,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    if (invalidDraft) {
                        Text(
                            stringResource(R.string.network_capture_plugins_invalid),
                            color = MikaTheme.colors.bad,
                            style = MikaType.Caption,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (!currentDraft.isNew && currentDraft.isOverridden) {
                        TextButton(
                            onClick = {
                                onResetPackage(currentDraft.packageName)
                                draft = null
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (currentDraft.isBuiltinEntry) {
                                        R.string.network_capture_plugins_restore_one
                                    } else {
                                        R.string.network_capture_plugins_delete_one
                                    },
                                ),
                                color = MikaTheme.colors.bad,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = currentDraft.toProfile()
                        if (parsed == null) {
                            invalidDraft = true
                            return@TextButton
                        }
                        onSaveProfile(currentDraft.packageName.trim(), parsed)
                        draft = null
                    },
                ) {
                    Text(stringResource(R.string.settings_jump_confirm_profiles_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { draft = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (pickMode != null) {
        val filtered = remember(installedApps, appSearch) {
            val q = appSearch.trim()
            if (q.isEmpty()) installedApps
            else installedApps.filter { (pkg, lbl) ->
                pkg.contains(q, ignoreCase = true) || lbl.contains(q, ignoreCase = true)
            }
        }
        MikaDialog(
            onDismissRequest = { appPickMode = null },
            title = { Text(stringResource(R.string.network_capture_plugins_field_package)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = appSearch,
                        onValueChange = { appSearch = it },
                        label = { Text(stringResource(R.string.settings_jump_confirm_profiles_search_app)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    val c = MikaTheme.colors
                    val pickerShape = RoundedCornerShape(12.dp)
                    Column(
                        Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        filtered.forEach { (pkg, label) ->
                            val interaction = remember(pkg) { MutableInteractionSource() }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(pickerShape)
                                    .mikaPress(interaction, pressedScale = 0.985f, shape = pickerShape)
                                    .background(c.glassSubtle, pickerShape)
                                    .border(0.5.dp, specularBorderBrush(c), pickerShape)
                                    .clickable(interactionSource = interaction, indication = null) {
                                        if (pickMode == PluginAppPickMode.DraftPackage && currentDraft != null) {
                                            invalidDraft = false
                                            draft = currentDraft.copy(packageName = pkg)
                                        } else if (pickMode == PluginAppPickMode.ScanTarget) {
                                            scanPackageInput = pkg
                                        }
                                        appPickMode = null
                                    }
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                            ) {
                                Text(label, style = MikaType.Body, color = c.ink)
                                Text(pkg, style = MikaType.Caption, color = c.ink3)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { appPickMode = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (scanInputOpen && pickMode == null) {
        MikaDialog(
            onDismissRequest = { scanInputOpen = false },
            title = { Text(stringResource(R.string.network_capture_plugins_scan_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = scanPackageInput,
                        onValueChange = { scanPackageInput = it },
                        label = { Text(stringResource(R.string.network_capture_plugins_field_package)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("capture-plugin-scan-package"),
                    )
                    if (installedApps.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = {
                                    appSearch = ""
                                    appPickMode = PluginAppPickMode.ScanTarget
                                },
                            ) {
                                Text(stringResource(R.string.settings_jump_confirm_profiles_pick_app))
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.network_capture_plugins_scan_hint),
                        style = MikaType.Caption,
                        color = MikaTheme.colors.ink3,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pkg = scanPackageInput.trim()
                        if (pkg.isNotEmpty() && pkg != "*") {
                            scanInputOpen = false
                            scanningPackage = pkg
                        }
                    },
                ) {
                    Text(stringResource(R.string.network_capture_plugins_scan_start))
                }
            },
            dismissButton = {
                TextButton(onClick = { scanInputOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val activeScanPkg = scanningPackage
    if (activeScanPkg != null) {
        MikaDialog(
            onDismissRequest = { scanningPackage = null },
            title = { Text(stringResource(R.string.network_capture_plugins_scanning_title)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(4.dp),
                        color = MikaTheme.colors.accent,
                        trackColor = MikaTheme.colors.accentSoft,
                    )
                    Text(stringResource(R.string.network_capture_plugins_scanning_body, activeScanPkg))
                }
            },
            confirmButton = {
                TextButton(onClick = { scanningPackage = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val outcome = discoveryOutcome
    if (outcome != null) {
        val (scannedPkg, result) = outcome
        val suggested = result.suggestedProfile
        MikaDialog(
            onDismissRequest = { discoveryOutcome = null },
            title = {
                Text(
                    stringResource(
                        if (result.ok) R.string.network_capture_plugins_scan_result_title
                        else R.string.network_capture_plugins_scan_failed_title,
                    ),
                )
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        CapturePluginCopy.formatDiscoverySummary(result),
                        style = MikaType.Caption,
                        color = MikaTheme.colors.ink2,
                    )
                    if (suggested != null) {
                        TextButton(
                            onClick = {
                                val matchingEntry = entries.firstOrNull { it.packageName == scannedPkg }
                                val entryForDraft = CapturePluginEntry(
                                    packageName = scannedPkg,
                                    profile = suggested,
                                    isBuiltin = matchingEntry?.isBuiltin ?: false,
                                    isOverridden = matchingEntry?.isOverridden ?: false,
                                )
                                invalidDraft = false
                                draft = CapturePluginDraft.fromEntry(entryForDraft).copy(isNew = matchingEntry == null)
                                discoveryOutcome = null
                            },
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            Text(stringResource(R.string.network_capture_plugins_scan_open_editor))
                        }
                    } else if (result.ok) {
                        Text(
                            stringResource(R.string.network_capture_plugins_scan_empty_hint),
                            style = MikaType.Caption,
                            color = MikaTheme.colors.warn,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                if (suggested != null) {
                    TextButton(
                        onClick = {
                            onSaveProfile(scannedPkg, suggested)
                            discoveryOutcome = null
                        },
                    ) {
                        Text(stringResource(R.string.network_capture_plugins_scan_apply_save))
                    }
                } else {
                    TextButton(onClick = { discoveryOutcome = null }) {
                        Text(stringResource(R.string.ok_got_it))
                    }
                }
            },
            dismissButton = if (suggested != null) {
                {
                    TextButton(onClick = { discoveryOutcome = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            } else null,
        )
    }

    // 开关状态以最新一份 packs 为准：弹窗里点开关后，手里那个对象就是旧的了。
    val detail = packDetail?.let { open -> packs.firstOrNull { it.id == open.id } ?: open }
    if (detail != null) {
        val info = detail.info
        MikaDialog(
            onDismissRequest = { packDetail = null },
            title = { Text(detail.displayTitle) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.network_capture_packs_declaration),
                        style = MikaType.Body,
                        color = MikaTheme.colors.ink,
                    )
                    Text(
                        stringResource(R.string.network_capture_packs_meta, info.version, info.apiVersion),
                        Modifier.padding(top = 2.dp),
                        style = MikaType.Caption,
                        color = MikaTheme.colors.ink3,
                    )
                    info.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, Modifier.padding(top = 10.dp), style = MikaType.Caption, color = MikaTheme.colors.ink2)
                    }
                    info.author?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            stringResource(R.string.network_capture_packs_author, it),
                            Modifier.padding(top = 6.dp),
                            style = MikaType.Hint,
                            color = MikaTheme.colors.ink3,
                        )
                    }
                    Text(
                        stringResource(R.string.network_capture_packs_scope, packScopeText(info)),
                        Modifier.padding(top = 10.dp),
                        style = MikaType.Caption,
                        color = MikaTheme.colors.ink2,
                    )
                    packChannelText(info).takeIf { it.isNotEmpty() }?.let {
                        Text(
                            stringResource(R.string.network_capture_packs_touches, it),
                            Modifier.padding(top = 6.dp),
                            style = MikaType.Caption,
                            color = MikaTheme.colors.ink2,
                        )
                    }
                    if (info.declares.isNotEmpty()) {
                        Text(
                            stringResource(R.string.network_capture_packs_notes_title),
                            Modifier.padding(top = 12.dp),
                            style = MikaType.Caption,
                            color = MikaTheme.colors.ink2,
                        )
                        info.declares.forEach { note ->
                            Text(
                                "• $note",
                                Modifier.padding(top = 4.dp),
                                style = MikaType.Hint,
                                color = MikaTheme.colors.ink3,
                            )
                        }
                    }
                    when {
                        info.error != null -> Text(
                            stringResource(R.string.network_capture_packs_error, info.error ?: "?"),
                            Modifier.padding(top = 12.dp),
                            style = MikaType.Caption,
                            color = MikaTheme.colors.bad,
                        )
                        info.disabledInFile -> Text(
                            stringResource(R.string.network_capture_packs_off_file),
                            Modifier.padding(top = 12.dp),
                            style = MikaType.Caption,
                            color = MikaTheme.colors.warn,
                        )
                        info.disabledByUser -> Text(
                            stringResource(R.string.network_capture_packs_off_user),
                            Modifier.padding(top = 12.dp),
                            style = MikaType.Caption,
                            color = MikaTheme.colors.warn,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(R.string.network_capture_packs_enabled),
                            Modifier.weight(1f),
                            style = MikaType.Body,
                            color = MikaTheme.colors.ink,
                        )
                        MikaSwitch(
                            checked = info.enabled,
                            onCheckedChange = { onTogglePack(info.id, it) },
                            enabled = info.error == null,
                        )
                    }
                    // 内置包在 assets 里，删了下次启动又回来——对它该做的是关掉上面的开关。
                    if (detail.isExternal) {
                        TextButton(
                            onClick = {
                                removingPack = detail
                                packDetail = null
                            },
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            Text(
                                stringResource(R.string.network_capture_packs_remove),
                                color = MikaTheme.colors.bad,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { packDetail = null }) {
                    Text(stringResource(R.string.done))
                }
            },
        )
    }

    val removing = removingPack
    if (removing != null) {
        MikaDialog(
            onDismissRequest = { removingPack = null },
            title = {
                Text(
                    stringResource(
                        R.string.network_capture_packs_remove_title,
                        removing.displayTitle,
                    ),
                )
            },
            text = { Text(stringResource(R.string.network_capture_packs_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removingPack = null
                        onRemovePack(removing.id)
                    },
                ) {
                    Text(
                        stringResource(R.string.network_capture_packs_remove),
                        color = MikaTheme.colors.bad,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { removingPack = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmingResetAll) {
        MikaDialog(
            onDismissRequest = { confirmingResetAll = false },
            title = { Text(stringResource(R.string.network_capture_plugins_reset_all_title)) },
            text = { Text(stringResource(R.string.network_capture_plugins_reset_all_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingResetAll = false
                        onResetAll()
                    },
                ) {
                    Text(
                        stringResource(R.string.network_capture_plugins_reset_all),
                        color = MikaTheme.colors.bad,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingResetAll = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * 一个插件包作用在哪些 App 上。兜底包（`packages: ["*"]`）直说"全部应用"，
 * 否则用户看到一行 `*` 不知道那是通配。
 *
 * 弹窗里用这一份：完整列出，逗号分隔，换行也没关系。
 */
@Composable
private fun packScopeText(info: ExternalCapturePluginLoader.PackInfo): String =
    if (info.fallback || info.packages.isEmpty()) {
        stringResource(R.string.network_capture_packs_scope_all)
    } else {
        info.packages.joinToString(", ")
    }

/**
 * 列表行里的作用范围，只留一行。
 *
 * 一行里塞进 `com.ss.android.ugc.aweme, com.ss.android.ugc.aweme.lite, …` 会撑成四五高，
 * 逗号还会单独占一行——列表要看的是"这个包是干什么的"，具体名单在弹窗里。
 */
@Composable
private fun packScopeSummary(info: ExternalCapturePluginLoader.PackInfo): String = when {
    info.fallback || info.packages.isEmpty() -> stringResource(R.string.network_capture_packs_scope_all)
    info.packages.size == 1 -> info.packages.first()
    else -> stringResource(R.string.network_capture_packs_scope_count, info.packages.size)
}

/**
 * 声明里"它会碰什么"那一栏：从 `plugin.json` 实际解析出来的通道，不是包自己说的。
 *
 * 和 [ExternalCapturePluginLoader.PackInfo.declares] 并排显示是有意的——自述可以写得很干净，
 * 通道计数不会。
 */
@Composable
private fun packChannelText(info: ExternalCapturePluginLoader.PackInfo): String = buildList {
    if (info.sslRules > 0) add(stringResource(R.string.network_capture_packs_channel_ssl, info.sslRules))
    if (info.cronetRules > 0) add(stringResource(R.string.network_capture_packs_channel_cronet, info.cronetRules))
    if (info.jniRules > 0) add(stringResource(R.string.network_capture_packs_channel_jni, info.jniRules))
    if (info.codecs > 0) add(stringResource(R.string.network_capture_packs_channel_codec, info.codecs))
    if (info.hostFix) add(stringResource(R.string.network_capture_packs_channel_host))
    if (info.hasCode) add(stringResource(R.string.network_capture_packs_channel_code))
}.joinToString(" · ")
