package cc.catsuki.nekoplay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * PC 宽屏布局（窗口 ≥ 900dp）：
 * ┌──────────┬──────────────────────────┬─────────────┐
 * │ 侧边栏    │ 内容区（主页 / 曲库 / 子页面） │ 歌词 / 队列  │
 * ├──────────┴──────────────────────────┴─────────────┤
 * │ 底部播放条：封面 · 歌名 | 控制 + 进度 | 喜欢 评论 歌词 队列 音量 │
 * └────────────────────────────────────────────────────┘
 * 页面组件和手机版是同一套，只是摆放方式不同。
 */
@Composable fun WideLayout() {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    // 手机版里「打开队列」的操作，在宽屏上改为打开右侧队列面板
    LaunchedEffect(nav.queue) { if (nav.queue) { nav.side = if (nav.side == 2) 0 else 2; nav.queue = false } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                val showSidebar = !(nav.stack.lastOrNull() == Page.CoverFlow && nav.hideSidebar)
                AnimatedVisibility(showSidebar,
                    enter = expandHorizontally(Motion.page()) + fadeIn(Motion.standard()),
                    exit = shrinkHorizontally(Motion.page()) + fadeOut(Motion.quick())) { Sidebar() }
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    WideContent()
                    // 右栏（歌词 / 队列 / 评论）：纯 GPU 位移滑入滑出，不改变主内容区宽度，避免每帧全页重排掉帧
                    androidx.compose.animation.AnimatedVisibility((nav.side != 0 || nav.comments != null) && st.current != null,
                        Modifier.align(Alignment.CenterEnd),
                        enter = androidx.compose.animation.slideInHorizontally(Motion.page()) { it } + fadeIn(Motion.standard()),
                        exit = androidx.compose.animation.slideOutHorizontally(Motion.standard()) { it } + fadeOut(Motion.quick())) { SidePanel(st) }
                }
            }
            PlayerBar()
        }
        PlayerOverlay()
    }
}

@Composable private fun Sidebar() {
    val nav = LocalNav.current
    val p = c
    val pls by Store.playlists.collectAsState()
    val prof by Store.profile.collectAsState()
    val rec by Store.recents.collectAsState()
    val liked = pls.firstOrNull { it.liked }
    val top = nav.stack.lastOrNull()
    Column(Modifier.width(232.dp).fillMaxHeight().background(barBg()).drawBehind {
        if (!Look.flat) drawRect(p.k, Offset(size.width - 3.dp.toPx(), 0f), Size(3.dp.toPx(), size.height))
    }.padding(end = 3.dp)) {
        // 桌面版的名字在标题栏里，侧边栏就不重复了
        if (!Plat.isDesktop) Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            T("NEKOPLAY", Fs.HEAD, w = 900, ls = .02f, modifier = Modifier.press { nav.player = false; nav.stack.clear(); nav.tab = 0 })
        } else Spacer(Modifier.height(10.dp))
        val allPlugs by Plugins.list.collectAsState()
        val cfOn = remember(allPlugs, Plugins.off) { Plugins.hasFeature("coverflow") }
        val navIdx = when {
            nav.stack.isEmpty() && nav.tab == 0 -> 0
            nav.stack.isEmpty() && nav.tab == 1 -> 1
            top is Page.Search -> 2
            top is Page.Fm -> 3
            top is Page.Playlists && top.kind == "top" -> 4
            cfOn && top is Page.CoverFlow -> 5
            else -> -1
        }
        val sliding = !AnimStyle.off
        val indY = animateFloatAsState(if (navIdx >= 0) navIdx.toFloat() else 0f, Motion.standard(), label = "sby")
        val indA = animateFloatAsState(if (navIdx >= 0) 1f else 0f, Motion.quick(), label = "sba")
        Box(Modifier.fillMaxWidth()) {
            // 连贯滑动的选中高亮块：切菜单时蓝色底块顺滑滑到新位置，由统一 Motion 令牌驱动
            if (sliding) Box(Modifier.fillMaxWidth().height(44.dp).graphicsLayer {
                translationY = indY.value * 44.dp.toPx()
                alpha = indA.value
            }.background(p.p))
            Column(Modifier.fillMaxWidth()) {
                SideItem(NIcons.home, "主页", navIdx == 0, sliding) { nav.player = false; nav.stack.clear(); nav.tab = 0 }
                SideItem(NIcons.library, "曲库", navIdx == 1, sliding) { nav.player = false; nav.stack.clear(); nav.tab = 1 }
                SideItem(NIcons.search, "搜索", navIdx == 2, sliding) { nav.player = false; nav.stack.clear(); nav.push(Page.Search) }
                SideItem(NIcons.radio, "私人 FM", navIdx == 3, sliding) { nav.player = false; nav.stack.clear(); nav.push(Page.Fm) }
                SideItem(NIcons.chart, "发现 · 排行榜", navIdx == 4, sliding) { nav.player = false; nav.stack.clear(); nav.push(Page.Playlists("top", "", "排行榜")) }
                if (cfOn) SideItem(NIcons.disc, "封面墙", navIdx == 5, sliding) { nav.player = false; nav.stack.clear(); nav.push(Page.CoverFlow) }
            }
        }
        Spacer(Modifier.height(14.dp))
        // 歌单列表一直显示（不再和曲库联动）
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 18.dp)) { TagRow("PLAYLISTS", 0.dp) }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (liked != null) item { SideList("我喜欢", (top as? Page.Detail)?.id == liked.id, { LikedCover(liked.cover, 32.dp) }) { nav.stack.clear(); nav.push(Page.Detail("liked", liked.id, "我喜欢", liked.cover)) } }
            item { SideList("最近播放", (top as? Page.Detail)?.kind == "recent", { RecentCover(rec.firstOrNull()?.cover, 32.dp) }) { nav.stack.clear(); nav.push(Page.Detail("recent", "recent", "最近播放")) } }
            if (Features.on("local")) item { SideList("本地音乐", top == Page.Local, { IconCover(NIcons.disc, 32.dp, iconSize = 16.dp) }) { nav.stack.clear(); nav.push(Page.Local) } }
            if (Features.on("dl")) item { SideList("本地下载", top == Page.DlList, { IconCover(NIcons.download, 32.dp, iconSize = 16.dp) }) { nav.stack.clear(); nav.push(Page.DlList) } }
            items(pls.filter { !it.liked }, key = { "sp" + it.id }, contentType = { "pl" }) { pl ->
                SideList(pl.name, (top as? Page.Detail)?.id == pl.id, { Cover(pl.cover, 32.dp, pl.name, decodeSize = 64.dp) }) { nav.stack.clear(); nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) }
            }
        }
        }
        }
        // 账号（桌面版在右上角标题栏里，这里不重复）
        if (!Plat.isDesktop) Row(Modifier.fillMaxWidth().height(64.dp).press { nav.stack.clear(); nav.push(if (prof != null) Page.User(prof!!.uid, prof!!.name) else Page.Login) }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Avatar(36.dp, 16); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { T(prof?.name ?: "本地用户", 14, w = 700); T(if (prof == null) "点击登录" else (Plugins.main()?.name ?: "ACCOUNT").uppercase(), 11, p.s, mono = true) }
            Box(Modifier.size(36.dp).press { nav.stack.clear(); nav.push(Page.Settings) }, contentAlignment = Alignment.Center) { NIcon(NIcons.gear, 18.dp, p.s) }
        }
    }
}

@Composable private fun SideItem(icon: String, label: String, on: Boolean, slidingBg: Boolean = false, onClick: () -> Unit) {
    val p = c
    val deskHover = Plat.isDesktop && !AnimStyle.off && (MotionEffects.hoverTint || MotionEffects.hoverShiftDp > 0.1f)
    val src = remember { MutableInteractionSource() }
    val hov = if (deskHover) remember(src) { Animatable(0f) } else null
    if (deskHover && hov != null) {
        LaunchedEffect(src) {
            var j: kotlinx.coroutines.Job? = null
            src.interactions.collect { ev ->
                when (ev) {
                    is HoverInteraction.Enter -> { j?.cancel(); j = launch { hov.animateTo(1f, Motion.quick()) } }
                    is HoverInteraction.Exit -> { j?.cancel(); j = launch { hov.animateTo(0f, Motion.standard()) } }
                }
            }
        }
    }
    val shiftDp = if (deskHover) MotionEffects.hoverShiftDp else 0f
    val showTint = deskHover && MotionEffects.hoverTint
    Row(Modifier.fillMaxWidth().height(44.dp).then(if (deskHover) Modifier.hoverable(src) else Modifier).press(interactionSource = src, onClick = onClick)
        .drawBehind {
            if (on && !slidingBg) drawRect(p.p)
            else if (showTint && !on && hov != null && hov.value > 0.01f) drawRect(p.k.copy(alpha = .06f * hov.value))
        }
        .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.then(if (hov != null && shiftDp > 0.1f && !on) Modifier.graphicsLayer { val v = hov.value; if (v > 0.001f) translationX = (v * shiftDp).dp.toPx() } else Modifier), verticalAlignment = Alignment.CenterVertically) {
            NIcon(icon, 18.dp, if (on) Color.White else p.k); Spacer(Modifier.width(12.dp)); T(label, 15, if (on) Color.White else p.k, 700)
        }
    }
}

@Composable private fun SideList(label: String, on: Boolean, cover: @Composable () -> Unit, onClick: () -> Unit) {
    val p = c
    val deskHover = Plat.isDesktop && !AnimStyle.off && (MotionEffects.hoverTint || MotionEffects.hoverShiftDp > 0.1f)
    val src = remember { MutableInteractionSource() }
    val hov = if (deskHover) remember(src) { Animatable(0f) } else null
    if (deskHover && hov != null) {
        LaunchedEffect(src) {
            var j: kotlinx.coroutines.Job? = null
            src.interactions.collect { ev ->
                when (ev) {
                    is HoverInteraction.Enter -> { j?.cancel(); j = launch { hov.animateTo(1f, Motion.quick()) } }
                    is HoverInteraction.Exit -> { j?.cancel(); j = launch { hov.animateTo(0f, Motion.standard()) } }
                }
            }
        }
    }
    val shiftDp = if (deskHover) MotionEffects.hoverShiftDp * .85f else 0f
    val showTint = deskHover && MotionEffects.hoverTint
    Row(Modifier.fillMaxWidth().height(44.dp).then(if (deskHover) Modifier.hoverable(src) else Modifier).press(interactionSource = src, onClick = onClick)
        .drawBehind {
            if (on) drawRect(p.hl)
            else if (showTint && hov != null && hov.value > 0.01f) drawRect(p.k.copy(alpha = .05f * hov.value))
        }
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.then(if (hov != null && shiftDp > 0.1f) Modifier.graphicsLayer { val v = hov.value; if (v > 0.001f) translationX = (v * shiftDp).dp.toPx() } else Modifier), verticalAlignment = Alignment.CenterVertically) {
            cover(); Spacer(Modifier.width(10.dp)); T(label, 14, if (on) p.p else p.k, if (on) 700 else 500)
        }
    }
}

/** 内容区：主页 / 曲库常驻保活；osu! 模式下采用强连贯的空间视差推拉（前进右进左退、返回左回右出、Tab 双画板横滑） */
@Composable private fun WideContent() {
    val nav = LocalNav.current
    val top = nav.stack.lastOrNull()
    val showHome = top == null && nav.tab == 0
    val showLib = top == null && nav.tab == 1
    var libBuilt by remember { mutableStateOf(false) }
    if (showLib) libBuilt = true
    val spatial = MotionEffects.pageScale && !AnimStyle.off
    // 跟踪前进 / 后退方向，用于空间视差模式下子页面之间的连贯左右推拉
    var lastDepth by remember { mutableIntStateOf(0) }
    val forward = nav.stack.size >= lastDepth
    SideEffect { lastDepth = nav.stack.size }

    val spec: androidx.compose.animation.core.FiniteAnimationSpec<Float> = Motion.page()
    val homeAlpha = animateFloatAsState(if (showHome) 1f else 0f, spec, label = "wh")
    val libAlpha = animateFloatAsState(if (showLib) 1f else 0f, spec, label = "wl")
    val tabSlide = if (spatial) animateFloatAsState(if (nav.tab == 1) 1f else 0f, spec, label = "wts") else null
    val subDepth = if (spatial) animateFloatAsState(if (top != null) 1f else 0f, spec, label = "wsd") else null

    var wPx by remember { mutableFloatStateOf(1f) }
    Box(Modifier.fillMaxSize().onSizeChanged { wPx = it.width.toFloat().coerceAtLeast(1f) }) {
        // 主页常驻：空间视差开启时切曲库向左顺滑推开，进子页面向左退 14% 并微缩形成视差衔接
        Box(Modifier.fillMaxSize().layout { m, cs -> val pl = m.measure(cs); layout(pl.width, pl.height) { if (homeAlpha.value > 0.01f) pl.place(0, 0) } }.graphicsLayer {
            alpha = homeAlpha.value
            if (tabSlide != null && subDepth != null) {
                translationX = -wPx * .22f * tabSlide.value - wPx * .14f * subDepth.value
                val s = 1f - .025f * subDepth.value
                scaleX = s; scaleY = s
            }
        }) {
            Centered { HomePage() }
        }
        if (libBuilt) {
            Box(Modifier.fillMaxSize().layout { m, cs -> val pl = m.measure(cs); layout(pl.width, pl.height) { if (libAlpha.value > 0.01f) pl.place(0, 0) } }.graphicsLayer {
                alpha = libAlpha.value
                if (tabSlide != null && subDepth != null) {
                    translationX = wPx * .22f * (1f - tabSlide.value) - wPx * .14f * subDepth.value
                    val s = 1f - .025f * subDepth.value
                    scaleX = s; scaleY = s
                }
            }) {
                Centered { LibraryPage() }
            }
        }
        AnimatedContent(top, Modifier.fillMaxSize(), transitionSpec = {
            if (AnimStyle.off) fadeIn(androidx.compose.animation.core.snap()) togetherWith fadeOut(androidx.compose.animation.core.snap())
            else if (spatial) {
                val scIn = if (MotionEffects.pageScale) scaleIn(Motion.page(), initialScale = 0.985f) else fadeIn(Motion.page())
                val scOut = if (MotionEffects.pageScale) scaleOut(Motion.standard(), targetScale = 0.99f) else fadeOut(Motion.quick())
                if (forward) {
                    (slideInHorizontally(Motion.page()) { (it * .22f).toInt() } + fadeIn(Motion.page()) + scIn) togetherWith
                        (slideOutHorizontally(Motion.standard()) { -(it * .12f).toInt() } + fadeOut(Motion.quick()) + scOut)
                } else {
                    (slideInHorizontally(Motion.page()) { -(it * .12f).toInt() } + fadeIn(Motion.page()) + scIn) togetherWith
                        (slideOutHorizontally(Motion.standard()) { (it * .22f).toInt() } + fadeOut(Motion.quick()) + scOut)
                }
            } else fadeIn(Motion.standard()) togetherWith fadeOut(Motion.quick())
        }, label = "widePage") { k ->
            if (k != null) Box(Modifier.fillMaxSize()) { PageContent(k) }
        }
    }
}

/** 主页 / 曲库在大屏上限制最大宽度并居中，避免一行拉得太长 */
@Composable private fun Centered(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = 980.dp).fillMaxHeight()) { content() } }

/** 右侧面板：歌词或播放队列 */
@Composable private fun SidePanel(st: PlayState) {
    val nav = LocalNav.current
    val p = c
    val t = st.current ?: return
    Column(Modifier.width(380.dp).fillMaxHeight().background(p.page.copy(alpha = .94f)).drawBehind {
        if (!Look.flat) drawRect(p.k, Offset.Zero, Size(3.dp.toPx(), size.height))
    }.padding(start = 3.dp)) {
        // 打开评论时，右栏换成评论；关掉回到歌词 / 队列
        nav.comments?.let { ct -> CommentsBody(ct) { nav.comments = null }; return@Column }
        if (nav.side == 0) return@Column
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(1 to "LYRICS", 2 to "QUEUE").forEach { (k, l) ->
                Box(Modifier.padding(end = 8.dp).press { nav.side = k }.background(if (nav.side == k) p.k else Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 12.dp, vertical = 5.dp)) {
                    T(l, 12, if (nav.side == k) p.tagFg else p.k, 700, mono = true)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(40.dp).press { nav.side = 0 }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 12.dp) }
        }
        AnimatedContent(nav.side, Modifier.weight(1f).fillMaxWidth(), transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            val ms = Motion.standardMs
            val ease = AnimStyle.activeEasing
            (slideInHorizontally(tween(ms, easing = ease)) { (it * .18f * dir).toInt() } + fadeIn(tween(ms, easing = ease))) togetherWith
                (slideOutHorizontally(tween(ms, easing = ease)) { (-it * .14f * dir).toInt() } + fadeOut(tween((ms * .7f).toInt())))
        }, label = "sideTab") { tab ->
            if (tab == 1) Box(Modifier.fillMaxSize()) { Lyrics(t, true) }
            else Box(Modifier.fillMaxSize()) { QueueList(st) }
        }
    }
}

/** 播放队列列表（右栏 / 播放页第三栏共用） */
@Composable fun QueueList(st: PlayState) {
    val nav = LocalNav.current
    val p = c
    Column(Modifier.fillMaxSize()) {
        val ls = rememberLazyListState((st.index - 2).coerceAtLeast(0))
        Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T("${st.queue.size} 首", 12, p.s, mono = true, modifier = Modifier.weight(1f)); LinkBtn("清空待播") { Engine.clearUpcoming() }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), ls, contentPadding = PaddingValues(start = 18.dp, end = 10.dp, bottom = 12.dp)) {
            itemsIndexed(st.queue, key = { i, x -> "$i${x.id}" }, contentType = { _, _ -> "track" }) { i, x ->
                TrackRow(x, i == st.index, null) { Engine.playAt(i) }
            }
        }
        LocateBtn(ls, st.index)
        }
    }
}

/** 底部播放条（横跨整个窗口） */
