package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.components.GlassBackdrop
import com.wangzan101.mika.ui.components.GlassMaterial
import com.wangzan101.mika.ui.components.liquidGlass
import com.wangzan101.mika.ui.theme.MikaTheme

/** 由调样台导出的参数。网页像素在安卓布局里按 dp 使用。 */
internal enum class UpdateGlassMaterial(
    val width: Int,
    val height: Int,
    val radius: Int,
    val blur: Int,
    val tint: Float,
    val rim: Int,
    val refraction: Float,
    val dispersion: Float,
    val saturation: Float,
    val brightness: Float,
    val shadow: Float,
    val darkBackdrop: Color,
    val scrim: Float = 0f,
) {
    Capsule(207, 48, 40, 4, 0.42f, 20, 1.35f, 0.18f, 1.45f, 1.08f, 0.30f, Color(0xFF172C29)),
    Confirmation(236, 178, 35, 12, 0.55f, 31, 1.40f, 0.17f, 1.40f, 1.06f, 0.35f, Color(0xFF202D35), 0.28f),
}

internal val UpdateGlassInk: Color
    @Composable get() = if (MikaTheme.colors.isDark) Color.White.copy(alpha = 0.94f) else MikaTheme.colors.ink
internal val UpdateGlassSecondaryInk: Color
    @Composable get() = if (MikaTheme.colors.isDark) Color(0xFFEBEBF5).copy(alpha = 0.62f) else MikaTheme.colors.ink2
internal val UpdateGlassScrim = Color(0xFF16221B)
private val NightTint = Color(0xFF0C1028)

@Composable
internal fun UpdateGlassSurface(
    material: UpdateGlassMaterial,
    backdrop: GlassBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = remember(material.radius) { RoundedCornerShape(material.radius.dp) }
    val glassMaterial = remember(material) { material.toGlassMaterial() }
    Box(modifier.liquidGlass(glassMaterial, backdrop = backdrop, shape = shape), content = content)
}

private fun UpdateGlassMaterial.toGlassMaterial(): GlassMaterial = GlassMaterial(
    cornerRadius = radius.dp,
    blur = blur.dp,
    tintAlphaLight = tint,
    tintAlphaDark = tint,
    rim = rim.dp,
    refraction = refraction,
    dispersion = dispersion,
    centerZoom = 0.05f,
    saturation = saturation,
    brightness = brightness,
    backdropRevealLight = 0.33f,
    backdropRevealDark = 0.095f,
    sheenAlphaLight = 0.23f,
    sheenAlphaDark = 0.23f,
    shadowElevation = 20.dp,
    shadowAlphaLight = shadow,
    shadowAlphaDark = shadow,
    borderWidth = 1.dp,
    fallbackAlphaLight = 1f,
    fallbackAlphaDark = 1f,
    customBaseLight = Color(0xFFE9F1E6),
    customBaseDark = darkBackdrop,
    customTintLight = Color.White,
    customTintDark = NightTint,
    customBorderHighlightLight = Color.White.copy(alpha = 0.96f),
    customBorderHighlightDark = Color.White.copy(alpha = 0.52f),
    customBorderMid = Color.White.copy(alpha = 0.09f),
    customBorderBottomLight = Color.Black.copy(alpha = 0.11f),
    customBorderBottomDark = Color.White.copy(alpha = 0.21f),
    scrimColor = UpdateGlassScrim,
    scrimAlpha = scrim,
)
