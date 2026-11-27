package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 播放音量淡入淡出控制器（独立拆分自 Engine.kt，减少状态机耦合） */
internal class EngineFade(private val scope: CoroutineScope, private val audio: () -> Audio) {
    var fadeMs by mutableIntStateOf(0)
    private var fadeJob: Job? = null
    private var rampOnPlay = false

    fun load(prefs: KV?) { fadeMs = prefs?.getInt("fade", 0) ?: 0 }
    fun set(ms: Int, prefs: KV?) { fadeMs = ms; prefs?.edit()?.putInt("fade", ms)?.apply() }

    private suspend fun ramp(from: Float, to: Float, ms: Int) {
        val steps = (ms / 16).coerceAtLeast(1)
        for (i in 1..steps) {
            audio().volume = Vol.v * (from + (to - from) * i / steps)
            delay(16)
        }
    }

    fun fadePause() {
        fadeJob?.cancel()
        val a = audio()
        if (fadeMs == 0 || !a.isPlaying) { a.pause(); return }
        fadeJob = scope.launch { ramp(1f, 0f, fadeMs); a.pause(); a.volume = Vol.v }
    }

    fun fadePlay() {
        fadeJob?.cancel()
        val a = audio()
        if (fadeMs == 0) { a.volume = Vol.v; a.play(); return }
        a.volume = 0f; a.play()
        fadeJob = scope.launch { ramp(0f, 1f, fadeMs) }
    }

    fun prepareLoad(autoplay: Boolean) {
        val a = audio()
        if (autoplay && fadeMs > 0) {
            fadeJob?.cancel()
            a.volume = 0f
            rampOnPlay = true
        } else if (!rampOnPlay) {
            a.volume = Vol.v
        }
    }

    fun startRampIn() {
        if (!rampOnPlay) return
        rampOnPlay = false
        fadeJob?.cancel()
        if (fadeMs == 0) audio().volume = Vol.v
        else fadeJob = scope.launch { ramp(0f, 1f, fadeMs) }
    }

    fun cancelImmediate() {
        rampOnPlay = false
        fadeJob?.cancel()
        audio().volume = Vol.v
    }

    val pendingRamp: Boolean get() = rampOnPlay
}
