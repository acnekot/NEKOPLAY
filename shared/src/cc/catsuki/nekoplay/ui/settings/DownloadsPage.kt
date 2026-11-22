package cc.catsuki.nekoplay.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.catsuki.nekoplay.ActionRow
import cc.catsuki.nekoplay.DlPref
import cc.catsuki.nekoplay.Downloads
import cc.catsuki.nekoplay.Engine
import cc.catsuki.nekoplay.Fs
import cc.catsuki.nekoplay.InputSpec
import cc.catsuki.nekoplay.LinkBtn
import cc.catsuki.nekoplay.LocalNav
import cc.catsuki.nekoplay.NIcon
import cc.catsuki.nekoplay.NIcons
import cc.catsuki.nekoplay.Nav
import cc.catsuki.nekoplay.Ncm
import cc.catsuki.nekoplay.OutlineBtn
import cc.catsuki.nekoplay.Plat
import cc.catsuki.nekoplay.PrimaryBar
import cc.catsuki.nekoplay.Rule
import cc.catsuki.nekoplay.SheetItem
import cc.catsuki.nekoplay.SheetSpec
import cc.catsuki.nekoplay.T
import cc.catsuki.nekoplay.Tag
import cc.catsuki.nekoplay.Track
import cc.catsuki.nekoplay.TrackRow
import cc.catsuki.nekoplay.bottomLine
import cc.catsuki.nekoplay.c
import cc.catsuki.nekoplay.press
import cc.catsuki.nekoplay.trackActions
import cc.catsuki.nekoplay.two

/** 下载前选音质：选过的会记住，下次默认勾上 */
fun downloadSheet(nav: Nav, list: List<Track>) {
    val todo = list.filter { !Downloads.has(it.id) && it.id !in Downloads.active.value }
    if (todo.isEmpty()) { nav.toast = "都已经下载过了"; return }
    val cur = Downloads.lastQuality
    nav.sheet = SheetSpec(if (todo.size == 1) "下载「${todo[0].title}」" else "下载 ${todo.size} 首", "选择音质；拿不到时自动降一档",
        listOf(SheetItem((if (cur < 0) "✓  " else "    ") + "跟随播放音质（${Ncm.LEVEL_LABELS[Engine.quality.coerceIn(0, 4)]}）") {
            Downloads.lastQuality = -1; Downloads.downloadAll(todo, Engine.quality); nav.toast = "已加入下载：${todo.size} 首"
        }) + Ncm.LEVEL_LABELS.indices.reversed().map { q ->
            SheetItem((if (cur == q) "✓  " else "    ") + Ncm.LEVEL_LABELS[q]) { Downloads.lastQuality = q; Downloads.downloadAll(todo, q); nav.toast = "已加入下载：${todo.size} 首" }
        })
}

/** 本地下载：上面是下载管理器（正在下、排队、失败），下面是下好的歌 */
@Composable fun DownloadsPage() {
    val nav = LocalNav.current
    val items by Downloads.items.collectAsState()
    val act by Downloads.active.collectAsState()
    val jobs by Downloads.jobs.collectAsState()
    val failed by Downloads.failed.collectAsState()
    val st by Engine.state.collectAsState()
    val p = c
    val list = items.map { it.track }
    val total = items.sumOf { it.bytes }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("DOWNLOADS"); Spacer(Modifier.height(4.dp))
                T("本地下载", Fs.HERO, w = 800, lh = 1.3f)
                T("${two(items.size)} 首 · ${(total / 1048576.0 + 0.5).toLong()} MB", 12, p.s, mono = true, ls = .04f)
                T(Downloads.dir.absolutePath + "  ✎", 12, p.s, lines = 2, modifier = Modifier.press { dirSheet(nav) })
                Spacer(Modifier.height(14.dp))
                if (list.isNotEmpty()) ActionRow {
                    PrimaryBar("播放", NIcons.play, Modifier.weight(1f)) { Engine.playList(list, 0) }
                    Spacer(Modifier.width(10.dp)); OutlineBtn("随机播放", pad = 14.dp) { Engine.playList(list, 0, shuffle = true) }
                }
                Spacer(Modifier.height(12.dp)); Rule()
                if (items.isEmpty() && jobs.isEmpty() && failed.isEmpty()) T("还没有下载的歌：长按歌曲选「下载」，或在歌单页点下载按钮", 13, p.s, lines = 3, modifier = Modifier.padding(vertical = 28.dp))
            }
        }
        if (jobs.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Tag("DOWNLOADING · ${jobs.size}") }
                    LinkBtn("全部取消", 13, p.bad) { Downloads.cancelAll() }
                }
            }
            items(jobs.size, key = { "j" + jobs[it].first.id }) { i ->
                val (t, q) = jobs[i]; val pr = act[t.id] ?: -1f
                Column(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { T(t.title, 15, w = 600); T("${t.artist} · ${Ncm.LEVEL_LABELS[q.coerceIn(0, 4)]}", 12, p.s) }
                        T(if (pr < 0) "等待中" else "${(pr * 100f + 0.5f).toInt()}%", 12, if (pr < 0) p.s else p.p, 700, mono = true)
                        Box(Modifier.size(40.dp).press { Downloads.cancel(t.id) }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 12.dp, p.s) }
                    }
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).background(p.l2)) {
                        Box(Modifier.fillMaxWidth(pr.coerceIn(0f, 1f)).fillMaxHeight().background(p.p))
                    }
                }
            }
        }
        if (failed.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Tag("FAILED · ${failed.size}") }
                    LinkBtn("全部重试", 13) { Downloads.retryAll() }; Spacer(Modifier.width(6.dp)); LinkBtn("清除", 13) { Downloads.clearFailed() }
                }
            }
            val fl = failed.values.toList()
            items(fl.size, key = { "f" + fl[it].first.id }) { i ->
                val (t, _, why) = fl[i]
                Row(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { T(t.title, 15, w = 600); T(why, 12, p.bad, lines = 2) }
                    LinkBtn("重试", 13) { Downloads.retry(t.id) }
                }
            }
        }
        if (items.isNotEmpty() && (jobs.isNotEmpty() || failed.isNotEmpty())) item { Box(Modifier.padding(top = 16.dp, bottom = 4.dp)) { Tag("DOWNLOADED · ${items.size}") } }
        itemsIndexed(list, key = { _, t -> "dl" + t.id }) { i, t ->
            TrackRow(t, st.current?.id == t.id, items[i].format, onLong = { trackActions(nav, t) }) { Engine.playList(list, i) }
        }
    }
}

/** 选择下载位置 */
fun dirSheet(nav: Nav) {
    val def = Plat.impl.downloadDir().absolutePath
    fun mark(path: String) = if ((DlPref.custom.ifBlank { def }) == path) "✓  " else "    "
    fun pick(path: String) {
        val f = java.io.File(path)
        if ((f.isDirectory || f.mkdirs()) && f.canWrite()) { DlPref.set(if (path == def) "" else path); nav.toast = "下载位置：$path" }
        else nav.toast = "这个文件夹写不进去，换一个吧"
    }
    nav.sheet = SheetSpec("下载位置", "新下载的歌放到这里；已经下载的歌不会移动", buildList {
        add(SheetItem(mark(def) + "默认 · $def") { DlPref.set(""); nav.toast = "已恢复默认下载位置" })
        Plat.impl.dirPresets().filter { it.second != def }.forEach { (n, path) -> add(SheetItem(mark(path) + "$n · $path") { pick(path) }) }
        if (Plat.isDesktop) add(SheetItem("选择文件夹…") {
            Plat.impl.pickFolder(DlPref.custom.ifBlank { def }) { it?.let { p -> pick(p) } }
        })
        add(SheetItem("输入路径…") { nav.input = InputSpec("下载位置", "完整的文件夹路径") { s -> if (s.isNotBlank()) pick(s.trim()) } })
    })
}
