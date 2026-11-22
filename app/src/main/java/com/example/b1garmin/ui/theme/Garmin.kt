package com.example.b1garmin.ui.theme

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 明暗两套设计令牌。深色参考 Garmin Connect：近黑背景、深灰卡片、提亮的强调色。
 * 用 Compose 状态承载调色板，切换主题时读过颜色的组件会自动重组。
 */
private data class GPalette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val text: Color,
    val sub: Color,
    val muted: Color,
    val line: Color,
    val lineSoft: Color,
    val blue: Color,
    val blueDark: Color,
    val blueSoft: Color,
    val green: Color,
    val greenSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val red: Color,
    val redSoft: Color,
    val chevron: Color,
    val track: Color,
    val borderBlue: Color,
    val borderMuted: Color,
    val toggleOff: Color,
    val dangerBorder: Color,
    val onBlue: Color,
)

private val LightPalette = GPalette(
    dark = false,
    bg = Color(0xFFF3F6F9),
    surface = Color(0xFFFFFFFF),
    text = Color(0xFF1B2733),
    sub = Color(0xFF5B6B7B),
    muted = Color(0xFF8A98A6),
    line = Color(0xFFE3E9EF),
    lineSoft = Color(0xFFEEF2F6),
    blue = Color(0xFF007CC3),
    blueDark = Color(0xFF00649E),
    blueSoft = Color(0xFFE8F3FA),
    green = Color(0xFF12805C),
    greenSoft = Color(0xFFE9F7F1),
    amber = Color(0xFFB45309),
    amberSoft = Color(0xFFFFF6E5),
    red = Color(0xFFC0392B),
    redSoft = Color(0xFFFDECEA),
    chevron = Color(0xFFC3CCD6),
    track = Color(0xFFE8EEF4),
    borderBlue = Color(0xFFD3E8F5),
    borderMuted = Color(0xFFB9CAD8),
    toggleOff = Color(0xFFD5DEE6),
    dangerBorder = Color(0xFFF3CFCB),
    onBlue = Color(0xFFFFFFFF),
)

private val DarkPalette = GPalette(
    dark = true,
    bg = Color(0xFF0B0F14),
    surface = Color(0xFF151B22),
    text = Color(0xFFF2F6FA),
    sub = Color(0xFFA9B6C3),
    muted = Color(0xFF74828F),
    line = Color(0xFF26313C),
    lineSoft = Color(0xFF1E2831),
    blue = Color(0xFF2E9BD8),
    blueDark = Color(0xFF247FB0),
    blueSoft = Color(0xFF12293A),
    green = Color(0xFF35C98F),
    greenSoft = Color(0xFF10281F),
    amber = Color(0xFFF2A93B),
    amberSoft = Color(0xFF2E2211),
    red = Color(0xFFFF6B6B),
    redSoft = Color(0xFF2E1517),
    chevron = Color(0xFF5F6C78),
    track = Color(0xFF1E2831),
    borderBlue = Color(0xFF1F3D50),
    borderMuted = Color(0xFF33414D),
    toggleOff = Color(0xFF3A4652),
    dangerBorder = Color(0xFF4A2622),
    onBlue = Color(0xFFFFFFFF),
)

object GColor {
    private var palette by mutableStateOf(LightPalette)

    /** 由 GarminTheme 在组合完成后调用；MainActivity 启动前也会先设一次，避免闪白。 */
    fun setDark(dark: Boolean) {
        val next = if (dark) DarkPalette else LightPalette
        if (palette != next) palette = next
    }

    val isDark: Boolean get() = palette.dark
    val Bg get() = palette.bg
    val Surface get() = palette.surface
    val Text get() = palette.text
    val Sub get() = palette.sub
    val Muted get() = palette.muted
    val Line get() = palette.line
    val LineSoft get() = palette.lineSoft
    val Blue get() = palette.blue
    val BlueDark get() = palette.blueDark
    val BlueSoft get() = palette.blueSoft
    val Green get() = palette.green
    val GreenSoft get() = palette.greenSoft
    val Amber get() = palette.amber
    val AmberSoft get() = palette.amberSoft
    val Red get() = palette.red
    val RedSoft get() = palette.redSoft
    val Chevron get() = palette.chevron
    val Track get() = palette.track
    val BorderBlue get() = palette.borderBlue
    val BorderMuted get() = palette.borderMuted
    val ToggleOff get() = palette.toggleOff
    val DangerBorder get() = palette.dangerBorder
    val OnBlue get() = palette.onBlue
}

/** 外观偏好：跟随系统 / 浅色 / 深色。 */
object ThemeStore {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private const val PREFS = "b1_prefs"
    private const val KEY = "theme_mode"

    fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, SYSTEM) ?: SYSTEM

    fun save(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode).apply()
    }
}

val GCardShape = RoundedCornerShape(12.dp)

@Composable
fun GarminTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    SideEffect { GColor.setDark(dark) }
    val scheme = if (dark) {
        darkColorScheme(
            primary = GColor.Blue,
            onPrimary = GColor.OnBlue,
            primaryContainer = GColor.BlueSoft,
            onPrimaryContainer = GColor.Blue,
            secondary = GColor.Green,
            onSecondary = Color(0xFF04140D),
            secondaryContainer = GColor.GreenSoft,
            onSecondaryContainer = GColor.Green,
            background = GColor.Bg,
            onBackground = GColor.Text,
            surface = GColor.Surface,
            onSurface = GColor.Text,
            surfaceVariant = GColor.LineSoft,
            onSurfaceVariant = GColor.Sub,
            outline = GColor.Muted,
            outlineVariant = GColor.Line,
            error = GColor.Red,
            onError = Color(0xFF1A0505),
            errorContainer = GColor.RedSoft,
            onErrorContainer = GColor.Red,
        )
    } else {
        lightColorScheme(
            primary = GColor.Blue,
            onPrimary = Color.White,
            primaryContainer = GColor.BlueSoft,
            onPrimaryContainer = GColor.Blue,
            secondary = GColor.Green,
            onSecondary = Color.White,
            secondaryContainer = GColor.GreenSoft,
            onSecondaryContainer = GColor.Green,
            background = GColor.Bg,
            onBackground = GColor.Text,
            surface = GColor.Surface,
            onSurface = GColor.Text,
            surfaceVariant = GColor.Bg,
            onSurfaceVariant = GColor.Sub,
            outline = GColor.Muted,
            outlineVariant = GColor.Line,
            error = GColor.Red,
            onError = Color.White,
            errorContainer = GColor.RedSoft,
            onErrorContainer = GColor.Red,
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = garminTypography(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(10.dp),
            large = GCardShape,
            extraLarge = RoundedCornerShape(18.dp),
        ),
        content = content,
    )
}

private fun garminTypography(): Typography {
    val d = Typography()
    return d.copy(
        displayLarge = d.displayLarge.copy(
            fontSize = 46.sp, lineHeight = 48.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp,
        ),
        headlineMedium = d.headlineMedium.copy(
            fontSize = 30.sp, lineHeight = 34.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
        ),
        headlineSmall = d.headlineSmall.copy(
            fontSize = 22.sp, lineHeight = 26.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp,
        ),
        titleLarge = d.titleLarge.copy(
            fontSize = 18.sp, lineHeight = 22.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp,
        ),
        titleMedium = d.titleMedium.copy(
            fontSize = 16.sp, lineHeight = 21.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp,
        ),
        titleSmall = d.titleSmall.copy(
            fontSize = 14.sp, lineHeight = 19.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        bodyLarge = d.bodyLarge.copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodyMedium = d.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = d.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = d.labelLarge.copy(
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        ),
        labelMedium = d.labelMedium.copy(
            fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
        ),
        labelSmall = d.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
    )
}

// ====================================================================
// 通用卡片 / 行 / 标签
// ====================================================================

/** 白底 + 1px 边线 + 12dp 圆角，与原型 .card 一致。 */
@Composable
fun GPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = GCardShape,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = GColor.Surface,
        border = BorderStroke(1.dp, GColor.Line),
        shadowElevation = if (GColor.isDark) 0.dp else 1.dp,
    ) {
        Column(content = content)
    }
}

/** 带内边距的卡片。 */
@Composable
fun GCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GPanel(modifier) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** 小节标题行：「最近同步 / 查看全部」。 */
@Composable
fun GSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = GColor.Text)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 灰色小标题（我的页分组名）。 */
@Composable
fun GGroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
        ),
        color = GColor.Muted,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 7.dp),
    )
}

@Composable
fun GTag(
    text: String,
    fg: Color = GColor.Sub,
    bg: Color = GColor.LineSoft,
    dot: Boolean = false,
) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(fg))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
        )
    }
}

/** 列表行左侧的圆角图标块。 */
@Composable
fun GLead(
    icon: GIconKind? = null,
    bg: Color = GColor.BlueSoft,
    fg: Color = GColor.Blue,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        when {
            content != null -> content()
            icon != null -> GIcon(icon, tint = fg)
        }
    }
}

/**
 * 标准列表行：图标 + 标题/副标题 + 右侧内容。
 * [topDivider] 为 true 时在行顶部画一条内缩分隔线（对齐原型 .row+.row:before）。
 */
@Composable
fun GRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    lead: (@Composable () -> Unit)? = null,
    topDivider: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier) {
        if (topDivider) {
            HorizontalDivider(
                Modifier.padding(start = if (lead != null) 62.dp else 16.dp, end = 16.dp),
                thickness = 1.dp,
                color = GColor.LineSoft,
            )
        }
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (lead != null) {
                lead()
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = GColor.Text,
                    maxLines = 1,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = GColor.Muted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            trailing?.invoke(this)
        }
    }
}

/** 行尾的「>」。 */
@Composable
fun GChevron() {
    Icon(
        Icons.Default.KeyboardArrowRight,
        contentDescription = null,
        tint = GColor.Chevron,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
fun GPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 44.dp,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = height),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = GColor.Blue,
            contentColor = Color.White,
            disabledContainerColor = GColor.Blue.copy(alpha = 0.45f),
            disabledContentColor = Color.White,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GGhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.heightIn(min = 44.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(10.dp),
        color = GColor.Surface,
        border = BorderStroke(1.dp, GColor.Line),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) GColor.Sub else GColor.Muted,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
    }
}

/** 顶部页面栏：小字英文 + 中文标题 + 右侧动作，白底 + 下边线。 */
@Composable
fun GPageBar(
    kicker: String?,
    title: String,
    trailing: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(GColor.Surface).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (!kicker.isNullOrBlank()) {
                    Text(
                        kicker,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                        ),
                        color = GColor.Muted,
                    )
                }
                Text(title, style = MaterialTheme.typography.titleLarge, color = GColor.Text)
            }
            trailing()
        }
        HorizontalDivider(color = GColor.Line)
    }
}

/** 圆形图标按钮（顶栏右侧）。 */
@Composable
fun GCircleIconButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(GColor.Bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

// ====================================================================
// 底部 Tab
// ====================================================================

@Composable
fun GTabBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(GColor.Surface).navigationBarsPadding()) {
        HorizontalDivider(color = GColor.Line)
        Row(
            Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem(0, "首页", selected, onSelect) { tint ->
                Icon(Icons.Default.Home, contentDescription = null, tint = tint,
                    modifier = Modifier.size(21.dp))
            }
            TabItem(1, "设备", selected, onSelect) { tint -> GIcon(GIconKind.Scale, tint = tint) }
            TabItem(2, "记录", selected, onSelect) { tint -> GIcon(GIconKind.Bars, tint = tint) }
            TabItem(3, "我的", selected, onSelect) { tint ->
                Icon(Icons.Default.Person, contentDescription = null, tint = tint,
                    modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(
    index: Int,
    label: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val active = index == selected
    val tint = if (active) GColor.Blue else GColor.Muted
    Column(
        Modifier.weight(1f).height(66.dp)
            .clickable { if (!active) onSelect(index) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon(tint)
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
        )
    }
}

// ====================================================================
// 图标：Material 核心集没有的，用 Canvas 按原型 24×24 线稿绘制
// ====================================================================

enum class GIconKind { Scale, Bars, Height, Sex, Target, Tune, Bluetooth, Shield }

@Composable
fun GIcon(kind: GIconKind, modifier: Modifier = Modifier.size(18.dp), tint: Color = GColor.Blue) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val w = 1.9f * s
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), w, StrokeCap.Round)
        fun dot(x: Float, y: Float, r: Float = 1.8f) =
            drawCircle(tint, r * s, Offset(x * s, y * s))

        when (kind) {
            GIconKind.Scale -> {
                drawRoundRect(
                    tint, Offset(3 * s, 4 * s), Size(18 * s, 16 * s),
                    CornerRadius(4 * s, 4 * s), style = stroke,
                )
                line(8f, 10f, 16f, 10f)
                drawArc(
                    tint, 0f, 180f, false,
                    topLeft = Offset(9.5f * s, 12.5f * s), size = Size(5 * s, 4 * s),
                    style = stroke,
                )
            }
            GIconKind.Bars -> {
                line(6f, 20f, 6f, 14f)
                line(12f, 20f, 12f, 10f)
                line(18f, 20f, 18f, 4f)
            }
            GIconKind.Height -> {
                line(12f, 3f, 12f, 21f)
                val up = Path().apply {
                    moveTo(8 * s, 7 * s); lineTo(12 * s, 3 * s); lineTo(16 * s, 7 * s)
                }
                drawPath(up, tint, style = stroke)
                val down = Path().apply {
                    moveTo(8 * s, 17 * s); lineTo(12 * s, 21 * s); lineTo(16 * s, 17 * s)
                }
                drawPath(down, tint, style = stroke)
            }
            GIconKind.Sex -> {
                drawCircle(tint, 5.5f * s, Offset(10 * s, 14 * s), style = stroke)
                line(14f, 10f, 19f, 5f)
                line(15.5f, 5f, 19f, 5f)
                line(19f, 5f, 19f, 8.5f)
            }
            GIconKind.Target -> {
                drawCircle(tint, 6.5f * s, Offset(11 * s, 13.5f * s), style = stroke)
                drawCircle(tint, 2f * s, Offset(11 * s, 13.5f * s), style = stroke)
                line(16f, 9f, 21f, 4f)
            }
            GIconKind.Tune -> {
                line(3f, 7f, 21f, 7f)
                line(3f, 12f, 21f, 12f)
                line(3f, 17f, 21f, 17f)
                dot(9f, 7f, 2.2f)
                dot(15f, 12f, 2.2f)
                dot(7f, 17f, 2.2f)
            }
            GIconKind.Bluetooth -> {
                val p = Path().apply {
                    moveTo(8 * s, 3 * s); lineTo(16 * s, 9 * s); lineTo(8 * s, 15 * s)
                    lineTo(8 * s, 9 * s); lineTo(16 * s, 15 * s); lineTo(8 * s, 21 * s)
                }
                drawPath(p, tint, style = stroke)
            }
            GIconKind.Shield -> {
                val p = Path().apply {
                    moveTo(12 * s, 3 * s)
                    lineTo(5 * s, 6 * s); lineTo(5 * s, 12 * s)
                    cubicTo(5 * s, 16.5f * s, 8 * s, 19.5f * s, 12 * s, 21 * s)
                    cubicTo(16 * s, 19.5f * s, 19 * s, 16.5f * s, 19 * s, 12 * s)
                    lineTo(19 * s, 6 * s); close()
                }
                drawPath(p, tint, style = stroke)
            }
        }
    }
}
