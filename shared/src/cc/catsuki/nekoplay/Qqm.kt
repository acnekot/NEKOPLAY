package cc.catsuki.nekoplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * QQ 音乐接口（musicu.fcg 网关），直接在设备上请求，不需要服务器。
 *
 * 接口名、参数和登录流程参考 luren-dc/QQMusicApi（MIT 许可时期的版本，2026-04-02 之前），
 * 这里是按自己的需要重新写的 Kotlin 实现。原项目版权声明：
 *   MIT License, Copyright (c) 2023 Luren
 *
 * 所有 QQ 音乐的 id 在 NEKOPLAY 里都带 "qq:" 前缀（歌曲、专辑、歌手用 mid，歌单用数字 id），
 * Ncm 的对应函数见到这个前缀就转到这里，界面不用区分来源。
 */
object Qqm {
    const val P = "qq:"
    fun isQq(id: String?) = id?.startsWith(P) == true
    private fun raw(id: String) = id.removePrefix(P)

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val GATE = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    /** 拿不到 CDN 列表时用的播放地址域名 */
    private const val CDN = "https://isure.stream.qqmusic.qq.com/"

    /** 登录流程里要自己处理 302（读跳转地址和 Set-Cookie），所以单独一个不跟随跳转的客户端 */
    private val noRedirect: OkHttpClient by lazy { Ncm.http.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    private lateinit var prefs: KV
    fun init() { prefs = Plat.prefs("qq") }

    // ---------- 登录凭证 ----------
    val musicid: Long get() = prefs.getLong("musicid", 0)
    private val musickey: String get() = prefs.getString("musickey", "") ?: ""
    private val loginType: Int get() = prefs.getInt("loginType", 0)
    val loggedIn get() = musicid > 0 && musickey.isNotEmpty()
    val nick: String get() = prefs.getString("nick", "") ?: ""
    val avatar: String? get() = prefs.getString("avatar", "")?.ifEmpty { null }

    private val guid: String by lazy {
        prefs.getString("guid", null) ?: (1..32).map { "0123456789abcdef"[kotlin.random.Random.nextInt(16)] }.joinToString("").also { prefs.edit().putString("guid", it).apply() }
    }

    fun logout() { prefs.edit().remove("musicid").remove("musickey").remove("loginType").remove("refresh_key").remove("refresh_token").remove("openid").remove("access_token").remove("unionid").remove("nick").remove("avatar").remove("keyAt").apply() }

    private fun saveCredential(d: JSONObject) {
        val key = d.optString("musickey")
        val id = d.optLong("musicid", d.optString("str_musicid").toLongOrNull() ?: 0)
        if (key.isEmpty() || id <= 0) throw NcmError("登录失败：没有拿到凭证")
        val lt = d.optInt("loginType", 0).takeIf { it > 0 } ?: if (key.startsWith("W_X")) 1 else 2
        prefs.edit().putLong("musicid", id).putString("musickey", key).putInt("loginType", lt)
            .putString("refresh_key", d.optString("refresh_key")).putString("refresh_token", d.optString("refresh_token"))
            .putString("openid", d.optString("openid")).putString("access_token", d.optString("access_token")).putString("unionid", d.optString("unionid"))
            .putLong("keyAt", System.currentTimeMillis()).apply()
    }

    /** Hash33：g_tk 和 ptqrtoken 都用它 */
    fun hash33(s: String, h0: Long = 0): Long {
        var h = h0
        for (c in s) h = ((h shl 5) + h + c.code) and 0xFFFFFFFFFFFFL
        return h and 2147483647L
    }

    // ---------- 网关请求 ----------
    /** 公共参数：用 PC 客户端的身份（ct=19），不需要设备指纹 */
    private fun comm(extra: JSONObject?): JSONObject {
        val c = JSONObject().put("ct", 19).put("cv", 2201).put("platform", "").put("chid", "0")
            .put("g_tk", if (loggedIn) hash33(musickey, 5381) else 5381).put("guid", guid.uppercase())
        // 登录态：PC 客户端用 uin + Cookie，手机客户端用 qq + authst，两种都带上
        if (loggedIn) c.put("uin", musicid).put("tmeLoginType", loginType).put("qq", musicid.toString()).put("authst", musickey)
        extra?.keys()?.forEach { c.put(it, extra.opt(it)) }
        return c
    }

    private fun cookie(): String = if (!loggedIn) "" else "uin=$musicid; qqmusic_uin=$musicid; qm_keyst=$musickey; qqmusic_key=$musickey; tmeLoginType=$loginType"

    /** 发一个请求，返回 req_0.data；code 不为 0 时抛错 */
    private suspend fun call(module: String, method: String, param: JSONObject, extraComm: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("comm", comm(extraComm)).put("req_0", JSONObject().put("module", module).put("method", method).put("param", param)).toString()
        val req = Request.Builder().url(GATE).post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", UA).header("Referer", "https://y.qq.com/").header("Origin", "https://y.qq.com").apply { cookie().takeIf { it.isNotEmpty() }?.let { header("Cookie", it) } }.build()
        val t0 = System.currentTimeMillis()
        val text = try {
            Ncm.http.newCall(req).execute().use { r ->
                val t = r.body?.string() ?: ""
                Debug.log("qq:$method", r.code, System.currentTimeMillis() - t0, r.header("Content-Type") ?: "", t)
                if (!r.isSuccessful) throw NcmError("QQ 音乐服务返回 ${r.code}")
                t
            }
        } catch (e: java.io.IOException) { throw NcmError("网络错误：${e.message}") }
        val j = try { JSONObject(text) } catch (e: Exception) { throw NcmError("QQ 音乐返回的不是 JSON") }
        val r0 = j.optJSONObject("req_0") ?: throw NcmError("QQ 音乐返回缺少数据（${j.optInt("code")}）")
        when (val c = r0.optInt("code", 0)) {
            0 -> {}
            1000 -> throw NcmError("QQ 音乐登录已过期，请重新登录")
            2000 -> throw NcmError("QQ 音乐签名校验失败")
            2001 -> throw NcmError("触发 QQ 音乐风控，稍后再试或先登录")
            else -> throw NcmError("QQ 音乐接口错误（$c）")
        }
        r0.optJSONObject("data") ?: JSONObject()
    }

    // ---------- 数据转换 ----------
    fun albumPic(mid: String?, size: Int = 500) = if (mid.isNullOrEmpty()) null else "https://y.gtimg.cn/music/photo_new/T002R${size}x${size}M000$mid.jpg"
    fun singerPic(mid: String?, size: Int = 500) = if (mid.isNullOrEmpty()) null else "https://y.gtimg.cn/music/photo_new/T001R${size}x${size}M000$mid.jpg"

    /** 歌曲 mid → 试听片段的 mid（没权限放整首时退而求其次） */
    private val trials = HashMap<String, String>()
    /** 歌曲 mid → 数字 id（收藏要用） */
    private val songIds = HashMap<String, Long>()
    /** 歌曲 mid → 文件信息（media_mid、各音质大小），拿播放地址时要用 */
    private val files = HashMap<String, JSONObject>()

    private fun arr(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    fun song(s0: JSONObject): Track {
        val s = s0.optJSONObject("songInfo") ?: s0
        val mid = s.optString("mid").ifEmpty { s.optString("songmid") }
        s.optJSONObject("file")?.let { files[mid] = it }
        s.optLong("id").takeIf { it > 0 }?.let { songIds[mid] = it }
        s.optJSONArray("vs")?.optString(0)?.takeIf { it.isNotEmpty() }?.let { trials[mid] = it }
        val singers = arr(s.optJSONArray("singer"))
        val al = s.optJSONObject("album") ?: JSONObject()
        val alMid = al.optString("mid").ifEmpty { s.optString("albummid") }
        val title = s.optString("name").ifEmpty { s.optString("title").ifEmpty { s.optString("songname") } }
        return Track(P + mid, title, singers.joinToString(" / ") { it.optString("name") }.ifEmpty { "未知艺人" },
            al.optString("name").ifEmpty { al.optString("title") }, s.optLong("interval") * 1000,
            albumPic(alMid) ?: singerPic(singers.firstOrNull()?.optString("mid")),
            singers.map { P + it.optString("mid") to it.optString("name") }, if (alMid.isEmpty()) "" else P + alMid,
            vip = s.optJSONObject("pay")?.optInt("pay_play") == 1)
    }

    // ---------- 搜索 ----------
    private fun searchId(): String {
        val e = kotlin.random.Random.nextLong(1, 21)
        return (e * 18014398509481984L + kotlin.random.Random.nextLong(0, 4194305) * 4294967296L + System.currentTimeMillis() % 86_400_000L).toString()
    }

    private suspend fun searchRaw(q: String, type: Int, page: Int, num: Int): JSONObject {
        val d = call("music.search.SearchCgiService", "DoSearchForQQMusicDesktop", JSONObject().put("searchid", searchId()).put("query", q)
            .put("search_type", type).put("num_per_page", num).put("page_num", page).put("highlight", 0).put("grp", 1))
        return d.optJSONObject("body") ?: JSONObject()
    }

    suspend fun search(q: String, page: Int = 1): List<Track> {
        val b = searchRaw(q, 0, page, 30)
        return arr(b.optJSONObject("song")?.optJSONArray("list") ?: b.optJSONArray("item_song")).map { song(it) }
    }

    suspend fun searchAlbums(q: String): List<Album> {
        val b = searchRaw(q, 2, 1, 30)
        return arr(b.optJSONObject("album")?.optJSONArray("list") ?: b.optJSONArray("item_album")).map { a ->
            val mid = a.optString("albumMID").ifEmpty { a.optString("albummid").ifEmpty { a.optString("mid") } }
            Album(P + mid, a.optString("albumName").ifEmpty { a.optString("name") }, a.optString("albumPic").ifEmpty { albumPic(mid) ?: "" }.ifEmpty { null }?.replace("http:", "https:"),
                a.optInt("song_count"), a.optString("publicTime").take(4), a.optString("singerName").ifEmpty { arr(a.optJSONArray("singer_list")).joinToString(" / ") { it.optString("name") } })
        }
    }

    suspend fun searchPlaylists(q: String): List<Playlist> {
        val b = searchRaw(q, 3, 1, 30)
        return arr(b.optJSONObject("songlist")?.optJSONArray("list") ?: b.optJSONArray("item_songlist")).map { p ->
            Playlist(P + p.opt("dissid").toString(), p.optString("dissname"), p.optInt("song_count"), p.optString("imgurl").ifEmpty { null }?.replace("http:", "https:"), mine = false)
        }
    }

    // ---------- 播放地址 ----------
    /** 音质档位对应 NEKOPLAY 的 Ncm.LEVELS：标准 / 较高 / 极高 / 无损 / Hi-Res（前缀、扩展名、文件大小字段、码率） */
    private class Fmt(val prefix: String, val ext: String, val sizeKey: String, val kbps: Int, val level: String)
    private val FMTS = listOf(
        Fmt("M500", ".mp3", "size_128mp3", 128, "standard"),
        Fmt("C600", ".m4a", "size_192aac", 192, "higher"),
        Fmt("M800", ".mp3", "size_320mp3", 320, "exhigh"),
        Fmt("F000", ".flac", "size_flac", 0, "lossless"),
        Fmt("RS01", ".flac", "size_hires", 0, "hires"),
    )
    private val urlMemo = HashMap<String, Pair<Stream, Long>>()

    private suspend fun fileInfo(mid: String): JSONObject {
        files[mid]?.let { return it }
        details(listOf(P + mid))
        return files[mid] ?: JSONObject()
    }

    suspend fun stream(id: String, quality: Int): Stream {
        val mid = raw(id)
        val key = "${mid}_$quality"
        urlMemo[key]?.let { (s, exp) -> if (System.currentTimeMillis() < exp) return s }
        val f = fileInfo(mid)
        // 文件名：前缀 + media_mid + 扩展名；没有 media_mid 时是 mid 写两遍
        val media = f.optString("media_mid").ifEmpty { mid + mid }
        var err = if (loggedIn) "没有可用的播放地址（这首歌需要 QQ 音乐会员或单独购买）" else "这首歌需要登录 QQ 音乐才能播放（未登录只能放少数免费歌）"
        val codes = LinkedHashSet<Int>()
        for (i in quality.coerceIn(0, FMTS.lastIndex) downTo 0) {
            val fmt = FMTS[i]
            // 文件信息里明确是 0 的音质跳过（没有这个文件）；字段不存在时照样试一次
            if (f.has(fmt.sizeKey) && f.optLong(fmt.sizeKey) <= 0 && i > 0) continue
            try {
                val d = call("music.vkey.GetVkey", "UrlGetVkey", JSONObject().put("uin", if (loggedIn) musicid.toString() else "0")
                    .put("filename", JSONArray().put("${fmt.prefix}$media${fmt.ext}")).put("guid", guid).put("songmid", JSONArray().put(mid))
                    .put("songtype", JSONArray().put(0)).put("ctx", 0))
                val info = d.optJSONArray("midurlinfo")?.optJSONObject(0) ?: continue
                val purl = info.optString("purl")
                if (purl.isEmpty()) { codes += info.optInt("result"); if (info.optInt("result") == 104003) err = "这首歌需要 QQ 音乐会员或购买"; continue }
                val host = d.optJSONArray("sip")?.optString(0)?.ifEmpty { null } ?: CDN
                val size = f.optLong(fmt.sizeKey)
                val kbps = if (fmt.kbps > 0) fmt.kbps else if (size > 0 && fileDur[mid] != null) (size * 8 / fileDur[mid]!! / 1000).toInt() else 0
                val s = Stream(host.replace("http:", "https:").trimEnd('/') + "/" + purl, fmt.ext.removePrefix("."), kbps, fmt.level)
                urlMemo[key] = s to System.currentTimeMillis() + 10 * 60_000
                return s
            } catch (e: NcmError) { err = e.message ?: err }
        }
        // 整首拿不到：放试听片段（一般 30~60 秒），并提示原因
        trials[mid]?.let { t ->
            runCatching {
                val d = call("music.vkey.GetVkey", "UrlGetVkey", JSONObject().put("uin", if (loggedIn) musicid.toString() else "0")
                    .put("filename", JSONArray().put("RS02$t.mp3")).put("guid", guid).put("songmid", JSONArray().put(mid)).put("songtype", JSONArray().put(0)).put("ctx", 0))
                val purl = d.optJSONArray("midurlinfo")?.optJSONObject(0)?.optString("purl").orEmpty()
                if (purl.isNotEmpty()) {
                    Engine.error.value = "这首是会员歌曲，当前账号只能放试听片段" + if (err.contains("会员")) "" else "（$err）"
                    return Stream(CDN + purl, "mp3", 128, "standard")
                }
            }
        }
        throw NcmError(err + if (Debug.on && codes.isNotEmpty()) "（result ${codes.joinToString()}）" else "")
    }
    /** 秒数（算无损码率用） */
    private val fileDur = HashMap<String, Long>()

    // ---------- 歌曲详情 ----------
    suspend fun details(ids: List<String>): List<Track> {
        val mids = ids.filter { isQq(it) }.map { raw(it) }
        if (mids.isEmpty()) return emptyList()
        val by = HashMap<String, Track>()
        for (part in mids.chunked(100)) {
            val d = call("music.trackInfo.UniformRuleCtrl", "CgiGetTrackInfo", JSONObject().put("mids", JSONArray(part))
                .put("types", JSONArray(part.map { 0 })).put("modify_stamp", JSONArray(part.map { 0 })).put("ctx", 0).put("client", 1))
            arr(d.optJSONArray("tracks")).forEach { o -> song(o).let { t -> by[t.id] = t; fileDur[raw(t.id)] = t.durationMs / 1000 } }
        }
        return ids.mapNotNull { by[it] }
    }

    // ---------- 歌词 ----------
    @OptIn(ExperimentalEncodingApi::class)
    private fun b64(s: String): String {
        if (s.isEmpty()) return ""
        val t = s.trim()
        if (t.startsWith("[")) return t
        return runCatching { Base64.decode(t).decodeToString() }.getOrDefault("")
    }

    suspend fun lyric(id: String): LyricData {
        val d = call("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", JSONObject().put("songMid", raw(id)).put("crypt", 0).put("ct", 19).put("cv", 2201)
            .put("lrc_t", 0).put("qrc", 0).put("qrc_t", 0).put("roma", 0).put("roma_t", 0).put("trans", 1).put("trans_t", 0).put("type", 1))
        val l = b64(d.optString("lyric"))
        if (l.isBlank()) throw NcmError("暂无歌词")
        return LyricData(l, b64(d.optString("trans")).ifBlank { null }, null)
    }

    // ---------- 专辑 / 歌手 / 歌单 ----------
    suspend fun album(id: String): Pair<Album, List<Track>> {
        val mid = raw(id)
        val info = runCatching { call("music.musichallAlbum.AlbumInfoServer", "GetAlbumDetail", JSONObject().put("albumMid", mid)) }.getOrNull()
        val songs = call("music.musichallAlbum.AlbumSongList", "GetAlbumSongList", JSONObject().put("albumMid", mid).put("begin", 0).put("num", 200).put("order", 2))
        val list = arr(songs.optJSONArray("songList")).map { song(it) }
        val b = info?.optJSONObject("basicInfo") ?: JSONObject()
        val singers = arr(info?.optJSONObject("singer")?.optJSONArray("singerList"))
        val al = Album(id, b.optString("albumName").ifEmpty { list.firstOrNull()?.album ?: "" }, albumPic(mid), songs.optInt("totalNum", list.size),
            b.optString("publishDate").take(4), singers.joinToString(" / ") { it.optString("name") }.ifEmpty { list.firstOrNull()?.artist ?: "" })
        return al to list
    }

    suspend fun artist(id: String): Artist {
        val mid = raw(id)
        val songs = call("musichall.song_list_server", "GetSingerSongList", JSONObject().put("singerMid", mid).put("order", 1).put("number", 50).put("begin", 0))
        val hot = arr(songs.optJSONArray("songList")).map { song(it) }
        val desc = runCatching { call("music.musichallSinger.SingerInfoInter", "GetSingerDetail", JSONObject().put("singer_mids", JSONArray().put(mid)).put("groups", 1).put("wikis", 1)) }.getOrNull()
        val s = arr(desc?.optJSONArray("singer_list")).firstOrNull()
        val basic = s?.optJSONObject("basic_info") ?: JSONObject()
        val name = basic.optString("name").ifEmpty { hot.firstOrNull()?.artists?.firstOrNull { it.first == id }?.second ?: "" }
        return Artist(id, name, singerPic(mid), s?.optJSONObject("ex_info")?.optString("desc") ?: "", songs.optInt("totalNum", hot.size), 0, hot)
    }

    suspend fun artistAlbums(id: String, begin: Int, num: Int = 60): Pair<List<Album>, Boolean> {
        val d = call("music.musichallAlbum.AlbumListServer", "GetAlbumList", JSONObject().put("singerMid", raw(id)).put("order", 1).put("number", num).put("begin", begin))
        val l = arr(d.optJSONArray("albumList")).map { a ->
            val mid = a.optString("albumMid")
            Album(P + mid, a.optString("albumName"), albumPic(mid), a.optInt("totalNum"), a.optString("publishDate").take(4), a.optString("singerName"))
        }
        return l to (begin + l.size < d.optInt("total", 0))
    }

    /** 歌单的歌（"qq:liked" 是「我喜欢」） */
    suspend fun playlistTracks(id: String): List<Track> {
        if (raw(id).startsWith("top")) return toplistTracks(raw(id).removePrefix("top").toInt())
        val liked = id == P + "liked"
        val out = ArrayList<Track>()
        var begin = 0
        while (true) {
            val p = JSONObject().put("disstid", if (liked) 0 else raw(id).toLong()).put("dirid", if (liked) 201 else 0).put("tag", 1)
                .put("song_begin", begin).put("song_num", 200).put("userinfo", 1).put("orderlist", 1).put("onlysonglist", 0)
            if (liked) p.put("enc_host_uin", "").put("uin", musicid)
            val d = call("music.srfDissInfo.DissInfo", "CgiGetDiss", p)
            val l = arr(d.optJSONArray("songlist")).map { song(it) }
            out += l
            val total = d.optInt("total_song_num", out.size)
            if (l.isEmpty() || out.size >= total || out.size >= 3000) break
            begin += l.size
        }
        return out
    }

    /** 自己建的歌单 + 「我喜欢」（放第一个） */
    suspend fun playlists(): List<Playlist> {
        if (!loggedIn) throw NcmError("请先登录 QQ 音乐")
        val d = call("music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", JSONObject().put("uin", musicid.toString()))
        fun pic(p: JSONObject) = listOf("picUrl", "bigpicUrl", "bigPicUrl", "coverUrl", "logo").map { p.optString(it) }.firstOrNull { it.startsWith("http") }?.replace("http://", "https://")
        val mine = arr(d.optJSONArray("v_playlist")).map { p ->
            val dir = p.optInt("dirId")
            if (dir == 201) Playlist(P + "liked", "我喜欢", p.optInt("songNum"), pic(p), liked = true)
            else Playlist(P + p.opt("tid").toString(), p.optString("dirName"), p.optInt("songNum"), pic(p))
        }
        val fav = runCatching {
            val f = call("music.musicasset.PlaylistFavRead", "CgiGetPlaylistFavInfo", JSONObject().put("uin", musicid.toString()).put("offset", 0).put("size", 100))
            arr(f.optJSONArray("v_list")).map { p -> Playlist(P + p.opt("tid").toString(), p.optString("name"), p.optInt("songnum"), pic(p), mine = false) }
        }.getOrDefault(emptyList())
        return mine.sortedByDescending { it.liked } + fav
    }

    suspend fun toplists(): List<Playlist> {
        val d = call("music.musicToplist.Toplist", "GetAll", JSONObject())
        return arr(d.optJSONArray("group")).flatMap { g -> arr(g.optJSONArray("toplist")) }.map { t ->
            Playlist(P + "top" + t.optInt("topId"), t.optString("title"), t.optInt("totalNum"), t.optString("frontPicUrl").ifEmpty { t.optString("headPicUrl") }.ifEmpty { null }?.replace("http:", "https:"), mine = false)
        }
    }

    suspend fun toplistTracks(topId: Int): List<Track> {
        val d = call("music.musicToplist.Toplist", "GetDetail", JSONObject().put("topid", topId).put("offset", 0).put("num", 100).put("period", ""))
        return arr(d.optJSONArray("songInfoList")).map { song(it) }
    }

    /** 账号昵称和头像 */
    suspend fun refreshProfile() {
        if (!loggedIn) return
        runCatching {
            val d = call("music.UnifiedHomepage.UnifiedHomepageSrv", "GetHomepageHeader", JSONObject().put("uin", musicid.toString()).put("IsQueryTabDetail", 1))
            val b = d.optJSONObject("Info")?.optJSONObject("BaseInfo") ?: return@runCatching
            prefs.edit().putString("nick", b.optString("Name")).putString("avatar", b.optString("Avatar").replace("http:", "https:")).apply()
        }
    }

    // ---------- 收藏 / 新碟（接口见 docs/qq-protocol.md） ----------
    /** 加入 / 移出 QQ 音乐的「我喜欢」（目录 201） */
    suspend fun like(id: String, on: Boolean) {
        if (!loggedIn) return
        val mid = raw(id)
        val sid = songIds[mid] ?: details(listOf(id)).let { songIds[mid] } ?: throw NcmError("找不到这首歌的 QQ 音乐 id")
        val d = call("music.musicasset.PlaylistDetailWrite", if (on) "AddSonglist" else "DelSonglist", JSONObject().put("dirId", 201).put("tid", 0).put("bFmtUtf8", true)
            .put("v_songInfo", JSONArray().put(JSONObject().put("songId", sid).put("songType", 0))))
        val rc = d.optInt("retCode", 0)
        if (rc != 0 && rc != 80092) throw NcmError("QQ 音乐收藏失败（$rc）")
    }

    /** 新碟上架：area 1 内地 / 2 港台 / 3 欧美 / 4 韩国 / 5 日本 */
    suspend fun newAlbums(area: Int, page: Int = 1, num: Int = 30): List<Album> {
        val d = call("newalbum.NewAlbumServer", "get_new_album_info", JSONObject().put("area", area).put("num", num).put("start", num * (page - 1)))
        return arr(d.optJSONArray("albums")).map { a ->
            val mid = a.optString("mid")
            Album(P + mid, a.optString("name"), albumPic(mid), 0, a.optString("release_time").take(4), arr(a.optJSONArray("singers")).joinToString(" / ") { it.optString("name") })
        }
    }

    // ---------- 扫码登录 ----------
    /** 二维码：图片字节 + 用来查状态的标识（QQ 是 qrsig，微信是 uuid） */
    class Qr(val png: ByteArray, val wx: Boolean, val id: String)
    /** 状态：0 成功 / 1 等待扫码 / 2 已扫码待确认 / 3 过期 / 4 拒绝 */
    enum class QrState { DONE, WAIT, SCANNED, EXPIRED, REFUSED }

    private fun cookiesOf(setCookie: List<String>) = setCookie.mapNotNull { c -> c.substringBefore(';').split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()

    private fun enc(s: String) = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff; val ch = c.toChar()
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-_.~") append(ch) else { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
        }
    }
    private fun dec(s: String): String {
        val out = ArrayList<Byte>(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length + 0 && i + 2 <= s.length - 1) { out.add(s.substring(i + 1, i + 3).toInt(16).toByte()); i += 3 }
            else { c.toString().encodeToByteArray().forEach { out.add(it) }; i++ }
        }
        return out.toByteArray().decodeToString()
    }
    private fun query(vararg kv: Pair<String, Any>) = kv.joinToString("&") { "${it.first}=${enc(it.second.toString())}" }

    suspend fun qrQq(): Qr = withContext(Dispatchers.IO) {
        val url = "https://ssl.ptlogin2.qq.com/ptqrshow?" + query("appid" to "716027609", "e" to "2", "l" to "M", "s" to "3", "d" to "72", "v" to "4",
            "t" to kotlin.random.Random.nextDouble().toString(), "daid" to "383", "pt_3rd_aid" to "100497308")
        noRedirect.newCall(Request.Builder().url(url).header("Referer", "https://xui.ptlogin2.qq.com/").header("User-Agent", UA).build()).execute().use { r ->
            val sig = cookiesOf(r.headers("Set-Cookie"))["qrsig"] ?: throw NcmError("获取 QQ 二维码失败")
            Qr(r.body?.bytes() ?: ByteArray(0), false, sig)
        }
    }

    suspend fun qrWx(): Qr = withContext(Dispatchers.IO) {
        val page = "https://open.weixin.qq.com/connect/qrconnect?" + query("appid" to "wx48db31d50e334801",
            "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/", "response_type" to "code", "scope" to "snsapi_login", "state" to "STATE",
            "href" to "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect")
        val html = Ncm.http.newCall(Request.Builder().url(page).header("User-Agent", UA).build()).execute().use { it.body?.string() ?: "" }
        val uuid = Regex("uuid=(.+?)\"").find(html)?.groupValues?.get(1) ?: throw NcmError("获取微信二维码失败")
        val img = Ncm.http.newCall(Request.Builder().url("https://open.weixin.qq.com/connect/qrcode/$uuid").header("Referer", "https://open.weixin.qq.com/connect/qrconnect").header("User-Agent", UA).build())
            .execute().use { it.body?.bytes() ?: ByteArray(0) }
        Qr(img, true, uuid)
    }

    /** step：确认之后换凭证的每一步，用来在界面上显示进度 */
    suspend fun qrCheck(q: Qr, step: (String) -> Unit = {}): QrState = if (q.wx) checkWx(q, step) else checkQq(q, step)

    // ---------- 网页登录（手机上用内嵌网页走 QQ 互联，可以一键拉起手机 QQ） ----------
    const val WEB_REDIRECT = "https://y.qq.com/portal/wx_redirect.html"
    fun webLoginUrl(): String = "https://graph.qq.com/oauth2.0/authorize?" + query("response_type" to "code", "client_id" to "100497308",
        "redirect_uri" to "$WEB_REDIRECT?login_type=1&surl=https://y.qq.com/", "state" to "state", "display" to "mobile")
    /** 网页里「一键登录」拉起 QQ 的地址，加上回跳到本 App */
    fun withCallback(u: String, ios: Boolean): String {
        // 1) p 里带上本 App 的包名，QQ 授权后按它跳回（网页本来是从页面地址的 pt_package / pt_bundleid 读的）
        var r = u
        Regex("([?&]p=)([^&]*)").find(r)?.let { m ->
            var inner = dec(m.groupValues[2])
            val key = if (ios) "pt_bundleid" else "pt_package"
            if ("$key=" !in inner) inner += "&$key=" + (if (ios) "cc.catsuki.nekoplay" else "cc.catsuki.nekoplay.native")
            r = r.replaceRange(m.groups[2]!!.range, enc(inner))
        }
        // 2) 网页会按浏览器类型自己填回跳 scheme（WebView 会被当成 Chrome 填 googlechrome://），换成本 App
        val cb = "schemacallback=" + enc("nekoplay://")
        return if (Regex("[?&]schemacallback=").containsMatchIn(r)) r.replace(Regex("schemacallback=[^&]*"), cb) else "$r&$cb"
    }
    /** QQ 跳回来的 nekoplay://… 里取出要继续加载的网页地址 */
    fun callbackToUrl(cb: String): String? {
        val r = cb.removePrefix("nekoplay://").replace("(null)", "")
        if (r.startsWith("https://") || r.startsWith("http://")) return r
        val i = r.indexOf("http"); if (i < 0) return null
        var u = r.substring(i)
        if (u.startsWith("http%3A", true) || u.startsWith("https%3A", true)) u = dec(u)
        return u
    }
    /** 网页登录最后跳到 y.qq.com 时带的 code */
    fun codeOf(u: String): String? = if (u.startsWith(WEB_REDIRECT)) Regex("[?&]code=([^&#]+)").find(u)?.groupValues?.get(1) else null
    suspend fun loginWithQqCode(code: String) {
        val d = call("QQConnectLogin.LoginServer", "QQLogin", JSONObject().put("code", code), JSONObject().put("tmeLoginType", 2))
        saveCredential(d.put("loginType", 2))
        refreshProfile()
    }

    private fun stateOf(code: Int) = when (code) { 0, 405 -> QrState.DONE; 66, 408 -> QrState.WAIT; 67, 404 -> QrState.SCANNED; 65, 402 -> QrState.EXPIRED; 68, 403 -> QrState.REFUSED; else -> QrState.WAIT }

    private suspend fun checkQq(q: Qr, step: (String) -> Unit): QrState {
        val text = withContext(Dispatchers.IO) {
            val url = "https://ssl.ptlogin2.qq.com/ptqrlogin?" + query("u1" to "https://graph.qq.com/oauth2.0/login_jump", "ptqrtoken" to hash33(q.id), "ptredirect" to "0",
                "h" to "1", "t" to "1", "g" to "1", "from_ui" to "1", "ptlang" to "2052", "action" to "0-0-${System.currentTimeMillis()}", "js_ver" to "20102616", "js_type" to "1",
                "pt_uistyle" to "40", "aid" to "716027609", "daid" to "383", "pt_3rd_aid" to "100497308", "has_onekey" to "1")
            noRedirect.newCall(Request.Builder().url(url).header("Referer", "https://xui.ptlogin2.qq.com/").header("Cookie", "qrsig=${q.id}").header("User-Agent", UA).build()).execute().use { it.body?.string() ?: "" }
        }
        val inner = Regex("ptuiCB\\((.*?)\\)").find(text)?.groupValues?.get(1) ?: return QrState.WAIT
        val args = Regex("'((?:\\\\.|[^'])*)'").findAll(inner).map { it.groupValues[1] }.toList()
        val code = args.firstOrNull()?.toIntOrNull() ?: return QrState.WAIT
        val st = stateOf(code)
        if (st != QrState.DONE) return st
        val jump = args.getOrNull(2)?.takeIf { it.startsWith("http") } ?: throw NcmError("QQ 登录：没有拿到跳转地址（${args.getOrNull(4) ?: code}）")
        step("已确认，正在验证 QQ 身份…")
        authorizeQq(jump, step)
        return QrState.DONE
    }

    /** 跳转地址本身就是 check_sig，直接请求它拿 p_skey，避免手拆参数时顺序不同拆错 */
    private suspend fun authorizeQq(jump: String, step: (String) -> Unit) {
        val cookies = withContext(Dispatchers.IO) {
            noRedirect.newCall(Request.Builder().url(jump).header("Referer", "https://xui.ptlogin2.qq.com/").header("User-Agent", UA).build()).execute().use { cookiesOf(it.headers("Set-Cookie")) }
                .filterValues { it.isNotEmpty() }
        }
        val pSkey = cookies["p_skey"] ?: throw NcmError("QQ 登录：获取 p_skey 失败")
        val location = withContext(Dispatchers.IO) {
            val form = FormBody.Builder().add("response_type", "code").add("client_id", "100497308")
                .add("redirect_uri", "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/")
                .add("scope", "get_user_info,get_app_friends").add("state", "state").add("switch", "").add("from_ptlogin", "1").add("src", "1")
                .add("update_auth", "1").add("openapi", "1010_1030").add("g_tk", hash33(pSkey, 5381).toString()).add("auth_time", System.currentTimeMillis().toString())
                .add("ui", (1..32).map { "0123456789abcdef"[kotlin.random.Random.nextInt(16)] }.joinToString("")).build()
            val ck = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            noRedirect.newCall(Request.Builder().url("https://graph.qq.com/oauth2.0/authorize").post(form).header("Cookie", ck).header("Referer", "https://graph.qq.com/").header("User-Agent", UA).build()).execute().use { it.header("Location") ?: "" }
        }
        val code = Regex("[?&]code=([^&]+)").find(location)?.groupValues?.get(1) ?: throw NcmError("QQ 登录：获取 code 失败" + if (location.isEmpty()) "" else "（${location.take(80)}）")
        step("正在换取 QQ 音乐凭证…")
        loginWithQqCode(code)
    }

    private suspend fun checkWx(q: Qr, step: (String) -> Unit): QrState {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                Ncm.http.newBuilder().readTimeout(35, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url("https://lp.open.weixin.qq.com/connect/l/qrconnect?uuid=${q.id}&_=${System.currentTimeMillis()}").header("Referer", "https://open.weixin.qq.com/").header("User-Agent", UA).build())
                    .execute().use { it.body?.string() ?: "" }
            }.getOrDefault("")
        }
        val m = Regex("window\\.wx_errcode=(\\d+);window\\.wx_code='([^']*)'").find(text) ?: return QrState.WAIT
        val st = stateOf(m.groupValues[1].toInt())
        if (st != QrState.DONE) return st
        val code = m.groupValues[2].ifEmpty { throw NcmError("微信登录：获取 code 失败") }
        step("已确认，正在换取 QQ 音乐凭证…")
        val d = call("music.login.LoginServer", "Login", JSONObject().put("code", code).put("strAppid", "wx48db31d50e334801"), JSONObject().put("tmeLoginType", 1))
        saveCredential(d.put("loginType", 1))
        refreshProfile()
        return QrState.DONE
    }
}
