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
/** 动效形式：0 弹簧（带一点回弹感的物理动画）/ 1 缓动（先快后慢）/ 2 线性 / 3 关闭（瞬间切换）/ 4 osu!（极速起手 + 丝滑指数缓出，带微缩放） */
object AnimStyle {
    val labels = listOf("弹簧", "缓动", "线性", "关闭", "osu!")
    var style by mutableIntStateOf(0)
    val osu get() = style == 4
    val off get() = style == 3
    fun load() { style = Plat.prefs("ui").getInt("animStyle", 0).coerceIn(0, labels.lastIndex) }
    fun set(v: Int) { style = v.coerceIn(0, labels.lastIndex); Plat.prefs("ui").edit().putInt("animStyle", style).apply() }
    val ms get() = (320 / Anim.f).toInt()
    val osuMs get() = (360 / Anim.f).toInt()
}
/** osu! stable 招牌 OutExpo/OutQuint 缓出曲线：瞬间冲出、长尾丝滑刹停、零回弹 */
val OsuOut = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
val DecelOut = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * 0.7.0 统一动效令牌（受 Anim 速度档位与 AnimStyle 关闭开关统一控制）：
 * - quick (120ms 减速)：按钮按下、开关、图标切换
 * - standard (240ms 标准缓动)：弹窗、菜单、面板展开
 * - page (320ms 强调缓动)：切页、进出详情
 * - crossfade (400ms)：换歌封面与背景交叉淡化
 * - hero (弹簧 阻尼 0.85)：迷你播放条 ↔ 播放页、封面放大
 */
object Motion {
    val quickMs get() = if (AnimStyle.off) 0 else (120 / Anim.f).toInt().coerceAtLeast(1)
    val standardMs get() = if (AnimStyle.off) 0 else (240 / Anim.f).toInt().coerceAtLeast(1)
    val pageMs get() = if (AnimStyle.off) 0 else (320 / Anim.f).toInt().coerceAtLeast(1)
    val crossfadeMs get() = if (AnimStyle.off) 0 else (400 / Anim.f).toInt().coerceAtLeast(1)
    fun <T> quick(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (AnimStyle.off) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(quickMs, easing = DecelOut)
    fun <T> standard(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (AnimStyle.off) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(standardMs, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    fun <T> page(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (AnimStyle.off) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(pageMs, easing = OsuOut)
    fun <T> hero(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (AnimStyle.off) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.85f, stiffness = Anim.stiff(Spring.StiffnessMediumLow))
}
private fun <T> styled(stiff: Float): androidx.compose.animation.core.FiniteAnimationSpec<T> = when (AnimStyle.style) {
    1 -> androidx.compose.animation.core.tween(AnimStyle.ms, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    2 -> androidx.compose.animation.core.tween(AnimStyle.ms, easing = androidx.compose.animation.core.LinearEasing)
    3 -> androidx.compose.animation.core.snap()
    4 -> androidx.compose.animation.core.tween(AnimStyle.osuMs, easing = OsuOut)
    else -> spring(dampingRatio = .92f, stiffness = Anim.stiff(stiff))
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
