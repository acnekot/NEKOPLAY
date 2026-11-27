package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * 插件订阅：一个订阅地址指向 index.json，里面列出可安装的插件（地址可以写相对 index.json 的路径）。
 * 格式：{"name": "订阅名", "plugins": [{"id", "name", "version", "author"?, "desc"?, "url"}]}
 */
object PluginSubs {
    /** 官方订阅：独立插件仓库里的 index.json（raw 打不开时自动换 jsDelivr） */
    const val OFFICIAL = "https://raw.githubusercontent.com/acnekot/NEKOPLAY_PLUGINS/main/index.json"
    private const val LEGACY_OFFICIAL = "https://raw.githubusercontent.com/acnekot/NEKOPLAY/main/plugins/index.json"

    class Item(val id: String, val name: String, val version: String, val author: String, val desc: String, val url: String, val from: String, val type: String = "source") {
        val isFeature: Boolean get() = !type.equals("source", ignoreCase = true)
    }
    class Sub(val url: String, val name: String, val items: List<Item>, val error: String?)

    private val prefs by lazy { Plat.prefs("plugins") }
    var urls by mutableStateOf<List<String>>(emptyList()); private set
    var subs by mutableStateOf<Map<String, Sub>>(emptyMap()); private set
    var loading by mutableStateOf(false); private set
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        val raw = prefs.getString("subs", null)?.split('\n')?.filter { it.isNotBlank() }
        if (raw == null) { urls = listOf(OFFICIAL); return }
        val migrated = raw.map { if (it == LEGACY_OFFICIAL) OFFICIAL else it }.distinct()
        urls = migrated
        if (migrated != raw) save()
    }
    private fun save() { prefs.edit().putString("subs", urls.joinToString("\n")).apply() }
    fun add(url: String) { val u = url.trim(); if (!u.startsWith("http")) throw SourceError("订阅地址要以 http:// 或 https:// 开头"); if (u !in urls) { urls = urls + u; save() } }
    fun remove(url: String) { urls = urls - url; subs = subs - url; save() }

    /** raw.githubusercontent.com 在有的网络打不开：同一个文件的 jsDelivr 地址当备用 */
    private fun mirrors(url: String): List<String> {
        val m = Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$").find(url) ?: return listOf(url)
        val (o, r, b, p) = m.destructured
        return listOf(url, "https://cdn.jsdelivr.net/gh/$o/$r@$b/$p")
    }
    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (u in mirrors(url)) {
            try {
                return@withContext SourceApi.http.newCall(Request.Builder().url(u).header("Cache-Control", "no-cache").build()).execute().use { r ->
                    if (!r.isSuccessful) throw SourceError("HTTP ${r.code}")
                    r.body?.string() ?: ""
                }
            } catch (e: Exception) { last = e }
        }
        throw SourceError("下载失败：${last?.message}")
    }
    /** index.json 里的相对路径换成完整地址 */
    private fun resolve(base: String, u: String) = when {
        u.startsWith("http") -> u
        u.startsWith("/") -> Regex("^https?://[^/]+").find(base)!!.value + u
        else -> base.substringBeforeLast('/') + "/" + u
    }

    /** 拉取全部订阅 */
    suspend fun refresh() {
        load()
        loading = true
        try {
            val got = coroutineScope {
                urls.map { u ->
                    async {
                        u to try {
                            val o = JSONObject(fetch(u))
                            val name = o.optString("name").ifEmpty { u.substringAfter("://").substringBefore('/') }
                            val items = Plugins.arr(o.optJSONArray("plugins")).mapNotNull { p ->
                                val id = p.optString("id"); val url = p.optString("url")
                                if (id.isEmpty() || url.isEmpty()) null
                                else Item(id, p.optString("name").ifEmpty { id }, p.optString("version", "0"), p.optString("author"), p.optString("desc"), resolve(u, url), name, p.optString("type", "source"))
                            }
                            Sub(u, name, items, null)
                        } catch (e: Exception) { Sub(u, u, emptyList(), e.message ?: e.toString()) }
                    }
                }.awaitAll().toMap()
            }
            subs = got
        } finally { loading = false }
    }

    /** a 比 b 新（按点分隔的数字比较） */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }; val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }
    /** 订阅里比已装版本新的插件 */
    val updates: List<Item> get() = subs.values.flatMap { it.items }.filter { i -> Plugins.get(i.id)?.let { newer(i.version, it.version) } == true }.distinctBy { it.id }

    /** 安装或更新；更新时保持原来的开关状态 */
    suspend fun install(i: Item): JsSource {
        val text = fetch(i.url)
        val id = Regex("""^\s*//\s*@id\s+([A-Za-z0-9_\-]+)\s*$""", RegexOption.MULTILINE).find(text.take(4000))?.groupValues?.get(1)
        if (id != i.id) throw SourceError("下载到的脚本 id 是「${id ?: "无"}」，和订阅里写的「${i.id}」不一致")
        val wasOff = Plugins.get(id) != null && !Plugins.on(id)
        val s = Plugins.import(text)
        if (wasOff) Plugins.set(id, false)
        return s
    }
}

/** 老版本内置音源的登录信息搬到对应插件里（导入插件前也先搬好，导入后直接是登录状态） */
object LegacyLogin {
    fun migrate() {
        val done = Plat.prefs("plugins")
        if (done.getBoolean("migrated", false)) return
        fun put(pid: String, key: String, json: org.json.JSONObject?) { val v = json ?: return; val st = Plat.prefs("plugin_$pid"); if (st.getString(key, null) == null) st.edit().putString(key, v.toString()).apply() }
        Plat.prefs("ncm").getString("cookie", "")?.takeIf { it.isNotEmpty() }?.let { ck ->
            val st = Plat.prefs("plugin_netease"); if (st.getString("cookie", null) == null) st.edit().putString("cookie", org.json.JSONObject.quote(ck)).apply()
        }
        Plat.prefs("qq").let { q -> val id = q.getLong("musicid", 0); val k = q.getString("musickey", "") ?: ""
            if (id > 0 && k.isNotEmpty()) put("qqmusic", "cred", org.json.JSONObject().put("id", id).put("key", k).put("type", q.getInt("loginType", 2)).put("nick", q.getString("nick", "") ?: "")) }
        Plat.prefs("kugou").let { g -> val id = g.getLong("userid", 0); val t = g.getString("token", "") ?: ""
            if (id > 0 && t.isNotEmpty()) put("kugou", "cred", org.json.JSONObject().put("userid", id).put("token", t).put("nick", g.getString("nick", "") ?: "").put("avatar", g.getString("avatar", "") ?: ""))
            g.getString("guid", null)?.let { gu -> val st = Plat.prefs("plugin_kugou"); if (st.getString("guid", null) == null) st.edit().putString("guid", org.json.JSONObject.quote(gu)).apply() } }
        done.edit().putBoolean("migrated", true).apply()
    }
}
