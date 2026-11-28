package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 已导入的插件：脚本文件放在 filesDir/plugins/<id>.js，开关存在 prefs "plugins"。
 * 插件给出的歌 / 歌单 / 专辑 / 歌手 id 在本体里统一加前缀「@插件id:」，调回插件时再去掉。
 */
object Plugins {
    private val dir by lazy { File(Plat.impl.filesDir, "plugins").apply { mkdirs() } }
    private val prefs by lazy { Plat.prefs("plugins") }
    val list = kotlinx.coroutines.flow.MutableStateFlow<List<JsSource>>(emptyList())
    var off by mutableStateOf(setOf<String>())
    /** 响应式变更版本号：导入/开关/重载/删除/启动完成均递增，确保所有读取 Plugins.main() / enabledSources 的页面立刻重组 */
    var rev by mutableIntStateOf(0); private set
    /** 缓存热路径查询结果：避免曲目行/控制栏每帧 filter 分配临时 List */
    var enabledSourceCount by mutableIntStateOf(0); private set
    var hasScrubPlugin by mutableStateOf(false); private set
    fun bump() {
        val curOff = off; val curList = list.value
        var src = 0; var scrub = false
        for (s in curList) {
            if (s.id in curOff) continue
            if (s.isSource) src++
            if (!scrub && s.isFeature && (s.featureSlot == "scrub" || s.id.equals("scrub", ignoreCase = true))) scrub = true
        }
        enabledSourceCount = src
        hasScrubPlugin = scrub
        rev++
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private var idleJob: kotlinx.coroutines.Job? = null
    private var refreshJob: kotlinx.coroutines.Job? = null
    @Volatile private var loaded = false
    private fun scheduleRefresh(before: suspend () -> Unit = {}) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            before()
            PluginStore.refresh().join()
            Store.refreshAll()
            bump()
        }
    }
    fun load() = synchronized(this) {
        off = prefs.getStringSet("off", null) ?: emptySet()
        list.value = (dir.listFiles() ?: emptyArray()).filter { it.extension == "js" }.sortedBy { it.name }.map { JsSource(it) }
        loaded = true; bump()
        // 按需启动：已有 caps 缓存的插件不立即创建 QuickJS；首次安装的插件后台跑一次拿 caps；5 分钟未用自动释放
        val needWarm = enabled.filter { it.caps.isEmpty() }
        if (needWarm.isNotEmpty()) scope.launch { needWarm.forEach { it.start() }; bump() }
        if (idleJob == null) idleJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                val now = System.currentTimeMillis()
                list.value.forEach { s -> if (s.isRunning && s.lastUsedAt > 0L && now - s.lastUsedAt > 5 * 60_000L) s.close() }
            }
        }
    }
    private fun ensureLoaded() { if (!loaded) load() }
    fun on(id: String): Boolean { ensureLoaded(); rev; return id !in off && list.value.any { it.id == id } }
    fun get(id: String): JsSource? { ensureLoaded(); rev; return list.value.firstOrNull { it.id == id } }
    val enabled get(): List<JsSource> { ensureLoaded(); rev; return list.value.filter { it.id !in off } }
    /** 音源插件（全部 / 已启用） */
    val sources get(): List<JsSource> { ensureLoaded(); rev; return list.value.filter { it.isSource } }
    val enabledSources get(): List<JsSource> { ensureLoaded(); rev; return enabled.filter { it.isSource } }
    /** 功能 / 页面 / 动效扩展插件（全部 / 已启用） */
    val features get(): List<JsSource> { ensureLoaded(); rev; return list.value.filter { it.isFeature } }
    val enabledFeatures get(): List<JsSource> { ensureLoaded(); rev; return enabled.filter { it.isFeature } }
    val enabledPages get(): List<JsSource> { ensureLoaded(); rev; return enabled.filter { it.isPage } }
    val enabledMotions get(): List<JsSource> { ensureLoaded(); rev; return enabled.filter { it.isMotion } }
    /** 读取当前启用的规范化动效插件（@type motion）导出的 profile() 配置 */
    suspend fun activeMotionProfile(): Pair<JsSource, JSONObject>? {
        val s = enabledMotions.firstOrNull() ?: return null
        if (!s.start() || !s.has("profile")) return null
        val o = runCatching { s.call("profile") as? JSONObject }.getOrNull() ?: return null
        return s to o
    }
    /** 是否启用了某个功能插件槽位（如 "coverflow"） */
    fun hasFeature(slot: String): Boolean {
        val key = slot.lowercase()
        return enabledFeatures.any { it.featureSlot == key || it.id.equals(key, ignoreCase = true) }
    }
    /** 读取指定功能槽位/插件的热更新配置（JS 导出 config()），实现不发版本体即可热调参数与行为 */
    suspend fun featureConfig(slotOrId: String): JSONObject? {
        val key = slotOrId.lowercase()
        val s = enabledFeatures.firstOrNull { it.featureSlot == key || it.id.equals(key, ignoreCase = true) } ?: return null
        if (!s.start() || !s.has("config")) return null
        return runCatching { s.call("config") as? JSONObject }.getOrNull()
    }
    /** 声明式页面插件渲染与交互派发（Plugin API v2） */
    suspend fun renderPage(id: String, ctx: JSONObject): JSONObject? {
        val s = get(id)?.takeIf { on(it.id) && it.isFeature } ?: return null
        if (!s.start() || !s.has("render")) return null
        return runCatching { s.call("render", ctx) as? JSONObject }.getOrNull()
    }
    suspend fun dispatchPageAction(id: String, action: String, payload: JSONObject? = null): JSONObject? {
        val s = get(id)?.takeIf { on(it.id) && it.isFeature } ?: return null
        if (!s.start() || !s.has("onAction")) return null
        return runCatching { s.call("onAction", action, payload ?: JSONObject()) as? JSONObject }.getOrNull()
    }

    /** 向所有启用的功能插件派发轻量生命周期事件（如 onTrackChange / onPlayState） */
    fun emitEvent(fn: String, payload: JSONObject) {
        val targets = enabledFeatures.filter { it.caps.isEmpty() || it.has(fn) }
        if (targets.isEmpty()) return
        scope.launch {
            targets.forEach { s -> runCatching { if (s.start() && s.has(fn)) s.call(fn, payload) } }
        }
    }
    /** 功能插件歌词后处理管道（如罗马音/排版清洗/翻译增强） */
    suspend fun transformLyric(track: Track, data: LyricData): LyricData {
        val targets = enabledFeatures.filter { it.caps.isEmpty() || it.has("transformLyric") }
        if (targets.isEmpty()) return data
        var cur = data
        val tObj = JSONObject().put("id", track.id).put("title", track.title).put("artist", track.artist).put("album", track.album)
        for (s in targets) {
            runCatching {
                if (!s.start() || !s.has("transformLyric")) return@runCatching
                val lObj = JSONObject().put("lrc", cur.lrc).put("tr", cur.tr ?: "").put("yrc", cur.yrc ?: "")
                val r = s.call("transformLyric", tObj, lObj) as? JSONObject ?: return@runCatching
                cur = LyricData(
                    r.optString("lrc", cur.lrc),
                    r.optString("tr").takeIf { it.isNotBlank() && it != "null" } ?: cur.tr,
                    r.optString("yrc").takeIf { it.isNotBlank() && it != "null" } ?: cur.yrc,
                )
            }
        }
        return cur
    }
    /** 功能插件执行结果：可返回提示文案，或请求宿主打开指定功能页面（如 open = "coverflow"） */
    data class ActionResult(val message: String? = null, val open: String? = null)
    /** 手动触发功能插件的 action(track) 动作 */
    suspend fun runFeatureAction(id: String, track: Track?): ActionResult? {
        val s = get(id)?.takeIf { on(it.id) && it.isFeature } ?: return null
        if (!s.start() || !s.has("action")) return null
        val tObj = track?.let { JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("album", it.album) }
        return when (val r = s.call("action", tObj)) {
            is JSONObject -> ActionResult(r.optString("message").takeIf { it.isNotEmpty() }, r.optString("open").takeIf { it.isNotEmpty() })
            is String -> ActionResult(r.takeIf { it.isNotEmpty() })
            else -> ActionResult("已执行「${s.name}」")
        }
    }
    fun set(id: String, v: Boolean) {
        val target = get(id)
        // 动效插件互斥生效：开启一个新的 @type motion 插件时自动关闭其它动效插件，避免曲线冲突
        val nextOff = if (v) {
            val base = off - id
            if (target?.isMotion == true) base + list.value.filter { it.isMotion && it.id != id }.map { it.id } else base
        } else off + id
        off = nextOff
        prefs.edit().putStringSet("off", off).apply()
        bump()
        if (target == null || target.isSource) {
            scheduleRefresh {
                if (v) target?.start() else target?.close()?.join()
                bump()
            }
        } else {
            scope.launch {
                if (v) target.start() else target.close().join()
                bump()
            }
        }
    }

    /** 导入脚本：检查开头的 @id 与 @minHost，同 id 覆盖旧版本；导入后自动刷新账号与曲库 */
    suspend fun import(text: String): JsSource = withContext(Dispatchers.IO) {
        val id = Regex("""^\s*//\s*@id\s+([A-Za-z0-9_\-]+)\s*$""", RegexOption.MULTILINE).find(text.take(4000))?.groupValues?.get(1)
            ?: throw SourceError("不是 NEKOPLAY 插件：脚本开头要有「// @id 名字」")
        get(id)?.close()?.join()
        val f = File(dir, "$id.js"); f.writeTextAtomic(text)
        val s = JsSource(f)
        if (!s.hostCompatible) { f.delete(); throw SourceError("插件需要 NEKOPLAY v${s.minHost} 或更高版本（当前 v$APP_VERSION）") }
        if (!s.start()) { f.delete(); throw SourceError("脚本运行出错：${s.error}") }
        list.value = list.value.filter { it.id != id } + s
        val baseOff = off - id
        off = if (s.isMotion) baseOff + list.value.filter { it.isMotion && it.id != id }.map { it.id } else baseOff
        prefs.edit().putStringSet("off", off).apply()
        bump()
        if (s.isSource) scheduleRefresh()
        s
    }
    suspend fun importUrl(url: String): JsSource {
        val text = withContext(Dispatchers.IO) { SourceApi.http.newCall(Request.Builder().url(url.trim()).build()).execute().use { r -> if (!r.isSuccessful) throw SourceError("下载失败：HTTP ${r.code}"); r.body?.string() ?: "" } }
        return import(text)
    }
    fun delete(id: String) {
        val wasSource = get(id)?.isSource != false
        get(id)?.close()
        File(dir, "$id.js").delete()
        list.value = list.value.filter { it.id != id }
        off = off - id; prefs.edit().putStringSet("off", off).apply()
        bump()
        if (wasSource) scheduleRefresh()
    }

    // ---------- 本体调用插件 ----------
    /** 老数据里的前缀：网易云的 id 不带前缀，QQ 音乐 "qq:"，酷狗 "kg:" */
    private val LEGACY = mapOf("qq:" to "qqmusic", "kg:" to "kugou")
    private val NAMES = mapOf("netease" to "网易云音乐", "qqmusic" to "QQ 音乐", "kugou" to "酷狗音乐")
    /** 在线 id（不是本地文件、也不是空）都归某个插件 */
    fun isPlugin(id: String?) = !id.isNullOrEmpty() && (id.startsWith("@") || LEGACY.keys.any { id.startsWith(it) } || id.all { it.isDigit() })
    fun pidOf(id: String): String = when {
        id.startsWith("@") -> id.removePrefix("@").substringBefore(':')
        else -> LEGACY.entries.firstOrNull { id.startsWith(it.key) }?.value ?: "netease"
    }
    fun raw(id: String): String = when {
        id.startsWith("@") -> id.substringAfter(':')
        else -> LEGACY.keys.firstOrNull { id.startsWith(it) }?.let { id.removePrefix(it) } ?: id
    }
    /** 网易云为了兼容老数据不加前缀，其它插件加「@插件id:」 */
    fun wrap(pid: String, raw: Any?): String = raw?.toString()?.takeIf { it.isNotEmpty() && it != "null" }?.let { if (pid == "netease") it else "@$pid:$it" } ?: ""
    fun nameOf(pid: String) = get(pid)?.name ?: NAMES[pid] ?: pid
    /** id 所属的插件；没装或关着时报错（提示去导入） */
    fun of(id: String): JsSource = get(pidOf(id))?.takeIf { on(it.id) } ?: throw SourceError("需要「${nameOf(pidOf(id))}」插件：到 设置 → 插件 导入或打开")
    fun ofOrNull(id: String): JsSource? = get(pidOf(id))?.takeIf { on(it.id) }
    /** 调 id 所属插件的方法；插件没实现时返回 null */
    suspend fun callFor(id: String, fn: String, vararg args: Any?): Any? { val s = ofOrNull(id) ?: return null; if (!s.start() || !s.has(fn)) return null; return s.call(fn, *args) }

    /** 主插件：账号、日推、FM 这类没有 id 的功能默认找它（首页来源 → 第一个有账号功能的 → 第一个音源），不偏向任何平台 */
    /** 主插件偏好提供者（由 UI 层注入，避免 data 层反向依赖界面偏好） */
    var preferredHomeId: () -> String = { "" }
    fun main(): JsSource? {
        val en = enabledSources
        val pref = preferredHomeId()
        return en.firstOrNull { it.id == pref } ?: en.firstOrNull { it.has("account") } ?: en.firstOrNull()
    }
    /** 提供某个功能的音源插件：先看主插件，再找第一个实现了它的音源插件 */
    suspend fun provider(cap: String): JsSource? {
        main()?.let { if (it.start() && it.has(cap)) return it }
        return enabledSources.firstOrNull { it.start() && it.has(cap) }
    }

    fun track(pid: String, o: JSONObject) = Track(wrap(pid, o.opt("id")), o.optString("title"), o.optString("artist").ifEmpty { "未知艺人" }, o.optString("album"),
        o.optLong("duration"), o.optString("cover").ifEmpty { null },
        o.optJSONArray("artists")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { wrap(pid, it.opt("id")) to it.optString("name") } } ?: emptyList(),
        wrap(pid, o.opt("albumId")), vip = o.optBoolean("vip"))
    fun playlist(pid: String, o: JSONObject) = Playlist(wrap(pid, o.opt("id")), o.optString("name"), o.optInt("count"), o.optString("cover").ifEmpty { null },
        liked = o.optBoolean("liked"), mine = o.optBoolean("mine", false), desc = o.optString("desc"))
    fun album(pid: String, o: JSONObject) = Album(wrap(pid, o.opt("id")), o.optString("name"), o.optString("cover").ifEmpty { null }, o.optInt("count"), o.optString("year"), o.optString("artist"))
    fun artistOf(pid: String, o: JSONObject) = Artist(wrap(pid, o.opt("id")), o.optString("name"), o.optString("cover").ifEmpty { null }, o.optString("desc"), o.optInt("songs"), o.optInt("albums"), emptyList(), o.optBoolean("followed"))
    fun userOf(pid: String, o: JSONObject) = User(wrap(pid, o.opt("uid")), o.optString("name"), o.optString("avatar").ifEmpty { null }, o.optString("bg").ifEmpty { null }, o.optString("signature"),
        o.optInt("level"), o.optInt("listened"), o.optInt("follows"), o.optInt("fans"), o.optBoolean("followed"))
    fun userBriefOf(pid: String, o: JSONObject) = UserBrief(wrap(pid, o.opt("uid")), o.optString("name"), o.optString("avatar").ifEmpty { null }, o.optString("signature"))
    fun commentOf(pid: String, o: JSONObject) = Comment(o.optLong("id"), o.optString("user"), wrap(pid, o.opt("uid")), o.optString("avatar").ifEmpty { null }, o.optString("content"),
        o.optInt("liked"), o.optLong("time"), o.optString("reply").takeIf { it.isNotEmpty() && it != "null" }, o.optInt("replies"), o.optBoolean("likedByMe"))
    fun mvOf(pid: String, o: JSONObject) = Mv(wrap(pid, o.opt("id")), o.optString("name"), o.optString("cover").ifEmpty { null }, o.optString("artist"), o.optLong("duration"), o.optLong("plays"))
    fun radioOf(pid: String, o: JSONObject) = Radio(wrap(pid, o.opt("id")), o.optString("name"), o.optString("cover").ifEmpty { null }, o.optString("desc"), o.optInt("count"))
    fun arr(v: Any?): List<JSONObject> = when (v) { is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }; else -> emptyList() }
    fun tracksOf(pid: String, v: Any?) = arr(if (v is JSONObject) v.opt("tracks") ?: v.opt("items") else v).map { track(pid, it) }

    suspend fun search(pid: String, q: String, type: String, page: Int = 1): Any {
        val s = get(pid) ?: throw SourceError("插件没有安装")
        val r = s.call("search", q, type, page)
        val items = arr(if (r is JSONObject) r.opt("items") ?: r.opt(type + "s") else r)
        return when (type) {
            "album" -> items.map { album(pid, it) }; "playlist" -> items.map { playlist(pid, it) }
            "artist" -> items.map { artistOf(pid, it) }; "user" -> items.map { userBriefOf(pid, it) }
            else -> items.map { track(pid, it) }
        }
    }
    /** 播放地址缓存：预加载下一首时取到的地址，真正切歌时直接用，不再请求一次（少触发风控） */
    private class CachedStream(val s: Stream, val at: Long)
    private val streamCache = HashMap<String, CachedStream>()
    private val streamLock = kotlinx.coroutines.sync.Mutex()
    private const val STREAM_TTL = 8 * 60_000L
    /** 地址放不了（过期 / 失效）时丢掉缓存 */
    fun forgetStream(id: String) { kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { streamLock.withLock { streamCache.keys.removeAll { it.startsWith("$id|") } } } }
    suspend fun stream(id: String, quality: Int): Stream {
        val key = "$id|$quality"
        val now = System.currentTimeMillis()
        streamLock.withLock { streamCache[key]?.takeIf { now - it.at < STREAM_TTL } }?.let { return it.s }
        val o = of(id).call("stream", raw(id), quality) as? JSONObject ?: throw SourceError("插件没有给出播放地址")
        val url = o.optString("url").ifEmpty { throw SourceError(o.optString("error").ifEmpty { "插件没有给出播放地址" }) }
        val trial = if (o.optBoolean("trial")) o.optString("message").ifEmpty { "当前只能放试听片段" } else null
        val st = Stream(url, o.optString("ext").ifEmpty { url.substringBefore('?').substringAfterLast('.', "mp3") }, o.optInt("kbps"), o.optString("level", "standard"), trial)
        streamLock.withLock { streamCache.entries.removeAll { now - it.value.at >= STREAM_TTL }; streamCache[key] = CachedStream(st, now) }
        return st
    }
    /** 音源给的原始歌词（不经过功能插件后处理） */
    suspend fun lyricRaw(id: String): LyricData {
        val o = callFor(id, "lyric", raw(id)) as? JSONObject ?: throw SourceError("暂无歌词")
        val l = LyricData(o.optString("lrc"), o.optString("tr").takeIf { it.isNotBlank() && it != "null" }, o.optString("yrc").takeIf { it.isNotBlank() && it != "null" })
        if (l.lrc.isBlank() && l.yrc == null) throw SourceError("暂无歌词")
        return l
    }
    suspend fun lyric(id: String): LyricData = transformLyric(trackFor(id), lyricRaw(id))
    /** 歌词后处理用的曲目信息：优先取队列里的（有歌名歌手，翻译插件可以拿来做上下文） */
    fun trackFor(id: String): Track = Engine.state.value.let { st -> st.current?.takeIf { it.id == id } ?: st.queue.firstOrNull { it.id == id } }
        ?: Track(id, "", "", "", 0, null)
    fun hasLyricTransform(): Boolean = enabledFeatures.any { it.caps.isEmpty() || it.has("transformLyric") }
    suspend fun playlistTracks(id: String): List<Track> = tracksOf(pidOf(id), of(id).call("playlist", raw(id)))
    suspend fun album(id: String): Pair<Album, List<Track>> {
        val pid = pidOf(id); val r = of(id).call("album", raw(id)) as? JSONObject ?: JSONObject()
        val tracks = tracksOf(pid, r)
        val al = r.optJSONObject("album")?.let { album(pid, it.put("id", it.opt("id") ?: raw(id))) } ?: Album(id, tracks.firstOrNull()?.album ?: "", tracks.firstOrNull()?.cover, tracks.size, "", tracks.firstOrNull()?.artist ?: "")
        return al to tracks
    }
    suspend fun artist(id: String): Artist {
        val pid = pidOf(id); val r = of(id).call("artist", raw(id)) as? JSONObject ?: JSONObject()
        val tracks = tracksOf(pid, r)
        return Artist(id, r.optString("name").ifEmpty { tracks.firstOrNull()?.artist ?: "" }, r.optString("cover").ifEmpty { null }, r.optString("desc"),
            r.optInt("songs", tracks.size), r.optInt("albums"), tracks, r.optBoolean("followed"))
    }
    suspend fun toplists(pid: String): List<Playlist> = arr(get(pid)?.call("toplists")).map { playlist(pid, it) }
    suspend fun newAlbums(pid: String): List<Album> = arr(get(pid)?.call("newAlbums", 0, 0).let { if (it is JSONObject) it.opt("albums") ?: it.opt("items") else it }).map { album(pid, it) }
    suspend fun myPlaylists(pid: String): List<Playlist> = arr(get(pid)?.call("myPlaylists")).map { playlist(pid, it) }
    suspend fun like(id: String, on: Boolean) { callFor(id, "like", raw(id), on) }

    /** 来源的显示名：组件上的小标签用；本地文件返回 null */
    fun sourceName(id: String): String? = if (isPlugin(id)) nameOf(pidOf(id)) else null
}

/** 音源插件的账号与歌单缓存（数据层状态，与 PluginUi 解耦） */
object PluginStore {
    fun logout(id: String) = io.launch { runCatching { Plugins.get(id)?.call("logout") }; refresh() }
    val accounts = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    val playlists = kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<Playlist>>>(emptyMap())
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    fun refresh() = io.launch {
        val acc = HashMap<String, String>(); val pls = HashMap<String, List<Playlist>>()
        for (s in Plugins.enabledSources) {
            if (!s.start() || !s.has("account")) continue
            val a = runCatching { s.call("account") as? JSONObject }.getOrNull() ?: continue
            acc[s.id] = a.optString("name")
            if (s.has("myPlaylists")) runCatching { Plugins.myPlaylists(s.id) }.onSuccess { pls[s.id] = it }
        }
        accounts.value = acc; playlists.value = pls
    }
}
