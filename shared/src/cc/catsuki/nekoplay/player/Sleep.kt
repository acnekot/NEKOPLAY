package cc.catsuki.nekoplay.player

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import cc.catsuki.nekoplay.*
import cc.catsuki.nekoplay.core.*
import cc.catsuki.nekoplay.data.*
import cc.catsuki.nekoplay.ui.*
import kotlinx.coroutines.*

/** 定时关闭：N 分钟后停，或者播完当前这首停；可选「到点后等这首播完再停」 */
object Sleep {
    var endAt by mutableLongStateOf(0L)       // 到点的时间（0 = 没有计时）
    var afterSong by mutableStateOf(false)    // 播完当前这首就停
    var finishSong by mutableStateOf(true)    // 到点后不立刻停，等这首播完
    private var due = false                   // 已经到点，等这首结束
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val active get() = endAt > 0 || afterSong || due

    fun load() { finishSong = Plat.prefs("ui").getBoolean("sleepFinish", true) }
    fun setFinish(v: Boolean) { finishSong = v; Plat.prefs("ui").edit().putBoolean("sleepFinish", v).apply() }

    fun start(min: Int) {
        cancel(); endAt = System.currentTimeMillis() + min * 60_000L
        job = scope.launch {
            delay(min * 60_000L); endAt = 0
            if (finishSong && Engine.isPlaying) due = true else Engine.fadePause()
        }
    }
    fun startAfterSong() { cancel(); afterSong = true }
    fun cancel() { job?.cancel(); job = null; endAt = 0; afterSong = false; due = false }

    /** 一首播完时问一下：要不要停在这里（停了就把计时清掉） */
    fun stopAtEnd(): Boolean = (afterSong || due).also { if (it) cancel() }
    /** 无缝播放要不要提前接下一首：定时快到了就不接 */
    val holdNext get() = afterSong || due

    /** 剩余时间文字，比如 23:05；「播完这首」显示「本首」 */
    fun left(now: Long = System.currentTimeMillis()): String = when {
        afterSong || due -> "本首"
        endAt > 0 -> ((endAt - now).coerceAtLeast(0) / 1000).let { "%d:%02d".format(it / 60, it % 60) }
        else -> ""
    }
}

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
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Sleep.endAt) { while (Sleep.endAt > 0) { now = System.currentTimeMillis(); delay(1000) } }
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.height(44.dp).defaultMinSize(minWidth = 44.dp).press(haptic = true) { sleepSheet(nav) }, contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (Sleep.active) T(Sleep.left(now), 12, c.p, 700, mono = true) else NIcon(NIcons.moon, 21.dp)
    }
}
