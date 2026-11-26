package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
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
    val LINE_MODES = listOf("单行", "双行 · 译文", "双行 · 下一句")
    val ALIGNS = listOf("居中", "左对齐", "右对齐")
    val COLOR_NAMES = listOf("跟随主题", "冰蓝", "樱粉", "琥珀", "翠绿", "纯白")
    val COLORS = listOf(null, Color(0xFF5580F7), Color(0xFFFF5C8A), Color(0xFFFFB703), Color(0xFF2EC4B6), Color(0xFFFFFFFF))

    var on by mutableStateOf(false)          // 电脑：显示悬浮歌词窗
    var locked by mutableStateOf(false)      // 锁定：不能拖动，鼠标点击穿透
    var size by mutableIntStateOf(30)
    var x by mutableIntStateOf(-1); var y by mutableIntStateOf(-1)
    var notify by mutableStateOf(false)      // 手机：通知栏 / 状态栏歌词
    var showTr by mutableStateOf(true)       // 兼容旧字段：true = 译文优先
    var lineMode by mutableIntStateOf(1)     // 0 单行 / 1 双行(译文优先) / 2 双行(下一句)
    var align by mutableIntStateOf(0)        // 0 居中 / 1 左 / 2 右
    var colorIdx by mutableIntStateOf(0)     // 0 跟随主题 / 1..5 预设高亮色
    var bold by mutableStateOf(true)         // 粗体
    var stroke by mutableStateOf(true)       // 深色描边
    var karaoke by mutableStateOf(true)      // 逐字染色进度
    var textAlpha by mutableFloatStateOf(1f) // 歌词整体不透明度
    var bgAlpha by mutableFloatStateOf(0f)   // 常驻底板不透明度（0 = 仅悬停显示）
    /** 每次点击「回正」递增，通知桌面歌词窗口立即居中归位 */
    var resetRev by mutableIntStateOf(0); private set

    private val k get() = Plat.prefs("ui")
    fun load() {
        on = k.getBoolean("dl_on", false); locked = k.getBoolean("dl_lock", false); size = k.getInt("dl_size", 30).coerceIn(18, 60)
        x = k.getInt("dl_x", -1); y = k.getInt("dl_y", -1); notify = k.getBoolean("lyric_notify", false); showTr = k.getBoolean("dl_tr", true)
        lineMode = k.getInt("dl_lines", if (showTr) 1 else 2).coerceIn(0, 2)
        align = k.getInt("dl_align", 0).coerceIn(0, 2)
        colorIdx = k.getInt("dl_color", 0).coerceIn(0, COLORS.lastIndex)
        bold = k.getBoolean("dl_bold", true)
        stroke = k.getBoolean("dl_stroke", true)
        karaoke = k.getBoolean("dl_kara", true)
        textAlpha = k.getFloat("dl_alpha", 1f).coerceIn(.3f, 1f)
        bgAlpha = k.getFloat("dl_bg", 0f).coerceIn(0f, .85f)
    }
    fun save() {
        showTr = lineMode == 1
        k.edit().putBoolean("dl_on", on).putBoolean("dl_lock", locked).putInt("dl_size", size)
            .putInt("dl_x", x).putInt("dl_y", y).putBoolean("lyric_notify", notify).putBoolean("dl_tr", showTr)
            .putInt("dl_lines", lineMode).putInt("dl_align", align).putInt("dl_color", colorIdx)
            .putBoolean("dl_bold", bold).putBoolean("dl_stroke", stroke).putBoolean("dl_kara", karaoke)
            .putFloat("dl_alpha", textAlpha).putFloat("dl_bg", bgAlpha).apply()
    }
    fun resetPos() { x = -1; y = -1; save(); resetRev++ }

    /** 当前一句和下一句（或翻译），给通知栏用 */
    fun lineAt(lines: List<LyricLine>, pos: Long): Pair<String, String>? {
        if (lines.isEmpty()) return null
        val i = Lrc.indexAt(lines, pos).coerceAtLeast(0)
        val cur = lines.getOrNull(i) ?: return null
        val second = cur.tr ?: lines.drop(i + 1).firstOrNull { it.text.isNotEmpty() }?.text ?: ""
        return cur.text.ifEmpty { "♪" } to second
    }
}

/** 设置页里的桌面歌词详细配置项 */
fun LazyListScope.deskLyricSettings(p: Pal) {
    item { SwitchRow("锁定桌面歌词", "锁定后不能拖动且点击穿透；鼠标移到歌词上悬停 3 秒自动弹出解锁按钮", DeskLyric.locked) { DeskLyric.locked = it; DeskLyric.save() } }
    item {
        SetLine("桌面歌词字号", "悬浮歌词主句大小（18–60）") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { NSlider(DeskLyric.size.toFloat(), 18f..60f, onDone = { DeskLyric.save() }) { DeskLyric.size = it.toInt() } }
                Spacer(Modifier.width(10.dp)); T("${DeskLyric.size}", 14, p.p, 700, mono = true)
            }
        }
    }
    item { SetLine("显示行数", "单行极简、双行优先显示翻译，或双行显示下一句预告") { ChipRow(DeskLyric.LINE_MODES, DeskLyric.lineMode) { DeskLyric.lineMode = it; DeskLyric.save() } } }
    item { SetLine("水平对齐", "悬浮窗内文字的对齐方向") { ChipRow(DeskLyric.ALIGNS, DeskLyric.align) { DeskLyric.align = it; DeskLyric.save() } } }
    item { SetLine("高亮颜色", "已演唱部分的强调色") { ChipRow(DeskLyric.COLOR_NAMES, DeskLyric.colorIdx) { DeskLyric.colorIdx = it; DeskLyric.save() } } }
    item {
        SetLine("文字不透明度", "调低后更融入桌面壁纸") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { NSlider(DeskLyric.textAlpha, .3f..1f, onDone = { DeskLyric.save() }) { DeskLyric.textAlpha = it } }
                Spacer(Modifier.width(10.dp)); T("${(DeskLyric.textAlpha * 100).toInt()}%", 14, p.p, 700, mono = true)
            }
        }
    }
    item {
        SetLine("常驻背景底板", "0% 表示仅鼠标移上去时显示背景；调高后常驻半透明底条") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { NSlider(DeskLyric.bgAlpha, 0f..0.85f, onDone = { DeskLyric.save() }) { DeskLyric.bgAlpha = it } }
                Spacer(Modifier.width(10.dp)); T("${(DeskLyric.bgAlpha * 100).toInt()}%", 14, p.p, 700, mono = true)
            }
        }
    }
    item { SwitchRow("桌面歌词逐字高亮", "随播放进度平滑染色；关闭后整句以高亮色显示", DeskLyric.karaoke) { DeskLyric.karaoke = it; DeskLyric.save() } }
    item { SwitchRow("字体加粗", "开启使用 800 粗体，关闭使用 500 常规字重", DeskLyric.bold) { DeskLyric.bold = it; DeskLyric.save() } }
    item { SwitchRow("文字深色描边", "在浅色或复杂壁纸上保持歌词清晰可读", DeskLyric.stroke) { DeskLyric.stroke = it; DeskLyric.save() } }
    item {
        SetLine("悬浮窗位置", if (DeskLyric.x >= 0) "当前坐标：${DeskLyric.x}, ${DeskLyric.y}（点击立即水平居中回正）" else "当前为默认底部居中位置") {
            Box(Modifier.press { DeskLyric.resetPos() }.nbf(1.dp, p.k).padding(horizontal = 12.dp, vertical = 6.dp)) { T("一键回正", 12, p.k, 700) }
        }
    }
}

/**
 * 悬浮歌词：当前句（唱过的部分染成主色）+ 下面一行（翻译或下一句）。
 * 字带深色描边，放在任何壁纸上都看得清；换句时新句从下面淡入。没锁时鼠标移上去出现半透明底板和控制条。
 */
@Composable fun DeskLyricView(hover: Boolean, unlockReady: Boolean = false, unlockProgress: Float = 0f, onClose: () -> Unit) {
    val p = c
    val st by Engine.state.collectAsState()
    val t = st.current
    val offset = t?.let { LyricOffset.of(it.id) } ?: 0L
    DisposableEffect(Unit) { Engine.fastPos++; onDispose { Engine.fastPos-- } }
    var lines by remember(t?.id) { mutableStateOf(t?.let { LyricCache.peek(it.id) }) }
    LaunchedEffect(t?.id) { if (t != null && lines == null) lines = runCatching { LyricCache.get(t.id) }.getOrDefault(emptyList()) }
    // 播放中按帧推算进度（漂移校正），暂停时监听 seek；now 仅在 derivedStateOf / drawWithContent 读取，扫光期间 0 重组
    val now = remember(t?.id) { mutableLongStateOf(Engine.position.value + offset) }
    LaunchedEffect(t?.id, st.playing, offset) {
        now.longValue = Engine.position.value + offset
        if (!st.playing) {
            Engine.position.collect { raw -> now.longValue = raw + offset }
        } else {
            var base = now.longValue
            var s0 = withFrameMillis { it }
            while (true) {
                withFrameMillis { f ->
                    val eng = Engine.position.value + offset
                    val est = base + (f - s0)
                    if (kotlin.math.abs(eng - est) > 120L) { base = eng; s0 = f; now.longValue = eng }
                    else now.longValue = est
                }
            }
        }
    }
    val l = lines.orEmpty()
    val i by remember(l) { derivedStateOf { if (l.isEmpty()) -1 else Lrc.indexAt(l, now.longValue).coerceAtLeast(0) } }
    val cur = l.getOrNull(i)
    val text = cur?.text?.ifEmpty { "♪" } ?: t?.let { "${it.title} - ${it.artist}" } ?: "NEKOPLAY"
    val nextText = remember(l, i) { l.drop(i + 1).firstOrNull { it.text.isNotEmpty() }?.text ?: "" }
    val sub = when (DeskLyric.lineMode) {
        0 -> ""
        1 -> cur?.tr ?: nextText
        else -> nextText
    }
    val showBar = hover && !DeskLyric.locked
    val showUnlock = DeskLyric.locked && unlockReady
    val showUnlockHint = DeskLyric.locked && !unlockReady && unlockProgress > 0.1f
    val panel = androidx.compose.animation.core.animateFloatAsState(if (showBar || showUnlock) 1f else 0f, Motion.standard(), label = "pn")
    val panelVisible by remember { derivedStateOf { panel.value > 0.01f } }
    val ink = Color(0xFF0E1015)
    val accent = DeskLyric.COLORS.getOrNull(DeskLyric.colorIdx) ?: p.p
    val tAlign = when (DeskLyric.align) { 1 -> TextAlign.Start; 2 -> TextAlign.End; else -> TextAlign.Center }
    val hAlign = when (DeskLyric.align) { 1 -> Alignment.Start; 2 -> Alignment.End; else -> Alignment.CenterHorizontally }
    val main = remember(DeskLyric.size, DeskLyric.bold, Typo.family, tAlign) {
        TextStyle(fontSize = DeskLyric.size.sp, fontWeight = FontWeight(if (DeskLyric.bold) 800 else 500), fontFamily = Typo.family, textAlign = tAlign)
    }
    // 和 App 一样的风格：悬停时出现底板和控制条；锁定后悬停满 3 秒弹出解锁按钮
    Box(Modifier.fillMaxSize().padding(6.dp)
        .drawBehind {
            val pv = panel.value
            val bgA = maxOf(DeskLyric.bgAlpha, if (showBar) .92f * pv else .68f * pv)
            if (bgA > 0.005f) drawRect(p.page.copy(alpha = bgA))
            if (pv > 0f && !Look.flat) drawRect(p.k.copy(alpha = pv), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
        }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 6.dp), horizontalAlignment = hAlign, verticalArrangement = Arrangement.Center) {
            // 控制条：正常悬停显示完整工具栏；锁定后悬停 3 秒显示解锁与回正，悬停计时期间显示柔和倒计时条
            Row(Modifier.height(30.dp).align(Alignment.CenterHorizontally).graphicsLayer { alpha = if (showUnlockHint) .9f else panel.value }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                @Composable fun I(icon: String, on: Boolean = false, act: () -> Unit) = Box(Modifier.size(30.dp).press(.92f) { act() }.background(if (on) p.p else Color.Transparent).nbf(1.dp, p.k), contentAlignment = Alignment.Center) { NIcon(icon, 15.dp, if (on) Color.White else p.k) }
                @Composable fun L(label: String, on: Boolean = false, act: () -> Unit) = Box(Modifier.height(30.dp).defaultMinSize(minWidth = 30.dp).press(.92f) { act() }.background(if (on) p.p else Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) { T(trKnown(label), 12, if (on) Color.White else p.k, 700, mono = true) }
                when {
                    showUnlock -> {
                        L("解锁桌面歌词", true) { DeskLyric.locked = false; DeskLyric.save() }
                        L("回正") { DeskLyric.resetPos() }
                    }
                    showUnlockHint -> {
                        val remain = kotlin.math.ceil((1f - unlockProgress) * 3f).toInt().coerceIn(1, 3)
                        Column(Modifier.background(p.page.copy(alpha = .85f)).nbf(1.dp, p.k).padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            T(tr("保持悬停 ${remain}s 解锁"), 11, p.k, 700, mono = true)
                            Spacer(Modifier.height(2.dp))
                            Box(Modifier.width(96.dp).height(2.dp).background(p.l2)) {
                                Box(Modifier.fillMaxWidth(unlockProgress.coerceIn(0f, 1f)).fillMaxHeight().background(p.p))
                            }
                        }
                    }
                    panelVisible && !DeskLyric.locked -> {
                        I(NIcons.prev) { Engine.prev() }; I(if (st.playing) NIcons.pause else NIcons.play) { Engine.toggle() }; I(NIcons.next) { Engine.next() }
                        Spacer(Modifier.width(8.dp))
                        L("A−") { DeskLyric.size = (DeskLyric.size - 2).coerceAtLeast(18); DeskLyric.save() }
                        L("A+") { DeskLyric.size = (DeskLyric.size + 2).coerceAtMost(60); DeskLyric.save() }
                        L(when (DeskLyric.lineMode) { 0 -> "单"; 1 -> "译"; else -> "双" }, DeskLyric.lineMode != 0) { DeskLyric.lineMode = (DeskLyric.lineMode + 1) % 3; DeskLyric.save() }
                        L("回正") { DeskLyric.resetPos() }
                        Spacer(Modifier.width(8.dp))
                        I(NIcons.lock) { DeskLyric.locked = true; DeskLyric.save() }; I(NIcons.close) { onClose() }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            // 换句：新句从下面淡入
            androidx.compose.animation.AnimatedContent(text, Modifier.graphicsLayer { alpha = DeskLyric.textAlpha }, transitionSpec = {
                (androidx.compose.animation.fadeIn(Motion.standard()) + androidx.compose.animation.slideInVertically(Motion.standard()) { it / 4 }) togetherWith
                    androidx.compose.animation.fadeOut(Motion.quick())
            }, label = "dl") { tx ->
                var lay by remember { mutableStateOf<TextLayoutResult?>(null) }
                Box(contentAlignment = Alignment.Center) {
                    if (DeskLyric.stroke) BasicText(tx, style = main.copy(color = ink, drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(DeskLyric.size * .28f, join = androidx.compose.ui.graphics.StrokeJoin.Round)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(tx, style = main.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = { lay = it })
                    BasicText(tx, style = main.copy(color = accent), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.drawWithContent {
                        val lr = lay ?: return@drawWithContent
                        val f = when {
                            tx != text || !DeskLyric.karaoke -> 1f
                            cur == null -> 0f
                            cur.words != null -> {
                                val nt = now.longValue
                                val ws = cur.words; val total = ws.sumOf { it.s.length }.coerceAtLeast(1)
                                var done = 0f
                                for (w in ws) { val wf = ((nt - w.t).toFloat() / w.d.coerceAtLeast(1)).coerceIn(0f, 1f); done += wf * w.s.length; if (wf < 1f) break }
                                done / total
                            }
                            else -> {
                                val nt = now.longValue
                                val next = l.getOrNull(i + 1)?.t ?: (cur.t + 5000)
                                ((nt - cur.t).toFloat() / (next - cur.t).coerceAtLeast(300)).coerceIn(0f, 1f)
                            }
                        }
                        val left = lr.getLineLeft(0); val w = lr.getLineRight(0) - left
                        clipRect(right = left + w * f) { this@drawWithContent.drawContent() }
                    })
                }
            }
            if (sub.isNotEmpty()) {
                val st2 = remember(main, DeskLyric.size, DeskLyric.bold) {
                    main.copy(fontSize = (DeskLyric.size * .52f).sp, fontWeight = FontWeight(if (DeskLyric.bold) 600 else 400))
                }
                Box(Modifier.padding(top = 2.dp).graphicsLayer { alpha = DeskLyric.textAlpha }, contentAlignment = Alignment.Center) {
                    if (DeskLyric.stroke) BasicText(sub, style = st2.copy(color = ink, drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(DeskLyric.size * .16f, join = androidx.compose.ui.graphics.StrokeJoin.Round)), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
