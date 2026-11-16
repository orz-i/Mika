package com.wangzan101.mika.ui.terminal

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.terminal.TerminalSession
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GlassMaterials
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.liquidGlass
import com.wangzan101.mika.ui.components.mikaPress
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.flow.MutableStateFlow

/** 终端页的入口:打开(或附着)这个对话的会话,把状态交给 [TerminalScreen]。 */
@Composable
internal fun TerminalRoute(app: MikaApp, conversationId: String, title: String?, onDone: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var session by remember {
        mutableStateOf(app.terminals.session(conversationId)?.takeIf { it.phase.value == TerminalSession.Phase.Running })
    }
    var opening by remember { mutableStateOf(session == null) }
    var error by remember { mutableStateOf<SandboxError?>(null) }

    LaunchedEffect(conversationId, attempt) {
        if (session != null) { opening = false; return@LaunchedEffect }
        opening = true
        error = null
        try {
            session = app.terminals.open(conversationId, title)
        } catch (e: SandboxError) {
            error = e
        } catch (e: Exception) {
            error = SandboxError("terminal_failed", e.message ?: "terminal failed", recoverable = true)
        } finally {
            opening = false
        }
    }

    val closed = remember { MutableStateFlow(TerminalSession.Phase.Closed) }
    val noExit = remember { MutableStateFlow<Int?>(null) }
    val phase by (session?.phase ?: closed).collectAsState()
    val exitCode by (session?.exitCode ?: noExit).collectAsState()
    val network = session?.network ?: app.sandboxState.networkAllowed
    val running = app.taskState.activeConversations.collectAsState().value.contains(conversationId)

    TerminalScreen(
        session = session,
        conversationTitle = title,
        network = network,
        taskRunning = running,
        opening = opening,
        error = error,
        phase = phase,
        exitCode = exitCode,
        onRetry = {
            session?.let { app.terminals.close(conversationId) }
            session = null
            attempt++
        },
        onClose = {
            app.terminals.close(conversationId)
            onDone()
        },
        onBack = onDone,
    )
}

@Composable
private fun TerminalScreen(
    session: TerminalSession?,
    conversationTitle: String?,
    network: Boolean,
    taskRunning: Boolean,
    opening: Boolean,
    error: SandboxError?,
    phase: TerminalSession.Phase,
    exitCode: Int?,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    var confirmClose by remember { mutableStateOf(false) }
    val finished = phase != TerminalSession.Phase.Running

    val handle = remember(session) { session?.let { createTerminalWebView(context, it) } }
    DisposableEffect(handle) {
        onDispose {
            (handle?.view?.parent as? ViewGroup)?.removeView(handle.view)
            handle?.close()
        }
    }

    BackHandler {
        if (finished) onBack() else confirmClose = true
    }

    fun send(bytes: ByteArray) {
        val s = session ?: return
        runCatching { s.writeBytes(bytes) }
        handle?.focus()
    }

    PageBackground(ambientGlow = false) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar(
                title = tr(R.string.terminal_title),
                left = MikaIcons.Back,
                onLeft = { if (finished) onBack() else confirmClose = true },
                leftLabel = stringResource(R.string.back),
                subtitle = conversationTitle,
                backdrop = null,
                right = {
                    StatusPill(
                        if (network) tr(R.string.terminal_network_on) else tr(R.string.terminal_network_off),
                        tone = if (network) PillTone.Ok else PillTone.Warn,
                        modifier = Modifier.padding(end = 2.dp),
                    )
                    BarIconButton(
                        MikaIcons.Copy,
                        tr(R.string.terminal_copy_all),
                        onClick = { handle?.copyAll() },
                        enabled = handle != null,
                        backdrop = null,
                    )
                    BarIconButton(
                        MikaIcons.Close,
                        tr(R.string.terminal_close),
                        onClick = { if (finished) onBack() else confirmClose = true },
                        backdrop = null,
                    )
                },
            )

            if (taskRunning) {
                Column(Modifier.fillMaxWidth().background(c.glassTopBar).background(c.warnSoft)) {
                    Text(
                        tr(R.string.terminal_task_running),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MikaType.Hint,
                        color = c.warn,
                    )
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.warnSolid.copy(alpha = 0.25f)))
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().background(ConsoleBg)) {
                when {
                    opening -> Message(tr(R.string.terminal_preparing))
                    error != null -> ErrorBox(error, onRetry)
                    session == null -> Message(tr(R.string.terminal_unavailable))
                    else -> {
                        val h = handle
                        if (h != null) {
                            key(h) {
                                AndroidView(factory = { h.view }, modifier = Modifier.fillMaxSize())
                            }
                        } else {
                            Message(tr(R.string.terminal_unavailable))
                        }
                        if (finished) {
                            ResultBanner(phase, exitCode, onRetry, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
                        }
                    }
                }
            }

            if (!opening && error == null && session != null && !finished) {
                ExtraKeys(
                    onEsc = { send(byteArrayOf(27)) },
                    onTab = { send(byteArrayOf(9)) },
                    onCtrlC = { send(byteArrayOf(3)) },
                    onLeft = { send(byteArrayOf(27, 91, 68)) },
                    onRight = { send(byteArrayOf(27, 91, 67)) },
                    onUp = { send(byteArrayOf(27, 91, 65)) },
                    onDown = { send(byteArrayOf(27, 91, 66)) },
                    onKeyboard = { handle?.focus() },
                    onStop = { confirmClose = true },
                )
            }
        }
    }

    if (confirmClose) {
        MikaDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(tr(R.string.terminal_close_title)) },
            text = { Text(tr(R.string.terminal_close_body)) },
            confirmButton = {
                TextButton({ confirmClose = false; onClose() }) { Text(tr(R.string.terminal_close_confirm), color = c.bad) }
            },
            dismissButton = {
                TextButton({ confirmClose = false }) { Text(tr(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopStart) {
        Text(text, style = MikaType.Body, color = ConsoleDim)
    }
}

@Composable
private fun ErrorBox(error: SandboxError, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(errorText(error), style = MikaType.Body, color = ConsoleFg)
        Spacer(Modifier.height(16.dp))
        MikaButton(tr(R.string.terminal_retry), onRetry, kind = ButtonKind.Primary, size = ButtonSize.Medium)
    }
}

@Composable
private fun ResultBanner(phase: TerminalSession.Phase, exitCode: Int?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .liquidGlass(GlassMaterials.FloatingPill.copy(cornerRadius = 16.dp), backdrop = null, shape = MikaShapes.Card)
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(resultText(phase, exitCode), Modifier.weight(1f), style = MikaType.Body, color = c.ink)
        MikaButton(tr(R.string.terminal_retry), onRetry, kind = ButtonKind.Primary, size = ButtonSize.Small)
    }
}

@Composable
private fun ExtraKeys(
    onEsc: () -> Unit,
    onTab: () -> Unit,
    onCtrlC: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onKeyboard: () -> Unit,
    onStop: () -> Unit,
) {
    val c = MikaTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .liquidGlass(GlassMaterials.TopBar.copy(bottomBorderOnly = false, borderWidth = 0.dp), backdrop = null, shape = RectangleShape)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier.fillMaxWidth().height(0.5.dp)
                .background(if (c.isDark) c.specularHighlight.copy(alpha = 0.22f) else c.glassEdge),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Key("Esc", onEsc)
            Key("Tab", onTab)
            Key("^C", onCtrlC)
            Key("←", onLeft)
            Key("↑", onUp)
            Key("↓", onDown)
            Key("→", onRight)
            Key("⌨", onKeyboard)
            Spacer(Modifier.weight(1f))
            Key(tr(R.string.terminal_stop), onStop, bad = true)
        }
    }
}

@Composable
private fun Key(text: String, onClick: () -> Unit, bad: Boolean = false) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val borderBrush = remember(c, bad) {
        if (bad) {
            Brush.linearGradient(listOf(c.bad.copy(alpha = 0.42f), c.specularMid, c.bad.copy(alpha = 0.20f)))
        } else {
            Brush.linearGradient(listOf(c.specularHighlight, c.specularMid, c.specularBottom))
        }
    }
    Box(
        Modifier
            .mikaPress(interaction, pressedScale = 0.92f, shape = shape)
            .background(if (bad) c.badSoft else c.glassCardElevated, shape)
            .border(0.75.dp, borderBrush, shape)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MikaType.Caption, color = if (bad) c.bad else c.ink)
    }
}

private fun resultText(phase: TerminalSession.Phase, exitCode: Int?): String = when (phase) {
    TerminalSession.Phase.Revoked -> tr(R.string.terminal_revoked)
    TerminalSession.Phase.Exited -> exitCode?.let { tr(R.string.terminal_exited, it) } ?: tr(R.string.terminal_closed)
    else -> tr(R.string.terminal_closed)
}

private fun errorText(error: SandboxError): String = when (error.code) {
    "sandbox_unsupported" -> tr(R.string.terminal_unsupported)
    "storage_low" -> tr(R.string.terminal_storage_low)
    "terminal_limit" -> tr(R.string.terminal_limit)
    "sandbox_busy" -> tr(R.string.terminal_busy)
    "sandbox_network_isolation_unavailable" -> tr(R.string.sandbox_isolation_unavailable)
    else -> error.message ?: tr(R.string.terminal_unavailable)
}

private val ConsoleBg = Color(0xFF101210)
private val ConsoleFg = Color(0xFFE8E8DF)
private val ConsoleDim = Color(0xFF8A8F86)
