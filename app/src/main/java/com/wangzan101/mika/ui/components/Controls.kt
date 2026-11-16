package com.wangzan101.mika.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------- 开关 ----------

/**
 * 液态玻璃开关：44×26，打开时强调蓝渐变伴轻柔光晕，切换过程带弹簧物理与高光滑块形变。
 * 严格遵守 [MikaTheme.reduceMotion]；[onCheckedChange] 为空时只显示(整行可点时用)。
 */
@Composable
fun MikaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = MikaTheme.colors
    val reduce = MikaTheme.reduceMotion
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val position by animateFloatAsState(if (checked) 1f else 0f, MikaMotion.switchSpring(reduce), label = "switch")
    val trackBottom by animateColorAsState(if (checked) c.accent else c.glassTonal, MikaMotion.switchSpring(reduce), label = "switch-track")
    val trackTop by animateColorAsState(if (checked) c.accentHighlight else c.glassSubtle, MikaMotion.switchSpring(reduce), label = "switch-track-top")
    val pressStretch by animateFloatAsState(if (pressed && enabled && !reduce) 1f else 0f, MikaMotion.pressSpring(reduce), label = "switch-press")
    val thumb = c.thumb
    val shadowRing = c.floatShadow.copy(alpha = 0.16f)
    Box(
        modifier
            .then(
                if (onCheckedChange != null) {
                    Modifier.minimumInteractiveComponentSize()
                        .toggleable(checked, enabled = enabled, role = Role.Switch, interactionSource = interaction, indication = null, onValueChange = onCheckedChange)
                } else {
                    Modifier
                },
            )
            .size(44.dp, 26.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .drawBehind {
                val corner = CornerRadius(size.height / 2)
                if (position > 0.02f && c.accentGlow.alpha > 0f) {
                    val pad = 1.5.dp.toPx()
                    drawRoundRect(
                        c.accentGlow.copy(alpha = c.accentGlow.alpha * 0.45f * position),
                        topLeft = Offset(-pad, -pad),
                        size = Size(size.width + pad * 2, size.height + pad * 2),
                        cornerRadius = CornerRadius(size.height / 2 + pad),
                    )
                }
                drawRoundRect(Brush.verticalGradient(listOf(trackTop, trackBottom)), cornerRadius = corner)
                drawRoundRect(
                    if (checked) Color.White.copy(alpha = 0.28f) else c.glassEdge,
                    cornerRadius = corner,
                    style = Stroke(0.75.dp.toPx()),
                )
                val r = 11.dp.toPx()
                val inset = 2.dp.toPx()
                val travel = (size.width - 2 * (inset + r)).coerceAtLeast(0f)
                val cx = inset + r + travel * position
                val cy = size.height / 2
                val glide = if (reduce) 0f else sin(position.coerceIn(0f, 1f) * PI).toFloat()
                val rx = r * (1f + 0.14f * maxOf(glide, pressStretch * 0.8f))
                val ry = r
                val maxRx = (size.width - inset * 2) * 0.5f
                val clampedRx = rx.coerceAtMost(maxRx)
                val thumbTopLeft = Offset(cx - clampedRx, cy - ry)
                val thumbSize = Size(clampedRx * 2, ry * 2)
                drawRoundRect(
                    shadowRing,
                    topLeft = Offset(thumbTopLeft.x - 0.4.dp.toPx(), thumbTopLeft.y + 0.7.dp.toPx()),
                    size = Size(thumbSize.width + 0.8.dp.toPx(), thumbSize.height + 0.5.dp.toPx()),
                    cornerRadius = CornerRadius(ry),
                )
                drawRoundRect(thumb, topLeft = thumbTopLeft, size = thumbSize, cornerRadius = CornerRadius(ry))
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.9f), Color.Transparent)),
                    topLeft = thumbTopLeft,
                    size = thumbSize,
                    cornerRadius = CornerRadius(ry),
                    style = Stroke(0.75.dp.toPx()),
                )
            },
    )
}

// ---------- 胶囊 ----------

enum class PillTone { Ok, Warn, Neutral, Accent }

/** 状态胶囊:可用、需要你、已停用;强调色的标明「在用」(例如模型在给哪个用途干活)。 */
@Composable
fun StatusPill(text: String, tone: PillTone = PillTone.Neutral, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val (bg, fg, rim) = when (tone) {
        PillTone.Ok -> Triple(c.okSoft, c.ok, c.ok.copy(alpha = 0.24f))
        PillTone.Warn -> Triple(c.warnSoft, c.warn, c.warnSolid.copy(alpha = 0.28f))
        PillTone.Accent -> Triple(c.accentSoft, c.accent, c.accent.copy(alpha = 0.24f))
        PillTone.Neutral -> Triple(c.glassSubtle, c.ink2, c.glassEdge)
    }
    Text(
        text,
        modifier.background(bg, MikaShapes.Pill).border(0.5.dp, rim, MikaShapes.Pill).padding(horizontal = 9.dp, vertical = 3.dp),
        style = PillText,
        color = fg,
        maxLines = 1,
    )
}

private val PillText = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)

// ---------- 按钮 ----------

/** 主按钮(强调蓝渐变+光晕)、浅色次按钮(磨砂玻璃卡)、浅填充按钮、文字按钮、实时液态玻璃按钮。 */
enum class ButtonKind { Primary, Secondary, Tonal, Text, Glass }

/** 大:页面底部 52;中:卡片里并排 42;小:行内 32 的胶囊。 */
enum class ButtonSize(val height: Dp, val shape: RoundedCornerShape, val text: TextStyle, val padding: Dp) {
    Large(MikaSize.Button, MikaShapes.Button, TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), 20.dp),
    Medium(42.dp, RoundedCornerShape(12.dp), TextStyle(fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold), 14.dp),
    Small(32.dp, RoundedCornerShape(16.dp), TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold), 14.dp),
}

/** 按钮。宽度由 [modifier] 定(铺满、按权重分或包住文字)。 */
@Composable
fun MikaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
) {
    val c = MikaTheme.colors
    val shape = size.shape
    val interaction = remember { MutableInteractionSource() }
    val fg = when (kind) {
        ButtonKind.Primary -> c.onAccent
        ButtonKind.Secondary, ButtonKind.Text -> c.accent
        ButtonKind.Tonal, ButtonKind.Glass -> c.ink
    }
    val surfaceMod = when (kind) {
        ButtonKind.Primary -> Modifier
            .then(
                if (enabled && c.accentGlow.alpha > 0f) {
                    Modifier.shadow(10.dp, shape, clip = false, ambientColor = c.accentGlow, spotColor = c.accentGlow)
                } else {
                    Modifier
                },
            )
            .background(Brush.verticalGradient(listOf(c.accentHighlight, c.accent)), shape)
            .border(
                0.75.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.08f))),
                shape,
            )
        ButtonKind.Secondary -> Modifier.cardSurface(c, shape, elevated = true)
        ButtonKind.Tonal -> Modifier.background(c.glassTonal, shape).border(0.75.dp, specularBorderBrush(c), shape)
        ButtonKind.Glass -> Modifier.liquidGlass(GlassMaterials.FloatingPill, backdrop = LocalGlassBackdrop.current, shape = shape)
        ButtonKind.Text -> Modifier
    }
    Box(
        modifier
            .mikaPress(interaction, enabled = enabled, pressedScale = if (kind == ButtonKind.Text) 0.98f else 0.965f, shape = shape)
            .then(surfaceMod)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .height(size.height)
            .padding(horizontal = size.padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = size.text, color = fg, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- 分段控件 ----------

/** 分段控件：浅玻璃底槽 + 弹性滑动的悬浮玻璃胶囊指示器。[tags] 是各段的测试标签。 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tags: List<String>? = null,
) {
    val c = MikaTheme.colors
    val reduce = MikaTheme.reduceMotion
    val safeSelected = if (options.isEmpty()) 0 else selected.coerceIn(0, options.lastIndex)
    val indicatorIndex by animateFloatAsState(safeSelected.toFloat(), MikaMotion.pillSpring(reduce), label = "segment-pill")
    val borderBrush = remember(c) { specularBorderBrush(c) }
    val sheenAlpha = if (c.isDark) 0.10f else 0.18f

    Box(
        modifier
            .fillMaxWidth()
            .background(c.glassSubtle, SegmentOuter)
            .border(0.75.dp, borderBrush, SegmentOuter)
            .padding(3.dp),
    ) {
        if (options.isNotEmpty()) {
            Layout(
                content = {
                    Box(
                        Modifier
                            .then(
                                if (c.cardShadow.alpha > 0f) {
                                    Modifier.shadow(3.dp, SegmentInner, clip = false, ambientColor = c.cardShadow.copy(alpha = 0.22f), spotColor = c.cardShadow.copy(alpha = 0.16f))
                                } else {
                                    Modifier
                                },
                            )
                            .clip(SegmentInner)
                            .background(c.glassCardElevated, SegmentInner)
                            .border(0.75.dp, borderBrush, SegmentInner)
                            .drawWithContent {
                                drawGlassSheen(sheenAlpha)
                                drawContent()
                            },
                    )
                    Row(Modifier.fillMaxWidth()) {
                        options.forEachIndexed { i, label ->
                            val on = i == selected
                            val textColor by animateColorAsState(if (on) c.ink else c.ink2, MikaMotion.pressSpring(reduce), label = "seg-txt-$i")
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clip(SegmentInner)
                                    .selectable(on, role = Role.Tab) { onSelect(i) }
                                    .then(if (tags != null) Modifier.testTag(tags[i]) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, style = SegmentText, color = textColor, maxLines = 1)
                            }
                        }
                    }
                },
            ) { measurables, constraints ->
                val rowPlaceable = measurables[1].measure(constraints)
                val tabW = (rowPlaceable.width / options.size).coerceAtLeast(1)
                val pillPlaceable = measurables[0].measure(Constraints.fixed(tabW, rowPlaceable.height))
                layout(rowPlaceable.width, rowPlaceable.height) {
                    pillPlaceable.placeRelative((tabW * indicatorIndex).roundToInt(), 0)
                    rowPlaceable.placeRelative(0, 0)
                }
            }
        }
    }
}

private val SegmentOuter = RoundedCornerShape(MikaRadius.SegmentOuter)
private val SegmentInner = RoundedCornerShape(MikaRadius.SegmentInner)
private val SegmentText = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

/** Mirrors horizontal navigation symbols in RTL while leaving non-directional artwork unchanged. */
@Composable
fun DirectionalIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val mirror = rtl && (icon == MikaIcons.Back || icon == MikaIcons.Forward || icon == MikaIcons.Chevron)
    Icon(icon, contentDescription, modifier.graphicsLayer { if (mirror) scaleX = -1f }, tint = tint)
}

// ---------- 顶栏 ----------

/**
 * 顶栏上的圆形图标按钮，40dp。[glass] 为真时是浮在内容上的真实液态玻璃圆（带折射与高光边）。
 * [badge] 为真时右上角一个强调色小圆点(有别的事在进行)；[enabled] 为假时变淡、不可点。
 */
@Composable
fun BarIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    glass: Boolean = false,
    enabled: Boolean = true,
    badge: Boolean = false,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
) {
    val c = MikaTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val glassModifier = if (glass) {
        Modifier.liquidGlass(GlassMaterials.IconCircle, backdrop = backdrop, shape = CircleShape)
    } else {
        Modifier.clip(CircleShape)
    }
    Box(
        Modifier
            .mikaPress(interaction, enabled = enabled, pressedScale = 0.92f, shape = CircleShape)
            .size(MikaSize.IconButton)
            .then(glassModifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DirectionalIcon(icon, label, Modifier.size(MikaSize.Icon).alpha(if (enabled) 1f else 0.35f), tint = c.ink)
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 7.dp).size(8.dp)
                    .background(c.bg, CircleShape).padding(1.dp).background(c.accent, CircleShape),
            )
        }
    }
}

/**
 * 顶栏：56 高，左右各一个图标按钮位，标题居中，可带一行副标题。
 * 默认启用克制液态玻璃（[glass] = true）：从 [LocalGlassBackdrop] 取背景做模糊与底边折射；
 * 同时消费未被子按钮处理的手势，防止误触滚到顶栏下方的列表项。
 */
@Composable
fun TopBar(
    title: String,
    left: ImageVector? = null,
    onLeft: () -> Unit = {},
    leftLabel: String = "",
    /** 左边按钮上的小圆点,见 [BarIconButton]。 */
    leftBadge: Boolean = false,
    right: (@Composable RowScope.() -> Unit)? = null,
    subtitle: String? = null,
    subtitleColor: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
    glass: Boolean = true,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
) {
    val c = MikaTheme.colors
    val surfaceMod = if (glass && background == Color.Unspecified) {
        Modifier.liquidGlass(GlassMaterials.TopBar, backdrop = backdrop, shape = RectangleShape)
    } else {
        Modifier.background(if (background == Color.Unspecified) c.bg else background)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .then(surfaceMod)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { if (!it.isConsumed) it.consume() }
                    }
                }
            }
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier.fillMaxWidth().height(MikaSize.TopBar).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (left != null) BarIconButton(left, leftLabel, onLeft, badge = leftBadge) else Spacer(Modifier.size(MikaSize.IconButton))
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MikaType.Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle, style = MikaType.Hint, fontWeight = FontWeight.Medium,
                        color = if (subtitleColor == Color.Unspecified) c.ink3 else subtitleColor, maxLines = 1,
                    )
                }
            }
            if (right != null) Row(verticalAlignment = Alignment.CenterVertically, content = right)
            else Spacer(Modifier.size(MikaSize.IconButton))
        }
    }
}

// ---------- 选择胶囊 ----------

/** 可选的胶囊(服务商、接口类型):34 高,白卡底;选中时深色实底、反色字。[small] 是行内的小号。 */
@Composable
fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    val c = MikaTheme.colors
    val shape = if (small) SmallChipShape else MikaShapes.Pill
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .mikaPress(interaction, pressedScale = 0.96f, shape = shape)
            .then(
                when {
                    selected -> Modifier.background(c.ink, shape)
                        .border(0.75.dp, c.specularHighlight.copy(alpha = if (c.isDark) 0.24f else 0.35f), shape)
                    small -> Modifier.background(c.glassSubtle, shape).border(0.5.dp, c.glassEdge, shape)
                    else -> Modifier.cardSurface(c, shape)
                },
            )
            .clip(shape)
            .selectable(selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .height(if (small) 28.dp else 34.dp)
            .padding(horizontal = if (small) 10.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = if (small) SmallChipText else ChipText, maxLines = 1, softWrap = false,
            color = if (selected) c.bg else c.ink,
        )
    }
}

private val SmallChipShape = RoundedCornerShape(8.dp)
private val ChipText = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
private val SmallChipText = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)

// ---------- 小标签 ----------

enum class TagTone { Neutral, Accent }

/** 能力小标签(工具调用、看图、64K):11 / 600,圆角 7。 */
@Composable
fun CapabilityTag(text: String, tone: TagTone = TagTone.Neutral) {
    val c = MikaTheme.colors
    val (bg, fg, rim) = if (tone == TagTone.Accent) {
        Triple(c.accentSoft, c.accent, c.accent.copy(alpha = 0.22f))
    } else {
        Triple(c.glassSubtle, c.ink2, c.glassEdge)
    }
    Text(
        text,
        Modifier.background(bg, TagShape).border(0.5.dp, rim, TagShape).padding(horizontal = 7.dp, vertical = 2.dp),
        style = TagText,
        color = fg,
        maxLines = 1,
    )
}

private val TagShape = RoundedCornerShape(7.dp)
private val TagText = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)

// ---------- 分页点 ----------

/** 上手流程底部的分页点:当前一步是 18 宽的强调色短条,其余是 6 的灰点。 */
@Composable
fun ProgressDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val reduce = MikaTheme.reduceMotion
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 18.dp else 6.dp, MikaMotion.pillSpring(reduce), label = "dot-w-$i")
            Box(Modifier.size(w, 6.dp).background(if (i == current) c.accent else c.hair, CircleShape))
        }
    }
}
