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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * 配置备份与恢复（本地导出 / 剪贴板 / WebDAV 网盘）：
 * - 覆盖设置、自定义主题、首页布局、效果器、歌词偏移、听歌统计、本机曲库、喜欢 / 最近播放与插件脚本；
 * - 支持可选口令 AES-256-CBC + 完整性校验加密（跨 Desktop / Android / iOS 全端互通），兼容旧版明文 v1 备份。
 */
object Backup {
    private val SETS = listOf("ui", "store", "fx", "lyricOffset", "plugins")
    private val kv by lazy { Plat.prefs("webdav") }
    var url by mutableStateOf(""); var user by mutableStateOf(""); var pass by mutableStateOf("")
    var cryptPass by mutableStateOf("")
    var withLogin by mutableStateOf(false)
    var last by mutableLongStateOf(0L)

    fun load() {
        url = kv.getString("url", "") ?: ""
        user = kv.getString("user", "") ?: ""
        pass = kv.getString("pass", "") ?: ""
        cryptPass = kv.getString("cryptPass", "") ?: ""
        withLogin = kv.getBoolean("login", false)
        last = kv.getLong("last", 0)
    }

    fun save() {
        kv.edit().putString("url", url).putString("user", user).putString("pass", pass)
            .putString("cryptPass", cryptPass).putBoolean("login", withLogin).putLong("last", last).apply()
    }

    private fun target(): String { val u = url.trim(); require(u.startsWith("http")) { "地址要以 http:// 或 https:// 开头" }; return u.trimEnd('/') + "/nekoplay-backup.json" }
    private fun req(u: String) = Request.Builder().url(u).apply { if (user.isNotEmpty()) header("Authorization", Credentials.basic(user, pass, Charsets.UTF_8)) }

    private val LOGINS get() = Plugins.list.value.map { "plugin_" + it.id } + "davmusic"
    fun hasLogin(root: JSONObject) = root.keys().asSequence().any { it in setOf("ncm", "qq", "kugou", "davmusic") || it.startsWith("plugin_") }
    fun isEncrypted(root: JSONObject) = root.optString("enc") == "aes256-cbc" && root.has("data")

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun deriveKeys(password: String, salt: ByteArray): Pair<ByteArray, String> {
        val base = password.encodeToByteArray() + byteArrayOf(0) + salt
        var h1 = hexBytes(Hash.md5(base + "k1".encodeToByteArray()))
        var h2 = hexBytes(Hash.md5(base + "k2".encodeToByteArray()))
        repeat(1500) {
            h1 = hexBytes(Hash.md5(h1 + base))
            h2 = hexBytes(Hash.md5(h2 + base))
        }
        val key = h1 + h2
        val macKey = Hash.md5(key + salt + "mac".encodeToByteArray())
        return key to macKey
    }

    /** 使用口令将备份 JSON 封装为 AES-256-CBC 密文信封（口令为空时原样返回） */
    @OptIn(ExperimentalEncodingApi::class)
    fun seal(root: JSONObject, password: String): JSONObject {
        val pwd = password.trim()
        if (pwd.isEmpty()) return root
        val salt = Random.nextBytes(16)
        val iv = Random.nextBytes(16)
        val (key, macKey) = deriveKeys(pwd, salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(root.toString().encodeToByteArray())
        val ctB64 = Base64.encode(ct)
        val saltB64 = Base64.encode(salt)
        val ivB64 = Base64.encode(iv)
        val mac = Hash.md5("$macKey|$saltB64|$ivB64|$ctB64")
        return JSONObject()
            .put("v", 2)
            .put("enc", "aes256-cbc")
            .put("login", hasLogin(root))
            .put("at", root.optLong("at"))
            .put("salt", saltB64)
            .put("iv", ivB64)
            .put("mac", mac)
            .put("data", ctB64)
    }

    /** 解密密文备份信封并校验完整性 */
    @OptIn(ExperimentalEncodingApi::class)
    fun unseal(root: JSONObject, password: String): JSONObject {
        if (!isEncrypted(root)) return root
        val pwd = password.trim()
        require(pwd.isNotEmpty()) { "该备份已加密，请输入解密密码" }
        val saltB64 = root.getString("salt")
        val ivB64 = root.getString("iv")
        val ctB64 = root.getString("data")
        val expectMac = root.optString("mac")
        val salt = Base64.decode(saltB64)
        val iv = Base64.decode(ivB64)
        val (key, macKey) = deriveKeys(pwd, salt)
        val actualMac = Hash.md5("$macKey|$saltB64|$ivB64|$ctB64")
        if (expectMac.isEmpty() || actualMac != expectMac) throw Exception("备份密码不正确或文件已损坏")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val plain = runCatching { cipher.doFinal(Base64.decode(ctB64)).decodeToString() }.getOrElse { throw Exception("备份密码不正确或文件已损坏") }
        return JSONObject(plain)
    }

    fun dump(login: Boolean = withLogin): JSONObject {
        val root = JSONObject().put("v", 1).put("from", Plat.impl.name).put("at", System.currentTimeMillis())
        for (name in SETS + if (login) LOGINS else emptyList()) {
            val o = JSONObject()
            Plat.prefs(name).all().forEach { (k, v) ->
                when (v) {
                    is Set<*> -> o.put(k, JSONArray(v.map { it.toString() }))
                    is Long -> o.put(k, JSONObject().put("L", v))
                    is Boolean, is Int, is Float, is Double -> o.put(k, v)
                    null -> {}
                    else -> o.put(k, v.toString())
                }
            }
            root.put(name, o)
        }
        root.put("recents", JSONArray(Store.recentEntries.value.filter { !it.track.id.startsWith("local:") }.map { it.toJson() }))
        root.put("scripts", JSONObject().apply { Plugins.list.value.forEach { put(it.id, it.file.readText()) } })
        root.put("locallib", LocalLib.dump())
        root.put("stats", Stats.dump())
        return root
    }

    suspend fun upload(password: String = cryptPass) = withContext(Dispatchers.IO) {
        val payload = seal(dump(), password)
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        Ncm.http.newCall(req(target()).put(body).build()).execute().use { r -> if (!r.isSuccessful) throw Exception("HTTP ${r.code}") }
        last = System.currentTimeMillis(); save()
    }

    suspend fun restore(password: String = cryptPass): Long = withContext(Dispatchers.IO) {
        val text = Ncm.http.newCall(req(target()).get().build()).execute().use { r -> if (!r.isSuccessful) throw Exception(if (r.code == 404) "还没有备份" else "HTTP ${r.code}"); r.body!!.string() }
        val root = unseal(JSONObject(text), password)
        apply(root)
    }

    suspend fun apply(root: JSONObject): Long = withContext(Dispatchers.IO) {
        if (!root.has("v") || !root.has("ui")) throw Exception("这不是 NEKOPLAY 的配置文件")
        root.optJSONObject("scripts")?.let { sc -> sc.keys().forEach { id -> runCatching { Plugins.import(sc.getString(id)) } } }
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
        root.optJSONArray("recents")?.let { a -> val l = (0 until a.length()).map { RecentEntry.fromJson(a.getJSONObject(it)) }; withContext(Dispatchers.Main) { Store.restoreRecentEntries(l) } }
        if (listOf("ncm", "qq", "kugou").any { root.has(it) }) { Plat.prefs("plugins").edit().putBoolean("migrated", false).apply(); LegacyLogin.migrate() }
        root.optString("locallib").takeIf { it.isNotEmpty() }?.let { LocalLib.restore(it) }
        root.optString("stats").takeIf { it.isNotEmpty() }?.let { Stats.restore(it) }
        DavMusic.load()
        Plugins.list.value.forEach { it.close() }; Plugins.load(); Store.reloadLiked(); Store.refreshAll()
        root.optLong("at")
    }
}
