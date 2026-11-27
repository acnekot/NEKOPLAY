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
private val SEARCH_TYPES = listOf("单曲", "专辑", "歌手", "歌单", "用户", "歌词")
private val SEARCH_KINDS = listOf("track", "album", "artist", "playlist", "user", "lyric")

/** 一次搜索的结果：按类型放在不同的列表里 */
private class SearchRes(val type: Int, val tracks: List<Track> = emptyList(), val albums: List<Album> = emptyList(), val artists: List<Artist> = emptyList(),
                        val playlists: List<Playlist> = emptyList(), val users: List<UserBrief> = emptyList(),
                        val mine: List<Playlist> = emptyList(), val page: Int = 1, val more: Boolean = false) {
    val empty get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty() && users.isEmpty() && mine.isEmpty()
    val size get() = tracks.size + albums.size + artists.size + playlists.size + users.size
    /** 翻页：追加下一页并按 id 去重；新一页没有新条目就当作到底了 */
    @Suppress("UNCHECKED_CAST")
    fun plus(v: List<Any>, kind: String, p: Int): SearchRes {
        val n = when (kind) {
            "album" -> SearchRes(type, albums = (albums + v as List<Album>).distinctBy { it.id }); "artist" -> SearchRes(type, artists = (artists + v as List<Artist>).distinctBy { it.id })
            "playlist" -> SearchRes(type, playlists = (playlists + v as List<Playlist>).distinctBy { it.id }); "user" -> SearchRes(type, users = (users + v as List<UserBrief>).distinctBy { it.uid })
            else -> SearchRes(type, tracks = (tracks + v as List<Track>).distinctBy { it.id })
        }
        return SearchRes(type, n.tracks, n.albums, n.artists, n.playlists, n.users, mine, p, n.size > size)
    }
}

/** 本地已有的歌单（主账号 + 其它音源曲库）里按名字匹配 */
private fun myPlaylists(k: String): List<Playlist> = k.trim().lowercase().let { q -> if (q.isEmpty()) emptyList() else Store.playlists.value.filter { it.name.lowercase().contains(q) }.take(20) }

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
        SearchSource.fix()
        if (SearchSource.options.isEmpty()) { nav.toast = "没有可用的音源：到 设置 → 插件 导入"; return }
        busy = true; res = null; type = t
        job?.cancel()
        job = scope.launch {
            try {
                val pid = SearchSource.plugin ?: throw NcmError("没有可用的音源")
                val kind = SEARCH_KINDS[t]
                if (kind !in (Plugins.get(pid)?.searchTypes ?: emptySet())) { nav.toast = "${Plugins.nameOf(pid)}不支持搜${SEARCH_TYPES[t]}"; res = SearchRes(t); busy = false; return@launch }
                @Suppress("UNCHECKED_CAST") val v = Plugins.search(pid, k, kind) as List<Any>
                res = SearchRes(t, mine = if (kind == "playlist") myPlaylists(k) else emptyList()).plus(v, kind, 1)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { nav.toast = "搜索失败：${e.message}"; res = SearchRes(t) }
            busy = false
        }
    }
    /** 滚到底时拉下一页 */
    fun more() {
        val r = res ?: return; if (busy || !r.more) return
        val pid = SearchSource.plugin ?: return; val kind = SEARCH_KINDS[r.type]; val k = q.trim()
        busy = true
        job = scope.launch {
            try { @Suppress("UNCHECKED_CAST") val v = Plugins.search(pid, k, kind, r.page + 1) as List<Any>; if (res === r) res = r.plus(v, kind, r.page + 1) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (res === r) res = SearchRes(r.type, r.tracks, r.albums, r.artists, r.playlists, r.users, r.mine, r.page, false) }
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
            Box(Modifier.defaultMinSize(44.dp, 44.dp).press { fm.clearFocus(); nav.back() }, contentAlignment = Alignment.Center) { T(tr("取消"), 15) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (Look.flat) Color.Transparent else p.l2))
        val r = res
        val searching = r != null || busy
        // 结果类型切换
        // 搜索来源：网易云 / QQ 音乐
        if (SearchSource.labels.size > 1) LazyRow(Modifier.fillMaxWidth().padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(SearchSource.options) { _, (l, key) ->
                val on = key == SearchSource.key
                Box(Modifier.press { if (!on) { SearchSource.key = key; if (q.isNotBlank() && (res != null || busy)) run(q.trim(), if (SEARCH_KINDS[type] !in (Plugins.get(key)?.searchTypes ?: emptySet())) 0 else type) } }
                    .then(if (on) Modifier.background(p.p) else Modifier).nbf(1.dp, if (on) p.p else p.l2).padding(horizontal = 12.dp, vertical = 5.dp)) { T(l, 12, if (on) Color.White else p.s, 700) }
            }
        }
        LazyRow(Modifier.fillMaxWidth().padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 只列出当前来源能搜的类型
            val kinds = SearchSource.plugin?.let { Plugins.get(it)?.searchTypes } ?: emptySet()
            items(SEARCH_TYPES.indices.filter { SEARCH_KINDS[it] in kinds }) { i ->
                val l = SEARCH_TYPES[i]
                val on = i == type
                Box(Modifier.press { if (!on) { if (searching && q.isNotBlank()) run(q.trim(), i) else type = i } }.background(if (on) p.k else Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 6.dp)) { T(tr(l), 13, if (on) p.tagFg else p.k, 700) }
            }
        }
        val ls = rememberLazyListState()
        val atEnd by remember { derivedStateOf { val li = ls.layoutInfo; li.totalItemsCount > 0 && (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 4 } }
        LaunchedEffect(atEnd, res) { if (atEnd) more() }
        LazyColumn(Modifier.weight(1f), state = ls, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp)) {
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
                if (r != null && r.mine.isNotEmpty()) {
                    item { TagRow("MY PLAYLISTS", 12.dp) }
                    items(r.mine, key = { "m" + it.id }, contentType = { "row" }) { pl ->
                        val sub = "${two(pl.count)} 首 · ${Plugins.sourceName(pl.id) ?: tr("我的")}"
                        ListRow({ Cover(pl.cover, 56.dp, pl.name) }, pl.name, sub) { nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) }
                    }
                    if (r.playlists.isNotEmpty()) item { Box(Modifier.padding(top = 18.dp)) { TagRow("ONLINE", 12.dp) } }
                }
                if (busy && (r == null || r.empty)) item { T("SEARCHING", 12, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 18.dp)) }
                if (r != null && r.empty) item { T("NO RESULTS", 12, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 18.dp)) }
                if (r != null) {
                    itemsIndexed(r.tracks, key = { i, t -> "$i${t.id}" }, contentType = { _, _ -> "track" }) { i, t -> TrackRow(t, st.current?.id == t.id, onLong = { trackActions(nav, t) }) { Engine.playList(r.tracks, i) } }
                    items(r.albums, key = { "a" + it.id }, contentType = { "row" }) { al -> ListRow({ Cover(al.pic, 56.dp, al.name) }, al.name, listOf(al.artist, al.year).filter { it.isNotEmpty() }.joinToString(" · ")) { nav.push(Page.Album(al.id, al.name, al.pic)) } }
                    items(r.artists, key = { "r" + it.id }, contentType = { "row" }) { ar -> ListRow({ Cover(ar.pic, 56.dp, ar.name) }, ar.name, "${two(ar.songs)} 首 · ${two(ar.albums)} 张专辑") { nav.push(Page.Artist(ar.id, ar.name)) } }
                    items(r.playlists, key = { "p" + it.id }, contentType = { "row" }) { pl -> ListRow({ Cover(pl.cover, 56.dp, pl.name) }, pl.name, "${two(pl.count)} 首") { nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) } }
                    items(r.users, key = { "u" + it.uid }, contentType = { "row" }) { u -> ListRow({ Cover(u.avatar, 56.dp, u.name) }, u.name, u.signature.ifBlank { "${Plugins.sourceName(u.uid) ?: "在线"}用户" }) { nav.push(Page.User(u.uid, u.name)) } }
                    if (busy && !r.empty) item { T("LOADING", 12, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 18.dp)) }
                }
            }
        }
    }
}

// ======================= 设置 =======================
