package okhttp3

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.toByteArray
import java.io.toNSData
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.darwin.*
import java.util.concurrent.TimeUnit

// iOS 版 OkHttp：用 NSURLSession 同步发请求（共用代码都在 IO 线程里调用 execute()）

class MediaType internal constructor(val text: String) {
    override fun toString() = text
    companion object { fun String.toMediaType() = MediaType(this); fun String.toMediaTypeOrNull() = MediaType(this) }
}

open class RequestBody internal constructor(internal val bytes: ByteArray, internal val type: MediaType?) {
    companion object {
        fun String.toRequestBody(t: MediaType? = null) = RequestBody(encodeToByteArray(), t)
        fun ByteArray.toRequestBody(t: MediaType? = null) = RequestBody(this, t)
    }
}

class FormBody private constructor(bytes: ByteArray) : RequestBody(bytes, MediaType("application/x-www-form-urlencoded")) {
    class Builder {
        private val parts = ArrayList<String>()
        fun add(k: String, v: String): Builder { parts += enc(k) + "=" + enc(v); return this }
        fun build() = FormBody(parts.joinToString("&").encodeToByteArray())
        private fun enc(s: String): String {
            val sb = StringBuilder()
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                when {
                    ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-_.*" -> sb.append(ch)
                    ch == ' ' -> sb.append('+')
                    else -> { sb.append('%'); sb.append("0123456789ABCDEF"[c shr 4]); sb.append("0123456789ABCDEF"[c and 15]) }
                }
            }
            return sb.toString()
        }
    }
}

object Credentials {
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun basic(user: String, pass: String, @Suppress("UNUSED_PARAMETER") charset: Any? = null): String =
        "Basic " + kotlin.io.encoding.Base64.Default.encode("$user:$pass".encodeToByteArray())
}

class Headers internal constructor(internal val list: List<Pair<String, String>>) {
    operator fun get(name: String): String? = list.lastOrNull { it.first.equals(name, true) }?.second
    fun values(name: String) = list.filter { it.first.equals(name, true) }.map { it.second }
    fun names(): Set<String> = list.mapTo(LinkedHashSet()) { it.first }
}

class Request private constructor(val url: String, val method: String, val headers: Headers, val body: RequestBody?) {
    fun header(n: String) = headers[n]
    class Builder {
        private var url = ""; private var method = "GET"; private var body: RequestBody? = null
        private val hs = ArrayList<Pair<String, String>>()
        fun url(u: String): Builder { url = u; return this }
        fun header(k: String, v: String): Builder { hs.removeAll { it.first.equals(k, true) }; hs += k to v; return this }
        fun addHeader(k: String, v: String): Builder { hs += k to v; return this }
        fun removeHeader(k: String): Builder { hs.removeAll { it.first.equals(k, true) }; return this }
        fun get(): Builder { method = "GET"; body = null; return this }
        fun head(): Builder { method = "HEAD"; body = null; return this }
        fun post(b: RequestBody): Builder { method = "POST"; body = b; return this }
        fun put(b: RequestBody): Builder { method = "PUT"; body = b; return this }
        fun delete(b: RequestBody? = null): Builder { method = "DELETE"; body = b; return this }
        fun method(m: String, b: RequestBody?): Builder { method = m; body = b; return this }
        fun build() = Request(url, method, Headers(hs.toList()), body)
    }
}

class ResponseBody internal constructor(private val data: ByteArray, private val type: String?) : AutoCloseable {
    fun string(): String = data.decodeToString()
    fun bytes(): ByteArray = data
    fun byteStream(): InputStream = ByteArrayInputStream(data)
    fun contentLength(): Long = data.size.toLong()
    fun contentType(): MediaType? = type?.let { MediaType(it) }
    override fun close() {}
}

class Response internal constructor(val request: Request, val code: Int, val message: String, private val hs: Headers, val body: ResponseBody?) : AutoCloseable {
    val isSuccessful get() = code in 200..299
    fun header(n: String): String? = hs[n]
    fun header(n: String, d: String): String = hs[n] ?: d
    fun headers(n: String): List<String> = hs.values(n)
    val headers get() = hs
    override fun close() {}
}

interface Call { fun execute(): Response; fun cancel() }

/** 不跟随跳转：把 302 原样交给调用方（登录流程要读 Location 和 Set-Cookie） */
private class NoRedirect : NSObject(), NSURLSessionTaskDelegateProtocol {
    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, willPerformHTTPRedirection: NSHTTPURLResponse, newRequest: NSURLRequest, completionHandler: (NSURLRequest?) -> Unit) {
        completionHandler(null)
    }
}

class OkHttpClient private constructor(private val connectMs: Long, private val readMs: Long, private val follow: Boolean = true) {
    constructor() : this(10_000, 15_000)

    private val session: NSURLSession by lazy {
        val c = NSURLSessionConfiguration.ephemeralSessionConfiguration
        // 和 OkHttp 一样不自动存 / 带 Cookie，交给调用方自己管
        c.HTTPShouldSetCookies = false
        c.HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
        c.HTTPCookieStorage = null
        c.URLCache = null
        c.timeoutIntervalForRequest = (connectMs + readMs) / 1000.0
        c.timeoutIntervalForResource = 600.0
        if (follow) NSURLSession.sessionWithConfiguration(c) else NSURLSession.sessionWithConfiguration(c, NoRedirect(), null)
    }

    fun newBuilder() = Builder(connectMs, readMs, follow)

    fun newCall(r: Request): Call = object : Call {
        private var task: NSURLSessionDataTask? = null
        override fun cancel() { task?.cancel() }
        override fun execute(): Response {
            val u = NSURL.URLWithString(r.url) ?: throw IOException("网址无效：${r.url}")
            val req = NSMutableURLRequest.requestWithURL(u)
            req.HTTPMethod = r.method
            req.HTTPShouldHandleCookies = false
            req.setTimeoutInterval((connectMs + readMs) / 1000.0)
            for ((k, v) in r.headers.list) req.addValue(v, forHTTPHeaderField = k)
            r.body?.let { b ->
                req.HTTPBody = b.bytes.toNSData()
                if (r.headers["Content-Type"] == null) b.type?.let { req.setValue(it.text, forHTTPHeaderField = "Content-Type") }
            }
            val sem = dispatch_semaphore_create(0)
            var data: NSData? = null; var resp: NSURLResponse? = null; var err: NSError? = null
            val t = session.dataTaskWithRequest(req) { d, rs, e -> data = d; resp = rs; err = e; dispatch_semaphore_signal(sem) }
            task = t; t.resume()
            dispatch_semaphore_wait(sem, DISPATCH_TIME_FOREVER)
            err?.let { throw IOException(it.localizedDescription) }
            val hr = resp as? NSHTTPURLResponse ?: throw IOException("没有响应")
            val hs = ArrayList<Pair<String, String>>()
            hr.allHeaderFields.forEach { (k, v) ->
                val key = k.toString(); val value = v.toString()
                if (!key.equals("Set-Cookie", true)) hs += key to value
            }
            // 多条 Set-Cookie 会被系统合并成一行，按 Cookie 解析后拆回一条条
            @Suppress("UNCHECKED_CAST")
            val cookies = NSHTTPCookie.cookiesWithResponseHeaderFields(hr.allHeaderFields as Map<Any?, *>, u)
            for (c in cookies) {
                val ck = c as NSHTTPCookie
                val parts = mutableListOf("${ck.name}=${ck.value}")
                ck.expiresDate?.let { parts += "Max-Age=" + maxOf(0L, (it.timeIntervalSinceNow).toLong()) }
                parts += "Path=${ck.path}"
                if (ck.domain.isNotEmpty()) parts += "Domain=${ck.domain}"
                hs += "Set-Cookie" to parts.joinToString("; ")
            }
            val ct = hs.lastOrNull { it.first.equals("Content-Type", true) }?.second
            return Response(r, hr.statusCode.toInt(), NSHTTPURLResponse.localizedStringForStatusCode(hr.statusCode), Headers(hs),
                ResponseBody(data?.toByteArray() ?: ByteArray(0), ct))
        }
    }

    class Builder internal constructor(private var connectMs: Long, private var readMs: Long, private var follow: Boolean = true) {
        constructor() : this(10_000, 15_000)
        fun connectTimeout(n: Long, u: TimeUnit): Builder { connectMs = u.toMillis(n); return this }
        fun readTimeout(n: Long, u: TimeUnit): Builder { readMs = u.toMillis(n); return this }
        fun writeTimeout(n: Long, u: TimeUnit): Builder = this
        fun callTimeout(n: Long, u: TimeUnit): Builder = this
        fun followRedirects(b: Boolean): Builder { follow = b; return this }
        fun followSslRedirects(b: Boolean): Builder { follow = b; return this }
        fun build() = OkHttpClient(connectMs, readMs, follow)
    }
}
