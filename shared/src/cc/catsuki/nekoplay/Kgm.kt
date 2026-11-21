package cc.catsuki.nekoplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 酷狗音乐接口（gateway.kugou.com），直接在设备上请求，不需要服务器。
 *
 * 签名算法、接口路径和参数参考 MakcRe/KuGouMusicApi，按 NEKOPLAY 的需要用 Kotlin 重写。原项目版权声明：
 *   MIT License, Copyright (c) 2023 MakcRe
 *
 * 歌曲 id 写成 "kg:<128k 文件 hash>:<album_audio_id>:<album_id>"（拿播放地址三样都要），
 * 专辑 "kg:al<album_id>"，歌手 "kg:ar<singer_id>"，歌单 "kg:<global_collection_id>"。
 */
object Kgm {
    const val P = "kg:"
    fun isKg(id: String?) = id?.startsWith(P) == true
    private fun raw(id: String) = id.removePrefix(P)

    private const val APPID = 1005
    private const val CLIENTVER = 20489
    private const val SRCAPPID = 2919
    private const val SALT_ANDROID = "OIlwieks28dk2k092lksi2UIkp"
    private const val SALT_WEB = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    private const val SALT_KEY = "57ae12eb6890223e355ccfcb74edf70d"
    private const val UA = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"

    private lateinit var prefs: KV
    fun init() { prefs = Plat.prefs("kugou") }

    val userid: Long get() = prefs.getLong("userid", 0)
    private val token: String get() = prefs.getString("token", "") ?: ""
    val loggedIn get() = userid > 0 && token.isNotEmpty()
    val nick: String get() = prefs.getString("nick", "") ?: ""
    fun logout() { prefs.edit().remove("userid").remove("token").remove("nick").remove("avatar").apply() }

    /** 设备标识：随机 GUID 存下来，mid = MD5(guid) 当作 16 进制大整数转成十进制 */
    private val guid: String by lazy {
        prefs.getString("guid", null) ?: md5(kotlin.random.Random.nextLong().toString() + System.currentTimeMillis()).also { prefs.edit().putString("guid", it).apply() }
    }
    private val mid: String by lazy { hexToDec(md5(guid)) }

    // ---------- 签名 ----------
    private fun valStr(v: Any?): String = when (v) { is JSONObject, is JSONArray -> v.toString(); else -> v.toString() }
    private fun signAndroid(p: Map<String, Any?>, body: String) = md5(SALT_ANDROID + p.keys.sorted().joinToString("") { "$it=${valStr(p[it])}" } + body + SALT_ANDROID)
    private fun signWeb(p: Map<String, Any?>, body: String = "") = md5(SALT_WEB + p.map { "${it.key}=${valStr(it.value)}" }.sorted().joinToString("") + body + SALT_WEB)

    private fun enc(s: String) = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff; val ch = c.toChar()
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-_.~") append(ch) else { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
        }
    }

    /**
     * 发请求：默认参数（dfid / mid / appid / clientver / clienttime，登录后加 token / userid）+ 签名。
     * web = 网页版签名（登录二维码用），否则安卓版签名。
     */
    private suspend fun req(path: String, params: Map<String, Any?>, base: String = "https://gateway.kugou.com", router: String? = null, body: JSONObject? = null,
                            web: Boolean = false, withKey: Boolean = false, dfid: String = "-", defaults: Boolean = true, headers: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val time = System.currentTimeMillis() / 1000
        val p = LinkedHashMap<String, Any?>()
        if (defaults) {
            p["dfid"] = dfid; p["mid"] = mid; p["uuid"] = "-"; p["appid"] = APPID; p["clientver"] = CLIENTVER; p["clienttime"] = time
            if (loggedIn) { p["token"] = token; p["userid"] = userid }
        }
        p.putAll(params)
        if (withKey) p["key"] = md5("${p["hash"]}$SALT_KEY${p["appid"]}${p["mid"]}${p["userid"] ?: 0}")
        val b = body?.toString() ?: ""
        p["signature"] = if (web) signWeb(p, b) else signAndroid(p, b)
        val url = base + path + "?" + p.entries.joinToString("&") { "${it.key}=${enc(valStr(it.value))}" }
        val rb = Request.Builder().url(url).header("User-Agent", UA).header("dfid", dfid).header("mid", mid).header("clienttime", (p["clienttime"] ?: time).toString())
            .header("kg-rc", "1").header("kg-thash", "5d816a0").header("kg-rec", "1").header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
        router?.let { rb.header("x-router", it) }
        headers.forEach { (k, v) -> rb.header(k, v) }
        if (body != null) rb.post(b.toRequestBody("application/json".toMediaType()))
        val t0 = System.currentTimeMillis()
        val text = try {
            Ncm.http.newCall(rb.build()).execute().use { r ->
                val t = r.body?.string() ?: ""
                Debug.log("kg:$path", r.code, System.currentTimeMillis() - t0, r.header("Content-Type") ?: "", t)
                if (!r.isSuccessful) throw NcmError("酷狗服务返回 ${r.code}")
                t
            }
        } catch (e: java.io.IOException) { throw NcmError("网络错误：${e.message}") }
        val j = try { JSONObject(text) } catch (e: Exception) { throw NcmError("酷狗返回的不是 JSON") }
        if (j.optInt("status", 1) == 0 || (j.has("error_code") && j.optInt("error_code") != 0)) {
            val code = j.optInt("error_code", j.optInt("errcode"))
            throw NcmError(when (code) { 20018, 20017, 51002 -> "酷狗登录已过期，请重新登录"; 20028 -> "酷狗要求安全验证（稍后再试或先登录）"; else -> "酷狗接口错误（$code）" + j.optString("error", j.optString("errmsg")).let { if (it.isNotEmpty()) "：$it" else "" } })
        }
        j
    }

    // ---------- 数据转换 ----------
    fun pic(u: String?, size: Int = 480): String? = u?.takeIf { it.isNotEmpty() }?.replace("{size}", size.toString())?.replace("http:", "https:")
    private fun arr(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun songId(hash: String, audioId: Any?, albumId: Any?) = "$P${hash.lowercase()}:${audioId ?: 0}:${albumId?.toString()?.ifEmpty { "0" } ?: "0"}"

    /** 搜索结果里的歌（字段是大写开头的那套） */
    private fun searchSong(x: JSONObject): Track {
        val singers = arr(x.optJSONArray("Singers"))
        val name = x.optString("SongName").ifEmpty { x.optString("OriSongName") }
        val alb = x.optString("AlbumID")
        return Track(songId(x.optString("FileHash"), x.opt("MixSongID"), alb), name, x.optString("SingerName").ifEmpty { "未知艺人" }, x.optString("AlbumName"),
            x.optLong("Duration") * 1000, pic(x.optString("Image")), singers.map { "${P}ar${it.opt("id")}" to it.optString("name") }, if (alb.isEmpty() || alb == "0") "" else "${P}al$alb")
    }

    /** 歌单 / 专辑里的歌（小写字段那套） */
    private fun listSong(x: JSONObject): Track {
        val base = x.optJSONObject("base")
        if (base != null) {
            val ai = x.optJSONObject("audio_info") ?: JSONObject()
            val al = x.optJSONObject("album_info") ?: JSONObject()
            val alb = base.opt("album_id")?.toString() ?: ""
            return Track(songId(ai.optString("hash_128").ifEmpty { ai.optString("hash") }, base.opt("album_audio_id"), alb), base.optString("audio_name"), base.optString("author_name").ifEmpty { "未知艺人" },
                al.optString("album_name"), ai.optLong("duration_128", ai.optLong("duration")), pic(al.optString("cover")),
                arr(x.optJSONArray("authors")).map { "${P}ar${it.opt("author_id")}" to it.optString("author_name") }, if (alb.isEmpty() || alb == "0") "" else "${P}al$alb")
        }
        val singers = arr(x.optJSONArray("singerinfo"))
        val full = x.optString("name")
        val title = if (" - " in full && singers.isNotEmpty()) full.substringAfter(" - ") else full
        val alb = x.opt("album_id")?.toString() ?: ""
        return Track(songId(x.optString("hash"), x.opt("mixsongid") ?: x.opt("add_mixsongid"), alb), title,
            singers.joinToString(" / ") { it.optString("name") }.ifEmpty { full.substringBefore(" - ", "未知艺人") },
            x.optJSONObject("albuminfo")?.optString("name") ?: "", x.optLong("timelen"), pic(x.optString("cover")),
            singers.map { "${P}ar${it.opt("id")}" to it.optString("name") }, if (alb.isEmpty() || alb == "0") "" else "${P}al$alb")
    }

    // ---------- 搜索 ----------
    private val searchHdr = "complexsearch.kugou.com"
    suspend fun search(q: String, page: Int = 1): List<Track> {
        val j = req("/v2/search/song", mapOf("keyword" to q, "page" to page, "pagesize" to 30, "platform" to "AndroidFilter", "iscorrection" to 1, "privilegefilter" to 0, "area_code" to 1, "dopicfull" to 1), router = searchHdr)
        return arr(j.optJSONObject("data")?.optJSONArray("lists")).map { searchSong(it) }
    }

    suspend fun searchPlaylists(q: String): List<Playlist> {
        val j = req("/v1/search/special", mapOf("albumhide" to 0, "iscorrection" to 1, "keyword" to q, "nocollect" to 0, "page" to 1, "pagesize" to 30, "platform" to "AndroidFilter"), router = searchHdr)
        return arr(j.optJSONObject("data")?.optJSONArray("lists")).map { Playlist(P + it.optString("gid"), it.optString("specialname"), it.optInt("song_count"), pic(it.optString("img")), mine = false) }
    }

    suspend fun searchAlbums(q: String): List<Album> {
        val j = req("/v1/search/album", mapOf("keyword" to q, "page" to 1, "pagesize" to 30, "platform" to "AndroidFilter", "iscorrection" to 1, "category" to "1", "sorttype" to 0, "searchsong" to 0), router = searchHdr)
        return arr(j.optJSONObject("data")?.optJSONArray("lists")).map {
            Album("${P}al${it.opt("albumid")}", it.optString("albumname"), pic(it.optString("img")), it.optInt("songcount"), it.optString("publish_time").take(4), it.optString("singer"))
        }
    }

    // ---------- 播放地址 ----------
    private val QUALITIES = listOf("128" to "standard", "128" to "higher", "320" to "exhigh", "flac" to "lossless", "high" to "hires")
    private val urlMemo = HashMap<String, Pair<Stream, Long>>()

    suspend fun stream(id: String, quality: Int): Stream {
        val parts = raw(id).split(':')
        val hash = parts[0]; val audioId = parts.getOrNull(1)?.toLongOrNull() ?: 0; val albumId = parts.getOrNull(2)?.toLongOrNull() ?: 0
        val key = "${hash}_$quality"
        urlMemo[key]?.let { (s, exp) -> if (System.currentTimeMillis() < exp) return s }
        var err = "没有可用的播放地址（无版权或需要酷狗会员）"
        var last = ""
        for (i in quality.coerceIn(0, QUALITIES.lastIndex) downTo 0) {
            val (q, level) = QUALITIES[i]
            if (q == last) continue
            last = q
            try {
                val j = req("/v5/url", mapOf("album_id" to albumId, "area_code" to 1, "hash" to hash, "ssa_flag" to "is_fromtrack", "version" to 11430, "page_id" to 151369488,
                    "quality" to q, "album_audio_id" to audioId, "behavior" to "play", "pid" to 2, "cmd" to 26, "pidversion" to 3001, "IsFreePart" to 0,
                    "ppage_id" to "463467626,350369493,788954147", "cdnBackup" to 1, "module" to "", "clientver" to 11430),
                    router = "trackercdn.kugou.com", withKey = true, dfid = randomStr(24))
                val u = j.optJSONArray("url")?.optString(0)?.ifEmpty { null } ?: j.optJSONArray("backupUrl")?.optString(0)?.ifEmpty { null }
                if (u == null) { err = if (j.optInt("errcode") == 20028) "酷狗要求安全验证，先登录酷狗或换个网络再试" else if (j.optInt("status") == 2) "这首歌需要酷狗会员或购买" else err; continue }
                val br = j.optInt("bitRate"); val ext = j.optString("extName").ifEmpty { if (q == "flac" || q == "high") "flac" else "mp3" }
                val s = Stream(u.replace("http:", "https:"), ext, if (br > 10_000) br / 1000 else br, level)
                urlMemo[key] = s to System.currentTimeMillis() + 10 * 60_000
                return s
            } catch (e: NcmError) { err = e.message ?: err }
        }
        throw NcmError(err)
    }

    private fun randomStr(n: Int) = (1..n).map { "abcdefghijklmnopqrstuvwxyz0123456789"[kotlin.random.Random.nextInt(36)] }.joinToString("")

    // ---------- 歌词：按 hash 找候选，再下载 LRC ----------
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun lyric(t: Track): LyricData {
        val parts = raw(t.id).split(':')
        val s = req("/v1/search", mapOf("album_audio_id" to (parts.getOrNull(1) ?: "0"), "appid" to APPID, "clientver" to CLIENTVER, "duration" to t.durationMs, "hash" to parts[0],
            "keyword" to "", "lrctxt" to 1, "man" to "no"), base = "https://lyrics.kugou.com", defaults = false)
        val c = arr(s.optJSONArray("candidates")).firstOrNull() ?: throw NcmError("暂无歌词")
        val d = req("/download", mapOf("ver" to 1, "client" to "android", "id" to c.opt("id"), "accesskey" to c.optString("accesskey"), "fmt" to "lrc", "charset" to "utf8"), base = "https://lyrics.kugou.com")
        val lrc = runCatching { Base64.decode(d.optString("content")).decodeToString() }.getOrDefault("")
        if (lrc.isBlank()) throw NcmError("暂无歌词")
        return LyricData(lrc, null, null)
    }
    /** 只有 id 时（歌词缓存按 id 取）：时长不知道也能搜 */
    suspend fun lyric(id: String): LyricData = lyric(Track(id, "", "", "", 0, null))

    // ---------- 歌单 / 专辑 / 歌手 ----------
    suspend fun playlistTracks(id: String): List<Track> {
        val gid = raw(id)
        val out = ArrayList<Track>()
        var page = 1
        while (out.size < 3000) {
            val j = req("/pubsongs/v2/get_other_list_file_nofilt", mapOf("area_code" to 1, "begin_idx" to (page - 1) * 300, "plat" to 1, "type" to 1, "mode" to 1,
                "personal_switch" to 1, "extend_fields" to "abtags,hot_cmt,popularization", "pagesize" to 300, "global_collection_id" to gid))
            val d = j.optJSONObject("data") ?: break
            val l = arr(d.optJSONArray("songs")).map { listSong(it) }
            out += l
            if (l.isEmpty() || out.size >= d.optInt("count", out.size)) break
            page++
        }
        return out
    }

    suspend fun album(id: String): Pair<Album, List<Track>> {
        val aid = raw(id).removePrefix("al")
        val out = ArrayList<Track>()
        var page = 1
        while (out.size < 1000) {
            val j = req("/v1/album_audio/lite", emptyMap(), router = "openapi.kugou.com", body = JSONObject().put("album_id", aid).put("is_buy", "").put("page", page).put("pagesize", 30), headers = mapOf("kg-tid" to "255"))
            val d = j.optJSONObject("data") ?: break
            val l = arr(d.optJSONArray("songs")).map { listSong(it) }
            out += l
            if (l.isEmpty() || out.size >= d.optInt("total", j.optInt("total", out.size))) break
            page++
        }
        val first = out.firstOrNull()
        return Album(id, first?.album ?: "", first?.cover, out.size, "", first?.artist ?: "") to out
    }

    suspend fun artist(id: String): Artist {
        val aid = raw(id).removePrefix("ar")
        val time = System.currentTimeMillis() / 1000
        val body = JSONObject().put("appid", APPID).put("clientver", CLIENTVER).put("mid", mid).put("clienttime", time)
            .put("key", md5("$APPID$SALT_ANDROID$CLIENTVER$time")).put("author_id", aid).put("pagesize", 50).put("page", 1).put("sort", 1).put("area_code", "all")
        val j = req("/kmr/v1/audio_group/author", emptyMap(), base = "https://openapi.kugou.com", router = "openapi.kugou.com", body = body, headers = mapOf("kg-tid" to "220"))
        val songs = arr(j.optJSONArray("data")).map { listSong(it) }
        val name = songs.firstOrNull()?.artists?.firstOrNull { it.first == id }?.second ?: songs.firstOrNull()?.artist ?: ""
        return Artist(id, name, songs.firstOrNull()?.cover, "", j.optInt("total", songs.size), 0, songs)
    }

    /** 自己的歌单（含「我喜欢」） */
    suspend fun playlists(): List<Playlist> {
        if (!loggedIn) throw NcmError("请先登录酷狗")
        val j = req("/v7/get_all_list", mapOf("plat" to 1), router = "cloudlist.service.kugou.com",
            body = JSONObject().put("userid", userid).put("token", token).put("total_ver", 979).put("type", 2).put("page", 1).put("pagesize", 100))
        return arr(j.optJSONObject("data")?.optJSONArray("info")).map { p ->
            val liked = p.optString("name") == "我喜欢" || p.optInt("type") == 1
            Playlist(P + p.optString("global_collection_id"), p.optString("name"), p.optInt("count"), pic(p.optString("pic")), liked = liked,
                mine = p.optLong("list_create_userid", userid) == userid)
        }.sortedByDescending { it.liked }
    }

    // ---------- 扫码登录 ----------
    /** 返回 (二维码里的地址, 查询用的 key) */
    suspend fun qrKey(): Pair<String, String> {
        val j = req("/v2/qrcode", mapOf("appid" to 1001, "type" to 1, "plat" to 4, "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=$APPID&", "srcappid" to SRCAPPID),
            base = "https://login-user.kugou.com", web = true)
        val key = j.optJSONObject("data")?.optString("qrcode")?.ifEmpty { null } ?: throw NcmError("获取酷狗二维码失败")
        return "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=$key" to key
    }

    /** 0 过期 / 1 等待 / 2 已扫码 / 4 成功（成功时已保存登录态） */
    suspend fun qrCheck(key: String): Int {
        val j = req("/v2/get_userinfo_qrcode", mapOf("plat" to 4, "appid" to APPID, "srcappid" to SRCAPPID, "qrcode" to key), base = "https://login-user.kugou.com", web = true)
        val d = j.optJSONObject("data") ?: return 1
        val st = d.optInt("status", 1)
        if (st == 4) {
            prefs.edit().putLong("userid", d.optLong("userid")).putString("token", d.optString("token")).putString("nick", d.optString("nickname"))
                .putString("avatar", pic(d.optString("pic")) ?: "").apply()
        }
        return st
    }

    // ---------- MD5（各平台通用的纯 Kotlin 实现） ----------
    private val S = intArrayOf(7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21)
    private val K = IntArray(64) { (kotlin.math.abs(kotlin.math.sin((it + 1).toDouble())) * 4294967296.0).toLong().toInt() }

    fun md5(s: String): String = md5(s.encodeToByteArray())
    fun md5(input: ByteArray): String {
        val bitLen = input.size.toLong() * 8
        val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
        val msg = ByteArray(input.size + 1 + padLen + 8)
        input.copyInto(msg); msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) msg[msg.size - 8 + i] = (bitLen ushr (8 * i)).toByte()
        var a0 = 0x67452301; var b0 = 0xefcdab89.toInt(); var c0 = 0x98badcfe.toInt(); var d0 = 0x10325476
        val m = IntArray(16)
        for (off in msg.indices step 64) {
            for (i in 0 until 16) m[i] = (msg[off + i * 4].toInt() and 0xff) or ((msg[off + i * 4 + 1].toInt() and 0xff) shl 8) or ((msg[off + i * 4 + 2].toInt() and 0xff) shl 16) or ((msg[off + i * 4 + 3].toInt() and 0xff) shl 24)
            var a = a0; var b = b0; var c = c0; var d = d0
            for (i in 0 until 64) {
                var f: Int; val g: Int
                when (i / 16) {
                    0 -> { f = (b and c) or (b.inv() and d); g = i }
                    1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                f += a + K[i] + m[g]
                a = d; d = c; c = b
                b += (f shl S[i]) or (f ushr (32 - S[i]))
            }
            a0 += a; b0 += b; c0 += c; d0 += d
        }
        return buildString { for (v in intArrayOf(a0, b0, c0, d0)) for (i in 0 until 4) { val x = (v ushr (8 * i)) and 0xff; append("0123456789abcdef"[x shr 4]); append("0123456789abcdef"[x and 15]) } }
    }

    /** 16 进制字符串 → 十进制字符串（任意长度） */
    private fun hexToDec(hex: String): String {
        var digits = intArrayOf(0) // 十进制，低位在前
        for (ch in hex) {
            var carry = ch.digitToInt(16)
            val out = IntArray(digits.size + 2)
            for (i in digits.indices) { val v = digits[i] * 16 + carry; out[i] = v % 10; carry = v / 10 }
            var k = digits.size
            while (carry > 0) { out[k++] = carry % 10; carry /= 10 }
            digits = out.copyOf(maxOf(k, digits.size))
        }
        return digits.reversed().joinToString("").trimStart('0').ifEmpty { "0" }
    }
}
