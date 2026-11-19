package cc.catsuki.nekoplay

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 首页布局：模块的顺序和开关，存成 "id:1,id:0,…" */
object HomeLayout {
    val ALL = listOf(
        "continue" to "继续收听", "daily" to "每日推荐", "fm" to "私人 FM", "discover" to "发现",
        "foryou" to "推荐歌单", "collections" to "推荐合集", "stats" to "统计",
    )
    private val names = ALL.toMap()
    fun label(id: String) = names[id] ?: id
    val items = mutableStateListOf<Pair<String, Boolean>>()
    val visible: List<String> get() = items.filter { it.second }.map { it.first }
    private var prefs: android.content.SharedPreferences? = null

    fun init(c: Context) {
        prefs = c.getSharedPreferences("ui", 0)
        val saved = prefs!!.getString("home_layout", "")!!.split(',').mapNotNull { e -> e.split(':').takeIf { it.size == 2 && it[0] in names }?.let { it[0] to (it[1] == "1") } }
        items.clear(); items += saved
        ALL.forEach { (id, _) -> if (items.none { it.first == id }) items += id to true } // 新增的模块默认打开、排在最后
    }
    private fun save() { prefs?.edit()?.putString("home_layout", items.joinToString(",") { "${it.first}:${if (it.second) 1 else 0}" })?.apply() }
    fun toggle(i: Int) { items[i] = items[i].first to !items[i].second; save() }
    fun move(i: Int, d: Int) { val j = i + d; if (j !in items.indices) return; val x = items[i]; items[i] = items[j]; items[j] = x; save() }
    fun reset() { items.clear(); items += ALL.map { it.first to true }; save() }
}

@Composable fun HomeLayoutPage() {
    val p = c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 24.dp)) {
        item {
            Column {
                Tag("HOME LAYOUT"); Spacer(Modifier.height(4.dp))
                T("首页布局", Fs.HERO, w = 800, lh = 1.3f); T("开关控制显示，箭头调整上下顺序", 13, p.s)
                Spacer(Modifier.height(12.dp)); Rule()
            }
        }
        itemsIndexed(HomeLayout.items, key = { _, it -> it.first }) { i, (id, on) ->
            Row(Modifier.fillMaxWidth().height(64.dp).animateItem().bottomLine(), verticalAlignment = Alignment.CenterVertically) {
                T(two(i + 1), 13, if (on) p.p else p.s, 700, mono = true, modifier = Modifier.width(32.dp))
                T(HomeLayout.label(id), 16, if (on) p.k else p.s, 700, modifier = Modifier.weight(1f))
                Box(Modifier.size(40.dp).graphicsLayer { alpha = if (i > 0) 1f else .25f }.press { HomeLayout.move(i, -1) }, contentAlignment = Alignment.Center) { NIcon(NIcons.up, 18.dp) }
                Box(Modifier.size(40.dp).graphicsLayer { alpha = if (i < HomeLayout.items.lastIndex) 1f else .25f }.press { HomeLayout.move(i, 1) }, contentAlignment = Alignment.Center) { NIcon(NIcons.down, 18.dp) }
                NSwitch(on) { HomeLayout.toggle(i) }
            }
        }
        item { Spacer(Modifier.height(16.dp)); OutlineBtn("恢复默认") { HomeLayout.reset() } }
    }
}
