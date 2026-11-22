package cc.catsuki.nekoplay

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
