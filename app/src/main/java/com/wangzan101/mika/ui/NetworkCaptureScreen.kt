package com.wangzan101.mika.ui

import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agent.use.common.network.CaptureMode
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.network.CapturePluginProfileStore
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置 › 高级与其他 › 端侧网络抓包 的文案辅助。
 */
object NetworkCaptureCopy {
    fun entryValue(enabled: Boolean, mode: CaptureMode): String =
        if (!enabled) tr(R.string.state_off) else modeShortLabel(mode)

    fun modeLabel(mode: CaptureMode): String = tr(
        when (mode) {
            CaptureMode.AUTO -> R.string.network_capture_mode_auto
            CaptureMode.EBPF -> R.string.network_capture_mode_ebpf
            CaptureMode.VPN_SERVICE -> R.string.network_capture_mode_vpn
        },
    )

    fun modeShortLabel(mode: CaptureMode): String = tr(
        when (mode) {
            CaptureMode.AUTO -> R.string.network_capture_mode_auto_short
            CaptureMode.EBPF -> R.string.network_capture_mode_ebpf_short
            CaptureMode.VPN_SERVICE -> R.string.network_capture_mode_vpn_short
        },
    )

    fun modeDetail(mode: CaptureMode): String = tr(
        when (mode) {
            CaptureMode.AUTO -> R.string.network_capture_mode_auto_sub
            CaptureMode.EBPF -> R.string.network_capture_mode_ebpf_sub
            CaptureMode.VPN_SERVICE -> R.string.network_capture_mode_vpn_sub
        },
    )
}

/**
 * 设置 › 高级与其他 › 端侧网络抓包：总开关、模式选择（Auto / eBPF / VpnService）、VPN 一键授权、运行状态与缓存管理。
 */
@Composable
fun NetworkCaptureScreen(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    mode: CaptureMode,
    onMode: (CaptureMode) -> Unit,
    onBack: () -> Unit,
    onOpenPlugins: () -> Unit = {},
) {
    val context = LocalContext.current
    val network = remember(context) { PhoneServices.of(context).network }
    val pluginStore = remember(context) { CapturePluginProfileStore.forContext(context) }
    var ebpfSupported by remember { mutableStateOf<Boolean?>(null) }
    var vpnGranted by remember { mutableStateOf(VpnService.prepare(context) == null) }
    var cacheCount by remember { mutableIntStateOf(network.cachedHistoryCount()) }
    var pluginEntries by remember { mutableStateOf(emptyList<com.wangzan101.mika.cap.phone.network.CapturePluginEntry>()) }
    val isCapturing = network.isCapturing()
    val currentPkg = network.currentPackage
    val activeMode = network.getActiveMode()
    val lastError = network.lastCaptureError()

    LaunchedEffect(Unit) {
        ebpfSupported = withContext(Dispatchers.IO) { runCatching { network.isEbpfSupported() }.getOrDefault(false) }
        vpnGranted = VpnService.prepare(context) == null
        cacheCount = network.cachedHistoryCount()
        pluginStore.loadIntoRegistry()
        pluginEntries = pluginStore.snapshotEntries()
    }

    val vpnAuthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vpnGranted = VpnService.prepare(context) == null
    }

    val builtinCount = pluginEntries.count { it.isBuiltin }
    val customCount = pluginEntries.count { it.isOverridden }
    val xhsLib = pluginEntries.firstOrNull { it.packageName == "com.xingin.xhs" }
        ?.let { it.profile.cronetOffsetRules.firstOrNull()?.libSubstring ?: it.profile.sslOffsetRules.firstOrNull()?.libSubstring }
        ?: "ELF"

    NetworkCaptureContent(
        enabled = enabled,
        onEnabled = { on ->
            if (!on) network.detach()
            onEnabled(on)
        },
        mode = mode,
        onMode = onMode,
        ebpfSupported = ebpfSupported,
        vpnGranted = vpnGranted,
        onRequestVpnPermission = {
            val intent = VpnService.prepare(context)
            if (intent != null) {
                vpnAuthLauncher.launch(intent)
            } else {
                vpnGranted = true
            }
        },
        activeSummary = if (isCapturing && !currentPkg.isNullOrBlank()) {
            stringResource(R.string.network_capture_active, currentPkg, NetworkCaptureCopy.modeShortLabel(activeMode))
        } else {
            null
        },
        lastError = if (!isCapturing) lastError else null,
        cacheCount = cacheCount,
        onClearCache = {
            network.clearHistory()
            cacheCount = 0
        },
        pluginSummary = if (pluginEntries.isNotEmpty()) {
            stringResource(R.string.network_capture_plugins_row_summary, builtinCount, customCount, xhsLib)
        } else {
            null
        },
        onOpenPlugins = onOpenPlugins,
        onBack = onBack,
    )
}

/** 无状态页面内容，便于测试与预览。 */
@Composable
fun NetworkCaptureContent(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    mode: CaptureMode,
    onMode: (CaptureMode) -> Unit,
    ebpfSupported: Boolean?,
    vpnGranted: Boolean,
    onRequestVpnPermission: () -> Unit,
    activeSummary: String?,
    cacheCount: Int,
    onClearCache: () -> Unit,
    onBack: () -> Unit,
    lastError: String? = null,
    pluginSummary: String? = null,
    onOpenPlugins: (() -> Unit)? = null,
) {
    PageScaffold(stringResource(R.string.network_capture_title), onBack) {
        GroupCard {
            ListRow(
                MikaIcons.Globe,
                stringResource(R.string.network_capture_enable),
                stringResource(R.string.network_capture_enable_sub),
                first = true,
                onClick = { onEnabled(!enabled) },
            ) {
                MikaSwitch(enabled, onEnabled, Modifier.testTag("network-capture-enabled"))
            }
        }

        GroupLabel(stringResource(R.string.network_capture_mode_group))
        GroupCard {
            CaptureMode.entries.forEachIndexed { index, option ->
                RadioRow(
                    title = NetworkCaptureCopy.modeLabel(option),
                    selected = mode == option,
                    onClick = { onMode(option) },
                    subtitle = NetworkCaptureCopy.modeDetail(option),
                    first = index == 0,
                    enabled = enabled,
                )
            }
        }

        GroupLabel(stringResource(R.string.network_capture_status_group))
        GroupCard {
            val (ebpfText, ebpfTone) = when (ebpfSupported) {
                null -> stringResource(R.string.network_capture_checking) to ValueTone.Plain
                true -> stringResource(R.string.network_capture_supported) to ValueTone.Ok
                false -> stringResource(R.string.network_capture_unsupported) to ValueTone.Plain
            }
            ListRow(
                MikaIcons.Key,
                stringResource(R.string.network_capture_ebpf_status),
                first = true,
            ) {
                RowValue(ebpfText, ebpfTone)
            }

            ListRow(
                MikaIcons.Shield,
                stringResource(R.string.network_capture_vpn_permission),
                stringResource(R.string.network_capture_vpn_permission_sub),
                tone = if (vpnGranted || ebpfSupported == true) TileTone.Neutral else TileTone.Warn,
                onClick = if (vpnGranted) null else onRequestVpnPermission,
            ) {
                if (vpnGranted) {
                    RowValue(stringResource(R.string.network_capture_vpn_granted), ValueTone.Ok)
                } else {
                    RowValue(stringResource(R.string.network_capture_vpn_grant_action), ValueTone.Link)
                }
            }

            ListRow(
                MikaIcons.Refresh,
                stringResource(R.string.network_capture_live_status),
                activeSummary ?: lastError ?: stringResource(R.string.network_capture_idle),
            ) {
                RowValue(
                    when {
                        activeSummary != null -> stringResource(R.string.state_on)
                        !lastError.isNullOrBlank() -> lastError
                        else -> stringResource(R.string.network_capture_idle)
                    },
                    when {
                        activeSummary != null -> ValueTone.Ok
                        !lastError.isNullOrBlank() -> ValueTone.Bad
                        else -> ValueTone.Plain
                    },
                )
            }

            ListRow(
                MikaIcons.Trash,
                stringResource(R.string.network_capture_cache_title),
                stringResource(R.string.network_capture_cache_count, cacheCount),
            ) {
                if (cacheCount > 0) {
                    Text(
                        stringResource(R.string.network_capture_cache_clear),
                        Modifier.clickable(onClick = onClearCache).padding(4.dp),
                        style = MikaType.Value,
                        color = MikaTheme.colors.bad,
                    )
                }
            }
        }

        if (onOpenPlugins != null) {
            GroupLabel(stringResource(R.string.network_capture_plugins_group))
            GroupCard {
                ListRow(
                    MikaIcons.Apps,
                    stringResource(R.string.network_capture_plugins_row_title),
                    pluginSummary,
                    first = true,
                    onClick = onOpenPlugins,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowChevron()
                    }
                }
            }
        }

        FooterNote(stringResource(R.string.network_capture_footer))
    }
}
