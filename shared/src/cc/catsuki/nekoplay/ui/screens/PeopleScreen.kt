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
@Composable fun ArtistPage(pg: Page.Artist) {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    var err by remember { mutableStateOf<String?>(null) }
    val info by produceState<Artist?>(null, pg.id) { try { value = SourceApi.artist(pg.id) } catch (e: Exception) { err = e.message } }
    val albums by produceState<List<Album>>(emptyList(), pg.id) { runCatching { value = SourceApi.artistAlbums(pg.id) } }
    val mvs by produceState<List<Mv>>(emptyList(), pg.id) { runCatching { value = SourceApi.artistMvs(pg.id) } }
    val simi by produceState<List<Artist>>(emptyList(), pg.id) { if (SourceApi.loggedIn) runCatching { value = SourceApi.simiArtists(pg.id) } }
    var expand by remember { mutableStateOf(false) }
    val a = info
    val hot = a?.hot ?: emptyList()
    val p = c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)) {
        item {
            Column {
                Tag("ARTIST"); Spacer(Modifier.height(12.dp))
                Row(Modifier.height(112.dp)) {
                    Cover(a?.pic, 112.dp, pg.name, modifier = Modifier.heroAnchor("art:${pg.id}", a?.pic, pg.name, isTarget = true).geomReveal("art:${pg.id}"))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        T(pg.name, 24, w = 800, lines = 2, lh = 1.25f)
                        Spacer(Modifier.height(4.dp))
                        T(if (a == null) "LOADING" else "${two(a.songs)} 首 · ${two(a.albums)} 张专辑", 12, p.s, mono = true, ls = .04f)
                        Spacer(Modifier.weight(1f)); T("${Plugins.sourceName(pg.id) ?: tr("在线音源")} · ${tr("歌手")}", 13, p.s)
                    }
                }
                val brief = a?.brief
                if (!brief.isNullOrBlank()) Box(Modifier.padding(top = 14.dp).press { expand = !expand }) {
                    T(brief, 13, p.s, lines = if (expand) 40 else 3, lh = 1.6f)
                }
                Spacer(Modifier.height(16.dp))
                ActionRow {
                    PrimaryBar("播放热门歌曲", NIcons.play, Modifier.weight(1f)) { Engine.playList(hot, 0) }
                    Spacer(Modifier.width(10.dp)); OutlineBtn("随机播放", pad = 14.dp) { Engine.playList(hot, 0, shuffle = true) }
                    if (SourceApi.loggedIn && a != null) {
                        Spacer(Modifier.width(10.dp))
                        FollowBtn(a.followed, "关注", "已关注") { on -> SourceApi.followArtist(pg.id, on) }
                    }
                }
                if (err != null) T("加载失败：$err", 13, p.bad, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(vertical = 24.dp))
                if (albums.isNotEmpty()) {
                    Spacer(Modifier.height(26.dp)); TagRow("ALBUMS", 12.dp) { T(if (info?.albums ?: 0 > albums.size) "${two(info!!.albums)} 张" else "${two(albums.size)} 张", 13, p.s, mono = true); Spacer(Modifier.width(8.dp)); LinkBtn("全部", 13) { nav.push(Page.ArtistAlbums(pg.id, pg.name)) } }; Spacer(Modifier.height(12.dp))
                }
            }
        }
        if (albums.isNotEmpty()) item {
            LazyRow(Modifier.fillMaxWidth().layoutNoPad(18.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(albums, key = { it.id }) { al ->
                    Column(Modifier.width(120.dp).press { SharedHero.capture("alb:${al.id}", al.pic, al.name); nav.push(Page.Album(al.id, al.name, al.pic)) }) {
                        Cover(al.pic, 120.dp, al.name, modifier = Modifier.heroAnchor("alb:${al.id}", al.pic, al.name)); Spacer(Modifier.height(8.dp))
                        T(al.name, 15, w = 700, lh = 1.35f); T(listOf(al.year, if (al.size > 0) "${al.size} 首" else "").filter { it.isNotEmpty() }.joinToString(" · "), 13, p.s)
                    }
                }
            }
        }
        if (mvs.isNotEmpty()) {
            item { Column(Modifier.padding(top = 26.dp, bottom = 12.dp)) { TagRow("MV", 12.dp) { T("${two(mvs.size)} 支", 13, p.s, mono = true) } } }
            item {
                LazyRow(Modifier.fillMaxWidth().layoutNoPad(18.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(mvs, key = { "mv" + it.id }) { mv -> MvCard(mv, 200.dp) { nav.push(Page.Mv(mv.id, mv.name, mv.artist)) } }
                }
            }
        }
        if (simi.isNotEmpty()) {
            item { Column(Modifier.padding(top = 26.dp, bottom = 12.dp)) { TagRow("SIMILAR", 12.dp) { T("${two(simi.size)} 位", 13, p.s, mono = true) } } }
            item {
                LazyRow(Modifier.fillMaxWidth().layoutNoPad(18.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(simi, key = { "s" + it.id }) { ar -> Column(Modifier.width(88.dp).press { nav.push(Page.Artist(ar.id, ar.name)) }) { Cover(ar.pic, 88.dp, ar.name); Spacer(Modifier.height(6.dp)); T(ar.name, 13, w = 700) } }
                }
            }
        }
        item { Column(Modifier.padding(top = 26.dp, bottom = 8.dp)) { TagRow("HOT SONGS", 12.dp) { T("${two(hot.size)} 首", 13, p.s, mono = true) } } }
        if (a == null && err == null) item { T("LOADING", 13, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 24.dp)) }
        itemsIndexed(hot, key = { i, t -> "$i:${t.id}" }) { i, t -> TrackRow(t, st.current?.id == t.id, onLong = { trackActions(nav, t) }) { Engine.playList(hot, i) } }
    }
}

// ======================= 专辑页 =======================
@Composable fun AlbumPage(pg: Page.Album) {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    var err by remember { mutableStateOf<String?>(null) }
    val data by produceState<Pair<Album, List<Track>>?>(null, pg.id) { try { value = SourceApi.album(pg.id) } catch (e: Exception) { err = e.message } }
    val list = data?.second ?: emptyList()
    val al = data?.first
    val p = c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)) {
        item {
            Column {
                Tag("ALBUM"); Spacer(Modifier.height(12.dp))
                Row(Modifier.height(112.dp)) {
                    Cover(al?.pic ?: pg.cover, 112.dp, pg.name, modifier = Modifier.heroAnchor("alb:${pg.id}", al?.pic ?: pg.cover, pg.name, isTarget = true).geomReveal("alb:${pg.id}"))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        T(pg.name, 24, w = 800, lines = 2, lh = 1.25f)
                        Spacer(Modifier.height(4.dp))
                        T(if (data == null) "LOADING" else listMeta(list) + (if (al?.year?.isNotEmpty() == true) " · ${al.year}" else ""), 12, p.s, mono = true, ls = .04f)
                        Spacer(Modifier.weight(1f))
                        val ar = list.firstOrNull()?.artists?.firstOrNull()
                        T(al?.artist?.ifEmpty { null } ?: ar?.second ?: "", 13, p.s, underline = ar != null, modifier = Modifier.press { if (ar != null) nav.push(Page.Artist(ar.first, ar.second)) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                ActionRow {
                    PrimaryBar("播放", NIcons.play, Modifier.weight(1f)) { Engine.playList(list, 0) }
                    Spacer(Modifier.width(10.dp)); OutlineBtn("随机播放", pad = 14.dp) { Engine.playList(list, 0, shuffle = true) }
                    if (SourceApi.loggedIn) {
                        val subbed by produceState<Boolean?>(null, pg.id) { value = runCatching { SourceApi.subAlbums().any { it.id == pg.id } }.getOrDefault(false) }
                        Spacer(Modifier.width(10.dp)); FollowBtn(subbed ?: false, "收藏", "已收藏", ready = subbed != null) { on -> SourceApi.subAlbum(pg.id, on); Store.refreshPlaylists() }
                    }
                }
                Spacer(Modifier.height(12.dp)); Rule()
                if (err != null) T("加载失败：$err", 13, p.bad, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(vertical = 28.dp))
                else if (data == null) T("LOADING", 13, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 28.dp))
            }
        }
        itemsIndexed(list, key = { i, t -> "$i:${t.id}" }) { i, t -> TrackRow(t, st.current?.id == t.id, onLong = { trackActions(nav, t) }) { Engine.playList(list, i) } }
    }
}

// ======================= 用户页 =======================
@Composable fun UserPage(pg: Page.User) {
    val nav = LocalNav.current
    val src = remember(pg.uid, Plugins.rev) { Plugins.ofOrNull(pg.uid) }
    val me = Store.profile.value
    val isMe = me != null && pg.uid == me.uid
    val capsReady = src != null && src.caps.isNotEmpty()
    val canUser = !capsReady || src.has("user")
    val canPls = !capsReady || src.has("userPlaylists")
    val canRecord = src?.has("playRecord") == true
    val canSubArtists = src?.has("subArtists") == true
    val canFollow = src?.has("followUser") == true
    var err by remember(pg.uid) { mutableStateOf<String?>(null) }
    val u by produceState<User?>(null, pg.uid, canUser) {
        if (!canUser) {
            value = User(pg.uid, if (isMe) me.name else pg.name, if (isMe) me.avatar.ifEmpty { null } else null, null, "${src.name}未提供详细主页信息", 0, 0, 0, 0, false)
            return@produceState
        }
        try { value = SourceApi.user(pg.uid) } catch (e: Exception) { err = e.message }
    }
    val pls by produceState<List<Playlist>?>(null, pg.uid, canPls) {
        if (!canPls) { value = if (isMe) Store.playlists.value else emptyList(); return@produceState }
        runCatching { value = SourceApi.userPlaylists(pg.uid) }.onFailure { value = if (isMe) Store.playlists.value else emptyList() }
    }
    val p = c
    val liked = pls?.firstOrNull { it.liked }
    val made = pls?.filter { !it.liked && it.mine } ?: emptyList()
    val faved = pls?.filter { !it.liked && !it.mine } ?: emptyList()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
        item {
            // 顶部背景图 + 头像
            Box(Modifier.fillMaxWidth().height(150.dp).background(p.ph)) {
                if (u?.bg != null) AsyncImage(img(u!!.bg, 768), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .18f)))
            }
        }
        item {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.offset(y = (-44).dp)) {
                    val av = u?.avatar ?: if (isMe) me.avatar.ifEmpty { null } else null
                    Box(Modifier.size(88.dp).background(p.p).nb(2.dp, p.k)) { if (av != null) AsyncImage(img(av, 256), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.padding(bottom = 4.dp)) { Tag(if (u == null || u!!.level <= 0) "USER" else "LV.${u!!.level}") }
                }
                Column(Modifier.offset(y = (-32).dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(u?.name ?: pg.name, Fs.HERO, w = 800, lh = 1.2f, modifier = Modifier.weight(1f))
                        if (SourceApi.loggedIn && u != null && !isMe && canFollow) FollowBtn(u!!.followed, "关注", "已关注") { on -> SourceApi.followUser(pg.uid, on) }
                    }
                    if (!u?.signature.isNullOrBlank()) T(u!!.signature, 13, p.s, lines = 3, lh = 1.6f, modifier = Modifier.padding(top = 6.dp))
                    if (err != null) T("加载失败：$err", 13, p.bad, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(top = 10.dp))
                    if (canUser) {
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.topLine()) {
                            Row { UStat("关注", two(u?.follows ?: 0), false, false); UStat("粉丝", two(u?.fans ?: 0), true, false) }
                            Row { UStat("听歌", "${u?.listened ?: 0} 首", false, true); UStat("歌单", "${two(made.size)} 个", true, true) }
                        }
                    }
                }
            }
        }
        if (canRecord) item { Box(Modifier.padding(horizontal = 18.dp)) { ProfileRow({ IconCover(NIcons.chart, 56.dp) }, "听歌排行", "最近一周 / 所有时间") { nav.push(Page.Rank(pg.uid, u?.name ?: pg.name)) } } }
        if (isMe && canSubArtists) item { Box(Modifier.padding(horizontal = 18.dp)) { ProfileRow({ IconCover(NIcons.user, 56.dp) }, "关注的歌手", "ARTISTS") { nav.push(Page.Subs("artist")) } } }
        if (liked != null) item {
            Column(Modifier.padding(horizontal = 18.dp)) {
                ProfileRow({ LikedCover(liked.cover, 56.dp) }, if (isMe) "我喜欢的音乐" else "TA 喜欢的音乐", "${two(liked.count)} 首") {
                    nav.push(Page.Detail(if (isMe) "liked" else "pl", liked.id, liked.name, liked.cover))
                }
            }
        }
        if (made.isNotEmpty()) item { Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 4.dp)) { TagRow("CREATED", 12.dp) { T("${two(made.size)} 个", 13, p.s, mono = true) } } }
        items(made, key = { "m" + it.id }) { pl -> Box(Modifier.padding(horizontal = 18.dp)) { ProfileRow({ Cover(pl.cover, 56.dp, pl.name) }, pl.name, "${two(pl.count)} 首") { nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) } } }
        if (faved.isNotEmpty()) item { Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 4.dp)) { TagRow("COLLECTED", 12.dp) { T("${two(faved.size)} 个", 13, p.s, mono = true) } } }
        items(faved, key = { "f" + it.id }) { pl -> Box(Modifier.padding(horizontal = 18.dp)) { ProfileRow({ Cover(pl.cover, 56.dp, pl.name) }, pl.name, "${two(pl.count)} 首") { nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) } } }
        if (pls == null) item { T("LOADING", 13, p.s, mono = true, ls = .08f, modifier = Modifier.padding(18.dp)) }
    }
}

@Composable private fun RowScope.UStat(k: String, v: String, left: Boolean, top: Boolean) {
    val p = c
    Column(Modifier.weight(1f).drawBehindLines(p.l2, left, top).padding(start = if (left) 16.dp else 0.dp, top = 12.dp, bottom = 12.dp)) {
        T(k, 11, p.s, mono = true, ls = .08f); T(v, 24, mono = true)
    }
}

private fun Modifier.drawBehindLines(col: Color, left: Boolean, top: Boolean) = this.drawBehind {
    if (left) drawRect(col, size = androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height))
    if (top) drawRect(col, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
}

@Composable internal fun ProfileRow(cover: @Composable () -> Unit, title: String, meta: String, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().height(76.dp).press(onClick = onClick).bottomLine(), verticalAlignment = Alignment.CenterVertically) {
        cover(); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { T(title, 16, w = 700, lh = 1.35f); T(meta, 12, c.s, mono = true, ls = .04f) }
        NIcon(NIcons.chevronRight, 16.dp)
    }

/** 音质选择菜单（设置页和播放页共用） */

@Composable fun ArtistAlbumsPage(pg: Page.ArtistAlbums) {
    val nav = LocalNav.current
    val p = c
    var list by remember(pg.id) { mutableStateOf<List<Album>>(emptyList()) }
    var more by remember(pg.id) { mutableStateOf(true) }
    var busy by remember(pg.id) { mutableStateOf(false) }
    var err by remember(pg.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun load() {
        if (busy || !more) return; busy = true
        scope.launch {
            try { val (l, m) = SourceApi.artistAlbumsPage(pg.id, list.size, 60); list = list + l.filter { a -> list.none { it.id == a.id } }; more = m && l.isNotEmpty(); err = null }
            catch (e: Exception) { err = e.message }
            busy = false
        }
    }
    LaunchedEffect(pg.id) { load() }
    val gs = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    LaunchedEffect(gs) { snapshotFlow { gs.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it >= list.size - 6) load() } }
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), gs,
        contentPadding = PaddingValues(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Column { Tag("ALBUMS"); Spacer(Modifier.height(4.dp)); T(pg.name, Fs.HERO, w = 800, lh = 1.3f); T("${two(list.size)} 张" + if (more) " · 继续加载中" else "", 12, p.s, mono = true); Spacer(Modifier.height(10.dp)); Rule() }
        }
        items(list.size, key = { list[it].id }) { i ->
            val al = list[i]
            Column(Modifier.press { SharedHero.capture("alb:${al.id}", al.pic, al.name); nav.push(Page.Album(al.id, al.name, al.pic)) }) {
                CoverFill(al.pic, al.name, modifier = Modifier.heroAnchor("alb:${al.id}", al.pic, al.name))
                Spacer(Modifier.height(8.dp))
                T(al.name, 15, w = 700, lh = 1.35f, lines = 2); T(listOf(al.year, if (al.size > 0) "${al.size} 首" else "").filter { it.isNotEmpty() }.joinToString(" · "), 13, p.s)
            }
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            T(when { err != null -> "加载失败：$err"; busy -> "LOADING"; !more -> "END"; else -> "" }, 12, if (err != null) p.bad else p.s, mono = true, ls = .08f, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}
