package cc.catsuki.nekoplay

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val cover: String?,
    val artists: List<Pair<String, String>> = emptyList(), // (id, 名字)
    val albumId: String = "",
    val src: String? = null, // 本地音乐：文件地址（file:// 或 content://），网易云的歌为 null
    val vip: Boolean = false, // 需要会员 / 付费才能放整首
) {
    val local get() = src != null
    fun toJson() = JSONObject().put("id", id).put("t", title).put("a", artist).put("al", album).put("d", durationMs).put("c", cover ?: "")
        .put("ar", JSONArray(artists.map { JSONArray().put(it.first).put(it.second) })).put("alid", albumId).apply { if (src != null) put("src", src); if (vip) put("v", true) }
    companion object {
        fun fromJson(o: JSONObject) = Track(o.getString("id"), o.optString("t"), o.optString("a"), o.optString("al"), o.optLong("d"), o.optString("c").ifEmpty { null },
            o.optJSONArray("ar")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { x -> x.getString(0) to x.getString(1) } } } ?: emptyList(), o.optString("alid"), o.optString("src").ifEmpty { null }, o.optBoolean("v"))
    }
}

data class Artist(val id: String, val name: String, val pic: String?, val brief: String, val songs: Int, val albums: Int, val hot: List<Track>, val followed: Boolean = false)
data class Album(val id: String, val name: String, val pic: String?, val size: Int, val year: String, val artist: String = "")
data class User(val uid: String, val name: String, val avatar: String?, val bg: String?, val signature: String, val level: Int, val listened: Int, val follows: Int, val fans: Int, val followed: Boolean = false)

data class Playlist(val id: String, val name: String, val count: Int, val cover: String?, val liked: Boolean = false, val mine: Boolean = true, val desc: String = "")
data class Profile(val uid: String, val name: String, val avatar: String)
data class Comment(val id: Long, val user: String, val uid: String, val avatar: String?, val content: String, val liked: Int, val time: Long, val reply: String?, val replies: Int = 0, val likedByMe: Boolean = false)
data class Mv(val id: String, val name: String, val cover: String?, val artist: String, val durationMs: Long, val plays: Long)
data class Radio(val id: String, val name: String, val pic: String?, val desc: String, val count: Int)
data class LyricData(val lrc: String, val tr: String?, val yrc: String?)
data class UserBrief(val uid: String, val name: String, val avatar: String?, val signature: String)
data class Stream(val url: String, val format: String, val kbps: Int, val level: String)
class NcmError(msg: String) : Exception(msg)

/**
 * 在线功能的统一入口。本体不带任何音源：这里的每个方法都转给插件——
 * 带 id 的（播放、歌词、歌单、专辑、歌手、评论……）交给 id 所属的插件，
 * 不带 id 的（日推、FM、推荐歌单、账号……）交给提供这个功能的插件（优先主插件，见 Plugins.main）。
 * 名字沿用 Ncm 是为了少改界面代码；网易云本身也只是一个插件（plugins/netease.js）。
 */
object Ncm {
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    fun init() {}

    /** 音质档位（从低到高），选中的拿不到时依次往下降 */
    val LEVELS = listOf("standard", "higher", "exhigh", "lossless", "hires")
    val LEVEL_LABELS = listOf("标准 128k", "较高 192k", "极高 320k", "无损 FLAC", "Hi-Res")
    fun pic(u: String?): String? = if (u.isNullOrEmpty()) null else u.replace("http:", "https:").substringBefore('?')

    /** 有没有可用的在线插件 */
    val available get() = Plugins.enabled.isNotEmpty()
    /** 主插件登录了没有 */
    val loggedIn get() = Plugins.main()?.let { PluginStore.accounts.value.containsKey(it.id) } ?: false

    private suspend fun need(cap: String, what: String): JsSource = Plugins.provider(cap) ?: throw NcmError("没有提供「$what」的插件：到 设置 → 插件 导入")
    private suspend fun mainCall(fn: String, vararg a: Any?): Any? { val m = Plugins.main() ?: return null; if (!m.start() || !m.has(fn)) return null; return m.call(fn, *a) }
    private fun arr(v: Any?) = Plugins.arr(v)
    private fun tracks(pid: String, v: Any?) = Plugins.tracksOf(pid, v)

    // ---------- 歌曲 ----------
    /** 按 id 取歌曲详情：按插件分组，插件没实现 details 的跳过 */
    suspend fun details(ids: List<String>): List<Track> {
        val by = HashMap<String, Track>()
        ids.filter { Plugins.isPlugin(it) }.groupBy { Plugins.pidOf(it) }.forEach { (pid, l) ->
            val s = Plugins.get(pid)?.takeIf { Plugins.on(pid) && it.start() && it.has("details") } ?: return@forEach
            runCatching { tracks(pid, s.call("details", JSONArray(l.map { Plugins.raw(it) }))) }.getOrNull()?.forEach { by[it.id] = it }
        }
        return ids.mapNotNull { by[it] }
    }
    suspend fun stream(id: String, quality: Int): Stream = Plugins.stream(id, quality)
    suspend fun lyric(id: String): Pair<String, String?> = Plugins.lyric(id).let { it.lrc to it.tr }
    suspend fun lyricFull(id: String): LyricData = Plugins.lyric(id)
    suspend fun like(id: String, like: Boolean) = Plugins.like(id, like)

    // ---------- 账号 ----------
    suspend fun profile(): Profile? {
        val m = Plugins.main() ?: return null
        val a = (if (m.start() && m.has("account")) m.call("account") else null) as? JSONObject ?: return null
        return Profile(Plugins.wrap(m.id, a.opt("uid") ?: a.optString("name")), a.optString("name"), a.optString("avatar").takeIf { it.isNotEmpty() && it != "null" } ?: "")
    }
    suspend fun uid(): String = profile()?.uid ?: throw NcmError("请先登录")
    /** 主插件账号下的歌单，第一个是「我喜欢」 */
    suspend fun playlists(): List<Playlist> { val m = Plugins.main() ?: throw NcmError("请先导入插件"); return Plugins.myPlaylists(m.id).sortedByDescending { it.liked } }
    suspend fun logout() { runCatching { mainCall("logout") }; PluginStore.refresh() }
    /** 粘贴 Cookie 登录（插件实现了 setCookie 才有） */
    suspend fun saveCookie(text: String) { mainCall("setCookie", text) ?: throw NcmError("当前插件不支持粘贴 Cookie 登录"); PluginStore.refresh() }

    // ---------- 歌单 / 专辑 / 歌手 / 用户 ----------
    suspend fun playlistTracks(id: String): List<Track> = Plugins.playlistTracks(id)
    suspend fun album(id: String): Pair<Album, List<Track>> = Plugins.album(id)
    suspend fun artist(id: String): Artist = Plugins.artist(id)
    suspend fun artistAlbums(id: String): List<Album> = artistAlbumsPage(id, 0, 60).first
    /** 歌手的专辑分页 → (这一页, 还有没有更多) */
    suspend fun artistAlbumsPage(id: String, offset: Int, limit: Int = 60): Pair<List<Album>, Boolean> {
        val r = Plugins.callFor(id, "artistAlbums", Plugins.raw(id), offset, limit) as? JSONObject ?: return emptyList<Album>() to false
        return arr(r.opt("items")).map { Plugins.album(Plugins.pidOf(id), it) } to r.optBoolean("more")
    }
    suspend fun user(uid: String): User = (Plugins.callFor(uid, "user", Plugins.raw(uid)) as? JSONObject)?.let { Plugins.userOf(Plugins.pidOf(uid), it) } ?: throw NcmError("这个音源没有用户主页")
    suspend fun userPlaylists(uid: String): List<Playlist> = arr(Plugins.callFor(uid, "userPlaylists", Plugins.raw(uid))).map { Plugins.playlist(Plugins.pidOf(uid), it) }

    // ---------- 发现（交给提供这个功能的插件） ----------
    private suspend fun listFrom(cap: String, what: String, vararg a: Any?): Pair<String, Any?> { val s = need(cap, what); return s.id to s.call(cap, *a) }
    suspend fun daily(): List<Track> = listFrom("daily", "每日推荐").let { (p, v) -> tracks(p, v) }
    suspend fun fm(): List<Track> = listFrom("fm", "私人 FM").let { (p, v) -> tracks(p, v) }
    suspend fun fmTrash(id: String) { Plugins.callFor(id, "fmTrash", Plugins.raw(id)) }
    suspend fun toplists(): List<Playlist> = listFrom("toplists", "排行榜").let { (p, v) -> arr(v).map { Plugins.playlist(p, it) } }
    suspend fun recPlaylists(): List<Playlist> = listFrom("recPlaylists", "推荐歌单").let { (p, v) -> arr(v).map { Plugins.playlist(p, it) } }
    suspend fun categories(): List<String> = (need("categories", "歌单分类").call("categories") as? JSONArray)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    suspend fun square(cat: String, offset: Int): Pair<List<Playlist>, Boolean> {
        val s = need("square", "歌单广场"); val r = s.call("square", cat, offset) as? JSONObject ?: JSONObject()
        return arr(r.opt("items")).map { Plugins.playlist(s.id, it) } to r.optBoolean("more")
    }
    /** 新碟：ALL / ZH / EA / KR / JP */
    suspend fun newAlbums(area: String, offset: Int): List<Album> = listFrom("newAlbums", "新碟", area, offset).let { (p, v) -> arr(v).map { Plugins.album(p, it) } }
    /** 新歌速递：0 全部 / 7 华语 / 96 欧美 / 8 日本 / 16 韩国 */
    suspend fun newSongs(area: Int): List<Track> = listFrom("newSongs", "新歌速递", area).let { (p, v) -> tracks(p, v) }
    suspend fun historyDates(): List<String> = (need("historyDates", "历史日推").call("historyDates") as? JSONArray)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    suspend fun historySongs(date: String): List<Track> = listFrom("historySongs", "历史日推", date).let { (p, v) -> tracks(p, v) }
    suspend fun simiSongs(id: String): List<Track> = tracks(Plugins.pidOf(id), Plugins.callFor(id, "simiSongs", Plugins.raw(id)))
    suspend fun simiArtists(id: String): List<Artist> = arr(Plugins.callFor(id, "simiArtists", Plugins.raw(id))).map { Plugins.artistOf(Plugins.pidOf(id), it) }
    suspend fun simiPlaylists(songId: String): List<Playlist> = arr(Plugins.callFor(songId, "simiPlaylists", Plugins.raw(songId))).map { Plugins.playlist(Plugins.pidOf(songId), it) }

    // ---------- 搜索（搜索页用当前选的来源；这里给联想词和热搜） ----------
    private suspend fun searchPlugin(cap: String): JsSource? = SearchSource.plugin?.let { Plugins.get(it) }?.takeIf { it.start() && it.has(cap) } ?: Plugins.provider(cap)
    suspend fun suggest(q: String): List<String> = (searchPlugin("suggest")?.call("suggest", q) as? JSONArray)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    suspend fun hotSearch(): List<String> = (searchPlugin("hotSearch")?.call("hotSearch") as? JSONArray)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

    // ---------- 评论 ----------
    /** 歌曲评论：hot=true 热门，false 最新 → (评论, 总数) */
    suspend fun comments(id: String, hot: Boolean, offset: Int, before: Long = 0): Pair<List<Comment>, Int> {
        val r = Plugins.callFor(id, "comments", Plugins.raw(id), hot, offset, before) as? JSONObject ?: return emptyList<Comment>() to 0
        return arr(r.opt("items")).map { Plugins.commentOf(Plugins.pidOf(id), it) } to r.optInt("total")
    }
    /** 楼层回复 → (回复, 下一页游标，-1 = 没有更多) */
    suspend fun commentFloor(songId: String, parent: Long, cursor: Long = -1): Pair<List<Comment>, Long> {
        val r = Plugins.callFor(songId, "commentFloor", Plugins.raw(songId), parent, cursor) as? JSONObject ?: return emptyList<Comment>() to -1
        return arr(r.opt("items")).map { Plugins.commentOf(Plugins.pidOf(songId), it) } to r.optLong("next", -1)
    }
    suspend fun commentLike(songId: String, cid: Long, on: Boolean) { Plugins.callFor(songId, "commentLike", Plugins.raw(songId), cid, on) }
    /** 发评论；replyTo 不为空时是回复某条评论 */
    suspend fun commentPost(songId: String, content: String, replyTo: Long?) { Plugins.callFor(songId, "commentPost", Plugins.raw(songId), content, replyTo) ?: throw NcmError("这个音源不支持评论") }

    // ---------- 歌单管理 / 收藏关注 ----------
    suspend fun createPlaylist(name: String, private: Boolean): String { val m = Plugins.provider("createPlaylist") ?: throw NcmError("当前插件不支持建歌单"); return Plugins.wrap(m.id, m.call("createPlaylist", name, private)) }
    suspend fun deletePlaylist(id: String) { Plugins.callFor(id, "deletePlaylist", Plugins.raw(id)) }
    /** 往歌单加 / 删歌：op = add / del */
    suspend fun playlistTracks(pid: String, op: String, ids: List<String>) {
        Plugins.callFor(pid, "editPlaylist", Plugins.raw(pid), op, JSONArray(ids.filter { Plugins.pidOf(it) == Plugins.pidOf(pid) }.map { Plugins.raw(it) })) ?: throw NcmError("这个歌单不支持修改")
    }
    suspend fun subscribePlaylist(id: String, sub: Boolean) { Plugins.callFor(id, "subscribePlaylist", Plugins.raw(id), sub) }
    suspend fun playlistSubscribed(id: String): Boolean = Plugins.callFor(id, "playlistSubscribed", Plugins.raw(id)) == true
    suspend fun followArtist(id: String, on: Boolean) { Plugins.callFor(id, "followArtist", Plugins.raw(id), on) }
    suspend fun followUser(uid: String, on: Boolean) { Plugins.callFor(uid, "followUser", Plugins.raw(uid), on) }
    suspend fun subAlbum(id: String, on: Boolean) { Plugins.callFor(id, "subAlbum", Plugins.raw(id), on) }
    suspend fun subAlbums(): List<Album> { val m = Plugins.main() ?: return emptyList(); return arr(mainCall("subAlbums")).map { Plugins.album(m.id, it) } }
    suspend fun subArtists(): List<Artist> { val m = Plugins.main() ?: return emptyList(); return arr(mainCall("subArtists")).map { Plugins.artistOf(m.id, it) } }
    /** 听歌排行：week=true 最近一周，false 所有时间 → (歌, 次数或分数) */
    suspend fun playRecord(uid: String, week: Boolean): List<Pair<Track, Int>> {
        val pid = Plugins.pidOf(uid)
        return arr(Plugins.callFor(uid, "playRecord", Plugins.raw(uid), week)).map { Plugins.track(pid, it.optJSONObject("track") ?: JSONObject()) to it.optInt("count") }
    }
    /** → (今天是否已签到, 提示文字) */
    suspend fun signin(): Pair<Boolean, String> { val r = mainCall("signin") as? JSONObject ?: return false to "当前插件没有签到"; return r.optBoolean("done") to r.optString("message") }
    suspend fun scrobbleStart(id: String) = runCatching { Plugins.callFor(id, "scrobbleStart", Plugins.raw(id)) }
    suspend fun scrobble(id: String, seconds: Long) = runCatching { Plugins.callFor(id, "scrobble", Plugins.raw(id), seconds) }
    /** 云盘：自己上传的歌 */
    suspend fun cloud(): List<Track> { val m = Plugins.main() ?: return emptyList(); return tracks(m.id, mainCall("cloud")) }

    // ---------- MV / 电台 ----------
    suspend fun artistMvs(id: String): List<Mv> = arr(Plugins.callFor(id, "artistMvs", Plugins.raw(id))).map { Plugins.mvOf(Plugins.pidOf(id), it) }
    suspend fun recMvs(): List<Mv> = listFrom("recMvs", "推荐 MV").let { (p, v) -> arr(v).map { Plugins.mvOf(p, it) } }
    suspend fun topMvs(): List<Mv> = listFrom("topMvs", "MV 排行").let { (p, v) -> arr(v).map { Plugins.mvOf(p, it) } }
    suspend fun mvUrl(id: String): String = Plugins.callFor(id, "mvUrl", Plugins.raw(id))?.toString() ?: throw NcmError("拿不到 MV 地址")
    suspend fun hotRadios(offset: Int): List<Radio> = listFrom("hotRadios", "播客电台", offset).let { (p, v) -> arr(v).map { Plugins.radioOf(p, it) } }
    /** 电台节目：每期节目背后是一首「歌」 */
    suspend fun radioPrograms(rid: String): List<Track> = tracks(Plugins.pidOf(rid), Plugins.callFor(rid, "radioPrograms", Plugins.raw(rid)))
}
