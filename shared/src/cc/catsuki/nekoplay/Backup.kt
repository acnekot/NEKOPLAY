package cc.catsuki.nekoplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlinx.coroutines.launch
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
    private val SETS = listOf("ui", "store", "fx", "lyricOffset")
    private val kv by lazy { Plat.prefs("webdav") }
    var url by mutableStateOf(""); var user by mutableStateOf(""); var pass by mutableStateOf("")
    var withLogin by mutableStateOf(false)
    var last by mutableLongStateOf(0L)
    fun load() { url = kv.getString("url", "") ?: ""; user = kv.getString("user", "") ?: ""; pass = kv.getString("pass", "") ?: ""; withLogin = kv.getBoolean("login", false); last = kv.getLong("last", 0) }
    fun save() { kv.edit().putString("url", url).putString("user", user).putString("pass", pass).putBoolean("login", withLogin).putLong("last", last).apply() }

    private fun target(): String { val u = url.trim(); require(u.startsWith("http")) { "地址要以 http:// 或 https:// 开头" }; return u.trimEnd('/') + "/nekoplay-backup.json" }
    private fun req(u: String) = Request.Builder().url(u).apply { if (user.isNotEmpty()) header("Authorization", Credentials.basic(user, pass, Charsets.UTF_8)) }

    private fun dump(): JSONObject {
        val root = JSONObject().put("v", 1).put("from", Plat.impl.name).put("at", System.currentTimeMillis())
        for (name in SETS + if (withLogin) listOf("ncm") else emptyList()) {
            val o = JSONObject()
            Plat.prefs(name).all().forEach { (k, v) ->
                when (v) {
                    is Set<*> -> o.put(k, JSONArray(v.map { it.toString() }))
                    is Boolean, is Int, is Long, is Float, is Double -> o.put(k, v)
                    null -> {}
                    else -> o.put(k, v.toString())
                }
            }
            root.put(name, o)
        }
        root.put("recents", JSONArray(Store.recents.value.filter { !it.local }.map { it.toJson() }))
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
        val root = JSONObject(text)
        for (name in SETS + if (withLogin) listOf("ncm") else emptyList()) {
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
                    else -> e.putString(k, v.toString())
                }
            }
            e.apply()
        }
        root.optJSONArray("recents")?.let { a -> val l = (0 until a.length()).map { Track.fromJson(a.getJSONObject(it)) }; withContext(Dispatchers.Main) { Store.restoreRecents(l) } }
        root.optLong("at")
    }
}

@Composable fun BackupPage() {
    val nav = LocalNav.current
    val p = c
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    @Composable fun Field(label: String, v: String, hint: String, secret: Boolean = false, set: (String) -> Unit) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            T(label, 13, p.s); Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(44.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (v.isEmpty()) T(hint, 13, p.s, mono = true)
                BasicTextField(v, { set(it); Backup.save() }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = 13.sp, fontFamily = Mono),
                    cursorBrush = SolidColor(p.p), visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("BACKUP"); Spacer(Modifier.height(4.dp))
                T("WebDAV 备份", Fs.HERO, w = 800, lh = 1.3f)
                T("把设置、主题、首页布局、效果器、歌词偏移、喜欢和最近播放存到你的 WebDAV 网盘，手机和电脑可以互相恢复", 13, p.s, lines = 3)
                Spacer(Modifier.height(10.dp))
                Field("地址（文件夹）", Backup.url, "https://example.com/dav/NEKOPLAY/") { Backup.url = it }
                Field("用户名", Backup.user, "") { Backup.user = it }
                Field("密码", Backup.pass, "", secret = true) { Backup.pass = it }
                SwitchRow("包含网易云登录", "恢复后不用重新登录；登录信息会以明文存在网盘里，介意就别开", Backup.withLogin) { Backup.withLogin = it; Backup.save() }
                Spacer(Modifier.height(14.dp))
                ActionRow {
                    PrimaryBar(if (busy) "处理中…" else "备份到网盘", NIcons.chevronRight, Modifier.weight(1f)) {
                        if (busy) return@PrimaryBar; busy = true
                        scope.launch { runCatching { Backup.upload() }.onSuccess { nav.toast = "已备份" }.onFailure { nav.toast = "备份失败：${it.message}" }; busy = false }
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlineBtn("从网盘恢复", pad = 14.dp) {
                        if (busy) return@OutlineBtn; busy = true
                        scope.launch {
                            runCatching { Backup.restore() }.onSuccess {
                                Boot.reloadPrefs()
                                nav.toast = "已恢复（备份于 ${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(it))}），部分设置重启后生效"
                            }.onFailure { nav.toast = "恢复失败：${it.message}" }
                            busy = false
                        }
                    }
                }
                if (Backup.last > 0) T("上次备份：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(Backup.last)), 12, p.s, mono = true, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}
