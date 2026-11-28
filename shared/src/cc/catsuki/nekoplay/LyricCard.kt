package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 歌词分享卡片：勾几句歌词（最多 8 句），生成一张带封面的图片存到相册 / 图片文件夹 */
@Composable fun LyricCardPage(t: Track) {
    val nav = LocalNav.current
    val p = c
    val lines by rememberLyricLines(t.id)
    val all = lines.orEmpty().filter { it.text.isNotBlank() }
    // 默认勾上正在唱的那句
    val sel = remember(t.id) { mutableStateListOf<Int>() }
    LaunchedEffect(all.size) {
        if (sel.isEmpty() && all.isNotEmpty()) {
            val pos = Engine.position.value + LyricOffset.of(t.id)
            sel += all.indexOfLast { it.t <= pos }.coerceAtLeast(0)
        }
    }
    var style by remember { mutableIntStateOf(0) }   // 0 浅色 / 1 深色 / 2 主色
    var withTr by remember { mutableStateOf(true) }
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val (bg, fg, sub) = when (style) {
        1 -> Triple(Color(0xFF16181D), Color(0xFFF2F2F2), Color(0xFF9AA0A8))
        2 -> Triple(p.p, Color.White, Color.White.copy(alpha = .75f))
        else -> Triple(Color(0xFFF4F2EC), Color(0xFF111111), Color(0xFF5A5F66))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("LYRIC CARD"); Spacer(Modifier.height(4.dp))
                T("歌词卡片", Fs.HERO, w = 800, lh = 1.3f)
                T("勾选下面的歌词（最多 8 句），保存成图片", 13, p.s)
                Spacer(Modifier.height(14.dp))
                // ---- 卡片本体（录进 layer，保存时直接导出这一块）----
                Box(Modifier.fillMaxWidth(if (Screen.wide) .5f else 1f).align(Alignment.CenterHorizontally).drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }; drawLayer(layer)
                }) {
                    Column(Modifier.fillMaxWidth().background(bg).then(if (style == 0) Modifier.border(3.dp, Color(0xFF111111)) else Modifier).padding(22.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Cover(t.cover, 56.dp, t.title); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) { T(t.title, 16, fg, 800, lines = 2); T(t.artist, 12, sub, lines = 1) }
                        }
                        Spacer(Modifier.height(20.dp))
                        Box(Modifier.width(28.dp).height(4.dp).background(if (style == 2) Color.White else p.p))
                        Spacer(Modifier.height(14.dp))
                        if (sel.isEmpty()) T("（还没选歌词）", 15, sub)
                        sel.sorted().forEach { i ->
                            val l = all.getOrNull(i) ?: return@forEach
                            T(l.text, 20, fg, 800, lines = 4, lh = 1.35f)
                            if (withTr && l.tr != null) T(l.tr, 13, sub, lines = 3, lh = 1.4f)
                            Spacer(Modifier.height(10.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.background(fg).padding(horizontal = 6.dp, vertical = 2.dp)) { T("NEKOPLAY", 11, bg, 900, mono = true, ls = .08f) }
                            Spacer(Modifier.weight(1f)); T(t.album, 11, sub, lines = 1, modifier = Modifier.widthIn(max = 200.dp))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                ChipRow(listOf("浅色", "深色", "主色"), style) { style = it }
                Spacer(Modifier.height(10.dp))
                ActionRow {
                    PrimaryBar("保存图片", NIcons.download, Modifier.weight(1f)) {
                        scope.launch {
                            val img = layer.toImageBitmap()
                            val path = withContext(Dispatchers.IO) { runCatching { Plat.impl.savePicture(img, "NEKOPLAY_${t.title.take(20)}_${System.currentTimeMillis() / 1000}") }.getOrNull() }
                            nav.toast = if (path != null) "已保存：$path" else "保存失败"
                        }
                    }
                    Spacer(Modifier.width(10.dp)); OutlineBtn(if (withTr) "隐藏翻译" else "显示翻译", pad = 14.dp) { withTr = !withTr }
                }
                Spacer(Modifier.height(12.dp)); Rule()
                if (lines == null) T("LOADING", 12, p.s, mono = true, modifier = Modifier.padding(vertical = 20.dp))
                else if (all.isEmpty()) T("NO LYRICS", 12, p.s, mono = true, modifier = Modifier.padding(vertical = 20.dp))
            }
        }
        itemsIndexed(all, key = { i, _ -> "l$i" }) { i, l ->
            val on = i in sel
            Row(Modifier.fillMaxWidth().press { if (on) sel -= i else if (sel.size < 8) sel += i else nav.toast = "最多 8 句" }.bottomLine().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).background(if (on) p.p else Color.Transparent).border(1.dp, if (on) p.p else p.s), contentAlignment = Alignment.Center) { if (on) T("✓", 12, Color.White, 900) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { T(l.text, 15, if (on) p.k else p.s, if (on) 700 else 400, lines = 3); if (l.tr != null) T(l.tr, 12, p.s, lines = 2) }
            }
        }
    }
}
