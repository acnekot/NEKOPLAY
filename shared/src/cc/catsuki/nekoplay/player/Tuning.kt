package cc.catsuki.nekoplay.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.catsuki.nekoplay.*
import cc.catsuki.nekoplay.core.*
import cc.catsuki.nekoplay.data.*
import cc.catsuki.nekoplay.ui.*

/** 动效：形式和速度 */
@Composable fun TuningPage() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item { Column { Tag("MOTION"); Spacer(Modifier.height(4.dp)); T("动效", Fs.HERO, w = 800, lh = 1.3f); Spacer(Modifier.height(12.dp)); Rule() } }
        item { SetLine("动效形式", "弹簧有一点物理回弹，缓动先快后慢，线性匀速，关闭则瞬间切换") { ChipRow(AnimStyle.labels, AnimStyle.style) { AnimStyle.set(it) } } }
        item { SetLine("动效速度", "页面切换、播放页、队列、弹窗的动画快慢") { ChipRow(Anim.labels, Anim.level) { Anim.level = it; Plat.prefs("ui").edit().putInt("anim", it).apply() } } }
    }
}

/** 快捷键一览（桌面） */
@Composable fun KeysPage() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item { Column { Tag("KEYBOARD"); Spacer(Modifier.height(4.dp)); T("快捷键", Fs.HERO, w = 800, lh = 1.3f); Spacer(Modifier.height(12.dp)); Rule() } }
        items(Keys.HELP.size) { i -> val (k, d) = Keys.HELP[i]
            Row(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(150.dp)) { Tag(k) }; T(d, 14, lines = 2)
            }
        }
    }
}

/** 设置里带控件的一行：标题 + 说明 + 下面放滑条 / 选项 */
@Composable fun SetLine(title: String, sub: String, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().bottomLine().padding(vertical = 12.dp)) {
        T(title, 16); T(sub, 13, c.s, lines = 2); Spacer(Modifier.height(10.dp)); content()
    }
