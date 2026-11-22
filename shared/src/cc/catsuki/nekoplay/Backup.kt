package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份到 WebDAV：设置、主题、首页布局、效果器、歌词偏移、喜欢 / 最近播放 / 队列。
 * 手机和电脑互相恢复也行（读取时会自动转换类型）。下载列表、本地音乐的路径和背景图不备份（换设备就对不上了）。
 */
object Backup {
    private val SETS = listOf("ui", "store", "fx", "lyricOffset", "plugins")
    private val kv by lazy { Plat.prefs("webdav") }
    var url by mutableStateOf(""); var user by mutableStateOf(""); var pass by mutableStateOf("")
    var withLogin by mutableStateOf(false)
    var last by mutableLongStateOf(0L)
    fun load() { url = kv.getString("url", "") ?: ""; user = kv.getString("user", "") ?: ""; pass = kv.getString("pass", "") ?: ""; withLogin = kv.getBoolean("login", false); last = kv.getLong("last", 0) }
    fun save() { kv.edit().putString("url", url).putString("user", user).putString("pass", pass).putBoolean("login", withLogin).putLong("last", last).apply() }

    private fun target(): String { val u = url.trim(); require(u.startsWith("http")) { "地址要以 http:// 或 https:// 开头" }; return u.trimEnd('/') + "/nekoplay-backup.json" }
    private fun req(u: String) = Request.Builder().url(u).apply { if (user.isNotEmpty()) header("Authorization", Credentials.basic(user, pass, Charsets.UTF_8)) }

    /** 登录信息：每个插件自己的存储（cookie、凭证都在里面） */
    private val LOGINS get() = Plugins.list.value.map { "plugin_" + it.id } + "davmusic"
    /** 备份里有没有登录信息（老版本的内置音源，或插件存储 / WebDAV 音乐） */
    fun hasLogin(root: JSONObject) = root.keys().asSequence().any { it in setOf("ncm", "qq", "kugou", "davmusic") || it.startsWith("plugin_") }
    fun dump(login: Boolean = withLogin): JSONObject {
        val root = JSONObject().put("v", 1).put("from", Plat.impl.name).put("at", System.currentTimeMillis())
        for (name in SETS + if (login) LOGINS else emptyList()) {
            val o = JSONObject()
            Plat.prefs(name).all().forEach { (k, v) ->
                when (v) {
                    is Set<*> -> o.put(k, JSONArray(v.map { it.toString() }))
                    is Long -> o.put(k, JSONObject().put("L", v))   // 标出 Long，恢复时类型不会变成 Int
                    is Boolean, is Int, is Float, is Double -> o.put(k, v)
                    null -> {}
                    else -> o.put(k, v.toString())
                }
            }
            root.put(name, o)
        }
        root.put("recents", JSONArray(Store.recents.value.filter { !it.id.startsWith("local:") }.map { it.toJson() }))
        // 插件脚本本身也带上，换设备恢复后不用重新导入
        root.put("scripts", JSONObject().apply { Plugins.list.value.forEach { put(it.id, it.file.readText()) } })
        root.put("locallib", LocalLib.dump())
        return root
    }

    suspend fun upload() = withContext(Dispatchers.IO) {
        val body = dump().toString().toRequestBody("application/json".toMediaType())
        Ncm.http.newCall(req(target()).put(body).build()).execute().use { r -> if (!r.isSuccessful) throw Exception("HTTP ${r.code}") }
        last = System.currentTimeMillis(); save()
    }

    /** 下载并写回；返回备份时间 */
    suspend fun restore(): Long = withContext(Dispatchers.IO) {
        val text = Ncm.http.newCall(req(target()).get().build()).execute().use { r -> if (!r.isSuccessful) throw Exception(if (r.code == 404) "还没有备份" else "HTTP ${r.code}"); r.body!!.string() }
        apply(JSONObject(text))
    }

    /** 把备份写回本机；备份里有登录信息就一起恢复。返回备份时间 */
    suspend fun apply(root: JSONObject): Long = withContext(Dispatchers.IO) {
        if (!root.has("v") || !root.has("ui")) throw Exception("这不是 NEKOPLAY 的配置文件")
        root.optJSONObject("scripts")?.let { sc -> sc.keys().forEach { id -> runCatching { Plugins.import(sc.getString(id)) } } }
        // 老备份里的 ncm / qq / kugou 是内置音源的登录信息：写回后再搬进插件
        for (name in SETS + listOf("ncm", "qq", "kugou", "davmusic") + root.keys().asSequence().filter { it.startsWith("plugin_") }) {
            val o = root.optJSONObject(name) ?: continue
            val e = Plat.prefs(name).edit()
            o.keys().forEach { k ->
                when (val v = o.get(k)) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) e.putInt(k, v.toInt()) else e.putLong(k, v)
                    is Double -> e.putFloat(k, v.toFloat())
                    is Float -> e.putFloat(k, v)
                    is JSONArray -> e.putStringSet(k, (0 until v.length()).map { v.getString(it) }.toSet())
                    is JSONObject -> if (v.has("L")) e.putLong(k, v.optLong("L")) else e.putString(k, v.toString())
                    else -> e.putString(k, v.toString())
                }
            }
            e.apply()
        }
        root.optJSONArray("recents")?.let { a -> val l = (0 until a.length()).map { Track.fromJson(a.getJSONObject(it)) }; withContext(Dispatchers.Main) { Store.restoreRecents(l) } }
        if (listOf("ncm", "qq", "kugou").any { root.has(it) }) { Plat.prefs("plugins").edit().putBoolean("migrated", false).apply(); LegacyLogin.migrate() }
        root.optString("locallib").takeIf { it.isNotEmpty() }?.let { LocalLib.restore(it) }
        DavMusic.load()
        Plugins.list.value.forEach { it.close() }; Plugins.load(); Store.reloadLiked(); Store.refreshAll()
        root.optLong("at")
    }
}
