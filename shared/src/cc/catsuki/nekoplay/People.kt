package cc.catsuki.nekoplay

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
data class SheetItem(val label: String, val danger: Boolean = false, val run: () -> Unit)
data class SheetSpec(val title: String, val sub: String?, val items: List<SheetItem>)

/** 曲目长按：下一首播放 / 加入队列 / 喜欢 / 歌手 / 专辑 */
fun trackActions(nav: Nav, t: Track, fromPl: String? = null) {
    val liked = t.id in Store.likedIds.value
    // 本地歌：只有和网易云无关的几项
    if (t.local) {
        nav.sheet = SheetSpec(t.title, t.artist, buildList {
            add(SheetItem("下一首播放") { Engine.enqueueNext(t); nav.toast = "已加入「下一首播放」" })
            add(SheetItem("加入播放队列") { Engine.enqueue(t); nav.toast = "已加入播放队列" })
            add(SheetItem("歌词卡片") { nav.player = false; nav.push(Page.LyricCard(t)) })
            if (Engine.state.value.current?.id == t.id) add(SheetItem("歌词偏移") { offsetSheet(nav, t) })
            add(SheetItem("效果器 / 均衡器") { nav.player = false; nav.push(Page.Effects) })
        })
        return
    }
    nav.sheet = SheetSpec(t.title, t.artist, buildList {
        add(SheetItem("下一首播放") { Engine.enqueueNext(t); nav.toast = "已加入「下一首播放」" })
        add(SheetItem("加入播放队列") { Engine.enqueue(t); nav.toast = "已加入播放队列" })
        add(SheetItem(if (liked) "取消喜欢" else "加入「我喜欢」") { Store.toggleLike(t) { nav.toast = it }; nav.toast = if (liked) "已取消喜欢" else "已加入「我喜欢」" })
        t.artists.forEach { (id, name) -> add(SheetItem("歌手：$name") { nav.player = false; nav.push(Page.Artist(id, name)) }) }
        if (t.albumId.isNotEmpty() && t.albumId != "0") add(SheetItem("专辑：${t.album}") { nav.player = false; nav.push(Page.Album(t.albumId, t.album, t.cover)) })
        if (Ncm.loggedIn) add(SheetItem("添加到歌单…") { addToPlaylist(nav, t) })
        if (fromPl != null) add(SheetItem("从这个歌单删除", danger = true) {
            Store.run { try { Ncm.playlistTracks(fromPl, "del", listOf(t.id)); nav.toast = "已从歌单删除"; Store.refreshPlaylists(fromPl) } catch (e: Exception) { nav.toast = "删除失败：${e.message}" } }
        })
        if (Downloads.has(t.id)) add(SheetItem("删除下载", danger = true) { Downloads.delete(t.id); nav.toast = "已删除下载" })
        else add(SheetItem("下载…") { downloadSheet(nav, listOf(t)) })
        add(SheetItem("查看评论") { nav.comments = t })
        add(SheetItem("歌词卡片") { nav.player = false; nav.push(Page.LyricCard(t)) })
        if (Engine.state.value.current?.id == t.id) add(SheetItem("歌词偏移" + LyricOffset.of(t.id).let { if (it == 0L) "" else "（%+.1f 秒）".format(it / 1000f) }) { offsetSheet(nav, t) })
        add(SheetItem("效果器 / 均衡器") { nav.player = false; nav.push(Page.Effects) })
        add(SheetItem("相似歌曲") { nav.player = false; nav.push(Page.Detail("simi", t.id, "相似：${t.title}", t.cover)) })
        add(SheetItem("包含这首歌的歌单") { nav.player = false; nav.push(Page.Playlists("simi", t.id, "相关歌单")) })
    })
}

/** 选择要加入的歌单（自己创建的），或新建一个 */
fun addToPlaylist(nav: Nav, t: Track) {
    val mine = Store.playlists.value.filter { it.mine }
    fun add(pl: Playlist) = Store.run {
        try { Ncm.playlistTracks(pl.id, "add", listOf(t.id)); nav.toast = "已加入「${if (pl.liked) "我喜欢" else pl.name}」"; Store.refreshPlaylists(pl.id) }
        catch (e: Exception) { nav.toast = "添加失败：${e.message}" }
    }
    nav.sheet = SheetSpec("添加到歌单", t.title, listOf(SheetItem("＋ 新建歌单") {
        nav.input = InputSpec("新建歌单", "歌单名称") { name ->
            Store.run {
                try { val id = Ncm.createPlaylist(name, false); add(Playlist(id, name, 0, null)) ; Store.refreshPlaylists() } catch (e: Exception) { nav.toast = "新建失败：${e.message}" }
            }
        }
    }) + mine.map { pl -> SheetItem(if (pl.liked) "我喜欢" else pl.name) { add(pl) } })
}

/** 新建歌单（曲库页用） */
fun newPlaylist(nav: Nav) {
    nav.input = InputSpec("新建歌单", "歌单名称") { name ->
        Store.run { try { Ncm.createPlaylist(name, false); nav.toast = "已新建「$name」"; Store.refreshPlaylists() } catch (e: Exception) { nav.toast = "新建失败：${e.message}" } }
    }
}

/** 曲库里长按歌单：自己的可以删除，收藏的可以取消收藏 */
fun playlistActions(nav: Nav, pl: Playlist) {
    nav.sheet = SheetSpec(pl.name, "${pl.count} 首", listOf(
        if (pl.mine) SheetItem("删除歌单", danger = true) {
            nav.sheet = SheetSpec("确定删除「${pl.name}」？", "删除后无法恢复", listOf(SheetItem("删除", danger = true) {
                Store.run { try { Ncm.deletePlaylist(pl.id); nav.toast = "已删除"; Store.refreshPlaylists() } catch (e: Exception) { nav.toast = "删除失败：${e.message}" } }
            }))
        } else SheetItem("取消收藏", danger = true) {
            Store.run { try { Ncm.subscribePlaylist(pl.id, false); nav.toast = "已取消收藏"; Store.refreshPlaylists() } catch (e: Exception) { nav.toast = "操作失败：${e.message}" } }
        }
    ))
}

// ======================= 文字输入框 =======================
data class InputSpec(val title: String, val hint: String, val onDone: (String) -> Unit)

@Composable fun InputDialog() {
    val nav = LocalNav.current
    val spec = nav.input ?: return
    val p = c
    var text by remember(spec) { mutableStateOf("") }
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(spec) { fr.requestFocus() }
    fun done() { val v = text.trim(); if (v.isEmpty()) return; nav.input = null; spec.onDone(v) }
    Box(Modifier.fillMaxSize().background(Color(0x80000000)).pointerInput(Unit) { detectTapGestures { nav.input = null } }.imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 28.dp).fillMaxWidth().background(p.sheet).nb(1.dp, p.k).topLine().pointerInput(Unit) { detectTapGestures { } }.padding(18.dp)) {
            T(spec.title, 17, w = 800); Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().nbf(1.dp, p.k).padding(horizontal = 12.dp, vertical = 12.dp)) {
                if (text.isEmpty()) T(spec.hint, 15, p.s)
                androidx.compose.foundation.text.BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(fr), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = p.k, fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp), fontFamily = Typo.family),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(p.p),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { done() }))
            }
            Spacer(Modifier.height(16.dp))
            Row { Box(Modifier.weight(1f)) { OutlineBtn("取消", 44.dp) { nav.input = null } }; Spacer(Modifier.width(10.dp)); Box(Modifier.weight(1f).press(.96f) { done() }.height(44.dp).background(p.p), contentAlignment = Alignment.Center) { T("确定", 15, Color.White, 700) } }
        }
    }
}

/** 打开歌手：多位歌手时先让用户选 */
fun openArtists(nav: Nav, t: Track) {
    when (t.artists.size) {
        0 -> nav.toast = "没有歌手信息"
        1 -> { nav.player = false; nav.push(Page.Artist(t.artists[0].first, t.artists[0].second)) }
        else -> nav.sheet = SheetSpec("选择歌手", t.title, t.artists.map { (id, n) -> SheetItem(n) { nav.player = false; nav.push(Page.Artist(id, n)) } })
    }
}

@Composable fun ActionSheet(wide: Boolean = false) {
    val nav = LocalNav.current
    val p = c
    var last by remember { mutableStateOf<SheetSpec?>(null) }
    nav.sheet?.let { last = it }
    AnimatedVisibility(nav.sheet != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Color(if (wide) 0x55000000 else 0x80000000)).pointerInput(Unit) { detectTapGestures { nav.sheet = null } })
    }
    // PC 宽屏：居中的小弹窗（缩放 + 淡入）；手机：底部弹出
    if (wide) AnimatedVisibility(nav.sheet != null, Modifier.fillMaxSize(),
        enter = fadeIn(tween(160)) + scaleIn(spring(.8f, Anim.stiff(Spring.StiffnessMedium)), initialScale = .92f),
        exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = .96f)) {
        val s = last ?: return@AnimatedVisibility
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.width(380.dp).heightIn(max = 620.dp).background(p.sheet).nb(1.dp, p.k).topLine().pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp)) {
                    T(s.title, 16, w = 800, lines = 2); if (s.sub != null) T(s.sub, 12, p.s, lines = 2)
                }
                s.items.forEach { it ->
                    Box(Modifier.fillMaxWidth().height(42.dp).press { nav.sheet = null; it.run() }.padding(horizontal = 18.dp).topLine(p.l1, 1.dp), contentAlignment = Alignment.CenterStart) {
                        T(it.label, 14, if (it.danger) p.bad else p.k)
                    }
                }
            }
        }
    }
    else AnimatedVisibility(nav.sheet != null, Modifier.fillMaxSize(), enter = slideInVertically(sp) { it }, exit = slideOutVertically(sp) { it }) {
        val s = last ?: return@AnimatedVisibility
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // 最多占半屏：标题和「取消」固定，中间的选项可以滚动
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight * .5f).background(p.sheet).topLine().pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding().padding(bottom = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp)) {
                    T(s.title, 17, w = 800); if (s.sub != null) T(s.sub, 13, p.s)
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    s.items.forEach { it ->
                        Box(Modifier.fillMaxWidth().height(52.dp).press { nav.sheet = null; it.run() }.padding(horizontal = 18.dp).topLine(p.l1, 1.dp), contentAlignment = Alignment.CenterStart) {
                            T(it.label, 16, if (it.danger) p.bad else p.k)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) { OutlineBtn("取消", 46.dp) { nav.sheet = null } }
            }
        }
    }
}

// ======================= 歌手页 =======================
@Composable fun ArtistPage(pg: Page.Artist) {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    var err by remember { mutableStateOf<String?>(null) }
    val info by produceState<Artist?>(null, pg.id) { try { value = Ncm.artist(pg.id) } catch (e: Exception) { err = e.message } }
    val albums by produceState<List<Album>>(emptyList(), pg.id) { runCatching { value = Ncm.artistAlbums(pg.id) } }
    val mvs by produceState<List<Mv>>(emptyList(), pg.id) { runCatching { value = Ncm.artistMvs(pg.id) } }
    val simi by produceState<List<Artist>>(emptyList(), pg.id) { if (Ncm.loggedIn) runCatching { value = Ncm.simiArtists(pg.id) } }
    var expand by remember { mutableStateOf(false) }
    val a = info
    val hot = a?.hot ?: emptyList()
    val p = c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)) {
        item {
            Column {
                Tag("ARTIST"); Spacer(Modifier.height(12.dp))
                Row(Modifier.height(112.dp)) {
                    Cover(a?.pic, 112.dp, pg.name)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        T(pg.name, 24, w = 800, lines = 2, lh = 1.25f)
                        Spacer(Modifier.height(4.dp))
                        T(if (a == null) "LOADING" else "${two(a.songs)} 首 · ${two(a.albums)} 张专辑", 12, p.s, mono = true, ls = .04f)
                        Spacer(Modifier.weight(1f)); T("网易云音乐 · 歌手", 13, p.s)
                    }
                }
                if (!a?.brief.isNullOrBlank()) Box(Modifier.padding(top = 14.dp).press { expand = !expand }) {
                    T(a!!.brief, 13, p.s, lines = if (expand) 40 else 3, lh = 1.6f)
                }
                Spacer(Modifier.height(16.dp))
                ActionRow {
                    PrimaryBar("播放热门歌曲", NIcons.play, Modifier.weight(1f)) { Engine.playList(hot, 0) }
                    Spacer(Modifier.width(10.dp)); OutlineBtn("随机播放", pad = 14.dp) { Engine.playList(hot, 0, shuffle = true) }
                    if (Ncm.loggedIn && a != null) {
                        Spacer(Modifier.width(10.dp))
                        FollowBtn(a.followed, "关注", "已关注") { on -> Ncm.followArtist(pg.id, on) }
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
                    Column(Modifier.width(120.dp).press { nav.push(Page.Album(al.id, al.name, al.pic)) }) {
                        Cover(al.pic, 120.dp, al.name); Spacer(Modifier.height(8.dp))
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
        itemsIndexed(hot, key = { i, t -> "$i${t.id}" }) { i, t -> TrackRow(t, st.current?.id == t.id, onLong = { trackActions(nav, t) }) { Engine.playList(hot, i) } }
    }
}

// ======================= 专辑页 =======================
@Composable fun AlbumPage(pg: Page.Album) {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    var err by remember { mutableStateOf<String?>(null) }
    val data by produceState<Pair<Album, List<Track>>?>(null, pg.id) { try { value = Ncm.album(pg.id) } catch (e: Exception) { err = e.message } }
    val list = data?.second ?: emptyList()
    val al = data?.first
    val p = c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)) {
        item {
            Column {
                Tag("ALBUM"); Spacer(Modifier.height(12.dp))
                Row(Modifier.height(112.dp)) {
                    Cover(al?.pic ?: pg.cover, 112.dp, pg.name)
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
                    PrimaryBar("播放全部", NIcons.play, Modifier.weight(1f)) { Engine.playList(list, 0) }
                    Spacer(Modifier.width(10.dp)); OutlineBtn("随机播放", pad = 14.dp) { Engine.playList(list, 0, shuffle = true) }
                    if (Ncm.loggedIn) {
                        val subbed by produceState<Boolean?>(null, pg.id) { value = runCatching { Ncm.subAlbums().any { it.id == pg.id } }.getOrDefault(false) }
                        Spacer(Modifier.width(10.dp)); FollowBtn(subbed ?: false, "收藏", "已收藏", ready = subbed != null) { on -> Ncm.subAlbum(pg.id, on); Store.refreshPlaylists() }
                    }
                }
                Spacer(Modifier.height(12.dp)); Rule()
                if (err != null) T("加载失败：$err", 13, p.bad, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(vertical = 28.dp))
                else if (data == null) T("LOADING", 13, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 28.dp))
            }
        }
        itemsIndexed(list, key = { i, t -> "$i${t.id}" }) { i, t -> TrackRow(t, st.current?.id == t.id, onLong = { trackActions(nav, t) }) { Engine.playList(list, i) } }
    }
}

// ======================= 用户页 =======================
@Composable fun UserPage(pg: Page.User) {
    val nav = LocalNav.current
    var err by remember { mutableStateOf<String?>(null) }
    val u by produceState<User?>(null, pg.uid) { try { value = Ncm.user(pg.uid) } catch (e: Exception) { err = e.message } }
    val pls by produceState<List<Playlist>?>(null, pg.uid) { runCatching { value = Ncm.userPlaylists(pg.uid) } }
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
                    Box(Modifier.size(88.dp).background(p.p).nb(2.dp, p.k)) { if (u?.avatar != null) AsyncImage(img(u!!.avatar, 256), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.padding(bottom = 4.dp)) { Tag(if (u == null) "USER" else "LV.${u!!.level}") }
                }
                Column(Modifier.offset(y = (-32).dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(u?.name ?: pg.name, Fs.HERO, w = 800, lh = 1.2f, modifier = Modifier.weight(1f))
                        if (Ncm.loggedIn && u != null && pg.uid != Store.profile.value?.uid) FollowBtn(u!!.followed, "关注", "已关注") { on -> Ncm.followUser(pg.uid, on) }
                    }
                    if (!u?.signature.isNullOrBlank()) T(u!!.signature, 13, p.s, lines = 3, lh = 1.6f, modifier = Modifier.padding(top = 6.dp))
                    if (err != null) T("加载失败：$err", 13, p.bad, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(top = 10.dp))
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.topLine()) {
                        Row { UStat("关注", two(u?.follows ?: 0), false, false); UStat("粉丝", two(u?.fans ?: 0), true, false) }
                        Row { UStat("听歌", "${u?.listened ?: 0} 首", false, true); UStat("歌单", "${two(made.size)} 个", true, true) }
                    }
                }
            }
        }
        item { Box(Modifier.padding(horizontal = 18.dp)) { ProfileRow({ IconCover(NIcons.chart, 56.dp) }, "听歌排行", "最近一周 / 所有时间") { nav.push(Page.Rank(pg.uid, u?.name ?: pg.name)) } } }
        if (pg.uid == Store.profile.value?.uid) item { Box(Modifier.padding(horizontal = 18.dp)) { ProfileRow({ IconCover(NIcons.user, 56.dp) }, "关注的歌手", "ARTISTS") { nav.push(Page.Subs("artist")) } } }
        if (liked != null) item {
            Column(Modifier.padding(horizontal = 18.dp)) {
                ProfileRow({ LikedCover(liked.cover, 56.dp) }, if (pg.uid == Store.profile.value?.uid) "我喜欢的音乐" else "TA 喜欢的音乐", "${two(liked.count)} 首") {
                    nav.push(Page.Detail(if (pg.uid == Store.profile.value?.uid) "liked" else "pl", liked.id, liked.name, liked.cover))
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

@Composable private fun ProfileRow(cover: @Composable () -> Unit, title: String, meta: String, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().height(76.dp).press(onClick = onClick).bottomLine(), verticalAlignment = Alignment.CenterVertically) {
        cover(); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { T(title, 16, w = 700, lh = 1.35f); T(meta, 12, c.s, mono = true, ls = .04f) }
        NIcon(NIcons.chevronRight, 16.dp)
    }

/** 音质选择菜单（设置页和播放页共用） */
fun cellQualitySheet(nav: Nav) {
    nav.sheet = SheetSpec("流量音质", "用手机流量时自动换成这个档位，省流量", listOf(SheetItem((if (Engine.qualityCell < 0) "✓  " else "    ") + "和 Wi-Fi 一样") { Engine.changeCellQuality(-1); nav.toast = "流量音质：和 Wi-Fi 一样" }) +
        Ncm.LEVEL_LABELS.mapIndexed { i, l -> SheetItem((if (i == Engine.qualityCell) "✓  " else "    ") + l) { Engine.changeCellQuality(i); nav.toast = "流量音质：$l" } }.reversed())
}

fun qualitySheet(nav: Nav) {
    nav.sheet = SheetSpec("选择音质", "选中的档位拿不到时会自动降一档；无损和 Hi-Res 需要会员", Ncm.LEVEL_LABELS.mapIndexed { i, l ->
        SheetItem((if (i == Engine.quality) "✓  " else "    ") + l) { Engine.changeQuality(i); nav.toast = "音质：$l" }
    }.reversed())
}


// ======================= 歌单列表页（相关歌单 / 以后的歌单广场共用） =======================
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
@Composable fun MultiChipRow(items: List<String>, on: (Int) -> Boolean, onToggle: (Int) -> Unit) {
    val p = c
    LazyRow(Modifier.fillMaxWidth().layoutNoPad(18.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(items) { i, l ->
            val o = on(i)
            Box(Modifier.press { onToggle(i) }.background(if (o) p.k else Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 6.dp)) { T(l, 13, if (o) p.tagFg else p.k, 700) }
        }
    }
}

@Composable fun ChipRow(items: List<String>, sel: Int, onSel: (Int) -> Unit) {
    val p = c
    LazyRow(Modifier.fillMaxWidth().layoutNoPad(18.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(items) { i, l ->
            val on = i == sel
            Box(Modifier.press { if (!on) onSel(i) }.background(if (on) p.k else Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 6.dp)) { T(l, 13, if (on) p.tagFg else p.k, 700) }
        }
    }
}

/** 滑到列表底部附近时触发 */
@Composable fun androidx.compose.foundation.lazy.LazyListState.nearEnd(): Boolean {
    val e by remember(this) { derivedStateOf { (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layoutInfo.totalItemsCount - 4 } }
    return e
}

// ======================= 歌单广场 =======================
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
@Composable fun FollowBtn(initial: Boolean, off: String, on: String, ready: Boolean = true, act: suspend (Boolean) -> Unit) {
    val nav = LocalNav.current
    var v by remember(initial) { mutableStateOf(initial) }
    val p = c
    val bg by androidx.compose.animation.animateColorAsState(if (v) Color.Transparent else p.k, tween(220), label = "fb")
    val fg by androidx.compose.animation.animateColorAsState(if (v) p.k else p.tagFg, tween(220), label = "ff")
    Box(Modifier.press(.96f, true) {
        if (!ready) return@press
        val n = !v; v = n
        Store.run { try { act(n); nav.toast = if (n) "已$off" else "已取消$off" } catch (e: Exception) { v = !n; nav.toast = "操作失败：${e.message}" } }
    }.height(48.dp).background(bg).nbf(1.dp, p.k).padding(horizontal = 14.dp).animateContentSize(tween(220)), contentAlignment = Alignment.Center) {
        androidx.compose.animation.AnimatedContent(v, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "ft") { x ->
            T(if (x) on else "+ $off", 15, fg, 700)
        }
    }
}

// ======================= 收藏的专辑 / 关注的歌手 =======================
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
                ActionRow { PrimaryBar("播放全部", NIcons.play, Modifier.weight(1f)) { Engine.playList(list, 0) } }
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
            try { val (l, m) = Ncm.artistAlbumsPage(pg.id, list.size, 60); list = list + l.filter { a -> list.none { it.id == a.id } }; more = m && l.isNotEmpty(); err = null }
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
            Column(Modifier.press { nav.push(Page.Album(al.id, al.name, al.pic)) }) {
                BoxWithConstraints(Modifier.fillMaxWidth()) { Cover(al.pic, maxWidth, al.name) }
                Spacer(Modifier.height(8.dp))
                T(al.name, 15, w = 700, lh = 1.35f, lines = 2); T(listOf(al.year, if (al.size > 0) "${al.size} 首" else "").filter { it.isNotEmpty() }.joinToString(" · "), 13, p.s)
            }
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            T(when { err != null -> "加载失败：$err"; busy -> "LOADING"; !more -> "END"; else -> "" }, 12, if (err != null) p.bad else p.s, mono = true, ls = .08f, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}
