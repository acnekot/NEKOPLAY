package cc.catsuki.nekoplay

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** 点 → 线 → 面几何展开参数（由 @type motion 插件通过 effects.geomReveal 按需启用，未启用时零开销） */
data class GeomRevealConfig(
    val lineRatio: Float = 0.34f,
    val lineDp: Float = 2.4f,
    val reverse: Boolean = true,
    val container: Boolean = false,
    val accent: Boolean = true,
)

/** 从 @type motion 插件的 effects JSON 解析几何点线面配置 */
internal fun parseGeomReveal(fx: JSONObject?): GeomRevealConfig? {
    if (fx == null || !fx.optBoolean("geomReveal", false)) return null
    return GeomRevealConfig(
        lineRatio = fx.optDouble("geomLineRatio", 0.34).toFloat().coerceIn(0.18f, 0.55f),
        lineDp = fx.optDouble("geomLineDp", 2.4).toFloat().coerceIn(1f, 6f),
        reverse = fx.optBoolean("geomReverse", true),
        container = fx.optBoolean("geomContainer", false),
        accent = fx.optBoolean("geomAccent", true),
    )
}

/**
 * 纯 GPU 绘制层「点 → 线 → 面」几何展开/收起修饰符（0 重组、120fps）：
 * - p ∈ (0, lineRatio)：从左上角顶点 (0,0) 出发，沿 OutExpo 横向极速拉出顶边光束线与前沿高光点；
 * - p ∈ [lineRatio, 1]：沿 OutQuint 从顶边垂直向下扫面裁剪展开，伴随前沿扫描线与轻微画面落位缩放；
 * - 反向动画（p: 1 → 0）天然呈现「面 → 线 → 点」原路缩回左上角顶点。
 */
internal fun Modifier.geomRevealProgress(
    progress: () -> Float,
    cfg: GeomRevealConfig,
    lineCol: Color,
    innerScale: Boolean = true,
): Modifier = this.drawWithContent {
    val p = progress().coerceIn(0f, 1f)
    if (p >= 0.999f) {
        drawContent()
        return@drawWithContent
    }
    val linePx = cfg.lineDp.dp.toPx().coerceAtLeast(1f)
    if (p <= 0.001f) {
        drawRect(lineCol.copy(alpha = 0.45f), Offset.Zero, Size(linePx * 1.3f, linePx * 1.3f))
        return@drawWithContent
    }
    val r = cfg.lineRatio
    if (p < r) {
        val u = MotionEasings.OutExpo.transform((p / r).coerceIn(0f, 1f))
        val len = (size.width * u).coerceAtLeast(linePx * 1.3f)
        // 顶边光束柔晕 + 主线 + 左上原点与前沿高光点
        drawRect(lineCol.copy(alpha = 0.24f), Offset.Zero, Size(len, linePx * 2.2f))
        drawRect(lineCol, Offset.Zero, Size(len, linePx))
        val dot = linePx * 1.35f
        drawRect(Color.White, Offset.Zero, Size(dot, dot))
        drawRect(Color.White, Offset((len - dot).coerceAtLeast(0f), 0f), Size(dot, dot))
    } else {
        val rawV = ((p - r) / (1f - r)).coerceIn(0f, 1f)
        val v = MotionEasings.OutQuint.transform(rawV)
        val revealH = (size.height * v).coerceAtLeast(linePx)
        clipRect(0f, 0f, size.width, revealH) {
            if (innerScale && v < 0.999f) {
                val sc = 1f + 0.045f * (1f - v)
                scale(sc, sc) { this@drawWithContent.drawContent() }
            } else {
                this@drawWithContent.drawContent()
            }
        }
        val fade = if (rawV < 0.82f) 1f else ((1f - rawV) / 0.18f).coerceIn(0f, 1f)
        if (fade > 0.01f) {
            // 顶边锚点残影
            drawRect(lineCol.copy(alpha = 0.30f * fade), Offset.Zero, Size(size.width, linePx * 0.65f))
            // 扫描前沿向上光晕
            val haloH = (linePx * 4.5f).coerceAtMost(revealH)
            val haloTop = (revealH - haloH).coerceAtLeast(0f)
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, lineCol.copy(alpha = 0.26f * fade)), haloTop, revealH),
                Offset(0f, haloTop), Size(size.width, haloH),
            )
            // 扫描主线 + 左右端高光刻度
            val scanTop = (revealH - linePx).coerceAtLeast(0f)
            drawRect(lineCol.copy(alpha = fade), Offset(0f, scanTop), Size(size.width, linePx))
            val tickW = (linePx * 2.2f).coerceAtMost(size.width * 0.2f)
            drawRect(Color.White.copy(alpha = fade), Offset(0f, scanTop), Size(tickW, linePx))
            drawRect(Color.White.copy(alpha = fade), Offset(size.width - tickW, scanTop), Size(tickW, linePx))
        }
    }
}

/** 独立封面或页面入场时的「点 → 线 → 面」展开修饰符（未启用几何插件时零开销直返） */
@Composable fun Modifier.geomReveal(key: Any?, innerScale: Boolean = true): Modifier {
    val cfg = MotionEffects.geom ?: return this
    val prog = remember { Animatable(0f) }
    LaunchedEffect(key, cfg) {
        prog.snapTo(0f)
        prog.animateTo(1f, tween(Motion.crossfadeMs, easing = LinearEasing))
    }
    val lineCol = if (cfg.accent) c.p else c.k
    return this.geomRevealProgress({ prog.value }, cfg, lineCol, innerScale)
}

/** 播放页 / 子页容器联动模式：绑定外部展开/收起进度（支持反向 面 → 线 → 点 缩回左上顶点） */
@Composable fun Modifier.geomContainerReveal(progress: () -> Float): Modifier {
    val cfg = MotionEffects.geom ?: return this
    val lineCol = if (cfg.accent) c.p else c.k
    return this.geomRevealProgress(progress, cfg, lineCol, innerScale = false)
}

/**
 * 封面切换统一宿主：
 * - 未启用点线面几何插件时直接走原生 Crossfade（零开销）；
 * - 启用后，首次出现从左上角「点 → 线 → 面」展开；切歌时按插件设置支持先反向「面 → 线 → 点」收起再展开新封面，或直接覆盖扫开。
 */
@Composable fun CoverRevealSwitch(url: String?, size: Dp, title: String, decodeSize: Dp = size) {
    val cfg = MotionEffects.geom
    if (cfg == null) {
        Crossfade(url, animationSpec = tween(Motion.crossfadeMs), label = "coverCf") { u ->
            Cover(u, size, title, decodeSize = decodeSize)
        }
        return
    }
    var activeUrl by remember { mutableStateOf(url) }
    var activeTitle by remember { mutableStateOf(title) }
    var underUrl by remember { mutableStateOf<String?>(null) }
    var underTitle by remember { mutableStateOf("") }
    var firstRun by remember { mutableStateOf(true) }
    val prog = remember { Animatable(0f) }
    LaunchedEffect(url, cfg) {
        val dur = Motion.crossfadeMs.coerceAtLeast(120)
        if (firstRun) {
            firstRun = false
            activeUrl = url; activeTitle = title; underUrl = null
            prog.snapTo(0f)
            prog.animateTo(1f, tween(dur, easing = LinearEasing))
        } else if (url != activeUrl) {
            if (cfg.reverse && prog.value > 0.05f) {
                underUrl = null
                prog.animateTo(0f, tween((dur * 0.62f).toInt().coerceAtLeast(80), easing = LinearEasing))
                activeUrl = url; activeTitle = title
                prog.animateTo(1f, tween(dur, easing = LinearEasing))
            } else {
                underUrl = activeUrl; underTitle = activeTitle
                activeUrl = url; activeTitle = title
                prog.snapTo(0f)
                prog.animateTo(1f, tween(dur, easing = LinearEasing))
                underUrl = null
            }
        } else {
            activeTitle = title
            if (prog.value < 0.999f) prog.animateTo(1f, tween(dur, easing = LinearEasing))
        }
    }
    val lineCol = if (cfg.accent) c.p else c.k
    Box(Modifier.size(size)) {
        if (underUrl != null) {
            Box(Modifier.graphicsLayer { alpha = (1f - prog.value * 0.75f).coerceIn(0.2f, 1f) }) {
                Cover(underUrl, size, underTitle, decodeSize = decodeSize)
            }
        }
        Box(Modifier.geomRevealProgress({ prog.value }, cfg, lineCol, innerScale = true)) {
            Cover(activeUrl, size, activeTitle, decodeSize = decodeSize)
        }
    }
}
