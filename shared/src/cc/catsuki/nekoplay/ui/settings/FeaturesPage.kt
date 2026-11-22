package cc.catsuki.nekoplay.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.catsuki.nekoplay.Features
import cc.catsuki.nekoplay.Nav
import cc.catsuki.nekoplay.SheetItem
import cc.catsuki.nekoplay.SheetSpec
import cc.catsuki.nekoplay.SidePref
import cc.catsuki.nekoplay.SwitchRow
import cc.catsuki.nekoplay.T
import cc.catsuki.nekoplay.Tag
import cc.catsuki.nekoplay.c

/** 设置 → 功能管理 */
@Composable fun FeaturesPage() {
    val p = c
    LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 28.dp)) {
        item { T("关掉的功能会隐藏它的按钮、菜单和设置，也不再在后台运行", 13, p.s, lh = 1.4f, modifier = Modifier.padding(bottom = 10.dp)) }
        Features.list.groupBy { it.group }.forEach { (g, fs) ->
            if (g.isNotEmpty()) item(key = "g$g") { Box(Modifier.padding(top = 18.dp, bottom = 4.dp)) { Tag(g) } }
            items(fs, key = { it.id }) { f -> SwitchRow(f.name, f.desc, Features.on(f.id)) { Features.set(f.id, it) } }
        }
    }
}

/** PC 侧拉模式弹窗菜单 */
fun SidePref.menu(nav: Nav) {
    nav.sheet = SheetSpec("侧拉模式", "贴在屏幕边上，鼠标离开自动收起，碰到边缘再滑出来", buildList {
        add(SheetItem((if (active == -1) "✓  " else "    ") + "贴在左边") { enter?.invoke(-1) })
        add(SheetItem((if (active == 1) "✓  " else "    ") + "贴在右边") { enter?.invoke(1) })
        if (active != 0) add(SheetItem("退出侧拉模式") { exit?.invoke() })
    })
}
