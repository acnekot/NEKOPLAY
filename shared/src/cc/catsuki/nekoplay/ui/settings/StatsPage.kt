package cc.catsuki.nekoplay.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.catsuki.nekoplay.ChipRow
import cc.catsuki.nekoplay.Engine
import cc.catsuki.nekoplay.Fs
import cc.catsuki.nekoplay.LinkBtn
import cc.catsuki.nekoplay.LocalNav
import cc.catsuki.nekoplay.Rule
import cc.catsuki.nekoplay.SheetItem
import cc.catsuki.nekoplay.SheetSpec
import cc.catsuki.nekoplay.Stats
import cc.catsuki.nekoplay.T
import cc.catsuki.nekoplay.Tag
import cc.catsuki.nekoplay.TrackRow
import cc.catsuki.nekoplay.c
import cc.catsuki.nekoplay.tr
import cc.catsuki.nekoplay.two

@Composable fun StatsPage() {
    val nav = LocalNav.current
    val p = c
    val songs by Stats.songs.collectAsState()
    val days by Stats.days.collectAsState()
    val st by Engine.state.collectAsState()
    var range by remember { mutableIntStateOf(1) } // 0 今天 / 1 7 天 / 2 30 天 / 3 全部
    val last30 = remember(days) { Stats.lastDays(30) }
    val total = when (range) { 0 -> days[Stats.today()] ?: 0; 1 -> last30.takeLast(7).sumOf { it.second }; 2 -> last30.sumOf { it.second }; else -> days.values.sum() }
    val top = remember(songs) { songs.values.sortedWith(compareByDescending<Stats.Song> { it.plays }.thenByDescending { it.sec }).take(20) }
    val artists = remember(songs) {
        songs.values.flatMap { s -> s.t.artist.split(" / ").map { it.trim() to s.sec } }.filter { it.first.isNotEmpty() }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.entries.sortedByDescending { it.value }.take(8)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("STATS"); Spacer(Modifier.height(4.dp))
                T(tr("听歌统计"), Fs.HERO, w = 800, lh = 1.3f)
                T(tr("只统计这台设备上的播放"), 13, p.s)
                Spacer(Modifier.height(14.dp))
                ChipRow(listOf("今天", "7 天", "30 天", "全部"), range) { range = it }
                Spacer(Modifier.height(14.dp))
                T(Stats.fmtTime(total), Fs.DISPLAY, p.p, 800, mono = true)
                Spacer(Modifier.height(16.dp))
                val d14 = last30.takeLast(14); val mx = (d14.maxOfOrNull { it.second } ?: 0).coerceAtLeast(60)
                Row(Modifier.fillMaxWidth().height(110.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    d14.forEach { (d, s) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height((90f * s / mx).coerceAtLeast(2f).dp).background(if (d == Stats.today()) p.p else p.k.copy(alpha = .25f)))
                            Spacer(Modifier.height(4.dp)); T(d.takeLast(2), 10, p.s, mono = true)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp)); Rule()
                if (artists.isNotEmpty()) {
                    Box(Modifier.padding(top = 16.dp, bottom = 6.dp)) { Tag("TOP ARTISTS") }
                    val amx = artists.first().value.coerceAtLeast(1)
                    artists.forEach { (a, s) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(a, 14, w = 600, lines = 1, modifier = Modifier.width(120.dp))
                            Box(Modifier.weight(1f).height(8.dp)) { Box(Modifier.fillMaxWidth(s.toFloat() / amx).fillMaxHeight().background(p.p.copy(alpha = .7f))) }
                            Spacer(Modifier.width(8.dp)); T(Stats.fmtTime(s), 11, p.s, mono = true)
                        }
                    }
                }
                Box(Modifier.padding(top = 18.dp, bottom = 2.dp)) { Tag("TOP SONGS") }
                if (top.isEmpty()) T("还没有记录，听几首歌再来看看", 13, p.s, modifier = Modifier.padding(vertical = 20.dp))
            }
        }
        itemsIndexed(top, key = { _, s -> "s" + s.t.id }) { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(two(i + 1), 13, if (i < 3) p.p else p.s, 700, mono = true, modifier = Modifier.width(30.dp))
                Box(Modifier.weight(1f)) { TrackRow(s.t, st.current?.id == s.t.id, "${s.plays} 次") { Engine.playList(top.map { it.t }, i) } }
            }
        }
        if (top.isNotEmpty()) item { Box(Modifier.padding(top = 16.dp)) { LinkBtn("清空统计", 13, p.bad) { nav.sheet = SheetSpec("清空听歌统计？", "清空后无法恢复", listOf(SheetItem("清空", danger = true) { Stats.clear() })) } } }
    }
}
