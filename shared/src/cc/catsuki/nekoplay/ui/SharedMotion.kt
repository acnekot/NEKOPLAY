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
 * 轻量跨页共享元素飞行控制器：
 * - 平常无飞行时零协程、零绘制、零重组；
 * - 切换页面或开关播放页时，相同内容（如封面）直接从源位置丝滑飞向目标位置，并在飞行中实时追踪目标坐标以精准落位；
 * - 飞行期间自动隐藏目标槽位，着陆同一帧无缝交接，杜绝双影与闪跳。
 */
object SharedHero {
    private var nextToken = 1
    private var nextSeq = 1
    private val anchors = LinkedHashMap<Int, HeroAnchorEntry>()
    private val pending = HashMap<String, PendingHero>()
    internal var flight by mutableStateOf<HeroFlight?>(null)
    internal var hostOrigin by mutableStateOf(Offset.Zero)

    internal fun allocToken(): Int = nextToken++

    internal fun register(entry: HeroAnchorEntry) {
        anchors[entry.token] = entry
    }

    internal fun unregister(token: Int) {
        val old = anchors.remove(token)
        if (old != null && !AnimStyle.off) {
            old.getRect()?.takeIf { it.width > 4f && it.height > 4f }?.let { rect ->
                // 若离开的是大元素（如播放页封面、详情页头图），且屏幕上仍有同 key 的归宿锚点，自动触发回飞
                val backTarget = anchors.values.lastOrNull { it.key == old.key && it.token != token }
                val toRect = backTarget?.getRect()?.takeIf { it.width > 4f && it.height > 4f }
                if (backTarget != null && toRect != null && isDistinct(rect, toRect)) {
                    startFlight(old.key, old.url ?: backTarget.url, old.title.ifEmpty { backTarget.title }, backTarget.border, rect, backTarget.token, toRect)
                } else {
                    pending[old.key] = PendingHero(old.key, old.url, old.title, old.border, rect, System.currentTimeMillis())
                }
            }
        }
        if (flight?.destToken == token) flight = null
    }

    /** 点击源条目或切换前主动捕获源位置（可选调用；即便不调，相同 key 新锚点出现时也会自动匹配仍在屏上的源锚点） */
    fun capture(key: String, url: String? = null, title: String = "", border: Boolean = true) {
        if (AnimStyle.off || key.isEmpty()) return
        val src = anchors.values.lastOrNull { it.key == key } ?: return
        val rect = src.getRect()?.takeIf { it.width > 4f && it.height > 4f } ?: return
        pending[key] = PendingHero(key, url ?: src.url, title.ifEmpty { src.title }, border, rect, System.currentTimeMillis())
    }

    /** 新锚点首次落位时尝试与刚捕获或同屏已存在的同 key 源锚点建立直飞 */
    internal fun onAnchorFirstPlaced(token: Int, key: String, url: String?, title: String, border: Boolean, destRect: Rect) {
        if (AnimStyle.off || key.isEmpty() || destRect.width <= 4f || destRect.height <= 4f) return
        val now = System.currentTimeMillis()
        val p = pending[key]?.takeIf { now - it.atMs <= 420L }
        val fromRect: Rect
        val u: String?
        val t: String
        if (p != null && isDistinct(p.from, destRect)) {
            pending.remove(key)
            fromRect = p.from
            u = url ?: p.url
            t = title.ifEmpty { p.title }
        } else {
            val other = anchors.values.lastOrNull { it.key == key && it.token != token }
            val otherRect = other?.getRect()?.takeIf { it.width > 4f && it.height > 4f } ?: return
            if (!isDistinct(otherRect, destRect)) return
            fromRect = otherRect
            u = url ?: other.url
            t = title.ifEmpty { other.title }
        }
        startFlight(key, u, t, border, fromRect, token, destRect)
    }

    private fun isDistinct(a: Rect, b: Rect): Boolean {
        val dx = abs(a.left - b.left) + abs(a.top - b.top)
        val ds = abs(a.width - b.width) + abs(a.height - b.height)
        return dx > 16f || ds > 12f
    }

    /** 主动从最新锚点回飞到同屏最早的同 key 归宿锚点（如关闭播放页时大封面立刻直飞回底部播放条） */
    fun flyBack(key: String) {
        if (AnimStyle.off || key.isEmpty()) return
        val matched = anchors.values.filter { it.key == key }
        if (matched.size < 2) return
        val src = matched.last()
        val dst = matched.first()
        val fromRect = src.getRect()?.takeIf { it.width > 4f && it.height > 4f } ?: return
        val toRect = dst.getRect()?.takeIf { it.width > 4f && it.height > 4f } ?: return
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

/** 标记共享内容锚点：相同 key 在页面或播放页切换时自动平滑直飞，无飞行时零开销 */
@Composable
fun Modifier.heroAnchor(key: String?, url: String?, title: String = "", border: Boolean = true): Modifier {
    if (key.isNullOrEmpty()) return this
    val token = remember { SharedHero.allocToken() }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var firstPlaced by remember(key) { mutableStateOf(false) }
    val curUrl by rememberUpdatedState(url)
    val curTitle by rememberUpdatedState(title)

    DisposableEffect(token, key) {
        SharedHero.register(HeroAnchorEntry(token, key, curUrl, curTitle, border) {
            coords?.takeIf { it.isAttached }?.boundsInRoot()
        })
        onDispose { SharedHero.unregister(token) }
    }
    SideEffect {
        SharedHero.register(HeroAnchorEntry(token, key, curUrl, curTitle, border) {
            coords?.takeIf { it.isAttached }?.boundsInRoot()
        })
    }

    return this
        .onPlaced { lc ->
            coords = lc
            if (!firstPlaced && lc.isAttached) {
                firstPlaced = true
                SharedHero.onAnchorFirstPlaced(token, key, curUrl, curTitle, border, lc.boundsInRoot())
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
