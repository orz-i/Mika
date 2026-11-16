package com.wangzan101.mika.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlin.math.abs

/** 能做背景模糊（Android 12 / API 31 起支持 RenderEffect）；API 30 下玻璃自动退回高通透磨砂回退层。 */
val GlassBlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 玻璃后面那层内容的画面：内容用 [glassSource] 画进来，浮层玻璃取它做实时模糊与 AGSL 折射。 */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
    @Volatile internal var recorded: Boolean = false
}

/** 当前作用域可用的实时玻璃背景。在 [GlassBackdropHost] 的内容子树内强制为 null，杜绝自递归。 */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** 创建可采集的玻璃背景；[enabled] 为假或 API < 31 时返回 null（零 GraphicsLayer 录制开销）。 */
@Composable
fun rememberGlassBackdrop(enabled: Boolean = true): GlassBackdrop? {
    if (!enabled || !GlassBlurSupported) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** 把这块内容记成玻璃的背景。玻璃本身不能在这块内容里面。 */
fun Modifier.glassSource(backdrop: GlassBackdrop?, enabled: Boolean = true): Modifier {
    if (backdrop == null || !enabled) {
        backdrop?.recorded = false
        return this
    }
    return onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            if (size.width >= 1f && size.height >= 1f && !backdrop.layer.isReleased) {
                backdrop.layer.record { this@drawWithContent.drawContent() }
                backdrop.recorded = true
                drawLayer(backdrop.layer)
            } else {
                backdrop.recorded = false
                drawContent()
            }
        }
}

/**
 * 隔离式玻璃宿主：内容区包进 [glassSource] 并强制屏蔽 [LocalGlassBackdrop] 为 null（防止自递归崩溃），
 * 浮层区提供 [LocalGlassBackdrop] 供顶栏、输入栏、胶囊、小窗、侧栏直接读取。
 */
@Composable
fun GlassBackdropHost(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.(GlassBackdrop?) -> Unit,
) {
    val backdrop = rememberGlassBackdrop(enabled = enabled)
    Box(modifier) {
        Box(Modifier.fillMaxSize().glassSource(backdrop, enabled = enabled)) {
            CompositionLocalProvider(LocalGlassBackdrop provides null) {
                content()
            }
        }
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                overlay(backdrop)
            }
        }
    }
}

/** 液态玻璃材质光学与层次参数。 */
@Immutable
data class GlassMaterial(
    val cornerRadius: Dp,
    val blur: Dp,
    val tintAlphaLight: Float,
    val tintAlphaDark: Float,
    val rim: Dp,
    val refraction: Float,
    val dispersion: Float,
    val centerZoom: Float = 0.04f,
    val saturation: Float = 1.32f,
    val brightness: Float = 1.04f,
    val backdropRevealLight: Float = 0.34f,
    val backdropRevealDark: Float = 0.16f,
    val sheenAlphaLight: Float = 0.20f,
    val sheenAlphaDark: Float = 0.14f,
    val shadowElevation: Dp = 14.dp,
    val shadowAlphaLight: Float = 0.24f,
    val shadowAlphaDark: Float = 0.42f,
    val borderWidth: Dp = 0.75.dp,
    val fallbackAlphaLight: Float = 0.88f,
    val fallbackAlphaDark: Float = 0.84f,
    val bottomBorderOnly: Boolean = false,
    val customBaseLight: Color? = null,
    val customBaseDark: Color? = null,
    val customTintLight: Color? = null,
    val customTintDark: Color? = null,
    val customBorderHighlightLight: Color? = null,
    val customBorderHighlightDark: Color? = null,
    val customBorderMid: Color? = null,
    val customBorderBottomLight: Color? = null,
    val customBorderBottomDark: Color? = null,
    val scrimColor: Color = Color.Transparent,
    val scrimAlpha: Float = 0f,
)

/** 各层级组件的标准液态玻璃材质预设。 */
object GlassMaterials {
    val TopBar = GlassMaterial(
        cornerRadius = 0.dp,
        blur = 16.dp,
        tintAlphaLight = 0.56f,
        tintAlphaDark = 0.60f,
        rim = 12.dp,
        refraction = 0.42f,
        dispersion = 0.05f,
        centerZoom = 0f,
        saturation = 1.24f,
        brightness = 1.03f,
        backdropRevealLight = 0.46f,
        backdropRevealDark = 0.26f,
        sheenAlphaLight = 0.10f,
        sheenAlphaDark = 0.08f,
        shadowElevation = 0.dp,
        shadowAlphaLight = 0f,
        shadowAlphaDark = 0f,
        borderWidth = 0.5.dp,
        fallbackAlphaLight = 0.88f,
        fallbackAlphaDark = 0.86f,
        bottomBorderOnly = true,
    )

    val Composer = GlassMaterial(
        cornerRadius = 28.dp,
        blur = 14.dp,
        tintAlphaLight = 0.46f,
        tintAlphaDark = 0.52f,
        rim = 22.dp,
        refraction = 1.22f,
        dispersion = 0.14f,
        centerZoom = 0.04f,
        saturation = 1.34f,
        brightness = 1.05f,
        backdropRevealLight = 0.36f,
        backdropRevealDark = 0.16f,
        sheenAlphaLight = 0.22f,
        sheenAlphaDark = 0.15f,
        shadowElevation = 14.dp,
        shadowAlphaLight = 0.22f,
        shadowAlphaDark = 0.42f,
        borderWidth = 0.85.dp,
        fallbackAlphaLight = 0.90f,
        fallbackAlphaDark = 0.86f,
    )

    val VoiceCard = GlassMaterial(
        cornerRadius = 22.dp,
        blur = 16.dp,
        tintAlphaLight = 0.48f,
        tintAlphaDark = 0.54f,
        rim = 26.dp,
        refraction = 1.28f,
        dispersion = 0.15f,
        centerZoom = 0.04f,
        saturation = 1.32f,
        brightness = 1.04f,
        backdropRevealLight = 0.34f,
        backdropRevealDark = 0.15f,
        sheenAlphaLight = 0.22f,
        sheenAlphaDark = 0.14f,
        shadowElevation = 16.dp,
        shadowAlphaLight = 0.24f,
        shadowAlphaDark = 0.44f,
        borderWidth = 0.85.dp,
        fallbackAlphaLight = 0.92f,
        fallbackAlphaDark = 0.88f,
    )

    val IconCircle = GlassMaterial(
        cornerRadius = 20.dp,
        blur = 12.dp,
        tintAlphaLight = 0.44f,
        tintAlphaDark = 0.50f,
        rim = 16.dp,
        refraction = 1.32f,
        dispersion = 0.16f,
        centerZoom = 0.05f,
        saturation = 1.36f,
        brightness = 1.06f,
        backdropRevealLight = 0.38f,
        backdropRevealDark = 0.18f,
        sheenAlphaLight = 0.24f,
        sheenAlphaDark = 0.16f,
        shadowElevation = 10.dp,
        shadowAlphaLight = 0.20f,
        shadowAlphaDark = 0.38f,
        borderWidth = 0.85.dp,
        fallbackAlphaLight = 0.90f,
        fallbackAlphaDark = 0.86f,
    )

    val FloatingPill = GlassMaterial(
        cornerRadius = 20.dp,
        blur = 10.dp,
        tintAlphaLight = 0.46f,
        tintAlphaDark = 0.52f,
        rim = 18.dp,
        refraction = 1.25f,
        dispersion = 0.14f,
        centerZoom = 0.04f,
        saturation = 1.34f,
        brightness = 1.05f,
        backdropRevealLight = 0.36f,
        backdropRevealDark = 0.16f,
        sheenAlphaLight = 0.22f,
        sheenAlphaDark = 0.14f,
        shadowElevation = 10.dp,
        shadowAlphaLight = 0.20f,
        shadowAlphaDark = 0.40f,
        borderWidth = 0.75.dp,
        fallbackAlphaLight = 0.90f,
        fallbackAlphaDark = 0.86f,
    )

    val PipFrame = GlassMaterial(
        cornerRadius = 16.dp,
        blur = 12.dp,
        tintAlphaLight = 0.46f,
        tintAlphaDark = 0.54f,
        rim = 18.dp,
        refraction = 1.25f,
        dispersion = 0.15f,
        centerZoom = 0.03f,
        saturation = 1.30f,
        brightness = 1.04f,
        backdropRevealLight = 0.34f,
        backdropRevealDark = 0.16f,
        sheenAlphaLight = 0.20f,
        sheenAlphaDark = 0.14f,
        shadowElevation = 14.dp,
        shadowAlphaLight = 0.26f,
        shadowAlphaDark = 0.48f,
        borderWidth = 0.85.dp,
        fallbackAlphaLight = 0.90f,
        fallbackAlphaDark = 0.88f,
    )

    val Drawer = GlassMaterial(
        cornerRadius = 24.dp,
        blur = 22.dp,
        tintAlphaLight = 0.64f,
        tintAlphaDark = 0.68f,
        rim = 18.dp,
        refraction = 0.65f,
        dispersion = 0.08f,
        centerZoom = 0f,
        saturation = 1.22f,
        brightness = 1.02f,
        backdropRevealLight = 0.32f,
        backdropRevealDark = 0.14f,
        sheenAlphaLight = 0.14f,
        sheenAlphaDark = 0.10f,
        shadowElevation = 18.dp,
        shadowAlphaLight = 0.22f,
        shadowAlphaDark = 0.46f,
        borderWidth = 0.75.dp,
        fallbackAlphaLight = 0.95f,
        fallbackAlphaDark = 0.94f,
    )

    val Dialog = GlassMaterial(
        cornerRadius = MikaRadius.Dialog,
        blur = 0.dp,
        tintAlphaLight = 0.26f,
        tintAlphaDark = 0.24f,
        rim = 0.dp,
        refraction = 0f,
        dispersion = 0f,
        centerZoom = 0f,
        sheenAlphaLight = 0.22f,
        sheenAlphaDark = 0.14f,
        shadowElevation = 0.dp,
        shadowAlphaLight = 0f,
        shadowAlphaDark = 0f,
        borderWidth = 0.85.dp,
        fallbackAlphaLight = 0.90f,
        fallbackAlphaDark = 0.86f,
    )

    val Sheet = GlassMaterial(
        cornerRadius = MikaRadius.Sheet,
        blur = 0.dp,
        tintAlphaLight = 0.24f,
        tintAlphaDark = 0.22f,
        rim = 0.dp,
        refraction = 0f,
        dispersion = 0f,
        centerZoom = 0f,
        sheenAlphaLight = 0.18f,
        sheenAlphaDark = 0.12f,
        shadowElevation = 0.dp,
        shadowAlphaLight = 0f,
        shadowAlphaDark = 0f,
        borderWidth = 0.85.dp,
        fallbackAlphaLight = 0.92f,
        fallbackAlphaDark = 0.88f,
    )

    val FloatingMenu = GlassMaterial(
        cornerRadius = MikaRadius.Card,
        blur = 0.dp,
        tintAlphaLight = 0.24f,
        tintAlphaDark = 0.22f,
        rim = 0.dp,
        refraction = 0f,
        dispersion = 0f,
        centerZoom = 0f,
        sheenAlphaLight = 0.20f,
        sheenAlphaDark = 0.12f,
        shadowElevation = 16.dp,
        shadowAlphaLight = 0.22f,
        shadowAlphaDark = 0.44f,
        borderWidth = 0.85.dp,
        fallbackAlphaLight = 0.90f,
        fallbackAlphaDark = 0.86f,
    )
}

/** 方向性三段高光描边画刷（左上亮 -> 中段过渡 -> 右下收边）。 */
internal fun specularBorderBrush(
    colors: MikaColors,
    highlight: Color = colors.specularHighlight,
    mid: Color = colors.specularMid,
    bottom: Color = colors.specularBottom,
): Brush = Brush.linearGradient(listOf(highlight, mid, bottom))

private class GlassEffectCache {
    var width: Float = -1f
    var height: Float = -1f
    var density: Float = -1f
    var layoutDirection: LayoutDirection? = null
    var shape: Shape? = null
    var material: GlassMaterial? = null
    var dark: Boolean = false
    var baseColor: Color = Color.Unspecified
    var renderEffect: ComposeRenderEffect? = null
    @RequiresApi(33)
    var shader: RuntimeShader? = null
}

/**
 * 共享液态玻璃修饰符：
 * - API 33+（有有效 backdrop）：GraphicsLayer + 缓存 AGSL RuntimeShader（四圆角折射 + RGB 色散 + 中心微放大 + 饱和度/亮度）+ BlurEffect 链。
 * - API 31–32（有有效 backdrop）：GraphicsLayer + BlurEffect（>= 0.5px）+ 高通透着色基底。
 * - API 30 或 backdrop == null：高通透磨砂回退底 + 左上径向高光 + 方向性高光描边 + 阴影（零离屏录制）。
 *
 * 严格避免：
 * - `origin` 仅在 draw 阶段读取用于平移，绝不参与缓存键或重组；
 * - 内部子内容 `drawContent()` 在 `glassLayer` 之外绘制，文字和图标永不被连带模糊。
 */
@Composable
fun Modifier.liquidGlass(
    material: GlassMaterial,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
    shape: Shape = RoundedCornerShape(material.cornerRadius),
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    val c = MikaTheme.colors
    val dark = c.isDark
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val useBackdropLayer = backdrop != null && GlassBlurSupported
    val glassLayer = if (useBackdropLayer) rememberGraphicsLayer() else null
    val effectCache = remember { GlassEffectCache() }
    val originState = remember { mutableStateOf(Offset.Zero) }

    val base = if (dark) {
        material.customBaseDark ?: c.glassBackdropBase
    } else {
        material.customBaseLight ?: c.glassBackdropBase
    }
    val tint = if (dark) {
        material.customTintDark ?: c.glassTint
    } else {
        material.customTintLight ?: c.glassTint
    }
    val tintAlpha = if (dark) material.tintAlphaDark else material.tintAlphaLight
    val fallbackAlpha = if (dark) material.fallbackAlphaDark else material.fallbackAlphaLight
    val sheenAlpha = if (dark) material.sheenAlphaDark else material.sheenAlphaLight

    val borderHighlight = if (dark) {
        material.customBorderHighlightDark ?: c.specularHighlight
    } else {
        material.customBorderHighlightLight ?: c.specularHighlight
    }
    val borderMid = material.customBorderMid ?: c.specularMid
    val borderBottom = if (dark) {
        material.customBorderBottomDark ?: c.specularBottom
    } else {
        material.customBorderBottomLight ?: c.specularBottom
    }
    val borderBrush = remember(borderHighlight, borderMid, borderBottom) {
        specularBorderBrush(c, borderHighlight, borderMid, borderBottom)
    }

    val shadowAlpha = if (dark) material.shadowAlphaDark else material.shadowAlphaLight
    val shadowMod = if (material.shadowElevation > 0.dp && shadowAlpha > 0f) {
        Modifier.shadow(
            elevation = material.shadowElevation,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = shadowAlpha),
            spotColor = Color.Black.copy(alpha = (shadowAlpha * 1.6f).coerceAtMost(0.85f)),
        )
    } else {
        Modifier
    }

    val posMod = if (useBackdropLayer) {
        Modifier.onGloballyPositioned { originState.value = it.positionInRoot() }
    } else {
        Modifier
    }

    val borderMod = if (!material.bottomBorderOnly && material.borderWidth > 0.dp) {
        Modifier.border(material.borderWidth, borderBrush, shape)
    } else {
        Modifier
    }

    return this
        .then(shadowMod)
        .clip(shape)
        .then(posMod)
        .drawWithContent {
            val w = size.width
            val h = size.height
            val canSampleBackdrop = glassLayer != null &&
                !glassLayer.isReleased &&
                backdrop != null &&
                backdrop.recorded &&
                !backdrop.layer.isReleased &&
                backdrop.layer.size.width > 0 &&
                backdrop.layer.size.height > 0 &&
                w >= 1f &&
                h >= 1f

            if (canSampleBackdrop) {
                val effect = effectCache.getOrCreate(
                    width = w,
                    height = h,
                    density = density,
                    layoutDirection = layoutDirection,
                    shape = shape,
                    material = material,
                    dark = dark,
                    baseColor = base,
                )
                glassLayer!!.clip = true
                glassLayer.renderEffect = effect
                glassLayer.record {
                    val delta = backdrop!!.origin - originState.value
                    translate(delta.x, delta.y) {
                        drawLayer(backdrop.layer)
                    }
                    if (material.scrimAlpha > 0f && material.scrimColor.alpha > 0f) {
                        drawRect(material.scrimColor.copy(alpha = material.scrimAlpha))
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        val reveal = if (dark) material.backdropRevealDark else material.backdropRevealLight
                        val overlayAlpha = (1f - reveal).coerceIn(0.45f, 0.92f)
                        drawRect(base.copy(alpha = overlayAlpha))
                    }
                }
                drawLayer(glassLayer)
                if (tintAlpha > 0f) {
                    drawRect(tint.copy(alpha = tintAlpha))
                }
            } else {
                // 回退分支（API 30 / 无 backdrop / backdrop 未就绪）
                if (material.scrimAlpha > 0f && material.scrimColor.alpha > 0f && backdrop == null) {
                    // 仅保留清晰基底
                }
                val baseSurface = if (material.customBaseDark != null || material.customBaseLight != null) {
                    base.copy(alpha = fallbackAlpha)
                } else {
                    val cardBase = if (material.shadowElevation >= 16.dp) c.glassCardElevated else c.glassCard
                    cardBase.copy(alpha = fallbackAlpha)
                }
                drawRect(baseSurface)
                if (tintAlpha > 0f && fallbackAlpha < 1f) {
                    drawRect(tint.copy(alpha = (tintAlpha * 0.35f).coerceAtMost(0.32f)))
                }
            }

            if (sheenAlpha > 0f && w > 0f) {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = sheenAlpha), Color.Transparent),
                        center = Offset.Zero,
                        radius = (w * 0.82f).coerceAtLeast(1f),
                    ),
                )
            }

            drawContent()

            if (material.bottomBorderOnly && material.borderWidth > 0.dp && h > 0f) {
                val stroke = material.borderWidth.toPx()
                val y = h - stroke * 0.5f
                val edgeColor = if (dark) c.specularHighlight.copy(alpha = 0.24f) else c.glassEdge
                drawLine(edgeColor, Offset(0f, y), Offset(w, y), strokeWidth = stroke)
            }
        }
        .then(borderMod)
}

private fun GlassEffectCache.getOrCreate(
    width: Float,
    height: Float,
    density: Density,
    layoutDirection: LayoutDirection,
    shape: Shape,
    material: GlassMaterial,
    dark: Boolean,
    baseColor: Color,
): ComposeRenderEffect? {
    val d = density.density
    val same = abs(this.width - width) < 0.5f &&
        abs(this.height - height) < 0.5f &&
        this.density == d &&
        this.layoutDirection == layoutDirection &&
        this.shape == shape &&
        this.material == material &&
        this.dark == dark &&
        this.baseColor == baseColor
    if (same) return this.renderEffect

    this.width = width
    this.height = height
    this.density = d
    this.layoutDirection = layoutDirection
    this.shape = shape
    this.material = material
    this.dark = dark
    this.baseColor = baseColor

    val blurPx = with(density) { material.blur.toPx() }
    val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        buildRefractedEffect(width, height, density, layoutDirection, shape, material, dark, baseColor, blurPx)
    } else if (blurPx >= 0.5f) {
        BlurEffect(blurPx, blurPx, TileMode.Clamp)
    } else {
        null
    }
    this.renderEffect = effect
    return effect
}

@RequiresApi(33)
private fun GlassEffectCache.buildRefractedEffect(
    width: Float,
    height: Float,
    density: Density,
    layoutDirection: LayoutDirection,
    shape: Shape,
    material: GlassMaterial,
    dark: Boolean,
    baseColor: Color,
    blurPx: Float,
): ComposeRenderEffect {
    val shader = this.shader ?: RuntimeShader(GLASS_SHADER).also { this.shader = it }
    val size = androidx.compose.ui.geometry.Size(width, height)
    val (tl, tr, br, bl) = extractCornerRadiiPx(shape, size, density, layoutDirection, with(density) { material.cornerRadius.toPx() })
    val rimPx = with(density) { material.rim.toPx() }
    val reveal = if (dark) material.backdropRevealDark else material.backdropRevealLight
    shader.setFloatUniform("extent", width, height)
    shader.setFloatUniform("radii", tl, tr, br, bl)
    shader.setFloatUniform("lens", rimPx, material.refraction, material.dispersion, material.centerZoom)
    shader.setFloatUniform("filter", material.saturation, material.brightness)
    shader.setFloatUniform("backdropTone", baseColor.red, baseColor.green, baseColor.blue, reveal)
    val refraction = RenderEffect.createRuntimeShaderEffect(shader, "image")
    val combined = if (blurPx >= 0.5f) {
        RenderEffect.createChainEffect(
            refraction,
            RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP),
        )
    } else {
        refraction
    }
    return combined.asComposeRenderEffect()
}

private fun extractCornerRadiiPx(
    shape: Shape,
    size: androidx.compose.ui.geometry.Size,
    density: Density,
    layoutDirection: LayoutDirection,
    fallbackPx: Float,
): FloatArray {
    if (shape === RectangleShape) return floatArrayOf(0f, 0f, 0f, 0f)
    val cornerShape = shape as? CornerBasedShape
        ?: return floatArrayOf(fallbackPx, fallbackPx, fallbackPx, fallbackPx)
    val ts = cornerShape.topStart.toPx(size, density)
    val te = cornerShape.topEnd.toPx(size, density)
    val be = cornerShape.bottomEnd.toPx(size, density)
    val bs = cornerShape.bottomStart.toPx(size, density)
    return if (layoutDirection == LayoutDirection.Rtl) {
        floatArrayOf(te, ts, bs, be)
    } else {
        floatArrayOf(ts, te, be, bs)
    }
}

/**
 * 触感弹簧按压修饰符：监听 [interactionSource]（不抢占手势，完全兼容 clickable 与测试 performClick），
 * 非 reduceMotion 时做微缩回弹 + 顶部高光瞬时增益；reduceMotion 时缩放恒为 1f 并 snap 微光。
 */
@Composable
fun Modifier.mikaPress(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = 0.965f,
    specularBoost: Boolean = true,
    shape: Shape? = null,
): Modifier {
    val reduce = MikaTheme.reduceMotion
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduce) pressedScale else 1f,
        animationSpec = MikaMotion.pressSpring(reduce),
        label = "mika-press-scale",
    )
    val boost by animateFloatAsState(
        targetValue = if (pressed && enabled && specularBoost) 1f else 0f,
        animationSpec = MikaMotion.pressSpring(reduce),
        label = "mika-press-boost",
    )
    val pressColor = MikaTheme.colors.specularPress
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (boost > 0.005f && pressColor.alpha > 0f) {
                val color = pressColor.copy(alpha = pressColor.alpha * boost)
                if (shape != null) {
                    drawOutline(shape.createOutline(size, layoutDirection, this), color)
                } else {
                    drawRect(color)
                }
            }
        }
}

internal fun DrawScope.drawGlassSheen(alpha: Float) {
    if (alpha <= 0f || size.width <= 0f) return
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = alpha), Color.Transparent),
            center = Offset.Zero,
            radius = (size.width * 0.82f).coerceAtLeast(1f),
        ),
    )
}

// 通用四圆角液态玻璃 AGSL 着色器：四圆角 SDF 边缘向内折射、RGB 色散与中心轻微凸透放大。
private const val GLASS_SHADER = """
    uniform shader image;
    uniform float2 extent;
    uniform float4 radii;
    uniform float4 lens;
    uniform float2 filter;
    uniform float4 backdropTone;

    half3 sampleAt(float2 p) {
        return image.eval(clamp(p, float2(0.5), max(float2(0.5), extent - 0.5))).rgb;
    }

    half4 main(float2 p) {
        float2 halfSize = extent * 0.5;
        float maxR = min(halfSize.x, halfSize.y);
        float2 center = p - halfSize;
        float radius = min(
            (center.x >= 0.0)
                ? ((center.y >= 0.0) ? radii.z : radii.y)
                : ((center.y >= 0.0) ? radii.w : radii.x),
            maxR
        );
        float2 q = abs(center) - (halfSize - radius);
        float2 corner = max(q, float2(0.0));
        float cornerLength = length(corner);
        float distance = radius - cornerLength - min(max(q.x, q.y), 0.0);
        float sx = center.x >= 0.0 ? 1.0 : -1.0;
        float sy = center.y >= 0.0 ? 1.0 : -1.0;
        float2 normal = cornerLength > 0.001
            ? float2(sx, sy) * corner / cornerLength
            : (q.x > q.y ? float2(sx, 0.0) : float2(0.0, sy));
        float rim = max(min(lens.x, maxR), 1.0);
        float strength = pow(clamp(1.0 - distance / rim, 0.0, 1.0), 2.6);
        float2 shift = normal * (rim * lens.y * strength);
        float2 base = p - center * lens.w;
        half3 rgb = half3(
            sampleAt(base - shift * (1.0 + lens.z)).r,
            sampleAt(base - shift).g,
            sampleAt(base - shift * (1.0 - lens.z)).b
        );
        half gray = dot(rgb, half3(0.2126, 0.7152, 0.0722));
        half3 filtered = clamp(mix(half3(gray), rgb, half(filter.x)) * half(filter.y), 0.0, 1.0);
        return half4(mix(half3(backdropTone.rgb), filtered, half(backdropTone.a)), 1.0);
    }
"""
