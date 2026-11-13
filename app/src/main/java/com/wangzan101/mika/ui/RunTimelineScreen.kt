package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.ui.res.stringResource
import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.StepItem
import com.wangzan101.mika.ui.components.StepState
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一次运行的时间线(只读):顶部是目标、标准、一行小结(点开看时间去向)和结局(模型调用次数这类细节收在「更多详情」里),
 * 下面是竖线步骤,每步一行,点有画面的步骤看当时的画面。
 */
/** 任务还在跑时,运行记录多久重读一次。 */
private const val LIVE_REFRESH_MS = 1_000L

@Composable
fun RunTimelineScreen(vm: RunTimelineViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // 还在跑就每秒再读一次运行记录(不自动滚到底:用户翻到哪就停在哪)
    val ui by produceState<TimelineUi>(TimelineUi.Loading, vm) {
        while (true) {
            val next = withContext(Dispatchers.IO) { vm.load() }
            value = next
            if ((next as? TimelineUi.Ready)?.header?.running != true) break
            delay(LIVE_REFRESH_MS)
        }
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveLog = rememberLauncherForActivityResult(CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val log = vm.usageLog()
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(log) }
                }.isSuccess
            }
            Toast.makeText(context, if (saved) R.string.timeline_exported else R.string.timeline_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.timeline_title), left = MikaIcons.Back, leftLabel = stringResource(R.string.back), onLeft = onBack)
            when (val u = ui) {
                TimelineUi.Loading -> Unit
                TimelineUi.Expired -> EmptyNote(TimelineUi.EXPIRED_TEXT, Modifier.padding(top = 80.dp))
                is TimelineUi.Ready -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 16.dp),
                ) {
                    item("header") {
                        HeaderGroup(u.header) {
                            try {
                                saveLog.launch("mika-usage-${vm.runId}.jsonl")
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, R.string.timeline_export_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    item("trace") { RunTraceSection(vm.runId) }
                    if (u.steps.isNotEmpty()) {
                        item("label") { GroupLabel(trPlural(R.plurals.timeline_total_steps, u.steps.size)) }
                        item("steps") {
                            GroupCard {
                                Column(Modifier.padding(start = 16.dp)) {
                                    u.steps.forEachIndexed { i, s ->
                                        TimelineRow(s, first = i == 0, last = i == u.steps.lastIndex, vm)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderGroup(h: TimelineHeader, onExport: () -> Unit) {
    val c = MikaTheme.colors
    var more by rememberSaveable { mutableStateOf(false) }
    GroupCard {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Text(h.goal, style = MikaType.Title, color = c.ink)
            if (h.criteria.isNotEmpty()) {
                Text(stringResource(R.string.timeline_criteria), Modifier.padding(top = 10.dp), style = MikaType.Label, color = c.ink3)
                h.criteria.forEachIndexed { i, text ->
                    Text(
                        if (h.criteria.size > 1) "${i + 1}. $text" else text, Modifier.padding(top = 3.dp),
                        style = MikaType.Caption, color = c.ink2,
                    )
                }
            }
        }
        h.metrics?.let { RunMetricsSummary(it) }
        h.stepLimit?.let { limit ->
            ListRow(null, stringResource(R.string.timeline_step_limit)) {
                RowValue(stringResource(R.string.timeline_step_limit_value, h.stepsUsed, limit))
            }
        }
        h.tokenBudget?.let { limit ->
            ListRow(null, stringResource(R.string.timeline_token_budget)) {
                RowValue(stringResource(R.string.timeline_token_budget_value, UsageCopy.tokens(h.tokensUsed), UsageCopy.tokens(limit)))
            }
        }
        // 还没跑完:这里说「进行中」,下面这些小结(核查、结局)等结束了才有
        if (h.running) {
            ListRow(null, stringResource(R.string.timeline_outcome)) { RowValue(stringResource(R.string.timeline_running), ValueTone.Plain) }
        } else {
            ListRow(null, stringResource(R.string.timeline_outcome)) {
                RowValue(h.outcome, if (h.outcome == stringResource(R.string.outcome_succeeded)) ValueTone.Ok else ValueTone.Plain)
            }
        }
        if (h.verdict != stringResource(R.string.verify_none)) ListRow(null, stringResource(R.string.timeline_check)) { RowValue(h.verdict) }
        // 小结那一行已经写了用时
        if (h.metrics == null) h.duration?.let { d -> ListRow(null, stringResource(R.string.timeline_duration)) { RowValue(d) } }
        ListRow(null, stringResource(R.string.timeline_export), stringResource(R.string.timeline_export_hint), onClick = onExport) { RowChevron() }
        ListRow(null, stringResource(R.string.timeline_more), onClick = { more = !more }) {
            RowValue(if (more) stringResource(R.string.collapse) else stringResource(R.string.expand))
            RowChevron()
        }
        if (more) {
            ListRow(null, stringResource(R.string.timeline_model_calls)) { RowValue(trPlural(R.plurals.times, h.modelCalls)) }
            if (h.usedMemory.isNotEmpty()) {
                ListRow(null, stringResource(R.string.timeline_memory_used)) { RowValue(trPlural(R.plurals.items, h.usedMemory.size)) }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    h.usedMemory.forEach { memory ->
                        Text("· $memory", Modifier.padding(top = 3.dp), style = MikaType.Hint, color = c.ink2)
                    }
                }
            }
            Text(
                stringResource(R.string.timeline_timing_note),
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                style = MikaType.Hint, color = c.ink3,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineRow(s: TimelineStep, first: Boolean, last: Boolean, vm: RunTimelineViewModel) {
    val c = MikaTheme.colors
    var screenOpen by rememberSaveable(s.step, "screen") { mutableStateOf(false) }
    var networkOpen by rememberSaveable(s.step, "network") { mutableStateOf(false) }
    val shot = s.screenshotId
    val netResults = s.networkResults
    val expandable = shot != null || netResults.isNotEmpty()
    val failed = s.result?.startsWith(stringResource(R.string.result_error_prefix)) == true
    StepItem(
        if (failed) StepState.Failed else StepState.Done, first, last,
        Modifier.then(
            if (expandable) {
                Modifier.clickable {
                    when {
                        shot != null && netResults.isEmpty() -> screenOpen = !screenOpen
                        shot == null -> networkOpen = !networkOpen
                        else -> {
                            val next = !(screenOpen || networkOpen)
                            screenOpen = next
                            networkOpen = next
                        }
                    }
                }
            } else {
                Modifier
            }
        ),
        dotY = 20.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(end = 16.dp, top = 11.dp, bottom = 11.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(stringResource(R.string.step_number, s.step), style = MikaType.Label, color = c.ink3)
                Text(
                    if (s.activities.isEmpty()) listOfNotNull(s.tool, s.detail).joinToString(" · ") else "",
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = MikaType.Body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                s.result?.let {
                    Text(
                        it, Modifier.padding(start = 8.dp).widthIn(max = 120.dp), style = MikaType.Caption,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = when {
                            failed -> c.bad
                            it == stringResource(R.string.result_effective) -> c.ok
                            else -> c.ink3
                        },
                    )
                }
            }
            Text(RunTimelineViewModel.timingLabel(s.timing), Modifier.padding(top = 2.dp), style = MikaType.Hint, color = c.ink3)
            if (s.said.isNotEmpty()) {
                Text(s.said, Modifier.padding(top = 3.dp), style = MikaType.Caption, color = c.ink2)
            }
            s.activities.forEach { a ->
                val action = if (a.count > 1) trPlural(R.plurals.timeline_viewed_times, a.count) else a.label
                // 和对话里点开一步用的是同一份说法(错误码只对开发有意义,给人看的是没成功)
                val effect = ChatCopy.effectLabel(a.effect)
                Text(
                    listOfNotNull(action, a.target?.takeIf(String::isNotBlank), effect.takeIf(String::isNotBlank)).joinToString(" · "),
                    Modifier.padding(top = 3.dp), style = MikaType.Caption, color = c.ink2,
                )
            }
            if (s.tags.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.tags.forEach { TagChip(it) }
                }
            }
            if (shot != null) {
                Text(
                    if (screenOpen) stringResource(R.string.timeline_hide_screen) else stringResource(R.string.timeline_show_screen),
                    Modifier.padding(top = 6.dp).clickable { screenOpen = !screenOpen },
                    style = MikaType.Label, color = c.accent,
                )
                if (screenOpen) Shot(vm, shot, s.step)
            }
            if (netResults.isNotEmpty()) {
                val (showRes, hideRes) = networkToggleLabels(netResults)
                Text(
                    if (networkOpen) stringResource(hideRes) else stringResource(showRes),
                    Modifier.padding(top = 6.dp).clickable { networkOpen = !networkOpen },
                    style = MikaType.Label, color = c.accent,
                )
                if (networkOpen) NetworkResultsCard(netResults)
            }
        }
    }
}

private fun networkToggleLabels(results: List<TimelineNetworkResult>): Pair<Int, Int> {
    val hasRequests = results.any { it.kind == NetworkResultKind.Requests }
    val hasResponse = results.any { it.kind == NetworkResultKind.Response }
    return when {
        hasRequests && !hasResponse -> R.string.timeline_show_network_requests to R.string.timeline_hide_network_requests
        hasResponse && !hasRequests -> R.string.timeline_show_network_response to R.string.timeline_hide_network_response
        else -> R.string.timeline_show_network_results to R.string.timeline_hide_network_results
    }
}

@Composable
private fun NetworkResultsCard(results: List<TimelineNetworkResult>) {
    val c = MikaTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .background(c.fill, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        results.forEachIndexed { idx, res ->
            if (idx > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
            if (results.size > 1) {
                Text(
                    stringResource(
                        if (res.kind == NetworkResultKind.Requests) R.string.timeline_network_requests_title
                        else R.string.timeline_network_response_title,
                    ),
                    style = MikaType.Label, color = c.ink,
                )
            }
            if (res.rawFallback != null) {
                NetworkMonoBox(res.rawFallback, res.truncated)
            } else if (res.kind == NetworkResultKind.Requests) {
                RequestsResultBlock(res)
            } else {
                ResponseResultBlock(res)
            }
        }
    }
}

@Composable
private fun RequestsResultBlock(res: TimelineNetworkResult) {
    val c = MikaTheme.colors
    val meta = listOfNotNull(
        stringResource(R.string.timeline_network_request_count, res.requests.size),
        res.activeMode?.takeIf(String::isNotBlank),
        res.targetPackage?.takeIf(String::isNotBlank),
    ).joinToString(" · ")
    Text(meta, style = MikaType.Hint, color = c.ink3)
    res.error?.takeIf(String::isNotBlank)?.let { err ->
        Text(err, style = MikaType.Caption, color = c.bad)
    }
    if (res.requests.isEmpty()) {
        if (res.error.isNullOrBlank()) {
            Text(
                stringResource(
                    if (res.capturing == false) R.string.timeline_network_capture_off
                    else R.string.timeline_network_empty,
                ),
                style = MikaType.Caption, color = c.ink3,
            )
        }
        return
    }
    SelectionContainer {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            res.requests.forEachIndexed { index, req ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(req.method, style = MikaType.Label, color = c.ink)
                        req.statusCode?.takeIf { it > 0 }?.let { code ->
                            Text(
                                code.toString(),
                                style = MikaType.Label,
                                color = if (code < 400) c.ok else c.bad,
                            )
                        }
                        req.durationMs?.takeIf { it >= 0 }?.let { ms ->
                            Text("${ms} ms", style = MikaType.Hint, color = c.ink3)
                        }
                        req.requestId?.takeIf(String::isNotBlank)?.let { id ->
                            Text(id, style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(
                        req.url,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, color = c.ink2,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResponseResultBlock(res: TimelineNetworkResult) {
    val c = MikaTheme.colors
    val statusText = res.statusCode?.takeIf { it > 0 }?.toString()
    val metaRight = listOfNotNull(
        res.contentType?.takeIf(String::isNotBlank),
        res.requestId?.takeIf(String::isNotBlank),
    ).joinToString(" · ")
    if (statusText != null || metaRight.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            statusText?.let {
                val code = res.statusCode ?: 0
                Text(it, style = MikaType.Label, color = if (code < 400) c.ok else c.bad)
            }
            if (metaRight.isNotEmpty()) {
                Text(metaRight, style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    res.url?.takeIf(String::isNotBlank)?.let { u ->
        SelectionContainer {
            Text(u, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, color = c.ink)
        }
    }
    res.error?.takeIf(String::isNotBlank)?.let { err ->
        Text(err, style = MikaType.Caption, color = c.bad)
    }
    when {
        res.body == null -> Unit
        res.body.isEmpty() -> Text(stringResource(R.string.timeline_network_body_empty), style = MikaType.Caption, color = c.ink3)
        else -> NetworkMonoBox(res.body, res.truncated)
    }
}

@Composable
private fun NetworkMonoBox(text: String, truncated: Boolean) {
    val c = MikaTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().heightIn(max = 280.dp)
                .background(c.surface.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            SelectionContainer {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink2)
            }
        }
        if (truncated) {
            Text(stringResource(R.string.timeline_network_truncated), style = MikaType.Hint, color = c.ink3)
        }
    }
}

@Composable
private fun TagChip(t: Tag) {
    val c = MikaTheme.colors
    val (bg, fg) = when (t.tone) {
        TagTone.Ok -> c.okSoft to c.ok
        TagTone.Warn -> c.warnSoft to c.warn
        TagTone.Bad -> c.badSoft to c.bad
    }
    Text(
        t.text, Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MikaType.Hint, fontWeight = FontWeight.SemiBold, color = fg,
    )
}

/** 当时的画面:在后台线程解码;文件已被清理时只说一句。 */
@Composable
private fun Shot(vm: RunTimelineViewModel, id: Int, step: Int) {
    val image by produceState<ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) { vm.screen(id)?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap() }
    }
    val shape = RoundedCornerShape(12.dp)
    image?.let {
        Image(
            it, stringResource(R.string.timeline_step_screen, step),
            Modifier.padding(top = 8.dp).widthIn(max = 220.dp).clip(shape).background(MikaTheme.colors.fill, shape),
            contentScale = ContentScale.FillWidth,
        )
    }
}
