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
    "manifest" to "功能声明", "profile" to "动效引擎", "config" to "热更新配置", "render" to "独立页面", "onTrackChange" to "切歌监听", "onPlayState" to "播控监听", "transformLyric" to "歌词处理", "action" to "快捷动作",
)

/** 首次导入插件或订阅前的安全与责任确认提示（确认后持久化不再重复弹窗） */
internal fun confirmPluginNotice(nav: Nav, proceed: () -> Unit) {
    val prefs = Plat.prefs("ui")
    if (prefs.getBoolean("plugin_notice_ack", false)) { proceed(); return }
    nav.sheet = SheetSpec(
        "插件使用与安全提示",
        "NEKOPLAY 本体为中立本地播放器，插件脚本在本地沙箱运行。请仅导入可信来源的脚本，并自行遵守对应服务条款；官方插件仓库采用独立 MIT 协议。",
        listOf(SheetItem("我已知晓并继续") {
            prefs.edit().putBoolean("plugin_notice_ack", true).apply()
            proceed()
        }),
    )
}

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
                T(tr("插件"), Fs.HERO, w = 800, lh = 1.3f)
                T(tr("插件分为「音源插件」（提供曲库、搜索、播放与歌词）和「功能插件」（监听播放状态、歌词后处理与扩展动作），本体不内置任何在线音源"), 13, p.s, lines = 4, lh = 1.5f)
                Spacer(Modifier.height(12.dp))
                ChipRow(listOf("${tr("全部")} (${list.size})", "${tr("音源插件")} (${list.count { it.isSource }})", "${tr("功能插件")} (${list.count { it.isFeature }})"), tab) { tab = it }
                Spacer(Modifier.height(14.dp))
                ActionRow {
                    if (picker != null) PrimaryBar(if (busy) "导入中…" else "从文件导入", NIcons.plus, Modifier.weight(1f)) { if (!busy) confirmPluginNotice(nav) { picker() } }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(44.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (url.isEmpty()) T(tr("插件地址 https://…/xxx.js"), 13, p.s, mono = true)
                        BasicTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = 13.sp, fontFamily = Mono), cursorBrush = SolidColor(p.p))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlineBtn("从网址导入", pad = 14.dp) {
                        if (busy || !url.trim().startsWith("http")) return@OutlineBtn
                        confirmPluginNotice(nav) { busy = true; scope.launch { done(runCatching { Plugins.importUrl(url) }.onSuccess { url = "" }) } }
                    }
                }
                Spacer(Modifier.height(18.dp)); Rule()
            }
        }
        pluginSubsSection(nav, tab)
        item {
            Column {
                val head = when (tab) { 1 -> "SOURCE PLUGINS · ${tr("音源插件")}"; 2 -> "FEATURE PLUGINS · ${tr("功能插件")}"; else -> "INSTALLED · ${tr("已安装")}" }
                Column(Modifier.padding(top = 26.dp, bottom = 4.dp)) { TagRow(head, 12.dp) { T(trKnown("${two(shown.size)} 个"), 13, p.s, mono = true) } }
                if (shown.isEmpty()) T(tr(if (tab == 2) "还没有功能插件。可在脚本开头声明 // @type feature 导入扩展功能插件" else "还没有插件。没有音源插件时只能播放本地音乐和 WebDAV 里的歌"), 13, p.s, lines = 3, modifier = Modifier.padding(vertical = 14.dp))
            }
        }
        items(shown, key = { it.id }) { s ->
            val on = Plugins.on(s.id)
            Column(Modifier.fillMaxWidth().cascadeEnter().bottomLine().padding(vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val badge = tr(when (s.kind) { PluginKind.MOTION -> "动效"; PluginKind.PAGE -> "页面"; PluginKind.FEATURE -> "功能"; PluginKind.SOURCE -> "音源" })
                            Box(Modifier.background(if (s.isFeature) p.k else p.p).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                T(badge, 9, if (s.isFeature) p.tagFg else androidx.compose.ui.graphics.Color.White, 800)
                            }
                            Spacer(Modifier.width(7.dp)); T(s.name, 16, if (on) p.k else p.s, 800)
                            Spacer(Modifier.width(8.dp)); T("v" + s.version, 11, p.s, mono = true)
                        }
                        val sub = listOfNotNull(s.meta["author"]?.let { "${tr("作者")} $it" }, s.meta["desc"]).joinToString(" · ")
                        if (sub.isNotEmpty()) T(sub, 12, p.s, lines = 2, lh = 1.4f)
                    }
                    NSwitch(on) { v -> Plugins.set(s.id, v) }
                }
                Spacer(Modifier.height(6.dp))
                val caps = CAP_NAMES.filterKeys { s.has(it) }.values.map { tr(it) }
                when {
                    s.error != null -> T("${tr("出错")}：${s.error}", 12, p.bad, lines = 3)
                    caps.isEmpty() -> T(tr("还没加载（用到时自动加载）"), 11, p.s, mono = true)
                    else -> T(caps.joinToString(" · "), 11, p.s, mono = true, lines = 2)
                }
                if (s.permissions.isNotEmpty()) {
                    val permText = "${tr("权限")}：" + s.permissions.joinToString(" · ") + if (!s.canNetwork) tr("（仅本地，无联网）") else ""
                    T(permText, 10, p.s, mono = true, lines = 1, modifier = Modifier.padding(top = 3.dp))
                }
                Row(Modifier.padding(top = 6.dp)) {
                    val acc by PluginStore.accounts.collectAsState()
                    if (on && s.has("loginQr")) {
                        if (acc[s.id] == null) LinkBtn("登录", 13) { nav.push(Page.PluginLogin(s.id)) }
                        else LinkBtn("退出 ${acc[s.id]}", 13) { confirmLogout(nav, s.name) { scope.launch { runCatching { s.call("logout") }; PluginStore.refresh() } } }
                        Spacer(Modifier.width(14.dp))
                    }
                    if (on && s.has("render")) { LinkBtn("打开页面", 13) { nav.push(Page.PluginPage(s.id)) }; Spacer(Modifier.width(14.dp)) }
                    if (on && s.isFeature && s.has("action")) {
                        LinkBtn("执行", 13) { scope.launch { runCatching { Plugins.runFeatureAction(s.id, Engine.state.value.current) }.onSuccess { r ->
                            val op = r?.open
                            if (op == "coverflow") nav.push(Page.CoverFlow) else if (op?.startsWith("plugin:") == true) nav.push(Page.PluginPage(op.removePrefix("plugin:")))
                            r?.message?.let { nav.toast = it }
                        }.onFailure { nav.toast = "执行失败：${it.message}" } } }
                        Spacer(Modifier.width(14.dp))
                    }
                    LinkBtn("重新加载", 13) { scope.launch { s.close().join(); if (s.start()) { Plugins.bump(); PluginStore.refresh().join(); Store.refreshAll(); nav.toast = "已重新加载" } else nav.toast = "加载失败：${s.error}" } }
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
