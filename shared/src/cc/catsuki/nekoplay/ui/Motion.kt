package cc.catsuki.nekoplay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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

/** 单个动效通道（支持解析数学缓动、贝塞尔或弹簧物理），由内置模式或 @type motion 插件提供 */
data class MotionChannel(
    val easing: Easing,
    val baseMs: Int,
    val isSpring: Boolean = false,
    val damping: Float = 0.85f,
    val stiffness: Float = 400f,
) {
    fun scaledMs(): Int = (baseMs / Anim.f).toInt().coerceAtLeast(1)
    fun <T> spec(): FiniteAnimationSpec<T> =
        if (isSpring) spring(dampingRatio = damping, stiffness = Anim.stiff(stiffness))
        else tween(scaledMs(), easing = easing)
}

/** 由外部 @type motion 插件（如 osu_motion.js）下发的完整规范化动效配置包 */
data class MotionPluginProfile(
    val pluginId: String,
    val pluginName: String,
    val presetLabel: String,
    val quick: MotionChannel,
    val standard: MotionChannel,
    val page: MotionChannel,
    val crossfade: MotionChannel,
    val hero: MotionChannel,
    val hoverShiftDp: Float? = null,
    val hoverTint: Boolean? = null,
    val pressScale: Float? = null,
    val pageScale: Boolean? = null,
    val listStaggerMs: Int? = null,
    val listSlideDp: Float? = null,
) {
    val displayName: String get() = if (presetLabel.isNotBlank()) "$pluginName · $presetLabel" else pluginName
}

/** 空间微交互令牌（与曲线/时间引擎完全解耦；启用动效插件时自动读取插件建议值，关闭插件立刻还原用户原值） */
object MotionEffects {
    var userHoverShiftDp by mutableFloatStateOf(5f)
    var userHoverTint by mutableStateOf(true)
    var userPressScale by mutableFloatStateOf(0.985f)
    var userPageScale by mutableStateOf(true)
    var userListStaggerMs by mutableIntStateOf(24)
    var userListSlideDp by mutableFloatStateOf(36f)

    var hoverShiftDp: Float
        get() = AnimStyle.pluginProfile?.hoverShiftDp ?: userHoverShiftDp
        set(v) { userHoverShiftDp = v.coerceIn(0f, 12f) }
    var hoverTint: Boolean
        get() = AnimStyle.pluginProfile?.hoverTint ?: userHoverTint
        set(v) { userHoverTint = v }
    var pressScale: Float
        get() = AnimStyle.pluginProfile?.pressScale ?: userPressScale
        set(v) { userPressScale = v.coerceIn(0.92f, 1.0f) }
    var pageScale: Boolean
        get() = AnimStyle.pluginProfile?.pageScale ?: userPageScale
        set(v) { userPageScale = v }
    var listStaggerMs: Int
        get() = AnimStyle.pluginProfile?.listStaggerMs ?: userListStaggerMs
        set(v) { userListStaggerMs = v.coerceIn(0, 80) }
    var listSlideDp: Float
        get() = AnimStyle.pluginProfile?.listSlideDp ?: userListSlideDp
        set(v) { userListSlideDp = v.coerceIn(0f, 80f) }

    private val k get() = Plat.prefs("ui")
    fun load() {
        userHoverShiftDp = k.getFloat("me_hoverShift", 5f).coerceIn(0f, 12f)
        userHoverTint = k.getBoolean("me_hoverTint", true)
        userPressScale = k.getFloat("me_pressScale", 0.985f).coerceIn(0.92f, 1.0f)
        userPageScale = k.getBoolean("me_pageScale", true)
        userListStaggerMs = k.getInt("me_listStagger", 24).coerceIn(0, 80)
        userListSlideDp = k.getFloat("me_listSlide", 36f).coerceIn(0f, 80f)
    }
    fun save() {
        k.edit().putFloat("me_hoverShift", userHoverShiftDp).putBoolean("me_hoverTint", userHoverTint)
            .putFloat("me_pressScale", userPressScale).putBoolean("me_pageScale", userPageScale)
            .putInt("me_listStagger", userListStaggerMs).putFloat("me_listSlide", userListSlideDp).apply()
    }
    fun reset() { userHoverShiftDp = 5f; userHoverTint = true; userPressScale = 0.985f; userPageScale = true; userListStaggerMs = 24; userListSlideDp = 36f; save() }
}

/** 内置动效形式（纯基础原语）：0 弹簧 / 1 缓动 / 2 线性 / 3 关闭 / 4 自定义曲线；特色动效（如 osu!lazer）已独立为 @type motion 插件 */
object AnimStyle {
    val labels = listOf("弹簧", "缓动", "线性", "关闭", "自定义曲线")
    var style by mutableIntStateOf(0)
    /** 当前生效的外部 @type motion 插件配置（非空且未处于「关闭」模式时接管全局 Motion） */
    var pluginProfile by mutableStateOf<MotionPluginProfile?>(null); private set

    // 自定义贝塞尔曲线控制点（支持 y 轴 -0.45..1.45 过冲）
    var x1 by mutableFloatStateOf(0.22f)
    var y1 by mutableFloatStateOf(1.00f)
    var x2 by mutableFloatStateOf(0.36f)
    var y2 by mutableFloatStateOf(1.00f)
    // 自定义弹簧物理参数
    var springDamping by mutableFloatStateOf(0.85f)
    var springStiffness by mutableFloatStateOf(400f)
    // 各类动画基准时长（ms）
    var quickBaseMs by mutableIntStateOf(120)
    var standardBaseMs by mutableIntStateOf(240)
    var pageBaseMs by mutableIntStateOf(320)
    var crossfadeBaseMs by mutableIntStateOf(400)

    val off get() = style == 3
    val custom get() = style == 4
    val activeLabel: String get() = if (off) labels[3] else pluginProfile?.displayName ?: labels[style]

    private var cachedKey = ""
    private var cachedEasing: Easing = SmoothBezierEasing(0.22f, 1.00f, 0.36f, 1.00f)
    val customEasing: Easing get() {
        val a = x1.coerceIn(0f, 1f); val b = y1.coerceIn(-0.45f, 1.45f)
        val c = x2.coerceIn(0f, 1f); val d = y2.coerceIn(-0.45f, 1.45f)
        val key = "$a,$b,$c,$d"
        if (key != cachedKey) { cachedKey = key; cachedEasing = SmoothBezierEasing(a, b, c, d) }
        return cachedEasing
    }
    val activeEasing: Easing get() = pluginProfile?.standard?.easing ?: when (style) {
        2 -> LinearEasing
        4 -> customEasing
        else -> FastOutSlowInEasing
    }

    /** 接收 @type motion 插件的 profile() JSON 并解析为原生 120fps 动效通道 */
    fun applyPlugin(pair: Pair<JsSource, JSONObject>?) {
        if (pair == null) { pluginProfile = null; return }
        val (src, o) = pair
        val fx = o.optJSONObject("effects")
        fun ch(key: String, defEase: Easing, defMs: Int): MotionChannel {
            val c = o.optJSONObject(key) ?: return MotionChannel(defEase, defMs)
            val ms = c.optInt("ms", defMs).coerceIn(40, 1200)
            val easeName = c.optString("easing", "").lowercase()
            if (easeName == "spring") {
                return MotionChannel(FastOutSlowInEasing, ms, isSpring = true,
                    damping = c.optDouble("damping", 0.82).toFloat().coerceIn(0.3f, 1f),
                    stiffness = c.optDouble("stiffness", 420.0).toFloat().coerceIn(80f, 1400f))
            }
            val easing = if (easeName == "bezier" || c.has("x1")) {
                SmoothBezierEasing(
                    c.optDouble("x1", 0.16).toFloat(), c.optDouble("y1", 1.0).toFloat(),
                    c.optDouble("x2", 0.30).toFloat(), c.optDouble("y2", 1.0).toFloat(),
                )
            } else MotionEasings.byName(easeName, defEase)
            return MotionChannel(easing, ms)
        }
        pluginProfile = MotionPluginProfile(
            pluginId = src.id,
            pluginName = o.optString("name").ifBlank { src.name },
            presetLabel = o.optString("presetLabel"),
            quick = ch("quick", MotionEasings.OutExpo, 140),
            standard = ch("standard", MotionEasings.OutQuint, 300),
            page = ch("page", MotionEasings.OutQuint, 420),
            crossfade = ch("crossfade", MotionEasings.OutQuint, 460),
            hero = ch("hero", MotionEasings.OutElasticQuarter, 440),
            hoverShiftDp = fx?.takeIf { it.has("hoverShiftDp") }?.optDouble("hoverShiftDp", 5.0)?.toFloat()?.coerceIn(0f, 12f),
            hoverTint = fx?.takeIf { it.has("hoverTint") }?.optBoolean("hoverTint"),
            pressScale = fx?.takeIf { it.has("pressScale") }?.optDouble("pressScale", 0.985)?.toFloat()?.coerceIn(0.92f, 1.0f),
            pageScale = fx?.takeIf { it.has("pageScale") }?.optBoolean("pageScale"),
            listStaggerMs = fx?.takeIf { it.has("listStaggerMs") }?.optInt("listStaggerMs", 24)?.coerceIn(0, 80),
            listSlideDp = fx?.takeIf { it.has("listSlideDp") }?.optDouble("listSlideDp", 36.0)?.toFloat()?.coerceIn(0f, 80f),
        )
    }

    private val k get() = Plat.prefs("ui")
    fun load() {
        Anim.load()
        val raw = k.getInt("animStyle", 0)
        // 平滑迁移旧版索引（原 4=内置osu! 已拆为插件，原 5=自定义曲线 顺移为 4）
        style = when {
            raw == 5 -> 4
            raw == 4 && !k.getBoolean("anim_v2_migrated", false) -> { k.edit().putBoolean("anim_v2_migrated", true).putInt("animStyle", 0).apply(); 0 }
            else -> raw.coerceIn(0, labels.lastIndex)
        }
        x1 = k.getFloat("anim_x1", 0.22f).coerceIn(0f, 1f)
        y1 = k.getFloat("anim_y1", 1.00f).coerceIn(-0.45f, 1.45f)
        x2 = k.getFloat("anim_x2", 0.36f).coerceIn(0f, 1f)
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
        k.edit().putBoolean("anim_v2_migrated", true).putInt("animStyle", style)
            .putFloat("anim_x1", x1).putFloat("anim_y1", y1).putFloat("anim_x2", x2).putFloat("anim_y2", y2)
            .putFloat("anim_sd", springDamping).putFloat("anim_ss", springStiffness)
            .putInt("anim_ms_q", quickBaseMs).putInt("anim_ms_s", standardBaseMs)
            .putInt("anim_ms_p", pageBaseMs).putInt("anim_ms_c", crossfadeBaseMs).apply()
    }
    fun set(v: Int) { style = v.coerceIn(0, labels.lastIndex); save() }
    fun setCurve(a: Float, b: Float, c: Float, d: Float, switchCustom: Boolean = true, persist: Boolean = true) {
        x1 = a.coerceIn(0f, 1f); y1 = b.coerceIn(-0.45f, 1.45f); x2 = c.coerceIn(0f, 1f); y2 = d.coerceIn(-0.45f, 1.45f)
        if (switchCustom) style = 4
        if (persist) save()
    }
    fun reset() {
        style = 0; Anim.set(1)
        x1 = 0.22f; y1 = 1f; x2 = 0.36f; y2 = 1f
        springDamping = 0.85f; springStiffness = 400f
        quickBaseMs = 120; standardBaseMs = 240; pageBaseMs = 320; crossfadeBaseMs = 400
        save(); MotionEffects.reset()
    }
    fun exportJson(): String = JSONObject()
        .put("format", "nekoplay-motion-v2")
        .put("style", style).put("speedLevel", Anim.level)
        .put("x1", x1.toDouble()).put("y1", y1.toDouble()).put("x2", x2.toDouble()).put("y2", y2.toDouble())
        .put("springDamping", springDamping.toDouble()).put("springStiffness", springStiffness.toDouble())
        .put("quickMs", quickBaseMs).put("standardMs", standardBaseMs).put("pageMs", pageBaseMs).put("crossfadeMs", crossfadeBaseMs)
        .put("hoverShiftDp", MotionEffects.userHoverShiftDp.toDouble()).put("hoverTint", MotionEffects.userHoverTint)
        .put("pressScale", MotionEffects.userPressScale.toDouble()).put("pageScale", MotionEffects.userPageScale)
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
        if (o.has("hoverShiftDp")) MotionEffects.hoverShiftDp = o.optDouble("hoverShiftDp", MotionEffects.userHoverShiftDp.toDouble()).toFloat()
        if (o.has("hoverTint")) MotionEffects.hoverTint = o.optBoolean("hoverTint", MotionEffects.userHoverTint)
        if (o.has("pressScale")) MotionEffects.pressScale = o.optDouble("pressScale", MotionEffects.userPressScale.toDouble()).toFloat()
        if (o.has("pageScale")) MotionEffects.pageScale = o.optBoolean("pageScale", MotionEffects.userPageScale)
        save(); MotionEffects.save()
    }
    val ms get() = (pageBaseMs / Anim.f).toInt().coerceAtLeast(1)
}

/** 标准数学解析缓动库（零 GC 60/120fps，供 @type motion 插件精确声明 OutQuint / OutExpo / OutPow10 / OutElastic 等曲线） */
object MotionEasings {
    val OutQuad = Easing { t -> val u = 1f - t; 1f - u * u }
    val OutCubic = Easing { t -> val u = 1f - t; 1f - u * u * u }
    val OutQuart = Easing { t -> val u = 1f - t; 1f - u * u * u * u }
    val OutQuint = Easing { t -> val u = 1f - t; 1f - u * u * u * u * u }
    val OutPow10 = Easing { t -> val u = (1.0 - t.toDouble()); (1.0 - u.pow(10.0)).toFloat() }
    /** 归一化 OutExpo / OutExpoTail：前 20% 时间瞬间冲出 80%+，后 80% 形成超长丝滑留尾且末帧 0 跳变 */
    private val expo10Norm = 1.0 / (1.0 - 2.0.pow(-10.0))
    private val expo12Norm = 1.0 / (1.0 - 2.0.pow(-12.0))
    val OutExpo = Easing { t -> if (t >= 1f) 1f else if (t <= 0f) 0f else ((1.0 - 2.0.pow(-10.0 * t)) * expo10Norm).toFloat() }
    val OutExpoTail = Easing { t -> if (t >= 1f) 1f else if (t <= 0f) 0f else ((1.0 - 2.0.pow(-12.0 * t)) * expo12Norm).toFloat() }
    val OutCirc = Easing { t -> val u = t - 1f; sqrt((1f - u * u).coerceAtLeast(0f)) }
    val OutBack = Easing { t -> val u = t - 1f; 1f + 2.70158f * u * u * u + 1.70158f * u * u }
    val OutElasticHalf = Easing { t ->
        if (t <= 0f) 0f else if (t >= 1f) 1f
        else (2.0.pow(-10.0 * t) * sin((0.5 * t - 0.075) * (2.0 * PI) / 0.3) + 1.0).toFloat()
    }
    val OutElasticQuarter = Easing { t ->
        if (t <= 0f) 0f else if (t >= 1f) 1f
        else (2.0.pow(-10.0 * t) * sin((0.25 * t - 0.075) * (2.0 * PI) / 0.3) + 1.0).toFloat()
    }
    val InOutCubic = Easing { t -> if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f }
    val InOutQuint = Easing { t -> if (t < 0.5f) 16f * t * t * t * t * t else 1f - (-2f * t + 2f).let { it * it * it * it * it } / 2f }

    fun byName(name: String, fallback: Easing): Easing = when (name.lowercase()) {
        "linear" -> LinearEasing
        "outquad" -> OutQuad
        "outcubic" -> OutCubic
        "outquart" -> OutQuart
        "outquint" -> OutQuint
        "outpow10" -> OutPow10
        "outexpo" -> OutExpo
        "outexpotail" -> OutExpoTail
        "outcirc" -> OutCirc
        "outback" -> OutBack
        "outelastichalf" -> OutElasticHalf
        "outelasticquarter" -> OutElasticQuarter
        "inoutcubic" -> InOutCubic
        "inoutquint" -> InOutQuint
        "fastoutslowin" -> FastOutSlowInEasing
        "decelout" -> DecelOut
        else -> fallback
    }
}

/** 64 位双精度三次贝塞尔缓动（牛顿迭代 + 二分兜底，精度 1e-7）：消除极端控制点末帧跳变顿挫 */
data class SmoothBezierEasing(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Easing {
    private val ax = x1.toDouble().coerceIn(0.0, 1.0); private val ay = y1.toDouble()
    private val bx = x2.toDouble().coerceIn(0.0, 1.0); private val by = y2.toDouble()
    private val cx = 3.0 * ax; private val bxC = 3.0 * (bx - ax) - cx; private val axC = 1.0 - cx - bxC
    private val cy = 3.0 * ay; private val byC = 3.0 * (by - ay) - cy; private val ayC = 1.0 - cy - byC
    private fun sampleX(t: Double) = ((axC * t + bxC) * t + cx) * t
    private fun sampleY(t: Double) = ((ayC * t + byC) * t + cy) * t
    private fun sampleDerivX(t: Double) = (3.0 * axC * t + 2.0 * bxC) * t + cx
    override fun transform(fraction: Float): Float {
        if (fraction <= 0f) return 0f; if (fraction >= 1f) return 1f
        val x = fraction.toDouble(); var t = x
        for (i in 0 until 8) {
            val err = sampleX(t) - x; if (kotlin.math.abs(err) < 1e-7) return sampleY(t).toFloat()
            val d = sampleDerivX(t); if (kotlin.math.abs(d) < 1e-7) break
            t = (t - err / d).coerceIn(0.0, 1.0)
        }
        var lo = 0.0; var hi = 1.0; t = x
        repeat(24) {
            val cur = sampleX(t); if (kotlin.math.abs(cur - x) < 1e-7) return sampleY(t).toFloat()
            if (cur < x) lo = t else hi = t; t = 0.5 * (lo + hi)
        }
        return sampleY(t).toFloat()
    }
}

val DecelOut = SmoothBezierEasing(0f, 0f, 0.2f, 1f)

/** 统一动效令牌（所有 UI 组件统一消费；当启用 @type motion 插件时自动由插件各通道接管） */
object Motion {
    val quickMs get() = if (AnimStyle.off) 0 else AnimStyle.pluginProfile?.quick?.scaledMs() ?: (AnimStyle.quickBaseMs / Anim.f).toInt().coerceAtLeast(1)
    val standardMs get() = if (AnimStyle.off) 0 else AnimStyle.pluginProfile?.standard?.scaledMs() ?: (AnimStyle.standardBaseMs / Anim.f).toInt().coerceAtLeast(1)
    val pageMs get() = if (AnimStyle.off) 0 else AnimStyle.pluginProfile?.page?.scaledMs() ?: (AnimStyle.pageBaseMs / Anim.f).toInt().coerceAtLeast(1)
    val crossfadeMs get() = if (AnimStyle.off) 0 else AnimStyle.pluginProfile?.crossfade?.scaledMs() ?: (AnimStyle.crossfadeBaseMs / Anim.f).toInt().coerceAtLeast(1)

    fun <T> quick(): FiniteAnimationSpec<T> =
        if (AnimStyle.off) snap()
        else AnimStyle.pluginProfile?.quick?.spec() ?: tween(quickMs, easing = if (AnimStyle.style in listOf(2, 4)) AnimStyle.activeEasing else DecelOut)
    fun <T> standard(): FiniteAnimationSpec<T> =
        if (AnimStyle.off) snap()
        else AnimStyle.pluginProfile?.standard?.spec() ?: tween(standardMs, easing = AnimStyle.activeEasing)
    fun <T> page(): FiniteAnimationSpec<T> =
        if (AnimStyle.off) snap()
        else AnimStyle.pluginProfile?.page?.spec() ?: tween(pageMs, easing = AnimStyle.activeEasing)
    fun <T> crossfade(): FiniteAnimationSpec<T> =
        if (AnimStyle.off) snap()
        else AnimStyle.pluginProfile?.crossfade?.spec() ?: tween(crossfadeMs, easing = AnimStyle.activeEasing)
    fun <T> hero(): FiniteAnimationSpec<T> =
        if (AnimStyle.off) snap()
        else AnimStyle.pluginProfile?.hero?.spec()
            ?: if (AnimStyle.style != 0) tween(pageMs, easing = AnimStyle.activeEasing)
            else spring(dampingRatio = AnimStyle.springDamping, stiffness = Anim.stiff(AnimStyle.springStiffness))
}

val sp: FiniteAnimationSpec<IntOffset> get() = Motion.hero()
val spF: FiniteAnimationSpec<Float> get() = Motion.hero()

/** osu! 风格列表逐项瀑布错峰入场协调器（同一波次 140ms 内连续挂载的列表项按序错峰，超过 16 项封顶不拖慢长列表滚动） */
object ListStagger {
    private var waveMs = 0L; private var slot = 0
    fun nextDelayMs(): Long {
        val step = MotionEffects.listStaggerMs; if (step <= 0 || AnimStyle.off) return 0L
        val now = System.currentTimeMillis(); if (now - waveMs > 140L) { waveMs = now; slot = 0 }
        val idx = slot++; return if (idx < 16) (idx * step / Anim.f).toLong() else 0L
    }
}

/** 歌单 / 曲目逐项右滑留尾错峰入场（纯 GPU graphicsLayer，0 重组） */
fun Modifier.cascadeEnter(): Modifier = composed {
    val slideDp = MotionEffects.listSlideDp; val stagger = MotionEffects.listStaggerMs
    if (AnimStyle.off || (stagger <= 0 && slideDp < 1f)) return@composed this
    val prog = remember { Animatable(0f) }
    LaunchedEffect(Unit) { val d = ListStagger.nextDelayMs(); if (d > 0L) delay(d); prog.animateTo(1f, Motion.page()) }
    this.graphicsLayer { val p = prog.value; alpha = p.coerceIn(0f, 1f); translationX = (1f - p) * slideDp.dp.toPx() }
}
