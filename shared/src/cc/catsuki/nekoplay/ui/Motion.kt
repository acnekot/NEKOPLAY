package cc.catsuki.nekoplay

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

/** 空间微交互令牌（与曲线/时间引擎解耦；瀑布入场等重动效完全剥离至 @type motion 插件，未装插件时保持纯净轻量默认） */
object MotionEffects {
    var userHoverShiftDp by mutableFloatStateOf(3f)
    var userHoverTint by mutableStateOf(true)
    var userPressScale by mutableFloatStateOf(0.985f)
    var userPageScale by mutableStateOf(true)

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
    /** 瀑布错峰入场与源点空间动效仅由外置 @type motion 插件提供；未安装动效插件时保持纯净轻量默认 */
    val listStaggerMs: Int get() = AnimStyle.pluginProfile?.listStaggerMs ?: 0
    val listSlideDp: Float get() = AnimStyle.pluginProfile?.listSlideDp ?: 0f
    val originExpand: Boolean get() = !AnimStyle.off && AnimStyle.pluginProfile?.originExpand == true
    val originRipple: Boolean get() = !AnimStyle.off && AnimStyle.pluginProfile?.originRipple == true
    val originInherit: Boolean get() = !AnimStyle.off && AnimStyle.pluginProfile?.originInherit == true
    val originCornerDp: Float get() = AnimStyle.pluginProfile?.originCornerDp ?: 18f
    val selectCoverScale: Float get() = AnimStyle.pluginProfile?.selectCoverScale ?: 1.09f
    val selectFontScale: Float get() = AnimStyle.pluginProfile?.selectFontScale ?: 1.05f
    val originActive: Boolean get() = originExpand || originRipple || originInherit
    val geom: GeomRevealConfig? get() = if (AnimStyle.off) null else AnimStyle.pluginProfile?.geom
    val geomReveal: Boolean get() = geom != null
    val geomContainer: Boolean get() = geom?.container == true

    private val k get() = Plat.prefs("ui")
    fun load() {
        userHoverShiftDp = k.getFloat("me_hoverShift", 3f).coerceIn(0f, 12f)
        userHoverTint = k.getBoolean("me_hoverTint", true)
        userPressScale = k.getFloat("me_pressScale", 0.985f).coerceIn(0.92f, 1.0f)
        userPageScale = k.getBoolean("me_pageScale", true)
    }
    fun save() {
        k.edit().putFloat("me_hoverShift", userHoverShiftDp).putBoolean("me_hoverTint", userHoverTint)
            .putFloat("me_pressScale", userPressScale).putBoolean("me_pageScale", userPageScale).apply()
    }
    fun reset() { userHoverShiftDp = 3f; userHoverTint = true; userPressScale = 0.985f; userPageScale = true; save() }
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

    val off get() = style == 3 || !Features.on("motion")
    val custom get() = style == 4 && Features.on("motion")
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
        pluginProfile = pair?.let { (src, o) -> parseMotionPluginProfile(src, o) }
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
