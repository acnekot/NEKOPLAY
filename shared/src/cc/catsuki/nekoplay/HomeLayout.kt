package cc.catsuki.nekoplay

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
        "combo" to "继续收听 + 每日推荐（并排）",
    )
    /** 新加的可选布局默认关着 */
    private val OFF_BY_DEFAULT = setOf("combo")
    private val names = ALL.toMap()
    fun label(id: String) = names[id] ?: id
    val items = mutableStateListOf<Pair<String, Boolean>>()
    val visible: List<String> get() = items.filter { it.second }.map { it.first }
    private var prefs: KV? = null

    fun init() {
        prefs = Plat.prefs("ui")
        val saved = prefs!!.getString("home_layout", "")!!.split(',').mapNotNull { e -> e.split(':').takeIf { it.size == 2 && it[0] in names }?.let { it[0] to (it[1] == "1") } }
        items.clear(); items += saved
        ALL.forEach { (id, _) -> if (items.none { it.first == id }) items += id to (id !in OFF_BY_DEFAULT) } // 新增的模块排在最后
    }
    private fun save() { prefs?.edit()?.putString("home_layout", items.joinToString(",") { "${it.first}:${if (it.second) 1 else 0}" })?.apply() }
    fun toggle(i: Int) { items[i] = items[i].first to !items[i].second; save() }
    fun move(i: Int, d: Int) { val j = i + d; if (j !in items.indices) return; val x = items[i]; items[i] = items[j]; items[j] = x; save() }
    fun reset() { items.clear(); items += ALL.map { it.first to (it.first !in OFF_BY_DEFAULT) }; save() }
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
                T(HomeLayout.label(id), 16, if (on) p.k else p.s, 700, modifier = Modifier.weight(1f))
                NSwitch(on) { HomeLayout.toggle(i) }
            }
        }
        item { Spacer(Modifier.height(16.dp)); OutlineBtn("恢复默认") { HomeLayout.reset() } }
    }
}
