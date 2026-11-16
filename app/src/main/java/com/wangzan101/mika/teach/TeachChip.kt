package com.wangzan101.mika.teach

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.teach.TeachPhase
import com.wangzan101.mika.ui.components.GlassMaterials
import com.wangzan101.mika.ui.components.liquidGlass
import com.wangzan101.mika.ui.components.mikaPress
import com.wangzan101.mika.ui.components.specularBorderBrush
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.animationsOff
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 采集和暂停时贴在屏幕上方的一条。用的是无障碍浮层，和悬浮胶囊同一类窗口，不申请通知权限，也不申请「显示在其他应用上层」。
 * 加不上就返回 false。这条属于 Mika 自己的界面，轨迹里会丢掉。
 */
class TeachChip(
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onEnd: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val phase = MutableStateFlow(TeachPhase.Paused)
    private var window: OverlayWindow? = null

    fun visible(): Boolean = window?.isOpen == true

    /** 必须在主线程调用。已经显示时只换文案。 */
    fun show(target: TeachPhase): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (target != TeachPhase.Recording && target != TeachPhase.Paused) {
            hideOnMain()
            return false
        }
        phase.value = target
        if (window?.isOpen == true) return true
        val service = ScreenAccessibility.instance.value ?: return false
        val top = service.overlays().area().insetTop + (8 * service.resources.displayMetrics.density).toInt()
        val opened = service.overlays().open(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, y = top)) {
            TeachChipBar(phase, onPause, onResume, onEnd)
        } ?: return false
        window = opened
        return opened.isOpen
    }

    fun hide() {
        if (Looper.myLooper() == Looper.getMainLooper()) hideOnMain() else main.post { hideOnMain() }
    }

    private fun hideOnMain() {
        window?.close()
        window = null
    }
}

private val TeachChipMaterial = GlassMaterials.FloatingPill.copy(shadowElevation = 8.dp)

@Composable
private fun TeachChipBar(
    phase: MutableStateFlow<TeachPhase>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
) {
    val current by phase.collectAsState()
    val recording = current == TeachPhase.Recording
    val context = LocalContext.current
    val reduceMotion = remember(context) { animationsOff(context) }
    MikaTheme(reduceMotion = reduceMotion) {
        val c = MikaTheme.colors
        Box(Modifier.padding(8.dp)) {
            Row(
                Modifier
                    .testTag("teach-chip")
                    .liquidGlass(TeachChipMaterial, backdrop = null, shape = MikaShapes.Pill)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(8.dp).background(if (recording) c.bad else c.warnSolid, CircleShape))
                Text(
                    stringResource(if (recording) R.string.teach_learning else R.string.teach_paused),
                    style = MikaType.BodyStrong,
                    color = c.ink,
                )
                ChipAction(
                    stringResource(if (recording) R.string.teach_pause else R.string.teach_resume),
                    tint = if (recording) c.ink else c.accent,
                    bg = if (recording) c.glassTonal else c.accentSoft,
                    border = if (recording) null else c.accent.copy(alpha = 0.25f),
                ) {
                    if (recording) onPause() else onResume()
                }
                ChipAction(
                    stringResource(R.string.teach_end),
                    tint = c.bad,
                    bg = c.badSoft,
                    border = c.bad.copy(alpha = 0.25f),
                    onClick = onEnd,
                )
            }
        }
    }
}

@Composable
private fun ChipAction(
    label: String,
    tint: Color,
    bg: Color,
    border: Color?,
    onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .mikaPress(interaction, pressedScale = 0.94f, shape = MikaShapes.Pill)
            .clip(MikaShapes.Pill)
            .background(bg, MikaShapes.Pill)
            .then(
                if (border != null) Modifier.border(0.5.dp, border, MikaShapes.Pill)
                else Modifier.border(0.5.dp, specularBorderBrush(c), MikaShapes.Pill),
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MikaType.Caption, color = tint)
    }
}
