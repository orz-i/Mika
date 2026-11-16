package com.wangzan101.mika.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 操作面板里的一项;[destructive] 为真时文字用出错红。 */
class SheetAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * 全 App 可复用的液态玻璃底部面板容器：透明外窗 + 定制玻璃拖拽手柄 + 自包含磨砂高光面板，
 * API 31+ 开启跨窗背板模糊 (`FLAG_BLUR_BEHIND`)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MikaBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    shape: Shape = MikaShapes.Sheet,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val blurBehindPx = with(density) { 18.dp.roundToPx() }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = Color.Transparent,
        contentColor = c.ink,
        tonalElevation = 0.dp,
        scrimColor = c.scrim,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        val sheetView = LocalView.current
        DisposableEffect(sheetView, c.isDark, blurBehindPx) {
            val window = (sheetView.parent as? DialogWindowProvider)?.window
            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wm = context.getSystemService(WindowManager::class.java)
                if (wm?.isCrossWindowBlurEnabled == true) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        this.blurBehindRadius = blurBehindPx
                    }
                }
            }
            onDispose {}
        }
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlass(GlassMaterials.Sheet, backdrop = null, shape = shape)
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(
                            if (c.isDark) c.specularHighlight.copy(alpha = 0.36f) else c.hair,
                            MikaShapes.Pill,
                        ),
                )
            }
            content()
        }
    }
}

/**
 * 点一行后从底部升起的操作面板:顶上一行说明是在对什么操作,下面每项一行。
 * 点任何一项都会先执行它、再关掉面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    MikaBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            if (!title.isNullOrEmpty()) {
                Text(
                    title, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MikaType.Caption, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            actions.forEachIndexed { i, a ->
                ListRow(
                    null, a.label, first = i == 0,
                    titleColor = if (a.destructive) c.bad else c.ink,
                    onClick = { a.onClick(); onDismiss() },
                )
            }
        }
    }
}
