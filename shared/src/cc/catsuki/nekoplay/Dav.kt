package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * WebDAV 音乐：直接浏览网盘里的文件夹，点歌就播。
 * 曲目的 src 存成「dav:/路径」，不带账号密码；播放时才拼出带认证的地址，备份和最近播放里不会留下密码。
 */
object DavMusic {
    private val kv by lazy { Plat.prefs("davmusic") }
    var url by mutableStateOf(""); private set
    var user by mutableStateOf(""); private set
    var pass by mutableStateOf(""); private set
    val ready get() = url.startsWith("http")
    private val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "wma", "alac", "aiff", "dsf")

    fun load() { url = kv.getString("url", "") ?: ""; user = kv.getString("user", "") ?: ""; pass = kv.getString("pass", "") ?: "" }
    fun save(u: String, n: String, p: String) { url = u.trim().trimEnd('/'); user = n.trim(); pass = p; kv.edit().putString("url", url).putString("user", user).putString("pass", pass).apply() }

    /** https://host:port（不含路径） */
    private val origin get() = Regex("^(https?://[^/]+)").find(url)?.groupValues?.get(1) ?: ""
    /** 配置里的起始路径（已编码） */
    val root get() = url.removePrefix(origin).ifEmpty { "/" }.let { if (it.endsWith("/")) it else "$it/" }

    class Entry(val name: String, val path: String, val dir: Boolean, val size: Long)

    private fun req(path: String) = Request.Builder().url(origin + path).apply { if (user.isNotEmpty()) header("Authorization", Credentials.basic(user, pass, Charsets.UTF_8)) }

    /** 列出一个文件夹：子文件夹在前，只留音乐文件 */
    suspend fun list(path: String): List<Entry> = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
        val text = SourceApi.http.newCall(req(path).header("Depth", "1").method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build()).execute().use { r ->
            if (r.code == 401) throw SourceError("账号或密码不对")
            if (!r.isSuccessful) throw SourceError("WebDAV 出错：HTTP ${r.code}")
            r.body?.string() ?: ""
        }
        val self = path.trimEnd('/')
        Regex("<(?:[A-Za-z0-9]+:)?response[\\s>].*?</(?:[A-Za-z0-9]+:)?response>", RegexOption.DOT_MATCHES_ALL).findAll(text).mapNotNull { m ->
            val r = m.value
            var href = Regex("<(?:[A-Za-z0-9]+:)?href>(.*?)</", RegexOption.DOT_MATCHES_ALL).find(r)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            href = href.replace("&amp;", "&")
            if (href.startsWith("http")) href = href.removePrefix(Regex("^https?://[^/]+").find(href)?.value ?: "")
            if (href.trimEnd('/') == self) return@mapNotNull null
            val dir = Regex("<(?:[A-Za-z0-9]+:)?collection\\s*/?>").containsMatchIn(r)
            val name = dec(href.trimEnd('/').substringAfterLast('/'))
            val size = Regex("<(?:[A-Za-z0-9]+:)?getcontentlength>(\\d+)<").find(r)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            if (!dir && name.substringAfterLast('.', "").lowercase() !in AUDIO) null else Entry(name, href, dir, size)
        }.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() })).toList()
    }

    /** 文件名「歌手 - 歌名.flac」拆出歌手和歌名 */
    fun trackOf(e: Entry): Track {
        val base = e.name.substringBeforeLast('.')
        val (artist, title) = base.split(" - ", limit = 2).let { if (it.size == 2) it[0].trim() to it[1].trim() else "WebDAV" to base }
        return Track("dav:" + e.path, title, artist, dec(e.path.trimEnd('/').substringBeforeLast('/').substringAfterLast('/')), 0, null, emptyList(), "", "dav:" + e.path)
    }

    /** 播放用的地址：账号密码放进地址里，各平台的播放内核按 Basic 认证处理 */
    fun playUrl(src: String): String {
        val path = src.removePrefix("dav:")
        if (user.isEmpty()) return origin + path
        val o = origin; val i = o.indexOf("://") + 3
        return o.substring(0, i) + enc(user) + ":" + enc(pass) + "@" + o.substring(i) + path
    }

    /** 同名 .lrc 歌词 */
    suspend fun lyric(src: String): String? = withContext(Dispatchers.IO) {
        val path = src.removePrefix("dav:").substringBeforeLast('.') + ".lrc"
        runCatching { SourceApi.http.newCall(req(path).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } }.getOrNull()
    }

    /** 百分号解码（不把 + 当空格），iOS 也能用 */
    fun dec(s: String): String {
        val out = ArrayList<Byte>(s.length); val b = s.encodeToByteArray(); var i = 0
        while (i < b.size) {
            if (b[i] == '%'.code.toByte() && i + 2 < b.size) { val h = "${b[i + 1].toInt().toChar()}${b[i + 2].toInt().toChar()}".toIntOrNull(16); if (h != null) { out.add(h.toByte()); i += 3; continue } }
            out.add(b[i]); i++
        }
        return out.toByteArray().decodeToString()
    }
    private fun enc(s: String) = buildString { s.encodeToByteArray().forEach { b -> val ch = b.toInt() and 0xff; if (ch.toChar().isLetterOrDigit() && ch < 128 || ch.toChar() in "-._~") append(ch.toChar()) else append('%').append("0123456789ABCDEF"[ch shr 4]).append("0123456789ABCDEF"[ch and 15]) } }
}
