package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 效果器设置：10 段均衡器（±12dB）+ 低音增强 + 环绕 + 响度。
 * 安卓用系统 DynamicsProcessing / BassBoost / Virtualizer / LoudnessEnhancer，桌面用 mpv 的 ffmpeg 滤镜，参数一套。
 */
data class Fx(
    val on: Boolean = false,
    val bands: List<Float> = List(10) { 0f },   // dB，对应 FREQS
    val bass: Float = 0f,       // 0..1
    val surround: Float = 0f,   // 0..1
    val loud: Float = 0f,       // 0..1（最多 +10dB）
    val preset: String = "默认",
) {
    companion object {
        val FREQS = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        val PRESETS: List<Pair<String, List<Float>>> = listOf(
            "默认" to List(10) { 0f },
            "流行" to listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 0f, 1f),
            "摇滚" to listOf(5f, 4f, 2f, -1f, -2f, -1f, 2f, 4f, 5f, 5f),
            "人声" to listOf(-3f, -2f, -1f, 1f, 4f, 5f, 4f, 2f, 0f, -1f),
            "古典" to listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
            "电子" to listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 1f, 4f, 5f),
            "低音" to listOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f),
            "爵士" to listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
            "高音" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f),
        )
    }
}

object FxStore {
    var fx by mutableStateOf(Fx())
        private set
    fun init() {
        val k = Plat.prefs("fx")
        fx = Fx(k.getBoolean("on", false), (k.getString("bands", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 10 }) ?: List(10) { 0f },
            k.getFloat("bass", 0f), k.getFloat("sur", 0f), k.getFloat("loud", 0f), k.getString("preset", "默认") ?: "默认")
        applyEffective()
    }
    fun applyEffective() {
        runCatching { Plat.impl.audio.applyFx(if (Features.on("fx")) fx else Fx(on = false)) }
    }
    fun set(n: Fx, save: Boolean = true) {
        fx = n
        applyEffective()
        if (save) Plat.prefs("fx").edit().putBoolean("on", n.on).putString("bands", n.bands.joinToString(",")).putFloat("bass", n.bass)
            .putFloat("sur", n.surround).putFloat("loud", n.loud).putString("preset", n.preset).apply()
    }
}
