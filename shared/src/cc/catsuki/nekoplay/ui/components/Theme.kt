package cc.catsuki.nekoplay

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
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
/** 主题（界面风格）：0 BenchLab 粗线框 / 1 无边框（默认，去掉所有框线和分隔线，控件改用浅色底块区分） */
object Look {
    val NAMES = listOf("BenchLab", "无边框")
    var style by mutableIntStateOf(1)
    val flat get() = style == 1
    fun load() { style = Plat.prefs("ui").getInt("look", 1) }
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

@Composable private fun MonetCoverWatcher() {
    val cover = if (Monet.mode == 3) Engine.state.collectAsState().value.current?.cover else null
    LaunchedEffect(Monet.mode, Monet.hue, cover, Bg.on, Bg.source) { Monet.refresh(cover) }
}

@Composable fun NekoTheme(content: @Composable () -> Unit) {
    val dark = when (ThemePref.mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    // 莫奈取色抽到独立节点，避免播放状态变化触发整个 NekoTheme 顶层重组
    MonetCoverWatcher()
    val customPal = ThemeStore.activePal()
    val base = customPal ?: if (dark) Dark else Light
    // 全局缩放：严禁在 NekoTheme 顶层直接读取 LocalWindowInfo.containerSize（拖窗每动 1px 都会触发整棵 App 重组导致掉帧！）
    // 改在 LaunchedEffect 里用 snapshotFlow 采样 + 250ms 防抖，拖窗全程 NekoTheme 0 重组
    val d = androidx.compose.ui.platform.LocalDensity.current
    val winInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    var settledAuto by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(UiPref.autoScale, d.density) {
        if (!UiPref.autoScale || !Plat.isDesktop) { settledAuto = 1f; return@LaunchedEffect }
        var job: kotlinx.coroutines.Job? = null
        snapshotFlow { winInfo.containerSize.width }.collect { winPx ->
            if (winPx <= 0) return@collect
            val logicalW = winPx / d.density
            val target = if (logicalW < 760f) 1f else ((((logicalW / 1280f).coerceIn(.85f, 1.25f) * 20f).roundToInt()) / 20f)
            if (target != settledAuto) {
                job?.cancel()
                job = launch { kotlinx.coroutines.delay(250); settledAuto = target }
            }
        }
    }
    val finalScale = (UiPref.scale / 100f * settledAuto).coerceIn(.6f, 1.6f)
    val dens = remember(d, finalScale) { androidx.compose.ui.unit.Density(d.density * finalScale, d.fontScale) }
    val resolvedPal = if (customPal != null) base else if (Monet.on) Monet.apply(base) else base
    CompositionLocalProvider(LocalPal provides animPal(resolvedPal), androidx.compose.ui.platform.LocalDensity provides dens, content = content)
}

/** 配色切换（换歌跟封面变色）时背景层和主色做一个渐变 */
@Composable private fun animPal(t: Pal): Pal {
    val sp = Motion.crossfade<Color>()
    @Composable fun a(v: Color, l: String) = androidx.compose.animation.animateColorAsState(v, sp, label = l).value
    return t.copy(p = a(t.p, "p"), page = a(t.page, "pg"), bar = a(t.bar, "bar"), sheet = a(t.sheet, "sh"), pop = a(t.pop, "pop"), ph = a(t.ph, "ph"), lyBg = a(t.lyBg, "ly"), tagFg = a(t.tagFg, "tf"))
}

/** 背景：深色纯平，浅色 165° 渐变 + 两个淡光斑（drawWithCache 缓存 Brush，避免每帧重算渐变） */
@Composable fun Modifier.backdrop(): Modifier {
    val p = c
    if (ThemeStore.activePal() != null || Monet.on) return if (p.dark) background(p.page) else drawWithCache {
        val bg = Brush.linearGradient(listOf(p.bar, p.page, p.sheet), Offset(size.width * .37f, 0f), Offset(size.width * .63f, size.height))
        onDrawBehind { drawRect(bg) }
    }
    return if (p.dark) background(p.page) else drawWithCache {
        val bg = Brush.linearGradient(listOf(Color(0xFFC6DCE4), Color(0xFFD3E5EA), Color(0xFFE2EDEE)), Offset(size.width * .37f, 0f), Offset(size.width * .63f, size.height))
        val r1 = 130.dp.toPx(); val c1 = Offset(size.width + 80.dp.toPx() - r1, 120.dp.toPx() + r1)
        val b1 = Brush.radialGradient(listOf(Color(0x33FFD9B0), Color(0x00FFD9B0)), c1, r1)
        val r2 = 140.dp.toPx(); val c2 = Offset(-90.dp.toPx() + r2, size.height - 160.dp.toPx() - r2)
        val b2 = Brush.radialGradient(listOf(Color(0x33A8C8FF), Color(0x00A8C8FF)), c2, r2)
        onDrawBehind {
            drawRect(bg)
            drawCircle(b1, r1, c1)
            drawCircle(b2, r2, c2)
        }
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

object Screen {
    var wide by mutableStateOf(false)
    /** 矮屏（窗口高度 < 720dp，比如 4.7 寸以下的 iPhone）：打开播放页时收起顶栏，把高度让给封面 */
    var short by mutableStateOf(false)
    /** 窄屏（宽度 < 360dp，比如 iPhone SE 一代）：按钮文字缩小、少留边 */
    var narrow by mutableStateOf(false)
}

/** 操作按钮行：手机占满一行；宽屏靠右，播放全部和随机播放一样大（和列表右侧的时长对齐） */

@Composable fun barBg(): Color = if (Bg.on) c.bar.copy(alpha = UiPref.barAlpha) else c.bar

/** 细节偏好（详细调整页） */
object UiPref {
    /** 是否在 macOS 上运行（窗口圆角只在 Mac 上提供） */
    val isMac: Boolean by lazy { Plat.isDesktop && (System.getProperty("os.name") ?: "").lowercase().contains("mac") }
    var showBuffer by mutableStateOf(true)      // 进度条上显示已缓存的部分
    var lyricBlur by mutableStateOf(true)       // 歌词远处轻微模糊
    var karaoke by mutableStateOf(true)         // 逐字高亮
    var barAlpha by mutableFloatStateOf(.5f)    // 有背景时栏位的不透明度
    var lowMem by mutableStateOf(false)         // 省内存模式
    var scale by mutableIntStateOf(100)        // 全局缩放（%）
    var autoScale by mutableStateOf(false)      // 桌面：按窗口宽度自适应缩放（默认关，避免拖窗松手后界面跳变）
    var round by mutableStateOf(true)          // macOS：窗口圆角
    var tray by mutableStateOf(true)            // 桌面：关闭窗口时留在托盘继续播放
    var autoHideSide by mutableStateOf(true)    // PC 播放页按钮竖栏：鼠标靠近才显示
    var coverPct by mutableIntStateOf(80)       // PC 播放页封面大小（占可用空间的百分比）
    var homePlugin by mutableStateOf("netease")   // 首页内容来源：插件 id，或 "mix" 混合
    /** 播放页底下那排按钮显示哪些 */
    val BTN_KEYS = listOf("mode", "sleep", "dl", "more", "comment", "queue")
    val BTN_NAMES = listOf("播放模式", "定时关闭", "下载", "更多", "评论", "播放列表")
    var btns by mutableStateOf(setOf("mode", "sleep", "more", "comment", "queue"))
    fun toggleBtn(key: String) { btns = if (key in btns) btns - key else btns + key; k.edit().putStringSet("btns", btns).apply() }
    private val k get() = Plat.prefs("ui")
    fun load() { k.getStringSet("btns", null)?.let { btns = it }; showBuffer = k.getBoolean("buf", true); lyricBlur = k.getBoolean("lblur", true); karaoke = k.getBoolean("kara", true); barAlpha = k.getFloat("barA", .5f); lowMem = k.getBoolean("lowMem", false); tray = k.getBoolean("tray", true); coverPct = k.getInt("coverPct", 80); autoHideSide = k.getBoolean("sideAuto", true); scale = k.getInt("scale", 100).coerceIn(60, 150); autoScale = k.getBoolean("autoScale", false); round = k.getBoolean("round", true); homePlugin = k.getString("homePlugin", null) ?: listOf("netease", "qqmusic", "kugou", "mix").getOrElse(k.getInt("homeSrc", 0)) { "netease" } }
    fun save() { k.edit().putBoolean("buf", showBuffer).putBoolean("lblur", lyricBlur).putBoolean("kara", karaoke).putFloat("barA", barAlpha).putBoolean("lowMem", lowMem).putBoolean("tray", tray).putInt("coverPct", coverPct).putBoolean("sideAuto", autoHideSide).putInt("scale", scale).putBoolean("autoScale", autoScale).putBoolean("round", round).putString("homePlugin", homePlugin).apply() }
}

/** 歌词样式与字号（支持预设切换及 JSON 导入导出；歌词页 / 右栏 / 播放页共用） */
object LyricPref {
    val STYLES = listOf("大字流动", "平滑跟进", "居中经典", "逐字浮光")
    val ANCHORS = listOf("顶部", "保留上一句", "偏上", "居中")
    val ALIGNS = listOf("左对齐", "居中")
    var style by mutableIntStateOf(0)
    var size by mutableIntStateOf(24)
    /** 当前句停在哪：0 顶部 / 1 第二行（上面保留上一句）/ 2 偏上三分之一 / 3 居中 */
    var anchor by mutableIntStateOf(1)
    /** 对齐：0 左对齐 / 1 居中 */
    var align by mutableIntStateOf(0)
    var gap by mutableIntStateOf(26)
    var scrollMs by mutableIntStateOf(580)
    var staggerMs by mutableIntStateOf(35)
    var activeScale by mutableFloatStateOf(1.08f)
    var idleBackMs by mutableIntStateOf(3000)
    var accentBar by mutableStateOf(false)
    var customName by mutableStateOf("")

    private val k get() = Plat.prefs("ui")
    fun applyPreset(idx: Int) {
        style = idx.coerceIn(0, STYLES.lastIndex); customName = ""
        when (style) {
            1 -> { align = 0; gap = 28; scrollMs = 580; staggerMs = 0; activeScale = 1.06f; idleBackMs = 1000; accentBar = true; anchor = 2 }
            2 -> { align = 1; gap = 22; scrollMs = 460; staggerMs = 0; activeScale = 1.08f; idleBackMs = 2000; accentBar = false; anchor = 3 }
            3 -> { align = 0; gap = 30; scrollMs = 540; staggerMs = 50; activeScale = 1.04f; idleBackMs = 2600; accentBar = false; anchor = 2 }
            else -> { align = 0; gap = 26; scrollMs = 520; staggerMs = 35; activeScale = 1.08f; idleBackMs = 3000; accentBar = false; anchor = 1 }
        }
        save()
    }
    fun changeAnchor(v: Int) { anchor = v.coerceIn(0, 3); save() }
    fun changeAlign(v: Int) { align = v.coerceIn(0, 1); save() }
    fun set(v: Int) { size = v.coerceIn(16, 40); save() }
    fun save() {
        k.edit().putInt("lyricStyle", style).putInt("lyricSize", size).putInt("lyricAnchor", anchor)
            .putInt("lyricAlign", align).putInt("lyricGap", gap).putInt("lyricScrollMs", scrollMs)
            .putInt("lyricStaggerMs", staggerMs).putFloat("lyricScale", activeScale)
            .putInt("lyricIdleMs", idleBackMs).putBoolean("lyricBar", accentBar)
            .putString("lyricCustomName", customName).apply()
    }
    fun load() {
        style = k.getInt("lyricStyle", 0).coerceIn(0, STYLES.lastIndex)
        size = k.getInt("lyricSize", 24).coerceIn(16, 40)
        anchor = k.getInt("lyricAnchor", 1).coerceIn(0, 3)
        align = k.getInt("lyricAlign", if (style == 2) 1 else 0).coerceIn(0, 1)
        gap = k.getInt("lyricGap", if (style == 1) 28 else 26).coerceIn(10, 60)
        scrollMs = k.getInt("lyricScrollMs", 580).coerceIn(120, 1500)
        staggerMs = k.getInt("lyricStaggerMs", if (style == 0) 35 else 0).coerceIn(0, 120)
        activeScale = k.getFloat("lyricScale", 1.08f).coerceIn(1f, 1.35f)
        idleBackMs = k.getInt("lyricIdleMs", if (style == 1) 1000 else 3000).coerceIn(500, 8000)
        accentBar = k.getBoolean("lyricBar", style == 1)
        customName = k.getString("lyricCustomName", "") ?: ""
    }
    fun exportJson(): String = org.json.JSONObject()
        .put("format", "nekoplay-lyric-style-v1")
        .put("name", customName.ifBlank { STYLES.getOrElse(style) { "自定义" } })
        .put("style", style).put("size", size).put("anchor", anchor).put("align", align)
        .put("gap", gap).put("scrollMs", scrollMs).put("staggerMs", staggerMs)
        .put("activeScale", activeScale.toDouble()).put("idleBackMs", idleBackMs)
        .put("accentBar", accentBar).put("karaoke", UiPref.karaoke).put("blur", UiPref.lyricBlur)
        .toString(2)
    fun importJson(raw: String): String {
        val o = org.json.JSONObject(raw.trim())
        if (o.has("style")) style = o.optInt("style", style).coerceIn(0, STYLES.lastIndex)
        if (o.has("size")) size = o.optInt("size", size).coerceIn(16, 40)
        if (o.has("anchor")) anchor = o.optInt("anchor", anchor).coerceIn(0, 3)
        if (o.has("align")) align = o.optInt("align", align).coerceIn(0, 1)
        if (o.has("gap")) gap = o.optInt("gap", gap).coerceIn(10, 60)
        if (o.has("scrollMs")) scrollMs = o.optInt("scrollMs", scrollMs).coerceIn(120, 1500)
        if (o.has("staggerMs")) staggerMs = o.optInt("staggerMs", staggerMs).coerceIn(0, 120)
        if (o.has("activeScale")) activeScale = o.optDouble("activeScale", activeScale.toDouble()).toFloat().coerceIn(1f, 1.35f)
        if (o.has("idleBackMs")) idleBackMs = o.optInt("idleBackMs", idleBackMs).coerceIn(500, 8000)
        if (o.has("accentBar")) accentBar = o.optBoolean("accentBar", accentBar)
        if (o.has("karaoke") || o.has("blur")) {
            if (o.has("karaoke")) UiPref.karaoke = o.optBoolean("karaoke", UiPref.karaoke)
            if (o.has("blur")) UiPref.lyricBlur = o.optBoolean("blur", UiPref.lyricBlur)
            UiPref.save()
        }
        customName = o.optString("name", "").trim().take(24)
        save()
        return customName.ifBlank { STYLES.getOrElse(style) { "自定义样式" } }
    }
}

/** 带角标的封面：封面图 + 右下角蓝底白色图标；没有封面时退回图标块 */
