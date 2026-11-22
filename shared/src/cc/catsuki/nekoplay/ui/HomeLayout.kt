package cc.catsuki.nekoplay.ui

import cc.catsuki.nekoplay.*
import cc.catsuki.nekoplay.core.*
import cc.catsuki.nekoplay.data.*
import cc.catsuki.nekoplay.player.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures

/** 首页布局：模块的顺序和开关，存成 "id:1,id:0,…" */
object HomeLayout {
    val ALL = listOf(
        "continue" to "继续收听", "daily" to "每日推荐", "fm" to "私人 FM", "discover" to "发现",
        "foryou" to "推荐歌单", "collections" to "推荐合集", "stats" to "统计",
        "charts" to "排行榜", "newalbums" to "新碟",
    )
    /** 模块要求首页来源里有插件实现的功能（QQ / 酷狗没有日推、FM 这些） */
    private val NEED = mapOf("daily" to "daily", "fm" to "fm", "foryou" to "recPlaylists", "charts" to "toplists", "newalbums" to "newAlbums")
    fun available(id: String): Boolean = when (id) {
        in NEED -> HomeSrc.has(NEED.getValue(id))
        "discover" -> HomeSrc.has("square") || HomeSrc.has("toplists") || Features.on("local")
        "stats" -> Features.on("stats")
        else -> pairOf(id)?.let { (a, b) -> available(a) || available(b) } ?: true
    }
    fun availableNow(id: String) = available(id)
    /** 新加的可选布局默认关着 */
    private val OFF_BY_DEFAULT = emptySet<String>()
    private val names = ALL.toMap()
    /** 并排组合：「pair:左+右」，两块各占一半 */
    fun pairOf(id: String): Pair<String, String>? = id.takeIf { it.startsWith("pair:") }?.removePrefix("pair:")?.split('+')?.takeIf { it.size == 2 && it.all { x -> x in names } }?.let { it[0] to it[1] }
    fun label(id: String) = pairOf(id)?.let { (a, b) -> "${names[a]} ＋ ${names[b]}" } ?: names[id] ?: id
    val items = mutableStateListOf<Pair<String, Boolean>>()
    val visible: List<String> get() = items.filter { it.second }.map { it.first }
    private var prefs: KV? = null

    fun init() {
        prefs = Plat.prefs("ui")
        val saved = prefs!!.getString("home_layout", "")!!.split(',').mapNotNull { e ->
            val on = e.substringAfterLast(':') == "1"
            // 旧的按音源分开的模块换成通用的
            when (val id = e.substringBeforeLast(':').replace("qqcharts", "charts").replace("kgcharts", "charts").replace("qqnew", "newalbums")) {
                "combo" -> "pair:continue+daily" to on          // 旧的固定组合
                in names -> id to on
                else -> if (pairOf(id) != null) id to on else null
            }
        }.distinctBy { it.first }
        items.clear(); items += saved
        // 新增的模块：QQ / 酷狗的放在「发现」后面，其它排在最后
        ALL.forEach { (id, _) ->
            if (items.none { it.first == id }) {
                val after = items.indexOfFirst { it.first == "discover" }
                if (id in setOf("charts", "newalbums") && after >= 0) items.add(after + 1 + items.drop(after + 1).takeWhile { it.first in setOf("charts", "newalbums") }.size, id to true)
                else items += id to (id !in OFF_BY_DEFAULT)
            }
        }
    }
    private fun save() { prefs?.edit()?.putString("home_layout", items.joinToString(",") { "${it.first}:${if (it.second) 1 else 0}" })?.apply() }
    fun toggle(i: Int) { items[i] = items[i].first to !items[i].second; save() }
    fun move(i: Int, d: Int) { val j = i + d; if (j !in items.indices) return; val x = items[i]; items[i] = items[j]; items[j] = x; save() }
    fun reset() { items.clear(); items += ALL.map { it.first to (it.first !in OFF_BY_DEFAULT) }; save() }
    /** 加一个并排组合（放在最前面，默认打开） */
    fun addPair(a: String, b: String) { val id = "pair:$a+$b"; if (items.none { it.first == id }) { items.add(0, id to true); save() } }
    fun remove(i: Int) { if (i in items.indices && pairOf(items[i].first) != null) { items.removeAt(i); save() } }
    fun moveTo(from: Int, to: Int) { if (from == to || from !in items.indices || to !in items.indices) return; val x = items.removeAt(from); items.add(to, x); save() }
}

@Composable fun HomeLayoutPage() {
    val p = c
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 24.dp)) {
        item {
            Column {
                Tag("HOME LAYOUT"); Spacer(Modifier.height(4.dp))
                T("首页布局", Fs.HERO, w = 800, lh = 1.3f); T("开关控制显示；按住左边的把手上下拖动排序", 13, p.s)
                Spacer(Modifier.height(12.dp)); Rule()
            }
        }
        itemsIndexed(HomeLayout.items, key = { _, it -> it.first }) { i, (id, on) ->
            val dragging = dragId == id
            Row(Modifier.fillMaxWidth().height(64.dp).then(if (dragging) Modifier else Modifier.animateItem())
                .graphicsLayer { translationY = if (dragging) dragDy else 0f; shadowElevation = if (dragging) 12f else 0f }
                .background(if (dragging) p.hl else Color.Transparent).bottomLine(), verticalAlignment = Alignment.CenterVertically) {
                // 把手：按住上下拖动排序
                Box(Modifier.size(40.dp, 64.dp).pointerInput(id) {
                    detectDragGestures(onDragStart = { dragId = id; dragDy = 0f }, onDragEnd = { dragId = null; dragDy = 0f }, onDragCancel = { dragId = null; dragDy = 0f }) { ch, d ->
                        ch.consume(); dragDy += d.y
                        val rowH = 64.dp.toPx()
                        val idx = HomeLayout.items.indexOfFirst { it.first == id }
                        if (dragDy > rowH / 2 && idx < HomeLayout.items.lastIndex) { HomeLayout.moveTo(idx, idx + 1); dragDy -= rowH }
                        else if (dragDy < -rowH / 2 && idx > 0) { HomeLayout.moveTo(idx, idx - 1); dragDy += rowH }
                    }
                }, contentAlignment = Alignment.Center) { NIcon(NIcons.queue, 18.dp, p.s) }
                T(two(i + 1), 13, if (on) p.p else p.s, 700, mono = true, modifier = Modifier.width(32.dp))
                Column(Modifier.weight(1f)) {
                    T(HomeLayout.label(id), 16, if (on) p.k else p.s, 700, lines = 2)
                    if (!HomeLayout.availableNow(id)) T("当前首页来源没有这个，不显示", 11, p.s)
                    else if (HomeLayout.pairOf(id) != null) T("并排 · 各占一半", 11, p.s, mono = true)
                }
                if (HomeLayout.pairOf(id) != null) Box(Modifier.size(40.dp).press { HomeLayout.remove(HomeLayout.items.indexOfFirst { it.first == id }) }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 12.dp, p.s) }
                NSwitch(on) { HomeLayout.toggle(i) }
            }
        }
        item {
            val nav = LocalNav.current
            Spacer(Modifier.height(16.dp))
            ActionRow {
                PrimaryBar("添加并排组合", NIcons.plus, Modifier.weight(1f)) {
                    // 先选左边，再选右边
                    // 只列出当前首页来源有的模块（比如 QQ 音乐没有日推、私人 FM）
                    val opts = HomeLayout.ALL.filter { HomeLayout.availableNow(it.first) }
                    fun pickRight(a: String) { nav.sheet = SheetSpec("右边放什么", "左边：${HomeLayout.label(a)}", opts.filter { it.first != a }.map { (id, n) -> SheetItem(n) { HomeLayout.addPair(a, id) } }) }
                    nav.sheet = SheetSpec("左边放什么", "两块并排各占一半；只列出当前首页来源（${HomeSrc.options.firstOrNull { it.second == HomeSrc.current }?.first ?: ""}）有的模块", opts.map { (id, n) -> SheetItem(n) { pickRight(id) } })
                }
                Spacer(Modifier.width(10.dp)); OutlineBtn("恢复默认", pad = 14.dp) { HomeLayout.reset() }
            }
        }
    }
}
