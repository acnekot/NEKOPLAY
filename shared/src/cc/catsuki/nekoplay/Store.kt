package cc.catsuki.nekoplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 单条播放历史记录（按本地日期分天归档，无条数上限） */
data class RecentEntry(
    val track: Track,
    val playedAt: Long = 0L,
    val day: String = "",
) {
    fun toJson(): JSONObject = track.toJson().apply {
        if (playedAt > 0L) put("at", playedAt)
        if (day.isNotEmpty()) put("day", day)
    }
    companion object {
        fun fromJson(o: JSONObject): RecentEntry {
            val t = Track.fromJson(o)
            val at = o.optLong("at", 0L)
            val d = o.optString("day").ifEmpty { if (at > 0L) Store.dayKey(at) else "" }
            return RecentEntry(t, at, d)
        }
    }
}

/** 按天分组后的历史记录区块（供 UI 懒加载渲染与按天播放） */
data class RecentDayGroup(
    val day: String,
    val entries: List<RecentEntry>,
    val startIndex: Int,
)

/** 列表数据：先读本地缓存立即显示，再联网刷新。 */
object Store {
    private lateinit var dir: File
    private lateinit var prefs: KV
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveRecentsJob: Job? = null

    val profile = MutableStateFlow<Profile?>(null)
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val daily = MutableStateFlow<List<Track>>(emptyList())
    val likedIds = MutableStateFlow<Set<String>>(emptySet())
    val recentEntries = MutableStateFlow<List<RecentEntry>>(emptyList())
    val recents = MutableStateFlow<List<Track>>(emptyList())
    val loading = MutableStateFlow(false)
    /** 歌单内容变了（加歌 / 删歌）就 +1，详情页据此重新读取 */
    val plVersion = MutableStateFlow(0)
    /** 今天是否已签到（按本地日期记） */
    val signedToday = MutableStateFlow(false)
    private fun today() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA).format(java.util.Date())
    private fun dayFmt() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    private fun timeFmt() = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    fun dayKey(ms: Long): String = if (ms <= 0L) "" else dayFmt().format(java.util.Date(ms))
    fun timeLabel(ms: Long): String? = if (ms <= 0L) null else timeFmt().format(java.util.Date(ms))
    fun dayLabel(day: String, nowMs: Long = System.currentTimeMillis()): String {
        if (day.isEmpty()) return "更早记录"
        if (day == dayKey(nowMs)) return "今天 · $day"
        val yest = java.util.Calendar.getInstance().apply { timeInMillis = nowMs; add(java.util.Calendar.DAY_OF_YEAR, -1) }
        if (day == dayKey(yest.timeInMillis)) return "昨天 · $day"
        val parsed = runCatching { dayFmt().parse(day) }.getOrNull() ?: return day
        val c = java.util.Calendar.getInstance().apply { timeInMillis = parsed.time }
        val wd = when (c.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.SUNDAY -> "周日"
            java.util.Calendar.MONDAY -> "周一"
            java.util.Calendar.SATURDAY -> "周六"
            3 -> "周二"
            4 -> "周三"
            5 -> "周四"
            6 -> "周五"
            else -> ""
        }
        return if (wd.isNotEmpty()) "$day · $wd" else day
    }
    fun groupRecentByDay(entries: List<RecentEntry>): List<RecentDayGroup> {
        if (entries.isEmpty()) return emptyList()
        val buckets = LinkedHashMap<String, MutableList<RecentEntry>>()
        for (e in entries) buckets.getOrPut(e.day) { ArrayList() }.add(e)
        val sortedDays = buckets.keys.sortedWith(Comparator { a, b ->
            when {
                a == b -> 0
                a.isEmpty() -> 1
                b.isEmpty() -> -1
                else -> b.compareTo(a)
            }
        })
        var cursor = 0
        return sortedDays.map { d ->
            val list = buckets[d]!!
            val g = RecentDayGroup(d, list, cursor)
            cursor += list.size
            g
        }
    }
    fun markSigned() { signedToday.value = true; prefs.edit().putString("signDay", today()).apply() }
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** 在主线程跑一个联网操作（给菜单、按钮这类非 Compose 代码用） */
    fun run(block: suspend () -> Unit) = main.launch { block() }

    fun init() {
        dir = File(Plat.impl.filesDir, "cache").apply { mkdirs() }
        prefs = Plat.prefs("store")
        prefs.getString("profile", null)?.let { val o = JSONObject(it); profile.value = Profile(o.getString("u"), o.getString("n"), o.getString("a")) }
        likedIds.value = prefs.getStringSet("liked", emptySet())!!
        setRecentEntries(readRecentEntries() ?: emptyList(), save = false)
        signedToday.value = prefs.getString("signDay", "") == today()
    }

    private fun readTracks(name: String): List<Track>? = try {
        val f = File(dir, "$name.json")
        if (!f.exists()) null else JSONArray(f.readText()).let { a -> (0 until a.length()).map { Track.fromJson(a.getJSONObject(it)) } }
    } catch (_: Exception) { null }

    private fun readRecentEntries(): List<RecentEntry>? = try {
        val f = File(dir, "recents.json")
        if (!f.exists()) null else JSONArray(f.readText()).let { a -> (0 until a.length()).map { RecentEntry.fromJson(a.getJSONObject(it)) } }
    } catch (_: Exception) { null }

    private fun setRecentEntries(list: List<RecentEntry>, save: Boolean) {
        recentEntries.value = list
        recents.value = list.map { it.track }
        if (save) scheduleSaveRecents(list)
    }

    private fun scheduleSaveRecents(snapshot: List<RecentEntry>) {
        saveRecentsJob?.cancel()
        saveRecentsJob = io.launch {
            delay(200L)
            runCatching { File(dir, "recents.json").writeTextAtomic(JSONArray(snapshot.map { it.toJson() }).toString()) }
        }
    }

    internal fun flushRecentsSync() {
        saveRecentsJob?.cancel()
        runCatching { File(dir, "recents.json").writeTextAtomic(JSONArray(recentEntries.value.map { it.toJson() }).toString()) }
    }

    fun reloadLiked() { likedIds.value = prefs.getStringSet("liked", emptySet())!! + LocalLib.liked.value.map { it.id } }
    /** 备份恢复用：直接替换播放历史（兼容无时间戳旧格式） */
    fun restoreRecents(l: List<Track>) = restoreRecentEntries(l.map { RecentEntry(it) })
    fun restoreRecentEntries(l: List<RecentEntry>) = setRecentEntries(l, save = true)
    fun clearRecents() = setRecentEntries(emptyList(), save = true)
    fun clearRecentDay(day: String) = setRecentEntries(recentEntries.value.filter { it.day != day }, save = true)
    private fun writeTracks(name: String, l: List<Track>) = io.launch {
        runCatching { File(dir, "$name.json").writeTextAtomic(JSONArray(l.map { it.toJson() }).toString()) }
    }

    /** 启动后调用：读缓存 → 联网刷新账号、歌单、每日推荐 */
    fun refreshAll() = io.launch {
        readTracks("daily")?.let { daily.value = it }
        File(dir, "playlists.json").takeIf { it.exists() }?.let { f ->
            runCatching {
                val a = JSONArray(f.readText())
                playlists.value = (0 until a.length()).map { val o = a.getJSONObject(it); Playlist(o.getString("id"), o.getString("n"), o.getInt("c"), o.optString("cv").ifEmpty { null }, o.optBoolean("l"), o.optBoolean("m", true)) }
            }
        }
        if (Plugins.main() == null) { profile.value = null; return@launch }
        PluginStore.refresh().join()      // 先拿到各插件的登录状态
        loading.value = true
        runCatching { SourceApi.profile() }.onSuccess { p ->
            profile.value = p
            if (p != null) prefs.edit().putString("profile", JSONObject().put("u", p.uid).put("n", p.name).put("a", p.avatar).toString()).apply()
            else prefs.edit().remove("profile").apply()
        }
        runCatching { SourceApi.daily() }.onSuccess { daily.value = it; writeTracks("daily", it) }
        if (SourceApi.loggedIn) runCatching { SourceApi.playlists() }.onSuccess { l ->
            playlists.value = l
            runCatching { File(dir, "playlists.json").writeTextAtomic(JSONArray(l.map { JSONObject().put("id", it.id).put("n", it.name).put("c", it.count).put("cv", it.cover ?: "").put("l", it.liked).put("m", it.mine) }).toString()) }
            l.firstOrNull { it.liked }?.let { lp -> runCatching { tracksOf(lp.id, refresh = true) } }
        }
        loading.value = false
    }

    /** 歌单曲目：内存里不常驻，读缓存或联网 */
    suspend fun tracksOf(id: String, refresh: Boolean = false): List<Track> = withContext(Dispatchers.IO) {
        if (!refresh) readTracks("pl_$id")?.let { return@withContext it }
        val l = SourceApi.playlistTracks(id)
        writeTracks("pl_$id", l)
        if (playlists.value.firstOrNull { it.liked }?.id == id) {
            likedIds.value = l.map { it.id }.toSet() + LocalLib.liked.value.map { it.id }
            prefs.edit().putStringSet("liked", likedIds.value).apply()
        }
        l
    }

    /** 歌单增删改后：重新拉歌单列表，必要时刷新某个歌单的曲目缓存 */
    fun refreshPlaylists(changed: String? = null) = io.launch {
        runCatching { SourceApi.playlists() }.onSuccess { l ->
            playlists.value = l
            runCatching { File(dir, "playlists.json").writeTextAtomic(JSONArray(l.map { JSONObject().put("id", it.id).put("n", it.name).put("c", it.count).put("cv", it.cover ?: "").put("l", it.liked).put("m", it.mine) }).toString()) }
        }
        if (changed != null) runCatching { tracksOf(changed, refresh = true) }
        plVersion.value++
    }

    fun cachedTracks(id: String) = readTracks("pl_$id")

    /** 能同步到账号的歌：来源插件开着、实现了收藏、并且登录了；其它的记在本机「我喜欢」 */
    fun cloudLike(t: Track): Boolean {
        if (t.local || !Plugins.isPlugin(t.id)) return false
        val s = Plugins.ofOrNull(t.id) ?: return false
        return s.has("like") && PluginStore.accounts.value.containsKey(s.id)
    }
    fun toggleLike(t: Track, onError: (String) -> Unit) {
        val like = t.id !in likedIds.value
        likedIds.value = if (like) likedIds.value + t.id else likedIds.value - t.id
        prefs.edit().putStringSet("liked", likedIds.value).apply()
        // 本机喜欢里有它（或者同步不了）就改本机的
        if (LocalLib.isLiked(t.id) != like && (LocalLib.isLiked(t.id) || !cloudLike(t))) { LocalLib.toggleLike(t); return }
        io.launch { runCatching { SourceApi.like(t.id, like) }.onFailure { onError("云端同步失败：${it.message}") } }
    }

    /** 写入一条播放历史：按本地日期分天保留、无条数上限；同日重复播放同一首歌置顶并刷新播放时间 */
    fun addRecent(t: Track, nowMs: Long = System.currentTimeMillis()) {
        val day = dayKey(nowMs)
        val entry = RecentEntry(t, nowMs, day)
        val cur = recentEntries.value
        val next = ArrayList<RecentEntry>(cur.size + 1)
        next.add(entry)
        for (e in cur) {
            if (!(e.day == day && e.track.id == t.id)) next.add(e)
        }
        setRecentEntries(next, save = true)
    }

    fun saveQueue(q: List<Track>, i: Int, pos: Long) {
        writeTracks("queue", q)
        prefs.edit().putInt("qi", i).putLong("qp", pos).apply()
    }

    fun restoreQueue(): Triple<List<Track>, Int, Long>? {
        val q = readTracks("queue") ?: return null
        if (q.isEmpty()) return null
        return Triple(q, prefs.getInt("qi", 0).coerceIn(0, q.lastIndex), prefs.getLong("qp", 0))
    }

    fun logout() = io.launch {
        SourceApi.logout(); profile.value = null; playlists.value = emptyList(); likedIds.value = emptySet()
        prefs.edit().remove("profile").remove("liked").apply()
        dir.listFiles()?.filter { it.name.startsWith("pl_") || it.name == "playlists.json" }?.forEach { it.delete() }
    }
}
