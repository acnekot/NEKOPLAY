package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
