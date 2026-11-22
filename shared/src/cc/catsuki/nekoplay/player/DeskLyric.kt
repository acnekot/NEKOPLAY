package cc.catsuki.nekoplay.player

import cc.catsuki.nekoplay.*
import cc.catsuki.nekoplay.core.*
import cc.catsuki.nekoplay.data.*
import cc.catsuki.nekoplay.ui.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.togetherWith
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
    var showTr by mutableStateOf(true)       // 第二行显示翻译（关掉就显示下一句）
    private val k get() = Plat.prefs("ui")
    fun load() {
        on = k.getBoolean("dl_on", false); locked = k.getBoolean("dl_lock", false); size = k.getInt("dl_size", 30)
        x = k.getInt("dl_x", -1); y = k.getInt("dl_y", -1); notify = k.getBoolean("lyric_notify", false); showTr = k.getBoolean("dl_tr", true)
    }
    fun save() { k.edit().putBoolean("dl_on", on).putBoolean("dl_lock", locked).putInt("dl_size", size).putInt("dl_x", x).putInt("dl_y", y).putBoolean("lyric_notify", notify).putBoolean("dl_tr", showTr).apply() }

    /** 当前一句和下一句（或翻译），给通知栏用 */
    fun lineAt(lines: List<LyricLine>, pos: Long): Pair<String, String>? {
        if (lines.isEmpty()) return null
        val i = Lrc.indexAt(lines, pos).coerceAtLeast(0)
        val cur = lines.getOrNull(i) ?: return null
        val second = cur.tr ?: lines.drop(i + 1).firstOrNull { it.text.isNotEmpty() }?.text ?: ""
        return cur.text.ifEmpty { "♪" } to second
    }
}

/**
 * 悬浮歌词：当前句（唱过的部分染成主色）+ 下面一行（翻译或下一句）。
 * 字带深色描边，放在任何壁纸上都看得清；换句时新句从下面淡入。没锁时鼠标移上去出现半透明底板和控制条。
 */
@Composable fun DeskLyricView(hover: Boolean, onClose: () -> Unit) {
    val p = c
    val st by Engine.state.collectAsState()
    val t = st.current
    val raw by Engine.position.collectAsState()
    val pos = raw + (t?.let { LyricOffset.of(it.id) } ?: 0)
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
    val sub = if (DeskLyric.showTr) cur?.tr ?: l.drop(i + 1).firstOrNull { it.text.isNotEmpty() }?.text ?: "" else l.drop(i + 1).firstOrNull { it.text.isNotEmpty() }?.text ?: ""
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
    val showBar = hover && !DeskLyric.locked
    val panel by androidx.compose.animation.core.animateFloatAsState(if (showBar) 1f else 0f, androidx.compose.animation.core.tween(180), label = "pn")
    val ink = Color(0xFF0E1015)
    val main = TextStyle(fontSize = DeskLyric.size.sp, fontWeight = FontWeight(800), fontFamily = Typo.family, textAlign = TextAlign.Center)
    // 和 App 一样的 BenchLab 风格：直角、粗黑框、页面底色；只在鼠标移上去时出现
    Box(Modifier.fillMaxSize().padding(6.dp).graphicsLayer { alpha = 1f }
        .drawBehind { if (panel > 0f) { drawRect(p.page.copy(alpha = .92f * panel)); if (!Look.flat) drawRect(p.k.copy(alpha = panel), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx())) } }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // 控制条：图标按钮，淡入淡出（不占位置时也不挤歌词）
            Row(Modifier.height(30.dp).graphicsLayer { alpha = panel }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (panel > 0.01f) {
                    // 按钮和播放条一样：直角方块、细黑框，开着的填主色
                    @Composable fun I(icon: String, on: Boolean = false, act: () -> Unit) = Box(Modifier.size(30.dp).press(.92f) { act() }.background(if (on) p.p else Color.Transparent).nbf(1.dp, p.k), contentAlignment = Alignment.Center) { NIcon(icon, 15.dp, if (on) Color.White else p.k) }
                    @Composable fun L(label: String, on: Boolean = false, act: () -> Unit) = Box(Modifier.height(30.dp).defaultMinSize(minWidth = 30.dp).press(.92f) { act() }.background(if (on) p.p else Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) { T(label, 12, if (on) Color.White else p.k, 700, mono = true) }
                    I(NIcons.prev) { Engine.prev() }; I(if (st.playing) NIcons.pause else NIcons.play) { Engine.toggle() }; I(NIcons.next) { Engine.next() }
                    Spacer(Modifier.width(8.dp))
                    L("A−") { DeskLyric.size = (DeskLyric.size - 2).coerceAtLeast(18); DeskLyric.save() }
                    L("A+") { DeskLyric.size = (DeskLyric.size + 2).coerceAtMost(60); DeskLyric.save() }
                    L("译", DeskLyric.showTr) { DeskLyric.showTr = !DeskLyric.showTr; DeskLyric.save() }
                    Spacer(Modifier.width(8.dp))
                    I(NIcons.lock) { DeskLyric.locked = true; DeskLyric.save() }; I(NIcons.close) { onClose() }
                }
            }
            Spacer(Modifier.height(2.dp))
            // 换句：新句从下面淡入
            androidx.compose.animation.AnimatedContent(text, transitionSpec = {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it / 4 }) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))
            }, label = "dl") { tx ->
                var lay by remember { mutableStateOf<TextLayoutResult?>(null) }
                val f = if (tx == text) frac else 1f
                Box(contentAlignment = Alignment.Center) {
                    // 描边 → 白字 → 唱过部分的主色
                    BasicText(tx, style = main.copy(color = ink, drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(DeskLyric.size * .28f, join = androidx.compose.ui.graphics.StrokeJoin.Round)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(tx, style = main.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = { lay = it })
                    BasicText(tx, style = main.copy(color = p.p), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.drawWithContent {
                        val lr = lay ?: return@drawWithContent
                        val left = lr.getLineLeft(0); val w = lr.getLineRight(0) - left
                        clipRect(right = left + w * f) { this@drawWithContent.drawContent() }
                    })
                }
            }
            if (sub.isNotEmpty()) {
                val st2 = main.copy(fontSize = (DeskLyric.size * .52f).sp, fontWeight = FontWeight(600))
                Box(Modifier.padding(top = 2.dp), contentAlignment = Alignment.Center) {
                    BasicText(sub, style = st2.copy(color = ink, drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(DeskLyric.size * .16f, join = androidx.compose.ui.graphics.StrokeJoin.Round)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(sub, style = st2.copy(color = Color.White.copy(alpha = .9f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 歌词时间偏移：每首歌单独记（毫秒，正数 = 歌词提前出现） */
object LyricOffset {
    private val map = mutableStateMapOf<String, Long>()
    private val k get() = Plat.prefs("lyricOffset")
    fun of(id: String): Long = map[id] ?: k.getLong(id, 0L).also { if (it != 0L) map[id] = it }
    fun set(id: String, ms: Long) { map[id] = ms; k.edit().apply { if (ms == 0L) remove(id) else putLong(id, ms) }.apply() }
}

fun offsetSheet(nav: Nav, t: Track) {
    val cur = LyricOffset.of(t.id)
    fun label(ms: Long) = (if (ms > 0) "提前 " else if (ms < 0) "推后 " else "") + if (ms == 0L) "不偏移" else "%.1f 秒".format(kotlin.math.abs(ms) / 1000f)
    fun step(d: Long) = SheetItem((if (d > 0) "歌词提前 " else "歌词推后 ") + "%.1f 秒".format(kotlin.math.abs(d) / 1000f)) { LyricOffset.set(t.id, cur + d); offsetSheet(nav, t) }
    nav.sheet = SheetSpec("歌词偏移 · ${label(cur)}", "歌词比歌慢就提前，比歌快就推后；每首歌单独记住", listOf(step(500), step(100), step(-100), step(-500)) +
        (if (cur != 0L) listOf(SheetItem("重置", danger = true) { LyricOffset.set(t.id, 0); nav.toast = "已重置歌词偏移" }) else emptyList()))
}

/** Discord 状态（电脑版）：在 Discord 上显示正在听什么 */
object DiscordPref {
    var on by mutableStateOf(false)
    var clientId by mutableStateOf("")
    var status by mutableStateOf("")   // 连接状态，给设置页看
    private val k get() = Plat.prefs("ui")
    fun load() { on = k.getBoolean("dc_on", false); clientId = k.getString("dc_id", "") ?: "" }
    fun save() { k.edit().putBoolean("dc_on", on).putString("dc_id", clientId).apply() }
}
