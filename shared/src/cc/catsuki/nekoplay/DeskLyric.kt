package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 桌面歌词（电脑版悬浮窗 / 手机通知栏共用的设置和取词逻辑） */
object DeskLyric {
    var on by mutableStateOf(false)          // 电脑：显示悬浮歌词窗
    var locked by mutableStateOf(false)      // 锁定：不能拖动，鼠标点击穿透
    var size by mutableIntStateOf(30)
    var x by mutableIntStateOf(-1); var y by mutableIntStateOf(-1)
    var notify by mutableStateOf(false)      // 手机：通知栏 / 状态栏歌词
    private val k get() = Plat.prefs("ui")
    fun load() {
        on = k.getBoolean("dl_on", false); locked = k.getBoolean("dl_lock", false); size = k.getInt("dl_size", 30)
        x = k.getInt("dl_x", -1); y = k.getInt("dl_y", -1); notify = k.getBoolean("lyric_notify", false)
    }
    fun save() { k.edit().putBoolean("dl_on", on).putBoolean("dl_lock", locked).putInt("dl_size", size).putInt("dl_x", x).putInt("dl_y", y).putBoolean("lyric_notify", notify).apply() }

    /** 当前一句和下一句（或翻译），给通知栏用 */
    fun lineAt(lines: List<LyricLine>, pos: Long): Pair<String, String>? {
        if (lines.isEmpty()) return null
        val i = Lrc.indexAt(lines, pos).coerceAtLeast(0)
        val cur = lines.getOrNull(i) ?: return null
        val second = cur.tr ?: lines.drop(i + 1).firstOrNull { it.text.isNotEmpty() }?.text ?: ""
        return cur.text.ifEmpty { "♪" } to second
    }
}

/** 悬浮歌词的内容：当前句（唱过的部分染成主色）+ 下面一行翻译或下一句；没锁时鼠标移上去显示控制条 */
@Composable fun DeskLyricView(hover: Boolean, onClose: () -> Unit) {
    val p = c
    val st by Engine.state.collectAsState()
    val t = st.current
    val pos by Engine.position.collectAsState()
    DisposableEffect(Unit) { Engine.fastPos++; onDispose { Engine.fastPos-- } }
    var lines by remember(t?.id) { mutableStateOf(t?.let { LyricCache.peek(it.id) }) }
    LaunchedEffect(t?.id) { if (t != null && lines == null) lines = runCatching { LyricCache.get(t.id) }.getOrDefault(emptyList()) }
    // 播放中按帧推算进度，高亮扫得更顺
    var now by remember { mutableLongStateOf(pos) }
    LaunchedEffect(pos, st.playing) {
        val base = pos; now = base
        if (st.playing) { val s0 = withFrameMillis { it }; while (true) withFrameMillis { f -> now = base + (f - s0) } }
    }
    val l = lines.orEmpty()
    val i = if (l.isEmpty()) -1 else Lrc.indexAt(l, now).coerceAtLeast(0)
    val cur = l.getOrNull(i)
    val text = cur?.text?.ifEmpty { "♪" } ?: t?.let { "${it.title} - ${it.artist}" } ?: "NEKOPLAY"
    val sub = cur?.tr ?: l.drop(i + 1).firstOrNull { it.text.isNotEmpty() }?.text ?: ""
    // 当前句进度：有逐字时间按字算，没有就按到下一句的时间平均
    val frac = when {
        cur == null -> 0f
        cur.words != null -> {
            val ws = cur.words; val total = ws.sumOf { it.s.length }.coerceAtLeast(1)
            var done = 0f
            for (w in ws) { val f = ((now - w.t).toFloat() / w.d.coerceAtLeast(1)).coerceIn(0f, 1f); done += f * w.s.length; if (f < 1f) break }
            done / total
        }
        else -> { val next = l.getOrNull(i + 1)?.t ?: (cur.t + 5000); ((now - cur.t).toFloat() / (next - cur.t).coerceAtLeast(300)).coerceIn(0f, 1f) }
    }
    val shadow = Shadow(Color.Black.copy(alpha = .85f), Offset(0f, 1.5f), 6f)
    val main = TextStyle(fontSize = DeskLyric.size.sp, fontWeight = FontWeight(800), fontFamily = Typo.family, textAlign = TextAlign.Center, shadow = shadow)
    Column(Modifier.fillMaxSize().background(if (hover && !DeskLyric.locked) Color.Black.copy(alpha = .35f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (hover && !DeskLyric.locked) Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable fun B(label: String, act: () -> Unit) = Box(Modifier.press(.92f) { act() }.background(Color.Black.copy(alpha = .5f)).padding(horizontal = 10.dp, vertical = 4.dp)) { T(label, 12, Color.White, 700) }
            B("⏮") { Engine.prev() }; B(if (st.playing) "⏸" else "▶") { Engine.toggle() }; B("⏭") { Engine.next() }
            B("A-") { DeskLyric.size = (DeskLyric.size - 2).coerceAtLeast(18); DeskLyric.save() }
            B("A+") { DeskLyric.size = (DeskLyric.size + 2).coerceAtMost(60); DeskLyric.save() }
            B("锁定") { DeskLyric.locked = true; DeskLyric.save() }
            B("关闭") { onClose() }
        }
        var lay by remember { mutableStateOf<TextLayoutResult?>(null) }
        Box(contentAlignment = Alignment.Center) {
            BasicText(text, style = main.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = { lay = it })
            // 唱过的部分：同一行字用主色再画一遍，只露出左边 frac 的宽度
            BasicText(text, style = main.copy(color = p.p, shadow = null), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.drawWithContent {
                val lr = lay ?: return@drawWithContent
                val left = lr.getLineLeft(0); val w = lr.getLineRight(0) - left
                clipRect(right = left + w * frac) { this@drawWithContent.drawContent() }
            })
        }
        if (sub.isNotEmpty()) BasicText(sub, style = main.copy(fontSize = (DeskLyric.size * .55f).sp, fontWeight = FontWeight(600), color = Color.White.copy(alpha = .85f)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}
