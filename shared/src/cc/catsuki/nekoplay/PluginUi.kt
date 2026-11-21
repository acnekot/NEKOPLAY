package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 能力名 → 给人看的说明 */
private val CAP_NAMES = linkedMapOf(
    "search" to "搜索", "stream" to "播放", "lyric" to "歌词", "playlist" to "歌单", "album" to "专辑", "artist" to "歌手",
    "toplists" to "排行榜", "myPlaylists" to "我的歌单", "like" to "收藏", "loginQr" to "扫码登录",
)

/** 设置 → 插件：导入 / 开关 / 删除 JS 插件 */
@Composable fun PluginsPage() {
    val nav = LocalNav.current
    val p = c
    val scope = rememberCoroutineScope()
    val list by Plugins.list.collectAsState()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun done(r: Result<JsSource>) { busy = false; r.onSuccess { nav.toast = "已导入「${it.name}」${it.version}"; PluginStore.refresh() }.onFailure { nav.toast = "导入失败：${it.message}" } }
    val picker = Plat.impl.filePicker { bytes -> busy = true; scope.launch { done(runCatching { Plugins.import(bytes.decodeToString()) }) } }
    LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("PLUGINS"); Spacer(Modifier.height(4.dp))
                T("插件", Fs.HERO, w = 800, lh = 1.3f)
                T("在线音源和其它服务都以 JS 插件的形式导入，本体不带任何音源。插件能做什么由它实现的接口决定，搜索、首页、曲库会自动出现对应的功能", 13, p.s, lines = 4, lh = 1.5f)
                Spacer(Modifier.height(14.dp))
                ActionRow {
                    if (picker != null) PrimaryBar(if (busy) "导入中…" else "从文件导入", NIcons.plus, Modifier.weight(1f)) { if (!busy) picker() }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(44.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (url.isEmpty()) T("插件地址 https://…/xxx.js", 13, p.s, mono = true)
                        BasicTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = 13.sp, fontFamily = Mono), cursorBrush = SolidColor(p.p))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlineBtn("从网址导入", pad = 14.dp) {
                        if (busy || !url.trim().startsWith("http")) return@OutlineBtn
                        busy = true; scope.launch { done(runCatching { Plugins.importUrl(url) }.onSuccess { url = "" }) }
                    }
                }
                Spacer(Modifier.height(18.dp)); Rule()
                if (list.isEmpty()) T("还没有插件。没有插件时只能播放本地音乐和 WebDAV 里的歌", 13, p.s, lines = 3, modifier = Modifier.padding(vertical = 22.dp))
            }
        }
        items(list, key = { it.id }) { s ->
            val on = Plugins.on(s.id)
            Column(Modifier.fillMaxWidth().bottomLine().padding(vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T(s.name, 16, if (on) p.k else p.s, 800)
                            Spacer(Modifier.width(8.dp)); T("v" + s.version, 11, p.s, mono = true)
                        }
                        val sub = listOfNotNull(s.meta["author"]?.let { "作者 $it" }, s.meta["desc"]).joinToString(" · ")
                        if (sub.isNotEmpty()) T(sub, 12, p.s, lines = 2, lh = 1.4f)
                    }
                    NSwitch(on) { v -> Plugins.set(s.id, v); PluginStore.refresh() }
                }
                Spacer(Modifier.height(6.dp))
                val caps = CAP_NAMES.filterKeys { s.has(it) }.values
                when {
                    s.error != null -> T("出错：${s.error}", 12, p.bad, lines = 3)
                    caps.isEmpty() -> T("还没加载（用到时自动加载）", 11, p.s, mono = true)
                    else -> T(caps.joinToString(" · "), 11, p.s, mono = true, lines = 2)
                }
                Row(Modifier.padding(top = 6.dp)) {
                    val acc by PluginStore.accounts.collectAsState()
                    if (on && s.has("loginQr")) {
                        if (acc[s.id] == null) LinkBtn("登录", 13) { nav.push(Page.PluginLogin(s.id)) }
                        else LinkBtn("退出 ${acc[s.id]}", 13) { confirmLogout(nav, s.name) { scope.launch { runCatching { s.call("logout") }; PluginStore.refresh() } } }
                        Spacer(Modifier.width(14.dp))
                    }
                    LinkBtn("重新加载", 13) { scope.launch { s.close(); if (s.start()) nav.toast = "已重新加载" else nav.toast = "加载失败：${s.error}" } }
                    Spacer(Modifier.width(14.dp))
                    LinkBtn("删除", 13, p.bad) {
                        nav.sheet = SheetSpec("删除插件「${s.name}」？", "删除后它提供的搜索、歌单等功能都会消失；以后可以重新导入", listOf(SheetItem("删除", danger = true) { Plugins.delete(s.id); nav.toast = "已删除" }))
                    }
                }
            }
        }
        // 没加载过的插件进页面时顺便加载，好显示它的能力
        item { LaunchedEffect(list) { list.filter { Plugins.on(it.id) && it.caps.isEmpty() && it.error == null }.forEach { it.start() } } }
    }
}

/** 插件的扫码登录：loginQr 给出二维码（图片 base64 或文字），loginCheck 轮询状态 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable fun PluginLoginPage(pid: String) {
    val nav = LocalNav.current
    val p = c
    val s = Plugins.get(pid)
    var round by remember { mutableIntStateOf(0) }
    var img by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    LaunchedEffect(round) {
        img = null; status = "正在获取二维码…"
        try {
            val q = s?.call("loginQr") as? org.json.JSONObject ?: throw NcmError("插件不支持扫码登录")
            val bytes = q.optString("image").takeIf { it.isNotEmpty() }?.let { kotlin.io.encoding.Base64.decode(it) }
            img = if (bytes != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { val f = java.io.File(Plat.impl.cacheDir, "plugin_qr"); f.writeBytes(bytes); Plat.impl.decodeImage(f.path, 600) }
                else q.optString("text").takeIf { it.isNotEmpty() }?.let { qrBitmap(it) } ?: throw NcmError("插件没有给出二维码")
            status = q.optString("hint").ifEmpty { "扫码并在手机上确认" }
            val key = q.optString("key")
            while (true) {
                kotlinx.coroutines.delay(2000)
                val r = s.call("loginCheck", key) as? org.json.JSONObject ?: continue
                when (r.optString("state")) {
                    "scanned" -> status = "已扫码，请在手机上确认"
                    "expired" -> { status = "二维码已过期，点二维码刷新"; break }
                    "refused" -> { status = "已在手机上取消，点二维码重试"; break }
                    "done" -> { nav.toast = "${s.name} 登录成功"; PluginStore.refresh(); nav.back(); break }
                }
                r.optString("message").takeIf { it.isNotEmpty() }?.let { status = it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { status = "出错了：${e.message}（点二维码重试）" }
    }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        TagRow((s?.name ?: pid).uppercase()); Spacer(Modifier.height(18.dp))
        Box(Modifier.size(240.dp).align(Alignment.CenterHorizontally).nb(1.dp, p.k).background(androidx.compose.ui.graphics.Color.White).press { round++ }, contentAlignment = Alignment.Center) {
            img?.let { androidx.compose.foundation.Image(it, "二维码", Modifier.size(220.dp)) } ?: T("…", 20, androidx.compose.ui.graphics.Color.Black)
        }
        Spacer(Modifier.height(14.dp)); T(status, 13, p.s, lines = 3, lh = 1.5f)
        if (Plat.impl.name != "desktop") img?.let { im ->
            Box(Modifier.padding(top = 12.dp).press { nav.toast = runCatching { "已保存到" + Plat.impl.savePicture(im, "登录二维码") }.getOrElse { "保存失败：${it.message}" } }
                .nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 8.dp)) { T("保存二维码", 13, p.k, 700) }
        }
    }
}

/** 插件账号和「我的歌单」：曲库里每个登录了的插件一段 */
object PluginStore {
    val accounts = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())      // 插件 id → 账号名
    val playlists = kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<Playlist>>>(emptyMap())
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    fun refresh() = io.launch {
        val acc = HashMap<String, String>(); val pls = HashMap<String, List<Playlist>>()
        for (s in Plugins.enabled) {
            if (!s.start() || !s.has("account")) continue
            val a = runCatching { s.call("account") as? org.json.JSONObject }.getOrNull() ?: continue
            acc[s.id] = a.optString("name")
            if (s.has("myPlaylists")) runCatching { Plugins.myPlaylists(s.id) }.onSuccess { pls[s.id] = it }
        }
        accounts.value = acc; playlists.value = pls
    }
}

/** 曲库里的插件区：登录了的显示歌单，没登录的显示登录入口 */
fun androidx.compose.foundation.lazy.LazyListScope.pluginLibrary(nav: Nav) {
    item {
        val list by Plugins.list.collectAsState()
        val acc by PluginStore.accounts.collectAsState()
        val pls by PluginStore.playlists.collectAsState()
        Column {
            list.filter { Plugins.on(it.id) && (it.has("myPlaylists") || it.has("loginQr")) }.forEach { s ->
                Column(Modifier.padding(top = 26.dp, bottom = 8.dp)) { TagRow(s.name.uppercase(), 12.dp) { acc[s.id]?.let { T(it, 13, c.s) } } }
                if (acc[s.id] == null && s.has("loginQr")) ListRow({ IconCover(NIcons.chevronRight, 56.dp) }, "登录${s.name}", "登录后在这里看到它的歌单") { nav.push(Page.PluginLogin(s.id)) }
                (pls[s.id] ?: emptyList()).forEach { pl ->
                    ListRow({ if (pl.liked) LikedCover(pl.cover, 56.dp) else Cover(pl.cover, 56.dp, pl.name) }, pl.name, (if (pl.mine) "" else "歌单 · ") + "${two(pl.count)} 首") { nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) }
                }
            }
        }
    }
}
