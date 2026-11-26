package cc.catsuki.nekoplay

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * `@feature scrub` 功能插件的热更新参数（未安装/未启用该插件时主软件不激活长按加速）。
 */
data class ScrubCfg(
    val holdMs: Long = 240L,
    val pitchLinked: Boolean = true,
    val fwdStart: Float = 1.5f,
    val fwdMax: Float = 3.8f,
    val fwdRampMs: Float = 2400f,
    val revStart: Float = 1.85f,
    val revMax: Float = 3.4f,
    val revStepMinMs: Long = 680L,
    val revStepMaxMs: Long = 2180L,
) {
    companion object {
        fun from(o: JSONObject?): ScrubCfg {
            if (o == null) return ScrubCfg()
            val fwdS = o.optDouble("fwdStart", 1.5).toFloat().coerceIn(1.1f, 4.0f)
            val fwdM = o.optDouble("fwdMax", 3.8).toFloat().coerceIn(fwdS, 5.0f)
            val revS = o.optDouble("revStart", 1.85).toFloat().coerceIn(1.1f, 4.0f)
            val revM = o.optDouble("revMax", 3.4).toFloat().coerceIn(revS, 5.0f)
            val minStep = o.optLong("revStepMinMs", 680L).coerceIn(200L, 4000L)
            val maxStep = o.optLong("revStepMaxMs", 2180L).coerceIn(minStep, 8000L)
            return ScrubCfg(
                holdMs = o.optLong("holdMs", 240L).coerceIn(120L, 1200L),
                pitchLinked = o.optBoolean("pitchLinked", true),
                fwdStart = fwdS,
                fwdMax = fwdM,
                fwdRampMs = o.optDouble("fwdRampMs", 2400.0).toFloat().coerceIn(400f, 8000f),
                revStart = revS,
                revMax = revM,
                revStepMinMs = minStep,
                revStepMaxMs = maxStep,
            )
        }
    }
}

/**
 * `@feature scrub` 插件宿主桥接控制器：
 * - 主软件本身不内置启用长按加速，仅当用户安装并启用 `@feature scrub` 功能插件时才接管长按手势；
 * - 全程运行在 Dispatchers.Main，确保 Android ExoPlayer / iOS AVPlayer / Compose 快照状态线程安全；
 * - 参数（触发时长、变速曲线、是否变调、快退步进）均由插件 `config()` 热更新驱动。
 */
object ScrubController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var stopJob: Job? = null
    private var syncJob: Job? = null
    private var wasPlaying = false
    private var cfgRev = -1

    /** -1 快退 / 0 正常 / +1 快进 */
    var dir by mutableIntStateOf(0); private set
    /** 当前实时转速倍率（供界面显示 << 2.6× / >> 3.8×） */
    var rate by mutableFloatStateOf(1f); private set
    /** 当前生效的插件配置 */
    var cfg by mutableStateOf(ScrubCfg()); private set

    fun syncFromPlugin() {
        val rev = Plugins.rev
        if (!Plugins.hasFeature("scrub")) {
            cfgRev = rev
            stop()
            return
        }
        if (cfgRev == rev) return
        cfgRev = rev
        syncJob?.cancel()
        syncJob = scope.launch {
            val loaded = withContext(Dispatchers.Default) {
                runCatching { ScrubCfg.from(Plugins.featureConfig("scrub")) }.getOrDefault(ScrubCfg())
            }
            if (Plugins.hasFeature("scrub")) cfg = loaded
        }
    }

    fun start(direction: Int) {
        if (!Plugins.hasFeature("scrub")) return
        val cur = Engine.state.value.current ?: return
        val audio = Plat.impl.audio
        if (audio.idle) return
        val c = cfg
        stopJob?.cancel(); stopJob = null
        job?.cancel(); job = null
        if (dir == 0) wasPlaying = Engine.state.value.playing
        dir = if (direction >= 0) 1 else -1
        runCatching { if (!audio.isPlaying) audio.play() }
        job = scope.launch {
            var elapsed = 0L
            if (dir > 0) {
                while (isActive && Plugins.hasFeature("scrub")) {
                    val t = (elapsed / c.fwdRampMs).coerceIn(0f, 1f)
                    val target = c.fwdStart + (c.fwdMax - c.fwdStart) * (t * (2f - t))
                    rate = target
                    runCatching { audio.setRate(target, pitchLinked = c.pitchLinked) }
                    val pos = runCatching { audio.position }.getOrDefault(0L)
                    if (cur.durationMs > 0 && pos >= cur.durationMs - 300L) break
                    delay(90L); elapsed += 90L
                }
            } else {
                var r = c.revStart
                rate = r
                runCatching { audio.setRate(r, pitchLinked = c.pitchLinked) }
                while (isActive && Plugins.hasFeature("scrub")) {
                    val t = (elapsed / c.fwdRampMs).coerceIn(0f, 1f)
                    r = c.revStart + (c.revMax - c.revStart) * t
                    rate = r
                    runCatching { audio.setRate(r, pitchLinked = c.pitchLinked) }
                    val stepMs = c.revStepMinMs + ((c.revStepMaxMs - c.revStepMinMs) * t).toLong()
                    val curPos = runCatching { audio.position }.getOrDefault(Engine.position.value)
                    val nextPos = (curPos - stepMs).coerceAtLeast(0L)
                    runCatching { Engine.seek(nextPos) }
                    if (nextPos <= 0L) break
                    delay(120L); elapsed += 120L
                }
            }
            if (!Plugins.hasFeature("scrub")) stop()
        }
    }

    fun stop() {
        if (dir == 0 && job == null) return
        val prevPlaying = wasPlaying
        val linked = cfg.pitchLinked
        job?.cancel(); job = null
        stopJob?.cancel(); stopJob = null
        dir = 0; rate = 1f
        val audio = Plat.impl.audio
        stopJob = scope.launch {
            runCatching {
                if (linked) {
                    audio.setRate(1.32f, pitchLinked = true)
                    delay(45L)
                }
                audio.setRate(1f, pitchLinked = false)
                if (!prevPlaying) audio.pause()
            }
        }
    }
}

/**
 * 切歌按钮扩展槽位：
 * - 未安装或关闭 `@feature scrub` 功能插件时，完全退化为普通点击按钮 `Modifier.press(...)`，主软件不包含长按加速行为；
 * - 启用 `@feature scrub` 插件后，短按切歌、长按按插件配置触发实时变速快进/快退。
 */
fun Modifier.holdScrub(
    dir: Int,
    enabled: Boolean = true,
    haptic: Boolean = false,
    scale: Float = 1f,
    onClick: () -> Unit,
): Modifier = composed {
    val rev = Plugins.rev
    val pluginOn = remember(rev) { Plugins.hasFeature("scrub") }
    LaunchedEffect(rev, pluginOn) {
        if (pluginOn) ScrubController.syncFromPlugin() else ScrubController.stop()
    }
    if (!pluginOn) return@composed this.press(scale, haptic, onClick = onClick)

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
            val holdMs = ScrubController.cfg.holdMs
            val timer = if (latestEnabled) cs.launch {
                delay(holdMs)
                if (!Plugins.hasFeature("scrub")) return@launch
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
