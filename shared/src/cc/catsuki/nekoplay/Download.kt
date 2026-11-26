package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /** 已下载曲目 ID 集合：O(1) 查询，且供单行精准订阅，避免下载进度刷屏重组整个列表 */
    val downloadedIds = MutableStateFlow<Set<String>>(emptySet())
    /** 正在下 / 排队中的：id → 进度（0~1，排队中是 -1） */
    val active = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** 是否存在任何已下载或进行中的任务：供列表行零协程短路 */
    var hasAny by mutableStateOf(false); private set
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

    private fun setItems(next: List<DlItem>) {
        items.value = next
        downloadedIds.value = next.mapTo(HashSet(next.size)) { it.track.id }
        hasAny = next.isNotEmpty() || active.value.isNotEmpty()
    }

    fun init() {
        setItems(runCatching {
            val a = JSONArray(kv.getString("list", "[]") ?: "[]")
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                DlItem(Track.fromJson(it.getJSONObject("t")), it.getString("p"), it.optString("f"), it.optLong("b"), it.optLong("at"))
            }.filter { File(it.path).exists() } // 被删掉的文件不再显示
        }.getOrDefault(emptyList()))
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

    /** 下载位置：设置里选过就用选的（写不进去时退回默认），没选就用平台默认 */
    val dir: File get() = (DlPref.custom.takeIf { it.isNotBlank() }?.let { File(it) }
        ?.takeIf { (it.isDirectory || it.mkdirs()) && it.canWrite() } ?: Plat.impl.downloadDir()).also { it.mkdirs() }

    fun has(id: String) = id in downloadedIds.value
    fun file(id: String): File? = items.value.firstOrNull { it.track.id == id }?.let { File(it.path) }?.takeIf { it.exists() }
    /** 曲目行下载状态文本（O(1) + 去重，单首下载不连累其它列表行重组） */
    fun labelNow(id: String): String? = active.value[id]?.let { if (it < 0) "等待下载" else "%.0f%%".format(it * 100) } ?: if (id in downloadedIds.value) "已下载" else null
    fun labelFlow(id: String) = combine(active, downloadedIds) { a, d ->
        a[id]?.let { if (it < 0) "等待下载" else "%.0f%%".format(it * 100) } ?: if (id in d) "已下载" else null
    }.distinctUntilChanged()

    fun download(t: Track, quality: Int = lastQuality.takeIf { it >= 0 } ?: Engine.quality) {
        if (has(t.id) || t.id in active.value) return
        failed.value = failed.value - t.id
        active.value = active.value + (t.id to -1f)
        hasAny = true
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
        hasAny = items.value.isNotEmpty() || active.value.isNotEmpty()
        if (runningId == id) running?.cancel()
    }
    fun cancelAll() { jobs.value.map { it.first.id }.forEach { cancel(it) } }
    fun retry(id: String) { failed.value[id]?.let { (t, q, _) -> download(t, q) } }
    fun retryAll() { failed.value.keys.toList().forEach { retry(it) } }
    fun clearFailed() { failed.value = emptyMap() }

    fun delete(id: String) {
        val it = items.value.firstOrNull { x -> x.track.id == id } ?: return
        runCatching { File(it.path).delete(); File(it.path.substringBeforeLast('.') + ".lrc").delete() }
        setItems(items.value - it)
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
                var got = 0L; var last = 0L; var lastMs = 0L
                body.byteStream().use { inp -> tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (total > 0 && got - last > 256 * 1024) {
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastMs >= 120L) {
                                last = got; lastMs = nowMs
                                if (t.id in active.value) active.value = active.value + (t.id to got.toFloat() / total)
                            }
                        }
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
            setItems(listOf(DlItem(t, f.absolutePath, ext.uppercase(), f.length(), System.currentTimeMillis())) + items.value.filter { it.track.id != t.id })
            save()
        } catch (e: CancellationException) {
            tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
            failed.value = failed.value + (t.id to Triple(t, quality, e.message ?: "未知错误"))
        } finally {
            active.value = active.value - t.id
            jobs.value = jobs.value.filter { it.first.id != t.id }
            hasAny = items.value.isNotEmpty() || active.value.isNotEmpty()
        }
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifEmpty { "track" }

    private fun save() {
        val a = JSONArray()
        items.value.forEach { a.put(JSONObject().put("t", it.track.toJson()).put("p", it.path).put("f", it.format).put("b", it.bytes).put("at", it.at)) }
        kv.edit().putString("list", a.toString()).apply()
    }
}

/** 自定义下载位置（空 = 平台默认）；已经下载的歌留在原来的位置 */
object DlPref {
    var custom by mutableStateOf("")
    fun load() { custom = Plat.prefs("ui").getString("dlDir", null) ?: "" }
    fun set(path: String) { custom = path; Plat.prefs("ui").edit().putString("dlDir", path).apply() }
}
