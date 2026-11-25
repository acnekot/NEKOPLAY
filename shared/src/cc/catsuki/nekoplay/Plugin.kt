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
/** 插件大类：音源插件（曲库/搜索/播放/歌词）、功能插件（槽位扩展/钩子/歌词管道）、页面插件（声明式页面）、动效插件（规范化动效引擎包） */
enum class PluginKind(val id: String, val label: String) {
    SOURCE("source", "音源插件"),
    FEATURE("feature", "功能插件"),
    PAGE("page", "页面插件"),
    MOTION("motion", "动效插件"),
}

class JsSource(val file: File) {
    /** 脚本开头注释里的信息：// @id xx  // @name xx  // @type source|feature|page|motion  // @version 1.0.0  // @minHost 0.6.8  // @permissions ... */
    val meta: Map<String, String> = Regex("""^\s*//\s*@(\w+)\s+(.+)$""", RegexOption.MULTILINE).findAll(file.readText().take(4000))
        .associate { it.groupValues[1] to it.groupValues[2].trim() }
    val id: String get() = meta["id"] ?: file.nameWithoutExtension
    val name: String get() = meta["name"] ?: id
    val version: String get() = meta["version"] ?: "0"
    val apiVersion: Int get() = meta["api"]?.toIntOrNull() ?: 1
    val minHost: String get() = meta["minHost"].orEmpty()
    val permissions: Set<String> get() = meta["permissions"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    val hostCompatible: Boolean get() = minHost.isEmpty() || !PluginSubs.newer(minHost, APP_VERSION)
    /** 功能插件绑定的扩展槽位标识（头注释 // @feature coverflow 等） */
    val featureSlot: String get() = meta["feature"]?.lowercase()?.trim().orEmpty()
    /** 插件类型：默认 source；支持 // @type feature|page|motion 显式声明，未写时自动按槽位与导出能力识别 */
    val kind: PluginKind get() = when (meta["type"]?.lowercase()) {
        "motion", "anim", "animation" -> PluginKind.MOTION
        "page", "ui", "view" -> PluginKind.PAGE
        "feature", "ext", "extension", "tool", "hook" -> PluginKind.FEATURE
        "source", "music", "audio" -> PluginKind.SOURCE
        else -> when {
            "profile" in caps && "stream" !in caps -> PluginKind.MOTION
            "render" in caps && "stream" !in caps -> PluginKind.PAGE
            featureSlot.isNotEmpty() || (caps.isNotEmpty() && "stream" !in caps && "search" !in caps &&
                listOf("manifest", "config", "onTrackChange", "onPlayState", "transformLyric", "action").any { it in caps }) -> PluginKind.FEATURE
            else -> PluginKind.SOURCE
        }
    }
    val isSource: Boolean get() = kind == PluginKind.SOURCE
    val isPage: Boolean get() = kind == PluginKind.PAGE
    val isMotion: Boolean get() = kind == PluginKind.MOTION
    val isFeature: Boolean get() = kind != PluginKind.SOURCE
    /** 能搜的类型（头注释「// @search track,album,…」），没写就当单曲 / 专辑 / 歌单 */
    val searchTypes: Set<String> get() = meta["search"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: setOf("track", "album", "playlist")

    private var js: QuickJs? = null
    @kotlin.concurrent.Volatile var lastUsedAt = 0L; private set
    private val lock = Mutex()
    /** 脚本实现了哪些接口（search / stream / lyric …），界面按这个决定显示什么；优先从本地缓存恢复以实现按需启动 */
    private val capsKey get() = "caps_${id}_${version}"
    var caps by mutableStateOf<Set<String>>(Plat.prefs("plugins").getStringSet(capsKey, null) ?: emptySet()); private set
    var error by mutableStateOf<String?>(null); private set
    val isRunning get() = js != null
    fun has(cap: String) = cap in caps
    suspend fun start(): Boolean = lock.withLock {
        lastUsedAt = System.currentTimeMillis()
        if (js != null) return true
        try {
            val q = QuickJs.create(Dispatchers.Default)
            installHost(q)
            q.evaluate<Any?>(prelude(), filename = "prelude.js")
            q.evaluate<Any?>(file.readText(), filename = file.name)
            val c = JSONArray(q.evaluate<String>("__caps()")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            caps = c; Plat.prefs("plugins").edit().putStringSet(capsKey, c).apply()
            js = q; error = null
            Plugins.bump()
            true
        } catch (e: Throwable) {
            error = e.message ?: e.toString(); Debug.log("js:$id", 0, 0, "start", error ?: ""); false
        }
    }

    /**
     * 关闭：先停掉还在跑的网络请求，再在锁里销毁脚本环境。
     * 后台可能正好在把网络结果交回脚本（evaluate 中途），不拿锁就销毁会让原生层崩溃。
     */
    fun close(): kotlinx.coroutines.Job {
        scope.coroutineContext.cancelChildren()
        return closer.launch {
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
        lastUsedAt = System.currentTimeMillis()
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

        /** 脚本运行前先注入的工具：nk.http / nk.md5 / nk.aes / nk.store / nk.log / nk.host，以及注册入口 */
        fun prelude() = """
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
              registerFeature: (s) => { globalThis.source = s; },
              registerPage: (s) => { globalThis.source = s; },
              registerMotion: (s) => { globalThis.source = s; },
              host: { version: '${APP_VERSION}', platform: '${Plat.impl.name}' },
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
