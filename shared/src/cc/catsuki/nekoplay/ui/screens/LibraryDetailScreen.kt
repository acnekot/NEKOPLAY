package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.ui.settings.dirSheet
import cc.catsuki.nekoplay.ui.settings.downloadSheet
import cc.catsuki.nekoplay.ui.settings.menu

import kotlin.math.roundToInt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
@Composable fun LibraryPage() {
    val nav = LocalNav.current
    val pls by Store.playlists.collectAsState()
    val rec by Store.recents.collectAsState()
    val prof by Store.profile.collectAsState()
    val liked = pls.firstOrNull { it.liked }
    val others = pls.filter { !it.liked }
    val ver by Store.plVersion.collectAsState()
    // 主插件（首页来源）的账号；其它插件的歌单在最下面
    val main = Plugins.main()
    val subAlbums by produceState<List<Album>?>(null, prof, ver, main?.id) { if (prof != null && main?.has("subAlbums") == true) runCatching { value = Ncm.subAlbums() } }
    var albumCard by remember { mutableStateOf(Plat.prefs("ui").getInt("lib_album_style", 0) == 0) }
    var albumAll by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp)) {
        item {
            Column {
                Tag("LIBRARY"); Spacer(Modifier.height(4.dp))
                val allPlugs by Plugins.list.collectAsState()
                val cfOn = remember(allPlugs, Plugins.off) { Plugins.hasFeature("coverflow") }
                Row(verticalAlignment = Alignment.Bottom) {
                    T(tr("曲库"), Fs.HERO, w = 800, lh = 1.3f, modifier = Modifier.weight(1f))
                    if (cfOn) { LinkBtn("封面墙", 14) { nav.push(Page.CoverFlow) }; Spacer(Modifier.width(6.dp)) }
                    if (prof != null && main != null) {
                        val signed by Store.signedToday.collectAsState()
                        if (!main.has("signin")) Unit
                        else if (signed) Box(Modifier.defaultMinSize(44.dp, 44.dp), contentAlignment = Alignment.Center) { Box(Modifier.height(28.dp).background(c.l3).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { T(tr("已签到"), 12, c.s, 700) } }
                        else LinkBtn("签到", 14) { Store.run { nav.toast = runCatching { Ncm.signin().also { (ok, _) -> if (ok) Store.markSigned() }.second }.getOrElse { "签到失败：${it.message}" } } }
                        if (main.has("createPlaylist")) { Spacer(Modifier.width(6.dp)); LinkBtn("新建歌单", 14) { newPlaylist(nav) } }
                    }
                    else T(tr("${two(others.size)} 个歌单"), 13, c.s, mono = true, ls = .06f, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(10.dp)); Rule()
            }
        }
        val net = main != null
        if (net && liked != null) item { ListRow({ LikedCover(liked.cover, 56.dp, Modifier.heroAnchor("pl:${liked.id}", liked.cover, "我喜欢")) }, "我喜欢", "${two(liked.count)} 首") { SharedHero.capture("pl:${liked.id}", liked.cover, "我喜欢"); nav.push(Page.Detail("liked", liked.id, "我喜欢", liked.cover)) } }
        item { ListRow({ RecentCover(rec.firstOrNull()?.cover, 56.dp, Modifier.heroAnchor("pl:recent", rec.firstOrNull()?.cover, "最近播放")) }, "最近播放", listMeta(rec)) { SharedHero.capture("pl:recent", rec.firstOrNull()?.cover, "最近播放"); nav.push(Page.Detail("recent", "recent", "最近播放")) } }
        if (Features.on("dl")) item { val dls by Downloads.items.collectAsState(); ListRow({ IconCover(NIcons.download, 56.dp) }, "本地下载", "${two(dls.size)} 首") { nav.push(Page.DlList) } }
        if (Features.on("stats")) item { ListRow({ IconCover(NIcons.chart, 56.dp) }, "听歌统计", "本机的播放次数和时长") { nav.push(Page.Stats) } }
        if (Features.on("local")) item { val loc by LocalMusic.list.collectAsState(); ListRow({ IconCover(NIcons.disc, 56.dp) }, "本地音乐", if (loc.isEmpty()) "扫描手机 / 电脑里的歌" else "${two(loc.size)} 首") { nav.push(Page.Local) } }
        if (Features.on("davmusic")) item { ListRow({ IconCover(NIcons.cloud, 56.dp) }, "WebDAV 音乐", if (DavMusic.ready) "浏览网盘里的歌" else "填好地址后直接播放网盘里的歌") { nav.push(Page.Dav) } }
        // 本机的喜欢和歌单：不需要账号
        item {
            val ll by LocalLib.liked.collectAsState()
            val lls by LocalLib.lists.collectAsState()
            Column {
                Column(Modifier.padding(top = 26.dp, bottom = 8.dp)) { TagRow("ON THIS DEVICE", 12.dp) { LinkBtn("新建本地歌单", 13) { newLocalPlaylist(nav) } } }
                ListRow({ LikedCover(ll.firstOrNull { it.cover != null }?.cover, 56.dp) }, "本机喜欢", if (ll.isEmpty()) "本地歌和没登录的来源点喜欢会记在这里" else "${two(ll.size)} 首") { nav.push(Page.Detail("llike", "llike", "本机喜欢")) }
                lls.forEach { l ->
                    ListRow({ Cover(l.tracks.firstOrNull { it.cover != null }?.cover, 56.dp, l.name) }, l.name, "本地歌单 · ${two(l.tracks.size)} 首", onLong = { localPlaylistActions(nav, l.id, l.name) }) { nav.push(Page.Detail("lpl", l.id, l.name)) }
                }
            }
        }
        if (main != null && prof == null && main.has("loginQr")) item {
            Column(Modifier.padding(vertical = 24.dp)) {
                T("NOT LOGGED IN", 12, c.s, mono = true, lines = 2, ls = .06f, lh = 1.7f)
                Spacer(Modifier.height(12.dp)); ActionRow { PrimaryBar("登录" + main.name, NIcons.chevronRight, Modifier.weight(1f)) { nav.push(Page.PluginLogin(main.id)) } }
            }
        }
        if (main != null && prof != null && main.has("cloud")) {
            item { ListRow({ IconCover(NIcons.cloud, 56.dp) }, "云盘", "自己上传的歌") { nav.push(Page.Detail("cloud", "cloud", "云盘")) } }
        }
        val made = if (net) others.filter { it.mine } else emptyList(); val faved = if (net) others.filter { !it.mine } else emptyList()
        val albums = if (net) subAlbums ?: emptyList() else emptyList()
        if (albums.isNotEmpty()) {
            fun openAl(al: Album) { SharedHero.capture("alb:${al.id}", al.pic, al.name); nav.push(Page.Album(al.id, al.name, al.pic)) }
            fun longAl(al: Album) {
                nav.sheet = SheetSpec(al.name, al.artist, listOf(SheetItem("取消收藏", danger = true) {
                    Store.run { try { Ncm.subAlbum(al.id, false); nav.toast = "已取消收藏"; Store.refreshPlaylists() } catch (e: Exception) { nav.toast = "操作失败：${e.message}" } }
                }))
            }
            item {
                Column(Modifier.padding(top = 26.dp, bottom = 6.dp)) {
                    TagRow("ALBUMS · 专辑", 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            T(trKnown("${two(albums.size)} 张"), 12, c.s, mono = true)
                            LinkBtn(if (albumCard) "列表" else "卡片", 12) {
                                albumCard = !albumCard
                                Plat.prefs("ui").edit().putInt("lib_album_style", if (albumCard) 0 else 1).apply()
                            }
                            if (albumCard || albums.size > 5) {
                                LinkBtn(if (albumAll) "收起" else "显示全部", 12) { albumAll = !albumAll }
                            }
                        }
                    }
                    if (albumCard && !albumAll) {
                        Spacer(Modifier.height(14.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(albums, key = { "al" + it.id }, contentType = { "albumCard" }) { al ->
                                Column(Modifier.width(124.dp).press(onLong = { longAl(al) }) { openAl(al) }) {
                                    Cover(al.pic, 124.dp, al.name, modifier = Modifier.heroAnchor("alb:${al.id}", al.pic, al.name))
                                    Spacer(Modifier.height(8.dp))
                                    T(al.name, 14, w = 700, lines = 1)
                                    val sub = al.artist.ifEmpty { if (al.size > 0) "${al.size} 首" else "" }
                                    if (sub.isNotEmpty()) T(sub, 12, c.s, lines = 1, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (albumCard && albumAll) {
                val cols = if (Screen.wide) 5 else if (Screen.narrow) 2 else 3
                val rows = albums.chunked(cols)
                itemsIndexed(rows, key = { idx, r -> "al_grid_${idx}_${r.first().id}" }, contentType = { _, _ -> "albumGridRow" }) { idx, row ->
                    Row(Modifier.fillMaxWidth().padding(top = if (idx == 0) 8.dp else 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { al ->
                            Column(Modifier.weight(1f).press(onLong = { longAl(al) }) { openAl(al) }) {
                                CoverFill(al.pic, al.name, modifier = Modifier.heroAnchor("alb:${al.id}", al.pic, al.name))
                                Spacer(Modifier.height(8.dp))
                                T(al.name, 14, w = 700, lines = 1)
                                val sub = al.artist.ifEmpty { if (al.size > 0) "${al.size} 首" else "" }
                                if (sub.isNotEmpty()) T(sub, 12, c.s, lines = 1, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (!albumCard) {
                val shown = if (albumAll) albums else albums.take(5)
                items(shown, key = { "al_row_" + it.id }, contentType = { "albumRow" }) { al ->
                    ListRow({ Cover(al.pic, 56.dp, al.name, modifier = Modifier.heroAnchor("alb:${al.id}", al.pic, al.name)) }, al.name, "专辑 · " + listOf(al.artist, if (al.size > 0) "${al.size} 首" else "").filter { it.isNotEmpty() }.joinToString(" · "),
                        onLong = { longAl(al) }) { openAl(al) }
                }
            }
        }
        if (made.isNotEmpty()) item { Column(Modifier.padding(top = 26.dp, bottom = 8.dp)) { TagRow("CREATED", 12.dp) { T(trKnown("${two(made.size)} 个"), 13, c.s, mono = true) } } }
        items(made, key = { it.id }, contentType = { "pl" }) { p -> ListRow({ Cover(p.cover, 56.dp, p.name, modifier = Modifier.heroAnchor("pl:${p.id}", p.cover, p.name)) }, p.name, "${two(p.count)} 首", onLong = { playlistActions(nav, p) }) { SharedHero.capture("pl:${p.id}", p.cover, p.name); nav.push(Page.Detail("pl", p.id, p.name, p.cover)) } }
        if (faved.isNotEmpty()) item { Column(Modifier.padding(top = 26.dp, bottom = 8.dp)) { TagRow("COLLECTED", 12.dp) { T(trKnown("${two(faved.size)} 个"), 13, c.s, mono = true) } } }
        items(faved, key = { it.id }, contentType = { "pl" }) { p -> ListRow({ Cover(p.cover, 56.dp, p.name, modifier = Modifier.heroAnchor("pl:${p.id}", p.cover, p.name)) }, p.name, "歌单 · ${two(p.count)} 首", onLong = { playlistActions(nav, p) }) { SharedHero.capture("pl:${p.id}", p.cover, p.name); nav.push(Page.Detail("pl", p.id, p.name, p.cover)) } }
        // 没有任何插件：本体只剩本地功能
        if (main == null) item {
            Column(Modifier.padding(vertical = 24.dp)) {
                T("NO SOURCE", 12, c.s, mono = true, ls = .06f); Spacer(Modifier.height(6.dp))
                T("还没有在线音源插件：到 设置 → 插件 导入脚本后，这里会出现它的歌单", 13, c.s, lines = 3, lh = 1.4f)
                Spacer(Modifier.height(12.dp)); ActionRow { PrimaryBar("管理插件", NIcons.chevronRight, Modifier.weight(1f)) { nav.push(Page.PluginMgr) } }
            }
        }
        pluginLibrary(nav, except = main?.id)
    }
}

// ======================= 列表详情 =======================
@Composable fun DetailPage(p: Page.Detail) {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    var err by remember { mutableStateOf<String?>(null) }
    val ver by Store.plVersion.collectAsState()
    val pls by Store.playlists.collectAsState()
    val mine = (pls.firstOrNull { it.id == p.id }?.mine == true && (p.kind == "pl" || p.kind == "liked")) || p.kind == "lpl"
    val tracks by produceState<List<Track>?>(null, p, ver) {
        when (p.kind) {
            "daily" -> Store.daily.collect { value = it }
            "recent" -> Store.recents.collect { value = it }
            "llike" -> LocalLib.liked.collect { value = it }
            "lpl" -> LocalLib.lists.collect { l -> value = l.firstOrNull { it.id == p.id }?.tracks ?: emptyList() }
            "simi" -> try { value = Ncm.simiSongs(p.id) } catch (e: Exception) { err = e.message }
            "newsong" -> try { value = Ncm.newSongs(p.id.toIntOrNull() ?: 0) } catch (e: Exception) { err = e.message }
            "hist" -> try { value = Ncm.historySongs(p.id) } catch (e: Exception) { err = e.message }
            "cloud" -> try { value = Ncm.cloud() } catch (e: Exception) { err = e.message }
            "radio" -> try { value = Ncm.radioPrograms(p.id) } catch (e: Exception) { err = e.message }
            else -> {
                withContext(Dispatchers.IO) { Store.cachedTracks(p.id) }?.let { value = it }
                try { val fresh = Store.tracksOf(p.id, refresh = true); if (fresh != value) value = fresh } catch (e: Exception) { if (value == null) err = e.message }
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
                    val heroUrl = p.cover ?: list.firstOrNull()?.cover
                    val heroMod = Modifier.heroAnchor("pl:${p.id}", heroUrl, p.title)
                    when {
                        p.kind == "liked" -> LikedCover(heroUrl, 112.dp, heroMod)
                        p.kind == "recent" -> RecentCover(list.firstOrNull()?.cover, 112.dp, heroMod)
                        else -> Cover(heroUrl, 112.dp, p.title, modifier = heroMod)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        T(trKnown(p.title), 24, w = 800, lines = 2, lh = 1.25f)
                        Spacer(Modifier.height(4.dp))
                        T(if (tracks == null) "LOADING" else trKnown(listMeta(list)), 12, c.s, mono = true, ls = .04f)
                        Spacer(Modifier.weight(1f))
                        T(trKnown(when (p.kind) { "daily" -> "每日更新"; "recent" -> "本机记录"; "simi" -> "相似推荐"; "newsong" -> "新歌速递"; "hist" -> "历史日推"; "cloud" -> "音乐云盘"; "radio" -> "播客电台"; "llike", "lpl" -> "本机"; else -> Plugins.sourceName(p.id) ?: "在线歌单" }), 13, c.s)
                    }
                }
                Spacer(Modifier.height(16.dp))
                ActionRow {
                    PrimaryBar("播放", NIcons.play, Modifier.weight(1f)) { Engine.playList(list, 0) }
                    Spacer(Modifier.width(10.dp)); OutlineBtn("随机播放", pad = 14.dp) { Engine.playList(list, 0, shuffle = true) }
                    if (p.kind != "recent" && list.isNotEmpty() && Features.on("dl")) {
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(48.dp).press(.96f, true) {
                            downloadSheet(nav, list)
                        }.nbf(1.dp, c.k), contentAlignment = Alignment.Center) { NIcon(NIcons.download, 20.dp) }
                    }
                    // 别人的歌单：可以收藏
                    // QQ / 酷狗的歌单只会是自己建的或已收藏的（曲库里列出来的就是），不显示收藏按钮
                    if (p.kind == "pl" && Ncm.loggedIn && !mine && Plugins.sourceName(p.id) == null) {
                        val subbed by produceState<Boolean?>(null, p.id) { value = runCatching { Ncm.playlistSubscribed(p.id) }.getOrNull() }
                        Spacer(Modifier.width(10.dp)); FollowBtn(subbed ?: false, "收藏", "已收藏", ready = subbed != null) { on -> Ncm.subscribePlaylist(p.id, on); Store.refreshPlaylists() }
                    }
                }
                Spacer(Modifier.height(12.dp)); Rule()
                if (err != null) T("加载失败：$err", 13, c.bad, lines = if (Debug.on) 12 else 3, modifier = Modifier.padding(vertical = 28.dp))
                else if (tracks == null) T("LOADING", 13, c.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 28.dp))
                else if (list.isEmpty()) T("NO TRACKS", 13, c.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 28.dp))
            }
        }
        itemsIndexed(list, key = { i, t -> "$i-${t.id}" }, contentType = { _, _ -> "track" }) { i, t ->
            TrackRow(t, st.current?.id == t.id, onLong = { trackActions(nav, t, if (mine) p.id else null) }) { Engine.playList(list, i) }
        }
    }
    val targetIdx = remember(list, st.current?.id) { list.indexOfFirst { it.id == st.current?.id }.let { if (it < 0) -1 else it + 1 } }
    LocateBtn(dls, targetIdx)
    }
}

// ======================= 搜索 =======================
