package cc.catsuki.nekoplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 下载好的一首歌：文件路径、格式、大小 */
data class DlItem(val track: Track, val path: String, val format: String, val bytes: Long, val at: Long)

/**
 * 下载：按顺序一首一首下（同时只跑一个，省流量也省内存），存成「歌手 - 歌名.格式」，旁边附一份 .lrc 歌词。
 * Android 存到 音乐/NEKOPLAY（失败退回 App 自己的音乐目录），电脑存到 ~/Music/NEKOPLAY。
 */
object Downloads {
    val items = MutableStateFlow<List<DlItem>>(emptyList())
    /** 正在下 / 排队中的：id → 进度（0~1，排队中是 -1） */
    val active = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val queue = Channel<Track>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kv by lazy { Plat.prefs("downloads") }
    var onMessage: (String) -> Unit = {}

    fun init() {
        items.value = runCatching {
            val a = JSONArray(kv.getString("list", "[]") ?: "[]")
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                DlItem(Track.fromJson(it.getJSONObject("t")), it.getString("p"), it.optString("f"), it.optLong("b"), it.optLong("at"))
            }.filter { File(it.path).exists() } // 被删掉的文件不再显示
        }.getOrDefault(emptyList())
        scope.launch { for (t in queue) runOne(t) }
    }

    val dir: File get() = Plat.impl.downloadDir().also { it.mkdirs() }

    fun has(id: String) = items.value.any { it.track.id == id }
    fun file(id: String): File? = items.value.firstOrNull { it.track.id == id }?.let { File(it.path) }?.takeIf { it.exists() }

    fun download(t: Track) {
        if (has(t.id) || t.id in active.value) return
        active.value = active.value + (t.id to -1f)
        queue.trySend(t)
    }

    /** 整个列表加进下载队列，返回实际新加的首数 */
    fun downloadAll(list: List<Track>): Int = list.count { t -> (!has(t.id) && t.id !in active.value).also { if (it) download(t) } }

    fun delete(id: String) {
        val it = items.value.firstOrNull { x -> x.track.id == id } ?: return
        runCatching { File(it.path).delete(); File(it.path.substringBeforeLast('.') + ".lrc").delete() }
        items.value = items.value - it
        save()
    }

    private suspend fun runOne(t: Track) {
        try {
            val s = Ncm.stream(t.id, Engine.quality)
            val ext = s.format.ifEmpty { "mp3" }
            val base = safe("${t.artist} - ${t.title}")
            var f = File(dir, "$base.$ext")
            if (f.exists()) f = File(dir, "$base (${t.id}).$ext")
            val tmp = File(dir, ".${t.id}.part")
            Ncm.http.newCall(Request.Builder().url(s.url).header("User-Agent", "Mozilla/5.0").header("Referer", "https://music.163.com/").build()).execute().use { r ->
                if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                val body = r.body ?: throw Exception("空响应")
                val total = body.contentLength()
                var got = 0L; var last = 0L
                body.byteStream().use { inp -> tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (total > 0 && got - last > 256 * 1024) { last = got; active.value = active.value + (t.id to got.toFloat() / total) }
                    }
                } }
            }
            if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
            // 歌词：有翻译就合并成双语 LRC
            runCatching {
                val d = Ncm.lyricFull(t.id)
                if (d.lrc.isNotBlank()) File(dir, f.nameWithoutExtension + ".lrc").writeText(if (d.tr.isNullOrBlank()) d.lrc else d.lrc + "\n" + d.tr)
            }
            Plat.impl.mediaScan(f)
            items.value = listOf(DlItem(t, f.absolutePath, ext.uppercase(), f.length(), System.currentTimeMillis())) + items.value.filter { it.track.id != t.id }
            save()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onMessage("下载失败「${t.title}」：${e.message}") }
        } finally {
            active.value = active.value - t.id
            if (active.value.isEmpty()) withContext(Dispatchers.Main) { onMessage("下载完成") }
        }
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifEmpty { "track" }

    private fun save() {
        val a = JSONArray()
        items.value.forEach { a.put(JSONObject().put("t", it.track.toJson()).put("p", it.path).put("f", it.format).put("b", it.bytes).put("at", it.at)) }
        kv.edit().putString("list", a.toString()).apply()
    }
}

/** 本地下载列表：正在下的在上面，下好的按时间倒序 */
@androidx.compose.runtime.Composable fun DownloadsPage() {
    val nav = LocalNav.current
    val items by Downloads.items.collectAsState()
    val act by Downloads.active.collectAsState()
    val st by Engine.state.collectAsState()
    val list = items.map { it.track }
    val total = items.sumOf { it.bytes }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("DOWNLOADS"); Spacer(Modifier.height(4.dp))
                T("本地下载", Fs.HERO, w = 800, lh = 1.3f)
                T("${two(items.size)} 首 · %.0f MB".format(total / 1048576.0) + if (act.isNotEmpty()) " · 下载中 ${act.size}" else "", 12, c.s, mono = true, ls = .04f)
                T(Downloads.dir.absolutePath, 12, c.s, lines = 2)
                Spacer(Modifier.height(14.dp))
                if (list.isNotEmpty()) ActionRow {
                    PrimaryBar("播放全部", NIcons.play, Modifier.weight(1f)) { Engine.playList(list, 0) }
                    Spacer(Modifier.width(10.dp)); OutlineBtn("随机播放", pad = 14.dp) { Engine.playList(list, 0, shuffle = true) }
                }
                Spacer(Modifier.height(12.dp)); Rule()
                if (items.isEmpty() && act.isEmpty()) T("还没有下载的歌：长按歌曲选「下载」，或在歌单页点下载按钮", 13, c.s, lines = 3, modifier = Modifier.padding(vertical = 28.dp))
            }
        }
        if (act.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().bottomLine().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                T("正在下载 ${act.size} 首", 14, modifier = Modifier.weight(1f))
                val cur = act.values.firstOrNull { it >= 0 }
                if (cur != null) T("%.0f%%".format(cur * 100), 13, c.p, 700, mono = true)
            }
        }
        itemsIndexed(list, key = { _, t -> "dl" + t.id }) { i, t ->
            TrackRow(t, st.current?.id == t.id, items[i].format, onLong = { trackActions(nav, t) }) { Engine.playList(list, i) }
        }
    }
}
