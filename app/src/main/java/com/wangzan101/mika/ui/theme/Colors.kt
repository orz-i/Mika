package com.wangzan101.mika.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 界面用到的全部颜色。页面一律从 [MikaTheme.colors] 取,不写死色值。
 * 实色只用在强调(主按钮、选中、开关、用户消息)和状态(成功、需要你、出错);图标块一律中性。
 */
@Immutable
class MikaColors(
    /** 页面背景。 */
    val bg: Color,
    /** 卡片、分组。 */
    val surface: Color,
    /** 浅填充:次按钮、分段控件底、代码块底。 */
    val fill: Color,
    /** 文字三级:正文、次要、辅助。 */
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    /** 分隔线。 */
    val hair: Color,
    /** 卡片描边。 */
    val edge: Color,
    val accent: Color,
    val accentSoft: Color,
    /** 强调色上的文字和图标。 */
    val onAccent: Color,
    val ok: Color,
    val okSoft: Color,
    /** 「需要你」:[warn] 用于文字,[warnSolid] 用于圆点、描边。 */
    val warn: Color,
    val warnSolid: Color,
    val warnSoft: Color,
    val bad: Color,
    val badSoft: Color,
    /** 图标块的底。 */
    val neutralSoft: Color,
    /** Mika 回答的底:比页面背景深一点点的灰,和用户的蓝色气泡区分开。 */
    val answer: Color,
    /** 灰色胶囊、关着的开关。 */
    val greySoft: Color,
    /** 悬浮玻璃(输入栏、浮在内容上的按钮)的底和描边。 */
    val glass: Color,
    val glassEdge: Color,
    /** 卡片阴影;深色下卡片只靠描边,为透明。 */
    val cardShadow: Color,
    /** 悬浮元素的阴影。 */
    val floatShadow: Color,
    /** 开关的圆钮。 */
    val thumb: Color,
    /**
     * 图表里分类的颜色(例如每家服务商一种),按固定的先后取;超出的归到 [seriesOther]。
     * 只给图形上色,文字一律用 ink 三级。
     */
    val series: List<Color>,
    val seriesOther: Color,
    /** 图表的网格线。 */
    val grid: Color,
    val isDark: Boolean,
    /** 透明磨砂卡片底（LazyColumn 列表卡片用，轻量半透明）。 */
    val glassCard: Color = surface,
    /** 悬浮/选中态磨砂底（如分段控件浮起药丸、次按钮、当前对话高亮）。 */
    val glassCardElevated: Color = surface,
    /** 更轻薄的半透明玻璃槽（分段控件底槽、行内小标签、搜索框）。 */
    val glassSubtle: Color = fill,
    /** Mika 回答气泡底（轻量磨砂玻璃）。 */
    val glassAnswer: Color = answer,
    /** Tonal 按钮/轨道填充。 */
    val glassTonal: Color = fill,
    /** 顶栏玻璃底色（无实时 backdrop 或降级时）。 */
    val glassTopBar: Color = bg,
    /** AGSL 着色器里的底色基调（backdropTone）。 */
    val glassBackdropBase: Color = bg,
    /** 玻璃表面的前向高光染色（叠加在模糊/折射层之上）。 */
    val glassTint: Color = Color.White,
    /** 方向性高光边框三段色（左上亮 -> 中段过渡 -> 右下收边）。 */
    val specularHighlight: Color = glassEdge,
    val specularMid: Color = glassEdge,
    val specularBottom: Color = glassEdge,
    /** 左上径向表面光泽（Radial sheen）。 */
    val specularSheen: Color = Color.Transparent,
    /** 按压瞬间的微光叠加。 */
    val specularPress: Color = Color.Transparent,
    /** 弹窗/底部面板的遮罩底色。 */
    val scrim: Color = Color(0x3D0B101B),
    /** 主强调色的亮端高光（主按钮与开关开启态渐变顶部）。 */
    val accentHighlight: Color = accent,
    /** 主按钮与开关开启态的环境光晕。 */
    val accentGlow: Color = accentSoft,
    /** 页面背景上克制的环境光球颜色（主、辅、第三点缀）。 */
    val ambientOrbPrimary: Color = Color.Transparent,
    val ambientOrbSecondary: Color = Color.Transparent,
    val ambientOrbTertiary: Color = Color.Transparent,
)

val LightColors = MikaColors(
    bg = Color(0xFFF5F6FA),
    surface = Color(0xFFFFFFFF),
    fill = Color(0x0D0A1020),
    ink = Color(0xFF181A20),
    ink2 = Color(0xFF5A5D68),
    ink3 = Color(0xFF8E929E),
    hair = Color(0x140A1020),
    edge = Color(0x140A1020),
    accent = Color(0xFF0A7CFF),
    accentSoft = Color(0x1C0A7CFF),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF1F9D55),
    okSoft = Color(0x1F1F9D55),
    warn = Color(0xFFB86E00),
    warnSolid = Color(0xFFFF9F0A),
    warnSoft = Color(0x1FFF9F0A),
    bad = Color(0xFFE5483D),
    badSoft = Color(0x1AE5483D),
    neutralSoft = Color(0x0D0A1020),
    answer = Color(0xB8FFFFFF),
    greySoft = Color(0x22767680),
    glass = Color(0xB8FFFFFF),
    glassEdge = Color(0x22000000),
    cardShadow = Color(0xFF0A1020),
    floatShadow = Color(0xFF080D18),
    thumb = Color(0xFFFFFFFF),
    series = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A)),
    seriesOther = Color(0xFFB4B5BC),
    grid = Color(0x100A1020),
    isDark = false,
    glassCard = Color(0xD9FFFFFF),
    glassCardElevated = Color(0xEBFFFFFF),
    glassSubtle = Color(0x8CFFFFFF),
    glassAnswer = Color(0xB8FFFFFF),
    glassTonal = Color(0x120A1020),
    glassTopBar = Color(0xCCF5F6FA),
    glassBackdropBase = Color(0xFFEAF0F6),
    glassTint = Color(0xFFFFFFFF),
    specularHighlight = Color(0xF2FFFFFF),
    specularMid = Color(0x4DFFFFFF),
    specularBottom = Color(0x16000000),
    specularSheen = Color(0x36FFFFFF),
    specularPress = Color(0x140A7CFF),
    scrim = Color(0x3D0B101B),
    accentHighlight = Color(0xFF3393FF),
    accentGlow = Color(0x4D0A7CFF),
    ambientOrbPrimary = Color(0x120A7CFF),
    ambientOrbSecondary = Color(0x0C1FB88A),
    ambientOrbTertiary = Color(0x0C6B7BFF),
)

val DarkColors = MikaColors(
    bg = Color(0xFF0B0D12),
    surface = Color(0xFF17191F),
    fill = Color(0x12FFFFFF),
    ink = Color(0xFFF3F5FA),
    ink2 = Color(0xFFB0B5C2),
    ink3 = Color(0xFF747987),
    hair = Color(0x16FFFFFF),
    edge = Color(0x14FFFFFF),
    accent = Color(0xFF3D97FF),
    accentSoft = Color(0x2B3D97FF),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF3CCF7A),
    okSoft = Color(0x243CCF7A),
    warn = Color(0xFFFFB340),
    warnSolid = Color(0xFFFF9F0A),
    warnSoft = Color(0x29FF9F0A),
    bad = Color(0xFFFF6961),
    badSoft = Color(0x24FF6961),
    neutralSoft = Color(0x14FFFFFF),
    answer = Color(0x7A181D26),
    greySoft = Color(0x38767680),
    glass = Color(0xB8151923),
    glassEdge = Color(0x24FFFFFF),
    cardShadow = Color.Transparent,
    floatShadow = Color(0xFF000000),
    thumb = Color(0xFFFFFFFF),
    series = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70)),
    seriesOther = Color(0xFF5A5B62),
    grid = Color(0x14FFFFFF),
    isDark = true,
    glassCard = Color(0xB8171A21),
    glassCardElevated = Color(0xCC1E222B),
    glassSubtle = Color(0x521F2430),
    glassAnswer = Color(0x7A181D26),
    glassTonal = Color(0x1CFFFFFF),
    glassTopBar = Color(0xCC0F1219),
    glassBackdropBase = Color(0xFF131822),
    glassTint = Color(0xFF0C1028),
    specularHighlight = Color(0x52FFFFFF),
    specularMid = Color(0x12FFFFFF),
    specularBottom = Color(0x28FFFFFF),
    specularSheen = Color(0x22FFFFFF),
    specularPress = Color(0x18FFFFFF),
    scrim = Color(0x7304060A),
    accentHighlight = Color(0xFF5CA8FF),
    accentGlow = Color(0x663D97FF),
    ambientOrbPrimary = Color(0x1A2D8CFF),
    ambientOrbSecondary = Color(0x111B9E7B),
    ambientOrbTertiary = Color(0x0E5B66E8),
)

/** 没有包在 [MikaTheme] 里时(比如界面测试)按浅色。 */
val LocalMikaColors = staticCompositionLocalOf { LightColors }
