package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.browser.BrowserTab
import com.wangzan101.mika.browser.FindState
import com.wangzan101.mika.browser.RecentItem
import com.wangzan101.mika.browser.StartPageModel
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GlassMaterials
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaBottomSheet
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.liquidGlass
import com.wangzan101.mika.ui.components.mikaPress
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.delay

/** 起点页右上角的菜单:标签页、历史记录、清除浏览数据。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserMenuSheet(tabsCount: Int, onDismiss: () -> Unit, onTabs: () -> Unit, onHistory: () -> Unit, onClearData: () -> Unit) {
    MikaBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 16.dp).testTag("browser-menu")) {
            fun act(f: () -> Unit): () -> Unit = { onDismiss(); f() }
            MikaCard {
                ListRow(
                    MikaIcons.Apps, tr(R.string.browser_tabs), detail = tabsCount.takeIf { it > 0 }?.toString(),
                    first = true, onClick = act(onTabs),
                )
                ListRow(MikaIcons.Clock, tr(R.string.browser_history), onClick = act(onHistory))
                ListRow(MikaIcons.Trash, tr(R.string.browser_clear_data), onClick = act(onClearData))
            }
        }
    }
}

/** 标签页面板:打开着的标签,点一下换过去,× 关掉;右上角新建。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsSheet(
    tabs: List<BrowserTab>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
) {
    val c = MikaTheme.colors
    MikaBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("browser-tabs")) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr(R.string.browser_tabs), Modifier.weight(1f), style = MikaType.Title, color = c.ink)
                val newInteraction = remember { MutableInteractionSource() }
                Text(
                    tr(R.string.browser_new_tab),
                    Modifier.mikaPress(newInteraction, pressedScale = 0.94f, shape = MikaShapes.Pill)
                        .clip(MikaShapes.Pill)
                        .clickable(interactionSource = newInteraction, indication = null, role = Role.Button, onClick = onNew)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MikaType.BodyStrong, color = c.accent,
                )
            }
            if (tabs.isEmpty()) {
                Text(
                    tr(R.string.browser_no_tabs), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MikaType.Body, color = c.ink3,
                )
            } else {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    MikaCard {
                        tabs.forEachIndexed { i, t ->
                            ListRow(
                                MikaIcons.Globe,
                                t.title.ifBlank { tr(R.string.browser_new_tab) },
                                subtitle = t.host ?: t.url,
                                first = i == 0,
                                titleColor = if (t.active) c.accent else Color.Unspecified,
                                onClick = { onSelect(t.id) },
                                trailing = {
                                    val close = tr(R.string.browser_close_tab)
                                    val closeInteraction = remember { MutableInteractionSource() }
                                    Box(
                                        Modifier.mikaPress(closeInteraction, pressedScale = 0.90f, shape = CircleShape)
                                            .size(34.dp)
                                            .background(c.glassSubtle, CircleShape)
                                            .border(0.5.dp, c.glassEdge, CircleShape)
                                            .clip(CircleShape)
                                            .clickable(interactionSource = closeInteraction, indication = null, role = Role.Button, onClickLabel = close) { onClose(t.id) },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(MikaIcons.Close, close, Modifier.size(15.dp), tint = c.ink3) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 完整历史:全部记录,整理时点 × 删一条;底部清除全部。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    pages: List<RecentItem>,
    now: Long,
    onDismiss: () -> Unit,
    onOpen: (RecentItem) -> Unit,
    onRemove: (RecentItem) -> Unit,
    onClear: () -> Unit,
) {
    val c = MikaTheme.colors
    var editing by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    MikaBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("browser-history")) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr(R.string.browser_history), Modifier.weight(1f), style = MikaType.Title, color = c.ink)
                if (pages.isNotEmpty()) {
                    val editInteraction = remember { MutableInteractionSource() }
                    Text(
                        if (editing) tr(R.string.done) else tr(R.string.browser_edit),
                        Modifier.mikaPress(editInteraction, pressedScale = 0.94f, shape = MikaShapes.Pill)
                            .clip(MikaShapes.Pill)
                            .clickable(interactionSource = editInteraction, indication = null, role = Role.Button) { editing = !editing }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MikaType.BodyStrong, color = c.accent,
                    )
                }
            }
            if (pages.isEmpty()) {
                Text(
                    tr(R.string.browser_no_recent), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MikaType.Body, color = c.ink3,
                )
            } else {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    MikaCard {
                        pages.forEachIndexed { i, r ->
                            ListRow(
                                if (r.search) MikaIcons.Search else MikaIcons.Globe,
                                r.title, subtitle = r.subtitle, detail = StartPageModel.relativeTime(r.openedAtMs, now),
                                first = i == 0,
                                onClick = if (editing) null else ({ onOpen(r) }),
                                trailing = {
                                    if (editing) {
                                        val remove = tr(R.string.browser_remove_site, r.title)
                                        val removeInteraction = remember { MutableInteractionSource() }
                                        Box(
                                            Modifier.mikaPress(removeInteraction, pressedScale = 0.90f, shape = CircleShape)
                                                .size(34.dp)
                                                .background(c.glassSubtle, CircleShape)
                                                .border(0.5.dp, c.glassEdge, CircleShape)
                                                .clip(CircleShape)
                                                .clickable(interactionSource = removeInteraction, indication = null, role = Role.Button, onClickLabel = remove) { onRemove(r) },
                                            contentAlignment = Alignment.Center,
                                        ) { Icon(MikaIcons.Close, remove, Modifier.size(15.dp), tint = c.ink3) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (pages.isNotEmpty()) {
                val clearInteraction = remember { MutableInteractionSource() }
                Box(
                    Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                        .mikaPress(clearInteraction, pressedScale = 0.97f, shape = MikaShapes.Button)
                        .cardSurface(c, MikaShapes.Button, elevated = true)
                        .clip(MikaShapes.Button)
                        .clickable(interactionSource = clearInteraction, indication = null, role = Role.Button) { clearing = true }
                        .height(42.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tr(R.string.browser_clear_history),
                        style = MikaType.BodyStrong.copy(fontWeight = FontWeight.SemiBold),
                        color = c.bad,
                    )
                }
            }
        }
    }
    if (clearing) {
        MikaDialog(
            onDismissRequest = { clearing = false },
            title = { Text(tr(R.string.browser_clear_history_q)) },
            confirmButton = {
                TextButton(onClick = { clearing = false; editing = false; onClear() }) { Text(tr(R.string.clear), color = c.bad) }
            },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text(tr(R.string.cancel)) } },
        )
    }
}

/** 清除浏览数据:勾选历史、网站数据、缓存,一起清。网站数据会退出所有网站。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearDataSheet(onDismiss: () -> Unit, onClear: (history: Boolean, siteData: Boolean, cache: Boolean) -> Unit) {
    val c = MikaTheme.colors
    var history by rememberSaveable { mutableStateOf(true) }
    var siteData by rememberSaveable { mutableStateOf(true) }
    var cache by rememberSaveable { mutableStateOf(true) }
    MikaBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("browser-clear-data")) {
            Text(
                tr(R.string.browser_clear_data), Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                style = MikaType.Title, color = c.ink,
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                MikaCard {
                    ListRow(MikaIcons.Clock, tr(R.string.browser_history), first = true, trailing = { MikaSwitch(history, { history = it }) })
                    ListRow(
                        MikaIcons.Globe, tr(R.string.browser_clear_site_data), subtitle = tr(R.string.browser_clear_site_data_sub),
                        trailing = { MikaSwitch(siteData, { siteData = it }) },
                    )
                    ListRow(MikaIcons.Refresh, tr(R.string.browser_clear_cache), trailing = { MikaSwitch(cache, { cache = it }) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MikaButton(tr(R.string.cancel), onDismiss, Modifier.weight(1f), kind = ButtonKind.Secondary, size = ButtonSize.Medium)
                MikaButton(
                    tr(R.string.clear),
                    { onClear(history, siteData, cache) },
                    Modifier.weight(1f),
                    kind = ButtonKind.Primary,
                    size = ButtonSize.Medium,
                    enabled = history || siteData || cache,
                )
            }
        }
    }
}

/** 页内查找条:输入后自动搜,显示第几处/共几处,上一个、下一个、关闭。自包含玻璃 (backdrop = null)。 */
@Composable
fun BrowserFindBar(find: FindState, onQuery: (String) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val c = MikaTheme.colors
    var text by remember(find.active) { mutableStateOf(find.query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(text) {
        delay(200)
        onQuery(text)
    }
    Row(
        Modifier.fillMaxWidth()
            .liquidGlass(GlassMaterials.TopBar, backdrop = null, shape = RectangleShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.weight(1f).height(40.dp).cardSurface(c, MikaShapes.Pill, elevated = true).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MikaIcons.Search, null, Modifier.size(16.dp), tint = c.ink3)
            BasicTextField(
                text, { text = it },
                Modifier.weight(1f).padding(horizontal = 8.dp).focusRequester(focus).testTag("browser-find-input"),
                singleLine = true,
                textStyle = MikaType.Body.copy(color = c.ink, textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        val count = when {
            find.query.isBlank() -> ""
            find.matches == 0 -> tr(R.string.browser_find_no_matches)
            else -> "${find.index}/${find.matches}"
        }
        if (count.isNotEmpty()) {
            Text(count, style = MikaType.Hint, color = c.ink3, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
        }
        FindAction(MikaIcons.Back, tr(R.string.browser_find_prev), onPrev)
        FindAction(MikaIcons.Forward, tr(R.string.browser_find_next), onNext)
        FindAction(MikaIcons.Close, tr(R.string.browser_find_close), onClose)
    }
}

@Composable
private fun FindAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.mikaPress(interaction, pressedScale = 0.90f, shape = CircleShape)
            .size(36.dp).clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(17.dp), tint = MikaTheme.colors.ink2) }
}
