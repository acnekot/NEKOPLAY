package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 能力名 → 给人看的说明 */
internal val CAP_NAMES = linkedMapOf(
    "search" to "搜索", "stream" to "播放", "lyric" to "歌词", "playlist" to "歌单", "album" to "专辑", "artist" to "歌手",
    "toplists" to "排行榜", "myPlaylists" to "我的歌单", "like" to "收藏", "loginQr" to "扫码登录", "loginWeb" to "网页登录", "setCookie" to "Cookie 登录",
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

/** 插件中心当前标签：放在页面外面，进详情页再返回时停留在原来的标签 */
private var pluginTab by mutableIntStateOf(0)

internal fun kindLabel(kind: String): String = when (kind.lowercase()) { "motion" -> "动效插件"; "page" -> "页面插件"; "feature" -> "功能插件"; else -> "音源插件" }
internal fun kindIcon(kind: String): String = when (kind.lowercase()) { "motion" -> NIcons.shift; "page" -> NIcons.image; "feature" -> NIcons.gear; else -> NIcons.disc }

/** 设置 → 插件：已安装 / 发现 / 订阅与导入 三个标签；单个插件的能力、权限、登录、删除都放在详情页 */
@Composable fun PluginsPage() {
    val nav = LocalNav.current
    val p = c
    val scope = rememberCoroutineScope()
    val list by Plugins.list.collectAsState()
    LaunchedEffect(Unit) { if (PluginSubs.subs.isEmpty()) runCatching { PluginSubs.refresh() } }
    val avail = remember(PluginSubs.subs, list) { PluginSubs.subs.values.flatMap { it.items }.distinctBy { it.id }.filter { i -> list.none { it.id == i.id } } }
    val ups = PluginSubs.updates
    var updating by remember { mutableStateOf(false) }
    LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 32.dp)) {
        item {
            Column {
                Tag("PLUGINS"); Spacer(Modifier.height(4.dp))
                T(tr("插件"), Fs.HERO, w = 800, lh = 1.3f)
                T(tr("音源插件提供曲库、搜索和播放；功能插件给播放器加新本事。本体不内置任何在线音源。"), 14, p.s, lines = 3, lh = 1.5f)
                if (ups.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 16.dp).background(p.p.copy(alpha = .08f)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(trKnown("${ups.size} 个插件有新版本"), 15, p.p, 700)
                        T(ups.joinToString("、") { it.name }, 12, p.s, lines = 2)
                    }
                    OutlineBtn(if (updating) "更新中…" else "全部更新", height = 40.dp, size = 13, pad = 14.dp) {
                        if (!updating) { updating = true; scope.launch {
                            val r = ups.map { runCatching { PluginSubs.install(it) } }
                            nav.toast = "已更新 ${r.count { it.isSuccess }} 个" + r.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { "，失败：${it.message}" }.orEmpty()
                            Store.refreshAll(); updating = false
                        } }
                    }
                }
                Spacer(Modifier.height(18.dp))
                ChipRow(listOf("${tr("已安装")} ${list.size}", "${tr("发现")} ${avail.size}", tr("订阅与导入")), pluginTab) { pluginTab = it }
                Spacer(Modifier.height(6.dp))
            }
        }
        when (pluginTab) {
            0 -> installedSection(nav, list)
            1 -> discoverSection(nav, avail)
            else -> subsSection(nav)
        }
        // 没加载过的插件进页面时顺便加载，好显示状态
        item { LaunchedEffect(list) { list.filter { Plugins.on(it.id) && it.caps.isEmpty() && it.error == null }.forEach { it.start() } } }
    }
}

/** 已安装：按类型分组，每行只放名字、一句话简介、状态和开关 */
private fun androidx.compose.foundation.lazy.LazyListScope.installedSection(nav: Nav, list: List<JsSource>) {
    if (list.isEmpty()) item {
        Column(Modifier.padding(vertical = 28.dp)) {
            T(tr("还没有安装插件"), 16, w = 700)
            T(tr("没有音源插件时只能播放本地音乐和 WebDAV 里的歌。到「发现」里一键安装。"), 13, c.s, lines = 3, lh = 1.5f, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp)); OutlineBtn("去发现", height = 42.dp, size = 14, pad = 16.dp) { pluginTab = 1 }
        }
    }
    listOf("source", "feature", "page", "motion").forEach { k ->
        val g = list.filter { it.kind.name.lowercase() == k }
        if (g.isEmpty()) return@forEach
        item(key = "h_$k") { Column(Modifier.padding(top = 22.dp, bottom = 2.dp)) { TagRow(tr(kindLabel(k)), 12.dp) { T(two(g.size), 13, c.s, mono = true) } } }
        items(g, key = { it.id }) { s -> InstalledRow(nav, s) }
    }
}

@Composable private fun InstalledRow(nav: Nav, s: JsSource) {
    val p = c
    val on = Plugins.on(s.id)
    val acc by PluginStore.accounts.collectAsState()
    val upd = PluginSubs.itemOf(s.id)?.takeIf { PluginSubs.newer(it.version, s.version) }
    val (status, tone) = when {
        s.error != null -> tr("出错，点开查看") to p.bad
        !on -> tr("已停用") to p.s
        upd != null -> trKnown("有新版本 v${upd.version}") to p.p
        s.canLogin && acc[s.id] == null -> tr("已启用 · 未登录") to p.s
        s.canLogin -> trKnown("已登录 ${acc[s.id]}") to p.k
        else -> tr("已启用") to p.k
    }
    Row(Modifier.fillMaxWidth().cascadeEnter().bottomLine().press { nav.push(Page.PluginDetail(s.id)) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconCover(kindIcon(s.kind.name), 48.dp, filled = on, iconSize = 20.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(s.name, 16, if (on) p.k else p.s, 700, modifier = Modifier.weight(1f, fill = false)); Spacer(Modifier.width(8.dp)); T("v" + s.version, 11, p.s, mono = true)
            }
            s.meta["desc"]?.let { T(it, 13, p.s, lines = 2, lh = 1.45f, modifier = Modifier.padding(top = 2.dp)) }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(tone)); Spacer(Modifier.width(6.dp)); T(status, 12, tone, 600, lines = 1)
            }
        }
        Spacer(Modifier.width(12.dp))
        NSwitch(on) { v -> Plugins.set(s.id, v) }
    }
}
/** 曲库里的插件区：登录了的显示歌单，没登录的显示登录入口 */
fun androidx.compose.foundation.lazy.LazyListScope.pluginLibrary(nav: Nav, except: String? = null) {
    item {
        val list by Plugins.list.collectAsState()
        val acc by PluginStore.accounts.collectAsState()
        val pls by PluginStore.playlists.collectAsState()
        Column {
            list.filter { it.id != except && Plugins.on(it.id) && (it.has("myPlaylists") || it.canLogin) }.forEach { s ->
                Column(Modifier.padding(top = 26.dp, bottom = 8.dp)) { TagRow(s.name.uppercase(), 12.dp) { acc[s.id]?.let { T(it, 13, c.s) } } }
                if (acc[s.id] == null && s.canLogin) ListRow({ IconCover(NIcons.chevronRight, 56.dp) }, "登录${s.name}", "登录后在这里看到它的歌单") { nav.push(Page.PluginLogin(s.id)) }
                (pls[s.id] ?: emptyList()).forEach { pl ->
                    ListRow({ if (pl.liked) LikedCover(pl.cover, 56.dp) else Cover(pl.cover, 56.dp, pl.name) }, pl.name, (if (pl.mine) "" else "歌单 · ") + "${two(pl.count)} 首") { nav.push(Page.Detail("pl", pl.id, pl.name, pl.cover)) }
                }
            }
        }
    }
}
