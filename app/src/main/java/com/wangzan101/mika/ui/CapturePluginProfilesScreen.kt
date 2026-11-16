package com.wangzan101.mika.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agent.use.common.network.plugin.EbpfCronetAbiRule
import com.agent.use.common.network.plugin.EbpfCronetOffsetRule
import com.agent.use.common.network.plugin.EbpfProbeProfile
import com.agent.use.common.network.plugin.EbpfSslOffsetRule
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.cap.phone.network.CapturePluginEntry
import com.wangzan101.mika.cap.phone.network.CapturePluginProfileStore
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        if (quickOffsetsEnabled && !hasSsl && !hasCronet && abiRule == null) {
            return null
        }

        return EbpfProbeProfile(
            sslOffsetRules = sslRules,
            cronetOffsetRules = cronetRules,
            cronetAbiRule = abiRule,
            replaceBuiltinOffsets = isBuiltinEntry || !quickOffsetsEnabled,
        )
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
        return parts.joinToString("\n")
    }
}

/**
 * 设置 › 高级与其他 › 端侧网络抓包 › eBPF 探测规则：展示并配置小红书 `libcronet.114.0.5735.38.so` 等快速偏移与 ABI 参数。
 */
@Composable
fun CapturePluginProfilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { CapturePluginProfileStore.forContext(context) }
    var entries by remember { mutableStateOf(emptyList<CapturePluginEntry>()) }
    var installedApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    fun refreshEntries() {
        entries = store.snapshotEntries()
    }

    LaunchedEffect(store) {
        store.loadIntoRegistry()
        refreshEntries()
        installedApps = withContext(Dispatchers.IO) {
            runCatching { launchableApps(context.packageManager, context.packageName) }.getOrDefault(emptyList())
        }
    }

    CapturePluginProfilesContent(
        entries = entries,
        installedApps = installedApps,
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
    )
}

@Composable
fun CapturePluginProfilesContent(
    entries: List<CapturePluginEntry>,
    installedApps: List<Pair<String, String>> = emptyList(),
    onBack: () -> Unit,
    onSaveProfile: (packageName: String, profile: EbpfProbeProfile) -> Unit = { _, _ -> },
    onResetPackage: (packageName: String) -> Unit = {},
    onResetAll: () -> Unit = {},
) {
    val appLabels = remember(installedApps) { installedApps.toMap() }
    var draft by remember { mutableStateOf<CapturePluginDraft?>(null) }
    var invalidDraft by remember { mutableStateOf(false) }
    var pickingApp by remember { mutableStateOf(false) }
    var appSearch by remember { mutableStateOf("") }
    var confirmingResetAll by remember { mutableStateOf(false) }

    val genericName = stringResource(R.string.network_capture_plugins_generic_name)
    fun displayTitle(entry: CapturePluginEntry): String = when (entry.packageName) {
        "*" -> genericName
        "com.xingin.xhs" -> (appLabels[entry.packageName]?.takeIf { it.isNotBlank() } ?: "小红书") + " (com.xingin.xhs)"
        else -> appLabels[entry.packageName]?.takeIf { it.isNotBlank() }?.let { "$it (${entry.packageName})" } ?: entry.packageName
    }

    PageScaffold(stringResource(R.string.network_capture_plugins_title), onBack) {
        GroupLabel(stringResource(R.string.network_capture_plugins_actions))
        GroupCard {
            ListRow(
                MikaIcons.Plus,
                stringResource(R.string.network_capture_plugins_add),
                first = true,
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
                    offsetsDisabled -> stringResource(R.string.network_capture_plugins_tag_disabled) to ValueTone.Warn
                    entry.isOverridden -> stringResource(R.string.network_capture_plugins_tag_custom) to ValueTone.Ok
                    else -> stringResource(R.string.network_capture_plugins_tag_builtin) to ValueTone.Plain
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowValue(badge, tone)
                        RowChevron()
                    }
                }
            }
        }

        FooterNote(stringResource(R.string.network_capture_plugins_footer))
    }

    val currentDraft = draft
    if (currentDraft != null && !pickingApp) {
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
                                    pickingApp = true
                                },
                            ) {
                                Text(stringResource(R.string.settings_jump_confirm_profiles_pick_app))
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                stringResource(R.string.network_capture_plugins_quick_toggle),
                                style = MikaType.Body,
                                color = MikaTheme.colors.ink,
                            )
                            Text(
                                stringResource(R.string.network_capture_plugins_quick_toggle_sub),
                                style = MikaType.Caption,
                                color = MikaTheme.colors.ink3,
                            )
                        }
                        Switch(
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

                    if (invalidDraft) {
                        Text(
                            stringResource(R.string.network_capture_plugins_invalid),
                            color = MaterialTheme.colorScheme.error,
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

    if (currentDraft != null && pickingApp) {
        val filtered = remember(installedApps, appSearch) {
            val q = appSearch.trim()
            if (q.isEmpty()) installedApps
            else installedApps.filter { (pkg, lbl) ->
                pkg.contains(q, ignoreCase = true) || lbl.contains(q, ignoreCase = true)
            }
        }
        MikaDialog(
            onDismissRequest = { pickingApp = false },
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
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        filtered.forEach { (pkg, label) ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        invalidDraft = false
                                        draft = currentDraft.copy(packageName = pkg)
                                        pickingApp = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                            ) {
                                Text(label, style = MikaType.Body, color = MikaTheme.colors.ink)
                                Text(pkg, style = MikaType.Caption, color = MikaTheme.colors.ink3)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingApp = false }) {
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
