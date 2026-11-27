package cc.catsuki.nekoplay

import org.json.JSONArray
import org.json.JSONObject

/**
 * 媒体规则：有的音源 CDN 要求图片 / 音频 / 视频请求带特定的 Referer / Origin / User-Agent（否则 403），
 * 图片还常用地址参数取不同尺寸的缩略图。宿主不写死任何平台，由音源插件在注册对象里声明：
 *
 *     nk.register({
 *       media: [
 *         { hosts: ['img.example.com'], headers: { Referer: 'https://www.example.com/' },
 *           image: { query: 'size={s}x{s}', sizes: [160, 256, 512, 768] } },
 *         { hosts: ['pic.example.net'], image: { from: '/480/', to: '/{s}/', sizes: [150, 240, 480] } },
 *       ],
 *       ...
 *     });
 *
 * - hosts：按域名后缀匹配（example.net 也匹配 a.example.net），不能是本机 / 局域网地址
 * - headers：只允许 Referer / Origin / User-Agent
 * - image：取缩略图的改写方式。from/to 为子串替换（要求地址包含 from），否则用 query 替换掉原查询串；
 *   contains 可再限定地址必须包含的子串；{s} 换成 sizes 里不小于显示像素的最小值；keepHttp 为 true 时不把 http 升级为 https
 * 只对已启用的音源插件生效。
 */
object MediaHeaders {
    class Image(val from: String?, val to: String?, val query: String?, val contains: String?, val sizes: List<Int>, val keepHttp: Boolean)
    class Rule(val hosts: List<String>, val headers: Map<String, String>, val image: Image? = null) {
        fun matches(host: String) = hosts.any { host == it || host.endsWith(".$it") }
    }

    private val ALLOWED = mapOf("referer" to "Referer", "origin" to "Origin", "user-agent" to "User-Agent")

    private fun cleanHosts(o: JSONObject): List<String> {
        val hs = o.optJSONArray("hosts") ?: return emptyList()
        return (0 until hs.length()).map { hs.optString(it).trim().lowercase().trimStart('.') }
            .filter { h -> '.' in h && h.all { it.isLetterOrDigit() || it == '.' || it == '-' } && !JsSource.isPrivateHost(h) }
    }
    private fun cleanHeaders(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().mapNotNull { k ->
            val name = ALLOWED[k.lowercase()] ?: return@mapNotNull null
            val v = o.optString(k).trim()
            if (v.isEmpty() || v.length > 512 || v.any { it == '\r' || it == '\n' }) null else name to v
        }.toMap()
    }
    private fun cleanImage(o: JSONObject?): Image? {
        if (o == null) return null
        fun str(k: String) = o.optString(k, "").takeIf { it.isNotEmpty() && it.length <= 200 }
        val sizes = o.optJSONArray("sizes")?.let { a -> (0 until a.length()).map { a.optInt(it) }.filter { it in 16..4096 }.sorted() }.orEmpty()
        val from = str("from"); val to = str("to"); val query = str("query")
        if (sizes.isEmpty() || (from == null || to == null) && query == null) return null
        return Image(from, to, query, str("contains"), sizes, o.optBoolean("keepHttp"))
    }

    /** 解析插件声明的 media 数组；不合规的条目直接丢弃 */
    fun parse(json: String): List<Rule> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val hosts = cleanHosts(o)
            val headers = cleanHeaders(o.optJSONObject("headers"))
            val image = cleanImage(o.optJSONObject("image"))
            if (hosts.isEmpty() || headers.isEmpty() && image == null) null else Rule(hosts, headers, image)
        }
    }.getOrDefault(emptyList())

    /** 测试用：替代「已启用音源插件的规则」 */
    @kotlin.concurrent.Volatile internal var override: List<Rule>? = null
    /** 按 Plugins.rev 缓存（插件启停 / 启动后读到新规则都会 bump），封面列表滚动时不用反复汇总 */
    @kotlin.concurrent.Volatile private var cache: Pair<Int, List<Rule>>? = null
    private fun rules(): List<Rule> {
        override?.let { return it }
        return runCatching {
            val rev = Plugins.rev
            cache?.takeIf { it.first == rev }?.second ?: Plugins.enabledSources.flatMap { it.media }.also { cache = rev to it }
        }.getOrDefault(emptyList())
    }

    /** 某个主机名要附加的请求头（多条规则命中时按插件顺序，先到先得） */
    fun forHost(host: String): Map<String, String> {
        val h = host.lowercase()
        if (h.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (r in rules()) if (r.matches(h)) r.headers.forEach { (k, v) -> if (k !in out) out[k] = v }
        return out
    }
    fun forUrl(url: String): Map<String, String> = forHost(JsSource.hostOf(url))

    /** 封面按显示像素取合适尺寸的缩略图地址；没有规则命中时只把 http 升级为 https */
    fun resize(url: String, px: Int): String {
        val host = JsSource.hostOf(url)
        val base = url.substringBefore('?')
        val rule = rules().firstOrNull { r ->
            val im = r.image ?: return@firstOrNull false
            r.matches(host) && (im.contains == null || im.contains in base) && (im.from == null || im.query != null || im.from in base)
        }
        val im = rule?.image
        val secure = if (url.startsWith("http://") && im?.keepHttp != true) "https://" + url.removePrefix("http://") else url
        if (im == null) return secure
        val s = (im.sizes.firstOrNull { it >= px } ?: im.sizes.last()).toString()
        val b = secure.substringBefore('?')
        return if (im.from != null && im.to != null && im.from in b) b.replace(im.from, im.to.replace("{s}", s))
        else if (im.query != null) b + "?" + im.query.replace("{s}", s) else secure
    }
}
