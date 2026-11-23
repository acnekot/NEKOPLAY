package cc.catsuki.nekoplay

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

import androidx.compose.foundation.clickable
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.withFrameMillis

// ======================= 迷你播放条 =======================
object LyricCache {
    // 最近用过的 30 首：读一次就挪到末尾，超出时丢掉最前面的（各平台通用写法）
    private val map = LinkedHashMap<String, List<LyricLine>>()
    private fun put(id: String, l: List<LyricLine>) = synchronized(this) {
        map.remove(id); map[id] = l
        while (map.size > 30) map.remove(map.keys.first())
    }
    fun peek(id: String): List<LyricLine>? = synchronized(this) { map.remove(id)?.also { map[id] = it } }
    suspend fun get(id: String): List<LyricLine> = peek(id) ?: run {
        // 本地音乐：读同名 .lrc
        if (id.startsWith("local:")) {
            val src = LocalMusic.byId(id)?.src
            val l = runCatching { val f = java.io.File(java.net.URI(src)); java.io.File(f.parentFile, f.nameWithoutExtension + ".lrc").takeIf { it.exists() }?.readText() }.getOrNull()
                ?.let { Lrc.parse(it, null) } ?: emptyList()
            put(id, l); return@run l
        }
        if (id.startsWith("dav:")) { val l = DavMusic.lyric(id)?.let { Lrc.parse(it, null) } ?: emptyList(); put(id, l); return@run l }
        val d = Ncm.lyricFull(id)
        // 有逐字歌词就用逐字的，没有退回普通 LRC
        val l = d.yrc?.let { Lrc.parseYrc(it, d.tr) }?.takeIf { it.isNotEmpty() } ?: Lrc.parse(d.lrc, d.tr)
        put(id, l); l
    }
}

/** 歌词是否看得见（封面页时歌词透明挂在后面） */
val LocalLyricsActive = compositionLocalOf { true }

@Composable fun Lyrics(t: Track, showTr: Boolean, active: Boolean = true, onBlank: (() -> Unit)? = null) =
    CompositionLocalProvider(LocalLyricsActive provides active) { LyricsBody(t, showTr, active, onBlank) }

@Composable private fun LyricsBody(t: Track, showTr: Boolean, active: Boolean, onBlank: (() -> Unit)?) {
    // 按歌重建状态：缓存里有就直接显示，没有才去拉
    var lines by remember(t.id) { mutableStateOf(LyricCache.peek(t.id)) }
    LaunchedEffect(t.id) { if (lines == null) lines = try { LyricCache.get(t.id) } catch (e: Exception) { emptyList() } }
    // 看得见时进度刷新加快（逐字高亮更顺），藏在封面后面时不加快
    DisposableEffect(active) { if (active) Engine.fastPos++; onDispose { if (active) Engine.fastPos-- } }
    val l = lines
    val p = c
    if (l == null || l.isEmpty()) {
        Box(Modifier.fillMaxSize().then(if (onBlank == null) Modifier else Modifier.pointerInput(onBlank) { detectTapGestures { onBlank() } }), contentAlignment = Alignment.Center) { T(if (l == null) "LOADING" else "NO LYRICS", 12, p.s, mono = true, ls = .08f) }
        return
    }
    val offset = LyricOffset.of(t.id)
    // 只在当前句索引变化时才重组整份歌词列表，避免每 50ms 重组上百行
    val cur by remember(l, offset) {
        Engine.position.map { Lrc.indexAt(l, it + offset) }.distinctUntilChanged()
    }.collectAsState(Lrc.indexAt(l, Engine.position.value + offset))
    LyricsFlow(l, cur, showTr, offset, onBlank)
}

/**
 * 歌词滚动渲染器：支持多种滚动物理与排版样式（可在 设置 → 歌词 切换或 JSON 导入导出）：
 * - 大字流动：逐行错开弹簧波浪滚动
 * - Hydrogen 顺滑（参考 Hydrogen-Music）：580ms cubic-bezier(0.4, 0, 0.12, 1) 整体平滑跟进 + 1s 松手回弹 + 左侧高亮指示条
 * - 居中经典：居中排版 + 柔和缓动滚动
 */
private val HydrogenEase = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 0.12f, 1f)

@Composable private fun LyricsFlow(l: List<LyricLine>, cur: Int, showTr: Boolean, offset: Long, onBlank: (() -> Unit)? = null) {
    val p = c
    val copy = rememberCopy()
    val tops = remember(l) { FloatArray(l.size) { -1f } }
    var measured by remember(l) { mutableStateOf(0) }
    val offs = remember(l) { List(l.size) { Animatable(0f) } }
    var settled by remember(l) { mutableStateOf(false) }
    var lastCur by remember(l) { mutableIntStateOf(-1) }
    var ready by remember(l) { mutableStateOf(false) } // 第一次排好位置之前先不显示，避免从顶部闪一下
    val gap = with(LocalDensity.current) { LyricPref.gap.dp.toPx() }
    var vh by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density
    val centered = LyricPref.align == 1
    // 当前句的停靠位置按设置来：顶部 / 第二行（保留上一句）/ 偏上 / 居中
    LaunchedEffect(cur, measured, vh, LyricPref.anchor, LyricPref.style, LyricPref.scrollMs, LyricPref.staggerMs, LyricPref.gap) {
        if (measured < l.size) return@LaunchedEffect
        val top = tops.getOrElse(cur) { 0f }
        val lineH = (tops.getOrNull(cur + 1)?.let { it - top - gap } ?: (LyricPref.size * 1.6f * density)).coerceAtLeast(10f)
        val target = when (LyricPref.anchor) {
            0 -> -top
            2 -> vh * .28f - top
            3 -> vh * .5f - lineH / 2 - top
            else -> -(if (cur > 0) tops[cur - 1] else 0f)
        }
        val first = (cur - 2).coerceAtLeast(0)
        // 刚打开歌词页或仅排版变化：直接到位，不抽动
        val jump = !settled || cur == lastCur
        settled = true; lastCur = cur
        if (jump) { l.indices.forEach { offs[it].snapTo(target) }; ready = true; return@LaunchedEffect }
        val stag = LyricPref.staggerMs
        val dur = (LyricPref.scrollMs / Anim.f).toInt().coerceIn(120, 1500)
        if (stag <= 0) {
            // Hydrogen 顺滑 / 居中经典：整体用 cubic-bezier(0.4, 0, 0.12, 1) 同步滑入，无错行抖动
            l.indices.forEach { i ->
                if (i < first - 2 || i > cur + 12) launch { offs[i].snapTo(target) }
                else launch { offs[i].animateTo(target, tween(dur, easing = HydrogenEase)) }
            }
        } else {
            l.indices.forEach { i ->
                if (i < first || i > cur + 10) launch { offs[i].snapTo(target) }
                else launch {
                    kotlinx.coroutines.delay(((i - first) * stag.toLong() / Anim.f).toLong())
                    offs[i].animateTo(target, spring(.86f, Anim.stiff(140f)))
                }
            }
        }
    }
    // 手动拖动浏览：拖动时暂停自动滚动、所有行变清晰；松手带惯性，停 idleBackMs 后回到当前句
    val scope = rememberCoroutineScope()
    val manual = remember(l) { Animatable(0f) }
    var browsing by remember(l) { mutableStateOf(false) }
    var backJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun bounds() {
        val s0 = offs.getOrNull(cur)?.targetValue ?: 0f
        val maxTop = tops.maxOrNull() ?: 0f
        manual.updateBounds(-maxTop - s0, -s0)
    }
    fun scheduleBack() {
        backJob?.cancel()
        backJob = scope.launch {
            kotlinx.coroutines.delay(LyricPref.idleBackMs.toLong())
            browsing = false
            manual.animateTo(0f, if (LyricPref.staggerMs <= 0) tween((LyricPref.scrollMs / Anim.f).toInt().coerceIn(160, 1000), easing = HydrogenEase) else spring(.9f, Anim.stiff(Spring.StiffnessLow)))
        }
    }
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { dy -> scope.launch { manual.snapTo(manual.value + dy) } }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { vh = it.height.toFloat() }
        .then(if (onBlank == null) Modifier else Modifier.pointerInput(onBlank) { detectTapGestures { onBlank() } })
        .pointerInput(l, cur) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type == androidx.compose.ui.input.pointer.PointerEventType.Scroll) {
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
        .draggable(drag, androidx.compose.foundation.gestures.Orientation.Vertical,
            onDragStarted = { backJob?.cancel(); browsing = true; bounds(); manual.stop() },
            onDragStopped = { v -> scope.launch { manual.animateDecay(v, androidx.compose.animation.core.exponentialDecay(.6f)) }; scheduleBack() })) {
        Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).padding(start = 22.dp, end = 30.dp, top = 8.dp)) {
            val sMax = LyricPref.activeScale.coerceIn(1f, 1.35f)
            l.forEachIndexed { i, line ->
                val dist = kotlin.math.abs(i - cur)
                val targetAlpha = if (browsing) (if (dist == 0) 1f else .7f) else if (LyricPref.style == 1) when (dist) { 0 -> 1f; 1 -> .42f; 2 -> .28f; else -> .18f } else when (dist) { 0 -> 1f; 1 -> .5f; 2 -> .36f; else -> .26f }
                val a = animateFloatAsState(targetAlpha, tween(260, easing = HydrogenEase), label = "la")
                Box(Modifier.fillMaxWidth()
                    .onPlaced { val y = it.positionInParent().y; if (tops[i] != y) { tops[i] = y; measured++ } }
                    .graphicsLayer { translationY = offs[i].value + manual.value; alpha = if (ready) a.value else 0f }
                    .then(if (UiPref.lyricBlur && !browsing && dist in 2..5 && Plat.impl.liveBlur) Modifier.blur(1.2.dp) else Modifier)
                    .padding(bottom = with(LocalDensity.current) { gap.toDp() }),
                    contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
                  val big = animateFloatAsState(if (i == cur && !browsing) sMax else 1f, if (LyricPref.staggerMs <= 0) tween(340, easing = HydrogenEase) else spring(.8f, Anim.stiff(Spring.StiffnessLow)), label = "lz")
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    if (LyricPref.accentBar && !centered) {
                        val barA by animateFloatAsState(if (i == cur && !browsing && line.text.isNotEmpty()) 1f else 0f, tween(220), label = "bar")
                        Box(Modifier.width(3.dp).height((LyricPref.size * 1.15f).dp).graphicsLayer { alpha = barA }.background(p.p))
                        Spacer(Modifier.width(10.dp))
                    }
                    Box(Modifier.layout { m, cs -> val pl = m.measure(cs.copy(minWidth = 0, maxWidth = (cs.maxWidth / sMax).toInt().coerceAtLeast(1))); layout(pl.width, pl.height) { pl.place(0, 0) } }
                      .graphicsLayer { scaleX = big.value; scaleY = big.value; transformOrigin = TransformOrigin(if (centered) .5f else 0f, .5f) }
                      .press(onLong = { if (line.text.isNotEmpty()) copy(if (showTr && line.tr != null) line.text + "\n" + line.tr else line.text, "歌词") }) { Engine.seek((line.t - offset).coerceAtLeast(0)); backJob?.cancel(); browsing = false; scope.launch { manual.animateTo(0f, tween(320, easing = HydrogenEase)) } }) {
                      if (line.text.isEmpty()) {
                          InterludeDots(i == cur, line.t, l.getOrNull(i + 1)?.t ?: (line.t + 5000), offset)
                      } else Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
                          if (UiPref.karaoke && i == cur && line.words != null && !browsing) KaraokeLine(line, offset) else T(line.text, LyricPref.size.toFloat(), p.k, 800, lines = 6, lh = 1.25f)
                          if (showTr && line.tr != null) T(line.tr, Fs.SUB, p.s, lines = 4, lh = 1.45f, modifier = Modifier.padding(top = 4.dp))
                      }
                    }
                  }
                }
            }
            Spacer(Modifier.height(400.dp))
        }
    }
}

// ======================= 播放队列 =======================

@Composable private fun InterludeDots(active: Boolean, start: Long, next: Long, offset: Long) {
    val p = c
    val prog = if (active) {
        val raw by Engine.position.collectAsState()
        ((raw + offset - start).toFloat() / (next - start).coerceAtLeast(300)).coerceIn(0f, 1f)
    } else 0f
    Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { k -> Box(Modifier.size(8.dp).background(if (active && prog > k / 3f) p.k else p.k.copy(alpha = .3f), androidx.compose.foundation.shape.CircleShape)) }
    }
}

/** 逐字高亮：底下一层半透明文字，上面一层实色文字按字的进度裁出来；进度用帧时钟在两次位置更新之间插值，扫过去是连续的 */
@Composable private fun KaraokeLine(line: LyricLine, offset: Long) {
    val p = c
    val words = line.words ?: return
    val raw by Engine.position.collectAsState()
    val pos = raw + offset
    // 歌词藏在封面后面时不逐帧推进（老设备上这是播放页卡顿的主要来源）
    val playing = Engine.state.collectAsState().value.playing && LocalLyricsActive.current
    var now by remember { mutableLongStateOf(pos) }
    LaunchedEffect(pos, playing) {
        val base = pos; now = base
        if (playing) { val start = withFrameMillis { it }; while (true) { withFrameMillis { f -> now = base + (f - start) }; if (UiPref.lowMem) kotlinx.coroutines.delay(40) } }
    }
    var lay by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    // 复用 Path，避免 120Hz 下每秒分配 120 个 Native Skia Path 对象
    val path = remember { androidx.compose.ui.graphics.Path() }
    val st = androidx.compose.ui.text.TextStyle(fontSize = LyricPref.size.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(800), fontFamily = Typo.family, lineHeight = (LyricPref.size * 1.25f).sp)
    Box {
        androidx.compose.foundation.text.BasicText(line.text, style = st.copy(color = p.k.copy(alpha = .32f)), maxLines = 6, onTextLayout = { lay = it })
        androidx.compose.foundation.text.BasicText(line.text, style = st.copy(color = p.k), maxLines = 6, modifier = Modifier.drawWithContent {
            val l = lay ?: return@drawWithContent
            val n = l.layoutInput.text.length
            path.reset()
            var ci = 0
            for (w in words) {
                val len = w.s.length
                val f = ((now - w.t).toFloat() / w.d.coerceAtLeast(1)).coerceIn(0f, 1f)
                if (f > 0f) for (k in 0 until len) {
                    val idx = ci + k; if (idx >= n) break
                    val cf = (f * len - k).coerceIn(0f, 1f); if (cf <= 0f) break
                    val b = l.getBoundingBox(idx); val ln = l.getLineForOffset(idx)
                    path.addRect(androidx.compose.ui.geometry.Rect(b.left, l.getLineTop(ln), b.left + b.width * cf, l.getLineBottom(ln)))
                }
                ci += len
            }
            clipPath(path) { this@drawWithContent.drawContent() }
        })
    }
}

// ======================= 评论 =======================
/** 评论列表保持原样；写评论 / 回复用底部的聊天式输入栏（跟着键盘上移，点评论内容就是回复那条） */
