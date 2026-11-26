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
@Composable fun MiniBar() {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    val t = st.current ?: return
    val p = c
    var barW by remember { mutableFloatStateOf(1f) }
    val dens = LocalDensity.current
    Box(Modifier.fillMaxWidth().height(60.dp).onSizeChanged { barW = it.width.toFloat().coerceAtLeast(1f) }) {
        Row(Modifier.fillMaxSize().background(barBg()).drawBehind { if (!Look.flat) drawRect(p.l2, size = Size(size.width, 1.dp.toPx())) }
            .press { SharedHero.capture("now:${t.id}", t.cover, t.title, tag = "mini"); nav.lyrics = false; nav.player = true }.padding(start = 14.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(t.cover, 40.dp, t.title, modifier = Modifier.heroAnchor("now:${t.id}", t.cover, t.title, tag = "mini", priority = 2).geomReveal(t.id))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { T(t.title, 15, w = 700, lh = 1.3f); T(t.artist, 12, p.s, lh = 1.3f) }
            Box(Modifier.size(40.dp, 48.dp).holdScrub(-1, enabled = !st.fm, haptic = true) { if (st.fm) { Engine.fmTrash(); nav.toast = "不再推荐这首" } else Engine.prev() }, contentAlignment = Alignment.Center) { NIcon(if (st.fm) NIcons.trash else NIcons.prev, 19.dp, if (ScrubController.dir < 0) p.p else p.k) }
            Box(Modifier.size(44.dp, 48.dp).press(haptic = true) { Engine.toggle() }, contentAlignment = Alignment.Center) { NIcon(if (st.playing) NIcons.pause else NIcons.play, 21.dp) }
            Box(Modifier.size(40.dp, 48.dp).holdScrub(1, haptic = true) { Engine.next() }, contentAlignment = Alignment.Center) { NIcon(NIcons.next, 19.dp, if (ScrubController.dir > 0) p.p else p.k) }
        }
        var scrub by remember { mutableStateOf<Float?>(null) }
        Box(Modifier.offset(y = (-7).dp)) { ScrubBar(t.durationMs) { scrub = it } }
        // 拖动时，时间气泡跟着手指（去掉 BoxWithConstraints，直接用已测宽度定位）
        scrub?.let { f ->
            val bubbleW = with(dens) { 52.dp.toPx() }
            val pad = with(dens) { 4.dp.toPx() }
            val xPx = (barW * f - bubbleW / 2f).coerceIn(pad, (barW - bubbleW - pad).coerceAtLeast(pad))
            Box(Modifier.graphicsLayer { translationX = xPx; translationY = -38.dp.toPx() }.size(52.dp, 26.dp).background(p.k), contentAlignment = Alignment.Center) {
                T(fmt((f * t.durationMs).toLong()), 12, p.tagFg, mono = true)
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
    var wasOpen by remember { mutableStateOf(nav.player) }
    val originExp = MotionEffects.originExpand
    val geomExp = MotionEffects.geomContainer
    val customExp = originExp || geomExp
    val originProg = remember { Animatable(if (nav.player) 1f else 0f) }
    LaunchedEffect(nav.player, customExp) {
        if (wasOpen != nav.player) OriginBus.onPlayerToggle(nav.player)
        if (wasOpen && !nav.player && drag.value < 24f) Engine.state.value.current?.let { SharedHero.flyBack("now:${it.id}") }
        wasOpen = nav.player
        if (customExp) originProg.animateTo(if (nav.player) 1f else 0f, if (geomExp) tween(Motion.pageMs, easing = androidx.compose.animation.core.LinearEasing) else if (nav.player) Motion.page() else Motion.standard())
        else originProg.snapTo(if (nav.player) 1f else 0f)
    }
    val showOriginPlayer by remember { derivedStateOf { customExp && (nav.player || originProg.value > 0.002f) } }
    AnimatedVisibility(if (customExp) showOriginPlayer else nav.player,
        enter = if (customExp) fadeIn(androidx.compose.animation.core.snap()) else if (MotionEffects.pageScale && !AnimStyle.off) slideInVertically(Motion.page()) { it / 4 } + fadeIn(Motion.page()) + scaleIn(Motion.page(), initialScale = .96f) else slideInVertically(sp) { it },
        exit = if (customExp) fadeOut(androidx.compose.animation.core.snap()) else if (MotionEffects.pageScale && !AnimStyle.off) slideOutVertically(Motion.standard()) { it / 4 } + fadeOut(Motion.quick()) + scaleOut(Motion.standard(), targetScale = .97f) else slideOutVertically(sp) { it }) {
        LaunchedEffect(Unit) { drag.snapTo(0f) }
        val st by Engine.state.collectAsState()
        val t = st.current
        val ltState = animateFloatAsState(if (nav.lyrics) 1f else 0f, spF, label = "ly")
        val isLyricsTitle by remember { derivedStateOf { ltState.value > .5f } }
        val showBigTitle by remember { derivedStateOf { ltState.value < .45f } }
        val showSmallTitle by remember { derivedStateOf { ltState.value > .75f } }
        LaunchedEffect(t?.id) { t?.let { runCatching { LyricCache.get(it.id) } } }
        var showTr by remember { mutableStateOf(true) }
        val p = c
        var rootH by remember { mutableFloatStateOf(1f) }
        val wideP = Screen.wide
        // 播放页整块吃掉点击：启用源点插件时直接从点击的播放条/封面位置撑开并原路缩回；下拉联动微缩与速度甩关
        Box(Modifier.fillMaxSize().onSizeChanged { rootH = it.height.toFloat().coerceAtLeast(1f) }.drawBehind {
            val dv = drag.value
            if (dv > 0.5f) {
                val remain = (1f - dv / (rootH * 0.7f)).coerceIn(0f, 1f)
                drawRect(Color.Black.copy(alpha = 0.22f * remain))
            } else if (customExp && originProg.value < 0.999f) {
                drawRect(Color.Black.copy(alpha = 0.20f * originProg.value))
            }
        }) {
        Box(Modifier.fillMaxSize().then(when { geomExp -> Modifier.geomContainerReveal { originProg.value }; originExp -> Modifier.originContainerExpand({ originProg.value }, { OriginBus.activePlayerOrigin() }); else -> Modifier }).graphicsLayer {
            val dv = drag.value
            translationY = dv
            if (dv > 0.5f && MotionEffects.pageScale) {
                val sc = 1f - 0.04f * (dv / rootH).coerceIn(0f, 1f)
                scaleX = sc; scaleY = sc
            }
        }.background(p.page).pointerInput(Unit) { detectTapGestures { } }) {
            BgLayer()
            // 矮屏时顶栏收起了，播放页自己躲开状态栏
            Column(Modifier.fillMaxSize().then(if (Screen.short && !wideP) Modifier.statusBarsPadding() else Modifier)) {
                // 顶栏：按住下拉可跟手收起，快速向下甩动直接收起
                if (!wideP) Row(Modifier.fillMaxWidth().height(if (Screen.short) 40.dp else 48.dp).pointerInput(Unit) {
                    val vt = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { vt.resetTracking() },
                        onDragEnd = {
                            val vy = vt.calculateVelocity().y
                            scope.launch { if (drag.value > rootH * .18f || vy > 950f) nav.player = false else drag.animateTo(0f, Motion.hero()) }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(0f, Motion.hero()) } },
                    ) { ch, dy ->
                        vt.addPosition(ch.uptimeMillis, ch.position)
                        scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) }
                    }
                }.drawBehind { if (!Look.flat) drawRect(p.l2, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).press { nav.player = false }, contentAlignment = Alignment.Center) { NIcon(NIcons.chevronDown, 20.dp) }
                    // 矮屏不显示 NOW PLAYING / LYRICS 标题，省出高度给封面
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { if (!Screen.short) T(if (isLyricsTitle && !wideP) "LYRICS" else if (st.fm) "PERSONAL FM" else "NOW PLAYING", 12, p.k.copy(alpha = .5f), 700, mono = true, ls = .14f) }
                    // 顶栏右侧槽位（默认放「译」，只在歌词页显示）
                    if (t != null) { val x = BtnCtx(t, st, showTr) { showTr = !showTr }
                        PlayerLayout.keys(false, "top").forEach { k -> Box(if (k == "tr") Modifier.graphicsLayer { alpha = ltState.value } else Modifier) { PBtn(k, x) } } }
                    else Spacer(Modifier.width(44.dp))
                }
                if (t == null) { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { T("NO TRACK", 12, p.s, mono = true, ls = .08f) }; return@Column }
                // 宽窗口（PC / 平板横屏）：左边封面 + 信息 + 控制，右边歌词
                if (wideP) { Box(Modifier.weight(1f).fillMaxWidth()) { WidePlayerBody(t, st, showTr) { showTr = !showTr } }; return@Column }
                var boxW by remember { mutableFloatStateOf(360f) }
                var boxH by remember { mutableFloatStateOf(640f) }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged {
                    if (it.width > 0 && it.height > 0) { boxW = it.width.toFloat(); boxH = it.height.toFloat() }
                }) {
                    val d = LocalDensity.current
                    val w = boxW
                    val bottomPad = with(d) { WindowInsets.navigationBars.getBottom(d).toFloat() }
                    val ctrlH = with(d) { 156.dp.toPx() } + bottomPad
                    val free = boxH - ctrlH
                    val dp = with(d) { 1.dp.toPx() }
                    // 下面要留给歌名、歌手、音质三行；矮屏这三行各只占一行，封面左右只留 12dp
                    val sh = Screen.short
                    val coverNow = (free - (if (sh) 106 else 150) * dp).coerceIn(120 * dp, (w - (if (sh) 24 else 56) * dp).coerceAtLeast(120 * dp))
                    val nowTop = ((free - coverNow - 84 * dp) / 2).coerceIn(8 * dp, 40 * dp)
                    val coverDp = with(d) { coverNow.toDp() }
                    // 歌词列
                    // 歌词一直挂着（封面页时透明、不接收点击）：切到歌词页时已经排好版、停在当前句，不会先闪一下再跳
                    Box(Modifier.fillMaxWidth().height(with(d) { (free - 68 * dp).coerceAtLeast(0f).toDp() }).offset(y = 68.dp)
                        .graphicsLayer { alpha = ((ltState.value - .7f) / .3f).coerceIn(0f, 1f).let { it * it } }
                        .then(if (nav.lyrics) Modifier else Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() } } })) {
                        Lyrics(t, showTr, active = nav.lyrics) { nav.lyrics = false }
                    }
                    // 封面：始终按大尺寸解码，切换封面/歌词全程仅在 graphicsLayer 做位移与缩放，0 重组；接入 heroAnchor 支持开合直飞
                    Box(Modifier.graphicsLayer {
                        val lt = ltState.value
                        val cs = lerp(coverNow, 48 * dp, lt)
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = lerp((w - coverNow) / 2, 18 * dp, lt)
                        translationY = lerp(nowTop, 8 * dp, lt)
                        scaleX = cs / coverNow; scaleY = cs / coverNow
                    }.heroAnchor("now:${t.id}", t.cover, t.title, isTarget = true).pointerInput(nav.lyrics) {
                        // 封面视图下往下拖封面或快速向下甩动 = 收起播放页；歌词模式不拦，留给歌词滚动
                        if (!nav.lyrics) {
                            val vt = androidx.compose.ui.input.pointer.util.VelocityTracker()
                            detectVerticalDragGestures(
                                onDragStart = { vt.resetTracking() },
                                onDragEnd = {
                                    val vy = vt.calculateVelocity().y
                                    scope.launch { if (drag.value > rootH * .18f || vy > 950f) nav.player = false else drag.animateTo(0f, Motion.hero()) }
                                },
                                onDragCancel = { scope.launch { drag.animateTo(0f, Motion.hero()) } },
                            ) { ch, dy ->
                                ch.consume(); vt.addPosition(ch.uptimeMillis, ch.position)
                                scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) }
                            }
                        }
                    }.then(Modifier.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { nav.lyrics = !nav.lyrics })) {
                        CoverRevealSwitch(t.cover, coverDp, t.title, revealKey = t.id)
                    }
                    // 标题：两套各在自己的位置淡入淡出
                    // 信息块：和封面左边对齐，宽度等于封面；爱心在歌名同一行最右，对齐封面右边缘
                    if (showBigTitle) Column(Modifier.width(coverDp).graphicsLayer {
                        val lt = ltState.value
                        translationX = (w - coverNow) / 2; translationY = nowTop + coverNow + 18 * dp - 12 * dp * lt; alpha = (1 - lt / .45f).coerceIn(0f, 1f)
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T(t.title, if (sh) 21 else 24, w = 800, lh = 1.25f, lines = if (sh) 1 else 2, modifier = Modifier.weight(1f).press(onLong = { copy(t.title, "歌名") }) {})
                            Row(Modifier.offset(x = 10.dp)) { SlotRow(false, "title", BtnCtx(t, st, showTr) { showTr = !showTr }, 0.dp) }
                        }
                        T(t.artist, if (sh) 14 else 15, p.s, lh = 1.3f, lines = if (sh) 1 else 2, modifier = Modifier.press(onLong = { copy(t.artist, "歌手") }) { openArtists(nav, t) })
                        // 点音质这一行可以换音质
                        val ainfo by Engine.info.collectAsState()
                        T(if (st.format.isEmpty()) Ncm.LEVEL_LABELS[Engine.quality.coerceIn(0, 4)] + " ▾"
                          else (ainfo?.let { if (it.kbps == 0 && st.kbps > 0) it.copy(kbps = st.kbps) else it }?.line() ?: "${st.format.uppercase()}  ${if (st.format == "flac") "无损" else "有损"}${if (st.kbps > 0) " · ${st.kbps} kbps" else ""}") + "${if (st.cached) " · 缓存" else ""} ▾",
                            11, p.s, mono = true, ls = .06f, modifier = Modifier.padding(top = 6.dp).press { qualitySheet(nav) })
                    }
                    if (showSmallTitle) Column(Modifier.width(with(d) { (w - 96 * dp).coerceAtLeast(40 * dp).toDp() }).graphicsLayer {
                        val lt = ltState.value
                        val cs = lerp(coverNow, 48 * dp, lt)
                        val cl = lerp((w - coverNow) / 2, 18 * dp, lt)
                        val ct = lerp(nowTop, 8 * dp, lt)
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
}

@Composable internal fun Controls(t: Track, st: PlayState, row: (@Composable RowScope.() -> Unit)? = null) {
    val nav = LocalNav.current
    var scrub by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).navigationBarsPadding()) {
        ScrubBar(t.durationMs) { scrub = it }
        Spacer(Modifier.height(2.dp)); TimeRow(t.durationMs, scrub)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (st.fm) SquareBtn(NIcons.trash) { Engine.fmTrash(); nav.toast = "不再推荐这首" } else SquareBtn(NIcons.prev, scrubDir = -1) { Engine.prev() }
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f).fillMaxHeight().press(.98f, true) { Engine.toggle() }.background(c.p).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val lbl = when {
                    ScrubController.dir > 0 -> "快进 >> %.1f×".format(ScrubController.rate)
                    ScrubController.dir < 0 -> "快退 << %.1f×".format(ScrubController.rate)
                    st.loading && st.want && !st.playing -> tr("加载中")
                    st.playing -> tr("暂停")
                    else -> tr("播放")
                }
                T(lbl, 15, Color.White, 700, modifier = Modifier.weight(1f))
                if (st.loading && st.want && !st.playing) LoadingDots() else NIcon(if (st.playing) NIcons.pause else NIcons.play, 20.dp, Color.White)
            }
            Spacer(Modifier.width(10.dp))
            SquareBtn(NIcons.next, scrubDir = 1) { Engine.next() }
        }
        if (row != null) { Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, content = row) }
    }
}

/** 缓冲超过 0.4 秒才显示加载点（仅在 graphicsLayer 读动画值，0 重组） */
@Composable private fun LoadingDots() {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(400); show = true }
    if (!show) NIcon(NIcons.play, 22.dp, Color.White)
    else {
        val a = androidx.compose.animation.core.rememberInfiniteTransition(label = "ld").animateFloat(.3f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(500), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(3) { Box(Modifier.size(5.dp).graphicsLayer { val v = a.value; alpha = if (it == 1) 1.3f - v else v }.background(Color.White)) } }
    }
}

// ======================= 歌词 =======================
/** 解析好的歌词放内存里：切到歌词页、开关评论 / 队列时不用重新拉，也就不会闪 LOADING */

@Composable internal fun WidePlayerBody(t: Track, st: PlayState, showTr: Boolean, toggleTr: () -> Unit) {
    val copy = rememberCopy()
    val nav = LocalNav.current
    val p = c
    val bx = BtnCtx(t, st, showTr, toggleTr)
    // 歌词顶端和封面顶端对齐：仅在坐标变化超过 1px 时更新，避免拖窗高频回写触发多余重组
    var coverY by remember { mutableFloatStateOf(-1f) }
    var rightY by remember { mutableFloatStateOf(0f) }
    val dens = LocalDensity.current
    // 仅在队列栏开关时跑弹簧插值；拖窗缩放时 queueAnim 恒定在 0 或 1，封面与左栏 1:1 实时贴合零追尾滞后
    val queueOpen = nav.side == 2
    val queueAnim = animateFloatAsState(if (queueOpen) 1f else 0f, if (AnimStyle.off) androidx.compose.animation.core.snap() else spring(1f, Anim.stiff(Spring.StiffnessMediumLow)), label = "qOpen")
    var bodyW by remember { mutableStateOf(1080.dp) }
    var bodyH by remember { mutableStateOf(720.dp) }
    Box(Modifier.fillMaxSize().onSizeChanged {
        if (it.width > 0 && it.height > 0) with(dens) { bodyW = it.width.toDp(); bodyH = it.height.toDp() }
    }) {
    val sideKeys = PlayerLayout.keys(true, "side")
    val sideW0 = if (sideKeys.isEmpty()) 0.dp else 52.dp
    val chrome = 44.dp + 56.dp + sideW0 + 16.dp
    val hLimit = minOf(bodyH - 290.dp, 560.dp) * (UiPref.coverPct / 100f)
    val coverClosed = minOf(hLimit, bodyW * .5f - chrome).coerceAtLeast(200.dp)
    val coverOpened = minOf(hLimit, (bodyW - 363.dp) * .5f - chrome).coerceAtLeast(200.dp)
    val q = queueAnim.value
    val cover = coverClosed + (coverOpened - coverClosed) * q
    val leftW = cover + chrome + 56.dp
    val rightTarget = (bodyW - (if (queueOpen) 363.dp else 0.dp) - (if (queueOpen) coverOpened else coverClosed) - chrome - 56.dp).coerceAtLeast(160.dp)
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(leftW).fillMaxHeight().clipToBounds(), contentAlignment = Alignment.CenterStart) {
            Row(Modifier.padding(start = 56.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.width(cover + 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // 解码尺寸固定 560dp；接入 heroAnchor 支持从底部播放条直飞大封面
                Box(Modifier.onPlaced { val ny = it.positionInRoot().y; if (kotlin.math.abs(ny - coverY) > 1f) coverY = ny }.heroAnchor("now:${t.id}", t.cover, t.title, isTarget = true)) {
                    CoverRevealSwitch(t.cover, cover, t.title, decodeSize = 560.dp, revealKey = t.id)
                }
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
            // 自动隐藏：鼠标靠近（进入竖栏周围这块区域）才淡入，离开一会儿再淡出
            if (sideKeys.isNotEmpty()) {
                var near by remember { mutableStateOf(false) }
                val show = near || !UiPref.autoHideSide
                val a = animateFloatAsState(if (show) 1f else 0f, tween(if (show) 160 else 420, delayMillis = if (show) 0 else 900), label = "side")
                Box(Modifier.width(76.dp).pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            when (e.type) { androidx.compose.ui.input.pointer.PointerEventType.Enter, androidx.compose.ui.input.pointer.PointerEventType.Move -> near = true; androidx.compose.ui.input.pointer.PointerEventType.Exit -> near = false }
                        }
                    }
                }.padding(start = 8.dp, top = 80.dp, bottom = 4.dp, end = 24.dp).graphicsLayer { alpha = a.value }) {
                    Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) { SlotCol(true, "side", bx, 4.dp) }
                }
            }
            }
            // 收起播放页（也可以按 Esc）
            Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(44.dp).press { nav.player = false }, contentAlignment = Alignment.Center) { NIcon(NIcons.chevronDown, 20.dp, p.s) }
        }
        // 右栏：歌词；打开评论时换成评论（从右边滑进来）
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().onPlaced { val ny = it.positionInRoot().y; if (kotlin.math.abs(ny - rightY) > 1f) rightY = ny }) {
            AnimatedContent(nav.comments, Modifier.fillMaxSize(), transitionSpec = {
                if (targetState != null) (slideInHorizontally(Motion.page()) { it / 3 } + fadeIn(Motion.standard())) togetherWith fadeOut(Motion.quick())
                else fadeIn(Motion.standard()) togetherWith (slideOutHorizontally(Motion.standard()) { it / 3 } + fadeOut(Motion.quick()))
            }, label = "right") { ct ->
                if (ct != null) CommentsBody(ct) { nav.comments = null }
                // 歌词栏按目标宽度排版：队列展开 / 收起的动画过程中不重新换行，不会跳动
                else Box(Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start, unbounded = true).width(rightTarget)
                    .padding(top = if (coverY < 0) 24.dp else with(dens) { (coverY - rightY).coerceAtLeast(0f).toDp() - 12.dp }.coerceAtLeast(0.dp), end = 24.dp)) { Lyrics(t, showTr) }
            }
        }
        // 第三栏：播放队列（看歌词的同时打开），从右边展开
        AnimatedVisibility(nav.side == 2, Modifier.fillMaxHeight(),
            enter = expandHorizontally(Motion.hero(), expandFrom = Alignment.Start) + fadeIn(Motion.standard()),
            exit = shrinkHorizontally(Motion.standard(), shrinkTowards = Alignment.Start) + fadeOut(Motion.quick())) {
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
}
