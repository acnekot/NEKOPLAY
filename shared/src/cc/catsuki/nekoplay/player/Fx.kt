package cc.catsuki.nekoplay.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import cc.catsuki.nekoplay.*
import cc.catsuki.nekoplay.core.*
import cc.catsuki.nekoplay.data.*
import cc.catsuki.nekoplay.ui.*

/**
 * 效果器设置：10 段均衡器（±12dB）+ 低音增强 + 环绕 + 响度。
 * 安卓用系统 DynamicsProcessing / BassBoost / Virtualizer / LoudnessEnhancer，桌面用 mpv 的 ffmpeg 滤镜，参数一套。
 */
data class Fx(
    val on: Boolean = false,
    val bands: List<Float> = List(10) { 0f }, // dB，对应 FREQS
    val bass: Float = 0f, // 0..1
    val surround: Float = 0f, // 0..1
    val loud: Float = 0f, // 0..1（最多 +10dB）
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
        runCatching { Plat.impl.audio.applyFx(fx) }
    }
    fun set(n: Fx, save: Boolean = true) {
        fx = n
        runCatching { Plat.impl.audio.applyFx(n) }
        if (save) Plat.prefs("fx").edit().putBoolean("on", n.on).putString("bands", n.bands.joinToString(",")).putFloat("bass", n.bass)
            .putFloat("sur", n.surround).putFloat("loud", n.loud).putString("preset", n.preset).apply()
    }
}

@Composable fun FxPage() {
    val p = c
    val fx = FxStore.fx
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("EFFECTS"); Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { T("效果器", Fs.HERO, w = 800, lh = 1.3f); T("均衡器、低音、环绕、响度", 13, p.s) }
                    NSwitch(fx.on) { FxStore.set(fx.copy(on = it)) }
                }
                Spacer(Modifier.height(12.dp)); Rule()
            }
        }
        item {
            Column(Modifier.padding(top = 18.dp)) {
                TagRow("PRESET", 0.dp); Spacer(Modifier.height(10.dp))
                val names = Fx.PRESETS.map { it.first } + if (Fx.PRESETS.none { it.first == fx.preset }) listOf(fx.preset) else emptyList()
                ChipRow(names, names.indexOf(fx.preset)) { i -> Fx.PRESETS.getOrNull(i)?.let { (n, b) -> FxStore.set(fx.copy(preset = n, bands = b, on = true)) } }
            }
        }
        item {
            Column(Modifier.padding(top = 22.dp)) {
                TagRow("EQUALIZER", 0.dp) { LinkBtn("归零") { FxStore.set(fx.copy(bands = List(10) { 0f }, preset = "默认")) } }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().height(230.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Fx.FREQS.forEachIndexed { i, f ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            T((if (fx.bands[i] > 0) "+" else "") + "%.0f".format(fx.bands[i]), 11, if (fx.bands[i] != 0f) p.p else p.s, 600, mono = true)
                            Spacer(Modifier.height(4.dp))
                            VSlider(fx.bands[i], -12f..12f, Modifier.weight(1f), onDone = { FxStore.set(FxStore.fx) }) { v ->
                                FxStore.set(FxStore.fx.copy(bands = FxStore.fx.bands.toMutableList().also { it[i] = (v * 2).toInt() / 2f }, preset = "自定义", on = true), save = false)
                            }
                            Spacer(Modifier.height(6.dp))
                            T(if (f >= 1000) "${f / 1000}k" else "$f", 11, p.s, mono = true)
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(top = 22.dp)) {
                TagRow("EFFECTS", 0.dp); Spacer(Modifier.height(4.dp))
                FxRow("低音增强", fx.bass) { FxStore.set(FxStore.fx.copy(bass = it, on = true), save = false) }
                FxRow("环绕 / 3D", fx.surround) { FxStore.set(FxStore.fx.copy(surround = it, on = true), save = false) }
                FxRow("响度增强", fx.loud) { FxStore.set(FxStore.fx.copy(loud = it, on = true), save = false) }
                T("效果因设备而异；部分手机不支持某些效果时会自动跳过。", 12, p.s, lines = 3, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable private fun FxRow(label: String, v: Float, onChange: (Float) -> Unit) {
    val p = c
    Column(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { T(label, 16, w = 600, modifier = Modifier.weight(1f)); T("%.0f%%".format(v * 100), 13, p.p, 600, mono = true) }
        NSlider(v, 0f..1f, onDone = { FxStore.set(FxStore.fx) }, onChange = onChange)
    }
}

/** 竖向滑块（均衡器用）：中线是 0dB，往上加、往下减 */
@Composable fun VSlider(value: Float, range: ClosedFloatingPointRange<Float>, modifier: Modifier, onDone: () -> Unit, onChange: (Float) -> Unit) {
    val p = c
    val f = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Box(modifier.width(32.dp).pointerInput(range) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(); val ch = e.changes.firstOrNull() ?: continue
                if (ch.pressed) { val y = 1f - (ch.position.y / size.height).coerceIn(0f, 1f); onChange(range.start + y * (range.endInclusive - range.start)); ch.consume() } else if (ch.previousPressed) onDone()
            }
        }
    }.drawBehind {
        val x = size.width / 2; val w = 4.dp.toPx(); val t = 14.dp.toPx()
        val mid = size.height / 2; val y = size.height * (1 - f)
        drawRect(p.l3, Offset(x - w / 2, 0f), Size(w, size.height))
        drawRect(p.p, Offset(x - w / 2, minOf(mid, y)), Size(w, kotlin.math.abs(mid - y)))
        drawRect(p.k, Offset(x - 6.dp.toPx(), mid - .5.dp.toPx()), Size(12.dp.toPx(), 1.dp.toPx()))
        drawRect(p.p, Offset(x - t / 2, (y - t / 2).coerceIn(0f, size.height - t)), Size(t, t))
    })
}
