package com.wangzan101.mika.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MarkdownText
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * App 内统一液态玻璃弹窗：基于 Compose [Dialog] 保留 `isDialog()` 语义与调用方内容、动作、测试标识。
 * 跨 Window 不采集主窗 GraphicsLayer，而在 API 31+ 开启原生跨窗背板模糊 (`FLAG_BLUR_BEHIND`) 并配自包含磨砂玻璃容器。
 */
@Composable
fun MikaDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit = {},
    dismissButton: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val blurBehindPx = with(density) { 20.dp.roundToPx() }
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView, c.isDark, blurBehindPx) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            if (window != null) {
                val wm = context.getSystemService(WindowManager::class.java)
                val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm?.isCrossWindowBlurEnabled == true
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(
                    if (canBlur) (if (c.isDark) 0.42f else 0.22f)
                    else (if (c.isDark) 0.56f else 0.34f),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canBlur) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        this.blurBehindRadius = blurBehindPx
                    }
                }
            }
            onDispose {}
        }
        Column(
            modifier
                .widthIn(min = 280.dp, max = 420.dp)
                .fillMaxWidth()
                .liquidGlass(GlassMaterials.Dialog, backdrop = null, shape = MikaShapes.Dialog)
                .padding(24.dp),
        ) {
            title?.let { content ->
                Box(Modifier.padding(bottom = if (text != null) 14.dp else 20.dp)) {
                    CompositionLocalProvider(
                        LocalContentColor provides c.ink,
                        LocalTextStyle provides MikaType.Title.copy(fontSize = 20.sp, lineHeight = 26.sp),
                    ) { content() }
                }
            }
            text?.let { content ->
                Box(Modifier.weight(1f, fill = false).padding(bottom = 22.dp)) {
                    CompositionLocalProvider(
                        LocalContentColor provides c.ink2,
                        LocalTextStyle provides MikaType.Body.copy(fontSize = 14.5.sp, lineHeight = 23.sp),
                    ) { content() }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (dismissButton != null) {
                    DialogAction(Modifier.weight(1f), c.glassTonal, c.glassEdge) {
                        CompositionLocalProvider(LocalContentColor provides c.ink2) { dismissButton() }
                    }
                }
                DialogAction(Modifier.weight(1f), c.accentSoft, c.accent.copy(alpha = 0.24f)) {
                    CompositionLocalProvider(LocalContentColor provides c.accent) { confirmButton() }
                }
            }
        }
    }
}

@Composable
private fun DialogAction(modifier: Modifier, background: Color, border: Color, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .background(background, shape)
            .border(0.75.dp, border, shape)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 通知型弹窗的正文和回答使用同一套 Markdown 排版，不把原始标记直接交给用户。 */
@Composable
fun MikaNoticeDialog(
    message: String,
    onDismiss: () -> Unit,
    title: String? = null,
    confirmText: String = stringResource(R.string.ok_got_it),
) {
    MikaDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = { MarkdownText(message, MikaType.Body.copy(color = MikaTheme.colors.ink2)) },
        confirmButton = { TextButton(onDismiss) { Text(confirmText) } },
    )
}
