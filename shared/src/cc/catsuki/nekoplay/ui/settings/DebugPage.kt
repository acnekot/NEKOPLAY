package cc.catsuki.nekoplay.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cc.catsuki.nekoplay.Debug
import cc.catsuki.nekoplay.Fs
import cc.catsuki.nekoplay.LinkBtn
import cc.catsuki.nekoplay.Rule
import cc.catsuki.nekoplay.T
import cc.catsuki.nekoplay.Tag
import cc.catsuki.nekoplay.bottomLine
import cc.catsuki.nekoplay.c
import cc.catsuki.nekoplay.press
import cc.catsuki.nekoplay.rememberCopy

@Composable fun DebugPage() {
    val p = c
    val reqs by Debug.reqs.collectAsState()
    val copy = rememberCopy()
    val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("DEBUG"); Spacer(Modifier.height(4.dp))
                T("调试", Fs.HERO, w = 800, lh = 1.3f)
                T("最近 80 次接口请求的真实返回；长按一条复制全部内容", 13, p.s)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("${reqs.size} 条", 12, p.s, mono = true, modifier = Modifier.weight(1f))
                    LinkBtn("清空", 13) { Debug.reqs.value = emptyList() }
                }
                Spacer(Modifier.height(8.dp)); Rule()
            }
        }
        items(reqs, key = { "${it.at}${it.path}" }) { r ->
            val bad = r.code != 200 || !r.body.trimStart().startsWith("{")
            Column(Modifier.fillMaxWidth().press(onLong = { copy("${r.path}\nHTTP ${r.code} · ${r.type} · ${r.ms}ms\n${r.body}", "调试信息") }) {}.bottomLine().padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(if (bad) p.bad else p.p).padding(horizontal = 6.dp, vertical = 2.dp)) { T(if (r.code < 0) "ERR" else "${r.code}", 11, Color.White, 700, mono = true) }
                    Spacer(Modifier.width(8.dp)); T(r.path, 13, w = 600, mono = true, lines = 1, modifier = Modifier.weight(1f))
                    T("${fmt.format(java.util.Date(r.at))} · ${r.ms}ms", 11, p.s, mono = true)
                }
                T("${r.type.ifEmpty { "-" }} · ${r.body.length} 字节", 11, p.s, mono = true, modifier = Modifier.padding(top = 4.dp))
                T(r.body.take(300).replace(Regex("\\s+"), " "), 12, if (bad) p.bad else p.s, mono = true, lines = 4, lh = 1.4f, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
