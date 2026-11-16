package com.wangzan101.mika.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 页面底：干净的冷白/深曜底色搭配克制的径向环境光晕（缓存着色器，零滚动开销）。 */
@Composable
fun PageBackground(
    modifier: Modifier = Modifier,
    ambientGlow: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = MikaTheme.colors
    val glowModifier = remember(c, ambientGlow) {
        if (!ambientGlow) Modifier
        else Modifier.drawWithCache {
            val minDim = size.minDimension.coerceAtLeast(1f)
            val primary = c.ambientOrbPrimary.takeIf { it.alpha > 0f }?.let {
                Brush.radialGradient(
                    listOf(it, Color.Transparent),
                    center = Offset(size.width * 0.14f, size.height * 0.10f),
                    radius = minDim * 0.78f,
                )
            }
            val secondary = c.ambientOrbSecondary.takeIf { it.alpha > 0f }?.let {
                Brush.radialGradient(
                    listOf(it, Color.Transparent),
                    center = Offset(size.width * 0.88f, size.height * 0.36f),
                    radius = minDim * 0.72f,
                )
            }
            val tertiary = c.ambientOrbTertiary.takeIf { it.alpha > 0f }?.let {
                Brush.radialGradient(
                    listOf(it, Color.Transparent),
                    center = Offset(size.width * 0.32f, size.height * 0.86f),
                    radius = minDim * 0.82f,
                )
            }
            onDrawBehind {
                primary?.let { drawRect(it) }
                secondary?.let { drawRect(it) }
                tertiary?.let { drawRect(it) }
            }
        }
    }
    Box(modifier.fillMaxSize().background(c.bg).then(glowModifier), content = content)
}

/**
 * 卡片的底：轻量半透明磨砂底 + 方向性高光渐变描边 + 极淡阴影（严禁对列表卡片加 RenderEffect，保持滚动流畅）。
 */
fun Modifier.cardSurface(
    colors: MikaColors,
    shape: Shape = MikaShapes.Card,
    elevated: Boolean = false,
    translucent: Boolean = true,
): Modifier {
    val fill = when {
        !translucent -> colors.surface
        elevated -> colors.glassCardElevated
        else -> colors.glassCard
    }
    val shadowMod = if (colors.cardShadow.alpha > 0f) {
        shadow(
            elevation = if (elevated) 10.dp else 6.dp,
            shape = shape,
            clip = false,
            ambientColor = colors.cardShadow.copy(alpha = if (elevated) 0.24f else 0.18f),
            spotColor = colors.cardShadow.copy(alpha = if (elevated) 0.20f else 0.14f),
        )
    } else {
        this
    }
    return shadowMod
        .background(fill, shape)
        .border(0.75.dp, specularBorderBrush(colors), shape)
}

/** 悬浮元素的阴影(输入栏、浮在内容上的按钮)。 */
fun Modifier.floatShadow(colors: MikaColors, shape: Shape): Modifier =
    shadow(14.dp, shape, clip = false, ambientColor = colors.floatShadow.copy(alpha = 0.28f), spotColor = colors.floatShadow.copy(alpha = 0.34f))

/** 一张卡片,内容裁在圆角里(行的按压效果不出角)。 */
@Composable
fun MikaCard(modifier: Modifier = Modifier, shape: Shape = MikaShapes.Card, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().cardSurface(MikaTheme.colors, shape).clip(shape), content = content)
}

/**
 * 分组卡片:一组 [ListRow]。下面自带 12dp 间距,和下一组的 [GroupLabel](上 8)合起来是分组之间的 20。
 * 行与行的分隔线由行自己画,从文字处开始。
 */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    MikaCard(modifier.padding(bottom = 12.dp), content = content)
}

/** 状态卡:左边圆形图标,右边标题和说明。用于页面顶上的总状态。 */
@Composable
fun StateCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tone: TileTone = TileTone.Neutral,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MikaTheme.colors
    MikaCard(modifier.padding(bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val (bg, fg) = tone.colors(c)
            Box(
                Modifier.size(36.dp).background(bg, CircleShape).border(0.5.dp, c.specularHighlight.copy(alpha = if (c.isDark) 0.18f else 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = fg)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MikaType.BodyStrong, color = c.ink)
                if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MikaType.Caption, color = c.ink3)
            }
            trailing()
        }
    }
}

/**
 * 玻璃输入栏/悬浮胶囊的外壳：半透明底 + 实时背景模糊/AGSL 折射 + 高光描边 + 悬浮阴影，高至少 52。
 * [backdrop] 为 null（不支持模糊、或后面没有内容）时自动退回高通透磨砂底。
 */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
    shape: Shape = MikaShapes.Pill,
    material: GlassMaterial = GlassMaterials.Composer,
    content: @Composable RowScope.() -> Unit,
) {
    Box(modifier.liquidGlass(material = material, backdrop = backdrop, shape = shape)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MikaSize.Composer).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}
