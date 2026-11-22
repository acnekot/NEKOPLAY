package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.ui.settings.downloadSheet

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.focusRequester
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState

// ======================= 操作菜单（底部弹出） =======================
@Composable fun PlaylistsPage(pg: Page.Playlists) {
    val nav = LocalNav.current
    var err by remember { mutableStateOf<String?>(null) }
    val list by produceState<List<Playlist>?>(null, pg) {
        try { value = when (pg.kind) { "simi" -> Ncm.simiPlaylists(pg.arg); else -> loadPlaylists(pg.kind, pg.arg) } } catch (e: Exception) { err = e.message; value = emptyList() }
    }
    val p = c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)) {
        item {
            Column {
                Tag("PLAYLISTS"); Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) { T(pg.title, Fs.HERO, w = 800, lh = 1.3f, modifier = Modifier.weight(1f)); T("${two(list?.size ?: 0)} 个", 13, p.s, mono = true, modifier = Modifier.padding(bottom = 6.dp)) }
                Spacer(Modifier.height(10.dp)); Rule()
                when { err != null -> T("加载失败：$err", 13, p.bad, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(vertical = 24.dp))
                    list == null -> T("LOADING", 13, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 24.dp))
                    list!!.isEmpty() -> T("EMPTY", 13, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 24.dp)) }
            }
        }
        items(list ?: emptyList(), key = { it.id }) { pl -> ProfileRow({ Cover(pl.cover, 56.dp, pl.name) }, pl.name, if (pl.desc.isNotEmpty()) pl.desc else "${two(pl.count)} 首") { nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) } }
    }
}

/** 一排可选的分类小方块 */
/** 多选的标签行：点一下开 / 关 */

@Composable fun SquarePage() {
    val nav = LocalNav.current
    val p = c
    val cats by produceState(listOf("全部")) { runCatching { value = Ncm.categories() } }
    var sel by remember { mutableIntStateOf(0) }
    val cat = cats.getOrElse(sel) { "全部" }
    val list = remember(cat) { mutableStateListOf<Playlist>() }
    var more by remember(cat) { mutableStateOf(true) }
    var busy by remember(cat) { mutableStateOf(false) }
    var err by remember(cat) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun load() {
        if (busy || !more) return
        busy = true
        scope.launch { try { val (l, m) = Ncm.square(cat, list.size); list += l.filter { x -> list.none { it.id == x.id } }; more = m && l.isNotEmpty() } catch (e: Exception) { err = e.message; more = false }; busy = false }
    }
    LaunchedEffect(cat) { load() }
    val ls = rememberLazyListState()
    val end = ls.nearEnd()
    LaunchedEffect(end, list.size) { if (end && list.isNotEmpty()) load() }
    LazyColumn(Modifier.fillMaxSize(), ls, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)) {
        item {
            Column {
                Tag("PLAYLISTS"); Spacer(Modifier.height(4.dp))
                T("歌单广场", Fs.HERO, w = 800, lh = 1.3f); Spacer(Modifier.height(12.dp))
                ChipRow(cats, sel) { sel = it }
                Spacer(Modifier.height(12.dp)); Rule()
            }
        }
        items(list, key = { "sq" + it.id }) { pl -> ProfileRow({ Cover(pl.cover, 56.dp, pl.name) }, pl.name, "${two(pl.count)} 首") { nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) } }
        item { T(when { err != null -> "加载失败：$err"; busy -> "LOADING"; !more -> "END"; else -> "" }, 12, p.s, mono = true, ls = .08f, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(vertical = 20.dp)) }
    }
}

// ======================= 新碟上架 =======================
@Composable fun NewAlbumsPage() {
    val nav = LocalNav.current
    val p = c
    val areas = listOf("ALL" to "全部", "ZH" to "华语", "EA" to "欧美", "KR" to "韩国", "JP" to "日本")
    var sel by remember { mutableIntStateOf(0) }
    val area = areas[sel].first
    val list = remember(area) { mutableStateListOf<Album>() }
    var more by remember(area) { mutableStateOf(true) }
    var busy by remember(area) { mutableStateOf(false) }
    var err by remember(area) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun load() {
        if (busy || !more) return
        busy = true
        scope.launch { try { val l = Ncm.newAlbums(area, list.size); list += l.filter { x -> list.none { it.id == x.id } }; more = l.size >= 30 } catch (e: Exception) { err = e.message; more = false }; busy = false }
    }
    LaunchedEffect(area) { load() }
    val ls = rememberLazyListState()
    val end = ls.nearEnd()
    LaunchedEffect(end, list.size) { if (end && list.isNotEmpty()) load() }
    LazyColumn(Modifier.fillMaxSize(), ls, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)) {
        item {
            Column {
                Tag("NEW ALBUMS"); Spacer(Modifier.height(4.dp))
                T("新碟上架", Fs.HERO, w = 800, lh = 1.3f); Spacer(Modifier.height(12.dp))
                ChipRow(areas.map { it.second }, sel) { sel = it }
                Spacer(Modifier.height(12.dp)); Rule()
            }
        }
        items(list, key = { "na" + it.id }) { al -> ProfileRow({ Cover(al.pic, 56.dp, al.name) }, al.name, listOf(al.artist, al.year).filter { it.isNotEmpty() }.joinToString(" · ")) { nav.push(Page.Album(al.id, al.name, al.pic)) } }
        item { T(when { err != null -> "加载失败：$err"; busy -> "LOADING"; !more -> "END"; else -> "" }, 12, p.s, mono = true, ls = .08f, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(vertical = 20.dp)) }
    }
}

// ======================= 历史日推 =======================
@Composable fun HistoryPage() {
    val nav = LocalNav.current
    val p = c
    var err by remember { mutableStateOf<String?>(null) }
    val dates by produceState<List<String>?>(null) { try { value = Ncm.historyDates() } catch (e: Exception) { err = e.message; value = emptyList() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)) {
        item {
            Column {
                Tag("HISTORY"); Spacer(Modifier.height(4.dp))
                T("历史日推", Fs.HERO, w = 800, lh = 1.3f); T("网易云只保留最近几天的每日推荐", 13, p.s)
                Spacer(Modifier.height(12.dp)); Rule()
                when { err != null -> T("加载失败：$err", 13, p.bad, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(vertical = 24.dp))
                    dates == null -> T("LOADING", 13, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 24.dp))
                    dates!!.isEmpty() -> T("EMPTY", 13, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 24.dp)) }
            }
        }
        items(dates ?: emptyList(), key = { "d$it" }) { d ->
            Row(Modifier.fillMaxWidth().height(64.dp).press { nav.push(Page.Detail("hist", d, "日推 $d")) }.bottomLine(), verticalAlignment = Alignment.CenterVertically) {
                T(d, 20, p.p, 600, mono = true, modifier = Modifier.weight(1f)); NIcon(NIcons.chevronRight, 16.dp)
            }
        }
    }
}

/** 其它歌单来源（第 2 批起：歌单广场等） */
suspend fun loadPlaylists(kind: String, arg: String): List<Playlist> = when (kind) {
    "top" -> Ncm.toplists(); "rec" -> Ncm.recPlaylists(); else -> throw NcmError("暂不支持")
}


/** 关注 / 收藏 按钮：先改界面，失败再改回来 */
/** 收藏 / 关注按钮：状态还没查到时先显示「+ 收藏」（不能点），查到是已收藏再渐变过去，不会突然冒出来 */

@Composable fun SubsPage(pg: Page.Subs) {
    val nav = LocalNav.current
    val p = c
    var err by remember { mutableStateOf<String?>(null) }
    val albums by produceState<List<Album>?>(null, pg) { if (pg.kind == "album") try { value = Ncm.subAlbums() } catch (e: Exception) { err = e.message; value = emptyList() } }
    val artists by produceState<List<Artist>?>(null, pg) { if (pg.kind != "album") try { value = Ncm.subArtists() } catch (e: Exception) { err = e.message; value = emptyList() } }
    val n = if (pg.kind == "album") albums?.size else artists?.size
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)) {
        item {
            Column {
                Tag(if (pg.kind == "album") "ALBUMS" else "ARTISTS"); Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) { T(if (pg.kind == "album") "收藏的专辑" else "关注的歌手", Fs.HERO, w = 800, lh = 1.3f, modifier = Modifier.weight(1f)); T("${two(n ?: 0)} 个", 13, p.s, mono = true, modifier = Modifier.padding(bottom = 6.dp)) }
                Spacer(Modifier.height(10.dp)); Rule()
                when { err != null -> T("加载失败：$err", 13, p.bad, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(vertical = 24.dp))
                    n == null -> T("LOADING", 13, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 24.dp))
                    n == 0 -> T("EMPTY", 13, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 24.dp)) }
            }
        }
        items(albums ?: emptyList(), key = { "a" + it.id }) { al -> ProfileRow({ Cover(al.pic, 56.dp, al.name) }, al.name, listOf(al.artist, if (al.size > 0) "${al.size} 首" else "").filter { it.isNotEmpty() }.joinToString(" · ")) { nav.push(Page.Album(al.id, al.name, al.pic)) } }
        items(artists ?: emptyList(), key = { "r" + it.id }) { ar -> ProfileRow({ Cover(ar.pic, 56.dp, ar.name) }, ar.name, "${two(ar.albums)} 张专辑") { nav.push(Page.Artist(ar.id, ar.name)) } }
    }
}

// ======================= 听歌排行 =======================
@Composable fun RankPage(pg: Page.Rank) {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    val p = c
    var sel by remember { mutableIntStateOf(0) }
    var err by remember(sel) { mutableStateOf<String?>(null) }
    val data by produceState<List<Pair<Track, Int>>?>(null, pg, sel) { value = null; try { value = Ncm.playRecord(pg.uid, sel == 0) } catch (e: Exception) { err = e.message; value = emptyList() } }
    val list = data?.map { it.first } ?: emptyList()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)) {
        item {
            Column {
                Tag("RANKING"); Spacer(Modifier.height(4.dp))
                T("听歌排行", Fs.HERO, w = 800, lh = 1.3f); T(pg.name, 13, p.s)
                Spacer(Modifier.height(12.dp)); ChipRow(listOf("最近一周", "所有时间"), sel) { sel = it }
                Spacer(Modifier.height(12.dp))
                ActionRow { PrimaryBar("播放", NIcons.play, Modifier.weight(1f)) { Engine.playList(list, 0) } }
                Spacer(Modifier.height(12.dp)); Rule()
                when { err != null -> T("加载失败：$err（对方可能隐藏了排行）", 13, p.bad, lines = 3, modifier = Modifier.padding(vertical = 24.dp))
                    data == null -> T("LOADING", 13, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 24.dp))
                    data!!.isEmpty() -> T("EMPTY", 13, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 24.dp)) }
            }
        }
        itemsIndexed(data ?: emptyList(), key = { i, x -> "$i${x.first.id}" }) { i, (t, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(two(i + 1), 13, if (i < 3) p.p else p.s, 700, mono = true, modifier = Modifier.width(30.dp))
                Box(Modifier.weight(1f)) { TrackRow(t, st.current?.id == t.id, if (sel == 0) "$n 次" else "$n 分", onLong = { trackActions(nav, t) }) { Engine.playList(list, i) } }
            }
        }
    }
}


// ======================= MV =======================
@Composable fun MvCard(mv: Mv, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val p = c
    Column(Modifier.width(width).press(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).background(p.ph).nb(1.dp, p.k)) {
            if (mv.cover != null) AsyncImage(img(mv.cover, 480), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(Modifier.align(Alignment.BottomEnd).background(p.k).padding(horizontal = 6.dp, vertical = 2.dp)) { T(if (mv.durationMs > 0) fmt(mv.durationMs) else "MV", 11, p.tagFg, mono = true) }
        }
        Spacer(Modifier.height(6.dp)); T(mv.name, 15, w = 700, lh = 1.35f); T(mv.artist, 12, p.s)
    }
}

@Composable fun MvsPage() {
    val nav = LocalNav.current
    val p = c
    var sel by remember { mutableIntStateOf(0) }
    var err by remember(sel) { mutableStateOf<String?>(null) }
    val list by produceState<List<Mv>?>(null, sel) { value = null; try { value = if (sel == 0) Ncm.recMvs() else Ncm.topMvs() } catch (e: Exception) { err = e.message; value = emptyList() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column {
                Tag("MUSIC VIDEO"); Spacer(Modifier.height(4.dp)); T("MV 精选", Fs.HERO, w = 800, lh = 1.3f)
                Spacer(Modifier.height(12.dp)); ChipRow(listOf("推荐", "排行榜"), sel) { sel = it }
                Spacer(Modifier.height(12.dp)); Rule()
                when { err != null -> T("加载失败：$err", 13, p.bad, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(top = 20.dp))
                    list == null -> T("LOADING", 13, p.s, mono = true, ls = .08f, modifier = Modifier.padding(top = 20.dp)) }
            }
        }
        items(list ?: emptyList(), key = { "m" + it.id }) { mv -> BoxWithConstraints { MvCard(mv, maxWidth) { nav.push(Page.Mv(mv.id, mv.name, mv.artist)) } } }
    }
}

/** MV 播放页：视频交给平台播放器（安卓 ExoPlayer / 桌面 mpv），进来时把音乐暂停 */
@Composable fun MvPage(pg: Page.Mv) {
    val p = c
    var err by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    val ctl = remember { VideoControl() }
    LaunchedEffect(pg.id) {
        if (Engine.isPlaying) Engine.pause()
        try { url = Ncm.mvUrl(pg.id) } catch (e: Exception) { err = e.message }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).background(Color.Black).press { ctl.toggle() }, contentAlignment = Alignment.Center) {
            Plat.impl.VideoPlayer(url, Modifier.matchParentSize(), { pl, ps, d, e -> playing = pl; pos = ps; dur = d; if (e != null) err = e }, ctl)
            if (err != null) T(err!!, 13, Color.White, lines = 3, modifier = Modifier.padding(18.dp))
            else if (!playing) NIcon(NIcons.play, 40.dp, Color.White)
        }
        // 进度：点按跳转
        Box(Modifier.fillMaxWidth().height(20.dp).pointerInput(dur) {
            detectTapGestures { o -> if (dur > 0) ctl.seek((o.x / size.width * dur).toLong()) }
        }.drawBehind {
            val y = size.height / 2
            drawRect(p.l3, androidx.compose.ui.geometry.Offset(0f, y - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 4.dp.toPx()))
            if (dur > 0) drawRect(p.p, androidx.compose.ui.geometry.Offset(0f, y - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width * pos / dur, 4.dp.toPx()))
        })
        Column(Modifier.padding(horizontal = 18.dp)) {
            Row { T(fmt(pos), 12, p.p, mono = true, modifier = Modifier.weight(1f)); T(fmt(dur), 12, p.s, mono = true) }
            Spacer(Modifier.height(14.dp)); Tag("MV"); Spacer(Modifier.height(8.dp))
            T(pg.name, Fs.PAGE, w = 800, lines = 3, lh = 1.25f); T(pg.artist, 15, p.s)
            Spacer(Modifier.height(16.dp))
            Row { PrimaryBar(if (playing) "暂停" else "播放", if (playing) NIcons.pause else NIcons.play, Modifier.weight(1f)) { ctl.toggle() } }
        }
    }
}

// ======================= 播客电台 =======================
@Composable fun RadiosPage() {
    val nav = LocalNav.current
    val p = c
    val list = remember { mutableStateListOf<Radio>() }
    var busy by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun load() {
        if (busy || !more) return
        busy = true
        scope.launch { try { val l = Ncm.hotRadios(list.size); list += l.filter { x -> list.none { it.id == x.id } }; more = l.size >= 30 } catch (e: Exception) { err = e.message; more = false }; busy = false }
    }
    LaunchedEffect(Unit) { load() }
    val ls = rememberLazyListState()
    val end = ls.nearEnd()
    LaunchedEffect(end, list.size) { if (end && list.isNotEmpty()) load() }
    LazyColumn(Modifier.fillMaxSize(), ls, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)) {
        item {
            Column {
                Tag("PODCAST"); Spacer(Modifier.height(4.dp)); T("播客电台", Fs.HERO, w = 800, lh = 1.3f); T("热门电台", 13, p.s)
                Spacer(Modifier.height(12.dp)); Rule()
            }
        }
        items(list, key = { "r" + it.id }) { r -> ProfileRow({ Cover(r.pic, 56.dp, r.name) }, r.name, "${two(r.count)} 期 · ${r.desc}") { nav.push(Page.Detail("radio", r.id, r.name, r.pic)) } }
        item { T(when { err != null -> "加载失败：$err"; busy -> "LOADING"; !more -> "END"; else -> "" }, 12, p.s, mono = true, ls = .08f, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(vertical = 20.dp)) }
    }
}


/** 歌手的全部专辑：网格，滚到底自动加载下一页 */
