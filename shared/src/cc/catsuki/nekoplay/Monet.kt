package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import kotlin.math.abs

/**
 * 莫奈取色：从一个「种子色」生成整套背景色（页面、栏位、弹窗、占位块）和主色。
 * 种子来源：系统壁纸（Android 12+）/ 自定义背景图 / 当前歌曲封面 / 自己选。
 */
object Monet {
    val MODES = listOf("关闭", "壁纸", "背景图", "封面", "自定义")
    var mode by mutableIntStateOf(0)
    /** 自定义模式的色相（0~360） */
    var hue by mutableFloatStateOf(230f)
    /** 取到的种子色（ARGB），null = 还没取到，按默认配色 */
    var seed by mutableStateOf<Int?>(null)
    /** 背景色浓淡：0 很淡 ~ 1 很浓 */
    var strength by mutableFloatStateOf(.5f)

    val on get() = Features.on("monet") && mode != 0 && seed != null
    val availableModes: List<Int> get() = MODES.indices.filter { it != 1 || Plat.impl.systemSeed() != null }

    fun load() {
        val kv = Plat.prefs("ui")
        mode = kv.getInt("monet", 0); hue = kv.getInt("monetHue", 230).toFloat(); strength = kv.getInt("monetStr", 50) / 100f
        if (mode == 1 && Plat.impl.systemSeed() == null) mode = 0
    }
    fun save() { Plat.prefs("ui").edit().putInt("monet", mode).putInt("monetHue", hue.toInt()).putInt("monetStr", (strength * 100).toInt()).apply() }
    fun set(m: Int) { mode = m; save() }

    // ---------- 生成配色 ----------
    /** HSL → Color（h 0~360，s/l 0~1） */
    fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): Color {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when ((h / 60).toInt().mod(6)) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
        return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f), a)
    }
    fun hueSat(argb: Int): Pair<Float, Float> {
        val r = (argb shr 16 and 255) / 255f; val g = (argb shr 8 and 255) / 255f; val b = (argb and 255) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn; val l = (mx + mn) / 2
        if (d < 1e-4f) return 0f to 0f
        val s = d / (1 - abs(2 * l - 1))
        val h = when (mx) { r -> 60 * (((g - b) / d).mod(6f)); g -> 60 * ((b - r) / d + 2); else -> 60 * ((r - g) / d + 4) }
        return h to s.coerceIn(0f, 1f)
    }

    /** 按种子色改写一套配色：文字和功能色不动，只换背景层和主色 */
    fun apply(base: Pal): Pal {
        if (!Features.on("monet")) return base
        val sd = seed ?: return base
        val (h, s0) = hueSat(sd)
        val s = s0.coerceIn(.18f, .9f)
        val k = strength
        return if (!base.dark) {
            val bs = s * (.25f + .45f * k)          // 背景饱和度
            base.copy(
                p = hsl(h, (s * 1.1f).coerceAtMost(.85f), .42f),
                lyBg = hsl(h, (s * 1.1f).coerceAtMost(.85f), .42f),
                page = hsl(h, bs, .90f - .05f * k), bar = hsl(h, bs, .84f - .05f * k), sheet = hsl(h, bs * .8f, .93f - .04f * k),
                pop = hsl(h, bs * .7f, .96f), ph = hsl(h, bs, .80f - .05f * k),
            )
        } else {
            val bs = s * (.12f + .30f * k)
            base.copy(
                p = hsl(h, (s * 1.1f).coerceAtMost(.85f), .70f),
                page = hsl(h, bs, .17f + .03f * k), bar = hsl(h, bs, .20f + .03f * k), sheet = hsl(h, bs, .20f + .03f * k),
                pop = hsl(h, bs, .25f + .03f * k), ph = hsl(h, bs, .12f + .02f * k), tagFg = hsl(h, bs, .20f + .03f * k),
            )
        }
    }

    // ---------- 取种子色 ----------
    /** 从图片里挑主色：按色相分 36 桶，权重 = 饱和度 × 亮度适中程度，取最重的桶的平均色 */
    fun dominant(px: IntArray): Int? {
        val w = FloatArray(36); val rs = FloatArray(36); val gs = FloatArray(36); val bs = FloatArray(36)
        for (c in px) {
            if ((c ushr 24) < 128) continue
            val (h, s) = hueSat(c)
            val r = (c shr 16 and 255); val g = (c shr 8 and 255); val b = (c and 255)
            val l = (maxOf(r, g, b) + minOf(r, g, b)) / 510f
            val wt = s * (1 - abs(l - .5f) * 1.6f).coerceAtLeast(0f)
            if (wt <= .02f) continue
            val i = (h / 10).toInt().coerceIn(0, 35)
            w[i] += wt; rs[i] += r * wt; gs[i] += g * wt; bs[i] += b * wt
        }
        // 相邻桶合并一下，避免一个颜色被切成两半
        val sm = FloatArray(36) { w[it] + .5f * (w[(it + 35) % 36] + w[(it + 1) % 36]) }
        val best = sm.indices.maxBy { sm[it] }
        if (w[best] < 1e-3f) return null
        val r = (rs[best] / w[best]).toInt(); val g = (gs[best] / w[best]).toInt(); val b = (bs[best] / w[best]).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun fromImage(img: ImageBitmap): Int? {
        val small = if (img.width > 64 || img.height > 64) Plat.impl.scaled(img, 48, (48f * img.height / img.width).toInt().coerceAtLeast(1)) else img
        return dominant(Plat.impl.pixels(small))
    }

    private val coverMemo = HashMap<String, Int?>()
    suspend fun fromCover(url: String): Int? = coverMemo[url] ?: withContext(Dispatchers.IO) {
        runCatching {
            val bytes = Ncm.http.newCall(Request.Builder().url("$url?param=64y64").build()).execute().use { it.body!!.bytes() }
            val f = File(Plat.impl.cacheDir, "monet_cover"); f.writeBytes(bytes)
            Plat.impl.decodeImage(f.path, 64)?.let { fromImage(it) }.also { f.delete() }
        }.getOrNull().also { coverMemo[url] = it }
    }

    /** 根据模式更新种子色（放在主题外层的 LaunchedEffect 里调用） */
    suspend fun refresh(coverUrl: String?) {
        seed = when (mode) {
            1 -> Plat.impl.systemSeed()
            2 -> Bg.source?.let { withContext(Dispatchers.Default) { fromImage(it) } }
            3 -> coverUrl?.let { fromCover(it) } ?: seed
            4 -> hsl(hue, .6f, .5f).let { (0xFF shl 24) or ((it.red * 255).toInt() shl 16) or ((it.green * 255).toInt() shl 8) or (it.blue * 255).toInt() }
            else -> null
        }
    }
}

/** 设置里的莫奈取色：模式、浓淡、自定义色相 */
@Composable fun MonetSettings() {
    val p = c
    SetLine("莫奈取色", "用一个颜色生成背景、栏位和主色：系统壁纸、自定义背景图、正在播放的封面，或者自己选") {
        val modes = Monet.availableModes
        ChipRow(modes.map { Monet.MODES[it] }, modes.indexOf(Monet.mode).coerceAtLeast(0)) { Monet.set(modes[it]) }
        if (Monet.mode == 2 && !Bg.on) T("还没有设置自定义背景图", 13, p.s, modifier = Modifier.padding(top = 8.dp))
        if (Monet.mode != 0) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("浓淡", 14, modifier = Modifier.width(52.dp))
                Box(Modifier.weight(1f)) { NSlider(Monet.strength, 0f..1f, onDone = { Monet.save() }) { Monet.strength = it } }
                Spacer(Modifier.width(10.dp)); T("%.0f%%".format(Monet.strength * 100), 14, p.p, 700, mono = true)
            }
        }
        if (Monet.mode == 4) {
            Spacer(Modifier.height(10.dp))
            // 色相条 + 当前颜色
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("色相", 14, modifier = Modifier.width(52.dp))
                Box(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().height(6.dp).align(Alignment.Center)) { repeat(24) { Box(Modifier.weight(1f).fillMaxHeight().background(Monet.hsl(it * 15f, .6f, .55f))) } }
                    NSlider(Monet.hue, 0f..359f, onDone = { Monet.save() }) { Monet.hue = it }
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(24.dp).background(Monet.hsl(Monet.hue, .6f, .5f)).border(1.dp, p.k))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0f, 25f, 45f, 140f, 175f, 200f, 230f, 265f, 300f, 335f).forEach { hh ->
                    Box(Modifier.weight(1f).aspectRatio(1f).background(Monet.hsl(hh, .6f, .55f)).then(if (abs(Monet.hue - hh) < 3) Modifier.border(2.dp, p.k) else Modifier).press { Monet.hue = hh; Monet.save() }) {}
                }
            }
        }
    }
}
