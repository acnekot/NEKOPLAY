package cc.catsuki.nekoplay

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 列表数据：先读本地缓存立即显示，再联网刷新。 */
object Store {
    private lateinit var dir: File
    private lateinit var prefs: android.content.SharedPreferences
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val profile = MutableStateFlow<Profile?>(null)
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val daily = MutableStateFlow<List<Track>>(emptyList())
    val likedIds = MutableStateFlow<Set<String>>(emptySet())
    val recents = MutableStateFlow<List<Track>>(emptyList())
    val loading = MutableStateFlow(false)
    /** 歌单内容变了（加歌 / 删歌）就 +1，详情页据此重新读取 */
    val plVersion = MutableStateFlow(0)
    /** 今天是否已签到（按本地日期记） */
    val signedToday = MutableStateFlow(false)
    private fun today() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA).format(java.util.Date())
    fun markSigned() { signedToday.value = true; prefs.edit().putString("signDay", today()).apply() }
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** 在主线程跑一个联网操作（给菜单、按钮这类非 Compose 代码用） */
    fun run(block: suspend () -> Unit) = main.launch { block() }

    fun init(c: Context) {
        dir = File(c.filesDir, "cache").apply { mkdirs() }
        prefs = c.getSharedPreferences("store", Context.MODE_PRIVATE)
        prefs.getString("profile", null)?.let { val o = JSONObject(it); profile.value = Profile(o.getString("u"), o.getString("n"), o.getString("a")) }
        likedIds.value = prefs.getStringSet("liked", emptySet())!!
        recents.value = readTracks("recents") ?: emptyList()
        signedToday.value = prefs.getString("signDay", "") == today()
    }

    private fun readTracks(name: String): List<Track>? = try {
        val f = File(dir, "$name.json")
        if (!f.exists()) null else JSONArray(f.readText()).let { a -> (0 until a.length()).map { Track.fromJson(a.getJSONObject(it)) } }
    } catch (_: Exception) { null }

    private fun writeTracks(name: String, l: List<Track>) = io.launch {
        runCatching { File(dir, "$name.json").writeText(JSONArray(l.map { it.toJson() }).toString()) }
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
        loading.value = true
        runCatching { Ncm.profile() }.onSuccess { p ->
            profile.value = p
            if (p != null) prefs.edit().putString("profile", JSONObject().put("u", p.uid).put("n", p.name).put("a", p.avatar).toString()).apply()
            else prefs.edit().remove("profile").apply()
        }
        runCatching { Ncm.daily() }.onSuccess { daily.value = it; writeTracks("daily", it) }
        if (Ncm.loggedIn) runCatching { Ncm.playlists() }.onSuccess { l ->
            playlists.value = l
            runCatching { File(dir, "playlists.json").writeText(JSONArray(l.map { JSONObject().put("id", it.id).put("n", it.name).put("c", it.count).put("cv", it.cover ?: "").put("l", it.liked).put("m", it.mine) }).toString()) }
            l.firstOrNull { it.liked }?.let { lp -> runCatching { tracksOf(lp.id, refresh = true) } }
        }
        loading.value = false
    }

    /** 歌单曲目：内存里不常驻，读缓存或联网 */
    suspend fun tracksOf(id: String, refresh: Boolean = false): List<Track> = withContext(Dispatchers.IO) {
        if (!refresh) readTracks("pl_$id")?.let { return@withContext it }
        val l = Ncm.playlistTracks(id)
        writeTracks("pl_$id", l)
        if (playlists.value.firstOrNull { it.liked }?.id == id) {
            likedIds.value = l.map { it.id }.toSet()
            prefs.edit().putStringSet("liked", likedIds.value).apply()
        }
        l
    }

    /** 歌单增删改后：重新拉歌单列表，必要时刷新某个歌单的曲目缓存 */
    fun refreshPlaylists(changed: String? = null) = io.launch {
        runCatching { Ncm.playlists() }.onSuccess { l ->
            playlists.value = l
            runCatching { File(dir, "playlists.json").writeText(JSONArray(l.map { JSONObject().put("id", it.id).put("n", it.name).put("c", it.count).put("cv", it.cover ?: "").put("l", it.liked).put("m", it.mine) }).toString()) }
        }
        if (changed != null) runCatching { tracksOf(changed, refresh = true) }
        plVersion.value++
    }

    fun cachedTracks(id: String) = readTracks("pl_$id")

    fun toggleLike(t: Track, onError: (String) -> Unit) {
        val like = t.id !in likedIds.value
        likedIds.value = if (like) likedIds.value + t.id else likedIds.value - t.id
        prefs.edit().putStringSet("liked", likedIds.value).apply()
        io.launch { runCatching { Ncm.like(t.id, like) }.onFailure { onError("云端同步失败：${it.message}") } }
    }

    fun addRecent(t: Track) {
        recents.value = (listOf(t) + recents.value.filter { it.id != t.id }).take(100)
        writeTracks("recents", recents.value)
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

    fun logout() {
        Ncm.logout(); profile.value = null; playlists.value = emptyList(); likedIds.value = emptySet()
        prefs.edit().remove("profile").remove("liked").apply()
        dir.listFiles()?.filter { it.name.startsWith("pl_") || it.name == "playlists.json" }?.forEach { it.delete() }
    }
}
