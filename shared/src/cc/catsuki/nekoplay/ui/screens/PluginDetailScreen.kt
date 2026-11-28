package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** 权限名 → 给人看的说明 */
private val PERM_NAMES = linkedMapOf(
    "network" to "联网：访问插件自己用到的网络服务",
    "storage" to "本地存储：保存插件的设置和缓存",
    "lan" to "局域网：访问本机或局域网里的地址（如自建服务器、本地模型）",
    "player.control" to "播放控制：切歌、暂停、调整播放速度",
    "library.read" to "读取曲库：歌单数量、播放历史等概况",
    "sensor.tilt" to "重力感应：读取手机倾斜角度做视差效果",
    "ui.sidebar" to "界面：临时收起侧栏",
    "motion.profile" to "动效：替换界面的动画曲线与转场",
)

/** 插件详情：完整介绍、展示图、能力、权限、版本信息与操作；已安装和订阅里未安装的插件都能看 */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun PluginDetailScreen(id: String) {
    val nav = LocalNav.current
    val p = c
    val scope = rememberCoroutineScope()
    val list by Plugins.list.collectAsState()
    LaunchedEffect(Unit) { if (PluginSubs.subs.isEmpty()) runCatching { PluginSubs.refresh() } }
    val inst = list.firstOrNull { it.id == id }
    val item = remember(PluginSubs.subs, id) { PluginSubs.itemOf(id) }
    LaunchedEffect(inst) { if (inst != null && Plugins.on(id) && inst.caps.isEmpty() && inst.error == null) inst.start() }
    if (inst == null && item == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { T(tr(if (PluginSubs.loading) "加载中…" else "找不到这个插件"), 13, p.s) }
        return
    }
    val on = inst != null && Plugins.on(id)
    val name = inst?.name ?: item!!.name
    val version = inst?.version ?: item!!.version
    val author = inst?.meta?.get("author") ?: item?.author.orEmpty()
    val desc = inst?.meta?.get("desc") ?: item?.desc.orEmpty()
    val details = inst?.details?.takeIf { it.isNotBlank() } ?: item?.details.orEmpty()
    val shots = item?.screenshots?.takeIf { it.isNotEmpty() } ?: inst?.let { PluginSubs.shotsOf(it) }.orEmpty()
    val kindKey = inst?.kind?.name?.lowercase() ?: item!!.type.lowercase()
    val isSource = inst?.isSource ?: !item!!.isFeature
    val minHost = inst?.minHost ?: item?.minHost.orEmpty()
    val perms = inst?.permissions ?: item?.permissions.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    val upd = inst != null && item != null && PluginSubs.newer(item.version, inst.version)
    val tooOld = minHost.isNotEmpty() && PluginSubs.newer(minHost, APP_VERSION)
    var busy by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf<String?>(null) }
    fun install() {
        val i = item ?: return
        if (busy) return
        confirmPluginNotice(nav) {
            busy = true
            scope.launch {
                runCatching { PluginSubs.install(i) }.onSuccess { nav.toast = "已安装「${it.name}」${it.version}"; Store.refreshAll() }.onFailure { nav.toast = "安装失败：${it.message}" }
                busy = false
            }
        }
    }

    LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 32.dp)) {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val badge = tr(when (kindKey) { "motion" -> "动效插件"; "page" -> "页面插件"; "feature" -> "功能插件"; else -> "音源插件" })
                    Box(Modifier.background(if (isSource) p.p else p.k).padding(horizontal = 6.dp, vertical = 2.dp)) { T(badge, 10, if (isSource) Color.White else p.tagFg, 800) }
                    Spacer(Modifier.width(8.dp)); T("v$version", 12, p.s, mono = true)
                    if (upd) { Spacer(Modifier.width(8.dp)); T("→ v${item!!.version}", 12, p.p, 700, mono = true) }
                }
                Spacer(Modifier.height(8.dp))
                T(name, Fs.HERO, w = 800, lines = 2, lh = 1.25f)
                if (author.isNotEmpty()) T("${tr("作者")} $author", 13, p.s, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        busy -> T(tr("安装中…"), 14, p.s, modifier = Modifier.weight(1f))
                        inst == null -> PrimaryBar(if (tooOld) "需要更新 NEKOPLAY" else "安装", NIcons.plus, Modifier.weight(1f)) { if (!tooOld) install() }
                        upd -> PrimaryBar("更新到 v${item!!.version}", NIcons.download, Modifier.weight(1f)) { install() }
                        else -> T(tr(if (on) "已安装 · 已启用" else "已安装 · 已停用"), 14, if (on) p.p else p.s, 700, modifier = Modifier.weight(1f))
                    }
                    if (inst != null) { Spacer(Modifier.width(12.dp)); NSwitch(on) { v -> Plugins.set(id, v) } }
                }
                if (tooOld) T(tr("这个插件需要 NEKOPLAY $minHost 或更新版本"), 12, p.bad, modifier = Modifier.padding(top = 6.dp))
                if (inst != null && on) FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (inst.has("render")) LinkBtn("打开设置页", 14, p.p) { nav.push(Page.PluginPage(id)) }
                    if (inst.canLogin) {
                        val acc by PluginStore.accounts.collectAsState()
                        val who = acc[id]
                        if (who == null) LinkBtn("登录", 14) { nav.push(Page.PluginLogin(id)) }
                        else LinkBtn("退出登录（$who）", 14) { confirmLogout(nav, name) { scope.launch { runCatching { inst.call("logout") }; PluginStore.refresh() } } }
                    }
                    if (inst.has("action")) LinkBtn("执行", 14) {
                        scope.launch {
                            runCatching { Plugins.runFeatureAction(id, Engine.state.value.current) }.onSuccess { r ->
                                val op = r?.open
                                if (op == "coverflow") nav.push(Page.CoverFlow) else if (op?.startsWith("plugin:") == true) nav.push(Page.PluginPage(op.removePrefix("plugin:")))
                                r?.message?.let { nav.toast = it }
                            }.onFailure { nav.toast = "执行失败：${it.message}" }
                        }
                    }
                }
                inst?.error?.let { T("${tr("出错")}：$it", 12, p.bad, lines = 4, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
        if (shots.isNotEmpty()) item {
            Column(Modifier.padding(top = 24.dp)) {
                zoom?.let { u ->
                    // 点开的大图：整宽按原比例显示，再点一下收起
                    Box(Modifier.fillMaxWidth().padding(bottom = 12.dp).nbf(1.dp, p.l2).press { zoom = null }) {
                        AsyncImage(u, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    }
                }
                val h = if (Screen.wide) 260.dp else 190.dp
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(shots, key = { i, u -> "$i:$u" }) { _, u ->
                        Box(Modifier.height(h).aspectRatio(1.6f).clipToBounds().background(p.l3).nbf(1.dp, if (zoom == u) p.p else p.l2).press { zoom = if (zoom == u) null else u }) {
                            AsyncImage(u, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }
                T(tr("点图片可放大"), 11, p.s, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            Column(Modifier.padding(top = 26.dp)) {
                TagRow("ABOUT", 12.dp)
                Spacer(Modifier.height(10.dp))
                T(desc.ifEmpty { tr("作者没有写介绍") }, 15, lines = 30, lh = 1.6f)
                details.split('\n').filter { it.isNotBlank() }.forEach { para ->
                    Row(Modifier.padding(top = 10.dp)) {
                        Box(Modifier.padding(top = 9.dp).size(5.dp).background(p.p)); Spacer(Modifier.width(10.dp))
                        T(para.trim(), 14, p.k, lines = 30, lh = 1.6f)
                    }
                }
            }
        }
        val caps = inst?.let { s -> CAP_NAMES.filterKeys { s.has(it) }.values.map { tr(it) } }.orEmpty()
        if (caps.isNotEmpty()) item {
            Column(Modifier.padding(top = 26.dp)) {
                TagRow("CAPABILITIES", 12.dp); Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    caps.forEach { cap -> Box(Modifier.nbf(1.dp, p.l2).padding(horizontal = 10.dp, vertical = 5.dp)) { T(cap, 12) } }
                }
            }
        }
        item {
            Column(Modifier.padding(top = 26.dp)) {
                TagRow("PERMISSIONS", 12.dp); Spacer(Modifier.height(10.dp))
                if (perms.isEmpty()) T(tr(if (isSource) "未声明（旧版音源插件默认允许联网）" else "不需要额外权限"), 13, p.s)
                perms.forEach { pm -> T("· " + tr(PERM_NAMES[pm] ?: pm), 13, lines = 2, lh = 1.5f, modifier = Modifier.padding(vertical = 2.dp)) }
                if (inst != null && !inst.canNetwork) T(tr("这个插件不能联网，只在本地运行"), 12, p.s, modifier = Modifier.padding(top = 4.dp))
            }
        }
        item {
            Column(Modifier.padding(top = 26.dp)) {
                TagRow("INFO", 12.dp); Spacer(Modifier.height(6.dp))
                listOfNotNull(
                    "ID" to id,
                    tr("已安装版本") to (inst?.version?.let { "v$it" } ?: tr("未安装")),
                    item?.let { tr("订阅里的最新版本") to "v${it.version}" },
                    minHost.takeIf { it.isNotEmpty() }?.let { tr("需要 NEKOPLAY") to "$it+" },
                    item?.let { tr("来自订阅") to it.from },
                ).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp)) {
                        T(k, 13, p.s, modifier = Modifier.width(132.dp)); T(v, 13, mono = true, lines = 2, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        if (inst != null) item {
            FlowRow(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinkBtn("重新加载", 13) {
                    scope.launch {
                        inst.close().join()
                        if (inst.start()) { Plugins.bump(); PluginStore.refresh().join(); Store.refreshAll(); nav.toast = "已重新加载" } else nav.toast = "加载失败：${inst.error}"
                    }
                }
                LinkBtn("删除插件", 13, p.bad) {
                    val del = SheetItem("删除", danger = true) { Plugins.delete(id); nav.toast = "已删除"; nav.back() }
                    nav.sheet = SheetSpec("删除插件「$name」？", "删除后它提供的功能都会消失；以后可以重新安装", listOf(del))
                }
            }
        }
    }
}
