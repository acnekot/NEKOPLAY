package cc.catsuki.nekoplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
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
    /** 排队和正在下的任务（下载管理器显示用，按加入顺序） */
    val jobs = MutableStateFlow<List<Pair<Track, Int>>>(emptyList())
    /** 下载失败的：id → (歌, 音质, 原因) */
    val failed = MutableStateFlow<Map<String, Triple<Track, Int, String>>>(emptyMap())
    private val queue = Channel<Pair<Track, Int>>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kv by lazy { Plat.prefs("downloads") }
    private var running: Job? = null
    private var runningId: String? = null
    var onMessage: (String) -> Unit = {}
    /** 上次选的下载音质（-1 = 跟播放音质） */
    var lastQuality: Int
        get() = kv.getInt("q", -1)
        set(v) { kv.edit().putInt("q", v).apply() }

    fun init() {
        items.value = runCatching {
            val a = JSONArray(kv.getString("list", "[]") ?: "[]")
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                DlItem(Track.fromJson(it.getJSONObject("t")), it.getString("p"), it.optString("f"), it.optLong("b"), it.optLong("at"))
            }.filter { File(it.path).exists() } // 被删掉的文件不再显示
        }.getOrDefault(emptyList())
        scope.launch {
            for ((t, q) in queue) {
                if (t.id !in active.value) continue          // 排队时被取消了
                runningId = t.id
                running = launch { runOne(t, q) }
                running?.join(); running = null; runningId = null
                if (active.value.isEmpty()) withContext(Dispatchers.Main) { onMessage(if (failed.value.isEmpty()) "下载完成" else "下载结束，${failed.value.size} 首失败") }
            }
        }
    }

    val dir: File get() = Plat.impl.downloadDir().also { it.mkdirs() }

    fun has(id: String) = items.value.any { it.track.id == id }
    fun file(id: String): File? = items.value.firstOrNull { it.track.id == id }?.let { File(it.path) }?.takeIf { it.exists() }

    fun download(t: Track, quality: Int = lastQuality.takeIf { it >= 0 } ?: Engine.quality) {
        if (has(t.id) || t.id in active.value) return
        failed.value = failed.value - t.id
        active.value = active.value + (t.id to -1f)
        jobs.value = jobs.value + (t to quality)
        queue.trySend(t to quality)
    }

    /** 整个列表加进下载队列，返回实际新加的首数 */
    fun downloadAll(list: List<Track>, quality: Int = lastQuality.takeIf { it >= 0 } ?: Engine.quality): Int =
        list.count { t -> (!has(t.id) && t.id !in active.value).also { if (it) download(t, quality) } }

    /** 取消一首（排队中的直接移掉，正在下的中断并删掉半成品） */
    fun cancel(id: String) {
        active.value = active.value - id
        jobs.value = jobs.value.filter { it.first.id != id }
        if (runningId == id) running?.cancel()
    }
    fun cancelAll() { jobs.value.map { it.first.id }.forEach { cancel(it) } }
    fun retry(id: String) { failed.value[id]?.let { (t, q, _) -> download(t, q) } }
    fun retryAll() { failed.value.keys.toList().forEach { retry(it) } }
    fun clearFailed() { failed.value = emptyMap() }

    fun delete(id: String) {
        val it = items.value.firstOrNull { x -> x.track.id == id } ?: return
        runCatching { File(it.path).delete(); File(it.path.substringBeforeLast('.') + ".lrc").delete() }
        items.value = items.value - it
        save()
    }

    private suspend fun runOne(t: Track, quality: Int) {
        val tmp = File(dir, ".${t.id}.part")
        try {
            val s = Ncm.stream(t.id, quality)
            val ext = s.format.ifEmpty { "mp3" }
            val base = safe("${t.artist} - ${t.title}")
            var f = File(dir, "$base.$ext")
            if (f.exists()) f = File(dir, "$base (${t.id}).$ext")
            Ncm.http.newCall(Request.Builder().url(s.url).header("User-Agent", "Mozilla/5.0").header("Referer", "https://music.163.com/").build()).execute().use { r ->
                if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                val body = r.body ?: throw Exception("空响应")
                val total = body.contentLength()
                var got = 0L; var last = 0L
                body.byteStream().use { inp -> tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (total > 0 && got - last > 256 * 1024) { last = got; if (t.id in active.value) active.value = active.value + (t.id to got.toFloat() / total) }
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
        } catch (e: CancellationException) {
            tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
            failed.value = failed.value + (t.id to Triple(t, quality, e.message ?: "未知错误"))
        } finally {
            active.value = active.value - t.id
            jobs.value = jobs.value.filter { it.first.id != t.id }
        }
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifEmpty { "track" }

    private fun save() {
        val a = JSONArray()
        items.value.forEach { a.put(JSONObject().put("t", it.track.toJson()).put("p", it.path).put("f", it.format).put("b", it.bytes).put("at", it.at)) }
        kv.edit().putString("list", a.toString()).apply()
    }
}

/** 下载前选音质：选过的会记住，下次默认勾上 */
fun downloadSheet(nav: Nav, list: List<Track>) {
    val todo = list.filter { !Downloads.has(it.id) && it.id !in Downloads.active.value }
    if (todo.isEmpty()) { nav.toast = "都已经下载过了"; return }
    val cur = Downloads.lastQuality
    nav.sheet = SheetSpec(if (todo.size == 1) "下载「${todo[0].title}」" else "下载 ${todo.size} 首", "选择音质；拿不到时自动降一档",
        listOf(SheetItem((if (cur < 0) "✓  " else "    ") + "跟随播放音质（${Ncm.LEVEL_LABELS[Engine.quality.coerceIn(0, 4)]}）") {
            Downloads.lastQuality = -1; Downloads.downloadAll(todo, Engine.quality); nav.toast = "已加入下载：${todo.size} 首"
        }) + Ncm.LEVEL_LABELS.indices.reversed().map { q ->
            SheetItem((if (cur == q) "✓  " else "    ") + Ncm.LEVEL_LABELS[q]) { Downloads.lastQuality = q; Downloads.downloadAll(todo, q); nav.toast = "已加入下载：${todo.size} 首" }
        })
}

/** 本地下载：上面是下载管理器（正在下、排队、失败），下面是下好的歌 */
@androidx.compose.runtime.Composable fun DownloadsPage() {
    val nav = LocalNav.current
    val items by Downloads.items.collectAsState()
    val act by Downloads.active.collectAsState()
    val jobs by Downloads.jobs.collectAsState()
    val failed by Downloads.failed.collectAsState()
    val st by Engine.state.collectAsState()
    val p = c
    val list = items.map { it.track }
    val total = items.sumOf { it.bytes }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("DOWNLOADS"); Spacer(Modifier.height(4.dp))
                T("本地下载", Fs.HERO, w = 800, lh = 1.3f)
                T("${two(items.size)} 首 · %.0f MB".format(total / 1048576.0), 12, p.s, mono = true, ls = .04f)
                T(Downloads.dir.absolutePath, 12, p.s, lines = 2)
                Spacer(Modifier.height(14.dp))
                if (list.isNotEmpty()) ActionRow {
                    PrimaryBar("播放全部", NIcons.play, Modifier.weight(1f)) { Engine.playList(list, 0) }
                    Spacer(Modifier.width(10.dp)); OutlineBtn("随机播放", pad = 14.dp) { Engine.playList(list, 0, shuffle = true) }
                }
                Spacer(Modifier.height(12.dp)); Rule()
                if (items.isEmpty() && jobs.isEmpty() && failed.isEmpty()) T("还没有下载的歌：长按歌曲选「下载」，或在歌单页点下载按钮", 13, p.s, lines = 3, modifier = Modifier.padding(vertical = 28.dp))
            }
        }
        // ---- 下载管理器 ----
        if (jobs.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Tag("DOWNLOADING · ${jobs.size}") }
                    LinkBtn("全部取消", 13, p.bad) { Downloads.cancelAll() }
                }
            }
            items(jobs.size, key = { "j" + jobs[it].first.id }) { i ->
                val (t, q) = jobs[i]; val pr = act[t.id] ?: -1f
                Column(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { T(t.title, 15, w = 600); T("${t.artist} · ${Ncm.LEVEL_LABELS[q.coerceIn(0, 4)]}", 12, p.s) }
                        T(if (pr < 0) "等待中" else "%.0f%%".format(pr * 100), 12, if (pr < 0) p.s else p.p, 700, mono = true)
                        Box(Modifier.size(40.dp).press { Downloads.cancel(t.id) }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 12.dp, p.s) }
                    }
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).background(p.l2)) {
                        Box(Modifier.fillMaxWidth(pr.coerceIn(0f, 1f)).fillMaxHeight().background(p.p))
                    }
                }
            }
        }
        if (failed.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Tag("FAILED · ${failed.size}") }
                    LinkBtn("全部重试", 13) { Downloads.retryAll() }; Spacer(Modifier.width(6.dp)); LinkBtn("清除", 13) { Downloads.clearFailed() }
                }
            }
            val fl = failed.values.toList()
            items(fl.size, key = { "f" + fl[it].first.id }) { i ->
                val (t, _, why) = fl[i]
                Row(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { T(t.title, 15, w = 600); T(why, 12, p.bad, lines = 2) }
                    LinkBtn("重试", 13) { Downloads.retry(t.id) }
                }
            }
        }
        if (items.isNotEmpty() && (jobs.isNotEmpty() || failed.isNotEmpty())) item { Box(Modifier.padding(top = 16.dp, bottom = 4.dp)) { Tag("DOWNLOADED · ${items.size}") } }
        itemsIndexed(list, key = { _, t -> "dl" + t.id }) { i, t ->
            TrackRow(t, st.current?.id == t.id, items[i].format, onLong = { trackActions(nav, t) }) { Engine.playList(list, i) }
        }
    }
}
