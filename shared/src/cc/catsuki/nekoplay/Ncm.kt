package cc.catsuki.nekoplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

/** 网易云网页端接口（weapi），直接在手机上请求，不需要服务器。 */
object Ncm {
    private const val NONCE = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    // 固定会话密钥：encSecKey 只要和 AES 密钥配套即可，省掉每次的 RSA 运算
    private const val SEC_KEY = "nK7qPz3Wm9XvL2tR"
    private const val ENC_SEC_KEY = "1b217eb9ffc597dcfbaae9a3c0fd2d56f1fa7fdd06f386c789714b426cd102041d4c883ea4ea82439e1aa9d453326b835904a61fbfab07b1266ec5dbda413220e8fe269c0035d839f02187d83dd4803437bf0af13ead1eba669df755a143fbfdd1cb9c1ba709885fd4d388ae586dac2108c6cbeae6b25098afaa44cd6e82eed2"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    private lateinit var prefs: KV
    fun init() { prefs = Plat.prefs("ncm") }

    var cookie: String
        get() = prefs.getString("cookie", "") ?: ""
        set(v) { prefs.edit().putString("cookie", v).remove("uid").apply() }
    val loggedIn get() = cookie.contains("MUSIC_U")
    private fun csrf() = Regex("__csrf=([^;\\s]+)").find(cookie)?.groupValues?.get(1) ?: ""

    private fun aes(text: String, key: String): String {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(IV.toByteArray()))
        return java.util.Base64.getEncoder().encodeToString(c.doFinal(text.toByteArray()))
    }

    private class Raw(val j: JSONObject, val setCookie: List<String>)

    private suspend fun raw(path: String, data: JSONObject): Raw = withContext(Dispatchers.IO) {
        data.put("csrf_token", csrf())
        val body = FormBody.Builder()
            .add("params", aes(aes(data.toString(), NONCE), SEC_KEY))
            .add("encSecKey", ENC_SEC_KEY).build()
        val req = Request.Builder().url("https://music.163.com$path?csrf_token=${csrf()}").post(body)
            .header("Referer", "https://music.163.com/").header("Origin", "https://music.163.com")
            .header("User-Agent", UA).header("Cookie", "os=pc; appver=2.7.1.198277; $cookie").build()
        val t0 = System.currentTimeMillis()
        try {
            http.newCall(req).execute().use { r ->
                val t = r.body?.string() ?: ""
                val ct = r.header("Content-Type") ?: ""
                Debug.log(path, r.code, System.currentTimeMillis() - t0, ct, t)
                if (!r.isSuccessful) throw NcmError("服务返回 ${r.code}" + Debug.detail(path, r.code, ct, t))
                val j = try { JSONObject(t) } catch (e: Exception) { throw NcmError("返回的不是 JSON（可能被风控）" + Debug.detail(path, r.code, ct, t)) }
                Raw(j, r.headers("Set-Cookie"))
            }
        } catch (e: java.io.IOException) {
            Debug.log(path, -1, System.currentTimeMillis() - t0, "", "${e.javaClass.simpleName}: ${e.message}")
            throw NcmError("网络错误：${e.message}" + if (Debug.on) "\n[$path]" else "")
        }
    }

    /** 不加密的老接口（GET），weapi 被拦时的后备 */
    private suspend fun plainGet(path: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://music.163.com$path").header("Referer", "https://music.163.com/").header("User-Agent", UA).build()
        http.newCall(req).execute().use { r -> JSONObject(r.body?.string() ?: "{}") }
    }

    private suspend fun api(path: String, data: JSONObject = JSONObject()): JSONObject {
        val j = raw(path, data).j
        val c = j.optInt("code", 200)
        if (c == -460 || c == -462) throw NcmError("被网易云风控拦截（$c），稍后再试")
        if (c != 200) throw NcmError("${j.optString("message", j.optString("msg", "接口错误"))}（$c）" + if (Debug.on) "\n[$path] " + j.toString().take(300) else "")
        return j
    }

    fun pic(u: String?): String? = if (u.isNullOrEmpty()) null else u.replace("http:", "https:").substringBefore('?')

    private fun song(s: JSONObject): Track {
        val ar = s.optJSONArray("ar") ?: s.optJSONArray("artists") ?: JSONArray()
        val al = s.optJSONObject("al") ?: s.optJSONObject("album") ?: JSONObject()
        val names = (0 until ar.length()).map { ar.getJSONObject(it).optString("name") }.filter { it.isNotEmpty() }
        val ids = (0 until ar.length()).map { ar.getJSONObject(it) }.filter { it.optString("name").isNotEmpty() }.map { it.opt("id").toString() to it.optString("name") }
        return Track(s.get("id").toString(), s.optString("name"), names.joinToString(" / ").ifEmpty { "未知艺人" },
            al.optString("name"), s.optLong("dt", s.optLong("duration")), pic(al.optString("picUrl")), ids, al.opt("id")?.toString() ?: "", vip = s.optInt("fee") == 1 || s.optInt("fee") == 4)
    }

    /** 按 id 取歌曲详情：每 200 首一个请求，并行发出 */
    suspend fun details(ids: List<String>): List<Track> = coroutineScope {
        // 插件的歌没有「按 id 取详情」，跳过（它们的信息在列表里已经带全了）
        if (ids.any { Plugins.isPlugin(it) }) return@coroutineScope details(ids.filter { !Plugins.isPlugin(it) })
        if (ids.any { Qqm.isQq(it) || Kgm.isKg(it) }) { val q = Qqm.details(ids).associateBy { it.id }; val n = details(ids.filter { !Qqm.isQq(it) && !Kgm.isKg(it) }).associateBy { it.id }; return@coroutineScope ids.mapNotNull { q[it] ?: n[it] } }
        val parts = ids.chunked(200).map { part ->
            async {
                val c = JSONArray(part.map { JSONObject().put("id", it.toLong()) })
                api("/weapi/v3/song/detail", JSONObject().put("c", c.toString())).optJSONArray("songs") ?: JSONArray()
            }
        }.awaitAll()
        val by = HashMap<String, Track>()
        parts.forEach { a -> for (i in 0 until a.length()) song(a.getJSONObject(i)).let { by[it.id] = it } }
        ids.mapNotNull { by[it] }
    }

    suspend fun search(q: String, page: Int = 1): List<Track> {
        val j = api("/weapi/search/get", JSONObject().put("s", q).put("type", 1).put("limit", 30).put("offset", (page - 1) * 30))
        val a = j.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        val ids = (0 until a.length()).map { a.getJSONObject(it).get("id").toString() }
        return details(ids) // 搜索结果没有封面，补一次详情
    }

    /** 音质档位（从低到高），选中的拿不到时依次往下降 */
    val LEVELS = listOf("standard", "higher", "exhigh", "lossless", "hires")
    val LEVEL_LABELS = listOf("标准 128k", "较高 192k", "极高 320k", "无损 FLAC", "Hi-Res")

    private val urlMemo = HashMap<String, Pair<Stream, Long>>()

    suspend fun stream(id: String, quality: Int): Stream {
        if (Plugins.isPlugin(id)) return Plugins.stream(id, quality)
        if (Qqm.isQq(id)) return Qqm.stream(id, quality)
        if (Kgm.isKg(id)) return Kgm.stream(id, quality)
        val key = "${id}_$quality"
        urlMemo[key]?.let { (s, exp) -> if (System.currentTimeMillis() < exp) return s }
        var err = "没有可用的播放地址（无版权或需要 VIP）"
        for (lv in LEVELS.subList(0, quality.coerceIn(0, LEVELS.lastIndex) + 1).reversed()) {
            try {
                val d = api("/weapi/song/enhance/player/url/v1", JSONObject().put("ids", "[$id]").put("level", lv).put("encodeType", "flac"))
                    .optJSONArray("data")?.optJSONObject(0) ?: continue
                val url = d.optString("url")
                if (url.isEmpty() || url == "null") continue
                if (!d.isNull("freeTrialInfo")) { err = "这首歌只有试听片段，需要 VIP"; continue }
                // 服务器实际给的档位（可能比要的低）
                val got = d.optString("level").ifEmpty { lv }
                val s = Stream(url.replace("http:", "https:"), d.optString("type").lowercase(), d.optInt("br") / 1000, got)
                urlMemo[key] = s to System.currentTimeMillis() + 10 * 60_000
                return s
            } catch (e: Exception) { err = e.message ?: err }
        }
        throw NcmError(err)
    }

    suspend fun lyric(id: String): Pair<String, String?> {
        if (Plugins.isPlugin(id)) return Plugins.lyric(id).let { it.lrc to it.tr }
        if (Qqm.isQq(id)) return Qqm.lyric(id).let { it.lrc to it.tr }
        if (Kgm.isKg(id)) return Kgm.lyric(id).let { it.lrc to it.tr }
        val j = api("/weapi/song/lyric", JSONObject().put("id", id).put("lv", -1).put("tv", -1).put("rv", -1).put("kv", -1).put("_nmclfl", 1))
        val l = j.optJSONObject("lrc")?.optString("lyric") ?: ""
        if (l.isEmpty()) throw NcmError("暂无歌词")
        return l to j.optJSONObject("tlyric")?.optString("lyric")?.ifEmpty { null }
    }

    suspend fun daily(): List<Track> {
        if (loggedIn) runCatching {
            val d = api("/weapi/v3/discovery/recommend/songs").optJSONObject("data")?.optJSONArray("dailySongs")
            if (d != null && d.length() > 0) return (0 until d.length()).map { song(d.getJSONObject(it)) }
        }
        val a = api("/weapi/v1/discovery/new/songs", JSONObject().put("areaId", 0).put("total", true)).optJSONArray("data") ?: JSONArray()
        return details((0 until minOf(30, a.length())).map { a.getJSONObject(it).get("id").toString() })
    }

    suspend fun profile(): Profile? {
        if (!loggedIn) return null
        val p = api("/weapi/w/nuser/account/get").optJSONObject("profile") ?: throw NcmError("登录已失效，请重新登录")
        val uid = p.get("userId").toString()
        prefs.edit().putString("uid", uid).apply()
        return Profile(uid, p.optString("nickname"), pic(p.optString("avatarUrl")) ?: "")
    }

    suspend fun uid(): String = prefs.getString("uid", null) ?: profile()?.uid ?: throw NcmError("请先登录")

    /** 账号下的全部歌单，第一个是「我喜欢的音乐」 */
    suspend fun playlists(): List<Playlist> {
        if (!loggedIn) throw NcmError("请先登录")
        return userPlaylists(uid())
    }

    suspend fun playlistTracks(id: String): List<Track> {
        if (Plugins.isPlugin(id)) return Plugins.playlistTracks(id)
        if (Qqm.isQq(id)) return Qqm.playlistTracks(id)
        if (Kgm.isKg(id)) return Kgm.playlistTracks(id)
        val ids = api("/weapi/v6/playlist/detail", JSONObject().put("id", id).put("n", 0).put("s", 0))
            .optJSONObject("playlist")?.optJSONArray("trackIds") ?: return emptyList()
        return details((0 until ids.length()).map { ids.getJSONObject(it).get("id").toString() })
    }

    // ---------- 歌手 / 专辑 / 用户 ----------
    suspend fun artist(id: String): Artist = if (Plugins.isPlugin(id)) Plugins.artist(id) else if (Qqm.isQq(id)) Qqm.artist(id) else if (Kgm.isKg(id)) Kgm.artist(id) else try { artistV1(id) } catch (e: Exception) {
        // 老接口被拦（返回 HTML）时，换成 头部信息 + 热门 50 首 两个新接口拼起来
        runCatching {
            val d = api("/weapi/artist/head/info/get", JSONObject().put("id", id)).optJSONObject("data") ?: throw e
            val a = d.optJSONObject("artist") ?: throw e
            val hot = api("/weapi/artist/top/song", JSONObject().put("id", id)).optJSONArray("songs") ?: JSONArray()
            Artist(id, a.optString("name"), pic(a.optString("cover").ifEmpty { a.optString("avatar") }), a.optString("briefDesc"), a.optInt("musicSize"), a.optInt("albumSize"),
                (0 until hot.length()).map { song(hot.getJSONObject(it)) }, d.optJSONObject("user")?.optBoolean("followed") ?: false)
        }.getOrElse { throw e }
    }
    private suspend fun artistV1(id: String): Artist {
        val j = api("/weapi/v1/artist/$id")
        val a = j.optJSONObject("artist") ?: throw NcmError("没有找到这位歌手")
        val hot = j.optJSONArray("hotSongs") ?: JSONArray()
        return Artist(id, a.optString("name"), pic(a.optString("picUrl")), a.optString("briefDesc"), a.optInt("musicSize"), a.optInt("albumSize"),
            (0 until hot.length()).map { song(hot.getJSONObject(it)) }, a.optBoolean("followed"))
    }

    private fun albumOf(o: JSONObject) = Album(o.get("id").toString(), o.optString("name"), pic(o.optString("picUrl")), o.optInt("size"),
        o.optLong("publishTime").takeIf { it > 0 }?.let { java.util.Calendar.getInstance().apply { timeInMillis = it }.get(java.util.Calendar.YEAR).toString() } ?: "",
        o.optJSONObject("artist")?.optString("name") ?: "")

    suspend fun artistAlbums(id: String): List<Album> = artistAlbumsPage(id, 0, 60).first

    /** 歌手的专辑分页 → (这一页, 还有没有更多) */
    suspend fun artistAlbumsPage(id: String, offset: Int, limit: Int = 60): Pair<List<Album>, Boolean> {
        if (Plugins.isPlugin(id)) return emptyList<Album>() to false
        if (Qqm.isQq(id)) return Qqm.artistAlbums(id, offset, limit)
        if (Kgm.isKg(id)) return emptyList<Album>() to false
        val j = api("/weapi/artist/albums/$id", JSONObject().put("limit", limit).put("offset", offset).put("total", true))
        val a = j.optJSONArray("hotAlbums") ?: JSONArray()
        return (0 until a.length()).map { albumOf(a.getJSONObject(it)) } to j.optBoolean("more")
    }

    suspend fun album(id: String): Pair<Album, List<Track>> {
        if (Plugins.isPlugin(id)) return Plugins.album(id)
        if (Qqm.isQq(id)) return Qqm.album(id)
        if (Kgm.isKg(id)) return Kgm.album(id)
        val j = api("/weapi/v1/album/$id")
        val songs = j.optJSONArray("songs") ?: JSONArray()
        val al = albumOf(j.optJSONObject("album") ?: throw NcmError("没有找到这张专辑"))
        return al to (0 until songs.length()).map { song(songs.getJSONObject(it)).let { t -> if (t.cover == null) t.copy(cover = al.pic) else t } }
    }

    suspend fun user(uid: String): User {
        val j = api("/weapi/v1/user/detail/$uid")
        val p = j.optJSONObject("profile") ?: throw NcmError("没有找到这个用户")
        return User(uid, p.optString("nickname"), pic(p.optString("avatarUrl")), pic(p.optString("backgroundUrl")), p.optString("signature"),
            j.optInt("level"), j.optInt("listenSongs"), p.optInt("follows"), p.optInt("followeds"), p.optBoolean("followed"))
    }

    /** 某个用户的歌单（第一个是 TA 喜欢的音乐） */
    suspend fun userPlaylists(uid: String): List<Playlist> {
        val a = api("/weapi/user/playlist", JSONObject().put("uid", uid).put("limit", 200).put("offset", 0).put("includeVideo", true)).optJSONArray("playlist") ?: JSONArray()
        return (0 until a.length()).map {
            val p = a.getJSONObject(it)
            Playlist(p.get("id").toString(), p.optString("name"), p.optInt("trackCount"), pic(p.optString("coverImgUrl")), it == 0 || p.optInt("specialType") == 5,
                mine = p.optJSONObject("creator")?.opt("userId")?.toString() == uid)
        }
    }

    suspend fun like(id: String, like: Boolean) {
        if (Plugins.isPlugin(id)) return Plugins.like(id, like)
        if (Kgm.isKg(id)) return // 酷狗的歌只记在本机
        if (Qqm.isQq(id)) return Qqm.like(id, like) // 登录 QQ 音乐时同步到它的「我喜欢」，没登录只记在本机
        api("/weapi/radio/like", JSONObject().put("alg", "itembased").put("trackId", id).put("like", like).put("time", "3"))
    }

    // ---------- 第 1 批：私人FM / 逐字歌词 / 评论 / 相似 / 搜索 ----------
    /** 歌词全套：普通 LRC、翻译、逐字（yrc，没有就是 null） */
    suspend fun lyricFull(id: String): LyricData {
        if (Plugins.isPlugin(id)) return Plugins.lyric(id)
        if (Qqm.isQq(id)) return Qqm.lyric(id)
        if (Kgm.isKg(id)) return Kgm.lyric(id)
        val j = api("/weapi/song/lyric", JSONObject().put("id", id).put("lv", -1).put("tv", -1).put("rv", -1).put("kv", -1).put("yv", -1).put("ytv", -1).put("_nmclfl", 1))
        val l = j.optJSONObject("lrc")?.optString("lyric") ?: ""
        val y = j.optJSONObject("yrc")?.optString("lyric")?.ifEmpty { null }
        if (l.isEmpty() && y == null) throw NcmError("暂无歌词")
        return LyricData(l, j.optJSONObject("tlyric")?.optString("lyric")?.ifEmpty { null }, y)
    }

    /** 私人 FM：每次给 3 首左右 */
    suspend fun fm(): List<Track> {
        val a = api("/weapi/v1/radio/get").optJSONArray("data") ?: JSONArray()
        return (0 until a.length()).map { song(a.getJSONObject(it)) }
    }
    suspend fun fmTrash(id: String) { api("/weapi/radio/trash/add", JSONObject().put("songId", id).put("alg", "RT").put("time", 25)) }

    /** 歌曲评论：hot=true 热门，false 最新 → (评论, 总数) */
    suspend fun comments(id: String, hot: Boolean, offset: Int, before: Long = 0): Pair<List<Comment>, Int> {
        if (Qqm.isQq(id) || Kgm.isKg(id) || Plugins.isPlugin(id)) return emptyList<Comment>() to 0
        // 热门用新版接口：带楼层回复数（cursor = normalHot#偏移）；最新还用老接口（按时间排）
        if (hot) runCatching {
            val d = api("/weapi/v2/resource/comments", JSONObject().put("threadId", "R_SO_4_$id").put("pageNo", offset / 20 + 1).put("pageSize", 20)
                .put("cursor", "normalHot#$offset").put("sortType", 2).put("showInner", true)).optJSONObject("data")
            val a = d?.optJSONArray("comments")
            if (a != null) return (0 until a.length()).map { commentOf(a.getJSONObject(it)) } to d.optInt("totalCount")
        }
        val path = if (hot) "/weapi/v1/resource/hotcomments/R_SO_4_$id" else "/weapi/v1/resource/comments/R_SO_4_$id"
        val j = api(path, JSONObject().put("rid", id).put("limit", 20).put("offset", offset).put("beforeTime", before))
        val a = (if (hot) j.optJSONArray("hotComments") else j.optJSONArray("comments")) ?: JSONArray()
        return (0 until a.length()).map { commentOf(a.getJSONObject(it)) } to j.optInt("total")
    }
    private fun commentOf(c: JSONObject): Comment {
        val u = c.optJSONObject("user") ?: JSONObject()
        val r = c.optJSONArray("beReplied")?.optJSONObject(0)
        return Comment(c.optLong("commentId"), u.optString("nickname"), u.opt("userId")?.toString() ?: "", pic(u.optString("avatarUrl")), c.optString("content"), c.optInt("likedCount"), c.optLong("time"),
            r?.let { x -> "@${x.optJSONObject("user")?.optString("nickname") ?: ""}：${x.optString("content")}" },
            c.optJSONObject("showFloorComment")?.optInt("replyCount") ?: c.optInt("replyCount"), c.optBoolean("liked"))
    }

    /** 楼层回复：某条评论下面的回复 → (回复, 下一页游标，-1 = 没有更多) */
    suspend fun commentFloor(songId: String, parent: Long, cursor: Long = -1): Pair<List<Comment>, Long> {
        val j = api("/weapi/resource/comment/floor/get", JSONObject().put("parentCommentId", parent).put("threadId", "R_SO_4_$songId").put("time", cursor).put("limit", 20))
        val d = j.optJSONObject("data") ?: JSONObject()
        val a = d.optJSONArray("comments") ?: JSONArray()
        val list = (0 until a.length()).map { commentOf(a.getJSONObject(it)) }
        return list to if (d.optBoolean("hasMore")) d.optLong("time", list.lastOrNull()?.time ?: -1) else -1
    }

    suspend fun simiSongs(id: String): List<Track> {
        if (Qqm.isQq(id) || Kgm.isKg(id) || Plugins.isPlugin(id)) return emptyList()
        val a = api("/weapi/v1/discovery/simiSong", JSONObject().put("songid", id).put("limit", 50).put("offset", 0)).optJSONArray("songs") ?: JSONArray()
        return details((0 until a.length()).map { a.getJSONObject(it).get("id").toString() })
    }
    suspend fun simiArtists(id: String): List<Artist> {
        if (Qqm.isQq(id) || Kgm.isKg(id) || Plugins.isPlugin(id)) return emptyList()
        val a = api("/weapi/discovery/simiArtist", JSONObject().put("artistid", id)).optJSONArray("artists") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Artist(o.get("id").toString(), o.optString("name"), pic(o.optString("picUrl")), "", o.optInt("musicSize"), o.optInt("albumSize"), emptyList()) } }
    }
    private fun plOf(o: JSONObject) = Playlist(o.get("id").toString(), o.optString("name"), o.optInt("trackCount"), pic(o.optString("coverImgUrl").ifEmpty { o.optString("picUrl") }), mine = false)
    suspend fun simiPlaylists(songId: String): List<Playlist> {
        if (Qqm.isQq(songId) || Kgm.isKg(songId) || Plugins.isPlugin(songId)) return emptyList()
        val a = api("/weapi/discovery/simiPlaylist", JSONObject().put("songid", songId).put("limit", 50).put("offset", 0)).optJSONArray("playlists") ?: JSONArray()
        return (0 until a.length()).map { plOf(a.getJSONObject(it)) }
    }

    suspend fun suggest(q: String): List<String> {
        val a = api("/weapi/search/suggest/keyword", JSONObject().put("s", q)).optJSONObject("result")?.optJSONArray("allMatch") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).optString("keyword") }.filter { it.isNotEmpty() }.distinct()
    }
    suspend fun hotSearch(): List<String> {
        val a = api("/weapi/hotsearchlist/get").optJSONArray("data") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).optString("searchWord") }.filter { it.isNotEmpty() }
    }

    /** 搜索类型：1 单曲 / 10 专辑 / 100 歌手 / 1000 歌单 / 1002 用户 / 1006 歌词 */
    private suspend fun searchRes(q: String, type: Int): JSONObject =
        api("/weapi/search/get", JSONObject().put("s", q).put("type", type).put("limit", 30).put("offset", 0)).optJSONObject("result") ?: JSONObject()
    suspend fun searchLyric(q: String): List<Track> {
        val a = searchRes(q, 1006).optJSONArray("songs") ?: return emptyList()
        return details((0 until a.length()).map { a.getJSONObject(it).get("id").toString() })
    }
    suspend fun searchAlbums(q: String): List<Album> { val a = searchRes(q, 10).optJSONArray("albums") ?: return emptyList(); return (0 until a.length()).map { albumOf(a.getJSONObject(it)) } }
    suspend fun searchArtists(q: String): List<Artist> {
        val a = searchRes(q, 100).optJSONArray("artists") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Artist(o.get("id").toString(), o.optString("name"), pic(o.optString("picUrl").ifEmpty { o.optString("img1v1Url") }), "", o.optInt("musicSize"), o.optInt("albumSize"), emptyList()) } }
    }
    suspend fun searchPlaylists(q: String): List<Playlist> { val a = searchRes(q, 1000).optJSONArray("playlists") ?: return emptyList(); return (0 until a.length()).map { plOf(a.getJSONObject(it)) } }
    suspend fun searchUsers(q: String): List<UserBrief> {
        val a = searchRes(q, 1002).optJSONArray("userprofiles") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> UserBrief(o.get("userId").toString(), o.optString("nickname"), pic(o.optString("avatarUrl")), o.optString("signature")) } }
    }

    // ---------- 第 2 批：排行榜 / 歌单广场 / 新碟新歌 / 历史日推 ----------
    suspend fun toplists(): List<Playlist> {
        val a = runCatching { api("/weapi/toplist").optJSONArray("list") }.getOrNull() ?: plainGet("/api/toplist").optJSONArray("list") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> plOf(o).copy(desc = o.optString("updateFrequency")) } }
    }
    suspend fun recPlaylists(): List<Playlist> {
        val a = api("/weapi/personalized/playlist", JSONObject().put("limit", 30).put("total", true).put("n", 1000)).optJSONArray("result") ?: JSONArray()
        return (0 until a.length()).map { plOf(a.getJSONObject(it)) }
    }
    /** 歌单广场的分类：热门分类在前 */
    suspend fun categories(): List<String> {
        val a = api("/weapi/playlist/catalogue").optJSONArray("sub") ?: JSONArray()
        val l = (0 until a.length()).map { a.getJSONObject(it) }
        return listOf("全部") + (l.filter { it.optBoolean("hot") } + l.filter { !it.optBoolean("hot") }).map { it.optString("name") }
    }
    suspend fun square(cat: String, offset: Int): Pair<List<Playlist>, Boolean> {
        val j = api("/weapi/playlist/list", JSONObject().put("cat", cat).put("order", "hot").put("limit", 30).put("offset", offset).put("total", true))
        val a = j.optJSONArray("playlists") ?: JSONArray()
        return (0 until a.length()).map { plOf(a.getJSONObject(it)) } to j.optBoolean("more")
    }
    /** 新碟：ALL / ZH / EA / KR / JP */
    suspend fun newAlbums(area: String, offset: Int): List<Album> {
        val a = api("/weapi/album/new", JSONObject().put("limit", 30).put("offset", offset).put("total", true).put("area", area)).optJSONArray("albums") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> albumOf(o).let { al -> if (al.artist.isEmpty()) al.copy(artist = o.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: "") else al } } }
    }
    /** 新歌速递：0 全部 / 7 华语 / 96 欧美 / 8 日本 / 16 韩国 */
    suspend fun newSongs(area: Int): List<Track> {
        val a = api("/weapi/v1/discovery/new/songs", JSONObject().put("areaId", area).put("total", true)).optJSONArray("data") ?: JSONArray()
        return (0 until a.length()).map { song(a.getJSONObject(it)) }.let { l -> if (l.any { it.cover == null }) details(l.map { it.id }) else l }
    }
    suspend fun historyDates(): List<String> {
        if (!loggedIn) throw NcmError("请先登录")
        val a = api("/weapi/discovery/recommend/songs/history/recent").optJSONObject("data")?.optJSONArray("dates") ?: JSONArray()
        return (0 until a.length()).map { a.getString(it) }
    }
    suspend fun historySongs(date: String): List<Track> {
        val a = api("/weapi/discovery/recommend/songs/history/detail", JSONObject().put("date", date)).optJSONObject("data")?.optJSONArray("songs") ?: JSONArray()
        return (0 until a.length()).map { song(a.getJSONObject(it)) }
    }

    // ---------- 第 3 批：歌单管理 / 收藏关注 / 听歌排行 / 签到 / 云盘 ----------
    suspend fun createPlaylist(name: String, private: Boolean): String =
        api("/weapi/playlist/create", JSONObject().put("name", name).put("privacy", if (private) "10" else "0").put("type", "NORMAL")).let { it.optJSONObject("playlist")?.opt("id")?.toString() ?: it.opt("id")?.toString() ?: "" }
    suspend fun deletePlaylist(id: String) { api("/weapi/playlist/remove", JSONObject().put("ids", "[$id]")) }
    /** 往歌单加 / 删歌：op = add / del */
    suspend fun playlistTracks(pid: String, op: String, ids: List<String>) {
        val j = raw("/weapi/playlist/manipulate/tracks", JSONObject().put("op", op).put("pid", pid).put("trackIds", JSONArray(ids).toString()).put("imme", "true")).j
        val c = j.optInt("code", 200)
        if (c == 502) throw NcmError("歌曲已经在歌单里了")
        if (c != 200) throw NcmError("${j.optString("message", "操作失败")}（$c）")
    }
    suspend fun subscribePlaylist(id: String, sub: Boolean) { api("/weapi/playlist/${if (sub) "subscribe" else "unsubscribe"}", JSONObject().put("id", id)) }
    suspend fun playlistSubscribed(id: String): Boolean =
        api("/weapi/v6/playlist/detail", JSONObject().put("id", id).put("n", 0).put("s", 0)).optJSONObject("playlist")?.optBoolean("subscribed") ?: false
    suspend fun followArtist(id: String, on: Boolean) { api("/weapi/artist/${if (on) "sub" else "unsub"}", JSONObject().put("artistId", id).put("artistIds", "[$id]")) }
    suspend fun followUser(uid: String, on: Boolean) { api("/weapi/user/${if (on) "follow" else "delfollow"}/$uid") }
    suspend fun subAlbum(id: String, on: Boolean) { api("/weapi/album/${if (on) "sub" else "unsub"}", JSONObject().put("id", id)) }
    suspend fun subAlbums(): List<Album> {
        val a = api("/weapi/album/sublist", JSONObject().put("limit", 200).put("offset", 0).put("total", true)).optJSONArray("data") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> albumOf(o).let { al -> if (al.artist.isEmpty()) al.copy(artist = o.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: "") else al } } }
    }
    suspend fun subArtists(): List<Artist> {
        val a = api("/weapi/artist/sublist", JSONObject().put("limit", 200).put("offset", 0).put("total", true)).optJSONArray("data") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Artist(o.get("id").toString(), o.optString("name"), pic(o.optString("picUrl").ifEmpty { o.optString("img1v1Url") }), "", o.optInt("musicSize"), o.optInt("albumSize"), emptyList(), true) } }
    }
    /** 听歌排行：week=true 最近一周，false 所有时间 → (歌, 次数或分数) */
    suspend fun playRecord(uid: String, week: Boolean): List<Pair<Track, Int>> {
        val j = api("/weapi/v1/play/record", JSONObject().put("uid", uid).put("type", if (week) 1 else 0))
        val a = (if (week) j.optJSONArray("weekData") else j.optJSONArray("allData")) ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> song(o.getJSONObject("song")) to o.optInt("playCount", o.optInt("score")) } }
    }
    /** 每日签到：返回提示文字 */
    /** → (今天是否已签到, 提示文字) */
    suspend fun signin(): Pair<Boolean, String> {
        val r = raw("/weapi/point/dailyTask", JSONObject().put("type", 1)).j
        return when (r.optInt("code")) { 200 -> true to "签到成功，经验 +${r.optInt("point")}"; -2 -> true to "今天已经签到过了"; 301 -> false to "请先登录"; else -> false to r.optString("msg", "签到失败") }
    }

    /** 听歌记录回传：startplay 进「最近播放」，play（带收听秒数）计入听歌排行 */
    suspend fun scrobbleStart(id: String) = runCatching {
        if (Qqm.isQq(id) || Kgm.isKg(id) || Plugins.isPlugin(id)) return@runCatching
        api("/weapi/feedback/weblog", JSONObject().put("logs", JSONArray().put(JSONObject().put("action", "startplay")
            .put("json", JSONObject().put("id", id).put("type", "song").put("mainsite", "1").put("mainsiteWeb", "1").put("content", "id="))).toString()))
    }
    suspend fun scrobble(id: String, seconds: Long) = runCatching {
        if (Qqm.isQq(id) || Kgm.isKg(id) || Plugins.isPlugin(id)) return@runCatching
        api("/weapi/feedback/weblog", JSONObject().put("logs", JSONArray().put(JSONObject().put("action", "play")
            .put("json", JSONObject().put("download", 0).put("end", "playend").put("id", id).put("sourceId", "").put("time", seconds)
                .put("type", "song").put("wifi", 0).put("source", "list").put("mainsite", "1").put("mainsiteWeb", "1").put("content", "id="))).toString()))
    }
    /** 云盘：自己上传的歌 */
    suspend fun cloud(): List<Track> {
        val out = ArrayList<Track>()
        var off = 0
        while (true) {
            val j = api("/weapi/v1/cloud/get", JSONObject().put("limit", 200).put("offset", off))
            val a = j.optJSONArray("data") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val sim = o.optJSONObject("simpleSong")
                out += if (sim != null && sim.optString("name").isNotEmpty()) song(sim).let { t -> if (t.cover == null) t.copy(cover = pic(o.optJSONObject("simpleSong")?.optJSONObject("al")?.optString("picUrl"))) else t }
                       else Track(o.get("songId").toString(), o.optString("songName").ifEmpty { o.optString("fileName") }, o.optString("artist").ifEmpty { "未知艺人" }, o.optString("album"), 0, null)
            }
            if (!j.optBoolean("hasMore") || a.length() == 0 || off > 3000) break
            off += a.length()
        }
        return out
    }

    // ---------- 第 4 批：MV / 播客电台 / 评论互动 ----------
    private fun mvOf(o: JSONObject) = Mv(o.get("id").toString(), o.optString("name"), pic(o.optString("imgurl16v9").ifEmpty { o.optString("cover").ifEmpty { o.optString("picUrl").ifEmpty { o.optString("imgurl") } } }),
        o.optString("artistName"), o.optLong("duration"), o.optLong("playCount"))
    suspend fun artistMvs(id: String): List<Mv> {
        val a = api("/weapi/artist/mvs", JSONObject().put("artistId", id).put("limit", 30).put("offset", 0).put("total", true)).optJSONArray("mvs") ?: JSONArray()
        return (0 until a.length()).map { mvOf(a.getJSONObject(it)) }
    }
    suspend fun recMvs(): List<Mv> {
        val a = api("/weapi/personalized/mv").optJSONArray("result") ?: JSONArray()
        return (0 until a.length()).map { mvOf(a.getJSONObject(it)) }
    }
    suspend fun topMvs(): List<Mv> {
        val a = api("/weapi/mv/toplist", JSONObject().put("limit", 50).put("offset", 0).put("total", true).put("area", "")).optJSONArray("data") ?: JSONArray()
        return (0 until a.length()).map { mvOf(a.getJSONObject(it)) }
    }
    suspend fun mvUrl(id: String): String {
        for (r in listOf(1080, 720, 480)) {
            val u = api("/weapi/song/enhance/play/mv/url", JSONObject().put("id", id).put("r", r)).optJSONObject("data")?.optString("url") ?: ""
            if (u.isNotEmpty() && u != "null") return u.replace("http:", "https:")
        }
        throw NcmError("拿不到 MV 地址")
    }

    suspend fun hotRadios(offset: Int): List<Radio> {
        val a = api("/weapi/djradio/hot/v1", JSONObject().put("limit", 30).put("offset", offset)).optJSONArray("djRadios") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Radio(o.get("id").toString(), o.optString("name"), pic(o.optString("picUrl")), o.optString("rcmdtext").ifEmpty { o.optString("category") }, o.optInt("programCount")) } }
    }
    /** 电台节目：每期节目背后是一首「歌」，用它的 id 取播放地址 */
    suspend fun radioPrograms(rid: String): List<Track> {
        val a = api("/weapi/dj/program/byradio", JSONObject().put("radioId", rid).put("limit", 100).put("offset", 0).put("asc", false)).optJSONArray("programs") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.getJSONObject(it).let { o ->
            val ms = o.optJSONObject("mainSong") ?: return@let null
            song(ms).copy(title = o.optString("name").ifEmpty { ms.optString("name") }, cover = pic(o.optString("coverUrl")) ?: song(ms).cover,
                artist = o.optJSONObject("dj")?.optString("nickname") ?: song(ms).artist, durationMs = o.optLong("duration", ms.optLong("duration")))
        } }
    }

    suspend fun commentLike(songId: String, cid: Long, on: Boolean) {
        api("/weapi/v1/comment/${if (on) "like" else "unlike"}", JSONObject().put("threadId", "R_SO_4_$songId").put("commentId", cid))
    }
    /** 发评论；replyTo 不为空时是回复某条评论 */
    suspend fun commentPost(songId: String, content: String, replyTo: Long?) {
        val d = JSONObject().put("threadId", "R_SO_4_$songId").put("content", content)
        if (replyTo != null) d.put("commentId", replyTo)
        api("/weapi/resource/comments/${if (replyTo != null) "reply" else "add"}", d)
    }

    // ---------- 登录 ----------
    suspend fun qrKey(): String = api("/weapi/login/qrcode/unikey", JSONObject().put("type", 1)).optString("unikey").ifEmpty { throw NcmError("获取二维码失败") }
    fun qrUrl(key: String) = "https://music.163.com/login?codekey=$key"

    /** 800 过期 / 801 等待扫码 / 802 已扫码待确认 / 803 成功（已保存登录信息） */
    suspend fun qrCheck(key: String): Int {
        val r = raw("/weapi/login/qrcode/client/login", JSONObject().put("key", key).put("type", 1))
        val code = r.j.optInt("code")
        if (code == 803) saveCookie(r.setCookie.joinToString("; ") + ";" + r.j.optString("cookie"))
        return code
    }

    fun saveCookie(text: String) {
        val m = LinkedHashMap<String, String>()
        Regex("(MUSIC_U|__csrf|MUSIC_A_T|MUSIC_R_T|NMTID)=([^;,\\s]+)").findAll(text).forEach { m[it.groupValues[1]] = it.groupValues[2] }
        if (!m.containsKey("MUSIC_U")) throw NcmError("没有拿到 MUSIC_U，请改用粘贴 Cookie")
        cookie = m.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun logout() { prefs.edit().clear().apply() }
}
