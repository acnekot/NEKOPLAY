package cc.catsuki.nekoplay

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import org.json.JSONObject

/** 全局动效速率档位：0 慢 / 1 标准 / 2 快 / 3 很快 */
object Anim {
    val labels = listOf("慢", "标准", "快", "很快")
    private val factors = listOf(0.7f, 1f, 1.4f, 2f)
    var level by mutableIntStateOf(1)
    val f get() = factors[level]
    fun stiff(base: Float) = base * f * f
    fun load() { level = Plat.prefs("ui").getInt("anim", 1).coerceIn(0, labels.lastIndex) }
    fun set(v: Int) { level = v.coerceIn(0, labels.lastIndex); Plat.prefs("ui").edit().putInt("anim", level).apply() }
}

/**
 * 空间微交互令牌（与曲线/时间引擎 AnimStyle 完全解耦）：
 * 无论使用弹簧、缓动、osu! 还是自定义贝塞尔曲线，以下空间反馈均可独立开关与调参。
 */
object MotionEffects {
    /** 鼠标悬停侧边栏 / 列表项时的横向微位移（dp，0 = 关闭） */
    var hoverShiftDp by mutableFloatStateOf(5f)
    /** 鼠标悬停时的轻底色高亮 */
    var hoverTint by mutableStateOf(true)
    /** 按下控件时的默认缩放比例（1.00 = 关闭按压缩放） */
    var pressScale by mutableFloatStateOf(0.985f)
    /** 播放页 / 面板 / 页面切换时是否叠加空间缩放与视差 */
    var pageScale by mutableStateOf(true)

    private val k get() = Plat.prefs("ui")
    fun load() {
        hoverShiftDp = k.getFloat("me_hoverShift", 5f).coerceIn(0f, 12f)
        hoverTint = k.getBoolean("me_hoverTint", true)
        pressScale = k.getFloat("me_pressScale", 0.985f).coerceIn(0.92f, 1.0f)
        pageScale = k.getBoolean("me_pageScale", true)
    }
    fun save() {
        k.edit().putFloat("me_hoverShift", hoverShiftDp).putBoolean("me_hoverTint", hoverTint)
            .putFloat("me_pressScale", pressScale).putBoolean("me_pageScale", pageScale).apply()
    }
    fun reset() { hoverShiftDp = 5f; hoverTint = true; pressScale = 0.985f; pageScale = true; save() }
}

/** 动效形式（纯时间与缓动曲线层）：0 弹簧 / 1 缓动 / 2 线性 / 3 关闭 / 4 osu! / 5 自定义曲线 */
object AnimStyle {
    val labels = listOf("弹簧", "缓动", "线性", "关闭", "osu!", "自定义曲线")
    var style by mutableIntStateOf(0)
    // 自定义贝塞尔曲线控制点（支持 y 轴 -0.45..1.45 过冲）
    var x1 by mutableFloatStateOf(0.16f)
    var y1 by mutableFloatStateOf(1.00f)
    var x2 by mutableFloatStateOf(0.30f)
    var y2 by mutableFloatStateOf(1.00f)
    // 自定义弹簧物理参数
    var springDamping by mutableFloatStateOf(0.85f)
    var springStiffness by mutableFloatStateOf(400f)
    // 各类动画基准时长（ms）
    var quickBaseMs by mutableIntStateOf(120)
    var standardBaseMs by mutableIntStateOf(240)
    var pageBaseMs by mutableIntStateOf(320)
    var crossfadeBaseMs by mutableIntStateOf(400)

    val osu get() = style == 4
    val off get() = style == 3
    val custom get() = style == 5
    private var cachedKey = ""
    private var cachedEasing: Easing = SmoothBezierEasing(0.16f, 1.00f, 0.30f, 1.00f)
    val customEasing: Easing get() {
        val a = x1.coerceIn(0f, 1f); val b = y1.coerceIn(-0.45f, 1.45f)
        val c = x2.coerceIn(0f, 1f); val d = y2.coerceIn(-0.45f, 1.45f)
        val key = "$a,$b,$c,$d"
        if (key != cachedKey) {
            cachedKey = key
            cachedEasing = SmoothBezierEasing(a, b, c, d)
        }
        return cachedEasing
    }
    val activeEasing: Easing get() = when (style) {
        2 -> LinearEasing
        4 -> OsuOut
        5 -> customEasing
        else -> FastOutSlowInEasing
    }

    private val k get() = Plat.prefs("ui")
    fun load() {
        Anim.load()
        style = k.getInt("animStyle", 0).coerceIn(0, labels.lastIndex)
        x1 = k.getFloat("anim_x1", 0.16f).coerceIn(0f, 1f)
        y1 = k.getFloat("anim_y1", 1.00f).coerceIn(-0.45f, 1.45f)
        x2 = k.getFloat("anim_x2", 0.30f).coerceIn(0f, 1f)
        y2 = k.getFloat("anim_y2", 1.00f).coerceIn(-0.45f, 1.45f)
        springDamping = k.getFloat("anim_sd", 0.85f).coerceIn(0.35f, 1f)
        springStiffness = k.getFloat("anim_ss", 400f).coerceIn(100f, 1200f)
        quickBaseMs = k.getInt("anim_ms_q", 120).coerceIn(40, 400)
        standardBaseMs = k.getInt("anim_ms_s", 240).coerceIn(80, 600)
        pageBaseMs = k.getInt("anim_ms_p", 320).coerceIn(100, 800)
        crossfadeBaseMs = k.getInt("anim_ms_c", 400).coerceIn(100, 1000)
        MotionEffects.load()
    }
    fun save() {
        k.edit().putInt("animStyle", style)
            .putFloat("anim_x1", x1).putFloat("anim_y1", y1).putFloat("anim_x2", x2).putFloat("anim_y2", y2)
            .putFloat("anim_sd", springDamping).putFloat("anim_ss", springStiffness)
            .putInt("anim_ms_q", quickBaseMs).putInt("anim_ms_s", standardBaseMs)
            .putInt("anim_ms_p", pageBaseMs).putInt("anim_ms_c", crossfadeBaseMs).apply()
    }
    fun set(v: Int) { style = v.coerceIn(0, labels.lastIndex); save() }
    fun setCurve(a: Float, b: Float, c: Float, d: Float, switchCustom: Boolean = true, persist: Boolean = true) {
        x1 = a.coerceIn(0f, 1f); y1 = b.coerceIn(-0.45f, 1.45f); x2 = c.coerceIn(0f, 1f); y2 = d.coerceIn(-0.45f, 1.45f)
        if (switchCustom) style = 5
        if (persist) save()
    }
    fun reset() {
        style = 0; Anim.set(1)
        x1 = 0.16f; y1 = 1f; x2 = 0.30f; y2 = 1f
        springDamping = 0.85f; springStiffness = 400f
        quickBaseMs = 120; standardBaseMs = 240; pageBaseMs = 320; crossfadeBaseMs = 400
        save()
        MotionEffects.reset()
    }
    fun exportJson(): String = JSONObject()
        .put("format", "nekoplay-motion-v1")
        .put("style", style).put("speedLevel", Anim.level)
        .put("x1", x1.toDouble()).put("y1", y1.toDouble()).put("x2", x2.toDouble()).put("y2", y2.toDouble())
        .put("springDamping", springDamping.toDouble()).put("springStiffness", springStiffness.toDouble())
        .put("quickMs", quickBaseMs).put("standardMs", standardBaseMs).put("pageMs", pageBaseMs).put("crossfadeMs", crossfadeBaseMs)
        .put("hoverShiftDp", MotionEffects.hoverShiftDp.toDouble()).put("hoverTint", MotionEffects.hoverTint)
        .put("pressScale", MotionEffects.pressScale.toDouble()).put("pageScale", MotionEffects.pageScale)
        .toString(2)
    fun importJson(raw: String) {
        val o = JSONObject(raw.trim())
        if (o.has("style")) style = o.optInt("style", style).coerceIn(0, labels.lastIndex)
        if (o.has("speedLevel")) Anim.set(o.optInt("speedLevel", Anim.level))
        if (o.has("x1")) x1 = o.optDouble("x1", x1.toDouble()).toFloat().coerceIn(0f, 1f)
        if (o.has("y1")) y1 = o.optDouble("y1", y1.toDouble()).toFloat().coerceIn(-0.45f, 1.45f)
        if (o.has("x2")) x2 = o.optDouble("x2", x2.toDouble()).toFloat().coerceIn(0f, 1f)
        if (o.has("y2")) y2 = o.optDouble("y2", y2.toDouble()).toFloat().coerceIn(-0.45f, 1.45f)
        if (o.has("springDamping")) springDamping = o.optDouble("springDamping", springDamping.toDouble()).toFloat().coerceIn(0.35f, 1f)
        if (o.has("springStiffness")) springStiffness = o.optDouble("springStiffness", springStiffness.toDouble()).toFloat().coerceIn(100f, 1200f)
        if (o.has("quickMs")) quickBaseMs = o.optInt("quickMs", quickBaseMs).coerceIn(40, 400)
        if (o.has("standardMs")) standardBaseMs = o.optInt("standardMs", standardBaseMs).coerceIn(80, 600)
        if (o.has("pageMs")) pageBaseMs = o.optInt("pageMs", pageBaseMs).coerceIn(100, 800)
        if (o.has("crossfadeMs")) crossfadeBaseMs = o.optInt("crossfadeMs", crossfadeBaseMs).coerceIn(100, 1000)
        if (o.has("hoverShiftDp")) MotionEffects.hoverShiftDp = o.optDouble("hoverShiftDp", MotionEffects.hoverShiftDp.toDouble()).toFloat().coerceIn(0f, 12f)
        if (o.has("hoverTint")) MotionEffects.hoverTint = o.optBoolean("hoverTint", MotionEffects.hoverTint)
        if (o.has("pressScale")) MotionEffects.pressScale = o.optDouble("pressScale", MotionEffects.pressScale.toDouble()).toFloat().coerceIn(0.92f, 1.0f)
        if (o.has("pageScale")) MotionEffects.pageScale = o.optBoolean("pageScale", MotionEffects.pageScale)
        save(); MotionEffects.save()
    }
    val ms get() = (pageBaseMs / Anim.f).toInt().coerceAtLeast(1)
    val osuMs get() = ((pageBaseMs + 40) / Anim.f).toInt().coerceAtLeast(1)
}

/**
 * 64 位双精度三次贝塞尔缓动（牛顿迭代 + 二分兜底，精度 1e-7）：
 * 消除极端控制点（x 贴近 0/1 或 y 过冲）在末帧收敛跳变导致的顿挫卡顿。
 */
data class SmoothBezierEasing(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Easing {
    private val ax = x1.toDouble().coerceIn(0.0, 1.0)
    private val ay = y1.toDouble()
    private val bx = x2.toDouble().coerceIn(0.0, 1.0)
    private val by = y2.toDouble()
    private val cx = 3.0 * ax
    private val bxC = 3.0 * (bx - ax) - cx
    private val axC = 1.0 - cx - bxC
    private val cy = 3.0 * ay
    private val byC = 3.0 * (by - ay) - cy
    private val ayC = 1.0 - cy - byC

    private fun sampleX(t: Double) = ((axC * t + bxC) * t + cx) * t
    private fun sampleY(t: Double) = ((ayC * t + byC) * t + cy) * t
    private fun sampleDerivX(t: Double) = (3.0 * axC * t + 2.0 * bxC) * t + cx

    override fun transform(fraction: Float): Float {
        if (fraction <= 0f) return 0f
        if (fraction >= 1f) return 1f
        val x = fraction.toDouble()
        var t = x
        for (i in 0 until 8) {
            val err = sampleX(t) - x
            if (kotlin.math.abs(err) < 1e-7) return sampleY(t).toFloat()
            val d = sampleDerivX(t)
            if (kotlin.math.abs(d) < 1e-7) break
            t = (t - err / d).coerceIn(0.0, 1.0)
        }
        var lo = 0.0; var hi = 1.0
        t = x
        repeat(24) {
            val cur = sampleX(t)
            if (kotlin.math.abs(cur - x) < 1e-7) return sampleY(t).toFloat()
            if (cur < x) lo = t else hi = t
            t = 0.5 * (lo + hi)
        }
        return sampleY(t).toFloat()
    }
}

/** osu! 招牌 OutExpo/OutQuint 缓出曲线：瞬间冲出、长尾丝滑刹停、零回弹 */
val OsuOut = SmoothBezierEasing(0.16f, 1f, 0.3f, 1f)
val DecelOut = SmoothBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * 统一动效令牌（所有 UI 组件统一消费此层，禁止散落硬编码 tween/spring 或曲线分支）：
 */
object Motion {
    val quickMs get() = if (AnimStyle.off) 0 else (AnimStyle.quickBaseMs / Anim.f).toInt().coerceAtLeast(1)
    val standardMs get() = if (AnimStyle.off) 0 else (AnimStyle.standardBaseMs / Anim.f).toInt().coerceAtLeast(1)
    val pageMs get() = if (AnimStyle.off) 0 else (AnimStyle.pageBaseMs / Anim.f).toInt().coerceAtLeast(1)
    val crossfadeMs get() = if (AnimStyle.off) 0 else (AnimStyle.crossfadeBaseMs / Anim.f).toInt().coerceAtLeast(1)

    fun <T> quick(): FiniteAnimationSpec<T> =
        if (AnimStyle.off) snap()
        else tween(quickMs, easing = if (AnimStyle.style in listOf(2, 4, 5)) AnimStyle.activeEasing else DecelOut)
    fun <T> standard(): FiniteAnimationSpec<T> =
        if (AnimStyle.off) snap()
        else tween(standardMs, easing = AnimStyle.activeEasing)
    fun <T> page(): FiniteAnimationSpec<T> =
        if (AnimStyle.off) snap()
        else tween(pageMs, easing = AnimStyle.activeEasing)
    fun <T> crossfade(): FiniteAnimationSpec<T> =
        if (AnimStyle.off) snap()
        else tween(crossfadeMs, easing = AnimStyle.activeEasing)
    fun <T> hero(): FiniteAnimationSpec<T> =
        if (AnimStyle.off) snap()
        else if (AnimStyle.style != 0) tween(pageMs, easing = AnimStyle.activeEasing)
        else spring(dampingRatio = AnimStyle.springDamping, stiffness = Anim.stiff(AnimStyle.springStiffness))
}

val sp: FiniteAnimationSpec<IntOffset> get() = Motion.hero()
val spF: FiniteAnimationSpec<Float> get() = Motion.hero()
