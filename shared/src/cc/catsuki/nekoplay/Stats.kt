package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** 本机听歌统计：每首歌听了几次、多久，每天听了多久（只存在本机） */
object Stats {
    data class Song(val t: Track, val plays: Int, val sec: Long)
    val songs = MutableStateFlow<Map<String, Song>>(emptyMap())
    val days = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val file get() = File(Plat.impl.filesDir, "stats.json")
    private val df get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun today(): String = df.format(Date())

    fun load() = runCatching {
        if (!file.exists()) return@runCatching
        val o = JSONObject(file.readText())
        songs.value = o.optJSONObject("songs")?.let { s -> s.keys().asSequence().associateWith { k -> s.getJSONObject(k).let { Song(Track.fromJson(it.getJSONObject("t")), it.optInt("n"), it.optLong("s")) } } } ?: emptyMap()
        days.value = o.optJSONObject("days")?.let { d -> d.keys().asSequence().associateWith { d.optLong(it) } } ?: emptyMap()
    }

    /** 一首歌听完 / 切走时记一笔：听满 30 秒（或短歌听了一半）算一次播放 */
    fun add(t: Track, sec: Long) {
        if (sec < 5) return
        val counted = sec >= 30 || (t.durationMs > 0 && sec * 2000 >= t.durationMs)
        val old = songs.value[t.id]
        songs.value = songs.value + (t.id to Song(t, (old?.plays ?: 0) + if (counted) 1 else 0, (old?.sec ?: 0) + sec))
        val d = today(); days.value = days.value + (d to (days.value[d] ?: 0) + sec)
        save()
    }

    private fun save() = runCatching {
        // 只留听得最多的 800 首，文件不会无限变大
        val keep = songs.value.values.sortedByDescending { it.sec }.take(800)
        val s = JSONObject(); keep.forEach { s.put(it.t.id, JSONObject().put("t", it.t.toJson()).put("n", it.plays).put("s", it.sec)) }
        val d = JSONObject(); days.value.forEach { (k, v) -> d.put(k, v) }
        file.writeText(JSONObject().put("songs", s).put("days", d).toString())
    }

    fun clear() { songs.value = emptyMap(); days.value = emptyMap(); file.delete() }

    /** 最近 n 天（含今天）每天的秒数，从早到晚 */
    fun lastDays(n: Int): List<Pair<String, Long>> {
        val c = Calendar.getInstance(); val out = ArrayList<Pair<String, Long>>()
        repeat(n) { out += df.format(c.time) to (days.value[df.format(c.time)] ?: 0); c.add(Calendar.DAY_OF_YEAR, -1) }
        return out.reversed()
    }
    fun fmtTime(sec: Long): String = when { sec >= 3600 -> "%d 小时 %d 分".format(sec / 3600, sec % 3600 / 60); sec >= 60 -> "${sec / 60} 分钟"; else -> "$sec 秒" }
}

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
                T("听歌统计", Fs.HERO, w = 800, lh = 1.3f)
                T("只统计这台设备上的播放", 13, p.s)
                Spacer(Modifier.height(14.dp))
                ChipRow(listOf("今天", "7 天", "30 天", "全部"), range) { range = it }
                Spacer(Modifier.height(14.dp))
                T(Stats.fmtTime(total), Fs.DISPLAY, p.p, 800, mono = true)
                Spacer(Modifier.height(16.dp))
                // 最近 14 天的柱状图
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
