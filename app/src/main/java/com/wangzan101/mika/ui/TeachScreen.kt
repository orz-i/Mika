package com.wangzan101.mika.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.wangzan101.mika.R
import com.wangzan101.mika.core.teach.TeachPhase
import com.wangzan101.mika.core.teach.TeachStep
import com.wangzan101.mika.core.teach.teachGoalAccepted
import com.wangzan101.mika.core.teach.teachLine
import com.wangzan101.mika.teach.TeachNotice
import com.wangzan101.mika.teach.TeachRuntime
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

@Composable
fun TeachScreen(runtime: TeachRuntime, onBack: () -> Unit, onOpenDrafts: () -> Unit) {
    val ui by runtime.ui.collectAsState()
    val session = ui.session
    PageScaffold(stringResource(R.string.teach_title), onBack) {
        KeyboardDismissArea(Modifier.fillMaxWidth().imePadding()) {
            Column(Modifier.fillMaxWidth()) {
                Notice(ui.notice)
                when (session?.phase) {
                    null, TeachPhase.Idle, TeachPhase.Ready -> GoalForm(runtime, ui.hint)
                    TeachPhase.Recording, TeachPhase.Paused -> Live(runtime, session.phase, session.steps.size)
                    TeachPhase.Interrupted -> Interrupted(runtime)
                    TeachPhase.Reviewing, TeachPhase.Drafting -> Review(runtime, session.steps, session.phase == TeachPhase.Drafting)
                    TeachPhase.Saved -> Saved(runtime, onOpenDrafts)
                }
            }
        }
    }
}

@Composable
private fun Notice(notice: TeachNotice?) {
    val text = when (notice) {
        TeachNotice.Busy -> stringResource(R.string.teach_busy)
        TeachNotice.NoAccessibility -> stringResource(R.string.teach_need_a11y)
        TeachNotice.NeedGoal -> stringResource(R.string.teach_need_goal)
        TeachNotice.SecretGoal -> stringResource(R.string.teach_secret_goal)
        TeachNotice.DraftFailed -> stringResource(R.string.teach_draft_failed)
        TeachNotice.NoSteps -> stringResource(R.string.teach_no_steps)
        TeachNotice.SecretNote -> stringResource(R.string.teach_sensitive_note)
        TeachNotice.NoVisibleStop -> stringResource(R.string.teach_no_visible)
        TeachNotice.WatchLost -> stringResource(R.string.teach_watch_lost)
        null -> return
    }
    val c = MikaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(c.warnSoft, MikaShapes.Card)
            .border(0.75.dp, c.warnSolid.copy(alpha = 0.28f), MikaShapes.Card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(MikaIcons.Exclaim, null, Modifier.size(18.dp), tint = c.warn)
        Text(text, color = c.warn, style = MikaType.Body)
    }
}

@Composable
private fun GoalForm(runtime: TeachRuntime, hint: String?) {
    val c = MikaTheme.colors
    var goal by remember { mutableStateOf(hint.orEmpty()) }
    var armed by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf<TeachNotice?>(null) }
    LaunchedEffect(hint) { if (goal.isEmpty() && !hint.isNullOrEmpty()) goal = hint }
    Column(
        Modifier.fillMaxWidth().testTag("teach-form"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Notice(local)
        if (!armed) {
            MikaCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.teach_disclosure), style = MikaType.Body, color = c.ink)
                    OutlinedTextField(
                        goal, { goal = it; local = null },
                        Modifier.fillMaxWidth().keepsKeyboard().testTag("teach-goal"),
                        label = { Text(stringResource(R.string.teach_goal_label)) },
                        placeholder = { Text(stringResource(R.string.teach_goal_hint)) },
                    )
                }
            }
            MikaButton(
                stringResource(R.string.teach_continue),
                {
                    local = when {
                        goal.isBlank() -> TeachNotice.NeedGoal
                        !teachGoalAccepted(goal) -> TeachNotice.SecretGoal
                        else -> null
                    }
                    if (local == null) armed = true
                },
                Modifier.fillMaxWidth(),
                kind = ButtonKind.Primary,
                size = ButtonSize.Medium,
            )
        } else {
            MikaCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.teach_ready_body), style = MikaType.Body, color = c.ink)
                    Text(
                        goal,
                        Modifier
                            .fillMaxWidth()
                            .background(c.glassSubtle, RoundedCornerShape(10.dp))
                            .border(0.5.dp, c.glassEdge, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MikaType.Body,
                        color = c.ink2,
                    )
                }
            }
            val context = LocalContext.current
            MikaButton(
                stringResource(R.string.teach_start),
                {
                    local = runtime.start(goal)
                    if (local == null) {
                        armed = false
                        (context as? Activity)?.moveTaskToBack(true)
                    }
                },
                Modifier.fillMaxWidth().testTag("teach-start"),
                kind = ButtonKind.Primary,
                size = ButtonSize.Medium,
            )
        }
    }
}

@Composable
private fun Live(runtime: TeachRuntime, phase: TeachPhase, steps: Int) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MikaCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(if (phase == TeachPhase.Recording) R.string.teach_learning else R.string.teach_paused),
                        style = MikaType.Title,
                        color = c.ink,
                    )
                    StatusPill(steps.toString(), if (phase == TeachPhase.Recording) PillTone.Accent else PillTone.Neutral)
                }
                Text(stringResource(R.string.teach_notification), style = MikaType.Body, color = c.ink2)
            }
        }
        val context = LocalContext.current
        if (phase == TeachPhase.Recording) {
            MikaButton(stringResource(R.string.teach_pause), runtime::pause, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, size = ButtonSize.Medium)
        } else {
            MikaButton(
                stringResource(R.string.teach_resume),
                { if (runtime.resume() == null) (context as? Activity)?.moveTaskToBack(true) },
                Modifier.fillMaxWidth(),
                kind = ButtonKind.Primary,
                size = ButtonSize.Medium,
            )
        }
        MikaButton(stringResource(R.string.teach_end), runtime::end, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, size = ButtonSize.Medium)
        MikaButton(stringResource(R.string.teach_discard), runtime::discard, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, size = ButtonSize.Medium)
    }
}

@Composable
private fun Interrupted(runtime: TeachRuntime) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val context = LocalContext.current
        MikaCard {
            Text(stringResource(R.string.teach_interrupted), Modifier.padding(16.dp), style = MikaType.Body, color = c.ink)
        }
        MikaButton(
            stringResource(R.string.teach_resume),
            { if (runtime.resume() == null) (context as? Activity)?.moveTaskToBack(true) },
            Modifier.fillMaxWidth(),
            kind = ButtonKind.Primary,
            size = ButtonSize.Medium,
        )
        MikaButton(stringResource(R.string.teach_end), runtime::end, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, size = ButtonSize.Medium)
        MikaButton(stringResource(R.string.teach_discard), runtime::discard, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, size = ButtonSize.Medium)
    }
}

@Composable
private fun Review(runtime: TeachRuntime, steps: List<TeachStep>, drafting: Boolean) {
    val c = MikaTheme.colors
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.teach_review), style = MikaType.Title, color = c.ink)
        if (steps.isEmpty()) EmptyNote(stringResource(R.string.teach_no_steps))
        steps.forEach { step -> StepEditor(runtime, step) }
        MikaButton(
            stringResource(R.string.teach_generate),
            runtime::generate,
            Modifier.fillMaxWidth().testTag("teach-generate"),
            enabled = steps.isNotEmpty() && !drafting,
            kind = ButtonKind.Primary,
            size = ButtonSize.Medium,
        )
        if (confirm) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.warnSoft, MikaShapes.Card)
                    .border(0.75.dp, c.warnSolid.copy(alpha = 0.28f), MikaShapes.Card)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.teach_discard_body), style = MikaType.Body, color = c.warn)
                MikaButton(stringResource(R.string.teach_discard), runtime::discard, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, size = ButtonSize.Medium)
            }
        } else {
            MikaButton(stringResource(R.string.teach_discard), { confirm = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, size = ButtonSize.Medium)
        }
    }
}

@Composable
private fun StepEditor(runtime: TeachRuntime, step: TeachStep) {
    val c = MikaTheme.colors
    var correction by remember(step.id, step.correction) { mutableStateOf(step.correction.orEmpty()) }
    var explanation by remember(step.id, step.explanation) { mutableStateOf(step.explanation.orEmpty()) }
    MikaCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(teachLine(step), style = MikaType.Body, color = c.ink)
            if (step.insufficient) Text(stringResource(R.string.teach_insufficient_note), style = MikaType.Hint, color = c.ink3)
            if (step.sensitiveOmitted) Text(stringResource(R.string.teach_sensitive_note), style = MikaType.Hint, color = c.ink3)
            OutlinedTextField(
                correction,
                {
                    correction = it
                    runtime.annotate(step.id, it, explanation)?.let { }
                },
                Modifier.fillMaxWidth().keepsKeyboard(),
                label = { Text(stringResource(R.string.teach_correct)) },
            )
            if (step.needsClarification) {
                OutlinedTextField(
                    explanation,
                    { explanation = it; runtime.annotate(step.id, correction, it) },
                    Modifier.fillMaxWidth().keepsKeyboard().testTag("teach-clarify"),
                    label = { Text(stringResource(R.string.teach_clarify)) },
                )
            }
            Row {
                MikaButton(stringResource(R.string.teach_remove), { runtime.remove(step.id) }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
            }
        }
    }
}

@Composable
private fun Saved(runtime: TeachRuntime, onOpenDrafts: () -> Unit) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MikaCard {
            Text(stringResource(R.string.teach_draft_ready), Modifier.padding(16.dp), style = MikaType.Body, color = c.ink)
        }
        MikaButton(stringResource(R.string.teach_open_drafts), onOpenDrafts, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, size = ButtonSize.Medium)
        MikaButton(stringResource(R.string.teach_discard), runtime::discard, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal, size = ButtonSize.Medium)
    }
}
