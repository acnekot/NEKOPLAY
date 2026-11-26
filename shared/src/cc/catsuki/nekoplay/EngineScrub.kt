package cc.catsuki.nekoplay

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 实体唱片机 / 磁带机风格实时变速快进与快退控制器：
 * - 全程运行在 Dispatchers.Main，保证 Android ExoPlayer / iOS AVPlayer / Compose 快照状态线程安全；
 * - 长按「下一首」：实时平滑拉升播放倍速与音高（1.5x → 3.8x，关闭音高补偿），声音像真实转动电机一样加速升调；
 * - 长按「上一首」：高音调实时监听片段 + 加速回退步进，还原实体 CD 机/随身听快退听感；
 * - 松手或手势取消时立即取消加速任务并平滑回落至正常 1.0x，恢复原播放状态。
 */
object ScrubController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var stopJob: Job? = null
    private var wasPlaying = false

    /** -1 快退 / 0 正常 / +1 快进 */
    var dir by mutableIntStateOf(0); private set
    /** 当前实时转速倍率（供界面显示 << 2.6× / >> 3.8×） */
    var rate by mutableFloatStateOf(1f); private set

    fun start(direction: Int) {
        val cur = Engine.state.value.current ?: return
        val audio = Plat.impl.audio
        if (audio.idle) return
        stopJob?.cancel(); stopJob = null
        job?.cancel(); job = null
        if (dir == 0) wasPlaying = Engine.state.value.playing
        dir = if (direction >= 0) 1 else -1
        runCatching { if (!audio.isPlaying) audio.play() }
        job = scope.launch {
            var elapsed = 0L
            if (dir > 0) {
                while (isActive) {
                    val t = (elapsed / 2400f).coerceIn(0f, 1f)
                    val target = 1.5f + 2.3f * (t * (2f - t))
                    rate = target
                    runCatching { audio.setRate(target, pitchLinked = true) }
                    val pos = runCatching { audio.position }.getOrDefault(0L)
                    if (cur.durationMs > 0 && pos >= cur.durationMs - 300L) break
                    delay(90L); elapsed += 90L
                }
            } else {
                var r = 1.85f
                rate = r
                runCatching { audio.setRate(r, pitchLinked = true) }
                while (isActive) {
                    val t = (elapsed / 2400f).coerceIn(0f, 1f)
                    r = 1.85f + 1.55f * t
                    rate = r
                    runCatching { audio.setRate(r, pitchLinked = true) }
                    val stepMs = 680L + (1500L * t).toLong()
                    val curPos = runCatching { audio.position }.getOrDefault(Engine.position.value)
                    val nextPos = (curPos - stepMs).coerceAtLeast(0L)
                    runCatching { Engine.seek(nextPos) }
                    if (nextPos <= 0L) break
                    delay(120L); elapsed += 120L
                }
            }
        }
    }

    fun stop() {
        if (dir == 0 && job == null) return
        val prevPlaying = wasPlaying
        job?.cancel(); job = null
        stopJob?.cancel(); stopJob = null
        dir = 0; rate = 1f
        val audio = Plat.impl.audio
        stopJob = scope.launch {
            runCatching {
                audio.setRate(1.32f, pitchLinked = true)
                delay(45L)
                audio.setRate(1f, pitchLinked = false)
                if (!prevPlaying) audio.pause()
            }
        }
    }
}

/** 短按切歌（<240ms）、长按实时变速快进/快退（松手、取消或离开页面均立即恢复正常倍速） */
fun Modifier.holdScrub(dir: Int, enabled: Boolean = true, haptic: Boolean = false, onClick: () -> Unit): Modifier = composed {
    val h = LocalHapticFeedback.current
    val cs = rememberCoroutineScope()
    val latestClick by rememberUpdatedState(onClick)
    val latestEnabled by rememberUpdatedState(enabled)
    DisposableEffect(dir) {
        onDispose { if (ScrubController.dir == dir) ScrubController.stop() }
    }
    pointerInput(dir) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var scrubbing = false
            val timer = if (latestEnabled) cs.launch {
                delay(240L)
                scrubbing = true
                if (haptic) runCatching { h.performHapticFeedback(HapticFeedbackType.LongPress) }
                ScrubController.start(dir)
            } else null
            try {
                val up = waitForUpOrCancellation()
                timer?.cancel()
                if (scrubbing) {
                    scrubbing = false
                    ScrubController.stop()
                } else if (up != null) {
                    if (haptic) runCatching { h.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    latestClick()
                }
            } finally {
                timer?.cancel()
                if (scrubbing) ScrubController.stop()
            }
        }
    }
}
