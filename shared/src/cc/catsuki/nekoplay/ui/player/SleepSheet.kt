package cc.catsuki.nekoplay.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.catsuki.nekoplay.LocalNav
import cc.catsuki.nekoplay.NIcon
import cc.catsuki.nekoplay.NIcons
import cc.catsuki.nekoplay.Nav
import cc.catsuki.nekoplay.SheetItem
import cc.catsuki.nekoplay.SheetSpec
import cc.catsuki.nekoplay.Sleep
import cc.catsuki.nekoplay.T
import cc.catsuki.nekoplay.c
import cc.catsuki.nekoplay.press
import kotlinx.coroutines.delay

fun sleepSheet(nav: Nav) {
    nav.sheet = SheetSpec("定时关闭", if (Sleep.active) "剩余 ${Sleep.left()}" else "到点后自动暂停", buildList {
        listOf(15, 30, 45, 60, 90).forEach { m -> add(SheetItem("$m 分钟") { Sleep.start(m); nav.toast = "$m 分钟后停止播放" }) }
        add(SheetItem("播完这首") { Sleep.startAfterSong(); nav.toast = "播完这首后停止" })
        add(SheetItem((if (Sleep.finishSong) "✓  " else "    ") + "到点后等这首播完再停") { Sleep.setFinish(!Sleep.finishSong); sleepSheet(nav) })
        if (Sleep.active) add(SheetItem("取消定时", danger = true) { Sleep.cancel(); nav.toast = "已取消定时关闭" })
    })
}

/** 播放页的定时按钮：没开时是个月亮图标，开着显示剩余时间 */
@Composable fun SleepBtn() {
    val nav = LocalNav.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Sleep.endAt) { while (Sleep.endAt > 0) { tick++; delay(1000) } }
    Box(Modifier.height(44.dp).defaultMinSize(minWidth = 44.dp).press(haptic = true) { sleepSheet(nav) }, contentAlignment = Alignment.Center) {
        @Suppress("UNUSED_VARIABLE") val t = tick
        if (Sleep.active) T(Sleep.left(), 12, c.p, 700, mono = true) else NIcon(NIcons.moon, 21.dp)
    }
}
