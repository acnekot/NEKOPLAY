package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    /** 能搜的类型（头注释「// @search track,album,…」），没写就当单曲 / 专辑 / 歌单 */
    val searchTypes: Set<String> get() = meta["search"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: setOf("track", "album", "playlist")

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

    /**
     * 关闭：先停掉还在跑的网络请求，再在锁里销毁脚本环境。
     * 后台可能正好在把网络结果交回脚本（evaluate 中途），不拿锁就销毁会让原生层崩溃。
     */
    fun close() {
        scope.coroutineContext.cancelChildren()
        closer.launch {
            lock.withLock {
                waiting.values.forEach { it.complete(false to "插件已关闭") }
                waiting.clear(); results.clear()
                js?.close(); js = null
            }
        }
    }

    /*
     * 并发：quickjs-kt 的 evaluate 会一直等到这次求值里的异步任务全部结束，多个 evaluate 之间是排队的。
     * 所以这里自己做事件循环：evaluate 只负责「开始一次调用」或「把一个网络结果交回脚本」，马上返回；
     * 网络请求在脚本外面并发跑，完成后再用一次很短的 evaluate 唤醒等待它的 Promise。
     * 这样一个插件里慢的调用（微信扫码长轮询、大歌单）不会挡住切歌、歌词这些。
     */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    /** 关闭用的协程不能放在 scope 里（会被上面的取消一起取消掉） */
    private val closer = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private var seq = 0L
    /** 等结果的调用 / 等交回脚本的网络结果；只在 lock 里读写 */
    private val waiting = HashMap<Long, kotlinx.coroutines.CompletableDeferred<Pair<Boolean, String?>>>()
    private val results = HashMap<Long, Pair<Boolean, String>>()

    /** 调用脚本里 source 对象的方法：参数和返回值都是 JSON */
    suspend fun call(fn: String, vararg args: Any?): Any? {
        if (js == null && !start()) throw NcmError("音源「$name」加载失败：$error")
        val argJson = JSONArray().apply { args.forEach { put(it ?: JSONObject.NULL) } }.toString()
        val t0 = System.currentTimeMillis()
        val d = kotlinx.coroutines.CompletableDeferred<Pair<Boolean, String?>>()
        var cid = 0L
        val (ok, out) = try {
            lock.withLock {
                val q = js ?: throw NcmError("音源「$name」已关闭")
                cid = ++seq; waiting[cid] = d
                q.evaluate<Any?>("__call($cid, ${q(fn)}, ${q(argJson)})", filename = "call.js")
            }
            d.await()
        } catch (e: kotlinx.coroutines.CancellationException) { lock.withLock { waiting.remove(cid) }; throw e }
        catch (e: Throwable) { lock.withLock { waiting.remove(cid) }; false to (e.message ?: e.toString()) }
        if (!ok) {
            val msg = (out ?: "").substringBefore("\n").removePrefix("Error: ")
            Debug.log("js:$id.$fn", 0, System.currentTimeMillis() - t0, "error", msg)
            throw NcmError(msg)
        }
        Debug.log("js:$id.$fn", 200, System.currentTimeMillis() - t0, "application/json", out ?: "null")
        if (out == null || out == "null") return null
        return when (out.firstOrNull()) { '{' -> JSONObject(out); '[' -> JSONArray(out); else -> JSONArray("[$out]").opt(0) }
    }

    /** 网络请求在脚本外面跑，完成后把结果交回脚本 */
    private fun httpStart(rid: Long, req: String) {
        scope.launch {
            val r = try { true to http(JSONObject(req)).toString() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { false to (e.message ?: e.toString()) }
            runCatching { lock.withLock { val q = js ?: return@withLock; results[rid] = r; q.evaluate<Any?>("__resolve($rid)", filename = "resolve.js") } }
        }
    }

    // ---------------- 给脚本用的宿主接口 ----------------
    private val store by lazy { Plat.prefs("plugin_$id") }

    @OptIn(ExperimentalEncodingApi::class)
    private fun installHost(q: QuickJs) {
        q.define("__host") {
            function("httpStart") { args -> httpStart((args[0] as Number).toLong(), args[1] as String); null }
            function("take") { args -> results.remove((args[0] as Number).toLong())?.let { (ok, v) -> JSONArray().put(ok).put(v).toString() } }
            function("done") { args -> waiting.remove((args[0] as Number).toLong())?.complete((args[1] as Boolean) to (args.getOrNull(2) as? String)); null }
            function("md5") { args -> Hash.md5((args[0] as String).encodeToByteArray()) }
            function("md5b64") { args -> Hash.md5(Base64.decode(args[0] as String)) }
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
        val base = if (o.optBoolean("redirect", true)) Ncm.http else noRedirect
        // timeout：秒，长轮询（微信扫码状态）要等得久一点
        val client = o.optInt("timeout", 0).takeIf { it > 0 }?.let { t -> base.newBuilder().readTimeout(t.toLong(), java.util.concurrent.TimeUnit.SECONDS).build() } ?: base
        // 调用方取消（比如退出登录页）时把连接也断掉，不让长轮询挂着
        val call = client.newCall(b.build())
        try {
            coroutineScope { val job = async { call.execute() }; try { job.await() } catch (e: kotlinx.coroutines.CancellationException) { call.cancel(); throw e } }.use { r ->
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
            const __p = {}; let __seq = 0;
            const __http = (req) => new Promise((res, rej) => { const id = ++__seq; __p[id] = { res, rej }; __host.httpStart(id, req); });
            globalThis.__resolve = (id) => { const p = __p[id]; delete __p[id]; const r = JSON.parse(__host.take(id) || '[false,"无结果"]'); if (p) r[0] ? p.res(r[1]) : p.rej(new Error(r[1])); };
            globalThis.__call = (cid, fn, args) => { __invoke(fn, args).then(r => __host.done(cid, true, r), e => __host.done(cid, false, String(e && e.message || e))); };
            globalThis.nk = {
              http: async (o) => { const r = JSON.parse(await __http(JSON.stringify(typeof o === 'string' ? { url: o } : o)));
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
        // 先把开着的脚本跑起来，界面才知道每个插件有哪些功能
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { enabled.forEach { it.start() } }
    }
    fun on(id: String) = id !in off && list.value.any { it.id == id }
    fun get(id: String) = list.value.firstOrNull { it.id == id }
    val enabled get() = list.value.filter { it.id !in off }
    fun set(id: String, v: Boolean) { off = if (v) off - id else off + id; prefs.edit().putStringSet("off", off).apply(); if (!v) get(id)?.close()
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { if (v) get(id)?.start(); PluginStore.refresh().join(); Store.refreshAll() } }

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
    fun of(id: String): JsSource = get(pidOf(id))?.takeIf { on(it.id) } ?: throw NcmError("需要「${nameOf(pidOf(id))}」插件：到 设置 → 插件 导入或打开")
    fun ofOrNull(id: String): JsSource? = get(pidOf(id))?.takeIf { on(it.id) }
    /** 调 id 所属插件的方法；插件没实现时返回 null */
    suspend fun callFor(id: String, fn: String, vararg args: Any?): Any? { val s = ofOrNull(id) ?: return null; if (!s.start() || !s.has(fn)) return null; return s.call(fn, *args) }

    /** 主插件：账号、日推、FM 这类没有 id 的功能默认找它（首页来源 → 网易云 → 第一个有账号功能的） */
    fun main(): JsSource? {
        val en = enabled
        return en.firstOrNull { it.id == UiPref.homePlugin } ?: en.firstOrNull { it.id == "netease" } ?: en.firstOrNull { it.has("account") } ?: en.firstOrNull()
    }
    /** 提供某个功能的插件：先看主插件，再找第一个实现了它的 */
    suspend fun provider(cap: String): JsSource? {
        main()?.let { if (it.start() && it.has(cap)) return it }
        return enabled.firstOrNull { it.start() && it.has(cap) }
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
        val s = get(pid) ?: throw NcmError("插件没有安装")
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
        val o = of(id).call("stream", raw(id), quality) as? JSONObject ?: throw NcmError("插件没有给出播放地址")
        val url = o.optString("url").ifEmpty { throw NcmError(o.optString("error").ifEmpty { "插件没有给出播放地址" }) }
        val trial = if (o.optBoolean("trial")) o.optString("message").ifEmpty { "当前只能放试听片段" } else null
        val st = Stream(url, o.optString("ext").ifEmpty { url.substringBefore('?').substringAfterLast('.', "mp3") }, o.optInt("kbps"), o.optString("level", "standard"), trial)
        streamLock.withLock { streamCache.entries.removeAll { now - it.value.at >= STREAM_TTL }; streamCache[key] = CachedStream(st, now) }
        return st
    }
    suspend fun lyric(id: String): LyricData {
        val o = callFor(id, "lyric", raw(id)) as? JSONObject ?: throw NcmError("暂无歌词")
        val l = LyricData(o.optString("lrc"), o.optString("tr").takeIf { it.isNotBlank() && it != "null" }, o.optString("yrc").takeIf { it.isNotBlank() && it != "null" })
        if (l.lrc.isBlank() && l.yrc == null) throw NcmError("暂无歌词")
        return l
    }
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

/** 老版本内置音源的登录信息搬到对应插件里（导入插件前也先搬好，导入后直接是登录状态） */
object LegacyLogin {
    fun migrate() {
        val done = Plat.prefs("plugins")
        if (done.getBoolean("migrated", false)) return
        fun put(pid: String, key: String, json: JSONObject?) { val v = json ?: return; val st = Plat.prefs("plugin_$pid"); if (st.getString(key, null) == null) st.edit().putString(key, v.toString()).apply() }
        Plat.prefs("ncm").getString("cookie", "")?.takeIf { it.isNotEmpty() }?.let { ck ->
            val st = Plat.prefs("plugin_netease"); if (st.getString("cookie", null) == null) st.edit().putString("cookie", JSONObject.quote(ck)).apply()
        }
        Plat.prefs("qq").let { q -> val id = q.getLong("musicid", 0); val k = q.getString("musickey", "") ?: ""
            if (id > 0 && k.isNotEmpty()) put("qqmusic", "cred", JSONObject().put("id", id).put("key", k).put("type", q.getInt("loginType", 2)).put("nick", q.getString("nick", "") ?: "")) }
        Plat.prefs("kugou").let { g -> val id = g.getLong("userid", 0); val t = g.getString("token", "") ?: ""
            if (id > 0 && t.isNotEmpty()) put("kugou", "cred", JSONObject().put("userid", id).put("token", t).put("nick", g.getString("nick", "") ?: "").put("avatar", g.getString("avatar", "") ?: ""))
            g.getString("guid", null)?.let { gu -> val st = Plat.prefs("plugin_kugou"); if (st.getString("guid", null) == null) st.edit().putString("guid", JSONObject.quote(gu)).apply() } }
        done.edit().putBoolean("migrated", true).apply()
    }
}
