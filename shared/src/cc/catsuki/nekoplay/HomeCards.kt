package cc.catsuki.nekoplay

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Calendar

/**
 * 首页的并排组合：任意两个模块左右各占一半。
 * 每个模块在这里有一个「半宽卡片」版本；只有网易云有的模块在没开网易云时不显示，另一块就占满整行。
 */
@Composable fun HomePair(a: String, b: String, topPad: Boolean, showNet: Boolean) {
    val ids = listOf(a, b).filter { HomeLayout.availableNow(it) }
    if (ids.isEmpty()) return
    Row(Modifier.padding(top = if (topPad) 32.dp else 0.dp).fillMaxWidth().height(IntrinsicSize.Min)) {
        ids.forEachIndexed { i, id ->
            if (i > 0) Spacer(Modifier.width(10.dp))
            HomeCard(id, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** 推荐歌单缓存：卡片来回滚动不重复请求 */
private var recCache: List<Playlist>? = null

@Composable private fun HomeCard(id: String, m: Modifier) {
    val nav = LocalNav.current
    val st by Engine.state.collectAsState()
    val daily by Store.daily.collectAsState()
    val pls by Store.playlists.collectAsState()
    val rec by Store.recents.collectAsState()
    val p = c
    Column(m.border(1.dp, Color.Gray.copy(alpha = .5f)).padding(12.dp)) {
        when (id) {
            "continue" -> {
                val cur = st.current ?: daily.firstOrNull() ?: rec.firstOrNull()
                Tag("CONTINUE"); Spacer(Modifier.height(10.dp))
                Box(Modifier.press { if (st.current != null) { nav.lyrics = false; nav.player = true } }) { Cover(cur?.cover, 64.dp, cur?.title ?: "") }
                Spacer(Modifier.height(8.dp))
                T(cur?.title ?: "还没有曲目", 15, w = 800, lines = 2, lh = 1.2f); T(cur?.artist ?: "", 12, p.s)
                Spacer(Modifier.weight(1f).heightIn(min = 8.dp))
                PrimaryBar(if (st.playing) "暂停" else "播放", if (st.playing) NIcons.pause else NIcons.play, Modifier.fillMaxWidth()) {
                    if (st.current != null) Engine.toggle() else if (daily.isNotEmpty()) Engine.playList(daily, 0) else if (rec.isNotEmpty()) Engine.playList(rec, 0)
                }
            }
            "daily" -> {
                val now = remember { Calendar.getInstance() }
                Tag("DAILY MIX"); Spacer(Modifier.height(10.dp))
                Column(Modifier.press { nav.push(Page.Detail("daily", "daily", "每日推荐", daily.firstOrNull()?.cover)) }) {
                    T("${two(now.get(Calendar.MONTH) + 1)}/${two(now.get(Calendar.DAY_OF_MONTH))}", 30, p.p, 600, mono = true, lh = 1f)
                    Row(verticalAlignment = Alignment.CenterVertically) { T("每日推荐", 15, w = 800); NIcon(NIcons.chevronRight, 16.dp, p.s) }
                    T(if (daily.isEmpty()) "LOADING" else "${daily.size} 首", 11, p.s, mono = true)
                }
                Spacer(Modifier.weight(1f).heightIn(min = 8.dp))
                PrimaryBar("播放", NIcons.play, Modifier.fillMaxWidth()) { if (daily.isNotEmpty()) Engine.playList(daily, 0) }
            }
            "fm" -> {
                Tag("PERSONAL FM"); Spacer(Modifier.height(10.dp))
                Column(Modifier.press { nav.push(Page.Fm) }) {
                    if (st.fm && st.current != null) Cover(st.current!!.cover, 64.dp, st.current!!.title) else IconCover(NIcons.radio, 64.dp, true)
                    Spacer(Modifier.height(8.dp)); T("私人 FM", 15, w = 800)
                    T(if (st.fm && st.current != null) st.current!!.title else "按口味无限连播", 12, p.s)
                }
                Spacer(Modifier.weight(1f).heightIn(min = 8.dp))
                PrimaryBar(if (st.fm && st.playing) "暂停" else "播放", if (st.fm && st.playing) NIcons.pause else NIcons.play, Modifier.fillMaxWidth()) {
                    if (st.fm) Engine.toggle() else Engine.startFm { nav.toast = it }
                }
            }
            "discover" -> {
                Tag("DISCOVER"); Spacer(Modifier.height(6.dp))
                buildList {
                    if (Features.on("ncm")) { add("排行榜" to { nav.push(Page.Playlists("top", "", "排行榜")) }); add("歌单广场" to { nav.push(Page.Square) }); add("新碟上架" to { nav.push(Page.NewAlbums) }); add("MV 精选" to { nav.push(Page.Mvs) }) }
                    if (Features.on("local")) add("本地音乐" to { nav.push(Page.Local) })
                }.forEach { (t, go) ->
                    Row(Modifier.fillMaxWidth().press(onClick = go).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { T(t, 14, w = 700, modifier = Modifier.weight(1f)); NIcon(NIcons.chevronRight, 14.dp, p.s) }
                }
            }
            "foryou" -> {
                val l by produceState(recCache) { if (value == null) runCatching { value = Ncm.recPlaylists().also { recCache = it } } }
                Tag("FOR YOU"); Spacer(Modifier.height(10.dp))
                val pl = l?.firstOrNull()
                Column(Modifier.press { if (pl != null) nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) }) {
                    Cover(pl?.cover, 64.dp, pl?.name ?: ""); Spacer(Modifier.height(8.dp))
                    T(pl?.name ?: "LOADING", 14, w = 700, lines = 3, lh = 1.25f)
                }
                Spacer(Modifier.weight(1f).heightIn(min = 8.dp))
                LinkBtn("全部推荐", 13) { nav.push(Page.Playlists("rec", "", "推荐歌单")) }
            }
            "collections" -> {
                val liked = pls.firstOrNull { it.liked }
                Tag("COLLECTIONS"); Spacer(Modifier.height(10.dp))
                if (liked != null && Features.on("ncm")) Row(Modifier.fillMaxWidth().press { nav.push(Page.Detail("liked", liked.id, "我喜欢", liked.cover)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LikedCover(liked.cover, 40.dp); Spacer(Modifier.width(8.dp)); Column { T("我喜欢", 14, w = 700); T("${two(liked.count)} 首", 11, p.s, mono = true) }
                }
                // QQ 音乐 / 酷狗的「我喜欢」（按首页来源）
                val hs = HomeSrc.current
                val qqLiked = QqStore.playlists.collectAsState().value.firstOrNull { it.liked }.takeIf { Features.on("qq") && (hs == 1 || hs == 3) }
                val kgLiked = KgStore.playlists.collectAsState().value.firstOrNull { it.liked }.takeIf { Features.on("kugou") && (hs == 2 || hs == 3) }
                listOfNotNull(qqLiked?.let { it to "QQ 我喜欢" }, kgLiked?.let { it to "酷狗 我喜欢" }).forEach { (pl, name) ->
                    Row(Modifier.fillMaxWidth().press { nav.push(Page.Detail("pl", pl.id, name, pl.cover)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        LikedCover(pl.cover, 40.dp); Spacer(Modifier.width(8.dp)); Column { T(name, 14, w = 700); T("${two(pl.count)} 首", 11, p.s, mono = true) }
                    }
                }
                Row(Modifier.fillMaxWidth().press { nav.push(Page.Detail("recent", "recent", "最近播放")) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RecentCover(rec.firstOrNull()?.cover, 40.dp); Spacer(Modifier.width(8.dp)); Column { T("最近播放", 14, w = 700); T("${two(rec.size)} 首", 11, p.s, mono = true) }
                }
            }
            "qqcharts", "kgcharts" -> {
                val src = if (id == "qqcharts") 1 else 2
                val l by produceState(HomeSrc.charts[src], src) { if (value == null) runCatching { value = (if (src == 1) Qqm.toplists() else Kgm.toplists()).also { HomeSrc.charts[src] = it } } }
                Tag(if (src == 1) "QQ CHARTS" else "KUGOU CHARTS"); Spacer(Modifier.height(6.dp))
                val list = l
                if (list == null) T("LOADING", 11, p.s, mono = true)
                else list.take(5).forEach { pl ->
                    Row(Modifier.fillMaxWidth().press { nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(pl.cover, 32.dp, pl.name); Spacer(Modifier.width(8.dp)); T(pl.name, 13, w = 700, modifier = Modifier.weight(1f))
                    }
                }
            }
            "qqnew" -> {
                val l by produceState(HomeSrc.qqAlbums) { if (value == null) value = runCatching { Qqm.newAlbums(1, num = 20) }.getOrDefault(emptyList()).also { HomeSrc.qqAlbums = it } }
                Tag("QQ NEW ALBUMS"); Spacer(Modifier.height(6.dp))
                val list = l
                if (list == null) T("LOADING", 11, p.s, mono = true)
                else list.take(4).forEach { a ->
                    Row(Modifier.fillMaxWidth().press { nav.push(Page.Album(a.id, a.name, a.pic)) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(a.pic, 32.dp, a.name); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { T(a.name, 13, w = 700); T(a.artist, 11, p.s) }
                    }
                }
            }
            "stats" -> {
                val liked = pls.firstOrNull { it.liked }
                Tag("STATS"); Spacer(Modifier.height(8.dp))
                listOf("我喜欢" to (liked?.count ?: 0), "歌单" to pls.count { !it.liked }, "最近播放" to rec.size, "每日推荐" to daily.size).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { T(k, 13, p.s, modifier = Modifier.weight(1f)); T(two(v), 13, w = 700, mono = true) }
                }
            }
        }
    }
}
