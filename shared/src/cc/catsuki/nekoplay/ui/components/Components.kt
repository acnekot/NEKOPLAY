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
    val a = animateFloatAsState(if (down) .72f else 1f, if (AnimStyle.off) androidx.compose.animation.core.snap() else tween(if (down) 45 else Motion.standardMs, easing = AnimStyle.activeEasing), label = "a")
    val effScale = if (scale != 1f) scale else MotionEffects.pressScale
    val sc = if (effScale < 0.999f && !AnimStyle.off) animateFloatAsState(if (down) effScale else 1f,
        if (AnimStyle.style != 0) tween(if (down) 45 else Motion.standardMs, easing = AnimStyle.activeEasing)
        else spring(AnimStyle.springDamping, Anim.stiff(if (down) Spring.StiffnessHigh else AnimStyle.springStiffness)), label = "s") else null
    val h = LocalHapticFeedback.current
    return this.graphicsLayer { alpha = a.value; if (sc != null) { scaleX = sc.value; scaleY = sc.value } }.combinedClickable(src, null,
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

@Composable fun SquareBtn(icon: String, size: Dp = 44.dp, iconSize: Dp = 18.dp, filled: Boolean = false, scrubDir: Int = 0, onClick: () -> Unit) =
    Box((if (scrubDir != 0) Modifier.holdScrub(scrubDir, haptic = true, onClick = onClick) else Modifier.press(.96f, true, onClick = onClick)).size(size).background(if (filled) c.p else Color.Transparent).then(if (filled) Modifier else Modifier.nbf(1.dp, c.k)),
        contentAlignment = Alignment.Center) { NIcon(icon, iconSize, if (filled) Color.White else c.k) }

@Composable fun PrimaryBar(label: String, icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // 宽屏按钮行里：和「随机播放」一样大的方块按钮
    val compact = LocalCompactAction.current
    Row(modifier.then(if (compact) Modifier.width(ACTION_W) else Modifier).press(.96f, true, onClick = onClick).height(48.dp).background(c.p).padding(horizontal = if (compact) 14.dp else if (Screen.narrow) 8.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start) {
        if (compact) { NIcon(icon, 16.dp, Color.White); Spacer(Modifier.width(6.dp)); T(label, 15, Color.White, 700) }
        else if (Screen.narrow) { Spacer(Modifier.weight(1f)); NIcon(icon, 15.dp, Color.White); Spacer(Modifier.width(5.dp)); T(label, 14, Color.White, 700, lines = 1); Spacer(Modifier.weight(1f)) }
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
    val curOn by rememberUpdatedState(on)
    val curChange by rememberUpdatedState(onChange)
    val x by animateDpAsState(if (on) 23.dp else 3.dp, Motion.standard(), label = "sw")
    val bg by androidx.compose.animation.animateColorAsState(if (on) c.p else Color.Transparent, Motion.quick(), label = "swb")
    Box(Modifier.size(52.dp, 44.dp).pointerInput(Unit) { detectTapGestures { curChange(!curOn) } }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp, 24.dp).background(bg).nbf(1.dp, c.k)) {
            Box(Modifier.offset(x = x, y = 3.dp).size(16.dp).background(if (on) Color.White else c.k))
        }
    }
}

@Composable fun Cover(url: String?, size: Dp, title: String = "", border: Boolean = true, decodeSize: Dp = size) {
    val px = with(LocalDensity.current) { decodeSize.roundToPx() }
    val ctx = coil3.compose.LocalPlatformContext.current
    Box(Modifier.size(size).background(c.ph).then(if (border) Modifier.nb(1.dp, c.k) else Modifier), contentAlignment = Alignment.Center) {
        if (url == null) T(title.take(1).uppercase().ifEmpty { "♪" }, size.value * .42f, c.s, 800)
        else {
            val req = remember(url, px) { coil3.request.ImageRequest.Builder(ctx).data(img(url, px)).build() }
            AsyncImage(req, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable fun IconCover(icon: String, size: Dp, filled: Boolean = false, iconSize: Dp = if (size > 80.dp) 44.dp else 24.dp) =
    Box(Modifier.size(size).background(if (filled) c.p else Color.Transparent).nbf(1.dp, c.k), contentAlignment = Alignment.Center) { NIcon(icon, iconSize, if (filled) Color.White else c.k) }

/** 曲目行（高 64）：外层列表负责左右 18 的边距 */
/** 歌名后面的小标：这首歌要会员 / 付费才能放整首 */
@Composable fun VipTag() = Box(Modifier.border(1.dp, c.p).padding(horizontal = 4.dp, vertical = 1.dp)) { T("VIP", 9, c.p, 800, mono = true, ls = .04f) }

/** 来源小标签：开着不止一个音源时，在歌手前面标出这首歌来自哪里 */
@Composable fun SourceTag(t: Track) {
    if (Plugins.enabledSources.size < 2) return
    val name = when { t.id.startsWith("dav:") -> "WebDAV"; t.local -> "本地"; else -> Plugins.sourceName(t.id) ?: return }
    Box(Modifier.padding(end = 6.dp).border(1.dp, c.l2).padding(horizontal = 4.dp)) { T(name, 9, c.s, 700, lh = 1.4f) }
}

@Composable fun TrackRow(t: Track, current: Boolean, label: String? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val p = c
    val fg = if (current) p.p else p.k
    val hSrc = remember { MutableInteractionSource() }
    val hovered by hSrc.collectIsHoveredAsState()
    val curAnim = animateFloatAsState(if (current) 1f else 0f, Motion.standard(), label = "trc")
    val hovAnim = animateFloatAsState(if (hovered && !current && MotionEffects.hoverTint) 1f else 0f, Motion.quick(), label = "trh")
    Row(Modifier.fillMaxWidth().height(64.dp).hoverable(hSrc).press(onLong = onLong, onClick = onClick).drawBehind {
        val ext = 10.dp.toPx()
        val ca = curAnim.value
        val ha = hovAnim.value
        if (ha > 0.01f) drawRect(p.k.copy(alpha = .045f * ha), Offset(-ext, 0f), Size(size.width + ext * 2, size.height))
        if (ca > 0.01f) {
            drawRect(p.hl.copy(alpha = p.hl.alpha * ca), Offset(-ext, 0f), Size(size.width + ext * 2, size.height))
            val barH = size.height * ca
            drawRect(p.p.copy(alpha = ca), Offset(-ext, (size.height - barH) / 2f), Size(3.dp.toPx(), barH))
        }
        if (!Look.flat) drawRect(p.l1, Offset(if (current) -ext else 0f, size.height - 1.dp.toPx()), Size(size.width + if (current) ext * 2 else 0f, 1.dp.toPx()))
    }, verticalAlignment = Alignment.CenterVertically) {
        Cover(t.cover, 44.dp, t.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(t.title, 16, fg, if (current) 700 else 600, lh = 1.4f, modifier = Modifier.weight(1f, fill = false))
                if (t.vip) { Spacer(Modifier.width(6.dp)); VipTag() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceTag(t)
                T(t.artist, 13, p.s, lh = 1.4f)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            T(if (t.durationMs > 0) fmt(t.durationMs) else "--:--", 12, fg, mono = true)
            TrackDownloadLabel(t.id, label)
        }
    }
}

@Composable private fun TrackDownloadLabel(id: String, label: String?) {
    val lab = if (label != null) label else {
        val act by Downloads.active.collectAsState()
        val dls by Downloads.items.collectAsState()
        act[id]?.let { if (it < 0) "等待下载" else "%.0f%%".format(it * 100) } ?: if (dls.any { it.track.id == id }) "已下载" else null
    }
    if (lab != null) T(lab, 11, c.s, mono = true, ls = .06f)
}

/** 进度条：热区 16，线 4（拖动时 6），拖动时出现方形滑块 */
@Composable fun ScrubBar(durMs: Long, hit: Dp = 16.dp, interactive: Boolean = true, onScrub: (Float?) -> Unit = {}) {
    val pos by Engine.position.collectAsState()
    val buf by Engine.buffered.collectAsState()
    val dur = durMs.coerceAtLeast(1)
    var drag by remember(dur) { mutableStateOf<Float?>(null) }
    val curScrub by rememberUpdatedState(onScrub)
    // 和 Flutter 版 ScrubBar 完全一致：
    // 进度线宽度永远用 150ms 匀速动画追到新位置（拖动、播放、跳转都一样，这就是拖尾）；
    // 方块滑块直接在手指位置，只做 150ms 的缩放（0.4→1）和淡入淡出
    val frac = drag ?: (pos.toFloat() / dur).coerceIn(0f, 1f)
    val fill = animateFloatAsState(frac, tween(150, easing = androidx.compose.animation.core.LinearEasing), label = "fill")
    val thumbAt = frac
    val h = animateDpAsState(if (drag != null) 6.dp else 4.dp, tween(150, easing = androidx.compose.animation.core.LinearEasing), label = "h")
    val th = animateFloatAsState(if (drag != null) 1f else 0f, tween(150, easing = androidx.compose.animation.core.LinearEasing), label = "t")
    val ts = animateFloatAsState(if (drag != null) 1f else .4f, tween(150, easing = androidx.compose.animation.core.LinearEasing), label = "ts")
    val p = c
    Box(Modifier.fillMaxWidth().height(hit).then(if (!interactive) Modifier else Modifier.pointerInput(dur) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull() ?: continue
                val f = (ch.position.x / size.width).coerceIn(0f, 1f)
                if (ch.pressed) { drag = f; curScrub(f); ch.consume() }
                else if (drag != null) { Engine.seek((drag!! * dur).toLong()); drag = null; curScrub(null) }
            }
        }
    }).drawBehind {
        val y = size.height / 2; val hp = h.value.toPx()
        drawRect(p.l3, Offset(0f, y - hp / 2), Size(size.width, hp))
        // 已缓存的部分：比底色深一点
        if (UiPref.showBuffer && interactive) drawRect(p.k.copy(alpha = .22f), Offset(0f, y - hp / 2), Size(size.width * (buf.toFloat() / dur).coerceIn(0f, 1f), hp))
        drawRect(p.p, Offset(0f, y - hp / 2), Size(size.width * fill.value, hp))
        val thv = th.value
        if (thv > 0f) { val s = 12.dp.toPx() * ts.value; drawRect(p.p.copy(alpha = thv), Offset(size.width * thumbAt - s / 2, y - s / 2), Size(s, s)) }
    })
}

@Composable fun TimeRow(durMs: Long, scrub: Float? = null, size: Int = 12) {
    val sec by remember { Engine.position.map { it / 1000L }.distinctUntilChanged() }.collectAsState(Engine.position.value / 1000L)
    Row(Modifier.fillMaxWidth()) {
        T(fmt(if (scrub != null) (scrub * durMs).toLong() else sec * 1000L), size, c.p, mono = true, modifier = Modifier.weight(1f))
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

@Composable fun ActionRow(content: @Composable RowScope.() -> Unit) {
    if (Screen.wide) Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1f))
        CompositionLocalProvider(LocalCompactAction provides true) { Row(Modifier.width(IntrinsicSize.Max), content = content) }
    }
    else Row(Modifier.fillMaxWidth(), content = content)
}

/** 顶栏 / 侧边栏 / 播放条的底色：有自定义背景时半透明，让背景连成一片 */

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
    if (url.startsWith("http://") && !url.contains("music.126.net")) return img("https://" + url.removePrefix("http://"), px)
    val b = when { px <= 160 -> 160; px <= 256 -> 256; px <= 512 -> 512; else -> 768 }
    val base = url.substringBefore('?')
    // QQ 音乐的图：尺寸写在文件名里（T002R500x500M000…），只有 150 / 300 / 500 / 800 几档
    if (base.contains("kugou.com") && base.contains("/480/")) { val q = when { px <= 160 -> 150; px <= 256 -> 240; else -> 480 }; return base.replace("/480/", "/$q/") }
    if (base.contains("gtimg.cn") && base.contains("R500x500")) { val q = when { px <= 160 -> 150; px <= 300 -> 300; px <= 512 -> 500; else -> 800 }; return base.replace("R500x500", "R${q}x$q") }
    if (!base.contains("music.126.net")) return url   // QQ（qpic）/ 酷狗等其它站：参数是它们自己的，不能乱加
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
        enter = androidx.compose.animation.fadeIn(Motion.quick()) + androidx.compose.animation.scaleIn(Motion.quick(), if (MotionEffects.pageScale) .8f else 1f),
        exit = androidx.compose.animation.fadeOut(Motion.quick()) + androidx.compose.animation.scaleOut(Motion.quick(), if (MotionEffects.pageScale) .8f else 1f)) {
        Box(Modifier.size(44.dp).press(.92f, true) {
            scope.launch { ls.animateScrollToItem(target, -(ls.layoutInfo.viewportEndOffset / 3)) }
        }.background(c.sheet).border(if (Look.flat) 0.dp else 1.dp, c.k), contentAlignment = Alignment.Center) { NIcon(NIcons.locate, 20.dp, c.p) }
    }
}
