package cc.catsuki.nekoplay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.unit.dp

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
                Sidebar()
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) { WideContent() }
                // 右栏（歌词 / 队列 / 评论）：从右边展开、收起
                androidx.compose.animation.AnimatedVisibility((nav.side != 0 || nav.comments != null) && st.current != null,
                    enter = androidx.compose.animation.expandHorizontally(spring(1f, Anim.stiff(androidx.compose.animation.core.Spring.StiffnessMediumLow)), expandFrom = Alignment.Start) + fadeIn(tween(200)),
                    exit = androidx.compose.animation.shrinkHorizontally(tween(220), shrinkTowards = Alignment.Start) + fadeOut(tween(160))) { SidePanel(st) }
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
        SideItem(NIcons.home, "主页", nav.stack.isEmpty() && nav.tab == 0) { nav.player = false; nav.stack.clear(); nav.tab = 0 }
        SideItem(NIcons.library, "曲库", nav.stack.isEmpty() && nav.tab == 1) { nav.player = false; nav.stack.clear(); nav.tab = 1 }
        SideItem(NIcons.search, "搜索", top is Page.Search) { nav.player = false; nav.stack.clear(); nav.push(Page.Search) }
        SideItem(NIcons.radio, "私人 FM", top is Page.Fm) { nav.player = false; nav.stack.clear(); nav.push(Page.Fm) }
        SideItem(NIcons.chart, "发现 · 排行榜", top is Page.Playlists && top.kind == "top") { nav.player = false; nav.stack.clear(); nav.push(Page.Playlists("top", "", "排行榜")) }
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
            items(pls.filter { !it.liked }, key = { "sp" + it.id }) { pl ->
                SideList(pl.name, (top as? Page.Detail)?.id == pl.id, { Cover(pl.cover, 32.dp, pl.name, decodeSize = 64.dp) }) { nav.stack.clear(); nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) }
            }
        }
        }
        }
        // 账号（桌面版在右上角标题栏里，这里不重复）
        if (!Plat.isDesktop) Row(Modifier.fillMaxWidth().height(64.dp).press { nav.stack.clear(); nav.push(if (prof != null) Page.User(prof!!.uid, prof!!.name) else Page.Login) }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Avatar(36.dp, 16); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { T(prof?.name ?: "好奇猫a", 14, w = 700); T(if (prof == null) "点击登录网易云" else "NETEASE", 11, p.s, mono = true) }
            Box(Modifier.size(36.dp).press { nav.stack.clear(); nav.push(Page.Settings) }, contentAlignment = Alignment.Center) { NIcon(NIcons.gear, 18.dp, p.s) }
        }
    }
}

@Composable private fun SideItem(icon: String, label: String, on: Boolean, onClick: () -> Unit) {
    val p = c
    Row(Modifier.fillMaxWidth().height(44.dp).press(onClick = onClick).background(if (on) p.p else Color.Transparent).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        NIcon(icon, 18.dp, if (on) Color.White else p.k); Spacer(Modifier.width(12.dp)); T(label, 15, if (on) Color.White else p.k, 700)
    }
}

@Composable private fun SideList(label: String, on: Boolean, cover: @Composable () -> Unit, onClick: () -> Unit) {
    val p = c
    Row(Modifier.fillMaxWidth().height(44.dp).press(onClick = onClick).background(if (on) p.hl else Color.Transparent).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        cover(); Spacer(Modifier.width(10.dp)); T(label, 14, if (on) p.p else p.k, if (on) 700 else 500)
    }
}

/** 内容区：没有子页面时是主页 / 曲库，有就显示最上面那个子页面（淡入淡出切换） */
@Composable private fun WideContent() {
    val nav = LocalNav.current
    val key: Any = nav.stack.lastOrNull() ?: "tab${nav.tab}"
    AnimatedContent(key, Modifier.fillMaxSize(), transitionSpec = {
        fadeIn(tween(160)) togetherWith fadeOut(tween(110))
    }, label = "wide") { k ->
        Box(Modifier.fillMaxSize()) {
            when (k) {
                is Page -> Box(Modifier.fillMaxSize().background(c.page)) { BgLayer(); PageContent(k) }
                "tab1" -> Centered { LibraryPage() }
                else -> Centered { HomePage() }
            }
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
    Column(Modifier.width(380.dp).fillMaxHeight().background(p.page.copy(alpha = .6f)).drawBehind {
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
        if (nav.side == 1) Box(Modifier.weight(1f).fillMaxWidth()) { Lyrics(t, true) }
        else Box(Modifier.weight(1f).fillMaxWidth()) { QueueList(st) }
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
            itemsIndexed(st.queue, key = { i, x -> "$i${x.id}" }) { i, x ->
                TrackRow(x, i == st.index, null) { Engine.playAt(i) }
            }
        }
        LocateBtn(ls, st.index)
        }
    }
}

/** 底部播放条（横跨整个窗口） */
@Composable private fun PlayerBar() {
    val nav = LocalNav.current
    val p = c
    val st by Engine.state.collectAsState()
    val liked by Store.likedIds.collectAsState()
    val t = st.current
    Row(Modifier.fillMaxWidth().height(84.dp).background(barBg()).drawBehind {
        if (!Look.flat) drawRect(p.k, Offset.Zero, Size(size.width, 3.dp.toPx()))
    }.padding(top = 3.dp, start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        // 左：封面 + 歌名
        Row(Modifier.width(300.dp).press { if (t != null) { nav.lyrics = false; nav.player = true } }, verticalAlignment = Alignment.CenterVertically) {
            Cover(t?.cover, 56.dp, t?.title ?: "")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T(t?.title ?: "还没有曲目", 15, w = 800, lh = 1.3f)
                T(t?.artist ?: "选一首歌开始播放", 12, p.s, lh = 1.3f, modifier = Modifier.press { if (t != null) openArtists(nav, t) })
            }
        }
        // 中：控制 + 进度
        Column(Modifier.weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).press { Engine.cycleMode(); nav.toast = listOf("列表循环", "单曲循环", "随机播放")[Engine.state.value.mode.ordinal] }, contentAlignment = Alignment.Center) {
                    NIcon(when (st.mode) { Mode.LOOP_ALL -> NIcons.modeSeq; Mode.LOOP_ONE -> NIcons.modeOne; Mode.SHUFFLE -> NIcons.modeShuffle }, 18.dp, p.s)
                }
                Spacer(Modifier.width(14.dp))
                Box(Modifier.size(36.dp).press { if (st.fm) Engine.fmTrash() else Engine.prev() }, contentAlignment = Alignment.Center) { NIcon(if (st.fm) NIcons.trash else NIcons.prev, 18.dp) }
                Spacer(Modifier.width(14.dp))
                Box(Modifier.size(40.dp).press(.94f) { Engine.toggle() }.background(p.p), contentAlignment = Alignment.Center) { NIcon(if (st.playing) NIcons.pause else NIcons.play, 18.dp, Color.White) }
                Spacer(Modifier.width(14.dp))
                Box(Modifier.size(36.dp).press { Engine.next() }, contentAlignment = Alignment.Center) { NIcon(NIcons.next, 18.dp) }
                Spacer(Modifier.width(14.dp))
                Box(Modifier.size(36.dp).press { if (t != null) trackActions(nav, t) }, contentAlignment = Alignment.Center) { NIcon(NIcons.more, 18.dp, p.s) }
            }
            Row(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val pos by Engine.position.collectAsState()
                T(fmt(pos), 11, p.p, mono = true, modifier = Modifier.width(44.dp))
                Box(Modifier.weight(1f)) { ScrubBar(t?.durationMs ?: 0, 14.dp, interactive = t != null) }
                T(fmt(t?.durationMs ?: 0), 11, p.s, mono = true, modifier = Modifier.width(44.dp).padding(start = 8.dp))
            }
        }
        // 右：喜欢 · 评论 · 歌词 · 队列 · 音量
        Row(Modifier.width(300.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val on = t != null && t.id in liked
            BarBtn(if (on) NIcons.heart else NIcons.heartLine, on) { if (t != null) Store.toggleLike(t) { nav.toast = it } }
            BarBtn(NIcons.comment, nav.comments != null) { if (t != null) nav.comments = if (nav.comments != null) null else t }
            if (Features.on("desk")) BarText("桌", DeskLyric.on) { DeskLyric.on = !DeskLyric.on; DeskLyric.save(); nav.toast = if (DeskLyric.on) "已打开桌面歌词" else "已关闭桌面歌词" }
            BarText("词", nav.side == 1) { nav.side = if (nav.side == 1) 0 else 1 }
            BarBtn(NIcons.queue, nav.side == 2) { nav.side = if (nav.side == 2) 0 else 2 }
            Spacer(Modifier.width(8.dp))
            VolumeBar()
        }
    }
}

@Composable private fun BarBtn(icon: String, on: Boolean, onClick: () -> Unit) =
    Box(Modifier.size(38.dp).press(onClick = onClick), contentAlignment = Alignment.Center) { NIcon(icon, 18.dp, if (on) c.p else c.k) }

@Composable private fun BarText(s: String, on: Boolean, onClick: () -> Unit) =
    Box(Modifier.size(38.dp).press(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).background(if (on) c.p else Color.Transparent).nbf(1.dp, c.k), contentAlignment = Alignment.Center) { T(s, 12, if (on) Color.White else c.k, 700) }
    }

/** 全局音量（音量条和快捷键共用） */
object Vol {
    var v by mutableFloatStateOf(1f)
    fun load() { v = Plat.prefs("ui").getFloat("vol", 1f); runCatching { Plat.impl.audio.volume = v } }
    fun set(x: Float, save: Boolean = true) { v = x.coerceIn(0f, 1f); Plat.impl.audio.volume = v; if (save) Plat.prefs("ui").edit().putFloat("vol", v).apply() }
}

/** 音量：点或拖动设置（存起来，下次打开沿用） */
@Composable private fun VolumeBar() {
    val p = c
    var v by Vol::v
    Box(Modifier.width(90.dp).height(24.dp).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(); val ch = e.changes.firstOrNull() ?: continue
                if (ch.pressed) { Vol.set((ch.position.x / size.width).coerceIn(0f, 1f), save = false); ch.consume() }
                else if (ch.previousPressed) Vol.set(Vol.v)
            }
        }
    }.drawBehind {
        val y = size.height / 2; val h = 4.dp.toPx()
        drawRect(p.l3, Offset(0f, y - h / 2), Size(size.width, h))
        drawRect(p.k, Offset(0f, y - h / 2), Size(size.width * v, h))
        val s = 10.dp.toPx(); drawRect(p.k, Offset((size.width * v - s / 2).coerceIn(0f, size.width - s), y - s / 2), Size(s, s))
    })
}
