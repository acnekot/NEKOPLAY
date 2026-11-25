package cc.catsuki.nekoplay

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 实体唱片机 / 磁带机风格实时变速快进与快退控制器：
 * - 长按「下一首」：实时平滑拉升播放倍速与音高（1.5x → 4.8x，关闭音高补偿），声音像真实转动电机一样加速升调；
 * - 长按「上一首」：高音调实时监听片段 + 加速回退步进，还原实体 CD 机/随身听快退听感；
 * - 松手瞬间平滑回落至正常 1.0x 并恢复原播放状态。
 */
object ScrubController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wasPlaying = false

    /** -1 快退 / 0 正常 / +1 快进 */
    var dir by mutableIntStateOf(0); private set
    /** 当前实时转速倍率（供界面显示 << 2.6× / >> 3.8×） */
    var rate by mutableFloatStateOf(1f); private set

    fun start(direction: Int) {
        val cur = Engine.state.value.current ?: return
        val audio = Plat.impl.audio
        job?.cancel()
        if (dir == 0) wasPlaying = Engine.state.value.playing
        dir = if (direction >= 0) 1 else -1
        if (!audio.isPlaying) audio.play()
        job = scope.launch {
            var elapsed = 0L
            if (dir > 0) {
                while (isActive) {
                    // 平滑电机加速曲线：1.5x → 4.8x（约 2.6 秒拉满）
                    val t = (elapsed / 2600f).coerceIn(0f, 1f)
                    val target = 1.5f + 3.3f * (t * (2f - t))
                    rate = target
                    audio.setRate(target, pitchLinked = true)
                    if (cur.durationMs > 0 && audio.position >= cur.durationMs - 250L) break
                    delay(55L); elapsed += 55L
                }
            } else {
                var r = 1.85f
                rate = r
                audio.setRate(r, pitchLinked = true)
                while (isActive) {
                    val t = (elapsed / 2400f).coerceIn(0f, 1f)
                    r = 1.85f + 1.75f * t
                    rate = r
                    audio.setRate(r, pitchLinked = true)
                    val stepMs = (680L + (1600L * t).toLong())
                    val nextPos = (audio.position - stepMs).coerceAtLeast(0L)
                    Engine.seek(nextPos)
                    if (nextPos <= 0L) break
                    delay(110L); elapsed += 110L
                }
            }
        }
    }

    fun stop() {
        if (dir == 0) return
        val prevPlaying = wasPlaying
        job?.cancel(); job = null
        dir = 0; rate = 1f
        val audio = Plat.impl.audio
        scope.launch {
            // 电机自然减速回落感：短暂过渡再恢复正常 1.0x
            runCatching {
                audio.setRate(1.38f, pitchLinked = true)
                delay(42L)
                audio.setRate(1f, pitchLinked = false)
                if (!prevPlaying) audio.pause()
            }
        }
    }
}

/** 短按切歌（<240ms）、长按实时变速快进/快退（松手即停） */
fun Modifier.holdScrub(dir: Int, enabled: Boolean = true, haptic: Boolean = false, onClick: () -> Unit): Modifier = composed {
    val h = LocalHapticFeedback.current
    val cs = rememberCoroutineScope()
    pointerInput(dir, enabled, onClick) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var scrubbing = false
            val timer = if (enabled) cs.launch {
                delay(240L)
                scrubbing = true
                if (haptic) h.performHapticFeedback(HapticFeedbackType.LongPress)
                ScrubController.start(dir)
            } else null
            val up = waitForUpOrCancellation()
            timer?.cancel()
            if (scrubbing) ScrubController.stop()
            else if (up != null) {
                if (haptic) h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
        }
    }
}
