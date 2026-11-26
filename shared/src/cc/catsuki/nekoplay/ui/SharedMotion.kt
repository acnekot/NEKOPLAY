package cc.catsuki.nekoplay

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 两阶段零开销实时缩放状态：
 * - 拖动「界面缩放」滑块或桌面开启 autoScale 拖窗期间，仅更新 GPU 缩放比例（0 重组、0 字体/图片缓存重建）；
 * - 配合 liveScaleContainer() 做约束反补偿，放大缩小始终 100% 铺满视口、无黑边不裁切；
 * - 松手或窗口停稳后同一帧原子落位到 LocalDensity 并归零 GPU 倍率，像素位置完全吻合，零跳变且恢复原生锐利渲染。
 */
object UiScalePreview {
    /** 用户正在拖动「界面缩放」滑块时的实时目标比例（如 1.15f）；null 表示未在拖动 */
    var liveUserScale by mutableStateOf<Float?>(null)
    /** 桌面开启 autoScale 拖窗期间的实时自适应倍率；null 表示已与 LocalDensity 同步 */
    var liveAutoScale by mutableStateOf<Float?>(null)
    /** 当前已提交到 LocalDensity 的用户缩放倍率 */
    var committedUserScale by mutableFloatStateOf(1f)
    /** 当前已提交到 LocalDensity 的桌面自适应倍率 */
    var committedAutoScale by mutableFloatStateOf(1f)

    /** 拖动「界面缩放」滑块期间暂时冻结宽窄屏断点切换，避免布局树中途重建打断手指拖动 */
    val freezeBreakpoint: Boolean get() = liveUserScale != null

    /** 当前生效的目标总倍率 */
    fun targetTotalScale(): Float {
        val u = liveUserScale ?: committedUserScale
        val a = liveAutoScale ?: committedAutoScale
        return (u * a).coerceIn(0.6f, 1.6f)
    }

    /** 当前已提交到 LocalDensity 的总倍率 */
    fun committedTotalScale(): Float = (committedUserScale * committedAutoScale).coerceIn(0.6f, 1.6f)

    /** 仅在 Layout / Draw 阶段读取的纯 GPU 相对缩放倍率 */
    fun gpuRatio(): Float {
        if (liveUserScale == null && liveAutoScale == null) return 1f
        val base = committedTotalScale().coerceAtLeast(0.5f)
        return (targetTotalScale() / base).coerceIn(0.55f, 1.85f)
    }

    fun clear() {
        liveUserScale = null
        liveAutoScale = null
    }
}

/**
 * 约束反补偿 + 纯 GPU 缩放容器：
 * 以 W/r × H/r 测量内容再由 GPU 放大 r 倍，确保任意缩放比例下都 100% 贴边铺满，
 * 且松手切回原生 LocalDensity 时逻辑 dp 宽度完全一致（(W/r)/(d*base) == W/(d*target)），视觉零跳变。
 */
fun Modifier.liveScaleContainer(): Modifier = this
    .layout { measurable, constraints ->
        val r = UiScalePreview.gpuRatio()
        if (abs(r - 1f) < 0.0005f) {
            val p = measurable.measure(constraints)
            layout(p.width, p.height) { p.place(0, 0) }
        } else {
            val cw = if (constraints.hasBoundedWidth) (constraints.maxWidth / r).roundToInt().coerceAtLeast(1) else constraints.maxWidth
            val ch = if (constraints.hasBoundedHeight) (constraints.maxHeight / r).roundToInt().coerceAtLeast(1) else constraints.maxHeight
            val minW = if (constraints.hasBoundedWidth) (constraints.minWidth / r).roundToInt().coerceAtMost(cw) else constraints.minWidth
            val minH = if (constraints.hasBoundedHeight) (constraints.minHeight / r).roundToInt().coerceAtMost(ch) else constraints.minHeight
            val p = measurable.measure(constraints.copy(minWidth = minW, maxWidth = cw, minHeight = minH, maxHeight = ch))
            val outW = if (constraints.hasBoundedWidth) constraints.maxWidth else (p.width * r).roundToInt()
            val outH = if (constraints.hasBoundedHeight) constraints.maxHeight else (p.height * r).roundToInt()
            layout(outW, outH) { p.place(0, 0) }
        }
    }
    .graphicsLayer {
        val r = UiScalePreview.gpuRatio()
        if (abs(r - 1f) >= 0.0005f) {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = r
            scaleY = r
        }
    }

// ======================= 共享元素平滑直飞引擎（相同内容切换直接移动过去） =======================

internal data class HeroAnchorEntry(
    val token: Int,
    val key: String,
    val url: String?,
    val title: String,
    val border: Boolean,
    val isTarget: Boolean,
    val tag: String,
    val priority: Int,
    val getRect: () -> Rect?,
)

internal data class PendingHero(
    val key: String,
    val url: String?,
    val title: String,
    val border: Boolean,
    val from: Rect,
    val atMs: Long,
)

internal data class HeroFlight(
    val seq: Int,
    val key: String,
    val url: String?,
    val title: String,
    val border: Boolean,
    val from: Rect,
    val destToken: Int,
    var lastTo: Rect,
    val hideSrcToken: Int = -1,
)

/**
 * 轻量跨页共享元素飞行控制器（严格意图驱动，杜绝切歌/滚动误触）：
 * - 仅在点击进入目标页/播放页显式调用 capture(...) 后，才允许对应 isTarget=true 锚点消费触发直飞；
 * - 切歌、滚动列表、背景页更新等未武装场景绝不产生任何误飞或重影；
 * - 支持按 tag 精准锁定被点击的源卡片/播放条，以及按 priority 精准回飞至前台播控栏。
 */
object SharedHero {
    private var nextToken = 1
    private var nextSeq = 1
    private val anchors = LinkedHashMap<Int, HeroAnchorEntry>()
    private var pendingOpen: PendingHero? = null
    internal var flight by mutableStateOf<HeroFlight?>(null)
    internal var hostOrigin by mutableStateOf(Offset.Zero)

    internal fun allocToken(): Int = nextToken++

    internal fun register(entry: HeroAnchorEntry) {
        anchors[entry.token] = entry
    }

    internal fun unregister(token: Int) {
        anchors.remove(token)
        if (flight?.destToken == token) flight = null
    }

    /** 供测试或重置状态清理使用 */
    internal fun resetForTest() {
        anchors.clear()
        pendingOpen = null
        flight = null
    }

    /** 点击源条目或播放条打开目标前武装直飞；传 tag 可在同屏存在多个同 key 封面时精准定位被点击项 */
    fun capture(key: String, url: String? = null, title: String = "", border: Boolean = true, tag: String = "") {
        // 源点容器展开或点线面容器模式生效时由整块容器统一转场，避免再叠一层独立飞封面产生双影卡顿
        if (AnimStyle.off || MotionEffects.originExpand || MotionEffects.geomContainer || key.isEmpty()) return
        val candidates = anchors.values.filter { it.key == key && !it.isTarget }
        val src = (if (tag.isNotEmpty()) candidates.lastOrNull { it.tag == tag } else null) ?: candidates.lastOrNull() ?: return
        val rect = src.getRect()?.takeIf { it.width > 4f && it.height > 4f } ?: return
        pendingOpen = PendingHero(key, url ?: src.url, title.ifEmpty { src.title }, border, rect, System.currentTimeMillis())
    }

    /** 仅当目标锚点（isTarget=true）首次落位且命中刚武装的同 key 打开意图时启动直飞 */
    internal fun onAnchorFirstPlaced(token: Int, key: String, url: String?, title: String, border: Boolean, isTarget: Boolean, destRect: Rect) {
        if (!isTarget || AnimStyle.off || key.isEmpty() || destRect.width <= 4f || destRect.height <= 4f) return
        val p = pendingOpen ?: return
        if (p.key != key) return
        if (System.currentTimeMillis() - p.atMs > 450L) {
            pendingOpen = null
            return
        }
        pendingOpen = null
        if (!isDistinct(p.from, destRect)) return
        startFlight(key, url ?: p.url, title.ifEmpty { p.title }, border, p.from, token, destRect)
    }

    private fun isDistinct(a: Rect, b: Rect): Boolean {
        val dx = abs(a.left - b.left) + abs(a.top - b.top)
        val ds = abs(a.width - b.width) + abs(a.height - b.height)
        return dx > 16f || ds > 12f
    }

    /** 主动从关闭中的目标锚点（如播放页大封面）回飞至前台优先级最高的源锚点（优先底栏 MiniBar/PlayerBar） */
    fun flyBack(key: String) {
        if (AnimStyle.off || MotionEffects.originExpand || MotionEffects.geomContainer || key.isEmpty()) return
        val src = anchors.values.lastOrNull { it.key == key && it.isTarget } ?: return
        val fromRect = src.getRect()?.takeIf { it.width > 4f && it.height > 4f } ?: return
        val dstList = anchors.values.filter { it.key == key && !it.isTarget }.mapNotNull { entry ->
            entry.getRect()?.takeIf { it.width > 4f && it.height > 4f && it.right > 0f && it.bottom > 0f }?.let { entry to it }
        }
        if (dstList.isEmpty()) return
        val maxPrio = dstList.maxOf { it.first.priority }
        val (dst, toRect) = dstList.last { it.first.priority == maxPrio }
        if (!isDistinct(fromRect, toRect)) return
        startFlight(key, src.url ?: dst.url, src.title.ifEmpty { dst.title }, dst.border, fromRect, dst.token, toRect, hideSrcToken = src.token)
    }

    private fun startFlight(key: String, url: String?, title: String, border: Boolean, from: Rect, destToken: Int, to: Rect, hideSrcToken: Int = -1) {
        flight = HeroFlight(nextSeq++, key, url, title, border, from, destToken, to, hideSrcToken)
    }

    internal fun liveDestRect(f: HeroFlight): Rect {
        val r = anchors[f.destToken]?.getRect()?.takeIf { it.width > 4f && it.height > 4f }
        if (r != null) f.lastTo = r
        return f.lastTo
    }

    internal fun finish(seq: Int) {
        if (flight?.seq == seq) flight = null
    }

    internal fun isHidden(token: Int): Boolean {
        val f = flight ?: return false
        return f.destToken == token || (f.hideSrcToken == token)
    }
}

/** 标记共享内容锚点：源条目用 isTarget=false + tag，目标页/播放页大封面用 isTarget=true；未武装时零飞行、零误触 */
@Composable
fun Modifier.heroAnchor(
    key: String?,
    url: String?,
    title: String = "",
    border: Boolean = true,
    isTarget: Boolean = false,
    tag: String = "",
    priority: Int = 0,
): Modifier {
    if (key.isNullOrEmpty()) return this
    val token = remember { SharedHero.allocToken() }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var firstPlaced by remember(key) { mutableStateOf(false) }
    val curUrl by rememberUpdatedState(url)
    val curTitle by rememberUpdatedState(title)

    DisposableEffect(token, key) {
        SharedHero.register(HeroAnchorEntry(token, key, curUrl, curTitle, border, isTarget, tag, priority) {
            coords?.takeIf { it.isAttached }?.boundsInRoot()
        })
        onDispose { SharedHero.unregister(token) }
    }
    SideEffect {
        SharedHero.register(HeroAnchorEntry(token, key, curUrl, curTitle, border, isTarget, tag, priority) {
            coords?.takeIf { it.isAttached }?.boundsInRoot()
        })
    }

    return this
        .onPlaced { lc ->
            coords = lc
            if (!firstPlaced && lc.isAttached) {
                firstPlaced = true
                SharedHero.onAnchorFirstPlaced(token, key, curUrl, curTitle, border, isTarget, lc.boundsInRoot())
            }
        }
        .graphicsLayer {
            if (SharedHero.isHidden(token)) alpha = 0f
        }
}

/** 共享元素飞行浮层：挂载在 Root 顶层，平时空返回零消耗；飞行中纯 GPU 位移+缩放 120fps 精准着陆 */
@Composable
fun SharedHeroHost() {
    Box(Modifier.fillMaxSize().onPlaced { if (it.isAttached) SharedHero.hostOrigin = it.positionInRoot() }) {
        val f = SharedHero.flight ?: return@Box
        val prog = remember(f.seq) { Animatable(0f) }
        LaunchedEffect(f.seq) {
            if (AnimStyle.off) {
                SharedHero.finish(f.seq)
            } else {
                prog.snapTo(0f)
                prog.animateTo(1f, Motion.page())
                SharedHero.finish(f.seq)
            }
        }
        val dens = LocalDensity.current
        val refPx = max(max(f.from.width, f.from.height), max(f.lastTo.width, f.lastTo.height)).coerceAtLeast(32f)
        val refDp = with(dens) { refPx.toDp() }
        Box(Modifier.graphicsLayer {
            val t = prog.value.coerceIn(0f, 1.15f)
            val dst = SharedHero.liveDestRect(f)
            val ox = SharedHero.hostOrigin.x
            val oy = SharedHero.hostOrigin.y
            val x = f.from.left + (dst.left - f.from.left) * t - ox
            val y = f.from.top + (dst.top - f.from.top) * t - oy
            val w = (f.from.width + (dst.width - f.from.width) * t).coerceAtLeast(1f)
            val h = (f.from.height + (dst.height - f.from.height) * t).coerceAtLeast(1f)
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = x
            translationY = y
            scaleX = w / refPx
            scaleY = h / refPx
        }) {
            Cover(f.url, refDp, f.title, border = f.border, decodeSize = 512.dp)
        }
    }
}
