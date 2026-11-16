package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.FileLibrary
import com.wangzan101.mika.chat.FileLibrarySnapshot
import com.wangzan101.mika.chat.LibraryFile
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class LibraryRow(
    val name: String,
    val size: String,
    val location: String,
    val at: Long?,
    val conversations: List<String>,
    val input: Boolean,
    val handle: LibraryFileHandle,
)

private data class LibraryLoaded(val rows: List<LibraryRow>, val titles: Map<String, String>, val partial: Boolean, val failed: Boolean = false)

internal data class LibraryLoadResult(val snapshot: FileLibrarySnapshot?, val titles: Map<String, String>, val partial: Boolean)

/** 索引和会话标题分别读取，一边出错不能阻止另一边显示。取消仍交给协程处理。 */
internal fun loadLibraryData(
    snapshot: () -> FileLibrarySnapshot,
    titles: () -> Map<String, String>,
    draftTitles: Map<String, String>,
    fallbackTitle: (String) -> String?,
): LibraryLoadResult {
    var partial = false
    fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        partial = true
        null
    }
    val files = attempt(snapshot)
    val names = attempt(titles)?.toMutableMap() ?: draftTitles.toMutableMap()
    draftTitles.forEach { (id, title) -> names.putIfAbsent(id, title) }
    files?.files?.forEach { file ->
        val ids = when (file) {
            is LibraryFile.Input -> file.sources.map { it.source.conversationId }
            is LibraryFile.Output -> file.sources.map { it.source.conversationId }
        }
        ids.distinct().forEach { id ->
            if (id !in names) names[id] = attempt { fallbackTitle(id) } ?: id
        }
    }
    return LibraryLoadResult(files, names, partial || files?.hasReadFailures == true)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileLibraryScreen(
    vm: ChatViewModel,
    initialConversation: String?,
    fromDrawer: Boolean,
    onBack: () -> Unit,
    onAdded: (String) -> Boolean,
) {
    BackHandler(onBack = onBack)
    val c = MikaTheme.colors
    val context = LocalContext.current
    val store = remember { ConversationStore.shared(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(store) { store.changes.collect { revision++ } }
    val drafts by vm.draftConversations.collectAsState()
    val loaded by produceState<LibraryLoaded?>(null, revision, drafts) {
        value = try { withContext(Dispatchers.IO) {
            val result = loadLibraryData(
                snapshot = { FileLibrary(store).snapshot() },
                titles = { vm.withDrafts(store.list()).associate { it.id to ChatCopy.plainTitle(it.title) } },
                draftTitles = drafts.associate { it.id to ChatCopy.plainTitle(it.title) },
                fallbackTitle = { id -> store.title(id)?.let(ChatCopy::plainTitle) },
            )
            val rows = result.snapshot?.files.orEmpty().mapNotNull { file ->
                when (file) {
                    is LibraryFile.Input -> file.sources.maxByOrNull { it.source.atMs ?: Long.MIN_VALUE }?.let { latest ->
                        LibraryRow(file.name, latest.sizeLabel, file.path, file.latestAtMs, file.sources.map { it.source.conversationId }.distinct(), true,
                            LibraryFileHandle.Workspace(latest.source.conversationId, file.path))
                    }
                    is LibraryFile.Output -> file.sources.maxByOrNull { it.source.atMs ?: Long.MIN_VALUE }?.let { latest ->
                        LibraryRow(latest.file.name, ChatViewModel.sizeLabel(latest.file.size), latest.file.savedAs, file.latestAtMs,
                            file.sources.map { it.source.conversationId }.distinct(), false, LibraryFileHandle.Exported(latest.file))
                    }
                }
            }.sortedByDescending { it.at ?: Long.MIN_VALUE }
            LibraryLoaded(rows, result.titles, result.partial, failed = result.snapshot == null)
        } } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LibraryLoaded(emptyList(), emptyMap(), partial = false, failed = true)
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var conversation by rememberSaveable { mutableStateOf(if (fromDrawer) null else initialConversation ?: "") }
    var source by rememberSaveable { mutableIntStateOf(0) }
    var conversationMenu by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<LibraryRow?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var savedElsewhere by remember { mutableStateOf(false) }
    val rows = loaded?.rows.orEmpty().filter {
        it.name.contains(query.trim(), ignoreCase = true) &&
            (conversation == null || conversation in it.conversations) &&
            (source == 0 || (source == 1) == it.input)
    }
    val titles = loaded?.titles.orEmpty()
    fun add(item: LibraryRow, id: String) {
        scope.launch {
            working = true
            val result = LibraryFileActions.addToDraft(context, vm, id, item.handle)
            working = false
            if (result == LibraryAttachmentResult.Added) savedElsewhere = !onAdded(id) else error = true
        }
    }
    val density = LocalDensity.current
    val initialTopPx = WindowInsets.statusBars.getTop(density) + with(density) { MikaSize.TopBar.roundToPx() }
    var topBarHeightPx by remember { mutableIntStateOf(initialTopPx) }
    val topBarHeightDp = with(density) { topBarHeightPx.toDp() }
    GlassBackdropHost(
        modifier = Modifier.fillMaxSize(),
        content = {
            PageBackground {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(topBarHeightDp + 4.dp))
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        SearchField(query, { query = it }, stringResource(R.string.file_library_search), Modifier.testTag("file-library-search"))
                        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                SelectChip(
                                    conversation?.let { titles[it] ?: stringResource(R.string.file_library_current) } ?: stringResource(R.string.file_library_all),
                                    conversation != null,
                                    { conversationMenu = true },
                                    Modifier.fillMaxWidth(),
                                )
                                LibraryDropdownMenu(expanded = conversationMenu, onDismissRequest = { conversationMenu = false }) {
                                    LibraryMenuItem(stringResource(R.string.file_library_all), selected = conversation == null) {
                                        conversation = null
                                        conversationMenu = false
                                    }
                                    titles.forEach { (id, title) ->
                                        LibraryMenuItem(title, selected = conversation == id) {
                                            conversation = id
                                            conversationMenu = false
                                        }
                                    }
                                    if (initialConversation != null && initialConversation !in titles) {
                                        LibraryMenuItem(stringResource(R.string.file_library_current), selected = conversation == initialConversation) {
                                            conversation = initialConversation
                                            conversationMenu = false
                                        }
                                    }
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                val label = when (source) { 1 -> R.string.file_library_input; 2 -> R.string.file_library_output; else -> R.string.file_library_sources }
                                SelectChip(stringResource(label), source != 0, { sourceMenu = true }, Modifier.fillMaxWidth())
                                LibraryDropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                                    listOf(R.string.file_library_sources, R.string.file_library_input, R.string.file_library_output).forEachIndexed { index, text ->
                                        LibraryMenuItem(stringResource(text), selected = source == index) {
                                            source = index
                                            sourceMenu = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (loaded?.partial == true && loaded?.failed == false) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.file_library_partial), Modifier.weight(1f).padding(vertical = 8.dp), style = MikaType.Hint, color = c.warn)
                            MikaButton(stringResource(R.string.file_library_retry), { revision++ }, kind = ButtonKind.Text, size = ButtonSize.Small)
                        }
                    }
                    when {
                        loaded == null -> EmptyNote(stringResource(R.string.file_library_loading))
                        loaded?.failed == true -> {
                            Column {
                                EmptyNote(stringResource(R.string.file_library_load_failed))
                                MikaButton(
                                    stringResource(R.string.file_library_retry),
                                    { revision++ },
                                    Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally),
                                    kind = ButtonKind.Secondary,
                                    size = ButtonSize.Small,
                                )
                            }
                        }
                        rows.isEmpty() -> EmptyNote(stringResource(R.string.file_library_empty))
                        else -> LazyColumn(
                            Modifier.fillMaxSize().navigationBarsPadding().testTag("file-library-list"),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(rows) { _, item ->
                                val available by produceState<Boolean?>(null, item.handle, revision) {
                                    value = null
                                    value = try {
                                        LibraryFileActions.isAvailable(context, item.handle)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        false
                                    }
                                }
                                val canOpen = !working && available == true
                                val cardInteraction = remember { MutableInteractionSource() }
                                MikaCard(
                                    Modifier
                                        .mikaPress(cardInteraction, enabled = canOpen, pressedScale = 0.985f, shape = MikaShapes.Card)
                                        .clickable(interactionSource = cardInteraction, indication = null, enabled = canOpen) {
                                            scope.launch { working = true; error = !LibraryFileActions.preview(context, item.handle); working = false }
                                        },
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(item.name, style = MikaType.BodyStrong, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val date = item.at?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: stringResource(R.string.file_library_unknown_time)
                                        Text(listOfNotNull(item.size.takeIf { it.isNotBlank() }, date).joinToString(" · "), style = MikaType.Hint, color = c.ink3)
                                        val location = item.location.takeIf { it.isNotBlank() && it != item.name }
                                            ?: (item.handle as? LibraryFileHandle.Exported)?.file?.uri?.takeIf { uri -> rows.count { it.name == item.name } > 1 && uri.isNotBlank() }
                                        if (location != null) Text(location, style = MikaType.Hint, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(item.conversations.joinToString(" · ") { titles[it] ?: it }, style = MikaType.Hint, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        FlowRow(
                                            Modifier.padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            CapabilityTag(stringResource(if (item.input) R.string.file_library_input else R.string.file_library_output))
                                            StatusPill(
                                                stringResource(when (available) { true -> R.string.file_library_available; false -> R.string.file_library_missing; null -> R.string.file_library_loading }),
                                                tone = when (available) { true -> PillTone.Ok; false -> PillTone.Warn; null -> PillTone.Neutral },
                                            )
                                        }
                                        FlowRow(
                                            Modifier.padding(top = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            if (available == false) {
                                                MikaButton(stringResource(R.string.file_library_retry), { revision++ }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
                                            }
                                            MikaButton(
                                                stringResource(R.string.file_library_open),
                                                { scope.launch { working = true; error = !LibraryFileActions.preview(context, item.handle); working = false } },
                                                kind = ButtonKind.Secondary,
                                                size = ButtonSize.Small,
                                                enabled = canOpen,
                                            )
                                            MikaButton(
                                                stringResource(R.string.file_library_share),
                                                { scope.launch { working = true; error = !LibraryFileActions.share(context, item.handle); working = false } },
                                                kind = ButtonKind.Tonal,
                                                size = ButtonSize.Small,
                                                enabled = canOpen,
                                            )
                                            MikaButton(
                                                stringResource(R.string.file_library_add),
                                                {
                                                    if (fromDrawer) choosing = item
                                                    else if (initialConversation != null) add(item, initialConversation)
                                                    else if (vm.ui.value.conversationId == null) add(item, vm.libraryTargetConversation())
                                                    else error = true
                                                },
                                                kind = ButtonKind.Primary,
                                                size = ButtonSize.Small,
                                                enabled = canOpen,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        overlay = {
            Box(Modifier.onSizeChanged { if (it.height > 0) topBarHeightPx = it.height }) {
                TopBar(stringResource(R.string.file_library_title), MikaIcons.Back, onBack, stringResource(R.string.back))
            }
        },
    )
    choosing?.let { item ->
        MikaDialog(
            onDismissRequest = { choosing = null },
            title = { Text(stringResource(R.string.file_library_choose)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    var first = true
                    fun nextFirst(): Boolean = first.also { first = false }
                    if (vm.canSwitch()) {
                        ListRow(
                            null,
                            stringResource(R.string.file_library_new),
                            first = nextFirst(),
                            titleColor = c.accent,
                            onClick = {
                                choosing = null
                                add(item, store.newConversation())
                            },
                        )
                    }
                    titles.filter { (id, _) -> vm.canSwitch() || id == vm.ui.value.conversationId }.forEach { (id, title) ->
                        ListRow(
                            null,
                            title,
                            first = nextFirst(),
                            onClick = {
                                choosing = null
                                add(item, id)
                            },
                        )
                    }
                    if (vm.ui.value.conversationId == null) {
                        ListRow(
                            null,
                            stringResource(R.string.file_library_current),
                            first = nextFirst(),
                            onClick = {
                                choosing = null
                                add(item, vm.libraryTargetConversation())
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (error) {
        MikaDialog(
            onDismissRequest = { error = false },
            text = { Text(stringResource(R.string.file_library_error)) },
            confirmButton = { TextButton(onClick = { error = false }) { Text(stringResource(R.string.done)) } },
        )
    }
    if (savedElsewhere) {
        MikaDialog(
            onDismissRequest = { savedElsewhere = false },
            text = { Text(stringResource(R.string.file_library_draft_saved)) },
            confirmButton = { TextButton(onClick = { savedElsewhere = false }) { Text(stringResource(R.string.done)) } },
        )
    }
}

@Composable
private fun LibraryDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MikaTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = MikaShapes.Card,
        containerColor = c.glassCardElevated,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(0.75.dp, Brush.linearGradient(listOf(c.specularHighlight, c.specularMid, c.specularBottom))),
        content = content,
    )
}

@Composable
private fun LibraryMenuItem(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = MikaTheme.colors
    DropdownMenuItem(
        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        colors = MenuDefaults.itemColors(textColor = if (selected) c.accent else c.ink),
    )
}
