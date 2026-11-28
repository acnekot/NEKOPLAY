package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** 正在安装的插件 id（列表和详情页共用，避免重复点） */
private var installing by mutableStateOf<String?>(null)

/** 发现：订阅里还没安装的插件，带展示图缩略图，按类型分组 */
internal fun LazyListScope.discoverSection(nav: Nav, avail: List<PluginSubs.Item>) {
    if (avail.isEmpty()) item {
        Column(Modifier.padding(vertical = 28.dp)) {
            T(tr(if (PluginSubs.loading) "正在读取订阅…" else "订阅里的插件都装好了"), 16, w = 700)
            T(tr("想要更多插件，可以在「订阅与导入」里添加别的订阅地址。"), 13, c.s, lines = 2, lh = 1.5f, modifier = Modifier.padding(top = 4.dp))
        }
    }
    listOf("source", "feature", "page", "motion").forEach { k ->
        val g = avail.filter { it.type.lowercase().let { t -> if (k == "source") t == "source" || t.isEmpty() else t == k } }
        if (g.isEmpty()) return@forEach
        item(key = "dh_$k") { Column(Modifier.padding(top = 22.dp, bottom = 2.dp)) { TagRow(tr(kindLabel(k)), 12.dp) { T(two(g.size), 13, c.s, mono = true) } } }
        items(g, key = { "d_" + it.id }) { i -> DiscoverRow(nav, i) }
    }
}

@Composable private fun DiscoverRow(nav: Nav, i: PluginSubs.Item) {
    val p = c
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().cascadeEnter().bottomLine().press { nav.push(Page.PluginDetail(i.id)) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        val shot = i.screenshots.firstOrNull()
        if (shot != null) Box(Modifier.width(112.dp).height(70.dp).clipToBounds().background(p.l3).nbf(1.dp, p.l2)) {
            AsyncImage(shot, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else Box(Modifier.width(112.dp).height(70.dp), contentAlignment = Alignment.Center) { IconCover(kindIcon(i.type), 48.dp, iconSize = 20.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            T(i.name, 16, w = 700)
            if (i.desc.isNotEmpty()) T(i.desc, 13, p.s, lines = 2, lh = 1.45f, modifier = Modifier.padding(top = 2.dp))
            T("v${i.version} · ${i.from}", 11, p.s, mono = true, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.width(12.dp))
        if (installing == i.id) T(tr("安装中…"), 12, p.s)
        else OutlineBtn("安装", height = 38.dp, size = 13, pad = 14.dp) {
            if (installing != null) return@OutlineBtn
            confirmPluginNotice(nav) {
                installing = i.id
                scope.launch {
                    runCatching { PluginSubs.install(i) }.onSuccess { nav.toast = "已安装「${it.name}」${it.version}"; Store.refreshAll() }.onFailure { nav.toast = "安装失败：${it.message}" }
                    installing = null
                }
            }
        }
    }
}

/** 订阅与导入：订阅地址管理、从文件 / 网址导入单个脚本 */
internal fun LazyListScope.subsSection(nav: Nav) {
    item(key = "subs") {
        val p = c
        val scope = rememberCoroutineScope()
        var subUrl by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        fun done(r: Result<JsSource>) { busy = false; r.onSuccess { nav.toast = "已导入${it.kind.label}「${it.name}」${it.version}"; Store.refreshAll() }.onFailure { nav.toast = "导入失败：${it.message}" } }
        val picker = Plat.impl.filePicker { bytes -> busy = true; scope.launch { done(runCatching { Plugins.import(bytes.decodeToString()) }) } }
        Column {
            Column(Modifier.padding(top = 22.dp, bottom = 4.dp)) {
                TagRow(tr("订阅"), 12.dp) { LinkBtn(if (PluginSubs.loading) "刷新中…" else "刷新", 13) { if (!PluginSubs.loading) scope.launch { PluginSubs.refresh() } } }
            }
            T(tr("订阅是一份插件清单（index.json），里面的插件会出现在「发现」里，有新版本时自动提示。"), 13, p.s, lines = 3, lh = 1.5f)
            PluginSubs.urls.forEach { u ->
                val sub = PluginSubs.subs[u]
                Row(Modifier.fillMaxWidth().bottomLine().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(sub?.name ?: tr("读取中…"), 15, w = 700)
                        T(u, 11, p.s, mono = true, lines = 1)
                        when {
                            sub?.error != null -> T("${tr("拉取失败")}：${sub.error}", 12, p.bad, lines = 2)
                            sub != null -> T(trKnown("${sub.items.size} 个插件"), 12, p.s)
                        }
                    }
                    LinkBtn("移除", 13, p.bad) { nav.sheet = SheetSpec("移除这个订阅？", u, listOf(SheetItem("移除", danger = true) { PluginSubs.remove(u) })) }
                }
            }
            InputLine(subUrl, { subUrl = it }, "https://…/index.json", "添加订阅") {
                confirmPluginNotice(nav) { runCatching { PluginSubs.add(subUrl) }.onSuccess { subUrl = ""; scope.launch { PluginSubs.refresh() } }.onFailure { nav.toast = it.message } }
            }

            Column(Modifier.padding(top = 30.dp, bottom = 4.dp)) { TagRow(tr("导入单个插件"), 12.dp) }
            T(tr("导入不在订阅里的插件脚本（.js）。请只导入可信来源的脚本。"), 13, p.s, lines = 2, lh = 1.5f)
            InputLine(url, { url = it }, "https://…/xxx.js", if (busy) "导入中…" else "从网址导入") {
                if (busy || !url.trim().startsWith("http")) return@InputLine
                confirmPluginNotice(nav) { busy = true; scope.launch { done(runCatching { Plugins.importUrl(url) }.onSuccess { url = "" }) } }
            }
            if (picker != null) Box(Modifier.padding(top = 10.dp)) { OutlineBtn(if (busy) "导入中…" else "从文件导入…", height = 44.dp, size = 14, pad = 16.dp) { if (!busy) confirmPluginNotice(nav) { picker() } } }
        }
    }
}

@Composable private fun InputLine(value: String, onChange: (String) -> Unit, hint: String, action: String, onGo: () -> Unit) {
    val p = c
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(44.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) T(hint, 13, p.s, mono = true)
            BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = 13.sp, fontFamily = Mono), cursorBrush = SolidColor(p.p))
        }
        Spacer(Modifier.width(8.dp))
        OutlineBtn(action, height = 44.dp, size = 14, pad = 14.dp, onClick = onGo)
    }
}
