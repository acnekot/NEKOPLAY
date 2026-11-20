package cc.catsuki.nekoplay

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ---------------- 配色：和 Flutter 版 tokens 完全一致 ----------------
data class Pal(
    val dark: Boolean, val k: Color, val p: Color, val s: Color, val s2: Color, val l1: Color, val l2: Color, val l3: Color,
    val bar: Color, val pop: Color, val tagBg: Color, val tagFg: Color, val hl: Color, val lyBg: Color, val lyFg: Color, val lyFg2: Color,
    val bad: Color, val ph: Color, val page: Color, val sheet: Color,
)
val Light = Pal(false, Color(0xFF0E1117), Color(0xFF1A4BE0), Color(0xFF46505E), Color(0xFF3D4350), Color(0x1F0E1117), Color(0x330E1117), Color(0x4D0E1117),
    Color(0xFFCBE0E7), Color(0xFFF1F7F8), Color(0xFF0E1117), Color.White, Color(0x80FFFFFF), Color(0x8CFFFFFF), Color(0xFF1A4BE0), Color(0xFF3D4350),
    Color(0xFFC42B2B), Color(0xFFB9D0D9), Color(0xFFD3E5EA), Color(0xFFE2EDEE))
val Dark = Pal(true, Color(0xFFE6EDF3), Color(0xFF5580F7), Color(0xFF9DA7B3), Color(0xFFB8C1CC), Color(0x1FE6EDF3), Color(0x33E6EDF3), Color(0x47E6EDF3),
    Color(0xFF2D333B), Color(0xFF373E47), Color(0xFFE6EDF3), Color(0xFF2D333B), Color(0x12FFFFFF), Color(0xFFF0F2F5), Color(0xFF0E1117), Color(0xFF0E1117),
    Color(0xFFF85149), Color(0xFF1B2027), Color(0xFF2D333B), Color(0xFF2D333B))
val LocalPal = staticCompositionLocalOf { Light }
val c: Pal @Composable get() = LocalPal.current

/** 深浅色：0 跟随系统 / 1 浅色 / 2 深色 */
/** 主题（界面风格）：0 BenchLab 粗线框（默认）/ 1 无边框（去掉所有框线和分隔线，控件改用浅色底块区分） */
object Look {
    val NAMES = listOf("BenchLab", "无边框")
    var style by mutableIntStateOf(0)
    val flat get() = style == 1
    fun load() { style = Plat.prefs("ui").getInt("look", 0) }
    fun set(v: Int) { style = v; Plat.prefs("ui").edit().putInt("look", v).apply() }
}

/** 边框：无边框主题下不画 */
@Composable fun Modifier.nb(width: Dp, color: Color): Modifier = if (Look.flat) this else border(width, color)
/** 控件边框：无边框主题下改成一块浅色底，按钮、输入框仍然看得出范围 */
@Composable fun Modifier.nbf(width: Dp, color: Color): Modifier = if (Look.flat) background(c.k.copy(alpha = .07f)) else border(width, color)
/** 分区粗线：无边框主题下只留间距 */
@Composable fun Rule() { if (Look.flat) Spacer(Modifier.height(3.dp)) else Box(Modifier.fillMaxWidth().height(3.dp).background(c.k)) }

object ThemePref {
    var mode by mutableIntStateOf(0)
    fun load() { mode = Plat.prefs("ui").getInt("theme", 0) }
    fun set(m: Int) { mode = m; Plat.prefs("ui").edit().putInt("theme", m).apply() }
}

@Composable fun NekoTheme(content: @Composable () -> Unit) {
    val dark = when (ThemePref.mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    // 莫奈取色：模式 / 色相 / 封面 / 背景图变了就重新取种子色
    val st by Engine.state.collectAsState()
    val cover = st.current?.cover
    LaunchedEffect(Monet.mode, Monet.hue, if (Monet.mode == 3) cover else null, Bg.on, Bg.source) { Monet.refresh(cover) }
    val base = if (dark) Dark else Light
    CompositionLocalProvider(LocalPal provides animPal(if (Monet.on) Monet.apply(base) else base), content = content)
}

/** 配色切换（换歌跟封面变色）时背景层和主色做一个渐变 */
@Composable private fun animPal(t: Pal): Pal {
    val sp = androidx.compose.animation.core.tween<Color>(450)
    @Composable fun a(v: Color, l: String) = androidx.compose.animation.animateColorAsState(v, sp, label = l).value
    return t.copy(p = a(t.p, "p"), page = a(t.page, "pg"), bar = a(t.bar, "bar"), sheet = a(t.sheet, "sh"), pop = a(t.pop, "pop"), ph = a(t.ph, "ph"), lyBg = a(t.lyBg, "ly"), tagFg = a(t.tagFg, "tf"))
}

/** 背景：深色纯平 #2D333B，浅色 165° 渐变 + 两个淡光斑 */
@Composable fun Modifier.backdrop(): Modifier {
    val p = c
    if (Monet.on) return if (p.dark) background(p.page) else drawBehind {
        drawRect(Brush.linearGradient(listOf(p.bar, p.page, p.sheet), Offset(size.width * .37f, 0f), Offset(size.width * .63f, size.height)))
    }
    return if (p.dark) background(Color(0xFF2D333B)) else drawBehind {
        drawRect(Brush.linearGradient(listOf(Color(0xFFC6DCE4), Color(0xFFD3E5EA), Color(0xFFE2EDEE)), Offset(size.width * .37f, 0f), Offset(size.width * .63f, size.height)))
        val r1 = 130.dp.toPx(); drawCircle(Brush.radialGradient(listOf(Color(0x33FFD9B0), Color(0x00FFD9B0)), Offset(size.width + 80.dp.toPx() - r1, 120.dp.toPx() + r1), r1), r1, Offset(size.width + 80.dp.toPx() - r1, 120.dp.toPx() + r1))
        val r2 = 140.dp.toPx(); drawCircle(Brush.radialGradient(listOf(Color(0x33A8C8FF), Color(0x00A8C8FF)), Offset(-90.dp.toPx() + r2, size.height - 160.dp.toPx() - r2), r2), r2, Offset(-90.dp.toPx() + r2, size.height - 160.dp.toPx() - r2))
    }
}

// ---------------- 字体：Inter（西文数字）+ JetBrains Mono（等宽），中文跟系统 ----------------
// 可变字体在安卓上按粗细切换不可靠，改为预先生成好的各粗细静态字体（只含西文和常用符号）
val Inter: FontFamily get() = Plat.impl.inter
val Mono: FontFamily get() = Plat.impl.mono

/**
 * 字号规范（全局只用这几档，T() 会把其它数值自动对齐到最近一档）：
 *   11 标签 / 12 时间与数字信息 / 13 次要说明 / 15 按钮与卡片标题 / 16 列表与设置标题
 *   20 小标题与歌词 / 24 页面标题 / 28 主标题 / 46 日期大数字
 */
object Fs {
    const val TAG = 11; const val META = 12; const val SUB = 13; const val BODY = 15; const val TITLE = 16
    const val HEAD = 20; const val PAGE = 24; const val HERO = 28; const val DISPLAY = 46
    private val scale = intArrayOf(TAG, META, SUB, BODY, TITLE, HEAD, PAGE, HERO, DISPLAY)
    fun snap(v: Int): Int = if (v < TAG) v else scale.minBy { kotlin.math.abs(it - v) }
}

/** 字体：1 Inter 无衬线（默认，英文数字用 Inter，中文跟系统）/ 0 系统字体 */
object Typo {
    var sans by mutableIntStateOf(1)
    fun load() { sans = Plat.prefs("ui").getInt("font2", 1) }
    fun set(v: Int) { sans = v; Plat.prefs("ui").edit().putInt("font2", v).apply() }
    val family: FontFamily get() = if (sans == 1) Inter else FontFamily.Default
}

/** 对应 Flutter 的 NText.sans / NText.mono。lh 为行高倍数（0 = 字体默认） */
@Composable fun T(text: String, size: Number = 15, color: Color = c.k, w: Int = 400, mono: Boolean = false, lines: Int = 1, modifier: Modifier = Modifier,
                  ls: Float = 0f, lh: Float = 0f, underline: Boolean = false, align: androidx.compose.ui.text.style.TextAlign? = null) {
    val sz = if (size is Int) Fs.snap(size).toFloat() else size.toFloat()
    BasicText(text, modifier, TextStyle(color = color, fontSize = sz.sp, fontWeight = FontWeight(w), fontFamily = if (mono) Mono else Typo.family,
        letterSpacing = (ls * sz).sp, lineHeight = if (lh > 0) (sz * lh).sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        textDecoration = if (underline) TextDecoration.Underline else null, textAlign = align ?: androidx.compose.ui.text.style.TextAlign.Unspecified),
        maxLines = lines, overflow = TextOverflow.Ellipsis)
}

/** 按下：变淡到 0.7，按钮再缩到 0.96；松手弹回（和 Flutter 版 Tap 一致） */
/** 长按复制：返回一个函数，传要复制的文字和提示里的名字 */
@Composable fun rememberCopy(): (String, String) -> Unit {
    @Suppress("DEPRECATION") val cb = androidx.compose.ui.platform.LocalClipboardManager.current
    val nav = LocalNav.current
    return remember(cb, nav) { { text, what -> cb.setText(androidx.compose.ui.text.AnnotatedString(text)); nav.toast = "已复制$what" } }
}

@Composable fun Modifier.press(scale: Float = 1f, haptic: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val a by animateFloatAsState(if (down) .7f else 1f, tween(if (down) 60 else 220), label = "a")
    val sc by animateFloatAsState(if (down) scale else 1f, spring(1f, Anim.stiff(if (down) Spring.StiffnessHigh else Spring.StiffnessMediumLow)), label = "s")
    val h = LocalHapticFeedback.current
    return this.graphicsLayer { alpha = a; scaleX = sc; scaleY = sc }.combinedClickable(src, null,
        onLongClick = onLong?.let { { h.performHapticFeedback(HapticFeedbackType.LongPress); it() } }) {
        if (haptic) h.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick()
    }
}

// ---------------- 图标：直接用原型的 SVG 路径 ----------------
object NIcons {
    const val chevronDown = "s2.6|M5 9l7 7 7-7"
    const val chevronRight = "s|M9 5l7 7-7 7"
    const val back = "s|M15 5l-7 7 7 7"
    const val search = "s|M4 10a6 6 0 1 0 12 0a6 6 0 1 0 -12 0M15 15l6 6"
    const val pause = "s3|M8 4v16M16 4v16"
    const val play = "f|M7 4l13 8-13 8z"
    const val heart = "f|M12 20l-7.5-7.3A4.8 4.8 0 0 1 12 6.2a4.8 4.8 0 0 1 7.5 6.5z"
    const val heartLine = "s|M12 20l-7.5-7.3A4.8 4.8 0 0 1 12 6.2a4.8 4.8 0 0 1 7.5 6.5z"
    const val folder = "s|M3 6h7l2 2h9v11H3z"
    const val locate = "s|M12 2v4M12 18v4M2 12h4M18 12h4M7 12a5 5 0 1 0 10 0a5 5 0 1 0 -10 0M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0"
    const val lock = "s|M6 11h12v9H6zM8.5 11V8a3.5 3.5 0 0 1 7 0v3"
    const val moon = "s|M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"
    const val clock = "s|M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0M12 8v4l3 2"
    const val clockSmall = "s|M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0M12 7v5l3 2"
    const val prev = "s|M6 5v14M19 5l-10 7 10 7z"
    const val next = "s|M18 5v14M5 5l10 7-10 7z"
    const val modeSeq = "r|M8 6h8a4.5 4.5 0 0 1 0 9H5M8.5 11.5L5 15l3.5 3.5"
    const val modeShuffle = "s|M3 7h4l10 10h4M3 17h4l3-3M14 10l3-3h4M18 4l3 3-3 3M18 14l3 3-3 3"
    const val modeOne = "s|M4 11V9a3 3 0 0 1 3-3h13M17 3l3 3-3 3M20 13v2a3 3 0 0 1-3 3H4M7 21l-3-3 3-3M11.5 10l1-.5V15"
    const val home = "s|M4 11l8-7 8 7v9h-5v-6H9v6H4z"
    const val library = "s|M5 4v16M10 4v16M15 5l4 15"
    const val queue = "s|M4 6h16M4 12h16M4 18h16"
    const val close = "x|M3 3l18 18M21 3L3 21"
    const val download = "s|M12 4v11M7 10l5 5 5-5M5 20h14"
    const val cloud = "s|M7 18h11a4 4 0 0 0 0-8 6 6 0 0 0-11.5 1.5A3.3 3.3 0 0 0 7 18z"
    const val chart = "s|M5 20V11M12 20V4M19 20v-8"
    const val disc = "s|M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"
    const val user = "s|M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0M4 21c0-4 4-6 8-6s8 2 8 6"
    const val plus = "s|M12 5v14M5 12h14"
    const val send = "f|M3 11l18-8-6 18-3-7z"
    const val gear = "s|M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1"
    const val up = "s|M5 15l7-7 7 7"
    const val down = "s|M5 9l7 7 7-7"
    const val image = "s|M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15 9.5a1 1 0 1 0 0.01 0"
    const val shift = "s|M12 4v16M6 8l-3 4 3 4M18 8l3 4-3 4"
    const val trash = "s|M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6"
    const val comment = "s|M4 5h16v11H10l-5 4v-4H4z"
    const val more = "f|M4 10.5h3.5v3.5H4zM10.25 10.5h3.5v3.5h-3.5zM16.5 10.5H20v3.5h-3.5z"
    const val radio = "s|M4 9h16v11H4zM8 5l8 4M8 14.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0M14 13h3M14 16h3"
}
private val iconCache = HashMap<String, ImageVector>()
private fun vector(spec: String): ImageVector = iconCache.getOrPut(spec) {
    val (kind, d) = spec.split('|', limit = 2)
    val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    val nodes = PathParser().parsePathString(d).toNodes()
    if (kind == "f") b.addPath(nodes, fill = SolidColor(Color.Black))
    else b.addPath(nodes, stroke = SolidColor(Color.Black),
        strokeLineWidth = when (kind) { "s2.6" -> 2.6f; "s3" -> 3f; "x" -> 3f; else -> 2f },
        strokeLineCap = if (kind == "r") StrokeCap.Round else StrokeCap.Square,
        strokeLineJoin = if (kind == "r") StrokeJoin.Round else StrokeJoin.Miter)
    b.build()
}
@Composable fun NIcon(spec: String, size: Dp = 24.dp, color: Color = c.k) =
    Image(rememberVectorPainter(vector(spec)), null, Modifier.size(size), colorFilter = ColorFilter.tint(color))

// ---------------- 组件 ----------------
@Composable fun Tag(text: String) =
    Box(Modifier.background(c.tagBg).padding(horizontal = 7.dp, vertical = 1.dp)) { T(text, 11, c.tagFg, 700, ls = .04f, lh = 1.6f) }

@Composable fun TagRow(text: String, lineEnd: Dp = 0.dp, trailing: (@Composable () -> Unit)? = null) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Tag(text); Box(Modifier.weight(1f).padding(start = 10.dp, end = lineEnd).height(1.dp).background(if (Look.flat) Color.Transparent else c.l3)); trailing?.invoke()
    }

@Composable fun SquareBtn(icon: String, size: Dp = 44.dp, iconSize: Dp = 18.dp, filled: Boolean = false, onClick: () -> Unit) =
    Box(Modifier.press(.96f, true, onClick = onClick).size(size).background(if (filled) c.p else Color.Transparent).then(if (filled) Modifier else Modifier.nbf(1.dp, c.k)),
        contentAlignment = Alignment.Center) { NIcon(icon, iconSize, if (filled) Color.White else c.k) }

@Composable fun PrimaryBar(label: String, icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // 宽屏按钮行里：和「随机播放」一样大的方块按钮
    val compact = LocalCompactAction.current
    Row(modifier.then(if (compact) Modifier.width(ACTION_W) else Modifier).press(.96f, true, onClick = onClick).height(48.dp).background(c.p).padding(horizontal = if (compact) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start) {
        if (compact) { NIcon(icon, 16.dp, Color.White); Spacer(Modifier.width(6.dp)); T(label, 15, Color.White, 700) }
        else { T(label, 16, Color.White, 700, modifier = Modifier.weight(1f)); NIcon(icon, 22.dp, Color.White) }
    }
}

/** 宽屏按钮行里主按钮和描边按钮统一的宽度 */
val ACTION_W = 124.dp
val LocalCompactAction = compositionLocalOf { false }

@Composable fun OutlineBtn(label: String, height: Dp = 48.dp, size: Int = 15, pad: Dp = 18.dp, color: Color = c.k, onClick: () -> Unit) =
    Box(Modifier.then(if (LocalCompactAction.current) Modifier.width(ACTION_W) else Modifier).press(.96f, true, onClick = onClick).height(height).nbf(1.dp, color).padding(horizontal = pad), contentAlignment = Alignment.Center) { T(label, size, color) }

/** 小号文字按钮：细黑框方块（不再用下划线），外面留出 44 的点击热区 */
@Composable fun LinkBtn(text: String, size: Int = 13, color: Color = c.k, onClick: () -> Unit) =
    Box(Modifier.press(.96f, onClick = onClick).defaultMinSize(44.dp, 44.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.height(28.dp).nbf(1.dp, color).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { T(text, 12, color, 700, ls = .04f) }
    }

@Composable fun NSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(if (on) 23.dp else 3.dp, spring(1f, Anim.stiff(Spring.StiffnessMedium)), label = "sw")
    Box(Modifier.size(52.dp, 44.dp).pointerInput(on) { detectTapGestures { onChange(!on) } }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp, 24.dp).background(if (on) c.p else Color.Transparent).nbf(1.dp, c.k)) {
            Box(Modifier.offset(x = x, y = 3.dp).size(16.dp).background(if (on) Color.White else c.k))
        }
    }
}

@Composable fun Cover(url: String?, size: Dp, title: String = "", border: Boolean = true, decodeSize: Dp = size) {
    val px = with(LocalDensity.current) { decodeSize.roundToPx() }
    Box(Modifier.size(size).background(c.ph).then(if (border) Modifier.nb(1.dp, c.k) else Modifier), contentAlignment = Alignment.Center) {
        if (url == null) T(title.take(1).uppercase().ifEmpty { "♪" }, size.value * .42f, c.s, 800)
        else AsyncImage(img(url, px), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

@Composable fun IconCover(icon: String, size: Dp, filled: Boolean = false, iconSize: Dp = if (size > 80.dp) 44.dp else 24.dp) =
    Box(Modifier.size(size).background(if (filled) c.p else Color.Transparent).nbf(1.dp, c.k), contentAlignment = Alignment.Center) { NIcon(icon, iconSize, if (filled) Color.White else c.k) }

/** 曲目行（高 64）：外层列表负责左右 18 的边距 */
@Composable fun TrackRow(t: Track, current: Boolean, label: String? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val p = c
    val fg = if (current) p.p else p.k
    Row(Modifier.fillMaxWidth().height(64.dp).press(onLong = onLong, onClick = onClick).drawBehind {
        if (current) {
            val ext = 10.dp.toPx()
            drawRect(p.hl, Offset(-ext, 0f), Size(size.width + ext * 2, size.height))
            drawRect(p.p, Offset(-ext, 0f), Size(3.dp.toPx(), size.height))
        }
        if (!Look.flat) drawRect(p.l1, Offset(if (current) -10.dp.toPx() else 0f, size.height - 1.dp.toPx()), Size(size.width + if (current) 20.dp.toPx() else 0f, 1.dp.toPx()))
    }, verticalAlignment = Alignment.CenterVertically) {
        Cover(t.cover, 44.dp, t.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(t.title, 16, fg, if (current) 700 else 600, lh = 1.4f)
            T(t.artist, 13, p.s, lh = 1.4f)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            T(if (t.durationMs > 0) fmt(t.durationMs) else "--:--", 12, fg, mono = true)
            val dls by Downloads.items.collectAsState()
            val act by Downloads.active.collectAsState()
            val lab = label ?: act[t.id]?.let { if (it < 0) "等待下载" else "%.0f%%".format(it * 100) } ?: if (dls.any { it.track.id == t.id }) "已下载" else null
            if (lab != null) T(lab, 11, p.s, mono = true, ls = .06f)
        }
    }
}

/** 进度条：热区 16，线 4（拖动时 6），拖动时出现方形滑块 */
@Composable fun ScrubBar(durMs: Long, hit: Dp = 16.dp, interactive: Boolean = true, onScrub: (Float?) -> Unit = {}) {
    val pos by Engine.position.collectAsState()
    val buf by Engine.buffered.collectAsState()
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur = durMs.coerceAtLeast(1)
    // 和 Flutter 版 ScrubBar 完全一致：
    // 进度线宽度永远用 150ms 匀速动画追到新位置（拖动、播放、跳转都一样，这就是拖尾）；
    // 方块滑块直接在手指位置，只做 150ms 的缩放（0.4→1）和淡入淡出
    val frac = drag ?: (pos.toFloat() / dur).coerceIn(0f, 1f)
    val fill by animateFloatAsState(frac, tween(150, easing = androidx.compose.animation.core.LinearEasing), label = "fill")
    val thumbAt = frac
    val h by animateDpAsState(if (drag != null) 6.dp else 4.dp, tween(150, easing = androidx.compose.animation.core.LinearEasing), label = "h")
    val th by animateFloatAsState(if (drag != null) 1f else 0f, tween(150, easing = androidx.compose.animation.core.LinearEasing), label = "t")
    val ts by animateFloatAsState(if (drag != null) 1f else .4f, tween(150, easing = androidx.compose.animation.core.LinearEasing), label = "ts")
    val p = c
    Box(Modifier.fillMaxWidth().height(hit).then(if (!interactive) Modifier else Modifier.pointerInput(dur) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull() ?: continue
                val f = (ch.position.x / size.width).coerceIn(0f, 1f)
                if (ch.pressed) { drag = f; onScrub(f); ch.consume() }
                else if (drag != null) { Engine.seek((drag!! * dur).toLong()); drag = null; onScrub(null) }
            }
        }
    }).drawBehind {
        val y = size.height / 2; val hp = h.toPx()
        drawRect(p.l3, Offset(0f, y - hp / 2), Size(size.width, hp))
        // 已缓存的部分：比底色深一点
        if (UiPref.showBuffer && interactive) drawRect(p.k.copy(alpha = .22f), Offset(0f, y - hp / 2), Size(size.width * (buf.toFloat() / dur).coerceIn(0f, 1f), hp))
        drawRect(p.p, Offset(0f, y - hp / 2), Size(size.width * fill, hp))
        if (th > 0) { val s = 12.dp.toPx() * ts; drawRect(p.p.copy(alpha = th), Offset(size.width * thumbAt - s / 2, y - s / 2), Size(s, s)) }
    })
}

@Composable fun TimeRow(durMs: Long, scrub: Float? = null, size: Int = 12) {
    val pos by Engine.position.collectAsState()
    Row(Modifier.fillMaxWidth()) {
        T(fmt(if (scrub != null) (scrub * durMs).toLong() else pos), size, c.p, mono = true, modifier = Modifier.weight(1f))
        T(fmt(durMs), size, c.s, mono = true)
    }
}

fun two(n: Int) = n.toString().padStart(2, '0')
fun fmt(ms: Long): String { val t = (ms / 1000).coerceAtLeast(0); val h = t / 3600; return if (h > 0) "$h:${two((t % 3600 / 60).toInt())}:${two((t % 60).toInt())}" else "${two((t / 60).toInt())}:${two((t % 60).toInt())}" }
fun listMeta(l: List<Track>) = "${two(l.size)} 首 · ${fmt(l.sumOf { it.durationMs })}"

@Composable fun Avatar(size: Dp, font: Int) {
    val p by Store.profile.collectAsState()
    Box(Modifier.size(size).background(c.p).nb(1.dp, c.k), contentAlignment = Alignment.Center) {
        if (p == null) T("猫", font, Color.White, 900)
        else Plat.impl.AnimatedImage(p!!.avatar, Modifier.matchParentSize())
    }
}

@Suppress("unused") private fun Float.r() = roundToInt()

/** 当前是不是宽屏（PC）布局，由 Root 按窗口宽度更新 */
object Screen {
    var wide by mutableStateOf(false)
    /** 矮屏（窗口高度 < 720dp，比如 4.7 寸以下的 iPhone）：打开播放页时收起顶栏，把高度让给封面 */
    var short by mutableStateOf(false)
}

/** 操作按钮行：手机占满一行；宽屏靠右，播放全部和随机播放一样大（和列表右侧的时长对齐） */
@Composable fun ActionRow(content: @Composable RowScope.() -> Unit) {
    if (Screen.wide) Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1f))
        CompositionLocalProvider(LocalCompactAction provides true) { Row(Modifier.width(IntrinsicSize.Max), content = content) }
    }
    else Row(Modifier.fillMaxWidth(), content = content)
}

/** 顶栏 / 侧边栏 / 播放条的底色：有自定义背景时半透明，让背景连成一片 */
@Composable fun barBg(): Color = if (Bg.on) c.bar.copy(alpha = UiPref.barAlpha) else c.bar

/** 细节偏好（详细调整页） */
object UiPref {
    var showBuffer by mutableStateOf(true)      // 进度条上显示已缓存的部分
    var lyricBlur by mutableStateOf(true)       // 歌词远处轻微模糊
    var karaoke by mutableStateOf(true)         // 逐字高亮
    var barAlpha by mutableFloatStateOf(.5f)    // 有背景时栏位的不透明度
    var lowMem by mutableStateOf(false)         // 省内存模式
    var tray by mutableStateOf(true)            // 桌面：关闭窗口时留在托盘继续播放
    var autoHideSide by mutableStateOf(true)    // PC 播放页按钮竖栏：鼠标靠近才显示
    var coverPct by mutableIntStateOf(80)       // PC 播放页封面大小（占可用空间的百分比）
    /** 播放页底下那排按钮显示哪些 */
    val BTN_KEYS = listOf("mode", "sleep", "dl", "more", "comment", "queue")
    val BTN_NAMES = listOf("播放模式", "定时关闭", "下载", "更多", "评论", "播放列表")
    var btns by mutableStateOf(setOf("mode", "sleep", "more", "comment", "queue"))
    fun toggleBtn(key: String) { btns = if (key in btns) btns - key else btns + key; k.edit().putStringSet("btns", btns).apply() }
    private val k get() = Plat.prefs("ui")
    fun load() { k.getStringSet("btns", null)?.let { btns = it }; showBuffer = k.getBoolean("buf", true); lyricBlur = k.getBoolean("lblur", true); karaoke = k.getBoolean("kara", true); barAlpha = k.getFloat("barA", .5f); lowMem = k.getBoolean("lowMem", false); tray = k.getBoolean("tray", true); coverPct = k.getInt("coverPct", 80); autoHideSide = k.getBoolean("sideAuto", true) }
    fun save() { k.edit().putBoolean("buf", showBuffer).putBoolean("lblur", lyricBlur).putBoolean("kara", karaoke).putFloat("barA", barAlpha).putBoolean("lowMem", lowMem).putBoolean("tray", tray).putInt("coverPct", coverPct).putBoolean("sideAuto", autoHideSide).apply() }
}

/** 歌词字号（设置里可调，歌词页 / 右栏 / 播放页都用它） */
object LyricPref {
    var size by mutableIntStateOf(24)
    /** 当前句停在哪：0 顶部 / 1 第二行（上面保留上一句）/ 2 偏上三分之一 / 3 居中 */
    val ANCHORS = listOf("顶部", "保留上一句", "偏上", "居中")
    var anchor by mutableIntStateOf(1)
    fun changeAnchor(v: Int) { anchor = v; Plat.prefs("ui").edit().putInt("lyricAnchor", v).apply() }
    fun load() { size = Plat.prefs("ui").getInt("lyricSize", 24); anchor = Plat.prefs("ui").getInt("lyricAnchor", 1) }
    fun set(v: Int) { size = v.coerceIn(16, 40); Plat.prefs("ui").edit().putInt("lyricSize", size).apply() }
}

/** 带角标的封面：封面图 + 右下角蓝底白色图标；没有封面时退回图标块 */
@Composable fun BadgeCover(url: String?, size: Dp, icon: String, filledFallback: Boolean) {
    if (url == null) { IconCover(icon, size, filledFallback, if (size > 80.dp) 44.dp else 24.dp); return }
    val badge = (size.value * .3f).coerceIn(18f, 40f).dp
    Box(Modifier.size(size)) {
        Cover(url, size)
        Box(Modifier.align(Alignment.BottomEnd).size(badge).background(c.p).nb(1.dp, c.k), contentAlignment = Alignment.Center) {
            NIcon(icon, badge * .62f, Color.White)
        }
    }
}

/** 「我喜欢」：歌单封面 + 爱心角标 */
@Composable fun LikedCover(url: String?, size: Dp) = BadgeCover(url, size, NIcons.heart, true)

/** 「最近播放」：最后播放那首的封面 + 时钟角标 */
@Composable fun RecentCover(url: String?, size: Dp) = BadgeCover(url, size, NIcons.clock, false)

/** 网易云图片按显示尺寸取小图 */
fun img(url: String?, px: Int): String? {
    if (url == null) return null
    val b = when { px <= 160 -> 160; px <= 256 -> 256; px <= 512 -> 512; else -> 768 }
    val base = url.substringBefore('?')
    return if (base.contains("/obj/")) "$base?imageView=1&thumbnail=${b}x$b&type=webp" else "$base?param=${b}y$b"
}



/**
 * 定位到正在播放：当前歌在列表里、但不在屏幕上时，右下角出现一个方块按钮，点一下滚过去（停在偏上的位置）。
 * target = 正在播放那一行在 LazyColumn 里的序号（含前面的标题项），-1 = 列表里没有。
 */
@Composable fun BoxScope.LocateBtn(ls: androidx.compose.foundation.lazy.LazyListState, target: Int, bottom: Dp = 16.dp) {
    val scope = rememberCoroutineScope()
    val show by remember(target) { derivedStateOf {
        target >= 0 && ls.layoutInfo.visibleItemsInfo.let { v -> v.isEmpty() || v.none { it.index == target } || v.first { it.index == target }.let { it.offset < 0 || it.offset + it.size > ls.layoutInfo.viewportEndOffset } }
    } }
    androidx.compose.animation.AnimatedVisibility(show, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottom),
        enter = androidx.compose.animation.fadeIn(tween(150)) + androidx.compose.animation.scaleIn(tween(150), .8f),
        exit = androidx.compose.animation.fadeOut(tween(150)) + androidx.compose.animation.scaleOut(tween(150), .8f)) {
        Box(Modifier.size(44.dp).press(.92f, true) {
            scope.launch { ls.animateScrollToItem(target, -(ls.layoutInfo.viewportEndOffset / 3)) }
        }.background(c.sheet).border(if (Look.flat) 0.dp else 1.dp, c.k), contentAlignment = Alignment.Center) { NIcon(NIcons.locate, 20.dp, c.p) }
    }
}
