package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.ui.player.SleepBtn
import cc.catsuki.nekoplay.ui.settings.downloadSheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 播放页按钮布局：每个按钮放在哪个槽位、什么顺序，手机（也用于 PC 侧拉模式）和 PC 宽屏各一套。
 * 按钮属于某个插件时，插件关掉就不显示。
 */
object PlayerLayout {
    class B(val key: String, val name: String, val short: String, val feature: String? = null)
    val BTNS = listOf(
        B("like", "喜欢", ""), B("tr", "歌词翻译", "译"), B("mode", "播放模式", "模"),
        B("sleep", "定时关闭", "定", "sleep"), B("dl", "下载", "下", "dl"), B("more", "更多", "…"),
        B("comment", "评论", "评"), B("queue", "播放列表", "列"), B("card", "歌词卡片", "卡", "card"),
        B("offset", "歌词偏移", "偏", "offset"), B("desk", "桌面歌词", "桌", "desk"),
    )
    fun btn(k: String) = BTNS.first { it.key == k }
    fun avail(k: String) = btn(k).feature?.let { Features.on(it) } ?: true

    val PHONE_SLOTS = listOf("top" to "顶栏右侧", "title" to "歌名右侧", "left" to "底栏左边", "right" to "底栏右边", "hide" to "不显示")
    val PC_SLOTS = listOf("side" to "歌词旁竖栏", "info" to "歌名右侧", "bottom" to "控制条下面", "hide" to "不显示")
    private val PHONE_DEF = mapOf("top" to listOf("tr"), "title" to listOf("like"), "left" to listOf("mode", "sleep"), "right" to listOf("more", "comment", "queue"))
    private val PC_DEF = mapOf("info" to emptyList(), "side" to listOf("tr", "like", "dl", "mode", "sleep", "comment", "queue", "more"), "bottom" to emptyList())

    var phone by mutableStateOf(PHONE_DEF)
    var pc by mutableStateOf(PC_DEF)

    private fun get(isPc: Boolean) = if (isPc) pc else phone
    private fun put(isPc: Boolean, m: Map<String, List<String>>) { if (isPc) pc = m else phone = m; save() }
    fun slots(isPc: Boolean) = if (isPc) PC_SLOTS else PHONE_SLOTS
    /** 槽位里实际显示的按钮（已排除关掉的插件） */
    fun keys(isPc: Boolean, slot: String) = get(isPc)[slot].orEmpty().filter { avail(it) }
    fun slotOf(isPc: Boolean, k: String) = get(isPc).entries.firstOrNull { k in it.value }?.key ?: "hide"
    /** 不显示的按钮：没放进任何槽位的都算 */
    fun hidden(isPc: Boolean) = BTNS.map { it.key }.filter { avail(it) && slotOf(isPc, it) == "hide" }

    fun move(isPc: Boolean, k: String, slot: String) {
        val m = get(isPc).mapValues { (_, v) -> v - k }.toMutableMap()
        if (slot != "hide") m[slot] = m[slot].orEmpty() + k
        put(isPc, m)
    }
    fun shift(isPc: Boolean, k: String, d: Int) {
        val s = slotOf(isPc, k); if (s == "hide") return
        val l = get(isPc)[s].orEmpty().toMutableList(); val i = l.indexOf(k); val j = i + d
        if (j !in l.indices) return
        l[i] = l[j]; l[j] = k
        put(isPc, get(isPc) + (s to l))
    }
    fun reset(isPc: Boolean) = put(isPc, if (isPc) PC_DEF else PHONE_DEF)

    private fun enc(m: Map<String, List<String>>) = m.entries.joinToString(";") { "${it.key}=${it.value.joinToString(",")}" }
    private fun dec(s: String?): Map<String, List<String>>? = s?.takeIf { it.isNotBlank() }?.split(";")?.mapNotNull {
        val kv = it.split("=", limit = 2); if (kv.size < 2) null else kv[0] to kv[1].split(",").filter { k -> BTNS.any { b -> b.key == k } }
    }?.toMap()
    fun load() {
        val k = Plat.prefs("ui")
        phone = dec(k.getString("lay_phone", null)) ?: PHONE_DEF.let { d ->
            // 旧版「播放页按钮」开关：关掉的按钮放进「不显示」
            val old = k.getStringSet("btns", null) ?: return@let d
            d.mapValues { (_, v) -> v.filter { it in old || it == "like" || it == "tr" } }
        }
        pc = dec(k.getString("lay_pc2", null)) ?: PC_DEF
    }
    private fun save() { Plat.prefs("ui").edit().putString("lay_phone", enc(phone)).putString("lay_pc2", enc(pc)).apply() }
}

/** 按钮渲染要用到的东西 */
class BtnCtx(val t: Track, val st: PlayState, val showTr: Boolean, val toggleTr: () -> Unit)

/** 一个播放页按钮（44×44） */
@Composable fun PBtn(k: String, x: BtnCtx) {
    val nav = LocalNav.current
    val p = c
    val t = x.t
    when (k) {
        "like" -> {
            val liked by Store.likedIds.collectAsState(); val on = t.id in liked
            Box(Modifier.size(44.dp).press(.85f, true) { Store.toggleLike(t) { nav.toast = it } }, contentAlignment = Alignment.Center) {
                NIcon(if (on) NIcons.heart else NIcons.heartLine, 24.dp, if (on) p.p else p.k)
            }
        }
        "tr" -> TextBtn("译", x.showTr) { x.toggleTr() }
        "desk" -> TextBtn("桌", DeskLyric.on) { DeskLyric.on = !DeskLyric.on; DeskLyric.save(); nav.toast = if (DeskLyric.on) "已打开桌面歌词" else "已关闭桌面歌词" }
        "mode" -> Box(Modifier.size(44.dp).press(haptic = true) { Engine.cycleMode(); nav.toast = listOf("列表循环", "单曲循环", "随机播放")[Engine.state.value.mode.ordinal] }, contentAlignment = Alignment.Center) {
            NIcon(when (x.st.mode) { Mode.LOOP_ALL -> NIcons.modeSeq; Mode.LOOP_ONE -> NIcons.modeOne; Mode.SHUFFLE -> NIcons.modeShuffle }, 22.dp)
        }
        "sleep" -> SleepBtn()
        "dl" -> {
            val dls by Downloads.items.collectAsState(); val act by Downloads.active.collectAsState()
            val has = dls.any { it.track.id == t.id }; val ing = t.id in act
            Box(Modifier.size(44.dp).press(haptic = true) { if (has) nav.toast = "已经下载过了" else if (!ing) downloadSheet(nav, listOf(t)) }, contentAlignment = Alignment.Center) {
                if (ing) T(act[t.id]!!.let { if (it < 0) "…" else "%.0f".format(it * 100) }, 12, p.p, 700, mono = true) else NIcon(NIcons.download, 22.dp, if (has) p.p else p.k)
            }
        }
        "more" -> IconBtn(NIcons.more) { trackActions(nav, t) }
        "comment" -> IconBtn(NIcons.comment) { nav.comments = if (nav.comments != null) null else t }  // 再点一次关掉
        "queue" -> IconBtn(NIcons.queue) { nav.queue = true }
        "card" -> IconBtn(NIcons.image) { nav.player = false; nav.push(Page.LyricCard(t)) }
        "offset" -> IconBtn(NIcons.shift) { offsetSheet(nav, t) }
    }
}

@Composable private fun IconBtn(icon: String, onClick: () -> Unit) =
    Box(Modifier.size(44.dp).press(haptic = true, onClick = onClick), contentAlignment = Alignment.Center) { NIcon(icon, 22.dp) }

@Composable private fun TextBtn(s: String, on: Boolean, onClick: () -> Unit) {
    val p = c
    Box(Modifier.size(44.dp).press(.9f, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(24.dp).background(if (on) p.k else Color.Transparent).nbf(1.dp, p.k), contentAlignment = Alignment.Center) {
            T(s, 12, if (on) p.tagFg else p.k, 700)
        }
    }
}

/** 一个槽位横排 */
@Composable fun SlotRow(isPc: Boolean, slot: String, x: BtnCtx, gap: Dp = 6.dp) {
    val ks = PlayerLayout.keys(isPc, slot)
    ks.forEachIndexed { i, k -> if (i > 0) Spacer(Modifier.width(gap)); PBtn(k, x) }
}
/** 一个槽位竖排 */
@Composable fun SlotCol(isPc: Boolean, slot: String, x: BtnCtx, gap: Dp = 6.dp) {
    val ks = PlayerLayout.keys(isPc, slot)
    ks.forEachIndexed { i, k -> if (i > 0) Spacer(Modifier.height(gap)); PBtn(k, x) }
}

// ======================= 设置 → 播放页布局 =======================
@Composable fun PlayerLayoutPage() {
    val nav = LocalNav.current
    val p = c
    var isPc by remember { mutableStateOf(Plat.isDesktop) }
    LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 28.dp)) {
        item {
            ChipRow(listOf(if (Plat.isDesktop) "手机 / 侧拉" else "竖屏", if (Plat.isDesktop) "PC 宽屏" else "横屏 / 平板"), if (isPc) 1 else 0) { isPc = it == 1 }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { if (isPc) PcPreview() else PhonePreview() }
            Spacer(Modifier.height(6.dp))
        }
        PlayerLayout.slots(isPc).forEach { (slot, label) ->
            val ks = if (slot == "hide") PlayerLayout.hidden(isPc) else PlayerLayout.keys(isPc, slot)
            item(key = "$isPc$slot") { Box(Modifier.padding(top = 18.dp, bottom = 6.dp)) { Tag(label.uppercase()) } }
            if (ks.isEmpty()) item(key = "$isPc$slot-e") { T("空", 13, p.s, modifier = Modifier.padding(vertical = 8.dp)) }
            ks.forEachIndexed { i, k ->
                item(key = "$isPc$slot$k") {
                    val b = PlayerLayout.btn(k)
                    Row(Modifier.fillMaxWidth().height(52.dp).bottomLine(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(26.dp).nbf(1.dp, p.k), contentAlignment = Alignment.Center) { if (k == "like") NIcon(NIcons.heartLine, 14.dp) else T(b.short, 12, p.k, 700) }
                        Spacer(Modifier.width(12.dp))
                        T(b.name, 16, modifier = Modifier.weight(1f))
                        if (slot != "hide") {
                            Box(Modifier.size(40.dp).press { PlayerLayout.shift(isPc, k, -1) }, contentAlignment = Alignment.Center) { NIcon(NIcons.up, 16.dp, if (i > 0) p.k else p.s.copy(alpha = .4f)) }
                            Box(Modifier.size(40.dp).press { PlayerLayout.shift(isPc, k, 1) }, contentAlignment = Alignment.Center) { NIcon(NIcons.down, 16.dp, if (i < ks.size - 1) p.k else p.s.copy(alpha = .4f)) }
                        }
                        LinkBtn("移到…", 13) {
                            nav.sheet = SheetSpec("「${b.name}」放在", null, PlayerLayout.slots(isPc).map { (s, l) ->
                                SheetItem((if (s == slot) "✓  " else "    ") + l) { PlayerLayout.move(isPc, k, s) }
                            })
                        }
                    }
                }
            }
        }
        item { Box(Modifier.padding(top = 22.dp)) { LinkBtn("恢复默认布局", 14, p.bad) { PlayerLayout.reset(isPc); nav.toast = "已恢复默认" } } }
    }
}

/** 预览里的小方块按钮 */
@Composable private fun Mini(k: String) {
    val p = c
    Box(Modifier.size(18.dp).nbf(1.dp, p.k).background(p.page), contentAlignment = Alignment.Center) { if (k == "like") NIcon(NIcons.heartLine, 11.dp) else T(PlayerLayout.btn(k).short, 9, p.k, 700) }
}
@Composable private fun MiniRow(isPc: Boolean, slot: String) = Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { PlayerLayout.keys(isPc, slot).forEach { Mini(it) } }
@Composable private fun MiniCol(isPc: Boolean, slot: String) = Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { PlayerLayout.keys(isPc, slot).forEach { Mini(it) } }
@Composable private fun Bar(w: Dp, h: Dp = 6.dp, a: Float = .25f) = Box(Modifier.size(w, h).background(c.k.copy(alpha = a)))

@Composable private fun PhonePreview() {
    val p = c
    Column(Modifier.width(170.dp).nbf(2.dp, p.k).padding(8.dp)) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) { T("⌄", 11, p.s); Spacer(Modifier.weight(1f)); MiniRow(false, "top") }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(p.k.copy(alpha = .12f)))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Bar(80.dp, 8.dp, .5f); Spacer(Modifier.height(3.dp)); Bar(50.dp) }; MiniRow(false, "title") }
        Spacer(Modifier.height(8.dp)); Bar(154.dp, 2.dp, .4f); Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Bar(14.dp, 14.dp); Spacer(Modifier.width(8.dp)); Box(Modifier.size(16.dp).background(p.p)); Spacer(Modifier.width(8.dp)); Bar(14.dp, 14.dp) }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) { MiniRow(false, "left"); Spacer(Modifier.weight(1f)); MiniRow(false, "right") }
    }
}

@Composable private fun PcPreview() {
    val p = c
    Row(Modifier.width(300.dp).height(190.dp).nbf(2.dp, p.k).padding(8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.width(18.dp).fillMaxHeight()) { T("⌄", 11, p.s) }
        Column(Modifier.width(84.dp)) {
            Box(Modifier.size(84.dp).background(p.k.copy(alpha = .12f)))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.width(84.dp)) { Column(Modifier.weight(1f)) { Bar(56.dp, 7.dp, .5f); Spacer(Modifier.height(3.dp)); Bar(36.dp) }; MiniCol(true, "info") }
            Spacer(Modifier.height(4.dp)); Bar(84.dp, 2.dp, .4f); Spacer(Modifier.height(4.dp))
            Row(Modifier.width(84.dp), horizontalArrangement = Arrangement.Center) { Bar(10.dp, 10.dp); Spacer(Modifier.width(5.dp)); Box(Modifier.size(12.dp).background(p.p)); Spacer(Modifier.width(5.dp)); Bar(10.dp, 10.dp) }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.width(84.dp), horizontalArrangement = Arrangement.Center) { MiniRow(true, "bottom") }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.width(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { PlayerLayout.keys(true, "side").forEach { Mini(it) } }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Bar(90.dp, 8.dp, .55f); repeat(6) { Bar((70 - it * 6).dp, 6.dp, .2f) } }
    }
}
