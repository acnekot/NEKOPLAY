package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 插件页里的订阅区：订阅地址管理 + 一键安装 / 全部更新 */
internal fun androidx.compose.foundation.lazy.LazyListScope.pluginSubsSection(nav: Nav, tab: Int) {
    item(key = "subs") {
        val p = c
        val scope = rememberCoroutineScope()
        val list by Plugins.list.collectAsState()
        var adding by remember { mutableStateOf(false) }
        var subUrl by remember { mutableStateOf("") }
        var working by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { if (PluginSubs.subs.isEmpty()) runCatching { PluginSubs.refresh() } }
        fun install(i: PluginSubs.Item) {
            if (working != null) return
            confirmPluginNotice(nav) {
                working = i.id
                scope.launch {
                    runCatching { PluginSubs.install(i) }.onSuccess { nav.toast = "已安装「${it.name}」${it.version}"; Store.refreshAll() }.onFailure { nav.toast = "安装失败：${it.message}" }
                    working = null
                }
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
            T(tr("从订阅列表一键安装、更新插件"), 12, p.s)
            if (adding) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(44.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (subUrl.isEmpty()) T(tr("订阅地址 https://…/index.json"), 13, p.s, mono = true)
                        BasicTextField(subUrl, { subUrl = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = 13.sp, fontFamily = Mono), cursorBrush = SolidColor(p.p))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlineBtn("添加", pad = 14.dp) {
                        confirmPluginNotice(nav) {
                            runCatching { PluginSubs.add(subUrl) }.onSuccess { subUrl = ""; adding = false; scope.launch { PluginSubs.refresh() } }.onFailure { nav.toast = it.message }
                        }
                    }
                }
            }
            val ups = PluginSubs.updates
            if (ups.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                T(trKnown("${ups.size} 个插件有新版本"), 13, p.p, 700, modifier = Modifier.weight(1f))
                OutlineBtn(if (working == "*") "更新中…" else "一键更新全部", pad = 12.dp) {
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
                    sub?.error?.let { T("${tr("拉取失败")}：$it", 12, p.bad, lines = 2) }
                    sub?.items?.filter { when (tab) { 1 -> !it.isFeature; 2 -> it.isFeature; else -> true } }?.forEach { i ->
                        val have = list.firstOrNull { it.id == i.id }
                        val upd = have != null && PluginSubs.newer(i.version, have.version)
                        Row(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val badge = tr(when (i.type.lowercase()) { "motion" -> "动效"; "page" -> "页面"; "feature" -> "功能"; else -> "音源" })
                                    Box(Modifier.background(if (i.isFeature) p.k else p.p).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                        T(badge, 9, if (i.isFeature) p.tagFg else androidx.compose.ui.graphics.Color.White, 800)
                                    }
                                    Spacer(Modifier.width(7.dp)); T(i.name, 14, w = 700, modifier = Modifier.weight(1f, fill = false)); Spacer(Modifier.width(8.dp)); T("v" + i.version, 11, p.s, mono = true)
                                }
                                val line = listOfNotNull(i.author.takeIf { it.isNotEmpty() }?.let { "${tr("作者")} $it" }, i.desc.takeIf { it.isNotEmpty() }).joinToString(" · ")
                                if (line.isNotEmpty()) T(line, 12, p.s, lines = 2, lh = 1.4f)
                            }
                            Spacer(Modifier.width(10.dp))
                            when {
                                working == i.id || (working == "*" && upd) -> T(tr("安装中…"), 12, p.s)
                                have == null -> OutlineBtn("安装", pad = 12.dp) { install(i) }
                                upd -> OutlineBtn("更新到 v${i.version}", pad = 12.dp) { install(i) }
                                else -> T(tr("已安装"), 12, p.s)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp)); Rule()
        }
    }
}
