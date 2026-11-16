package com.wangzan101.mika.ui

import android.graphics.BitmapFactory
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.mikaPress
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次答复的来源:每条是「第几步 · 在哪 · 标题」,下面是当时的画面;没有画面的(后台读的网页正文、文件、命令输出)是摘录原文。 */
@Composable
fun SourceScreen(vm: SourceViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ui by produceState<SourceUi?>(null, vm) { value = withContext(Dispatchers.IO) { vm.load() } }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val c = MikaTheme.colors
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.source_label), left = MikaIcons.Back, leftLabel = stringResource(R.string.back), onLeft = onBack)
            ui?.let { source ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 16.dp),
                ) {
                    source.legacyText?.takeIf(String::isNotBlank)?.let { legacy ->
                        item("legacy") { GroupCard { Text(legacy, Modifier.padding(16.dp), style = MikaType.Body, color = c.ink) } }
                    }
                    source.entries.forEachIndexed { index, entry ->
                        item("source-$index") {
                            GroupCard {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                                    Text(
                                        listOfNotNull(stringResource(R.string.step_number, entry.step), entry.place, entry.title).joinToString(" · "),
                                        style = MikaType.BodyStrong, color = c.ink,
                                    )
                                    // 有画面就只给画面:识别出的文字和画面重复;没有画面时摘录就是唯一的依据
                                    if (entry.screenshots.isEmpty()) {
                                        entry.text?.let { Text(it, Modifier.padding(top = 6.dp), style = MikaType.Caption, color = c.ink2) }
                                    }
                                    entry.screenshots.forEach { id -> SourceShot(vm, id) }
                                }
                            }
                        }
                    }
                    if (source.legacyText.isNullOrBlank() && source.entries.isEmpty()) {
                        item("empty") { EmptyNote(stringResource(R.string.source_none)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceShot(vm: SourceViewModel, id: Int) {
    val c = MikaTheme.colors
    val image by produceState<ImageBitmap?>(null, vm, id) {
        value = withContext(Dispatchers.IO) { vm.screen(id)?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap() }
    }
    var zoomed by remember { mutableStateOf(false) }
    image?.let {
        val shotShape = RoundedCornerShape(12.dp)
        val interaction = remember { MutableInteractionSource() }
        Image(
            it,
            stringResource(R.string.source_image),
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .mikaPress(interaction, pressedScale = 0.98f, shape = shotShape)
                .clip(shotShape)
                .border(0.75.dp, c.glassEdge, shotShape)
                .clickable(interactionSource = interaction, indication = null, onClickLabel = stringResource(R.string.zoom_in)) { zoomed = true },
            contentScale = ContentScale.FillWidth,
        )
        if (zoomed) ZoomedShot(it) { zoomed = false }
    }
}

/** 全屏看一张画面,点一下或按返回收起。 */
@Composable
private fun ZoomedShot(image: ImageBitmap, onClose: () -> Unit) {
    GlassZoomDialog(onDismiss = onClose, dismissLabel = stringResource(R.string.collapse)) {
        Image(image, stringResource(R.string.source_image), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/**
 * 全屏看图弹窗：API 31+ 开启跨窗背景模糊，使用自包含液态玻璃关闭按钮（backdrop = null），
 * 画面内容本身保持 100% 清晰无染色。
 */
@Composable
internal fun GlassZoomDialog(
    onDismiss: () -> Unit,
    dismissLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val blurBehindPx = with(density) { 20.dp.roundToPx() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView, c.isDark, blurBehindPx) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            if (window != null) {
                val wm = context.getSystemService(WindowManager::class.java)
                val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm?.isCrossWindowBlurEnabled == true
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(if (canBlur) (if (c.isDark) 0.56f else 0.36f) else (if (c.isDark) 0.72f else 0.52f))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canBlur) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        this.blurBehindRadius = blurBehindPx
                    }
                }
            }
            onDispose {}
        }
        Box(
            modifier
                .fillMaxSize()
                .background(c.bg.copy(alpha = if (c.isDark) 0.86f else 0.78f))
                .clickable(onClickLabel = dismissLabel, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            content()
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(16.dp),
            ) {
                BarIconButton(MikaIcons.Close, dismissLabel, onDismiss, glass = true, backdrop = null)
            }
        }
    }
}
