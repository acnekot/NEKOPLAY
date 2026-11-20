package cc.catsuki.nekoplay

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
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
@Composable fun MiniBar() {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    val t = st.current ?: return
    val p = c
    Box(Modifier.fillMaxWidth().height(60.dp)) {
        Row(Modifier.fillMaxSize().background(barBg()).drawBehind { if (!Look.flat) drawRect(p.l2, size = Size(size.width, 1.dp.toPx())) }
            .press { nav.lyrics = false; nav.player = true }.padding(start = 14.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(t.cover, 40.dp, t.title)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { T(t.title, 15, w = 700, lh = 1.3f); T(t.artist, 12, p.s, lh = 1.3f) }
            Box(Modifier.size(48.dp).press(haptic = true) { Engine.toggle() }, contentAlignment = Alignment.Center) { NIcon(if (st.playing) NIcons.pause else NIcons.play, 22.dp) }
            Box(Modifier.size(44.dp, 48.dp).press(haptic = true) { Engine.next() }, contentAlignment = Alignment.Center) { NIcon(NIcons.next, 20.dp) }
        }
        var scrub by remember { mutableStateOf<Float?>(null) }
        Box(Modifier.offset(y = (-7).dp)) { ScrubBar(t.durationMs) { scrub = it } }
        // 拖动时，时间气泡跟着手指
        scrub?.let { f ->
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val w = maxWidth
                val x = (w * f - 26.dp).coerceIn(4.dp, w - 56.dp)
                Box(Modifier.offset(x = x, y = (-38).dp).size(52.dp, 26.dp).background(p.k), contentAlignment = Alignment.Center) {
                    T(fmt((f * t.durationMs).toLong()), 12, p.tagFg, mono = true)
                }
            }
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

// ======================= 播放页（封面 ⇄ 歌词 共用一个舞台） =======================
@Composable fun PlayerOverlay() {
    val nav = LocalNav.current
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val copy = rememberCopy()
    AnimatedVisibility(nav.player, enter = slideInVertically(sp) { it }, exit = slideOutVertically(sp) { it }) {
        LaunchedEffect(Unit) { drag.snapTo(0f) }
        val st by Engine.state.collectAsState()
        val t = st.current
        val lt by animateFloatAsState(if (nav.lyrics) 1f else 0f, spF, label = "ly")
        LaunchedEffect(t?.id) { t?.let { runCatching { LyricCache.get(it.id) } } }
        var showTr by remember { mutableStateOf(true) }
        val p = c
        // 播放页整块吃掉点击：空白处（比如没有歌词时）不会点穿到后面的歌单
        BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { translationY = drag.value }.background(p.page).pointerInput(Unit) { detectTapGestures { } }) {
            BgLayer()
            val h = constraints.maxHeight.toFloat()
            val wideP = maxWidth >= 900.dp
            Column(Modifier.fillMaxSize()) {
                // 顶栏：按住下拉可以跟手收起
                if (!wideP) Row(Modifier.fillMaxWidth().height(48.dp).pointerInput(Unit) {
                    detectVerticalDragGestures(onDragEnd = {
                        scope.launch { if (drag.value > h * .18f) nav.player = false else drag.animateTo(0f, spring(1f, Anim.stiff(Spring.StiffnessMediumLow))) }
                    }) { _, dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } }
                }.drawBehind { if (!Look.flat) drawRect(p.l2, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).press { nav.player = false }, contentAlignment = Alignment.Center) { NIcon(NIcons.chevronDown, 20.dp) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { T(if (lt > .5f && !wideP) "LYRICS" else if (st.fm) "PERSONAL FM" else "NOW PLAYING", 12, p.k.copy(alpha = .5f), 700, mono = true, ls = .14f) }
                    // 顶栏右侧槽位（默认放「译」，只在歌词页显示）
                    if (t != null) { val x = BtnCtx(t, st, showTr) { showTr = !showTr }
                        PlayerLayout.keys(false, "top").forEach { k -> Box(if (k == "tr") Modifier.graphicsLayer { alpha = lt } else Modifier) { PBtn(k, x) } } }
                    else Spacer(Modifier.width(44.dp))
                }
                if (t == null) { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { T("NO TRACK", 12, p.s, mono = true, ls = .08f) }; return@Column }
                // 宽窗口（PC / 平板横屏）：左边封面 + 信息 + 控制，右边歌词
                if (wideP) { Box(Modifier.weight(1f).fillMaxWidth()) { WidePlayerBody(t, st, showTr) { showTr = !showTr } }; return@Column }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    val d = LocalDensity.current
                    val w = with(d) { maxWidth.toPx() }
                    val bottomPad = with(d) { WindowInsets.navigationBars.getBottom(d).toFloat() }
                    val ctrlH = with(d) { 156.dp.toPx() } + bottomPad
                    val free = with(d) { maxHeight.toPx() } - ctrlH
                    val dp = with(d) { 1.dp.toPx() }
                    val coverNow = (free - 150 * dp).coerceIn(120 * dp, w - 56 * dp) // 下面要留给歌名、歌手、音质三行
                    val nowTop = ((free - coverNow - 84 * dp) / 2).coerceIn(8 * dp, 40 * dp)
                    val cs = lerp(coverNow, 48 * dp, lt)
                    val cl = lerp((w - coverNow) / 2, 18 * dp, lt)
                    val ct = lerp(nowTop, 8 * dp, lt)
                    val coverDp = with(d) { coverNow.toDp() }
                    // 歌词列
                    // 歌词一直挂着（封面页时透明、不接收点击）：切到歌词页时已经排好版、停在当前句，不会先闪一下再跳
                    Box(Modifier.fillMaxWidth().height(with(d) { (free - 68 * dp).coerceAtLeast(0f).toDp() }).offset(y = 68.dp)
                        .graphicsLayer { alpha = ((lt - .7f) / .3f).coerceIn(0f, 1f).let { it * it } }
                        .then(if (nav.lyrics) Modifier else Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() } } })) {
                        Lyrics(t, showTr, active = nav.lyrics) { nav.lyrics = false }
                    }
                    // 封面：始终按大尺寸解码，只做位移和缩放
                    Box(Modifier.graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f); translationX = cl; translationY = ct; scaleX = cs / coverNow; scaleY = cs / coverNow
                    }.pointerInput(nav.lyrics) {
                        // 封面视图下往下拖封面 = 点 ⌄ 收起；歌词模式不拦，留给歌词滚动
                        if (!nav.lyrics) detectVerticalDragGestures(onDragEnd = {
                            scope.launch { if (drag.value > h * .18f) nav.player = false else drag.animateTo(0f, spring(1f, Anim.stiff(Spring.StiffnessMediumLow))) }
                        }, onDragCancel = {
                            scope.launch { drag.animateTo(0f, spring(1f, Anim.stiff(Spring.StiffnessMediumLow))) }
                        }) { ch, dy -> ch.consume(); scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } }
                    }.press(.97f) { nav.lyrics = !nav.lyrics }) { Cover(t.cover, coverDp, t.title) }
                    // 标题：两套各在自己的位置淡入淡出
                    // 信息块：和封面左边对齐，宽度等于封面；爱心在歌名同一行最右，对齐封面右边缘
                    if (lt < .45f) Column(Modifier.width(coverDp).graphicsLayer {
                        translationX = (w - coverNow) / 2; translationY = nowTop + coverNow + 18 * dp - 12 * dp * lt; alpha = (1 - lt / .45f).coerceIn(0f, 1f)
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T(t.title, 24, w = 800, lh = 1.25f, lines = 2, modifier = Modifier.weight(1f).press(onLong = { copy(t.title, "歌名") }) {})
                            Row(Modifier.offset(x = 10.dp)) { SlotRow(false, "title", BtnCtx(t, st, showTr) { showTr = !showTr }, 0.dp) }
                        }
                        T(t.artist, 15, p.s, lh = 1.3f, lines = 2, modifier = Modifier.press(onLong = { copy(t.artist, "歌手") }) { openArtists(nav, t) })
                        // 点音质这一行可以换音质
                        val ainfo by Engine.info.collectAsState()
                        T(if (st.format.isEmpty()) Ncm.LEVEL_LABELS[Engine.quality.coerceIn(0, 4)] + " ▾"
                          else (ainfo?.let { if (it.kbps == 0 && st.kbps > 0) it.copy(kbps = st.kbps) else it }?.line() ?: "${st.format.uppercase()}  ${if (st.format == "flac") "无损" else "有损"}${if (st.kbps > 0) " · ${st.kbps} kbps" else ""}") + "${if (st.cached) " · 缓存" else ""} ▾",
                            11, p.s, mono = true, ls = .06f, modifier = Modifier.padding(top = 6.dp).press { qualitySheet(nav) })
                    }
                    if (lt > .75f) Column(Modifier.width(with(d) { (w - cl - cs - 30 * dp).coerceAtLeast(40 * dp).toDp() }).graphicsLayer {
                        translationX = cl + cs + 12 * dp; translationY = ct + (cs - 38 * dp) / 2; alpha = ((lt - .75f) / .25f).coerceIn(0f, 1f)
                    }) { T(t.title, 16, w = 800, lh = 1.25f, lines = 2, modifier = Modifier.press(onLong = { copy(t.title, "歌名") }) {}); T(t.artist, 13, p.s, lh = 1.3f, modifier = Modifier.press(onLong = { copy(t.artist, "歌手") }) { openArtists(nav, t) }) }
                    // 控制组（两页位置完全一致）
                    val bx = BtnCtx(t, st, showTr) { showTr = !showTr }
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(d) { ctrlH.toDp() })) {
                        Controls(t, st) { SlotRow(false, "left", bx); Spacer(Modifier.weight(1f)); SlotRow(false, "right", bx) }
                    }
                }
            }
        }
    }
}

@Composable private fun Controls(t: Track, st: PlayState, row: (@Composable RowScope.() -> Unit)? = null) {
    val nav = LocalNav.current
    val liked by Store.likedIds.collectAsState()
    var scrub by remember { mutableStateOf<Float?>(null) }
    val on = t.id in liked
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).navigationBarsPadding()) {
        ScrubBar(t.durationMs) { scrub = it }
        Spacer(Modifier.height(2.dp)); TimeRow(t.durationMs, scrub)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (st.fm) SquareBtn(NIcons.trash) { Engine.fmTrash(); nav.toast = "不再推荐这首" } else SquareBtn(NIcons.prev) { Engine.prev() }
            Spacer(Modifier.width(26.dp))
            Box(Modifier.press(.96f, true) { Engine.toggle() }.size(48.dp).background(c.p), contentAlignment = Alignment.Center) {
                if (st.loading && st.want && !st.playing) LoadingDots() else NIcon(if (st.playing) NIcons.pause else NIcons.play, 22.dp, Color.White)
            }
            Spacer(Modifier.width(26.dp))
            SquareBtn(NIcons.next) { Engine.next() }
        }
        if (row != null) { Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, content = row) }
    }
}

/** 缓冲超过 0.4 秒才显示加载点 */
@Composable private fun LoadingDots() {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(400); show = true }
    if (!show) NIcon(NIcons.play, 22.dp, Color.White)
    else {
        val a by androidx.compose.animation.core.rememberInfiniteTransition(label = "ld").animateFloat(.3f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(500), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(3) { Box(Modifier.size(5.dp).graphicsLayer { alpha = if (it == 1) 1.3f - a else a }.background(Color.White)) } }
    }
}

// ======================= 歌词 =======================
/** 解析好的歌词放内存里：切到歌词页、开关评论 / 队列时不用重新拉，也就不会闪 LOADING */
object LyricCache {
    private val map = object : LinkedHashMap<String, List<LyricLine>>(16, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<LyricLine>>?) = size > 30
    }
    @Synchronized fun peek(id: String): List<LyricLine>? = map[id]
    suspend fun get(id: String): List<LyricLine> = peek(id) ?: run {
        // 本地音乐：读同名 .lrc
        if (id.startsWith("local:")) {
            val src = LocalMusic.byId(id)?.src
            val l = runCatching { val f = java.io.File(java.net.URI(src)); java.io.File(f.parentFile, f.nameWithoutExtension + ".lrc").takeIf { it.exists() }?.readText() }.getOrNull()
                ?.let { Lrc.parse(it, null) } ?: emptyList()
            synchronized(this) { map[id] = l }; return@run l
        }
        val d = Ncm.lyricFull(id)
        // 有逐字歌词就用逐字的，没有退回普通 LRC
        val l = d.yrc?.let { Lrc.parseYrc(it, d.tr) }?.takeIf { it.isNotEmpty() } ?: Lrc.parse(d.lrc, d.tr)
        synchronized(this) { map[id] = l }; l
    }
}

@Composable fun Lyrics(t: Track, showTr: Boolean, active: Boolean = true, onBlank: (() -> Unit)? = null) {
    // 按歌重建状态：缓存里有就直接显示，没有才去拉
    var lines by remember(t.id) { mutableStateOf(LyricCache.peek(t.id)) }
    LaunchedEffect(t.id) { if (lines == null) lines = try { LyricCache.get(t.id) } catch (e: Exception) { emptyList() } }
    // 看得见时进度刷新加快（逐字高亮更顺），藏在封面后面时不加快
    DisposableEffect(active) { if (active) Engine.fastPos++; onDispose { if (active) Engine.fastPos-- } }
    val raw by Engine.position.collectAsState()
    val pos = raw + LyricOffset.of(t.id)
    val l = lines
    val p = c
    if (l == null || l.isEmpty()) {
        Box(Modifier.fillMaxSize().then(if (onBlank == null) Modifier else Modifier.pointerInput(onBlank) { detectTapGestures { onBlank() } }), contentAlignment = Alignment.Center) { T(if (l == null) "LOADING" else "NO LYRICS", 12, p.s, mono = true, ls = .08f) }
        return
    }
    val cur = Lrc.indexAt(l, pos)
    LyricsFlow(l, cur, showTr, pos, onBlank)
}

/**
 * 方案 B「大字流动」：大号粗体左对齐，当前句全亮、其它按距离渐淡（远处轻微模糊）；
 * 换句时每一行各自用弹簧滚到新位置，从上往下错开约 35ms，形成「流动」感。
 */
@Composable private fun LyricsFlow(l: List<LyricLine>, cur: Int, showTr: Boolean, pos: Long, onBlank: (() -> Unit)? = null) {
    val p = c
    val copy = rememberCopy()
    val tops = remember(l) { FloatArray(l.size) { -1f } }
    var measured by remember(l) { mutableStateOf(0) }
    val offs = remember(l) { List(l.size) { Animatable(0f) } }
    var settled by remember(l) { mutableStateOf(false) }
    var ready by remember(l) { mutableStateOf(false) } // 第一次排好位置之前先不显示，避免从顶部闪一下
    val gap = with(LocalDensity.current) { 26.dp.toPx() }
    var vh by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density
    // 当前句的停靠位置按设置来：顶部 / 第二行（保留上一句）/ 偏上 / 居中
    LaunchedEffect(cur, measured, vh, LyricPref.anchor) {
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
        val jump = !settled // 刚打开歌词页：直接到位，不从顶部滚下来
        settled = true
        if (jump) { l.indices.forEach { offs[it].snapTo(target) }; ready = true; return@LaunchedEffect }
        l.indices.forEach { i ->
            if (i < first || i > cur + 10) launch { offs[i].snapTo(target) }
            else launch {
                kotlinx.coroutines.delay(((i - first) * 35L / Anim.f).toLong())
                offs[i].animateTo(target, spring(.86f, Anim.stiff(140f)))
            }
        }
    }
    // 手动拖动浏览：拖动时暂停自动滚动、所有行变清晰；松手带惯性，停 3 秒后回到当前句
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
        backJob = scope.launch { kotlinx.coroutines.delay(3000); browsing = false; manual.animateTo(0f, spring(.9f, Anim.stiff(Spring.StiffnessLow))) }
    }
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { dy -> scope.launch { manual.snapTo(manual.value + dy) } }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { vh = it.height.toFloat() }.then(if (onBlank == null) Modifier else Modifier.pointerInput(onBlank) { detectTapGestures { onBlank() } }).draggable(drag, androidx.compose.foundation.gestures.Orientation.Vertical,
        onDragStarted = { backJob?.cancel(); browsing = true; bounds(); manual.stop() },
        onDragStopped = { v -> scope.launch { manual.animateDecay(v, androidx.compose.animation.core.exponentialDecay(.6f)) }; scheduleBack() })) {
        Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).padding(start = 22.dp, end = 30.dp, top = 8.dp)) {
            l.forEachIndexed { i, line ->
                val dist = kotlin.math.abs(i - cur)
                val a by animateFloatAsState(if (browsing) (if (dist == 0) 1f else .7f) else when (dist) { 0 -> 1f; 1 -> .5f; 2 -> .36f; else -> .26f }, spring(1f, Anim.stiff(Spring.StiffnessLow)), label = "la")
                Box(Modifier.fillMaxWidth()
                    .onPlaced { val y = it.positionInParent().y; if (tops[i] != y) { tops[i] = y; measured++ } }
                    .graphicsLayer { translationY = offs[i].value + manual.value; alpha = if (ready) a else 0f }
                    .then(if (UiPref.lyricBlur && !browsing && dist in 2..8 && Plat.impl.liveBlur) Modifier.blur(1.2.dp) else Modifier)
                    .padding(bottom = with(LocalDensity.current) { gap.toDp() })) {
                  // 当前句放大两个字号（用缩放做，换句时有过渡）；点击区域只包住文字，旁边的空白点了回到封面
                  val big by animateFloatAsState(if (i == cur && !browsing) (LyricPref.size + 2f) / LyricPref.size else 1f, spring(.8f, Anim.stiff(Spring.StiffnessLow)), label = "lz")
                  // 所有行都按「放大后刚好不超宽」的宽度排版：当前句放大后不会被右边裁掉，换句时也不会重新折行
                  val sMax = (LyricPref.size + 2f) / LyricPref.size
                  Box(Modifier.layout { m, cs -> val pl = m.measure(cs.copy(minWidth = 0, maxWidth = (cs.maxWidth / sMax).toInt())); layout(pl.width, pl.height) { pl.place(0, 0) } }
                    .graphicsLayer { scaleX = big; scaleY = big; transformOrigin = TransformOrigin(0f, .5f) }
                    .press(onLong = { if (line.text.isNotEmpty()) copy(if (showTr && line.tr != null) line.text + "\n" + line.tr else line.text, "歌词") }) { Engine.seek(line.t); backJob?.cancel(); browsing = false; scope.launch { manual.animateTo(0f, spring(.9f, Anim.stiff(Spring.StiffnessMediumLow))) } }) {
                    if (line.text.isEmpty()) {
                        val next = l.getOrNull(i + 1)?.t ?: (line.t + 5000)
                        val prog = if (i == cur) ((pos - line.t).toFloat() / (next - line.t).coerceAtLeast(300)).coerceIn(0f, 1f) else 0f
                        Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) { k -> Box(Modifier.size(8.dp).background(if (i == cur && prog > k / 3f) p.k else p.k.copy(alpha = .3f), androidx.compose.foundation.shape.CircleShape)) }
                        }
                    } else Column {
                        if (UiPref.karaoke && i == cur && line.words != null && !browsing) KaraokeLine(line, pos) else T(line.text, LyricPref.size.toFloat(), p.k, 800, lines = 6, lh = 1.25f)
                        if (showTr && line.tr != null) T(line.tr, Fs.SUB, p.s, lines = 4, lh = 1.45f, modifier = Modifier.padding(top = 4.dp))
                    }
                  }
                }
            }
            Spacer(Modifier.height(400.dp))
        }
    }
}

// ======================= 播放队列 =======================
@Composable fun QueueSheet() {
    val nav = LocalNav.current
    val p = c
    AnimatedVisibility(nav.queue, enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
        Box(Modifier.fillMaxSize().background(Color(0x80000000)).pointerInput(Unit) { detectTapGestures { nav.queue = false } })
    }
    AnimatedVisibility(nav.queue, Modifier.fillMaxSize(), enter = slideInVertically(sp) { it }, exit = slideOutVertically(sp) { it }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val st by Engine.state.collectAsState()
            val ls = rememberLazyListState((st.index - 2).coerceAtLeast(0))
            Column(Modifier.fillMaxWidth().fillMaxHeight(.75f).background(p.sheet).drawBehind { if (!Look.flat) drawRect(p.k, size = Size(size.width, 3.dp.toPx())) }
                .pointerInput(Unit) { detectTapGestures { } }) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { TagRow("QUEUE · ${st.queue.size}", 8.dp) }
                    LinkBtn("清空待播", 14) { Engine.clearUpcoming() }
                    Box(Modifier.size(44.dp).press { nav.queue = false }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 14.dp) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(if (Look.flat) Color.Transparent else p.l2))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), ls, contentPadding = PaddingValues(start = 18.dp, end = 6.dp, bottom = 12.dp)) {
                    itemsIndexed(st.queue, key = { i, t -> "$i${t.id}" }) { i, t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { TrackRow(t, i == st.index, st.format.uppercase().takeIf { i == st.index && it.isNotEmpty() }) { Engine.playAt(i) } }
                            Box(Modifier.size(44.dp).press { Engine.removeAt(i) }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 12.dp, p.s) }
                        }
                    }
                }
                LocateBtn(ls, st.index)
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}


/** 逐字高亮：底下一层半透明文字，上面一层实色文字按字的进度裁出来；进度用帧时钟在两次位置更新之间插值，扫过去是连续的 */
@Composable private fun KaraokeLine(line: LyricLine, pos: Long) {
    val p = c
    val words = line.words ?: return
    val playing = Engine.state.collectAsState().value.playing
    var now by remember { mutableLongStateOf(pos) }
    LaunchedEffect(pos, playing) {
        val base = pos; now = base
        if (playing) { val start = withFrameMillis { it }; while (true) { withFrameMillis { f -> now = base + (f - start) }; if (UiPref.lowMem) kotlinx.coroutines.delay(40) } }
    }
    var lay by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val st = androidx.compose.ui.text.TextStyle(fontSize = LyricPref.size.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(800), fontFamily = Typo.family, lineHeight = (LyricPref.size * 1.25f).sp)
    Box {
        androidx.compose.foundation.text.BasicText(line.text, style = st.copy(color = p.k.copy(alpha = .32f)), maxLines = 6, onTextLayout = { lay = it })
        androidx.compose.foundation.text.BasicText(line.text, style = st.copy(color = p.k), maxLines = 6, modifier = Modifier.drawWithContent {
            val l = lay ?: return@drawWithContent
            val n = l.layoutInput.text.length
            val path = androidx.compose.ui.graphics.Path()
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
@Composable fun CommentSheet() {
    val nav = LocalNav.current
    val p = c
    var last by remember { mutableStateOf<Track?>(null) }
    nav.comments?.let { last = it }
    AnimatedVisibility(nav.comments != null, Modifier.fillMaxSize(), enter = slideInVertically(sp) { it }, exit = slideOutVertically(sp) { it }) {
        val t = last ?: return@AnimatedVisibility
        CommentsBody(t) { nav.comments = null }
    }
}

/** 评论内容（手机是全屏弹层；PC 宽屏放在右栏替换歌词） */
@Composable fun CommentsBody(t: Track, onClose: () -> Unit) {
    val nav = LocalNav.current
    val p = c
    val me = Store.profile.collectAsState().value
    var hot by remember(t.id) { mutableStateOf(true) }
    val list = remember(t.id, hot) { mutableStateListOf<Comment>() }
    var total by remember(t.id, hot) { mutableIntStateOf(-1) }
    var busy by remember(t.id, hot) { mutableStateOf(false) }
    var end by remember(t.id, hot) { mutableStateOf(false) }
    var err by remember(t.id, hot) { mutableStateOf<String?>(null) }
    var text by remember(t.id) { mutableStateOf("") }
    var replyTo by remember(t.id) { mutableStateOf<Comment?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    fun more() {
        if (busy || end) return
        busy = true
        scope.launch {
            try {
                val (l, n) = Ncm.comments(t.id, hot, list.size, if (!hot) list.lastOrNull()?.time ?: 0 else 0)
                list += l.filter { x -> list.none { it.id == x.id } }; total = n; if (l.isEmpty() || (total in 0..list.size)) end = true
            } catch (e: Exception) { err = e.message; end = true }
            busy = false
        }
    }
    LaunchedEffect(t.id, hot) { more() }
    val ls = remember(t.id, hot) { androidx.compose.foundation.lazy.LazyListState() }
    val nearEnd by remember(ls) { derivedStateOf { (ls.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= ls.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, list.size) { if (nearEnd && list.isNotEmpty()) more() }
    fun send() {
        val v = text.trim(); if (v.isEmpty() || sending) return
        if (me == null) { nav.toast = "登录后才能评论"; return }
        sending = true
        val r = replyTo
        scope.launch {
            try {
                Ncm.commentPost(t.id, v, r?.id)
                text = ""; replyTo = null; fm.clearFocus()
                nav.toast = if (r != null) "回复已发送" else "评论已发送"
                if (!hot) list.add(0, Comment(System.currentTimeMillis(), me.name, me.uid, me.avatar, v, 0, System.currentTimeMillis(), r?.let { "@${it.user}：${it.content}" }))
            } catch (e: Exception) { nav.toast = "发送失败：${e.message}" }
            sending = false
        }
    }
    Column(Modifier.fillMaxSize().background(p.sheet).statusBarsPadding().drawBehind { if (!Look.flat) drawRect(p.k, size = Size(size.width, 3.dp.toPx())) }) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TagRow("COMMENTS" + if (total > 0) " · $total" else "", 8.dp) }
            Box(Modifier.size(44.dp).press { fm.clearFocus(); onClose() }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 14.dp) }
        }
        Row(Modifier.padding(horizontal = 18.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to "热门", false to "最新").forEach { (k, l) ->
                Box(Modifier.press { hot = k }.background(if (hot == k) p.k else Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 6.dp)) { T(l, 13, if (hot == k) p.tagFg else p.k, 700) }
            }
            Spacer(Modifier.weight(1f)); T(t.title, 13, p.s, modifier = Modifier.align(Alignment.CenterVertically).widthIn(max = 180.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (Look.flat) Color.Transparent else p.l2))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), ls, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
            itemsIndexed(list, key = { _, x -> x.id }) { _, x ->
                val replyOf: (Comment) -> Unit = { r -> if (me == null) nav.toast = "登录后才能回复" else { replyTo = r; fr.requestFocus() } }
                val userOf: (Comment) -> Unit = { r -> fm.clearFocus(); nav.comments = null; nav.player = false; nav.push(Page.User(r.uid, r.user)) }
                CommentRow(x, t.id, onReply = { replyOf(x) }, onUserOf = userOf, onReplyOf = replyOf) { userOf(x) }
            }
            item {
                T(when { err != null -> "加载失败：$err"; busy -> "LOADING"; list.isEmpty() -> "NO COMMENTS"; end -> "END"; else -> "" },
                    12, p.s, mono = true, ls = .08f, lines = 3, modifier = Modifier.padding(vertical = 20.dp))
            }
        }
        // 聊天式输入栏：贴在键盘上
        Column(Modifier.fillMaxWidth().background(p.bar).drawBehind { if (!Look.flat) drawRect(p.k, size = Size(size.width, 2.dp.toPx())) }) {
            replyTo?.let { r ->
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(30.dp).background(p.p)); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) { T("回复 @${r.user}", 12, p.p, 700); T(r.content, 12, p.s) }
                    Box(Modifier.size(40.dp).press { replyTo = null }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 12.dp) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f).heightIn(min = 44.dp).nbf(1.dp, p.k).background(p.page).padding(horizontal = 12.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) T(if (me == null) "登录后才能评论" else if (replyTo != null) "回复 @${replyTo!!.user}…" else "说点什么…", 15, p.s)
                    androidx.compose.foundation.text.BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(fr), enabled = me != null, maxLines = 5,
                        textStyle = androidx.compose.ui.text.TextStyle(color = p.k, fontSize = 15.sp, fontFamily = Typo.family, lineHeight = 21.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(p.p))
                }
                Spacer(Modifier.width(8.dp))
                val can = text.isNotBlank() && !sending
                Box(Modifier.size(44.dp).press(.92f, true) { send() }.background(if (can) p.p else p.l3), contentAlignment = Alignment.Center) {
                    if (sending) T("…", 16, Color.White, 800) else NIcon(NIcons.send, 20.dp, Color.White)
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars)))
        }
    }
}

@Composable private fun CommentRow(x: Comment, songId: String, nested: Boolean = false, parentUser: String? = null, onReply: () -> Unit, onUserOf: (Comment) -> Unit = {}, onReplyOf: (Comment) -> Unit = {}, onUser: () -> Unit) {
    val p = c
    val nav = LocalNav.current
    var liked by remember(x.id) { mutableStateOf(x.likedByMe) }
    val n = x.liked + (if (liked) 1 else 0) - (if (x.likedByMe) 1 else 0)
    Row(Modifier.fillMaxWidth().drawBehind { if (!Look.flat) drawRect(p.l1, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }.padding(vertical = 14.dp)) {
        Box(Modifier.size(36.dp).background(p.ph).nb(1.dp, p.k).press(onClick = onUser)) {
            if (x.avatar != null) coil3.compose.AsyncImage(img(x.avatar, 96), null, Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(x.user, 13, p.s, 600, modifier = Modifier.weight(1f).press(onClick = onUser))
                Row(Modifier.press(.9f, true) {
                    if (!Ncm.loggedIn) { nav.toast = "登录后才能点赞"; return@press }
                    val on = !liked; liked = on
                    Store.run { try { Ncm.commentLike(songId, x.id, on) } catch (e: Exception) { liked = !on; nav.toast = "点赞失败：${e.message}" } }
                }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 点了赞：红色实心；没点：空心
                    NIcon(if (liked) NIcons.heart else NIcons.heartLine, 15.dp, if (liked) LikeRed else p.s)
                    Spacer(Modifier.width(4.dp)); T(likeNum(n), 12, if (liked) LikeRed else p.s, mono = true)
                }
            }
            Spacer(Modifier.height(4.dp))
            T(x.content, 15, p.k, lines = 40, lh = 1.5f, modifier = Modifier.press(onClick = onReply))
            // 楼里的回复不再引用原文：回楼主的什么都不加，回楼里别人的只标一行「回复 @某人」
            if (x.reply != null) {
                if (!nested) T(x.reply, 13, p.s, lines = 4, lh = 1.5f, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().background(p.hl).padding(8.dp))
                else { val who = x.reply.removePrefix("@").substringBefore("："); if (who != parentUser) T("回复 @$who", 12, p.s, modifier = Modifier.padding(top = 4.dp)) }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(timeAgo(x.time), 11, p.s, mono = true, modifier = Modifier.weight(1f))
                T("回复", 12, p.s, 600, modifier = Modifier.press(onClick = onReply).padding(vertical = 4.dp, horizontal = 2.dp))
            }
            if (!nested && x.replies > 0) FloorReplies(x, songId, onUserOf, onReplyOf)
        }
    }
}

val LikeRed = Color(0xFFE5484D)

/** 楼层回复：点「N 条回复」展开，楼里的回复缩进显示，可以继续加载更多 */
@Composable private fun FloorReplies(x: Comment, songId: String, onUserOf: (Comment) -> Unit, onReplyOf: (Comment) -> Unit) {
    val p = c
    val nav = LocalNav.current
    var open by remember(x.id) { mutableStateOf(false) }
    var list by remember(x.id) { mutableStateOf<List<Comment>>(emptyList()) }
    var cursor by remember(x.id) { mutableStateOf(-1L) }
    var busy by remember(x.id) { mutableStateOf(false) }
    var loadedOnce by remember(x.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun more() {
        if (busy) return; busy = true
        scope.launch {
            try { val (l, cur) = Ncm.commentFloor(songId, x.id, if (loadedOnce) cursor else -1); list = list + l.filter { a -> list.none { it.id == a.id } }; cursor = cur; loadedOnce = true }
            catch (e: Exception) { nav.toast = "回复加载失败：${e.message}" }
            busy = false
        }
    }
    Column(Modifier.padding(top = 6.dp)) {
        if (open) Column(Modifier.fillMaxWidth().drawBehind { drawRect(p.l2, size = Size(2.dp.toPx(), size.height)) }.padding(start = 10.dp)) {
            list.forEach { r -> CommentRow(r, songId, nested = true, parentUser = x.user, onReply = { onReplyOf(r) }, onUser = { onUserOf(r) }, onUserOf = onUserOf) }
            if (busy) T("LOADING", 11, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 8.dp))
            else if (cursor != -1L) T("加载更多回复", 12, p.s, 600, modifier = Modifier.press { more() }.padding(vertical = 8.dp))
        }
        T(if (open) "收起回复" else "${x.replies} 条回复 ›", 12, p.p, 600, modifier = Modifier.press {
            open = !open; if (open && !loadedOnce) more()
        }.padding(vertical = 4.dp))
    }
}

/** 像聊天软件那样的时间：刚刚 / 5 分钟前 / 今天 13:20 / 昨天 / 2024-03-01 */
fun timeAgo(ms: Long): String {
    val d = System.currentTimeMillis() - ms
    val now = java.util.Calendar.getInstance(); val c0 = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = now.get(java.util.Calendar.YEAR) == c0.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == c0.get(java.util.Calendar.DAY_OF_YEAR)
    return when {
        d < 60_000 -> "刚刚"
        d < 3_600_000 -> "${d / 60_000} 分钟前"
        sameDay -> java.text.SimpleDateFormat("今天 HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
        d < 172_800_000 -> java.text.SimpleDateFormat("昨天 HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
        now.get(java.util.Calendar.YEAR) == c0.get(java.util.Calendar.YEAR) -> java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(ms))
    }
}

fun likeNum(n: Int) = if (n >= 10000) "%.1fw".format(n / 10000f) else n.toString()


/** 宽屏播放页：左栏封面、歌名、控制（限制宽度居中），右栏歌词 */
@Composable private fun WidePlayerBody(t: Track, st: PlayState, showTr: Boolean, toggleTr: () -> Unit) {
    val copy = rememberCopy()
    val nav = LocalNav.current
    val p = c
    val bx = BtnCtx(t, st, showTr, toggleTr)
    // 歌词顶端和封面顶端对齐：记下封面在播放页里的纵向位置
    var coverY by remember { mutableStateOf(-1f) }
    var rightY by remember { mutableStateOf(0f) }
    val dens = LocalDensity.current
    Row(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            // 封面宽度 = 进度条宽度（控制区左右各留 22 的内边距，所以外框比封面宽 44）
            // 左边留 48 给收起按钮，右边是按钮竖栏（类似 Hydrogen）；封面大小在设置里按百分比调
            val sideKeys = PlayerLayout.keys(true, "side")
            val sideW = if (sideKeys.isEmpty()) 0.dp else 52.dp
            val full = minOf(maxWidth - 44.dp - 56.dp - sideW - 16.dp, maxHeight - 290.dp, 560.dp)
            val cover = (full * (UiPref.coverPct / 100f)).coerceAtLeast(160.dp)
            Row(Modifier.padding(start = 56.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.width(cover + 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.onGloballyPositioned { coverY = it.positionInRoot().y }) { Cover(t.cover, cover, t.title) }
                Spacer(Modifier.height(22.dp))
                Row(Modifier.width(cover), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        T(t.title, Fs.PAGE, w = 800, lh = 1.25f, lines = 2, modifier = Modifier.press(onLong = { copy(t.title, "歌名") }) {})
                        T(t.artist, 15, p.s, lh = 1.3f, lines = 2, modifier = Modifier.press(onLong = { copy(t.artist, "歌手") }) { openArtists(nav, t) })
                        val ainfo by Engine.info.collectAsState()
                        T(if (st.format.isEmpty()) Ncm.LEVEL_LABELS[Engine.quality.coerceIn(0, 4)] + " ▾"
                          else (ainfo?.let { if (it.kbps == 0 && st.kbps > 0) it.copy(kbps = st.kbps) else it }?.line() ?: "${st.format.uppercase()}  ${if (st.format == "flac") "无损" else "有损"}${if (st.kbps > 0) " · ${st.kbps} kbps" else ""}") + "${if (st.cached) " · 缓存" else ""} ▾",
                            11, p.s, mono = true, ls = .06f, modifier = Modifier.padding(top = 6.dp).press { qualitySheet(nav) })
                    }
                    // 右侧一列：歌名右侧槽位（默认 喜欢 / 译 / 播放模式），和封面右边缘对齐
                    Column(Modifier.offset(x = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) { SlotCol(true, "info", bx, 0.dp) }
                }
                Spacer(Modifier.height(10.dp))
                val bottom = PlayerLayout.keys(true, "bottom")
                Box(Modifier.fillMaxWidth()) { Controls(t, st, if (bottom.isEmpty()) null else ({ Spacer(Modifier.weight(1f)); SlotRow(true, "bottom", bx); Spacer(Modifier.weight(1f)) })) }
            }
            // 按钮竖栏：贴在封面区右边、和控制条底部对齐，往上排
            if (sideKeys.isNotEmpty()) Column(Modifier.padding(start = 8.dp, bottom = 4.dp).width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) { SlotCol(true, "side", bx, 4.dp) }
            }
            // 收起播放页（也可以按 Esc）
            Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(44.dp).press { nav.player = false }, contentAlignment = Alignment.Center) { NIcon(NIcons.chevronDown, 20.dp, p.s) }
        }
        // 右栏：歌词；打开评论时换成评论（从右边滑进来）
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().onGloballyPositioned { rightY = it.positionInRoot().y }) {
            AnimatedContent(nav.comments, Modifier.fillMaxSize(), transitionSpec = {
                if (targetState != null) (slideInHorizontally(sp) { it / 3 } + fadeIn(tween(200))) togetherWith fadeOut(tween(120))
                else fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(160)))
            }, label = "right") { ct ->
                if (ct != null) CommentsBody(ct) { nav.comments = null }
                else Box(Modifier.fillMaxSize().padding(top = if (coverY < 0) 24.dp else with(dens) { (coverY - rightY).coerceAtLeast(0f).toDp() - 12.dp }.coerceAtLeast(0.dp), end = 24.dp)) { Lyrics(t, showTr) }
            }
        }
        // 第三栏：播放队列（看歌词的同时打开），从右边展开
        AnimatedVisibility(nav.side == 2, Modifier.fillMaxHeight(),
            enter = expandHorizontally(spring(1f, Anim.stiff(Spring.StiffnessMediumLow)), expandFrom = Alignment.Start) + fadeIn(tween(200)),
            exit = shrinkHorizontally(tween(220), shrinkTowards = Alignment.Start) + fadeOut(tween(160))) {
            Column(Modifier.width(360.dp).fillMaxHeight().drawBehind {
                if (!Look.flat) drawRect(p.k, Offset.Zero, Size(3.dp.toPx(), size.height))
            }.padding(start = 3.dp)) {
                Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("QUEUE", 12, p.k, 700, mono = true, ls = .1f, modifier = Modifier.weight(1f))
                    Box(Modifier.size(40.dp).press { nav.side = 0 }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 12.dp) }
                }
                Box(Modifier.weight(1f)) { QueueList(st) }
            }
        }
    }
}
