package cc.catsuki.nekoplay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/** 底部细线 */
@Composable fun Modifier.bottomLine(color: Color = c.l1, w: Dp = 1.dp, ext: Dp = 0.dp) = if (Look.flat) this else drawBehind {
    drawRect(color, Offset(-ext.toPx(), size.height - w.toPx()), Size(size.width + ext.toPx() * 2, w.toPx()))
}
@Composable fun Modifier.topLine(color: Color = c.k, w: Dp = 3.dp) = if (Look.flat) this else drawBehind { drawRect(color, Offset.Zero, Size(size.width, w.toPx())) }

// ======================= 主页 =======================
@Composable fun HomePage() {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    val daily by Store.daily.collectAsState()
    val pls by Store.playlists.collectAsState()
    val rec by Store.recents.collectAsState()
    val cur = st.current ?: daily.firstOrNull()
    val now = remember { Calendar.getInstance() }
    val mix = daily.take(10)
    val liked = pls.firstOrNull { it.liked }
    val recPl by produceState<List<Playlist>>(emptyList()) { runCatching { value = Ncm.recPlaylists() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 24.dp)) {
        // 首页模块：按「首页布局」里的顺序和开关排列
        HomeLayout.visible.forEachIndexed { mi, m ->
            when (m) {
                "combo" -> item { HomeCombo(mi > 0) }
                "continue" -> {
                // ---- CONTINUE LISTENING
                item {
                    Column(Modifier.padding(top = if (mi > 0) 32.dp else 0.dp)) {
                        Tag("CONTINUE LISTENING"); Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Box(Modifier.press { if (st.current != null) { nav.lyrics = false; nav.player = true } else if (daily.isNotEmpty()) Engine.playList(daily, 0) }) { Cover(cur?.cover, 128.dp, cur?.title ?: "") }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                T(cur?.title ?: "还没有曲目", Fs.PAGE, w = 800, lines = 3, lh = 1.1f)
                                Spacer(Modifier.height(4.dp)); T(cur?.artist ?: "登录网易云后开始播放", 14, c.s2)
                                Spacer(Modifier.height(16.dp))
                                if (st.current != null) { TimeRow(st.current!!.durationMs); Spacer(Modifier.height(8.dp)); ScrubBar(st.current!!.durationMs, 4.dp, interactive = false) }
                                else { Row { T("00:00", 12, c.p, mono = true, modifier = Modifier.weight(1f)); T(fmt(cur?.durationMs ?: 0), 12, c.s, mono = true) }; Spacer(Modifier.height(8.dp)); Box(Modifier.fillMaxWidth().height(4.dp).background(c.l3)) }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth()) { // 首页这条播放按钮在宽屏也保持整条
                            PrimaryBar(if (st.playing) "暂停" else if (st.current != null) "继续播放" else "播放", if (st.playing) NIcons.pause else NIcons.play, Modifier.weight(1f)) {
                                if (st.current != null) Engine.toggle() else if (daily.isNotEmpty()) Engine.playList(daily, 0)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlineBtn("打开队列") { if (st.current == null && daily.isNotEmpty()) Engine.playList(daily, 0); nav.queue = true }
                        }
                    }
                }
                }
                "daily" -> {
                // ---- DAILY MIX
                item {
                    Column(Modifier.padding(top = 32.dp)) {
                        Tag("DAILY MIX")
                        Row(Modifier.padding(top = 10.dp).bottomLine(c.k, 3.dp).padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            // 可点的标题块：50% 灰色细框，一看就知道能点进去
                            Row(Modifier.weight(1f)) {
                                Column(Modifier.border(1.dp, Color.Gray.copy(alpha = .5f)).press { nav.push(Page.Detail("daily", "daily", "每日推荐", daily.firstOrNull()?.cover)) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) { T("每日推荐", Fs.PAGE, w = 800, ls = .08f, lh = 1.15f); Spacer(Modifier.width(6.dp)); NIcon(NIcons.chevronRight, 20.dp, c.s) }
                                    Spacer(Modifier.height(4.dp))
                                    T(if (daily.isNotEmpty()) "${daily.size} 首 · 点开查看全部" else "DAILY RECOMMENDATION", 11, c.s, mono = true, ls = .08f)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            // 日期在左、播放按钮在右
                            Column(horizontalAlignment = Alignment.End) {
                                T(two(now.get(Calendar.MONTH) + 1), 46, c.p, 600, mono = true, ls = -.05f, lh = .9f)
                                T(two(now.get(Calendar.DAY_OF_MONTH)), 46, c.p, 600, mono = true, ls = -.05f, lh = .9f)
                            }
                            Spacer(Modifier.width(14.dp))
                            Box(Modifier.border(1.dp, Color.Gray.copy(alpha = .5f))) { SquareBtn(NIcons.play) { if (mix.isNotEmpty()) Engine.playList(mix, 0) } }
                        }
                        if (mix.isEmpty()) T("LOADING", 12, c.s, mono = true, ls = .06f, modifier = Modifier.padding(vertical = 22.dp))
                    }
                }
                itemsIndexed(mix, key = { _, t -> "d" + t.id }) { i, t -> TrackRow(t, st.current?.id == t.id, null, onLong = { trackActions(nav, t) }) { Engine.playList(mix, i) } }
                if (daily.size > mix.size) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { LinkBtn("查看全部 ${daily.size} 首") { nav.push(Page.Detail("daily", "daily", "每日推荐", daily.firstOrNull()?.cover)) } } }
                }
                "fm" -> {
                // ---- PERSONAL FM
                item {
                    Column(Modifier.padding(top = 32.dp)) {
                        Tag("PERSONAL FM")
                        Row(Modifier.padding(top = 10.dp).fillMaxWidth().press { nav.push(Page.Fm) }
                            .bottomLine(c.k, 3.dp).padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (st.fm && st.current != null) Cover(st.current!!.cover, 56.dp, st.current!!.title) else IconCover(NIcons.radio, 56.dp, true)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                T("私人 FM", Fs.PAGE, w = 800, ls = .08f, lh = 1.15f)
                                Spacer(Modifier.height(4.dp))
                                T(if (st.fm && st.current != null) "正在播放 · ${st.current!!.title}" else "按你的口味无限连播", 11, c.s, mono = true, ls = .06f)
                            }
                            SquareBtn(if (st.fm && st.playing) NIcons.pause else NIcons.play, filled = true) { if (st.fm) Engine.toggle() else Engine.startFm { nav.toast = it } }
                        }
                    }
                }
                }
                "discover" -> {
                // ---- DISCOVER
                item {
                    Column(Modifier.padding(top = 32.dp)) {
                        TagRow("DISCOVER"); Spacer(Modifier.height(2.dp))
                        T("发现", 24, w = 800, lh = 1.4f); Spacer(Modifier.height(10.dp))
                        val tiles = buildList {
                            add(Triple("排行榜", "TOP CHARTS") { nav.push(Page.Playlists("top", "", "排行榜")) })
                            add(Triple("歌单广场", "PLAYLISTS") { nav.push(Page.Square) })
                            add(Triple("新碟上架", "NEW ALBUMS") { nav.push(Page.NewAlbums) })
                            add(Triple("新歌速递", "NEW SONGS") { nav.push(Page.Detail("newsong", "0", "新歌速递")) })
                            add(Triple("MV 精选", "MUSIC VIDEO") { nav.push(Page.Mvs) })
                            add(Triple("播客电台", "PODCAST") { nav.push(Page.Radios) })
                            if (Ncm.loggedIn) add(Triple("历史日推", "HISTORY") { nav.push(Page.History) })
                        }
                        tiles.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (t, en, go) ->
                                    Column(Modifier.weight(1f).press(.97f, onClick = go).nbf(1.dp, c.k).padding(horizontal = 14.dp, vertical = 12.dp)) {
                                        T(en, 11, c.s, 700, mono = true, ls = .08f); Spacer(Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) { T(t, 17, w = 800, modifier = Modifier.weight(1f)); NIcon(NIcons.chevronRight, 16.dp, c.s) }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                }
                "foryou" -> {
                // ---- 推荐歌单
                if (recPl.isNotEmpty()) {
                    item { Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) { TagRow("FOR YOU", 12.dp) { LinkBtn("全部") { nav.push(Page.Playlists("rec", "", "推荐歌单")) } } } }
                    item {
                        LazyRow(Modifier.fillMaxWidth().layoutNoPad(18.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(recPl.take(12), key = { "rp" + it.id }) { p -> CardItem({ Cover(p.cover, 132.dp, p.name) }, p.name, "${two(p.count)} 首") { nav.push(Page.Detail("pl", p.id, p.name, p.cover)) } }
                        }
                    }
                }
                }
                "collections" -> {
                // ---- RECOMMENDED COLLECTIONS
                item {
                    Column(Modifier.padding(top = 32.dp)) {
                        TagRow("RECOMMENDED COLLECTIONS"); Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) { T("推荐合集", 24, w = 800, lh = 1.4f, modifier = Modifier.weight(1f)); LinkBtn("查看全部") { nav.tab = 1 } }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                item {
                    LazyRow(Modifier.fillMaxWidth().layoutNoPad(18.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (liked != null) item { CardItem({ LikedCover(liked.cover, 132.dp) }, "我喜欢", "${two(liked.count)} 首") { nav.push(Page.Detail("liked", liked.id, "我喜欢", liked.cover)) } }
                        item { CardItem({ RecentCover(rec.firstOrNull()?.cover, 132.dp) }, "最近播放", listMeta(rec)) { nav.push(Page.Detail("recent", "recent", "最近播放")) } }
                        items(pls.filter { !it.liked }.take(12), key = { it.id }) { p -> CardItem({ Cover(p.cover, 132.dp, p.name) }, p.name, "${two(p.count)} 首") { nav.push(Page.Detail("pl", p.id, p.name, p.cover)) } }
                    }
                }
                }
                "stats" -> {
                // ---- 统计
                item {
                    Column(Modifier.padding(top = 14.dp).topLine()) {
                        Row { Stat("我喜欢", "${two(liked?.count ?: 0)} 首", false, false); Stat("歌单", "${two(pls.count { !it.liked })} 个", true, false) }
                        Row { Stat("最近播放", "${two(rec.size)} 首", false, true); Stat("每日推荐", "${two(daily.size)} 首", true, true) }
                    }
                }
                }
            }
        }
    }
}

/** LazyRow 放在有左右边距的列表里时，抵消掉外层的 18 边距，让横向卡片能滑到屏幕边 */
fun Modifier.layoutNoPad(pad: Dp) = this.layout { m, cons ->
    val extra = pad.roundToPx() * 2
    val p = m.measure(cons.copy(maxWidth = cons.maxWidth + extra, minWidth = cons.minWidth + extra))
    layout(cons.maxWidth, p.height) { p.place(-pad.roundToPx(), 0) }
}

@Composable fun CardItem(cover: @Composable () -> Unit, title: String, meta: String, onClick: () -> Unit) =
    Column(Modifier.width(132.dp).press(onClick = onClick)) { cover(); Spacer(Modifier.height(8.dp)); T(title, 15, w = 700, lh = 1.35f); T(meta, 12, c.s, mono = true, ls = .04f) }

@Composable private fun RowScope.Stat(k: String, v: String, left: Boolean, top: Boolean) {
    val p = c
    Column(Modifier.weight(1f).drawBehind {
        if (left) if (!Look.flat) drawRect(p.l2, size = Size(1.dp.toPx(), size.height))
        if (top) if (!Look.flat) drawRect(p.l2, size = Size(size.width, 1.dp.toPx()))
    }.padding(start = if (left) 16.dp else 0.dp, top = 12.dp, bottom = 12.dp)) { T(k, 11, c.s, mono = true, ls = .08f); T(v, 24, mono = true) }
}

// ======================= 曲库 =======================
@Composable fun ListRow(cover: @Composable () -> Unit, title: String, meta: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().height(76.dp).press(onLong = onLong, onClick = onClick).bottomLine(), verticalAlignment = Alignment.CenterVertically) {
        cover(); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { T(title, 16, w = 700, lh = 1.35f); T(meta, 12, c.s, mono = true, ls = .04f) }
        NIcon(NIcons.chevronRight, 16.dp)
    }

@Composable fun LibraryPage() {
    val nav = LocalNav.current
    val pls by Store.playlists.collectAsState()
    val rec by Store.recents.collectAsState()
    val prof by Store.profile.collectAsState()
    val liked = pls.firstOrNull { it.liked }
    val others = pls.filter { !it.liked }
    val ver by Store.plVersion.collectAsState()
    val subAlbums by produceState<List<Album>?>(null, prof, ver) { if (prof != null) runCatching { value = Ncm.subAlbums() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp)) {
        item {
            Column {
                Tag("LIBRARY"); Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    T("曲库", Fs.HERO, w = 800, lh = 1.3f, modifier = Modifier.weight(1f))
                    if (prof != null) {
                        val signed by Store.signedToday.collectAsState()
                        if (signed) Box(Modifier.defaultMinSize(44.dp, 44.dp), contentAlignment = Alignment.Center) { Box(Modifier.height(28.dp).background(c.l3).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { T("已签到", 12, c.s, 700) } }
                        else LinkBtn("签到", 14) { Store.run { nav.toast = runCatching { Ncm.signin().also { (ok, _) -> if (ok) Store.markSigned() }.second }.getOrElse { "签到失败：${it.message}" } } }
                        Spacer(Modifier.width(6.dp)); LinkBtn("新建歌单", 14) { newPlaylist(nav) }
                    }
                    else T("${two(others.size)} 个歌单", 13, c.s, mono = true, ls = .06f, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(10.dp)); Rule()
            }
        }
        if (liked != null) item { ListRow({ LikedCover(liked.cover, 56.dp) }, "我喜欢", "${two(liked.count)} 首") { nav.push(Page.Detail("liked", liked.id, "我喜欢", liked.cover)) } }
        item { ListRow({ RecentCover(rec.firstOrNull()?.cover, 56.dp) }, "最近播放", listMeta(rec)) { nav.push(Page.Detail("recent", "recent", "最近播放")) } }
        item { val dls by Downloads.items.collectAsState(); ListRow({ IconCover(NIcons.download, 56.dp) }, "本地下载", "${two(dls.size)} 首") { nav.push(Page.DlList) } }
        item { ListRow({ IconCover(NIcons.chart, 56.dp) }, "听歌统计", "本机的播放次数和时长") { nav.push(Page.Stats) } }
        item { val loc by LocalMusic.list.collectAsState(); ListRow({ IconCover(NIcons.disc, 56.dp) }, "本地音乐", if (loc.isEmpty()) "扫描手机 / 电脑里的歌" else "${two(loc.size)} 首") { nav.push(Page.Local) } }
        if (prof == null) item {
            Column(Modifier.padding(vertical = 24.dp)) {
                T("NOT LOGGED IN", 12, c.s, mono = true, lines = 2, ls = .06f, lh = 1.7f)
                Spacer(Modifier.height(12.dp)); ActionRow { PrimaryBar("登录网易云音乐", NIcons.chevronRight, Modifier.weight(1f)) { nav.push(Page.Login) } }
            }
        }
        if (prof != null) {
            item { ListRow({ IconCover(NIcons.cloud, 56.dp) }, "云盘", "自己上传的歌") { nav.push(Page.Detail("cloud", "cloud", "云盘")) } }
        }
        val made = others.filter { it.mine }; val faved = others.filter { !it.mine }
        val albums = subAlbums ?: emptyList()
        if (made.isNotEmpty()) item { Column(Modifier.padding(top = 26.dp, bottom = 8.dp)) { TagRow("CREATED", 12.dp) { T("${two(made.size)} 个", 13, c.s, mono = true) } } }
        items(made, key = { it.id }) { p -> ListRow({ Cover(p.cover, 56.dp, p.name) }, p.name, "${two(p.count)} 首", onLong = { playlistActions(nav, p) }) { nav.push(Page.Detail("pl", p.id, p.name, p.cover)) } }
        // 收藏：歌单和专辑放在一起
        if (faved.isNotEmpty() || albums.isNotEmpty()) item { Column(Modifier.padding(top = 26.dp, bottom = 8.dp)) { TagRow("COLLECTED", 12.dp) { T("${two(faved.size + albums.size)} 个", 13, c.s, mono = true) } } }
        items(albums, key = { "al" + it.id }) { al -> ListRow({ Cover(al.pic, 56.dp, al.name) }, al.name, "专辑 · " + listOf(al.artist, if (al.size > 0) "${al.size} 首" else "").filter { it.isNotEmpty() }.joinToString(" · "),
            onLong = { nav.sheet = SheetSpec(al.name, al.artist, listOf(SheetItem("取消收藏", danger = true) { Store.run { try { Ncm.subAlbum(al.id, false); nav.toast = "已取消收藏"; Store.refreshPlaylists() } catch (e: Exception) { nav.toast = "操作失败：${e.message}" } } })) }) { nav.push(Page.Album(al.id, al.name, al.pic)) } }
        items(faved, key = { it.id }) { p -> ListRow({ Cover(p.cover, 56.dp, p.name) }, p.name, "歌单 · ${two(p.count)} 首", onLong = { playlistActions(nav, p) }) { nav.push(Page.Detail("pl", p.id, p.name, p.cover)) } }
    }
}

// ======================= 列表详情 =======================
@Composable fun DetailPage(p: Page.Detail) {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    val liked by Store.likedIds.collectAsState()
    var err by remember { mutableStateOf<String?>(null) }
    val ver by Store.plVersion.collectAsState()
    val pls by Store.playlists.collectAsState()
    val mine = pls.firstOrNull { it.id == p.id }?.mine == true && (p.kind == "pl" || p.kind == "liked")
    val tracks by produceState<List<Track>?>(null, p, ver) {
        when (p.kind) {
            "daily" -> Store.daily.collect { value = it }
            "recent" -> Store.recents.collect { value = it }
            "simi" -> try { value = Ncm.simiSongs(p.id) } catch (e: Exception) { err = e.message }
            "newsong" -> try { value = Ncm.newSongs(p.id.toIntOrNull() ?: 0) } catch (e: Exception) { err = e.message }
            "hist" -> try { value = Ncm.historySongs(p.id) } catch (e: Exception) { err = e.message }
            "cloud" -> try { value = Ncm.cloud() } catch (e: Exception) { err = e.message }
            "radio" -> try { value = Ncm.radioPrograms(p.id) } catch (e: Exception) { err = e.message }
            else -> {
                withContext(Dispatchers.IO) { Store.cachedTracks(p.id) }?.let { value = it }
                try { value = Store.tracksOf(p.id, refresh = true) } catch (e: Exception) { if (value == null) err = e.message }
            }
        }
    }
    val list = tracks ?: emptyList()
    val dls = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), dls, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)) {
        item {
            Column {
                Tag(when (p.kind) { "daily" -> "DAILY MIX"; "pl" -> "PLAYLIST"; "simi" -> "SIMILAR"; "newsong" -> "NEW SONGS"; "hist" -> "DAILY MIX"; "cloud" -> "CLOUD"; "radio" -> "PODCAST"; else -> "COLLECTION" })
                Spacer(Modifier.height(12.dp))
                Row(Modifier.height(112.dp)) {
                    when {
                        p.kind == "liked" -> LikedCover(p.cover ?: list.firstOrNull()?.cover, 112.dp)
                        p.kind == "recent" -> RecentCover(list.firstOrNull()?.cover, 112.dp)
                        else -> Cover(p.cover ?: list.firstOrNull()?.cover, 112.dp, p.title)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        T(p.title, 24, w = 800, lines = 2, lh = 1.25f)
                        Spacer(Modifier.height(4.dp))
                        T(if (tracks == null) "LOADING" else listMeta(list), 12, c.s, mono = true, ls = .04f)
                        Spacer(Modifier.weight(1f))
                        T(when (p.kind) { "daily" -> "每日更新"; "recent" -> "本机记录"; "simi" -> "相似推荐"; "newsong" -> "新歌速递"; "hist" -> "历史日推"; "cloud" -> "网易云云盘"; "radio" -> "播客电台"; else -> "网易云音乐" }, 13, c.s)
                    }
                }
                Spacer(Modifier.height(16.dp))
                ActionRow {
                    PrimaryBar("播放全部", NIcons.play, Modifier.weight(1f)) { Engine.playList(list, 0) }
                    Spacer(Modifier.width(10.dp)); OutlineBtn("随机播放", pad = 14.dp) { Engine.playList(list, 0, shuffle = true) }
                    if (p.kind != "recent" && list.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(48.dp).press(.96f, true) {
                            downloadSheet(nav, list)
                        }.nbf(1.dp, c.k), contentAlignment = Alignment.Center) { NIcon(NIcons.download, 20.dp) }
                    }
                    // 别人的歌单：可以收藏
                    if (p.kind == "pl" && Ncm.loggedIn && !mine) {
                        val subbed by produceState<Boolean?>(null, p.id) { value = runCatching { Ncm.playlistSubscribed(p.id) }.getOrNull() }
                        Spacer(Modifier.width(10.dp)); FollowBtn(subbed ?: false, "收藏", "已收藏", ready = subbed != null) { on -> Ncm.subscribePlaylist(p.id, on); Store.refreshPlaylists() }
                    }
                }
                Spacer(Modifier.height(12.dp)); Rule()
                if (err != null) T("加载失败：$err", 13, c.bad, lines = 3, modifier = Modifier.padding(vertical = 28.dp))
                else if (tracks == null) T("LOADING", 13, c.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 28.dp))
                else if (list.isEmpty()) T("NO TRACKS", 13, c.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 28.dp))
            }
        }
        itemsIndexed(list, key = { i, t -> "$i-${t.id}" }) { i, t ->
            TrackRow(t, st.current?.id == t.id, onLong = { trackActions(nav, t, if (mine) p.id else null) }) { Engine.playList(list, i) }
        }
    }
    LocateBtn(dls, list.indexOfFirst { it.id == st.current?.id }.let { if (it < 0) -1 else it + 1 })
    }
}

// ======================= 搜索 =======================
private val SEARCH_TYPES = listOf("单曲", "专辑", "歌手", "歌单", "用户", "歌词")

/** 一次搜索的结果：按类型放在不同的列表里 */
private class SearchRes(val type: Int, val tracks: List<Track> = emptyList(), val albums: List<Album> = emptyList(), val artists: List<Artist> = emptyList(),
                        val playlists: List<Playlist> = emptyList(), val users: List<UserBrief> = emptyList()) {
    val empty get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty() && users.isEmpty()
}

@Composable fun SearchPage() {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    val prefs = remember { Plat.prefs("ui") }
    var history by remember { mutableStateOf(prefs.getString("history", "")!!.split('\n').filter { it.isNotBlank() }) }
    var q by remember { mutableStateOf("") }
    var type by remember { mutableIntStateOf(0) }
    var res by remember { mutableStateOf<SearchRes?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sugg by remember { mutableStateOf<List<String>>(emptyList()) }
    val hot by produceState<List<String>>(emptyList()) { runCatching { value = Ncm.hotSearch() } }
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(Unit) { if (SearchBus.query == null) fr.requestFocus() }
    // 输入时 250ms 防抖取联想词
    LaunchedEffect(q, res) {
        if (res != null || q.isBlank()) { sugg = emptyList(); return@LaunchedEffect }
        delay(250); runCatching { sugg = Ncm.suggest(q.trim()).take(10) }
    }
    fun saveHistory(l: List<String>) { history = l; prefs.edit().putString("history", l.joinToString("\n")).apply() }
    fun run(k: String, t: Int) {
        busy = true; res = null; type = t
        job?.cancel()
        job = scope.launch {
            try {
                res = when (t) {
                    0 -> SearchRes(t, tracks = Ncm.search(k)); 1 -> SearchRes(t, albums = Ncm.searchAlbums(k)); 2 -> SearchRes(t, artists = Ncm.searchArtists(k))
                    3 -> SearchRes(t, playlists = Ncm.searchPlaylists(k)); 4 -> SearchRes(t, users = Ncm.searchUsers(k)); else -> SearchRes(t, tracks = Ncm.searchLyric(k))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { nav.toast = "搜索失败：${e.message}"; res = SearchRes(t) }
            busy = false
        }
    }
    fun go(text: String = q) {
        val k = text.trim(); if (k.isEmpty()) return
        q = k; fm.clearFocus(); sugg = emptyList()
        saveHistory((listOf(k) + history.filter { it != k }).take(10))
        run(k, type)
    }
    LaunchedEffect(SearchBus.query) { val k = SearchBus.query ?: return@LaunchedEffect; SearchBus.query = null; go(k) }
    val p = c
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            // 不要外框：只留文字输入，下方的分隔线就是边界
            Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.CenterStart) {
                if (q.isEmpty()) T("SEARCH", 13, p.s, mono = true, ls = .08f)
                BasicTextField(q, { q = it; res = null; busy = false; job?.cancel() }, Modifier.fillMaxWidth().focusRequester(fr), singleLine = true,
                    textStyle = TextStyle(color = p.k, fontSize = Fs.SUB.sp, fontFamily = Mono, letterSpacing = (Fs.SUB * .08f).sp), cursorBrush = SolidColor(p.p),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go() }))
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.defaultMinSize(44.dp, 44.dp).press { fm.clearFocus(); nav.back() }, contentAlignment = Alignment.Center) { T("取消", 15) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (Look.flat) Color.Transparent else p.l2))
        val r = res
        val searching = r != null || busy
        // 结果类型切换
        if (searching) LazyRow(Modifier.fillMaxWidth().padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(SEARCH_TYPES) { i, l ->
                val on = i == type
                Box(Modifier.press { if (!on) run(q.trim(), i) }.background(if (on) p.k else Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 6.dp)) { T(l, 13, if (on) p.tagFg else p.k, 700) }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = if (searching) 8.dp else 18.dp, bottom = 24.dp)) {
            if (!searching && q.isNotBlank() && sugg.isNotEmpty()) {
                items(sugg, key = { "s$it" }) { k ->
                    Row(Modifier.fillMaxWidth().height(48.dp).press { go(k) }.bottomLine(), verticalAlignment = Alignment.CenterVertically) {
                        NIcon(NIcons.search, 14.dp, p.s); Spacer(Modifier.width(10.dp)); T(k, 16)
                    }
                }
            } else if (!searching) {
                item { TagRow("HISTORY", 12.dp) { if (history.isNotEmpty()) LinkBtn("清空", 14) { saveHistory(emptyList()) } } }
                if (history.isEmpty()) item { T("NO HISTORY", 12, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 18.dp)) }
                items(history, key = { "h$it" }) { h ->
                    Row(Modifier.fillMaxWidth().height(48.dp).bottomLine(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).fillMaxHeight().press { go(h) }, verticalAlignment = Alignment.CenterVertically) { NIcon(NIcons.clockSmall, 14.dp, p.s); Spacer(Modifier.width(10.dp)); T(h, 16) }
                        Box(Modifier.size(44.dp).press { saveHistory(history - h) }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 12.dp) }
                    }
                }
                if (hot.isNotEmpty()) {
                    item { Box(Modifier.padding(top = 26.dp, bottom = 4.dp)) { TagRow("TRENDING", 12.dp) } }
                    itemsIndexed(hot.take(20), key = { _, h -> "t$h" }) { i, h ->
                        Row(Modifier.fillMaxWidth().height(48.dp).press { go(h) }.bottomLine(), verticalAlignment = Alignment.CenterVertically) {
                            T(two(i + 1), 13, if (i < 3) p.p else p.s, 700, mono = true, modifier = Modifier.width(34.dp)); T(h, 16, w = if (i < 3) 700 else 400)
                        }
                    }
                }
            } else {
                if (busy) item { T("SEARCHING", 12, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 18.dp)) }
                if (r != null && r.empty) item { T("NO RESULTS", 12, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 18.dp)) }
                if (r != null) {
                    itemsIndexed(r.tracks, key = { i, t -> "$i${t.id}" }) { i, t -> TrackRow(t, st.current?.id == t.id, onLong = { trackActions(nav, t) }) { Engine.playList(r.tracks, i) } }
                    items(r.albums, key = { "a" + it.id }) { al -> ListRow({ Cover(al.pic, 56.dp, al.name) }, al.name, listOf(al.artist, al.year).filter { it.isNotEmpty() }.joinToString(" · ")) { nav.push(Page.Album(al.id, al.name, al.pic)) } }
                    items(r.artists, key = { "r" + it.id }) { ar -> ListRow({ Cover(ar.pic, 56.dp, ar.name) }, ar.name, "${two(ar.songs)} 首 · ${two(ar.albums)} 张专辑") { nav.push(Page.Artist(ar.id, ar.name)) } }
                    items(r.playlists, key = { "p" + it.id }) { pl -> ListRow({ Cover(pl.cover, 56.dp, pl.name) }, pl.name, "${two(pl.count)} 首") { nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) } }
                    items(r.users, key = { "u" + it.uid }) { u -> ListRow({ Cover(u.avatar, 56.dp, u.name) }, u.name, u.signature.ifBlank { "网易云用户" }) { nav.push(Page.User(u.uid, u.name)) } }
                }
            }
        }
    }
}

// ======================= 设置 =======================
@Composable fun SettingsPage() {
    val nav = LocalNav.current
    val prof by Store.profile.collectAsState()
    val prefs = remember { Plat.prefs("ui") }
    val pctx = coil3.compose.LocalPlatformContext.current
    var cacheSize by remember { mutableStateOf(0L) }
    LaunchedEffect(nav.sheet) { cacheSize = withContext(Dispatchers.IO) { Engine.cacheBytes() } }
    var mem by remember { mutableStateOf("") }
    var analysis by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (isActive) { mem = withContext(Dispatchers.IO) { Plat.impl.memInfo() }; delay(2000) } }
    val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
    val p = c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 28.dp)) {
        item { Group("ACCOUNT") }
        item {
            Row(Modifier.fillMaxWidth().bottomLine(p.k, 2.dp).press { prof?.let { nav.push(Page.User(it.uid, it.name)) } }.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(54.dp, 24); Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) { T(prof?.name ?: "好奇猫a", 20, w = 800); T(if (prof == null) "LOCAL ACCOUNT" else "NETEASE CLOUD MUSIC", 11, p.s, mono = true, ls = .08f) }
                if (prof != null) NIcon(NIcons.chevronRight, 16.dp, p.s)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp).bottomLine().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { T("网易云音乐", 16, w = 700); T(if (prof == null) "未登录 · 登录后可使用歌单和「我喜欢」" else "已登录 · ${prof!!.name}", 13, p.s, lh = 1.4f) }
                if (prof == null) LinkBtn("登录", 14) { nav.push(Page.Login) }
                else LinkBtn("退出登录", 14, p.bad) { Store.logout(); nav.toast = "已退出网易云音乐" }
            }
        }
        item { Group("APPEARANCE") }
        item { SwitchRow("跟随系统", "跟随系统的深浅色设置", ThemePref.mode == 0) { ThemePref.set(if (it) 0 else if (sysDark) 2 else 1) } }
        item { SwitchRow("深色模式", "切换为暗色界面", if (ThemePref.mode == 0) sysDark else ThemePref.mode == 2, ThemePref.mode != 0) { ThemePref.set(if (it) 2 else 1) } }
        item { NavRow("主题", "界面风格：BenchLab 粗线框 / 无边框", Look.NAMES[Look.style]) { nav.sheet = SheetSpec("选择主题", null, Look.NAMES.mapIndexed { i, n -> SheetItem((if (i == Look.style) "✓  " else "    ") + n) { Look.set(i) } }) } }
        item { NavRow("字体", "默认 Inter 无衬线；也可以改用系统字体", if (Typo.sans == 0) "系统字体" else "Inter") { Typo.set(1 - Typo.sans) } }
        item { MonetSettings() }
        item { NavRow("首页布局", "调整首页模块的顺序，或关掉不需要的", "${HomeLayout.visible.size}/${HomeLayout.ALL.size}") { nav.push(Page.HomeLayout) } }
        item { NavRow("自定义背景", "选一张图片，可调模糊、透明度、位置和大小", if (Bg.on) "已设置" else "未设置") { nav.push(Page.Background) } }
        if (Bg.on) item {
            SetLine("栏位不透明度", "有自定义背景时，侧边栏、顶栏、播放条的不透明度") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { NSlider(UiPref.barAlpha, 0f..1f, onDone = { UiPref.save() }) { UiPref.barAlpha = it } }
                    Spacer(Modifier.width(10.dp)); T("%.0f%%".format(UiPref.barAlpha * 100), 14, p.p, 700, mono = true)
                }
            }
        }
        item { NavRow("动效", "动画形式和速度", "${AnimStyle.labels[AnimStyle.style]} · ${Anim.labels[Anim.level]}") { nav.push(Page.Tuning) } }
        item { Group("LYRICS") }
        item {
            SetLine("歌词字号", "歌词页、播放页右栏") {
                T("今も頭上を回って輝く", LyricPref.size.toFloat(), p.k, 800)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { NSlider(LyricPref.size.toFloat(), 16f..40f, onDone = { LyricPref.set(LyricPref.size) }) { LyricPref.size = it.toInt() } }
                    Spacer(Modifier.width(10.dp)); T("${LyricPref.size}", 14, p.p, 700, mono = true)
                }
            }
        }
        item { SetLine("当前句位置", "正在唱的那一句停在哪：顶部、第二行（上面保留上一句）、偏上或居中") { ChipRow(LyricPref.ANCHORS, LyricPref.anchor) { LyricPref.changeAnchor(it) } } }
        if (!Plat.isDesktop) item { SwitchRow("通知栏歌词", "播放通知里显示当前这句歌词；也会发送状态栏歌词（魅族协议，配合状态栏歌词模块）", DeskLyric.notify) { DeskLyric.notify = it; DeskLyric.save() } }
        item { SwitchRow("逐字高亮", "有逐字歌词的歌，当前句一个字一个字亮起来", UiPref.karaoke) { UiPref.karaoke = it; UiPref.save() } }
        item { SwitchRow("远处歌词模糊", "离当前句较远的行轻微模糊（更有层次，略耗性能）", UiPref.lyricBlur) { UiPref.lyricBlur = it; UiPref.save() } }
        item { Group("PLAYBACK") }
        item { NavRow(if (Plat.isDesktop) "音质" else "Wi-Fi 音质", "在线播放的档位，拿不到时自动降一档", Ncm.LEVEL_LABELS[Engine.quality.coerceIn(0, 4)]) { qualitySheet(nav) } }
        if (!Plat.isDesktop) item { NavRow("流量音质", "用手机流量时换成这个档位", if (Engine.qualityCell < 0) "同 Wi-Fi" else Ncm.LEVEL_LABELS[Engine.qualityCell]) { cellQualitySheet(nav) } }
        item { NavRow("效果器", "10 段均衡器、低音增强、环绕、响度", if (FxStore.fx.on) FxStore.fx.preset else "关") { nav.push(Page.Effects) } }
        item { val dls by Downloads.items.collectAsState(); NavRow("本地下载", "按当前音质下载，附带歌词 · ${Downloads.dir.absolutePath}", "${dls.size} 首") { nav.push(Page.DlList) } }
        item { val ms = listOf(0, 150, 300, 800); SetLine("淡入淡出", "暂停、继续和切歌时音量渐变") { ChipRow(listOf("关", "短", "中", "长"), ms.indexOf(Engine.fadeMs).coerceAtLeast(0)) { Engine.setFade(ms[it]) } } }
        item { SwitchRow("无缝播放", "快播完时提前把下一首接上，歌和歌之间不会断一下（现场专辑、连续曲目）", Engine.gapless) { Engine.changeGapless(it) } }
        item { SetLine("播放页按钮", "选择播放页底部那排要显示的按钮") { MultiChipRow(UiPref.BTN_NAMES, { UiPref.BTN_KEYS[it] in UiPref.btns }) { UiPref.toggleBtn(UiPref.BTN_KEYS[it]) } } }
        item { SwitchRow("进度条显示缓存", "进度条上用深一点的颜色标出已经下载好的部分", UiPref.showBuffer) { UiPref.showBuffer = it; UiPref.save() } }
        item {
            NavRow("播放缓存", "听过的歌存在本地，再听不用重新下载（上限 1GB）", "%.0f MB".format(cacheSize / 1048576.0)) {
                nav.sheet = SheetSpec("播放缓存", "已用 %.0f MB / 1024 MB".format(cacheSize / 1048576.0), listOf(SheetItem("清空播放缓存", danger = true) {
                    scope.launch { withContext(Dispatchers.IO) { Engine.clearCache() }; cacheSize = 0; nav.toast = "已清空播放缓存" }
                }))
            }
        }
        if (Plat.isDesktop) {
            item { Group("DESKTOP") }
            item { SwitchRow("后台留存", "关闭窗口时缩到系统托盘继续播放；托盘图标可以播放 / 暂停、切歌、退出", UiPref.tray) { UiPref.tray = it; UiPref.save() } }
            item { SwitchRow("桌面歌词", "置顶的悬浮歌词窗：鼠标移上去可以切歌、调字号、锁定；托盘菜单也能开关", DeskLyric.on) { DeskLyric.on = it; DeskLyric.save() } }
            if (DeskLyric.on) item { SwitchRow("锁定桌面歌词", "锁定后不能拖动，鼠标点击会穿过歌词（Windows）", DeskLyric.locked) { DeskLyric.locked = it; DeskLyric.save() } }
            item { SwitchRow("Discord 状态", "在 Discord 上显示正在听的歌（需要打开 Discord 客户端）" + if (DiscordPref.status.isNotEmpty()) " · ${DiscordPref.status}" else "", DiscordPref.on) { DiscordPref.on = it; DiscordPref.save() } }
            if (DiscordPref.on) item {
                SetLine("Discord Client ID", "在 discord.com/developers 新建一个应用（名字会显示成「正在听 xxx」），把 Application ID 填这里") {
                    Box(Modifier.fillMaxWidth().height(40.dp).nbf(1.dp, p.k).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                        if (DiscordPref.clientId.isEmpty()) T("Application ID", 13, p.s, mono = true)
                        BasicTextField(DiscordPref.clientId, { DiscordPref.clientId = it.filter { ch -> ch.isDigit() }; DiscordPref.save() }, Modifier.fillMaxWidth(), singleLine = true,
                            textStyle = TextStyle(color = p.k, fontSize = Fs.SUB.sp, fontFamily = Mono), cursorBrush = SolidColor(p.p))
                    }
                }
            }
            item { NavRow("快捷键", "播放、切歌、音量等键盘操作", "${Keys.HELP.size} 个") { nav.push(Page.KeyMap) } }
        }
        item { Group("PERFORMANCE") }
        item { val ps by Perf.stat.collectAsState(); T(Perf.line(ps), 11, p.s, mono = true, lines = 3, lh = 1.5f, modifier = Modifier.padding(vertical = 10.dp)) }
        item { val po by Perf.overlay.collectAsState(); SwitchRow("性能浮窗", "在所有页面左下角显示 CPU、帧率和 GPU", po) { Perf.overlay.value = it; prefs.edit().putBoolean("perf", it).apply() } }
        item { SwitchRow("省内存模式", "图片缓存变小、头像不播放动画、逐字高亮降到约 20 帧${if (Plat.isDesktop) "、最小化时释放内存" else ""}；图片缓存大小重启后生效", UiPref.lowMem) { UiPref.lowMem = it; UiPref.save() } }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).bottomLine().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("内存占用", 16)
                    T(mem, 13, p.s, lines = 3, lh = 1.4f)
                    if (analysis.isNotEmpty()) T(analysis, 11, p.k, mono = true, lines = 8, lh = 1.4f)
                }
                Column(horizontalAlignment = Alignment.End) {
                    LinkBtn("分析", 14) { scope.launch { analysis = withContext(Dispatchers.IO) { Plat.impl.memAnalysis() } } }
                    LinkBtn("清理缓存", 14) { SingletonImageLoader.get(pctx).memoryCache?.clear(); SingletonImageLoader.get(pctx).diskCache?.clear(); nav.toast = "已清理图片缓存" }
                }
            }
        }
        item { Group("DATA") }
        item { NavRow("WebDAV 备份", "设置、喜欢和最近播放备份到网盘，换设备一键恢复", if (Backup.last > 0) java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(Backup.last)) else "未备份") { nav.push(Page.Backup) } }
        item { Group("ABOUT") }
        item { NavRow("版本", null, "原生版 0.3.0", clickable = false) {} }
    }
}

@Composable private fun Group(tag: String) = Box(Modifier.padding(top = 22.dp, bottom = 8.dp)) { Tag(tag) }

@Composable fun SwitchRow(title: String, sub: String?, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).bottomLine().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(title, 16, if (enabled) c.k else c.s); if (sub != null) T(sub, 13, c.s, lh = 1.4f, lines = 3) }
        Box(Modifier.graphicsAlpha(if (enabled) 1f else .45f)) { NSwitch(on) { if (enabled) onChange(it) } }
    }

private fun Modifier.graphicsAlpha(a: Float) = this.graphicsLayer { alpha = a }

@Composable private fun NavRow(title: String, sub: String?, value: String?, clickable: Boolean = true, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).then(if (clickable) Modifier.press(onClick = onClick) else Modifier).bottomLine().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(title, 16); if (sub != null) T(sub, 13, c.s, lh = 1.4f, lines = 3) }
        if (value != null) T(value, 13, c.s)
        if (clickable) { Spacer(Modifier.width(6.dp)); NIcon(NIcons.chevronRight, 16.dp, c.s) }
    }


// ======================= 登录 =======================
@Composable fun LoginPage() {
    val nav = LocalNav.current
    var status by remember { mutableStateOf("正在获取二维码…") }
    var qr by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var ck by remember { mutableStateOf("") }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        try {
            val k = Ncm.qrKey()
            qr = withContext(Dispatchers.Default) { qrBitmap(Ncm.qrUrl(k)) }
            status = "用另一台设备上的网易云音乐 App 扫码，并在手机上点确认"
            while (isActive) {
                delay(2000)
                when (Ncm.qrCheck(k)) {
                    800 -> { status = "二维码已过期，点二维码刷新"; break }
                    802 -> status = "已扫码，请在手机上点「确认登录」"
                    803 -> { status = "登录成功"; Store.refreshAll(); nav.toast = "登录成功"; nav.back(); break }
                }
            }
        } catch (e: Exception) { status = "出错了：${e.message}（点二维码重试）" }
    }
    val p = c
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        TagRow("SCAN"); Spacer(Modifier.height(16.dp))
        Box(Modifier.size(240.dp).align(Alignment.CenterHorizontally).nb(1.dp, p.k).background(Color.White).press { round++ }, contentAlignment = Alignment.Center) {
            qr?.let { Image(it, "二维码", Modifier.size(220.dp)) } ?: T("…", 20, Color.Black)
        }
        Spacer(Modifier.height(14.dp)); T(status, 13, p.s, lines = 3, lh = 1.5f)
        Spacer(Modifier.height(28.dp)); TagRow("COOKIE"); Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(44.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (ck.isEmpty()) T("MUSIC_U=…", 13, p.s, mono = true)
            BasicTextField(ck, { ck = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = Fs.SUB.sp, fontFamily = Mono), cursorBrush = SolidColor(p.p))
        }
        Spacer(Modifier.height(10.dp))
        PrimaryBar("保存 Cookie", NIcons.chevronRight, Modifier.fillMaxWidth()) {
            try { Ncm.saveCookie(ck); Store.refreshAll(); nav.toast = "已保存"; nav.back() } catch (e: Exception) { nav.toast = e.message }
        }
    }
}

/** 二维码画成 ImageBitmap（两端通用） */
fun qrBitmap(text: String): androidx.compose.ui.graphics.ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 440, 440)
    val b = androidx.compose.ui.graphics.ImageBitmap(m.width, m.height)
    val cv = androidx.compose.ui.graphics.Canvas(b)
    val white = androidx.compose.ui.graphics.Paint().apply { color = Color.White }
    val black = androidx.compose.ui.graphics.Paint().apply { color = Color.Black }
    cv.drawRect(0f, 0f, m.width.toFloat(), m.height.toFloat(), white)
    for (x in 0 until m.width) for (y in 0 until m.height) if (m[x, y]) cv.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, black)
    return b
}


/** 可选布局：继续收听和每日推荐各占一半（窄屏时上下排） */
@Composable fun HomeCombo(topPad: Boolean) {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    val daily by Store.daily.collectAsState()
    val cur = st.current ?: daily.firstOrNull()
    val now = remember { Calendar.getInstance() }
    val p = c
    val left = @Composable { m: Modifier ->
        Column(m.border(1.dp, Color.Gray.copy(alpha = .5f)).padding(14.dp)) {
            Tag("CONTINUE LISTENING"); Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.press { if (st.current != null) { nav.lyrics = false; nav.player = true } else if (daily.isNotEmpty()) Engine.playList(daily, 0) }) { Cover(cur?.cover, 88.dp, cur?.title ?: "") }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { T(cur?.title ?: "还没有曲目", Fs.HEAD, w = 800, lines = 2, lh = 1.2f); T(cur?.artist ?: "", 13, p.s) }
            }
            Spacer(Modifier.height(10.dp))
            if (st.current != null) { ScrubBar(st.current!!.durationMs, 10.dp, interactive = false); TimeRow(st.current!!.durationMs) }
            Spacer(Modifier.height(8.dp))
            Row {
                PrimaryBar(if (st.playing) "暂停" else if (st.current != null) "继续播放" else "播放", if (st.playing) NIcons.pause else NIcons.play, Modifier.weight(1f)) {
                    if (st.current != null) Engine.toggle() else if (daily.isNotEmpty()) Engine.playList(daily, 0)
                }
                Spacer(Modifier.width(8.dp)); OutlineBtn("队列", pad = 12.dp) { nav.queue = true }
            }
        }
    }
    val right = @Composable { m: Modifier ->
        Column(m.border(1.dp, Color.Gray.copy(alpha = .5f)).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).press { nav.push(Page.Detail("daily", "daily", "每日推荐", daily.firstOrNull()?.cover)) }) {
                    Tag("DAILY MIX"); Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) { T("每日推荐", Fs.HEAD, w = 800); NIcon(NIcons.chevronRight, 18.dp, p.s) }
                    T("${daily.size} 首", 11, p.s, mono = true)
                }
                T("${two(now.get(Calendar.MONTH) + 1)}/${two(now.get(Calendar.DAY_OF_MONTH))}", 28, p.p, 600, mono = true)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.border(1.dp, Color.Gray.copy(alpha = .5f))) { SquareBtn(NIcons.play) { if (daily.isNotEmpty()) Engine.playList(daily, 0) } }
            }
            Spacer(Modifier.height(6.dp))
            daily.take(3).forEachIndexed { i, t -> TrackRow(t, st.current?.id == t.id, null, onLong = { trackActions(nav, t) }) { Engine.playList(daily, i) } }
        }
    }
    BoxWithConstraints(Modifier.padding(top = if (topPad) 32.dp else 0.dp)) {
        if (maxWidth >= 640.dp) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) { left(Modifier.weight(1f).fillMaxHeight()); Spacer(Modifier.width(14.dp)); right(Modifier.weight(1f).fillMaxHeight()) }
        else Column { left(Modifier.fillMaxWidth()); Spacer(Modifier.height(14.dp)); right(Modifier.fillMaxWidth()) }
    }
}
