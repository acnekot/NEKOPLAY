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
    // 首页内容来源：一个插件或混合；歌单来自这些插件的账号
    val homePl = HomeSrc.plugins
    val allPls by PluginStore.playlists.collectAsState()
    val homePls = homePl.flatMap { allPls[it.id].orEmpty() }
    val mainId = Plugins.main()?.id
    val likedLists = homePl.mapNotNull { s -> allPls[s.id]?.firstOrNull { it.liked }?.let { s to it } }
    val showRec = HomeSrc.has("recPlaylists")
    val recPl by produceState<List<Playlist>>(emptyList(), showRec) { if (showRec) runCatching { value = Ncm.recPlaylists() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 24.dp)) {
        // 首页模块：按「首页布局」里的顺序和开关排列；只有网易云有的模块在别的来源下不显示
        HomeLayout.visible.forEachIndexed { mi, m ->
            // 并排组合：两块各占一半
            HomeLayout.pairOf(m)?.let { (a, b) -> item(key = m) { HomePair(a, b, mi > 0) }; return@forEachIndexed }
            if (!HomeLayout.available(m)) return@forEachIndexed
            when (m) {
                "charts" -> homePl.filter { it.has("toplists") }.forEachIndexed { i, pl -> item(key = m + pl.id) { PluginCharts(pl.id, pl.name, mi == 0 && i == 0) } }
                "newalbums" -> homePl.filter { it.has("newAlbums") }.forEach { pl -> item(key = m + pl.id) { PluginNewAlbums(pl.id, pl.name) } }
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
                                Spacer(Modifier.height(4.dp)); T(cur?.artist ?: "选择一首歌开始播放", 14, c.s2)
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
                if (HomeSrc.has("square") || HomeSrc.has("toplists")) item {
                    Column(Modifier.padding(top = 32.dp)) {
                        TagRow("DISCOVER"); Spacer(Modifier.height(2.dp))
                        T("发现", 24, w = 800, lh = 1.4f); Spacer(Modifier.height(10.dp))
                        val tiles = buildList {
                            if (HomeSrc.has("toplists")) add(Triple("排行榜", "TOP CHARTS") { nav.push(Page.Playlists("top", "", "排行榜")) })
                            if (HomeSrc.has("square")) add(Triple("歌单广场", "PLAYLISTS") { nav.push(Page.Square) })
                            if (HomeSrc.has("newAlbums")) add(Triple("新碟上架", "NEW ALBUMS") { nav.push(Page.NewAlbums) })
                            if (HomeSrc.has("newSongs")) add(Triple("新歌速递", "NEW SONGS") { nav.push(Page.Detail("newsong", "0", "新歌速递")) })
                            if (HomeSrc.has("recMvs")) add(Triple("MV 精选", "MUSIC VIDEO") { nav.push(Page.Mvs) })
                            if (HomeSrc.has("hotRadios")) add(Triple("播客电台", "PODCAST") { nav.push(Page.Radios) })
                            if (Ncm.loggedIn && HomeSrc.has("historyDates")) add(Triple("历史日推", "HISTORY") { nav.push(Page.History) })
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
                        likedLists.forEach { (src, lk) ->
                            val name = if (likedLists.size > 1) "${src.name} 我喜欢" else "我喜欢"
                            item(key = "lk" + lk.id) { CardItem({ LikedCover(lk.cover, 132.dp) }, name, "${two(lk.count)} 首") { nav.push(if (src.id == mainId) Page.Detail("liked", lk.id, name, lk.cover) else Page.Detail("pl", lk.id, name, lk.cover)) } }
                        }
                        item { CardItem({ RecentCover(rec.firstOrNull()?.cover, 132.dp) }, "最近播放", listMeta(rec)) { nav.push(Page.Detail("recent", "recent", "最近播放")) } }
                        val others = homePls.filter { !it.liked }.take(24)
                        items(others, key = { it.id }) { p -> CardItem({ Cover(p.cover, 132.dp, p.name) }, p.name, "${two(p.count)} 首") { nav.push(Page.Detail("pl", p.id, p.name, p.cover)) } }
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

@Composable fun PluginCharts(pid: String, name: String, first: Boolean = false) {
    val nav = LocalNav.current
    var err by remember { mutableStateOf<String?>(null) }
    val lists by produceState(HomeSrc.pluginCharts[pid], pid) {
        if (value == null) try { value = Plugins.toplists(pid).also { HomeSrc.pluginCharts[pid] = it } } catch (e: Exception) { err = e.message; value = emptyList() }
    }
    Column(Modifier.padding(top = if (first) 0.dp else 32.dp)) {
        TagRow("CHARTS · " + name.uppercase()); Spacer(Modifier.height(2.dp))
        T(name + "排行榜", 24, w = 800, lh = 1.4f); Spacer(Modifier.height(10.dp))
        val l = lists
        when {
            l == null -> T("LOADING", 12, c.s, mono = true, ls = .06f)
            l.isEmpty() -> T("加载失败" + (err?.let { "：$it" } ?: ""), 13, c.s, lines = 3)
            else -> LazyRow(Modifier.fillMaxWidth().layoutNoPad(18.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(l, key = { it.id }) { p -> CardItem({ Cover(p.cover, 132.dp, p.name) }, p.name, if (p.count > 0) "${two(p.count)} 首" else "榜单") { nav.push(Page.Detail("pl", p.id, p.name, p.cover)) } }
            }
        }
    }
}

/** 首页内容来源：一个插件，或者全部开着的插件混在一起 */
object HomeSrc {
    const val MIX = "mix"
    private val sources get() = Plugins.enabledSources
    /** 选了但没开的插件退回第一个开着的 */
    val current: String get() = UiPref.homePlugin.let { v -> val s = sources.map { it.id }; if (v == MIX && s.size > 1) MIX else if (v in s) v else s.firstOrNull { it == "netease" } ?: s.firstOrNull() ?: "" }
    /** 首页要展示的插件 */
    val plugins: List<JsSource> get() = current.let { cur -> if (cur == MIX) sources else sources.filter { it.id == cur } }
    /** 首页来源里有没有插件实现了这个功能 */
    fun has(cap: String) = plugins.any { it.has(cap) }
    /** 设置里的选项：(名字, 插件 id) */
    val options: List<Pair<String, String>> get() = sources.map { it.name to it.id } + (if (sources.size > 1) listOf("混合" to MIX) else emptyList())
    fun set(v: String) {
        UiPref.homePlugin = v; UiPref.save()
        // 搜索默认也用同一个来源
        if (v != MIX) SearchSource.key = v
        SearchSource.fix()
        Store.refreshAll()     // 主账号跟着首页来源换
    }
    /** 排行榜 / 新碟缓存：首页来回滚动不重复请求 */
    val pluginCharts = HashMap<String, List<Playlist>>()
    val albums = HashMap<String, List<Album>>()
}

@Composable fun PluginNewAlbums(pid: String, name: String) {
    val nav = LocalNav.current
    val albums by produceState(HomeSrc.albums[pid], pid) { if (value == null) value = runCatching { Plugins.newAlbums(pid) }.getOrDefault(emptyList()).also { HomeSrc.albums[pid] = it } }
    val l = albums ?: return
    if (l.isEmpty()) return
    Column(Modifier.padding(top = 28.dp)) {
        TagRow("NEW ALBUMS · " + name.uppercase()); Spacer(Modifier.height(2.dp))
        T(name + "新碟", 24, w = 800, lh = 1.4f); Spacer(Modifier.height(10.dp))
        LazyRow(Modifier.fillMaxWidth().layoutNoPad(18.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(l, key = { it.id }) { a -> CardItem({ Cover(a.pic, 132.dp, a.name) }, a.name, a.artist) { nav.push(Page.Album(a.id, a.name, a.pic)) } }
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

@Composable internal fun RowScope.Stat(k: String, v: String, left: Boolean, top: Boolean) {
    val p = c
    Column(Modifier.weight(1f).drawBehind {
        if (left) if (!Look.flat) drawRect(p.l2, size = Size(1.dp.toPx(), size.height))
        if (top) if (!Look.flat) drawRect(p.l2, size = Size(size.width, 1.dp.toPx()))
    }.padding(start = if (left) 16.dp else 0.dp, top = 12.dp, bottom = 12.dp)) { T(k, 11, c.s, mono = true, ls = .08f); T(v, 24, mono = true) }
}

// ======================= 曲库 =======================
@Composable fun ListRow(cover: @Composable () -> Unit, title: String, meta: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().height(76.dp).cascadeEnter().press(onLong = onLong, onClick = onClick).bottomLine(), verticalAlignment = Alignment.CenterVertically) {
        cover(); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { T(title, 16, w = 700, lh = 1.35f); T(meta, 12, c.s, mono = true, ls = .04f) }
        NIcon(NIcons.chevronRight, 16.dp)
    }

