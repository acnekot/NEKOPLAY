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
    "toplists" to "排行榜", "myPlaylists" to "我的歌单", "like" to "收藏", "loginQr" to "扫码登录", "loginWeb" to "网页登录",
    "daily" to "每日推荐", "fm" to "私人 FM", "comments" to "评论", "recMvs" to "MV", "hotRadios" to "电台", "cloud" to "云盘",
    "manifest" to "功能声明", "onTrackChange" to "切歌监听", "onPlayState" to "播控监听", "transformLyric" to "歌词处理", "action" to "快捷动作",
)

/** 设置 → 插件：分为「音源插件」与「功能插件」两类管理 */
@Composable fun PluginsPage() {
    val nav = LocalNav.current
    val p = c
    val scope = rememberCoroutineScope()
    val list by Plugins.list.collectAsState()
    var tab by remember { mutableIntStateOf(0) } // 0 全部 / 1 音源插件 / 2 功能插件
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val shown = remember(list, tab) { when (tab) { 1 -> list.filter { it.isSource }; 2 -> list.filter { it.isFeature }; else -> list } }
    fun done(r: Result<JsSource>) { busy = false; r.onSuccess { nav.toast = "已导入${it.kind.label}「${it.name}」${it.version}"; Store.refreshAll() }.onFailure { nav.toast = "导入失败：${it.message}" } }
    val picker = Plat.impl.filePicker { bytes -> busy = true; scope.launch { done(runCatching { Plugins.import(bytes.decodeToString()) }) } }
    LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("PLUGINS"); Spacer(Modifier.height(4.dp))
                T("插件", Fs.HERO, w = 800, lh = 1.3f)
                T("插件分为「音源插件」（提供曲库、搜索、播放与歌词）和「功能插件」（监听播放状态、歌词后处理与扩展动作），本体不内置任何在线音源", 13, p.s, lines = 4, lh = 1.5f)
                Spacer(Modifier.height(12.dp))
                ChipRow(listOf("全部 (${list.size})", "音源插件 (${list.count { it.isSource }})", "功能插件 (${list.count { it.isFeature }})"), tab) { tab = it }
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
            }
        }
        pluginSubsSection(nav, tab)
        item {
            Column {
                val head = when (tab) { 1 -> "SOURCE PLUGINS · 音源插件"; 2 -> "FEATURE PLUGINS · 功能插件"; else -> "INSTALLED · 已安装" }
                Column(Modifier.padding(top = 26.dp, bottom = 4.dp)) { TagRow(head, 12.dp) { T("${two(shown.size)} 个", 13, p.s, mono = true) } }
                if (shown.isEmpty()) T(if (tab == 2) "还没有功能插件。可在脚本开头声明 // @type feature 导入扩展功能插件" else "还没有插件。没有音源插件时只能播放本地音乐和 WebDAV 里的歌", 13, p.s, lines = 3, modifier = Modifier.padding(vertical = 14.dp))
            }
        }
        items(shown, key = { it.id }) { s ->
            val on = Plugins.on(s.id)
            Column(Modifier.fillMaxWidth().bottomLine().padding(vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.background(if (s.isFeature) p.k else p.p).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                T(if (s.isFeature) "功能" else "音源", 9, if (s.isFeature) p.tagFg else androidx.compose.ui.graphics.Color.White, 800)
                            }
                            Spacer(Modifier.width(7.dp)); T(s.name, 16, if (on) p.k else p.s, 800)
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
                    if (on && s.isFeature && s.has("action")) {
                        LinkBtn("执行", 13) { scope.launch { runCatching { Plugins.runFeatureAction(s.id, Engine.state.value.current) }.onSuccess { r ->
                            if (r?.open == "coverflow") nav.push(Page.CoverFlow)
                            r?.message?.let { nav.toast = it }
                        }.onFailure { nav.toast = "执行失败：${it.message}" } } }
                        Spacer(Modifier.width(14.dp))
                    }
                    LinkBtn("重新加载", 13) { scope.launch { s.close().join(); if (s.start()) nav.toast = "已重新加载" else nav.toast = "加载失败：${s.error}" } }
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
    val mobile = Plat.impl.name != "desktop"
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()
    // 登录方式：插件的 loginModes()，没写就按有没有 loginWeb / loginQr 自动生成
    val modes by produceState<List<org.json.JSONObject>>(emptyList(), pid) {
        val l = runCatching { Plugins.arr(s?.takeIf { it.start() && it.has("loginModes") }?.call("loginModes")) }.getOrDefault(emptyList())
        value = l.filter { !it.optBoolean("web") || Plat.impl.hasWebView }.ifEmpty {
            listOfNotNull(if (s?.has("loginWeb") == true && Plat.impl.hasWebView) org.json.JSONObject().put("id", "web").put("name", "网页登录").put("web", true) else null,
                if (s?.has("loginQr") == true) org.json.JSONObject().put("id", "qr").put("name", "扫码登录") else null)
        }
    }
    var mode by remember { mutableStateOf<org.json.JSONObject?>(null) }
    // 桌面端优先默认原生扫码登录（秒开、零 WebView 依赖），移动端优先网页登录（可一键拉起 App）；仍可随时点上方切换
    LaunchedEffect(modes) {
        if (mode == null) mode = if (Plat.isDesktop) (modes.firstOrNull { !it.optBoolean("web") } ?: modes.firstOrNull()) else modes.firstOrNull()
    }
    var round by remember { mutableIntStateOf(0) }
    var img by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var qrInfo by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var status by remember { mutableStateOf("") }
    var ck by remember { mutableStateOf("") }
    fun done() { nav.toast = "${s?.name} 登录成功"; PluginStore.refresh(); if (pid == Plugins.main()?.id) Store.refreshAll(); nav.back() }
    val m = mode
    val web = m?.optBoolean("web") == true
    LaunchedEffect(m?.optString("id"), round) {
        if (m == null || web || s == null) return@LaunchedEffect
        img = null; qrInfo = null; status = "正在获取二维码…"
        try {
            val q = s.call("loginQr", m.optString("id")) as? org.json.JSONObject ?: throw NcmError("插件不支持扫码登录")
            val bytes = q.optString("image").takeIf { it.isNotEmpty() }?.let { kotlin.io.encoding.Base64.decode(it) }
            img = if (bytes != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { val f = java.io.File(Plat.impl.cacheDir, "plugin_qr"); f.writeBytes(bytes); Plat.impl.decodeImage(f.path, 600) }
                else q.optString("text").takeIf { it.isNotEmpty() }?.let { qrBitmap(it) } ?: throw NcmError("插件没有给出二维码")
            qrInfo = q
            status = q.optString("hint").ifEmpty { "扫码并在手机上确认" }
            val key = q.optString("key")
            var fails = 0
            while (true) {
                kotlinx.coroutines.delay(q.optLong("poll", 2000))
                // 切去手机确认时 App 会被挂起，回来第一次请求可能失败：网络错误重试几次
                val r = try { s.call("loginCheck", key) as? org.json.JSONObject ?: continue } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (++fails > 5 || e.message?.startsWith("网络错误") != true) throw e; continue }
                fails = 0
                when (r.optString("state")) {
                    "scanned" -> status = "已扫码，请在手机上确认"
                    "expired" -> { status = "二维码已过期，点二维码刷新"; break }
                    "refused" -> { status = "已在手机上取消，点二维码重试"; break }
                    "done" -> { done(); break }
                }
                r.optString("message").takeIf { it.isNotEmpty() }?.let { status = it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { status = "出错了：${e.message}（点二维码重试）" }
    }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        TagRow((s?.name ?: pid).uppercase()); Spacer(Modifier.height(14.dp))
        if (modes.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { md ->
                val on = md.optString("id") == m?.optString("id")
                Box(Modifier.press { if (!on) mode = md }.background(if (on) p.k else androidx.compose.ui.graphics.Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 6.dp)) { T(md.optString("name"), 13, if (on) p.tagFg else p.k, 700) }
            }
        }
        if (s == null) { T("插件没有安装", 13, p.s); return@Column }
        if (web) { Spacer(Modifier.height(12.dp)); PluginWebLogin(s, Modifier.weight(1f)) { done() }; return@Column }
        Spacer(Modifier.height(18.dp))
        if (m != null) {
            Box(Modifier.size(240.dp).align(Alignment.CenterHorizontally).nb(1.dp, p.k).background(androidx.compose.ui.graphics.Color.White).press { round++ }, contentAlignment = Alignment.Center) {
                img?.let { androidx.compose.foundation.Image(it, "二维码", Modifier.size(220.dp)) } ?: T("…", 20, androidx.compose.ui.graphics.Color.Black)
            }
            Spacer(Modifier.height(14.dp)); T(status, 13, p.s, lines = 3, lh = 1.5f)
            if (mobile) img?.let { im ->
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 同一台手机：存图后直接打开扫码的 App，在里面选相册
                    val app = qrInfo?.optString("saveApp")?.ifEmpty { null }
                    if (app != null) Box(Modifier.press {
                        runCatching { Plat.impl.savePicture(im, "登录二维码") }.onFailure { nav.toast = "保存失败：${it.message}"; return@press }
                        status = "二维码已存到相册：在${qrInfo?.optString("saveAppName")}扫一扫里点「相册」选它，确认后回到这里"
                        runCatching { uri.openUri(app) }.onFailure { nav.toast = "没能打开${qrInfo?.optString("saveAppName")}" }
                    }.background(p.k).padding(horizontal = 14.dp, vertical = 8.dp)) { T("存图并打开${qrInfo?.optString("saveAppName")}", 13, p.tagFg, 700) }
                    Box(Modifier.press { nav.toast = runCatching { "已保存到" + Plat.impl.savePicture(im, "登录二维码") }.getOrElse { "保存失败：${it.message}" } }
                        .nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 8.dp)) { T("保存二维码", 13, p.k, 700) }
                }
            }
        }
        // 粘贴 Cookie：插件实现了 setCookie 才有
        if (s.has("setCookie")) {
            Spacer(Modifier.height(28.dp)); TagRow("COOKIE"); Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(44.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (ck.isEmpty()) T("粘贴 Cookie", 13, p.s, mono = true)
                BasicTextField(ck, { ck = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = Fs.SUB.sp, fontFamily = Mono), cursorBrush = SolidColor(p.p))
            }
            Spacer(Modifier.height(10.dp))
            PrimaryBar("保存 Cookie", NIcons.chevronRight, Modifier.fillMaxWidth()) {
                scope.launch { try { s.call("setCookie", ck); done() } catch (e: Exception) { nav.toast = e.message } }
            }
        }
    }
}

/** 网页登录：内嵌插件给的登录页，跳到 done 规定的地址时交给插件换凭证；open 规定的地址交给系统（拉起别的 App） */
@Composable private fun PluginWebLogin(s: JsSource, modifier: Modifier, onDone: () -> Unit) {
    val p = c
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()
    // desktop：电脑版用电脑网页（扫码 / 本机 QQ 快捷登录），手机用手机网页（拉起 App）
    val cfg by produceState<org.json.JSONObject?>(null, s.id) { value = runCatching { s.call("loginWeb", org.json.JSONObject().put("desktop", Plat.isDesktop)) as? org.json.JSONObject }.getOrNull() }
    val c0 = cfg ?: run { Box(modifier) { T("LOADING", 12, p.s, mono = true) }; return }
    var url by remember(c0) { mutableStateOf(c0.optString("url")) }
    var status by remember(c0) { mutableStateOf(c0.optString("hint")) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    val doneRe = remember(c0) { c0.optString("done").takeIf { it.isNotEmpty() }?.let { Regex(it) } }
    val openRe = remember(c0) { c0.optString("open").takeIf { it.isNotEmpty() }?.let { Regex(it) } }
    // 别的 App 跳回 nekoplay://…：交给插件算出要继续加载的网页
    val back by DeepLink.url.collectAsState()
    LaunchedEffect(back) {
        val b = back ?: return@LaunchedEffect
        DeepLink.url.value = null
        val next = runCatching { if (s.has("loginWebBack")) s.call("loginWebBack", b) as? String else null }.getOrNull()
        if (next != null) { url = next; status = "已返回，正在继续登录…" } else status = "已返回，但没带回登录信息（$b）"
    }
    Column(modifier) {
        T(status, 13, p.s, lines = 3, lh = 1.5f)
        if (info.isNotEmpty()) T(info, 12, p.s, lines = 2, mono = true)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f).nbf(1.dp, p.k)) {
            Plat.impl.WebPage(url, Modifier.fillMaxSize(), desktopUa = c0.optBoolean("pc"), onInfo = { info = it }, stopOn = c0.optString("done").ifEmpty { null }) { u ->
                when {
                    doneRe?.containsMatchIn(u) == true -> {
                        if (!busy) { busy = true; scope.launch {
                            status = "正在换取凭证…"
                            try { s.call("loginWebDone", u); onDone() }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { status = "出错了：${e.message}"; busy = false; url = c0.optString("url") + (if ('?' in c0.optString("url")) "&" else "?") + "r=" + System.currentTimeMillis() }
                        } }
                        true
                    }
                    openRe?.containsMatchIn(u) == true -> {
                        status = "正在打开 App…"
                        scope.launch {
                            val ios = Plat.impl.name == "ios"
                            val app = org.json.JSONObject().put("ios", ios).put("id", if (ios) "cc.catsuki.nekoplay" else "cc.catsuki.nekoplay.native").put("scheme", "nekoplay://")
                            val target = runCatching { if (s.has("loginWebOpen")) s.call("loginWebOpen", u, app) as? String else null }.getOrNull() ?: u
                            runCatching { uri.openUri(target) }.onFailure { status = "没能打开对应的 App（没装？）" }
                        }
                        true
                    }
                    u.startsWith("http") || u.startsWith("about:") -> false
                    else -> true   // 其它 App 的跳转一律不处理
                }
            }
        }
    }
}

/** 曲库里的插件区：登录了的显示歌单，没登录的显示登录入口 */
fun androidx.compose.foundation.lazy.LazyListScope.pluginLibrary(nav: Nav, except: String? = null) {
    item {
        val list by Plugins.list.collectAsState()
        val acc by PluginStore.accounts.collectAsState()
        val pls by PluginStore.playlists.collectAsState()
        Column {
            list.filter { it.id != except && Plugins.on(it.id) && (it.has("myPlaylists") || it.has("loginQr")) }.forEach { s ->
                Column(Modifier.padding(top = 26.dp, bottom = 8.dp)) { TagRow(s.name.uppercase(), 12.dp) { acc[s.id]?.let { T(it, 13, c.s) } } }
                if (acc[s.id] == null && s.has("loginQr")) ListRow({ IconCover(NIcons.chevronRight, 56.dp) }, "登录${s.name}", "登录后在这里看到它的歌单") { nav.push(Page.PluginLogin(s.id)) }
                (pls[s.id] ?: emptyList()).forEach { pl ->
                    ListRow({ if (pl.liked) LikedCover(pl.cover, 56.dp) else Cover(pl.cover, 56.dp, pl.name) }, pl.name, (if (pl.mine) "" else "歌单 · ") + "${two(pl.count)} 首") { nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) }
                }
            }
        }
    }
}


/** 插件页里的订阅区：订阅地址管理 + 订阅里可以安装 / 更新的插件（跟随上方音源/功能分类筛选） */
private fun androidx.compose.foundation.lazy.LazyListScope.pluginSubsSection(nav: Nav, tab: Int) {
    item(key = "subs") {
        val p = c
        val scope = rememberCoroutineScope()
        val list by Plugins.list.collectAsState()
        var adding by remember { mutableStateOf(false) }
        var subUrl by remember { mutableStateOf("") }
        var working by remember { mutableStateOf<String?>(null) }   // 正在安装的插件 id
        LaunchedEffect(Unit) { if (PluginSubs.subs.isEmpty()) runCatching { PluginSubs.refresh() } }
        fun install(i: PluginSubs.Item) {
            if (working != null) return
            working = i.id
            scope.launch {
                runCatching { PluginSubs.install(i) }.onSuccess { nav.toast = "已安装「${it.name}」${it.version}"; Store.refreshAll() }.onFailure { nav.toast = "安装失败：${it.message}" }
                working = null
            }
        }
        Column {
            Column(Modifier.padding(top = 26.dp, bottom = 4.dp)) {
                TagRow("SUBSCRIPTIONS", 12.dp) {
                    LinkBtn(if (PluginSubs.loading) "刷新中…" else "刷新", 13) { if (!PluginSubs.loading) scope.launch { PluginSubs.refresh() } }
                    Spacer(Modifier.width(12.dp))
                    LinkBtn(if (adding) "收起" else "添加订阅", 13) { adding = !adding }
                }
            }
            T("从订阅列表一键安装、更新插件", 12, p.s)
            if (adding) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(44.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (subUrl.isEmpty()) T("订阅地址 https://…/index.json", 13, p.s, mono = true)
                        BasicTextField(subUrl, { subUrl = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = 13.sp, fontFamily = Mono), cursorBrush = SolidColor(p.p))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlineBtn("添加", pad = 14.dp) {
                        runCatching { PluginSubs.add(subUrl) }.onSuccess { subUrl = ""; adding = false; scope.launch { PluginSubs.refresh() } }.onFailure { nav.toast = it.message }
                    }
                }
            }
            // 更新提示
            val ups = PluginSubs.updates
            if (ups.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                T("${ups.size} 个插件有新版本", 13, p.p, 700, modifier = Modifier.weight(1f))
                LinkBtn("全部更新", 13) {
                    if (working == null) { working = "*"; scope.launch {
                        val r = ups.map { runCatching { PluginSubs.install(it) } }
                        nav.toast = "已更新 ${r.count { it.isSuccess }} 个" + r.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { "，失败：${it.message}" }.orEmpty()
                        Store.refreshAll(); working = null
                    } }
                }
            }
            PluginSubs.urls.forEach { u ->
                val sub = PluginSubs.subs[u]
                Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T(sub?.name ?: "LOADING", 14, w = 800)
                            T(u, 11, p.s, mono = true, lines = 1)
                        }
                        LinkBtn("移除", 12, p.bad) {
                            nav.sheet = SheetSpec("移除这个订阅？", u, listOf(SheetItem("移除", danger = true) { PluginSubs.remove(u) }))
                        }
                    }
                    sub?.error?.let { T("拉取失败：$it", 12, p.bad, lines = 2) }
                    sub?.items?.filter { when (tab) { 1 -> !it.isFeature; 2 -> it.isFeature; else -> true } }?.forEach { i ->
                        val have = list.firstOrNull { it.id == i.id }
                        val upd = have != null && PluginSubs.newer(i.version, have.version)
                        Row(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.background(if (i.isFeature) p.k else p.p).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                        T(if (i.isFeature) "功能" else "音源", 9, if (i.isFeature) p.tagFg else androidx.compose.ui.graphics.Color.White, 800)
                                    }
                                    Spacer(Modifier.width(7.dp)); T(i.name, 14, w = 700); Spacer(Modifier.width(8.dp)); T("v" + i.version, 11, p.s, mono = true)
                                }
                                val line = listOfNotNull(i.author.takeIf { it.isNotEmpty() }?.let { "作者 $it" }, i.desc.takeIf { it.isNotEmpty() }).joinToString(" · ")
                                if (line.isNotEmpty()) T(line, 12, p.s, lines = 2, lh = 1.4f)
                            }
                            Spacer(Modifier.width(10.dp))
                            when {
                                working == i.id || (working == "*" && upd) -> T("安装中…", 12, p.s)
                                have == null -> OutlineBtn("安装", pad = 12.dp) { install(i) }
                                upd -> OutlineBtn("更新到 v${i.version}", pad = 12.dp) { install(i) }
                                else -> T("已安装", 12, p.s)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp)); Rule()
        }
    }
}
