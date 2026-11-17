package com.wangzan101.mika.ui

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.atlas.FileAtlasStore
import com.wangzan101.mika.core.atlas.AtlasShare
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaNoticeDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RowActions
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.SearchField
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.components.mikaPress
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 「App 经验」页面入口：列表页与单个 App 详情页。
 * 详情页由 [vm] 维护选中包名，系统返回键先回到列表页，再退出到上一级设置页。
 */
@Composable
fun AppExperienceScreen(
    vm: AppExperienceViewModel,
    onPrepareShareUri: suspend (String) -> Uri?,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    val detail = ui.selectedDetail
    if (ui.selectedPackage != null && detail != null) {
        BackHandler { vm.closeApp() }
        AppExperienceDetailScreen(
            detail = detail,
            vm = vm,
            onPrepareShareUri = onPrepareShareUri,
            onBack = { vm.closeApp() },
        )
    } else {
        BackHandler(onBack = onBack)
        AppExperienceListScreen(
            ui = ui,
            vm = vm,
            onBack = onBack,
        )
    }

    ui.message?.let { msg ->
        MikaNoticeDialog(msg, onDismiss = vm::dismissMessage)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppExperienceListScreen(
    ui: AppExperienceUi,
    vm: AppExperienceViewModel,
    onBack: () -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }

    SubPage(
        title = stringResource(R.string.app_exp_title),
        onBack = onBack,
        right = if (ui.apps.isNotEmpty()) ({
            BarIconButton(MikaIcons.Search, stringResource(R.string.search), {
                searching = !searching
                if (!searching) vm.search("")
            })
        }) else null,
    ) {
        val c = MikaTheme.colors
        ui.brokenNotice?.let {
            Text(
                stringResource(R.string.app_exp_broken_notice),
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .background(c.warnSoft, MikaShapes.Card)
                    .border(0.75.dp, c.warnSolid.copy(alpha = 0.28f), MikaShapes.Card)
                    .padding(16.dp),
                style = MikaType.Caption,
                color = c.warn,
            )
        }

        if (searching || ui.query.isNotEmpty()) {
            SearchField(
                value = ui.query,
                onValueChange = vm::search,
                placeholder = stringResource(R.string.app_exp_search_hint),
                modifier = Modifier.testTag("app-exp-search"),
            )
            Spacer(Modifier.height(10.dp))
        }

        FlowRow(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MikaButton(
                text = stringResource(R.string.app_exp_import),
                onClick = vm::openImportDialog,
                kind = ButtonKind.Secondary,
                size = ButtonSize.Small,
                modifier = Modifier.testTag("app-exp-import"),
            )
            if (ui.apps.isNotEmpty()) {
                DangerPill(
                    text = stringResource(R.string.app_exp_clear_all),
                    onClick = { confirmClearAll = true },
                    modifier = Modifier.testTag("app-exp-clear-all"),
                )
            }
        }

        when {
            ui.loading && ui.apps.isEmpty() -> {
                EmptyNote(stringResource(R.string.loading))
            }
            ui.apps.isEmpty() -> {
                MikaCard {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.app_exp_empty), style = MikaType.BodyStrong, color = c.ink)
                        Text(stringResource(R.string.app_exp_empty_sub), style = MikaType.Caption, color = c.ink3)
                    }
                }
            }
            ui.filteredApps.isEmpty() -> {
                EmptyNote(stringResource(R.string.not_found))
            }
            else -> {
                GroupLabel(trPlural(R.plurals.app_exp_apps_count, ui.filteredApps.size))
                GroupCard {
                    ui.filteredApps.forEachIndexed { index, item ->
                        AppExperienceRow(
                            item = item,
                            first = index == 0,
                            onClick = { vm.openApp(item.packageName) },
                        )
                    }
                }
            }
        }
    }

    ui.importState?.let { imp ->
        ImportExperienceDialog(
            state = imp,
            onDismiss = vm::dismissImportDialog,
            onTextChange = vm::updateImportText,
            onPickUri = { resolver, uri, name -> vm.inspectImportUri(resolver, uri, name) },
            onConflictMode = vm::setImportConflictMode,
            onConfirm = vm::confirmImport,
            onNoticeError = vm::setImportError,
        )
    }

    if (confirmClearAll) {
        MikaDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text(stringResource(R.string.app_exp_clear_all_title)) },
            text = { Text(stringResource(R.string.app_exp_clear_all_body)) },
            confirmButton = {
                TextButton({
                    confirmClearAll = false
                    vm.clearAll()
                }) { Text(stringResource(R.string.clear), color = MikaTheme.colors.bad) }
            },
            dismissButton = {
                TextButton({ confirmClearAll = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun appSummaryCountsText(
    screenCount: Int,
    routeCount: Int,
    shortcutCount: Int,
    knowledgeCount: Int,
): String = buildList {
    add(trPlural(R.plurals.app_exp_pages_count, screenCount))
    add(trPlural(R.plurals.app_exp_routes_count, routeCount))
    if (shortcutCount > 0) add(trPlural(R.plurals.app_exp_shortcuts_count, shortcutCount))
    add(trPlural(R.plurals.app_exp_knowledge_count, knowledgeCount))
}.joinToString(" · ")

@Composable
private fun AppExperienceRow(
    item: AppExperienceItem,
    first: Boolean,
    onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val counts = appSummaryCountsText(
        screenCount = item.screenCount,
        routeCount = item.routeCount,
        shortcutCount = item.shortcutCount,
        knowledgeCount = item.knowledgeCount,
    )
    val subtitle = if (item.appLabel != item.packageName) {
        "${item.packageName}\n$counts"
    } else {
        counts
    }
    val lastUsed = item.lastUsedMs.takeIf { it > 0L }?.let { ms ->
        tr(R.string.app_exp_last_used, formatShortDate(ms))
    }

    Row(
        Modifier
            .fillMaxWidth()
            .topRowHairline(first, start = 70.dp, color = c.hair)
            .mikaPress(interaction, pressedScale = 0.988f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppIconBadge(item.packageName, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(item.appLabel, style = MikaType.Body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
            if (lastUsed != null) {
                Text(lastUsed, Modifier.padding(top = 2.dp), style = MikaType.Hint, color = c.ink3)
            }
        }
        RowChevron()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppExperienceDetailScreen(
    detail: AppExperienceDetail,
    vm: AppExperienceViewModel,
    onPrepareShareUri: suspend (String) -> Uri?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pkg = detail.summary.packageName
    var expandedScreenId by rememberSaveable(pkg) { mutableStateOf<String?>(null) }
    var expandedKnowledgeId by rememberSaveable(pkg) { mutableStateOf<String?>(null) }
    var confirmClearApp by remember { mutableStateOf(false) }
    var deletingRoute by remember { mutableStateOf<AtlasRouteItem?>(null) }
    var deletingShortcut by remember { mutableStateOf<AtlasShortcutItem?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) vm.exportToUri(context.contentResolver, uri, pkg)
    }

    SubPage(title = detail.summary.appLabel, onBack = onBack) {
        val c = MikaTheme.colors
        MikaCard(Modifier.padding(bottom = 12.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AppIconBadge(pkg, size = 46.dp)
                    Column(Modifier.weight(1f)) {
                        Text(detail.summary.appLabel, style = MikaType.Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pkg, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val metaParts = buildList {
                            add(
                                appSummaryCountsText(
                                    screenCount = detail.summary.screenCount,
                                    routeCount = detail.summary.routeCount,
                                    shortcutCount = detail.summary.shortcutCount,
                                    knowledgeCount = detail.summary.knowledgeCount,
                                ),
                            )
                            if (detail.summary.versionsSeen.isNotEmpty()) {
                                add(tr(R.string.app_exp_versions, detail.summary.versionsSeen.joinToString(", ")))
                            }
                            if (detail.summary.lastUsedMs > 0L) {
                                add(tr(R.string.app_exp_last_used, formatShortDate(detail.summary.lastUsedMs)))
                            }
                        }
                        Text(metaParts.joinToString("\n"), Modifier.padding(top = 4.dp), style = MikaType.Hint, color = c.ink3)
                    }
                }

                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MikaButton(
                        text = stringResource(R.string.app_exp_share),
                        onClick = {
                            scope.launch {
                                val uri = vm.prepareSharePackage(pkg, onPrepareShareUri)
                                if (uri == null) {
                                    vm.showNotice(tr(R.string.app_exp_share_nothing))
                                    return@launch
                                }
                                val send = Intent(Intent.ACTION_SEND)
                                    .setType("application/json")
                                    .putExtra(Intent.EXTRA_SUBJECT, detail.summary.appLabel)
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                try {
                                    context.startActivity(Intent.createChooser(send, detail.summary.appLabel))
                                } catch (_: ActivityNotFoundException) {
                                    vm.showNotice(tr(R.string.app_exp_no_share_app))
                                }
                            }
                        },
                        kind = ButtonKind.Secondary,
                        size = ButtonSize.Small,
                        enabled = detail.canExport,
                        modifier = Modifier.testTag("app-exp-share"),
                    )
                    MikaButton(
                        text = stringResource(R.string.app_exp_export),
                        onClick = {
                            exportLauncher.launch("${FileAtlasStore.sanitizePackageName(pkg)}-atlas.json")
                        },
                        kind = ButtonKind.Tonal,
                        size = ButtonSize.Small,
                        enabled = detail.canExport,
                        modifier = Modifier.testTag("app-exp-export"),
                    )
                    if (detail.summary.screenCount > 0 || detail.summary.routeCount > 0) {
                        DangerPill(
                            text = stringResource(R.string.app_exp_clear_app),
                            onClick = { confirmClearApp = true },
                            modifier = Modifier.testTag("app-exp-clear-app"),
                        )
                    }
                }
            }
        }

        // ---- 1. 页面与跳转 (App Atlas) ----
        GroupLabel("${stringResource(R.string.app_exp_section_pages)} · ${detail.screens.size}")
        GroupCard {
            if (detail.screens.isEmpty()) {
                EmptyNote(stringResource(R.string.app_exp_pages_empty))
            } else {
                detail.screens.forEachIndexed { index, screen ->
                    val expanded = expandedScreenId == screen.sigId
                    AtlasScreenRow(
                        screen = screen,
                        first = index == 0,
                        expanded = expanded,
                        onToggle = { expandedScreenId = if (expanded) null else screen.sigId },
                    )
                }
            }
        }

        // ---- 2. 操作路线 (Routes) ----
        GroupLabel("${stringResource(R.string.app_exp_section_routes)} · ${detail.routes.size}")
        GroupCard {
            if (detail.routes.isEmpty()) {
                EmptyNote(stringResource(R.string.app_exp_routes_empty))
            } else {
                detail.routes.forEachIndexed { index, route ->
                    AtlasRouteRow(
                        route = route,
                        first = index == 0,
                        onToggleDisabled = { disabled -> vm.toggleRouteDisabled(route.id, disabled) },
                        onDelete = { deletingRoute = route },
                    )
                }
            }
        }

        // ---- 3. 子流程快捷 (Shortcuts) ----
        GroupLabel("${stringResource(R.string.app_exp_section_shortcuts)} · ${detail.shortcuts.size}")
        GroupCard {
            if (detail.shortcuts.isEmpty()) {
                EmptyNote(stringResource(R.string.app_exp_shortcuts_empty))
            } else {
                detail.shortcuts.forEachIndexed { index, shortcut ->
                    AtlasShortcutRow(
                        shortcut = shortcut,
                        first = index == 0,
                        onToggleDisabled = { disabled -> vm.toggleShortcutDisabled(pkg, shortcut.id, disabled) },
                        onDelete = { deletingShortcut = shortcut },
                    )
                }
            }
        }

        // ---- 4. 相关知识与技能 ----
        GroupLabel("${stringResource(R.string.app_exp_section_knowledge)} · ${detail.knowledgeItems.size}")
        GroupCard {
            if (detail.knowledgeItems.isEmpty()) {
                EmptyNote(stringResource(R.string.app_exp_knowledge_empty))
            } else {
                detail.knowledgeItems.forEachIndexed { index, item ->
                    val expanded = expandedKnowledgeId == item.id
                    RelatedKnowledgeRow(
                        item = item,
                        first = index == 0,
                        expanded = expanded,
                        onToggle = { expandedKnowledgeId = if (expanded) null else item.id },
                    )
                }
            }
        }
    }

    if (confirmClearApp) {
        MikaDialog(
            onDismissRequest = { confirmClearApp = false },
            title = { Text(stringResource(R.string.app_exp_clear_app_title, detail.summary.appLabel)) },
            text = { Text(stringResource(R.string.app_exp_clear_app_body)) },
            confirmButton = {
                TextButton({
                    confirmClearApp = false
                    vm.clearApp(pkg) { vm.closeApp() }
                }) { Text(stringResource(R.string.clear), color = MikaTheme.colors.bad) }
            },
            dismissButton = {
                TextButton({ confirmClearApp = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    deletingRoute?.let { r ->
        ConfirmDeleteFlowDialog(
            title = stringResource(R.string.app_exp_route_delete_title),
            body = stringResource(R.string.app_exp_route_delete_body, r.template),
            onDismiss = { deletingRoute = null },
            onConfirm = {
                deletingRoute = null
                vm.removeRoute(r.id)
            },
        )
    }

    deletingShortcut?.let { s ->
        ConfirmDeleteFlowDialog(
            title = stringResource(R.string.app_exp_shortcut_delete_title),
            body = stringResource(R.string.app_exp_shortcut_delete_body, s.name),
            onDismiss = { deletingShortcut = null },
            onConfirm = {
                deletingShortcut = null
                vm.removeShortcut(pkg, s.id)
            },
        )
    }
}

@Composable
private fun AtlasScreenRow(
    screen: AtlasScreenItem,
    first: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val c = MikaTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val subtitle = buildList {
        screen.shortActivity?.takeIf { it != screen.displayTitle }?.let(::add)
        add(stringResource(R.string.app_exp_hits, screen.hits))
        screen.appVersion?.let { add("v$it") }
    }.joinToString(" · ")

    Column(Modifier.fillMaxWidth().topRowHairline(first, start = 16.dp, color = c.hair)) {
        Row(
            Modifier
                .fillMaxWidth()
                .mikaPress(interaction, pressedScale = 0.988f)
                .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(screen.displayTitle, style = MikaType.Body, color = c.ink)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
                }
            }
            if (screen.unverifiedAfterUpgrade) {
                StatusPill(stringResource(R.string.app_exp_unverified), PillTone.Warn)
            }
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .background(c.glassSubtle, SubPanelShape)
                    .border(0.5.dp, c.glassEdge, SubPanelShape)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.app_exp_controls), style = MikaType.Label, color = c.ink2)
                if (screen.controls.isEmpty()) {
                    Text(stringResource(R.string.app_exp_controls_empty), style = MikaType.Hint, color = c.ink3)
                } else {
                    screen.controls.forEach { ctrl ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                ctrl.targetHint?.let { "${ctrl.label} ($it)" } ?: ctrl.label,
                                style = MikaType.Caption,
                                color = c.ink,
                            )
                            val stats = buildList {
                                add(stringResource(R.string.app_exp_control_stats, ctrl.used))
                                if (ctrl.noEffect > 0) add(stringResource(R.string.app_exp_control_no_effect, ctrl.noEffect))
                            }.joinToString(" · ")
                            Text(stats, style = MikaType.Hint, color = if (ctrl.noEffect > 0) c.warn else c.ink3)
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.app_exp_edges), style = MikaType.Label, color = c.ink2)
                if (screen.outgoingEdges.isEmpty()) {
                    Text(stringResource(R.string.app_exp_edges_empty), style = MikaType.Hint, color = c.ink3)
                } else {
                    screen.outgoingEdges.forEach { edge ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val targetPart = edge.targetDesc?.let { " 「$it」" }.orEmpty()
                            Text(
                                "${edge.toolLabel}$targetPart → ${edge.toScreenTitle}",
                                style = MikaType.Caption,
                                color = c.ink,
                            )
                            Text(
                                stringResource(R.string.app_exp_edge_counts, edge.ok, edge.miss),
                                style = MikaType.Hint,
                                color = if (edge.miss > 0) c.warn else c.ok,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDeleteFlowDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onConfirm) { Text(stringResource(R.string.confirm_delete), color = MikaTheme.colors.bad) }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun missDetailText(consecutiveMisses: Int, missStep: Int?): String? {
    if (consecutiveMisses <= 0 && missStep == null) return null
    return buildList {
        if (consecutiveMisses > 0) add(stringResource(R.string.app_exp_route_misses, consecutiveMisses))
        missStep?.let { add(stringResource(R.string.app_exp_route_miss_step, it + 1)) }
    }.joinToString(" · ")
}

@Composable
private fun ToggleableFlowRow(
    title: String,
    subtitle: String,
    missDetail: String?,
    first: Boolean,
    disabled: Boolean,
    tagPrefix: String,
    onToggleDisabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val c = MikaTheme.colors
    Column {
        ListRow(
            icon = null,
            title = title,
            subtitle = subtitle,
            first = first,
            detail = missDetail,
            detailColor = c.warn,
        ) {
            MikaSwitch(
                checked = !disabled,
                onCheckedChange = { enabled -> onToggleDisabled(!enabled) },
                modifier = Modifier.testTag("$tagPrefix-switch"),
            )
        }
        RowActions {
            MikaButton(
                text = stringResource(R.string.confirm_delete),
                onClick = onDelete,
                kind = ButtonKind.Tonal,
                size = ButtonSize.Small,
                modifier = Modifier.testTag("$tagPrefix-delete"),
            )
        }
    }
}

@Composable
private fun AtlasRouteRow(
    route: AtlasRouteItem,
    first: Boolean,
    onToggleDisabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val subtitle = buildList {
        add(stringResource(R.string.app_exp_route_steps_ok, route.stepCount, route.successes))
        if (route.disabled) add(stringResource(R.string.app_exp_route_disabled))
        if (route.lastUsedMs > 0L) add(formatShortDate(route.lastUsedMs))
    }.joinToString(" · ")
    ToggleableFlowRow(
        title = route.template,
        subtitle = subtitle,
        missDetail = missDetailText(route.consecutiveMisses, route.missStep),
        first = first,
        disabled = route.disabled,
        tagPrefix = "route-${route.id}",
        onToggleDisabled = onToggleDisabled,
        onDelete = onDelete,
    )
}

@Composable
private fun AtlasShortcutRow(
    shortcut: AtlasShortcutItem,
    first: Boolean,
    onToggleDisabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val stats = buildList {
        add(stringResource(R.string.app_exp_route_steps_ok, shortcut.stepCount, shortcut.successes))
        if (shortcut.slotCount > 0) add(trPlural(R.plurals.app_exp_shortcut_slots_count, shortcut.slotCount))
        shortcut.entryScreenTitle?.let { add(stringResource(R.string.app_exp_shortcut_entry, it)) }
        shortcut.appVersion?.let { add("v$it") }
        if (shortcut.disabled) add(stringResource(R.string.app_exp_route_disabled))
        if (shortcut.lastUsedMs > 0L) add(formatShortDate(shortcut.lastUsedMs))
    }.joinToString(" · ")
    val subtitle = if (shortcut.description.isNotBlank()) "${shortcut.description}\n$stats" else stats
    ToggleableFlowRow(
        title = shortcut.name,
        subtitle = subtitle,
        missDetail = missDetailText(shortcut.consecutiveMisses, shortcut.missStep),
        first = first,
        disabled = shortcut.disabled,
        tagPrefix = "shortcut-${shortcut.id}",
        onToggleDisabled = onToggleDisabled,
        onDelete = onDelete,
    )
}

@Composable
private fun RelatedKnowledgeRow(
    item: RelatedKnowledgeItem,
    first: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val c = MikaTheme.colors
    val kindLabel = stringResource(
        when (item.kind) {
            RelatedKnowledgeKind.PersonalSkill -> R.string.app_exp_kind_skill
            RelatedKnowledgeKind.AppKnowledge -> R.string.app_exp_kind_knowledge
        },
    )
    val subtitle = listOfNotNull(kindLabel, item.subtitle).joinToString(" · ")
    val (stageLabel, stageTone) = when (item.stage) {
        RelatedKnowledgeStage.Active -> stringResource(R.string.app_exp_stage_active) to PillTone.Ok
        RelatedKnowledgeStage.Verified -> stringResource(R.string.app_exp_stage_verified) to PillTone.Ok
        RelatedKnowledgeStage.Trial -> stringResource(R.string.app_exp_stage_trial) to PillTone.Accent
        RelatedKnowledgeStage.Draft -> stringResource(R.string.app_exp_stage_draft) to PillTone.Warn
        RelatedKnowledgeStage.Paused -> stringResource(R.string.app_exp_stage_paused) to PillTone.Neutral
    }

    Column {
        ListRow(
            icon = null,
            title = item.title,
            subtitle = subtitle,
            first = first,
            onClick = onToggle,
        ) {
            StatusPill(stageLabel, stageTone)
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .background(c.glassSubtle, SubPanelShape)
                    .border(0.5.dp, c.glassEdge, SubPanelShape)
                    .padding(10.dp),
            ) {
                Text(item.markdown, style = MikaType.Hint, color = c.ink2)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportExperienceDialog(
    state: AppExperienceImportState,
    onDismiss: () -> Unit,
    onTextChange: (String) -> Unit,
    onPickUri: (android.content.ContentResolver, Uri, String?) -> Unit,
    onConflictMode: (AtlasShare.ConflictMode) -> Unit,
    onConfirm: () -> Unit,
    onNoticeError: (String) -> Unit,
) {
    val context = LocalContext.current
    val c = MikaTheme.colors
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val label = queryDisplayName(context, uri)
            onPickUri(context.contentResolver, uri, label)
        }
    }

    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_exp_import_title)) },
        text = {
            KeyboardDismissArea {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.app_exp_import_hint), style = MikaType.Hint, color = c.ink3)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MikaButton(
                            text = stringResource(R.string.app_exp_import_paste),
                            onClick = {
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                if (clip.isBlank()) {
                                    onNoticeError(tr(R.string.app_exp_clipboard_empty))
                                } else {
                                    onTextChange(clip)
                                }
                            },
                            kind = ButtonKind.Tonal,
                            size = ButtonSize.Small,
                        )
                        MikaButton(
                            text = stringResource(R.string.app_exp_import_file),
                            onClick = { pickFile.launch(arrayOf("application/json", "text/*", "*/*")) },
                            kind = ButtonKind.Tonal,
                            size = ButtonSize.Small,
                        )
                    }

                    state.fileLabel?.takeIf { it.isNotBlank() && state.fromFile }?.let { fileName ->
                        Text(fileName, style = MikaType.Caption, color = c.accent)
                    }

                    OutlinedTextField(
                        value = state.text,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp).keepsKeyboard().testTag("app-exp-import-text"),
                        label = { Text(stringResource(R.string.app_exp_import_json_label)) },
                    )

                    state.preview?.let { preview ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(c.glassSubtle, SubPanelShape)
                                .border(0.5.dp, c.glassEdge, SubPanelShape)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val basePreview = stringResource(
                                R.string.app_exp_import_preview,
                                preview.appLabel,
                                preview.packageName,
                                trPlural(R.plurals.app_exp_pages_count, preview.screenCount),
                                trPlural(R.plurals.app_exp_routes_count, preview.routeCount),
                            )
                            val previewText = if (preview.shortcutCount > 0) {
                                "$basePreview · ${trPlural(R.plurals.app_exp_shortcuts_count, preview.shortcutCount)}"
                            } else {
                                basePreview
                            }
                            Text(
                                previewText,
                                style = MikaType.Caption,
                                color = c.ok,
                            )
                            if (preview.hasLocalConflict) {
                                Text(stringResource(R.string.app_exp_import_conflict_hint), style = MikaType.Hint, color = c.ink2)
                                val modes = listOf(AtlasShare.ConflictMode.Merge, AtlasShare.ConflictMode.Replace)
                                SegmentedControl(
                                    options = listOf(
                                        stringResource(R.string.app_exp_import_conflict_merge),
                                        stringResource(R.string.app_exp_import_conflict_replace),
                                    ),
                                    selected = modes.indexOf(state.conflictMode),
                                    onSelect = { idx -> onConflictMode(modes[idx]) },
                                )
                            }
                        }
                    }

                    state.error?.let { err ->
                        Text(err, style = MikaType.Hint, color = c.bad)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = state.preview != null && !state.busy,
            ) { Text(stringResource(R.string.app_exp_import_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun AppIconBadge(packageName: String, size: Dp) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }.coerceAtLeast(48)
    val bitmap by produceState<ImageBitmap?>(null, packageName, sizePx) {
        value = withContext(Dispatchers.IO) {
            loadAppIconBitmap(context, packageName, sizePx)
        }
    }
    val shape = RoundedCornerShape(10.dp)
    val c = MikaTheme.colors
    val iconBitmap = bitmap
    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(shape)
                .border(0.5.dp, c.glassEdge, shape),
        )
    } else {
        Box(
            Modifier
                .size(size)
                .background(c.neutralSoft, shape)
                .border(0.5.dp, c.specularHighlight.copy(alpha = if (c.isDark) 0.16f else 0.55f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Apps, null, Modifier.size(size * 0.52f), tint = c.ink2)
        }
    }
}

@Composable
private fun DangerPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .mikaPress(interaction, pressedScale = 0.96f, shape = MikaShapes.Pill)
            .height(ButtonSize.Small.height)
            .clip(MikaShapes.Pill)
            .background(c.badSoft, MikaShapes.Pill)
            .border(0.5.dp, c.bad.copy(alpha = 0.25f), MikaShapes.Pill)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = ButtonSize.Small.text,
            color = c.bad,
            maxLines = 1,
        )
    }
}

private fun Modifier.topRowHairline(first: Boolean, start: Dp, color: androidx.compose.ui.graphics.Color): Modifier =
    if (first) this
    else drawBehind {
        val y = 0.25.dp.toPx()
        drawLine(color, Offset(start.toPx(), y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
    }

private fun loadAppIconBitmap(context: Context, packageName: String, sizePx: Int): ImageBitmap? = runCatching {
    val drawable = context.packageManager.getApplicationIcon(packageName)
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        drawable.bitmap.asImageBitmap()
    } else {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        bmp.asImageBitmap()
    }
}.getOrNull()

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
    }
}.getOrNull() ?: uri.lastPathSegment

private fun formatShortDate(ms: Long): String =
    SimpleDateFormat(tr(R.string.date_pattern_md_compact), uiLocale()).format(Date(ms))

private val SubPanelShape = RoundedCornerShape(10.dp)
