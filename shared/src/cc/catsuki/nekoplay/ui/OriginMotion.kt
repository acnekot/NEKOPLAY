package cc.catsuki.nekoplay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign

/** 页面栈中每一层记录的来源锚点：用于实现多级跳转的「从哪来、回哪去」与方向继承 */
data class OriginFrame(
    val pageKey: Any,
    val rect: Rect,
)

/** 点击与回落触发的空间波纹事件：驱动邻近同级列表项物理避让与柔和回弹 */
data class OriginWave(
    val cx: Float,
    val cy: Float,
    val seq: Int,
    val strength: Float = 1f,
)

/** 跨页联动继承载荷：将点击源位置与展开动量方向传递给目标页内部元素 */
data class OriginInheritState(
    val origin: Offset,
    val vector: Offset,
    val seq: Int,
    val atMs: Long,
)

/**
 * 源点空间动效总线（由 @type motion 插件通过 effects.originExpand / originRipple / originInherit 按需启用）：
 * - 平时未启用相关动效插件时零采样、零协程、零内存占用；
 * - 启用后自动捕获每次点击控件的屏幕坐标，按导航栈记忆每一层「从哪来回哪去」，并向目标页传递动量与点击波纹。
 */
object OriginBus {
    private var waveSeq = 1
    private var inheritSeq = 1
    internal var hostOrigin by mutableStateOf(Offset.Zero)
    internal var hostSize by mutableStateOf(Size(1f, 1f))

    /** 最近一次点击控件在 Root 坐标系下的位置（相对于 hostOrigin） */
    var lastTapRect by mutableStateOf<Rect?>(null); private set
    var lastTapAtMs: Long = 0L; private set

    /** 与 nav.stack 同步的多级页面源点栈：进栈压入起点、退栈弹出并作为收起目标 */
    private val pageStack = ArrayList<OriginFrame>()
    /** 最近一次弹出或关闭时用于回缩的目标位置 */
    var closingPageRect by mutableStateOf<Rect?>(null); private set
    /** 进入播放页时的来源入口位置（关闭播放页时精准缩回） */
    var playerOriginRect by mutableStateOf<Rect?>(null); private set
    /** 当前触发中的邻近波纹 */
    var wave by mutableStateOf<OriginWave?>(null); private set
    /** 当前生效的跨页联动继承态（新页入场短窗口内消费） */
    var inherit by mutableStateOf<OriginInheritState?>(null); private set

    fun updateHost(lc: LayoutCoordinates) {
        if (!lc.isAttached) return
        hostOrigin = lc.positionInRoot()
        hostSize = Size(lc.size.width.toFloat().coerceAtLeast(1f), lc.size.height.toFloat().coerceAtLeast(1f))
    }

    private fun toHostLocal(rootRect: Rect): Rect = Rect(
        rootRect.left - hostOrigin.x,
        rootRect.top - hostOrigin.y,
        rootRect.right - hostOrigin.x,
        rootRect.bottom - hostOrigin.y,
    )

    /** 由 Modifier.press 在点击瞬间自动记录被点控件的位置，并触发邻近波纹联动 */
    fun recordTap(lc: LayoutCoordinates?) {
        if (!MotionEffects.originActive) return
        val r = lc?.takeIf { it.isAttached }?.boundsInRoot()?.takeIf { it.width > 2f && it.height > 2f } ?: return
        recordTapRect(toHostLocal(r))
    }

    internal fun recordTapRect(localRect: Rect) {
        lastTapRect = localRect
        lastTapAtMs = System.currentTimeMillis()
        if (MotionEffects.originRipple) emitWave(localRect, 1f)
    }

    fun emitWave(rect: Rect, strength: Float = 1f) {
        if (!MotionEffects.originRipple || AnimStyle.off) return
        wave = OriginWave(rect.center.x, rect.center.y, waveSeq++, strength)
    }

    private fun freshTapRect(): Rect? {
        val r = lastTapRect ?: return null
        return if (System.currentTimeMillis() - lastTapAtMs <= 500L) r else null
    }

    /** 同步 nav.stack：压栈记录来源并向新页传递联动继承向量；弹栈保留回缩目标并在原位回响波纹 */
    fun syncStack(stack: List<Page>) {
        if (!MotionEffects.originActive) {
            if (pageStack.isNotEmpty()) pageStack.clear()
            closingPageRect = null
            inherit = null
            return
        }
        val oldSize = pageStack.size
        val newSize = stack.size
        when {
            newSize > oldSize -> {
                for (i in oldSize until newSize) {
                    val src = freshTapRect() ?: defaultCenterRect()
                    pageStack.add(OriginFrame(stack[i], src))
                    closingPageRect = src
                    armInherit(src)
                }
            }
            newSize < oldSize -> {
                var lastPopped: Rect? = null
                while (pageStack.size > newSize) {
                    lastPopped = pageStack.removeAt(pageStack.lastIndex).rect
                }
                if (lastPopped != null) {
                    closingPageRect = lastPopped
                    // 从哪来回哪去：缩回原入口后在原位触发柔和的落位回响波纹，完成动量继承闭环
                    emitWave(lastPopped, 0.72f)
                }
            }
            newSize > 0 && pageStack.lastOrNull()?.pageKey != stack.last() -> {
                // 同深度替换（例如侧边栏直接切另一个歌单）
                val src = freshTapRect() ?: pageStack.lastOrNull()?.rect ?: defaultCenterRect()
                pageStack[pageStack.lastIndex] = OriginFrame(stack.last(), src)
                closingPageRect = src
                armInherit(src)
            }
        }
    }

    fun onPlayerToggle(opening: Boolean) {
        if (!MotionEffects.originActive) return
        if (opening) {
            val src = freshTapRect() ?: Rect(0f, hostSize.height - 84f, hostSize.width, hostSize.height)
            playerOriginRect = src
            armInherit(src)
        } else {
            playerOriginRect?.let { emitWave(it, 0.78f) }
        }
    }

    fun activePageOrigin(): Rect? = pageStack.lastOrNull()?.rect ?: closingPageRect
    fun activePlayerOrigin(): Rect? = playerOriginRect

    /** 计算展开动量继承向量（从点击点指向屏幕中心方向，让新页内容顺着展开惯性自然舒展） */
    private fun armInherit(src: Rect) {
        if (!MotionEffects.originInherit) return
        val cx = hostSize.width * 0.5f
        val cy = hostSize.height * 0.5f
        val dx = src.center.x - cx
        val dy = src.center.y - cy
        val len = hypot(dx, dy).coerceAtLeast(1f)
        val slide = (MotionEffects.listSlideDp.takeIf { it >= 6f } ?: 32f)
        // 源点在左 → 新内容从左往右承接；源点在下 → 新内容从下往上承接
        val vx = (dx / len) * slide * 0.9f
        val vy = (dy / len) * slide * 1.05f
        inherit = OriginInheritState(src.center, Offset(vx, vy), inheritSeq++, System.currentTimeMillis())
    }

    fun activeInherit(): OriginInheritState? {
        if (!MotionEffects.originInherit || AnimStyle.off) return null
        val cur = inherit ?: return null
        return if (System.currentTimeMillis() - cur.atMs <= 650L) cur else null
    }

    /** 按源点矩形计算归一化 TransformOrigin（用于多级页「从哪来回哪去」：前进用进栈起点、返回用弹栈原起点，不受返回按钮位置干扰） */
    fun originTransformOrigin(useClosing: Boolean = false, fallback: TransformOrigin = TransformOrigin.Center): TransformOrigin {
        if (!MotionEffects.originExpand) return fallback
        val r = (if (useClosing) closingPageRect else activePageOrigin()) ?: freshTapRect() ?: return fallback
        val w = hostSize.width.coerceAtLeast(1f)
        val h = hostSize.height.coerceAtLeast(1f)
        return TransformOrigin((r.center.x / w).coerceIn(0.08f, 0.92f), (r.center.y / h).coerceIn(0.08f, 0.92f))
    }

    /** 根据最近一次点击位置计算弹窗/菜单展开的归一化 TransformOrigin */
    fun tapTransformOrigin(fallback: TransformOrigin = TransformOrigin.Center): TransformOrigin {
        if (!MotionEffects.originExpand) return fallback
        val r = freshTapRect() ?: return fallback
        val w = hostSize.width.coerceAtLeast(1f)
        val h = hostSize.height.coerceAtLeast(1f)
        return TransformOrigin((r.center.x / w).coerceIn(0.08f, 0.92f), (r.center.y / h).coerceIn(0.08f, 0.92f))
    }

    private fun defaultCenterRect(): Rect = Rect(
        hostSize.width * 0.25f, hostSize.height * 0.35f,
        hostSize.width * 0.75f, hostSize.height * 0.65f,
    )

    internal fun resetForTest() {
        pageStack.clear()
        lastTapRect = null
        lastTapAtMs = 0L
        closingPageRect = null
        playerOriginRect = null
        wave = null
        inherit = null
        hostOrigin = Offset.Zero
        hostSize = Size(400f, 800f)
    }
}

/** 随进度动态插值的圆角矩形裁剪（零重组，只在 Draw/RenderNode 阶段求值） */
private class DynamicOriginShape(
    private val progress: () -> Float,
    private val origin: () -> Rect?,
    private val cornerDp: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = progress().coerceIn(0f, 1f)
        val o = origin()
        if (o == null || p >= 0.999f) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        val l = o.left * (1f - p)
        val t = o.top * (1f - p)
        val r = o.right + (size.width - o.right) * p
        val b = o.bottom + (size.height - o.bottom) * p
        val rad = with(density) { (cornerDp * (1f - p)).dp.toPx() }.coerceAtLeast(0f)
        return Outline.Rounded(RoundRect(l, t, r.coerceAtLeast(l + 1f), b.coerceAtLeast(t + 1f), CornerRadius(rad, rad)))
    }
}

/**
 * 源点容器展开与原路回缩修饰符（纯 GPU graphicsLayer，120fps / 0 重组）：
 * - 裁剪边界从被点控件的精确 Rect 平滑撑开至全容器，圆角同步从 cornerDp 展平至 0；
 * - 内部内容保持等比微缩放（绝不拉扁文字）并跟随源点中心位移至目标居中，关闭时严格原路缩回源点。
 */
@Composable
fun Modifier.originContainerExpand(
    progress: () -> Float,
    origin: () -> Rect?,
    cornerDp: Float = MotionEffects.originCornerDp,
): Modifier {
    val shape = remember(cornerDp) { DynamicOriginShape(progress, origin, cornerDp) }
    return this.graphicsLayer {
        val p = progress().coerceIn(0f, 1f)
        val o = origin()
        if (o == null || p >= 0.995f) {
            alpha = if (p <= 0.001f) 0f else 1f
            clip = false
            return@graphicsLayer
        }
        val curW = (o.width + (size.width - o.width) * p).coerceAtLeast(1f)
        val curH = (o.height + (size.height - o.height) * p).coerceAtLeast(1f)
        val minScale = max(o.width / size.width.coerceAtLeast(1f), o.height / size.height.coerceAtLeast(1f)).coerceIn(0.68f, 0.94f)
        val sc = minScale + (1f - minScale) * p
        val targetCx = o.center.x + (size.width * 0.5f - o.center.x) * p
        val targetCy = o.center.y + (size.height * 0.5f - o.center.y) * p
        clip = p < 0.96f
        this.shape = shape
        scaleX = sc
        scaleY = sc
        translationX = (targetCx - size.width * 0.5f) * (1f - p) * 0.42f
        translationY = (targetCy - size.height * 0.5f) * (1f - p) * 0.42f
        // 仅在最初/末尾 12% 过渡淡入淡出，其余 88% 保持 alpha=1f 避免整屏离屏 saveLayer 开销
        alpha = if (p < 0.12f) (p / 0.12f).coerceIn(0f, 1f) * (if (curW > 2f && curH > 2f) 1f else 0f) else 1f
    }
}

/**
 * 列表/同级控件点击波纹联动修饰符（未启用 originRipple 插件时零开销直返）：
 * 点击某一行或退回原位时，被点项轻微聚焦，上下邻近项按距离衰减做柔和的物理避让推开与弹簧回弹，全程 0 重组。
 */
@Composable
fun Modifier.originRippleItem(): Modifier {
    if (!MotionEffects.originRipple || AnimStyle.off) return this
    var myCenterY by remember { mutableFloatStateOf(-1f) }
    val nudgeY = remember { Animatable(0f) }
    val wave = OriginBus.wave
    LaunchedEffect(wave?.seq) {
        val w = wave ?: return@LaunchedEffect
        val cy = myCenterY
        if (cy < 0f) return@LaunchedEffect
        val dy = cy - w.cy
        val ad = abs(dy)
        if (ad < 26f || ad > 460f) return@LaunchedEffect
        val ratio = 1f - ad / 460f
        val weight = ratio * ratio * w.strength
        val push = sign(dy) * 11f * weight
        val dMs = (ad / 16f).toLong().coerceAtMost(65L)
        if (dMs > 0L) delay(dMs)
        launch {
            nudgeY.animateTo(push, Motion.quick())
            nudgeY.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Anim.stiff(380f)))
        }
    }
    return this
        .onPlaced { lc -> if (lc.isAttached) myCenterY = lc.boundsInRoot().center.y - OriginBus.hostOrigin.y }
        .graphicsLayer {
            val ny = nudgeY.value
            if (abs(ny) > 0.05f) translationY = ny.dp.toPx()
        }
}
