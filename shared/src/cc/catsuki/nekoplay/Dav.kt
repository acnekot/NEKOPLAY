package cc.catsuki.nekoplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        val text = Ncm.http.newCall(req(path).header("Depth", "1").method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build()).execute().use { r ->
            if (r.code == 401) throw NcmError("账号或密码不对")
            if (!r.isSuccessful) throw NcmError("WebDAV 出错：HTTP ${r.code}")
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
        runCatching { Ncm.http.newCall(req(path).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } }.getOrNull()
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

/** 曲库 → WebDAV 音乐：没配置时先填地址；配好后逐级浏览文件夹 */
@Composable fun DavMusicPage() {
    val nav = LocalNav.current
    val p = c
    val st by Engine.state.collectAsState()
    var editing by remember { mutableStateOf(!DavMusic.ready) }
    var path by remember { mutableStateOf(DavMusic.root) }
    var list by remember { mutableStateOf<List<DavMusic.Entry>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path, editing) {
        if (editing) return@LaunchedEffect
        list = null; err = null
        try { list = DavMusic.list(path) } catch (e: Exception) { err = e.message; list = emptyList() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("WEBDAV"); Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    T("WebDAV 音乐", Fs.HERO, w = 800, lh = 1.3f, modifier = Modifier.weight(1f))
                    if (!editing) LinkBtn("设置", 14) { editing = true }
                }
                T("直接播放网盘里的歌，文件名「歌手 - 歌名」会自动拆开，同名 .lrc 当作歌词", 13, p.s, lines = 2, lh = 1.4f)
                Spacer(Modifier.height(12.dp)); Rule()
            }
        }
        if (editing) item {
            var u by remember { mutableStateOf(DavMusic.url) }
            var n by remember { mutableStateOf(DavMusic.user) }
            var pw by remember { mutableStateOf(DavMusic.pass) }
            @Composable fun Field(label: String, v: String, hint: String, secret: Boolean = false, set: (String) -> Unit) {
                Spacer(Modifier.height(14.dp)); T(label, 13, p.s); Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(44.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (v.isEmpty()) T(hint, 13, p.s, mono = true)
                    BasicTextField(v, set, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = Fs.SUB.sp, fontFamily = Mono), cursorBrush = SolidColor(p.p),
                        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
                }
            }
            Column {
                Field("地址（音乐所在的文件夹）", u, "https://pan.example.com/dav/音乐") { u = it }
                Field("用户名", n, "可以留空") { n = it }
                Field("密码", pw, "可以留空", secret = true) { pw = it }
                Spacer(Modifier.height(16.dp))
                PrimaryBar("保存并打开", NIcons.chevronRight, Modifier.fillMaxWidth()) {
                    if (!u.trim().startsWith("http")) { nav.toast = "地址要以 http:// 或 https:// 开头"; return@PrimaryBar }
                    DavMusic.save(u, n, pw); path = DavMusic.root; editing = false
                }
            }
        }
        if (!editing) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(DavMusic.dec(path), 12, p.s, mono = true, lines = 2, modifier = Modifier.weight(1f))
                    if (path != DavMusic.root) LinkBtn("上一级", 13) { path = path.trimEnd('/').substringBeforeLast('/') + "/" }
                }
            }
            val l = list
            when {
                l == null -> item { T("LOADING", 12, p.s, mono = true) }
                err != null -> item { T("打不开：$err", 13, p.s, lines = 3) }
                l.isEmpty() -> item { T("这个文件夹里没有音乐", 13, p.s) }
                else -> {
                    val songs = l.filter { !it.dir }.map { DavMusic.trackOf(it) }
                    if (songs.isNotEmpty()) item {
                        ActionRow { PrimaryBar("播放这个文件夹", NIcons.play, Modifier.weight(1f)) { Engine.playList(songs, 0) } }
                        Spacer(Modifier.height(8.dp))
                    }
                    items(l.filter { it.dir }, key = { "d" + it.path }) { e -> ListRow({ IconCover(NIcons.disc, 56.dp) }, e.name, "文件夹") { path = e.path } }
                    items(songs.indices.toList(), key = { songs[it].id }) { i ->
                        val t = songs[i]
                        TrackRow(t, st.current?.id == t.id, t.src?.substringAfterLast('.')?.uppercase()?.take(4)) { Engine.playList(songs, i) }
                    }
                }
            }
        }
    }
}
