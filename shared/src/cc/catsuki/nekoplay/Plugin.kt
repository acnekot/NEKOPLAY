package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 音源脚本（JS 插件）运行时。
 *
 * 本体不带任何在线音源；音源是用户导入的 .js 脚本，在内置的 QuickJS 里运行。
 * 脚本和本体之间只传 JSON 字符串，接口说明见 docs/source-api.md。
 */
class JsSource(val file: File) {
    /** 脚本开头注释里的信息：// @id qq  // @name QQ 音乐  // @version 1.0.0  // @author xx  // @desc xx */
    val meta: Map<String, String> = Regex("""^\s*//\s*@(\w+)\s+(.+)$""", RegexOption.MULTILINE).findAll(file.readText().take(4000))
        .associate { it.groupValues[1] to it.groupValues[2].trim() }
    val id: String get() = meta["id"] ?: file.nameWithoutExtension
    val name: String get() = meta["name"] ?: id
    val version: String get() = meta["version"] ?: "0"

    private var js: QuickJs? = null
    private val lock = Mutex()
    /** 脚本实现了哪些接口（search / stream / lyric …），界面按这个决定显示什么 */
    var caps by mutableStateOf<Set<String>>(emptySet()); private set
    var error by mutableStateOf<String?>(null); private set

    fun has(cap: String) = cap in caps

    suspend fun start(): Boolean = lock.withLock {
        if (js != null) return true
        try {
            val q = QuickJs.create(Dispatchers.Default)
            installHost(q)
            q.evaluate<Any?>(PRELUDE, filename = "prelude.js")
            q.evaluate<Any?>(file.readText(), filename = file.name)
            caps = JSONArray(q.evaluate<String>("__caps()")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            js = q; error = null
            true
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
            Debug.log("js:$id", 0, 0, "start", error ?: "")
            false
        }
    }

    fun close() { js?.close(); js = null }

    /** 调用脚本里 source 对象的方法：参数和返回值都是 JSON */
    suspend fun call(fn: String, vararg args: Any?): Any? {
        if (js == null && !start()) throw NcmError("音源「$name」加载失败：$error")
        val argJson = JSONArray().apply { args.forEach { put(it ?: JSONObject.NULL) } }.toString()
        val t0 = System.currentTimeMillis()
        val out = try {
            lock.withLock { js!!.evaluate<String?>("await __invoke(${q(fn)}, ${q(argJson)})", filename = "call.js") }
        } catch (e: Throwable) {
            val msg = (e.message ?: e.toString()).substringBefore("\n")
            Debug.log("js:$id.$fn", 0, System.currentTimeMillis() - t0, "error", msg)
            throw NcmError(msg)
        }
        Debug.log("js:$id.$fn", 200, System.currentTimeMillis() - t0, "application/json", out ?: "null")
        if (out == null) return null
        return when (out.firstOrNull()) { '{' -> JSONObject(out); '[' -> JSONArray(out); else -> JSONArray("[$out]").opt(0) }
    }

    // ---------------- 给脚本用的宿主接口 ----------------
    private val store by lazy { Plat.prefs("plugin_$id") }

    @OptIn(ExperimentalEncodingApi::class)
    private fun installHost(q: QuickJs) {
        q.define("__host") {
            asyncFunction("http") { args -> http(JSONObject(args[0] as String)).toString() }
            function("md5") { args -> Kgm.md5((args[0] as String).encodeToByteArray()) }
            function("md5b64") { args -> Kgm.md5(Base64.decode(args[0] as String)) }
            function("aes") { args -> aes(JSONObject(args[0] as String)) }
            function("b64enc") { args -> Base64.encode((args[0] as String).encodeToByteArray()) }
            function("b64dec") { args -> Base64.decode(args[0] as String).decodeToString() }
            function("get") { args -> store.getString(args[0] as String, null) }
            function("set") { args -> store.edit().apply { val v = args.getOrNull(1); if (v == null) remove(args[0] as String) else putString(args[0] as String, v.toString()) }.apply(); null }
            function("log") { args -> Debug.log("js:$id", 0, 0, "log", args.joinToString(" ")); null }
        }
    }

    /** {url, method, headers{}, body(字符串), form{}, json(对象), redirect:false, binary:true} → {status, url, headers{小写名:[值]}, body | bodyBase64} */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun http(o: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(o.getString("url"))
        o.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> b.header(k, h.optString(k)) } }
        if (o.optJSONObject("headers")?.has("User-Agent") != true && o.optJSONObject("headers")?.has("user-agent") != true) b.header("User-Agent", UA)
        val method = o.optString("method", "").uppercase().ifEmpty { if (o.has("body") || o.has("form") || o.has("json")) "POST" else "GET" }
        val body = when {
            o.has("form") -> FormBody.Builder().apply { val f = o.getJSONObject("form"); f.keys().forEach { k -> add(k, f.optString(k)) } }.build()
            o.has("json") -> o.get("json").toString().toRequestBody("application/json".toMediaType())
            o.has("body") -> o.getString("body").toRequestBody((o.optJSONObject("headers")?.optString("Content-Type")?.ifEmpty { null } ?: "text/plain").toMediaType())
            else -> null
        }
        b.method(method, body)
        val client = if (o.optBoolean("redirect", true)) Ncm.http else noRedirect
        try {
            client.newCall(b.build()).execute().use { r ->
                val hs = JSONObject()
                r.headers.names().forEach { n -> hs.put(n.lowercase(), JSONArray(r.headers(n))) }
                val res = JSONObject().put("status", r.code).put("url", r.request.url.toString()).put("headers", hs)
                if (o.optBoolean("binary")) res.put("bodyBase64", Base64.encode(r.body?.bytes() ?: ByteArray(0))) else res.put("body", r.body?.string() ?: "")
                res
            }
        } catch (e: java.io.IOException) { throw NcmError("网络错误：${e.message}") }
    }

    /** {data(文本) | dataBase64, key, iv?, mode: cbc|ecb, padding: true, decrypt: false} → base64 */
    @OptIn(ExperimentalEncodingApi::class)
    private fun aes(o: JSONObject): String {
        val data = if (o.has("dataBase64")) Base64.decode(o.getString("dataBase64")) else o.getString("data").encodeToByteArray()
        val ecb = o.optString("mode", "cbc").lowercase() == "ecb"
        val c = Cipher.getInstance("AES/${if (ecb) "ECB" else "CBC"}/${if (o.optBoolean("padding", true)) "PKCS5Padding" else "NoPadding"}")
        val key = SecretKeySpec(o.getString("key").encodeToByteArray(), "AES")
        val mode = if (o.optBoolean("decrypt")) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE
        if (ecb) c.init(mode, key) else c.init(mode, key, IvParameterSpec(o.optString("iv", "0000000000000000").encodeToByteArray()))
        return Base64.encode(c.doFinal(data))
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
        private val noRedirect: OkHttpClient by lazy { Ncm.http.newBuilder().followRedirects(false).followSslRedirects(false).build() }
        private fun q(s: String) = JSONObject.quote(s)

        /** 脚本运行前先注入的工具：nk.http / nk.md5 / nk.aes / nk.store / nk.log，以及注册音源用的 nk.register */
        val PRELUDE = """
            globalThis.nk = {
              http: async (o) => { const r = JSON.parse(await __host.http(JSON.stringify(typeof o === 'string' ? { url: o } : o)));
                r.header = (n) => (r.headers[n.toLowerCase()] || [])[0];
                r.cookies = () => { const m = {}; (r.headers['set-cookie'] || []).forEach(c => { const kv = c.split(';')[0]; const i = kv.indexOf('='); if (i > 0) m[kv.slice(0, i).trim()] = kv.slice(i + 1).trim(); }); return m; };
                r.json = () => JSON.parse(r.body); return r; },
              md5: (s) => __host.md5(String(s)),
              md5Base64: (b64) => __host.md5b64(b64),
              aes: (o) => __host.aes(JSON.stringify(o)),
              base64: { encode: (s) => __host.b64enc(String(s)), decode: (s) => __host.b64dec(String(s)) },
              store: { get: (k) => { const v = __host.get(String(k)); return v == null ? null : JSON.parse(v); }, set: (k, v) => __host.set(String(k), v === undefined || v === null ? null : JSON.stringify(v)) },
              log: (...a) => __host.log(a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ')),
              register: (s) => { globalThis.source = s; },
            };
            globalThis.console = { log: nk.log, warn: nk.log, error: nk.log };
            globalThis.__caps = () => JSON.stringify(Object.keys(globalThis.source || {}).filter(k => typeof globalThis.source[k] === 'function'));
            globalThis.__invoke = async (fn, args) => {
              const s = globalThis.source;
              if (!s || typeof s[fn] !== 'function') throw new Error('音源不支持：' + fn);
              const r = await s[fn](...JSON.parse(args));
              return r === undefined ? null : JSON.stringify(r);
            };
        """.trimIndent()
    }
}

/**
 * 已导入的插件：脚本文件放在 filesDir/plugins/<id>.js，开关存在 prefs "plugins"。
 * 插件给出的歌 / 歌单 / 专辑 / 歌手 id 在本体里统一加前缀「@插件id:」，调回插件时再去掉。
 */
object Plugins {
    private val dir by lazy { File(Plat.impl.filesDir, "plugins").apply { mkdirs() } }
    private val prefs by lazy { Plat.prefs("plugins") }
    val list = kotlinx.coroutines.flow.MutableStateFlow<List<JsSource>>(emptyList())
    var off by mutableStateOf(setOf<String>())

    fun load() {
        off = prefs.getStringSet("off", null) ?: emptySet()
        list.value = (dir.listFiles() ?: emptyArray()).filter { it.extension == "js" }.sortedBy { it.name }.map { JsSource(it) }
    }
    fun on(id: String) = id !in off && list.value.any { it.id == id }
    fun get(id: String) = list.value.firstOrNull { it.id == id }
    val enabled get() = list.value.filter { it.id !in off }
    fun set(id: String, v: Boolean) { off = if (v) off - id else off + id; prefs.edit().putStringSet("off", off).apply(); if (!v) get(id)?.close() }

    /** 导入脚本：检查开头的 @id，同 id 覆盖旧版本；返回导入的插件 */
    suspend fun import(text: String): JsSource = withContext(Dispatchers.IO) {
        val id = Regex("""^\s*//\s*@id\s+([A-Za-z0-9_\-]+)\s*$""", RegexOption.MULTILINE).find(text.take(4000))?.groupValues?.get(1)
            ?: throw NcmError("不是 NEKOPLAY 插件：脚本开头要有「// @id 名字」")
        get(id)?.close()
        val f = File(dir, "$id.js"); f.writeText(text)
        val s = JsSource(f)
        if (!s.start()) { f.delete(); throw NcmError("脚本运行出错：${s.error}") }
        list.value = list.value.filter { it.id != id } + s
        off = off - id; prefs.edit().putStringSet("off", off).apply()
        s
    }
    suspend fun importUrl(url: String): JsSource {
        val text = withContext(Dispatchers.IO) { Ncm.http.newCall(Request.Builder().url(url.trim()).build()).execute().use { r -> if (!r.isSuccessful) throw NcmError("下载失败：HTTP ${r.code}"); r.body?.string() ?: "" } }
        return import(text)
    }
    fun delete(id: String) { get(id)?.close(); File(dir, "$id.js").delete(); list.value = list.value.filter { it.id != id } }

    // ---------- 本体调用插件 ----------
    fun isPlugin(id: String?) = id?.startsWith("@") == true
    fun pidOf(id: String) = id.removePrefix("@").substringBefore(':')
    fun raw(id: String) = id.substringAfter(':')
    private fun wrap(pid: String, raw: Any?): String = raw?.toString()?.takeIf { it.isNotEmpty() && it != "null" }?.let { "@$pid:$it" } ?: ""
    private fun src(id: String): JsSource = get(pidOf(id))?.takeIf { on(it.id) } ?: throw NcmError("插件「${pidOf(id)}」没有安装或已关闭")

    fun track(pid: String, o: JSONObject) = Track(wrap(pid, o.opt("id")), o.optString("title"), o.optString("artist").ifEmpty { "未知艺人" }, o.optString("album"),
        o.optLong("duration"), o.optString("cover").ifEmpty { null },
        o.optJSONArray("artists")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { wrap(pid, it.opt("id")) to it.optString("name") } } ?: emptyList(),
        wrap(pid, o.opt("albumId")), vip = o.optBoolean("vip"))
    fun playlist(pid: String, o: JSONObject) = Playlist(wrap(pid, o.opt("id")), o.optString("name"), o.optInt("count"), o.optString("cover").ifEmpty { null },
        liked = o.optBoolean("liked"), mine = o.optBoolean("mine", false), desc = o.optString("desc"))
    fun album(pid: String, o: JSONObject) = Album(wrap(pid, o.opt("id")), o.optString("name"), o.optString("cover").ifEmpty { null }, o.optInt("count"), o.optString("year"), o.optString("artist"))
    private fun arr(v: Any?): List<JSONObject> = when (v) { is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }; else -> emptyList() }
    private fun tracksOf(pid: String, v: Any?) = arr(if (v is JSONObject) v.opt("tracks") else v).map { track(pid, it) }

    suspend fun search(pid: String, q: String, type: String, page: Int = 1): Any {
        val s = get(pid) ?: throw NcmError("插件没有安装")
        val r = s.call("search", q, type, page)
        val items = arr(if (r is JSONObject) r.opt("items") ?: r.opt(type + "s") else r)
        return when (type) { "album" -> items.map { album(pid, it) }; "playlist" -> items.map { playlist(pid, it) }; else -> items.map { track(pid, it) } }
    }
    suspend fun stream(id: String, quality: Int): Stream {
        val o = src(id).call("stream", raw(id), quality) as? JSONObject ?: throw NcmError("插件没有给出播放地址")
        val url = o.optString("url").ifEmpty { throw NcmError(o.optString("error").ifEmpty { "插件没有给出播放地址" }) }
        if (o.optBoolean("trial")) Engine.error.value = o.optString("message").ifEmpty { "当前只能放试听片段" }
        return Stream(url, o.optString("ext", url.substringBefore('?').substringAfterLast('.', "mp3")), o.optInt("kbps"), o.optString("level", "standard"))
    }
    suspend fun lyric(id: String): LyricData {
        val o = src(id).let { if (it.has("lyric")) it.call("lyric", raw(id)) as? JSONObject else null } ?: return LyricData("", null, null)
        return LyricData(o.optString("lrc"), o.optString("tr").ifEmpty { null }, null)
    }
    suspend fun playlistTracks(id: String): List<Track> = tracksOf(pidOf(id), src(id).call("playlist", raw(id)))
    suspend fun album(id: String): Pair<Album, List<Track>> {
        val pid = pidOf(id); val r = src(id).call("album", raw(id)) as? JSONObject ?: JSONObject()
        val tracks = tracksOf(pid, r)
        val al = r.optJSONObject("album")?.let { album(pid, it.put("id", it.opt("id") ?: raw(id))) } ?: Album(id, tracks.firstOrNull()?.album ?: "", tracks.firstOrNull()?.cover, tracks.size, "", tracks.firstOrNull()?.artist ?: "")
        return al to tracks
    }
    suspend fun artist(id: String): Artist {
        val pid = pidOf(id); val r = src(id).call("artist", raw(id)) as? JSONObject ?: JSONObject()
        val tracks = tracksOf(pid, r)
        return Artist(id, r.optString("name").ifEmpty { tracks.firstOrNull()?.artist ?: "" }, r.optString("cover").ifEmpty { null }, r.optString("desc"), tracks.size, 0, tracks)
    }
    suspend fun toplists(pid: String): List<Playlist> = arr(get(pid)?.call("toplists")).map { playlist(pid, it) }
    suspend fun myPlaylists(pid: String): List<Playlist> = arr(get(pid)?.call("myPlaylists")).map { playlist(pid, it) }
    suspend fun like(id: String, on: Boolean) { src(id).takeIf { it.has("like") }?.call("like", raw(id), on) }

    /** 来源的显示名：组件上的小标签用 */
    fun sourceName(id: String): String? = when {
        isPlugin(id) -> get(pidOf(id))?.name ?: pidOf(id)
        id.startsWith("qq:") -> "QQ 音乐"
        id.startsWith("kg:") -> "酷狗"
        else -> null
    }
}
