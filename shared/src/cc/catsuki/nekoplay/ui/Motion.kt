package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.ui.settings.BackupPage
import cc.catsuki.nekoplay.ui.settings.DavMusicPage
import cc.catsuki.nekoplay.ui.settings.DebugPage
import cc.catsuki.nekoplay.ui.settings.DownloadsPage
import cc.catsuki.nekoplay.ui.settings.FeaturesPage
import cc.catsuki.nekoplay.ui.settings.LocalMusicPage
import cc.catsuki.nekoplay.ui.settings.StatsPage

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.detectHorizontalDragGestures

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

object Anim {
    val labels = listOf("慢", "标准", "快", "很快")
    private val factors = listOf(0.7f, 1f, 1.4f, 2f)
    var level by mutableIntStateOf(1)
    val f get() = factors[level]
    fun stiff(base: Float) = base * f * f
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
    /** 播放页 / 面板展开收起时是否叠加微缩放过渡 */
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
    val customEasing get() = androidx.compose.animation.core.CubicBezierEasing(x1.coerceIn(0f, 1f), y1.coerceIn(-0.45f, 1.45f), x2.coerceIn(0f, 1f), y2.coerceIn(-0.45f, 1.45f))
    val activeEasing: androidx.compose.animation.core.Easing get() = when (style) {
        2 -> androidx.compose.animation.core.LinearEasing
        4 -> OsuOut
        5 -> customEasing
        else -> androidx.compose.animation.core.FastOutSlowInEasing
    }

    private val k get() = Plat.prefs("ui")
    fun load() {
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
    fun setCurve(a: Float, b: Float, c: Float, d: Float, switchCustom: Boolean = true) {
        x1 = a.coerceIn(0f, 1f); y1 = b.coerceIn(-0.45f, 1.45f); x2 = c.coerceIn(0f, 1f); y2 = d.coerceIn(-0.45f, 1.45f)
        if (switchCustom) style = 5
        save()
    }
    fun reset() {
        style = 0; Anim.level = 1; k.edit().putInt("anim", 1).apply()
        x1 = 0.16f; y1 = 1f; x2 = 0.30f; y2 = 1f
        springDamping = 0.85f; springStiffness = 400f
        quickBaseMs = 120; standardBaseMs = 240; pageBaseMs = 320; crossfadeBaseMs = 400
        save()
        MotionEffects.reset()
    }
    fun exportJson(): String = org.json.JSONObject()
        .put("format", "nekoplay-motion-v1")
        .put("style", style).put("speedLevel", Anim.level)
        .put("x1", x1.toDouble()).put("y1", y1.toDouble()).put("x2", x2.toDouble()).put("y2", y2.toDouble())
        .put("springDamping", springDamping.toDouble()).put("springStiffness", springStiffness.toDouble())
        .put("quickMs", quickBaseMs).put("standardMs", standardBaseMs).put("pageMs", pageBaseMs).put("crossfadeMs", crossfadeBaseMs)
        .put("hoverShiftDp", MotionEffects.hoverShiftDp.toDouble()).put("hoverTint", MotionEffects.hoverTint)
        .put("pressScale", MotionEffects.pressScale.toDouble()).put("pageScale", MotionEffects.pageScale)
        .toString(2)
    fun importJson(raw: String) {
        val o = org.json.JSONObject(raw.trim())
        if (o.has("style")) style = o.optInt("style", style).coerceIn(0, labels.lastIndex)
        if (o.has("speedLevel")) { Anim.level = o.optInt("speedLevel", Anim.level).coerceIn(0, Anim.labels.lastIndex); k.edit().putInt("anim", Anim.level).apply() }
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
/** osu! stable 招牌 OutExpo/OutQuint 缓出曲线：瞬间冲出、长尾丝滑刹停、零回弹 */
val OsuOut = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
val DecelOut = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * 统一动效令牌（所有组件统一消费此层，禁止在 UI 里直接判断 if (AnimStyle.osu)）：
 */
object Motion {
    val quickMs get() = if (AnimStyle.off) 0 else (AnimStyle.quickBaseMs / Anim.f).toInt().coerceAtLeast(1)
    val standardMs get() = if (AnimStyle.off) 0 else (AnimStyle.standardBaseMs / Anim.f).toInt().coerceAtLeast(1)
    val pageMs get() = if (AnimStyle.off) 0 else (AnimStyle.pageBaseMs / Anim.f).toInt().coerceAtLeast(1)
    val crossfadeMs get() = if (AnimStyle.off) 0 else (AnimStyle.crossfadeBaseMs / Anim.f).toInt().coerceAtLeast(1)
    fun <T> quick(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (AnimStyle.off) androidx.compose.animation.core.snap()
        else androidx.compose.animation.core.tween(quickMs, easing = if (AnimStyle.style in listOf(2, 4, 5)) AnimStyle.activeEasing else DecelOut)
    fun <T> standard(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (AnimStyle.off) androidx.compose.animation.core.snap()
        else androidx.compose.animation.core.tween(standardMs, easing = AnimStyle.activeEasing)
    fun <T> page(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (AnimStyle.off) androidx.compose.animation.core.snap()
        else androidx.compose.animation.core.tween(pageMs, easing = AnimStyle.activeEasing)
    fun <T> hero(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (AnimStyle.off) androidx.compose.animation.core.snap()
        else if (AnimStyle.style != 0) androidx.compose.animation.core.tween(pageMs, easing = AnimStyle.activeEasing)
        else spring(dampingRatio = AnimStyle.springDamping, stiffness = Anim.stiff(AnimStyle.springStiffness))
}
private fun <T> styled(stiff: Float): androidx.compose.animation.core.FiniteAnimationSpec<T> = when (AnimStyle.style) {
    1 -> androidx.compose.animation.core.tween(AnimStyle.ms, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    2 -> androidx.compose.animation.core.tween(AnimStyle.ms, easing = androidx.compose.animation.core.LinearEasing)
    3 -> androidx.compose.animation.core.snap()
    4 -> androidx.compose.animation.core.tween(AnimStyle.osuMs, easing = OsuOut)
    5 -> androidx.compose.animation.core.tween(AnimStyle.ms, easing = AnimStyle.customEasing)
    else -> spring(dampingRatio = AnimStyle.springDamping, stiffness = Anim.stiff(AnimStyle.springStiffness.coerceAtLeast(stiff * 0.5f)))
}
val sp: androidx.compose.animation.core.FiniteAnimationSpec<IntOffset> get() = styled(Spring.StiffnessMediumLow)
val spF: androidx.compose.animation.core.FiniteAnimationSpec<Float> get() = styled(Spring.StiffnessMediumLow)


object Keys {
    var nav: Nav? = null
    val HELP = listOf(
        "空格" to "播放 / 暂停", "Ctrl + →" to "下一首", "Ctrl + ←" to "上一首", "→ / ←" to "快进 / 快退 5 秒",
        "Ctrl + ↑ / ↓" to "音量 + / −", "Ctrl + L" to "喜欢 / 取消喜欢", "Ctrl + P" to "打开 / 收起播放页",
        "Ctrl + F" to "搜索", "Ctrl + Q" to "播放队列", "Ctrl + D" to "歌词栏", "Ctrl + ," to "设置", "Esc" to "返回",
        "媒体键" to "播放 / 暂停、上一首、下一首（窗口在前台时）",
    )
    /** 带 Ctrl 的组合键和媒体键：输入框里也生效 */
    fun handleCtrl(key: androidx.compose.ui.input.key.Key, ctrl: Boolean): Boolean {
        val n = nav ?: return false
        val K = androidx.compose.ui.input.key.Key
        when (key) {
            K.MediaPlayPause -> { Engine.toggle(); return true }
            K.MediaNext -> { Engine.next(); return true }
            K.MediaPrevious -> { Engine.prev(); return true }
        }
        if (!ctrl) return false
        when (key) {
            K.DirectionRight -> Engine.next()
            K.DirectionLeft -> Engine.prev()
            K.DirectionUp -> Vol.set(Vol.v + .05f)
            K.DirectionDown -> Vol.set(Vol.v - .05f)
            K.Spacebar -> Engine.toggle()
            K.L -> Engine.state.value.current?.let { t -> Store.toggleLike(t) { n.toast = it }; n.toast = if (t.id in Store.likedIds.value) "已喜欢" else "已取消喜欢" }
            K.P -> { if (Engine.state.value.current != null) { n.lyrics = false; n.player = !n.player } }
            K.F -> { n.player = false; n.stack.clear(); n.push(Page.Search) }
            K.Q -> n.queue = true
            K.D -> n.side = if (n.side == 1) 0 else 1
            K.Comma -> { n.player = false; n.stack.clear(); n.push(Page.Settings) }
            else -> return false
        }
        return true
    }
    /** 不带修饰键的：输入框没用掉才会到这里 */
    fun handlePlain(key: androidx.compose.ui.input.key.Key): Boolean {
        val K = androidx.compose.ui.input.key.Key
        when (key) {
            K.Spacebar -> Engine.toggle()
            K.DirectionRight -> Engine.seek(Engine.position.value + 5000)
            K.DirectionLeft -> Engine.seek((Engine.position.value - 5000).coerceAtLeast(0))
            else -> return false
        }
        return true
    }
}

/** 标题栏搜索框 → 搜索页：把要搜的词交给搜索页 */
object SearchBus { var query by mutableStateOf<String?>(null) }
