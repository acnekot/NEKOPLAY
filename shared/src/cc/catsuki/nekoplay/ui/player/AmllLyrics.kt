package cc.catsuki.nekoplay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AMLL（Apple Music-like Lyrics）风格 Jetpack Compose 歌词渲染器：
 * - 阶梯交错弹簧滚动（50ms 逐行级联弹簧，手动拖动/切窗时自动瞬移不抽动）
 * - 渐进景深模糊（按与当前句距离 0 / 0.9 / 1.8 / 2.8 / 4.0 dp 阶梯模糊，手动浏览时全清）
 * - 上下边缘柔和渐变羽化遮罩（BlendMode.DstIn）
 * - 逐字柔和渐变扫光 + 微微上浮动画（AMLL float 0.05em + glow）
 * - 呼吸感三点间奏动画
 */
@Composable internal fun AmllLyricsFlow(l: List<LyricLine>, cur: Int, showTr: Boolean, offset: Long, onBlank: (() -> Unit)? = null) {
    val p = c
    val copy = rememberCopy()
    val tops = remember(l) { FloatArray(l.size) { -1f } }
    var measured by remember(l) { mutableStateOf(0) }
    val offs = remember(l) { List(l.size) { Animatable(0f) } }
    var settled by remember(l) { mutableStateOf(false) }
    var lastCur by remember(l) { mutableIntStateOf(-1) }
    var ready by remember(l) { mutableStateOf(false) }
    val gap = with(LocalDensity.current) { LyricPref.gap.dp.toPx() }
    var vh by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density
    val centered = LyricPref.align == 1

    LaunchedEffect(cur, measured, vh, LyricPref.anchor, LyricPref.staggerMs, LyricPref.gap) {
        if (measured < l.size) return@LaunchedEffect
        val top = tops.getOrElse(cur) { 0f }
        val lineH = (tops.getOrNull(cur + 1)?.let { it - top - gap } ?: (LyricPref.size * 1.6f * density)).coerceAtLeast(10f)
        val target = when (LyricPref.anchor) {
            0 -> -top
            2 -> vh * .30f - top
            3 -> vh * .5f - lineH / 2 - top
            else -> -(if (cur > 0) tops[cur - 1] else 0f)
        }
        val first = (cur - 2).coerceAtLeast(0)
        val jump = !settled || cur == lastCur
        settled = true; lastCur = cur
        if (jump) { l.indices.forEach { offs[it].snapTo(target) }; ready = true; return@LaunchedEffect }
        val stag = LyricPref.staggerMs.coerceAtLeast(10).toLong()
        l.indices.forEach { i ->
            if (i < first || i > cur + 10) launch { offs[i].snapTo(target) }
            else launch {
                delay(((i - first) * stag / Anim.f).toLong())
                offs[i].animateTo(target, spring(0.82f, Anim.stiff(125f)))
            }
        }
    }

    val scope = rememberCoroutineScope()
    val manual = remember(l) { Animatable(0f) }
    var browsing by remember(l) { mutableStateOf(false) }
    var backJob by remember { mutableStateOf<Job?>(null) }
    fun bounds() {
        val s0 = offs.getOrNull(cur)?.targetValue ?: 0f
        val maxTop = tops.maxOrNull() ?: 0f
        manual.updateBounds(-maxTop - s0, -s0)
    }
    fun scheduleBack() {
        backJob?.cancel()
        backJob = scope.launch {
            delay(LyricPref.idleBackMs.toLong())
            browsing = false
            manual.animateTo(0f, spring(0.88f, Anim.stiff(Spring.StiffnessLow)))
        }
    }
    val drag = rememberDraggableState { dy -> scope.launch { manual.snapTo(manual.value + dy) } }
    val fadeMask = remember {
        Brush.verticalGradient(0f to Color.Transparent, 0.09f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent)
    }

    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { vh = it.height.toFloat() }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(brush = fadeMask, blendMode = BlendMode.DstIn)
        }
        .then(if (onBlank == null) Modifier else Modifier.pointerInput(onBlank) { detectTapGestures { onBlank() } })
        .pointerInput(l, cur) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type == PointerEventType.Scroll) {
                        val dy = ev.changes.fold(0f) { acc, ch -> acc + ch.scrollDelta.y }
                        if (dy != 0f) {
                            ev.changes.forEach { it.consume() }
                            backJob?.cancel(); browsing = true; bounds()
                            scope.launch { manual.snapTo(manual.value - dy * 48f * density) }
                            scheduleBack()
                        }
                    }
                }
            }
        }
        .draggable(drag, Orientation.Vertical,
            onDragStarted = { backJob?.cancel(); browsing = true; bounds(); manual.stop() },
            onDragStopped = { v -> scope.launch { manual.animateDecay(v, exponentialDecay(.6f)) }; scheduleBack() })) {
        Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).padding(start = 24.dp, end = 28.dp, top = 16.dp)) {
            l.forEachIndexed { i, line ->
                val dist = kotlin.math.abs(i - cur)
                val active = i == cur && !browsing
                val targetAlpha = when {
                    browsing -> if (dist == 0) 1f else .76f
                    dist == 0 -> 1f
                    dist == 1 -> .56f
                    dist == 2 -> .36f
                    dist == 3 -> .24f
                    else -> .16f
                }
                val targetScale = when {
                    browsing -> 1f
                    dist == 0 -> LyricPref.activeScale.coerceIn(1f, 1.12f)
                    dist == 1 -> .96f
                    dist == 2 -> .93f
                    else -> .91f
                }
                val targetBlur = when {
                    !UiPref.lyricBlur || !Plat.impl.liveBlur || browsing || dist == 0 -> 0.dp
                    dist == 1 -> 0.9.dp
                    dist == 2 -> 1.8.dp
                    dist == 3 -> 2.8.dp
                    else -> 4.0.dp
                }
                val alpha by animateFloatAsState(targetAlpha, spring(1f, Anim.stiff(Spring.StiffnessLow)), label = "aa")
                val scale by animateFloatAsState(targetScale, spring(0.84f, Anim.stiff(Spring.StiffnessLow)), label = "as")
                val blurDp by animateDpAsState(targetBlur, tween(280), label = "ab")

                Box(Modifier.fillMaxWidth()
                    .onPlaced { val y = it.positionInParent().y; if (tops[i] != y) { tops[i] = y; measured++ } }
                    .graphicsLayer {
                        translationY = offs[i].value + manual.value
                        this.alpha = if (ready) alpha else 0f
                    }
                    .then(if (blurDp > 0.1.dp) Modifier.blur(blurDp) else Modifier)
                    .padding(bottom = with(LocalDensity.current) { gap.toDp() }),
                    contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
                    Box(Modifier
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            transformOrigin = TransformOrigin(if (centered) .5f else 0f, .5f)
                        }
                        .press(onLong = { if (line.text.isNotEmpty()) copy(if (showTr && line.tr != null) line.text + "\n" + line.tr else line.text, "歌词") }) {
                            Engine.seek((line.t - offset).coerceAtLeast(0))
                            backJob?.cancel(); browsing = false
                            scope.launch { manual.animateTo(0f, spring(0.88f, Anim.stiff(Spring.StiffnessMediumLow))) }
                        }) {
                        if (line.text.isEmpty()) {
                            AmllInterludeDots(active, line.t, l.getOrNull(i + 1)?.t ?: (line.t + 5000), offset)
                        } else Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
                            if (UiPref.karaoke && active && line.words != null) {
                                AmllWordLine(line, offset, centered)
                            } else {
                                val st = TextStyle(
                                    fontSize = LyricPref.size.sp,
                                    fontWeight = FontWeight(800),
                                    fontFamily = Typo.family,
                                    lineHeight = (LyricPref.size * 1.28f).sp,
                                    color = p.k,
                                    shadow = if (active) Shadow(p.k.copy(alpha = .28f), Offset.Zero, 14f) else null,
                                )
                                BasicText(line.text, style = st, maxLines = 6)
                            }
                            if (showTr && line.tr != null) {
                                T(line.tr, (LyricPref.size * .56f).coerceIn(12f, 18f), p.s, 600, lines = 4, lh = 1.45f, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(420.dp))
        }
    }
}

/** AMLL 逐字柔和扫光 + 轻微上浮（FlowRow 自动换行，单字按进度柔和亮起并上浮 2dp） */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AmllWordLine(line: LyricLine, offset: Long, centered: Boolean) {
    val p = c
    val words = line.words ?: return
    val raw by Engine.position.collectAsState()
    val pos = raw + offset
    val playing = Engine.state.collectAsState().value.playing && LocalLyricsActive.current
    var now by remember { mutableLongStateOf(pos) }
    LaunchedEffect(pos, playing) {
        val base = pos; now = base
        if (playing) {
            val start = withFrameMillis { it }
            while (true) {
                withFrameMillis { f -> now = base + (f - start) }
                if (UiPref.lowMem) delay(40)
            }
        }
    }
    val baseSt = TextStyle(fontSize = LyricPref.size.sp, fontWeight = FontWeight(800), fontFamily = Typo.family, lineHeight = (LyricPref.size * 1.28f).sp)
    val liftPx = with(LocalDensity.current) { (LyricPref.size * 0.075f).dp.toPx() }
    FlowRow(horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start) {
        words.forEach { w ->
            val dur = w.d.coerceAtLeast(120L).toFloat()
            val prog = ((now - w.t).toFloat() / dur).coerceIn(0f, 1f)
            val lift = -liftPx * FastOutSlowInEasing.transform(prog)
            val glow = if (prog > 0.05f) Shadow(p.k.copy(alpha = 0.28f * prog), Offset.Zero, 14f * prog) else null
            Box(Modifier.graphicsLayer { translationY = lift }) {
                BasicText(w.s, style = baseSt.copy(color = p.k.copy(alpha = 0.30f)), maxLines = 1)
                if (prog > 0f) {
                    BasicText(w.s, style = baseSt.copy(color = p.k, shadow = glow), maxLines = 1,
                        modifier = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                            drawContent()
                            if (prog < 0.999f) {
                                val edge = (size.width * 0.35f).coerceAtLeast(8f)
                                val head = (size.width + edge) * prog
                                val startX = (head - edge).coerceAtLeast(0f)
                                val brush = Brush.horizontalGradient(
                                    0f to Color.Black,
                                    (startX / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f) to Color.Black,
                                    (head / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f) to Color.Transparent,
                                )
                                drawRect(brush = brush, blendMode = BlendMode.DstIn)
                            }
                        })
                }
            }
        }
    }
}

/** AMLL 呼吸三点间奏 */
@Composable private fun AmllInterludeDots(active: Boolean, start: Long, next: Long, offset: Long) {
    val p = c
    val prog = if (active) {
        val raw by Engine.position.collectAsState()
        ((raw + offset - start).toFloat() / (next - start).coerceAtLeast(300)).coerceIn(0f, 1f)
    } else 0f
    val pulse by rememberInfiniteTransition(label = "amllDot").animateFloat(
        initialValue = 0.88f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p",
    )
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { k ->
            val seg = ((prog * 3f) - k).coerceIn(0f, 1f)
            val s = if (active) (0.82f + 0.28f * seg) * (if (seg in 0.01f..0.99f) pulse else 1f) else 0.78f
            val a = if (active) 0.28f + 0.72f * seg else 0.24f
            Box(Modifier.size(10.dp).graphicsLayer { scaleX = s; scaleY = s; alpha = a }.background(p.k, CircleShape))
        }
    }
}
