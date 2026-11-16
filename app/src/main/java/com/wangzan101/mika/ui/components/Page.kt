package com.wangzan101.mika.ui.components

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaSpace

/**
 * 子页面的骨架：页面底 + 悬浮玻璃顶栏 + 一列可滚动的内容（左右留 [MikaSpace.Page]，避开导航栏）。
 * [floatingTopBar] 为真时通过 [GlassBackdropHost] 让内容在顶栏下方平滑穿透折射，并在内容顶部留出顶栏真实高度的占位，确保初始不遮挡。
 */
@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    floatingTopBar: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backLabel = stringResource(R.string.back)
    if (!floatingTopBar) {
        PageBackground(modifier) {
            Column(Modifier.fillMaxSize()) {
                TopBar(title, MikaIcons.Back, onBack, backLabel)
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = MikaSpace.Page),
                ) {
                    Spacer(Modifier.height(MikaSpace.S4))
                    content()
                    Spacer(Modifier.height(MikaSpace.S24))
                }
            }
        }
        return
    }
    val density = LocalDensity.current
    val initialTopPx = WindowInsets.statusBars.getTop(density) + with(density) { MikaSize.TopBar.roundToPx() }
    var topBarHeightPx by remember { mutableIntStateOf(initialTopPx) }
    val topBarHeightDp = with(density) { topBarHeightPx.toDp() }
    GlassBackdropHost(
        modifier = modifier.fillMaxSize(),
        content = {
            PageBackground {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(topBarHeightDp))
                    Column(
                        Modifier.weight(1f).fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = MikaSpace.Page),
                    ) {
                        Spacer(Modifier.height(MikaSpace.S4))
                        content()
                        Spacer(Modifier.height(MikaSpace.S24))
                    }
                }
            }
        },
        overlay = {
            Box(Modifier.onSizeChanged { if (it.height > 0) topBarHeightPx = it.height }) {
                TopBar(title, MikaIcons.Back, onBack, backLabel)
            }
        },
    )
}
