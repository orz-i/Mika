package com.wangzan101.mika.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.core.custody.JumpConfirmProfile
import com.wangzan101.mika.core.custody.JumpConfirmProfileState
import com.wangzan101.mika.core.custody.JumpProfileSource
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.components.mikaPress
import com.wangzan101.mika.ui.components.specularBorderBrush
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ProfileDraft(
    val replacingId: String? = null,
    val initiatorPackage: String = "",
    val targetLabel: String = "",
    val targetPackage: String = "",
    val enabled: Boolean = true,
)

private enum class AppPickField { Initiator, Target }

/** 设置 › 手机操作 › 跳转目标映射：展示并管理方案 2 兜底 Profile。 */
@Composable
fun JumpConfirmProfilesScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as MikaApp
    val store = app.jumpConfirmProfiles
    val state by store.state.collectAsState()
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            runCatching { launchableApps(app.packageManager, app.packageName) }.getOrDefault(emptyList())
        }
    }

    JumpConfirmProfilesContent(
        state = state,
        installedApps = apps,
        onBack = onBack,
        onToggleAutoLearn = store::setAutoLearn,
        onToggleProfile = store::setEnabled,
        onSaveManual = { replacingId, initiator, label, target, enabled ->
            store.upsertManual(
                initiatorPackage = initiator,
                targetLabel = label,
                targetPackage = target,
                enabled = enabled,
                replacingId = replacingId,
            )
        },
        onDeleteProfile = store::remove,
        onClearLearned = store::clearLearned,
        onReset = store::reset,
    )
}

@Composable
fun JumpConfirmProfilesContent(
    state: JumpConfirmProfileState,
    installedApps: List<Pair<String, String>> = emptyList(),
    onBack: () -> Unit,
    onToggleAutoLearn: (Boolean) -> Unit = {},
    onToggleProfile: (String, Boolean) -> Unit = { _, _ -> },
    onSaveManual: (replacingId: String?, initiatorPackage: String, targetLabel: String, targetPackage: String, enabled: Boolean) -> Unit = { _, _, _, _, _ -> },
    onDeleteProfile: (String) -> Unit = {},
    onClearLearned: () -> Unit = {},
    onReset: () -> Unit = {},
) {
    val labels = remember(installedApps) { installedApps.toMap() }
    var draft by remember { mutableStateOf<ProfileDraft?>(null) }
    var invalidDraft by remember { mutableStateOf(false) }
    var pickingField by remember { mutableStateOf<AppPickField?>(null) }
    var appSearch by remember { mutableStateOf("") }
    var confirmingClear by remember { mutableStateOf(false) }

    fun displayName(pkg: String): String = labels[pkg]?.takeIf { it.isNotBlank() } ?: pkg

    PageScaffold(stringResource(R.string.settings_jump_confirm_profiles_title), onBack) {
        if (!state.available) {
            GroupCard {
                ListRow(
                    MikaIcons.Close,
                    stringResource(R.string.settings_jump_confirm_profiles_unavailable),
                    first = true,
                    tone = TileTone.Warn,
                    onClick = onReset,
                ) {
                    RowValue(stringResource(R.string.settings_jump_confirm_profiles_reset), ValueTone.Link)
                }
            }
            return@PageScaffold
        }

        GroupLabel(stringResource(R.string.settings_jump_confirm_profiles_behavior))
        GroupCard {
            ListRow(
                MikaIcons.External,
                stringResource(R.string.settings_jump_confirm_profiles_autolearn),
                stringResource(R.string.settings_jump_confirm_profiles_autolearn_sub),
                first = true,
                onClick = { onToggleAutoLearn(!state.autoLearnEnabled) },
            ) {
                MikaSwitch(
                    state.autoLearnEnabled,
                    onToggleAutoLearn,
                    Modifier.testTag("jump-profiles-autolearn"),
                )
            }
            ListRow(
                MikaIcons.Plus,
                stringResource(R.string.settings_jump_confirm_profiles_add),
                onClick = {
                    invalidDraft = false
                    draft = ProfileDraft()
                },
            ) {
                RowChevron()
            }
            if (state.profiles.any { it.source == JumpProfileSource.LEARNED }) {
                ListRow(
                    MikaIcons.Trash,
                    stringResource(R.string.settings_jump_confirm_profiles_clear_learned),
                    tone = TileTone.Bad,
                    titleColor = MikaTheme.colors.bad,
                    onClick = { confirmingClear = true },
                )
            }
        }

        GroupLabel(stringResource(R.string.settings_jump_confirm_profiles_list_group, state.profiles.size))
        GroupCard {
            if (state.profiles.isEmpty()) {
                ListRow(
                    null,
                    stringResource(R.string.settings_jump_confirm_profiles_empty),
                    first = true,
                )
            } else {
                state.profiles.forEachIndexed { idx, profile ->
                    val sourceTag = stringResource(
                        if (profile.source == JumpProfileSource.MANUAL) {
                            R.string.settings_jump_confirm_profiles_source_manual
                        } else {
                            R.string.settings_jump_confirm_profiles_source_learned
                        },
                    )
                    ListRow(
                        icon = null,
                        title = profile.targetLabel,
                        subtitle = "${displayName(profile.initiatorPackage)} → ${displayName(profile.targetPackage)}",
                        detail = "$sourceTag · ${profile.initiatorPackage} → ${profile.targetPackage}",
                        first = idx == 0,
                        onClick = {
                            invalidDraft = false
                            draft = ProfileDraft(
                                replacingId = profile.id,
                                initiatorPackage = profile.initiatorPackage,
                                targetLabel = profile.targetLabel,
                                targetPackage = profile.targetPackage,
                                enabled = profile.enabled,
                            )
                        },
                    ) {
                        MikaSwitch(
                            profile.enabled,
                            { on -> onToggleProfile(profile.id, on) },
                            Modifier.testTag("jump-profile-toggle-${profile.id}"),
                        )
                    }
                }
            }
        }
        FooterNote(stringResource(R.string.settings_jump_confirm_profiles_footer))
    }

    val currentDraft = draft
    if (currentDraft != null && pickingField == null) {
        MikaDialog(
            onDismissRequest = { draft = null },
            title = {
                Text(
                    stringResource(
                        if (currentDraft.replacingId == null) {
                            R.string.settings_jump_confirm_profiles_add_title
                        } else {
                            R.string.settings_jump_confirm_profiles_edit_title
                        },
                    ),
                )
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = currentDraft.initiatorPackage,
                        onValueChange = {
                            invalidDraft = false
                            draft = currentDraft.copy(initiatorPackage = it)
                        },
                        label = { Text(stringResource(R.string.settings_jump_confirm_profiles_initiator)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("jump-profile-input-initiator"),
                    )
                    if (installedApps.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = {
                                    appSearch = ""
                                    pickingField = AppPickField.Initiator
                                },
                            ) {
                                Text(stringResource(R.string.settings_jump_confirm_profiles_pick_app))
                            }
                        }
                    }

                    OutlinedTextField(
                        value = currentDraft.targetLabel,
                        onValueChange = {
                            invalidDraft = false
                            draft = currentDraft.copy(targetLabel = it)
                        },
                        label = { Text(stringResource(R.string.settings_jump_confirm_profiles_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("jump-profile-input-label"),
                    )

                    OutlinedTextField(
                        value = currentDraft.targetPackage,
                        onValueChange = {
                            invalidDraft = false
                            draft = currentDraft.copy(targetPackage = it)
                        },
                        label = { Text(stringResource(R.string.settings_jump_confirm_profiles_target)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("jump-profile-input-target"),
                    )
                    if (installedApps.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = {
                                    appSearch = ""
                                    pickingField = AppPickField.Target
                                },
                            ) {
                                Text(stringResource(R.string.settings_jump_confirm_profiles_pick_app))
                            }
                        }
                    }

                    if (invalidDraft) {
                        Text(
                            stringResource(R.string.settings_jump_confirm_profiles_invalid),
                            color = MikaTheme.colors.bad,
                            style = MikaType.Caption,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (currentDraft.replacingId != null) {
                        TextButton(
                            onClick = {
                                onDeleteProfile(currentDraft.replacingId)
                                draft = null
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(
                                stringResource(R.string.settings_jump_confirm_profiles_delete),
                                color = MikaTheme.colors.bad,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val initPkg = currentDraft.initiatorPackage.trim()
                        val lbl = currentDraft.targetLabel.trim()
                        val tgtPkg = currentDraft.targetPackage.trim()
                        if (initPkg.isEmpty() || lbl.isEmpty() || tgtPkg.isEmpty() ||
                            initPkg.equals(tgtPkg, ignoreCase = true)
                        ) {
                            invalidDraft = true
                            return@TextButton
                        }
                        onSaveManual(currentDraft.replacingId, initPkg, lbl, tgtPkg, currentDraft.enabled)
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

    val field = pickingField
    if (currentDraft != null && field != null) {
        val filtered = remember(installedApps, appSearch) {
            val q = appSearch.trim()
            if (q.isEmpty()) installedApps
            else installedApps.filter { (pkg, lbl) ->
                pkg.contains(q, ignoreCase = true) || lbl.contains(q, ignoreCase = true)
            }
        }
        MikaDialog(
            onDismissRequest = { pickingField = null },
            title = {
                Text(
                    stringResource(
                        if (field == AppPickField.Initiator) {
                            R.string.settings_jump_confirm_profiles_initiator
                        } else {
                            R.string.settings_jump_confirm_profiles_target
                        },
                    ),
                )
            },
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
                                        invalidDraft = false
                                        draft = if (field == AppPickField.Initiator) {
                                            currentDraft.copy(initiatorPackage = pkg)
                                        } else {
                                            currentDraft.copy(targetPackage = pkg)
                                        }
                                        pickingField = null
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
                TextButton(onClick = { pickingField = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmingClear) {
        MikaDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.settings_jump_confirm_profiles_clear_title)) },
            text = { Text(stringResource(R.string.settings_jump_confirm_profiles_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        onClearLearned()
                    },
                ) {
                    Text(
                        stringResource(R.string.settings_jump_confirm_profiles_clear_learned),
                        color = MikaTheme.colors.bad,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
