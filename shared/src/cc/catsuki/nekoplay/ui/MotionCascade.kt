package cc.catsuki.nekoplay

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

internal class CascadeTicket {
    var x: Float = 0f
    var y: Float = 0f
    var placed: Boolean = false
    val placedSignal = CompletableDeferred<Unit>()
    val delayMs = CompletableDeferred<Long>()
}

/**
 * 列表逐项瀑布错峰入场协调器（严格按屏幕视觉顺序从顶部依次展开）：
 * - 等待首次 onPlaced 落位到真实屏幕坐标后才参与排波，彻底屏蔽 LazyColumn 离屏 prefetch 抢跑；
 * - 同帧落位的全部列表项先按屏幕 Y 坐标升序（同行再按 X 升序）排序，保证始终从顶部第一项依次按顺序滑入；
 * - 连续向下滚动时单调衔接尾项并封顶排队时长，既不抢跑也不留大段空白。
 */
object ListStagger {
    private val pending = ArrayList<CascadeTicket>()
    private var lastTailStartMs = 0L

    internal fun register(t: CascadeTicket, x: Float, y: Float) {
        if (t.placed) return
        t.x = x; t.y = y; t.placed = true
        pending.add(t)
        t.placedSignal.complete(Unit)
    }

    internal fun cancel(t: CascadeTicket) {
        pending.remove(t)
        if (!t.delayMs.isCompleted) t.delayMs.complete(0L)
    }

    internal fun flushBatch() {
        if (pending.isEmpty()) return
        val batch = ArrayList(pending)
        pending.clear()
        val step = (MotionEffects.listStaggerMs / Anim.f).toLong().coerceAtLeast(0L)
        if (step <= 0L || AnimStyle.off) {
            batch.forEach { if (!it.delayMs.isCompleted) it.delayMs.complete(0L) }
            return
        }
        val inh = OriginBus.activeInherit()
        if (inh != null) {
            batch.sortBy { t -> val dx = t.x - inh.origin.x; val dy = t.y - inh.origin.y; dx * dx + dy * dy }
        } else {
            batch.sortWith(compareBy<CascadeTicket> { kotlin.math.round(it.y / 8f) }.thenBy { it.x })
        }
        val now = System.currentTimeMillis()
        val minY = batch.first().y
        val maxQueueLag = (step * 4L).coerceAtMost(140L)
        val continueDown = batch.size <= 3 && minY > 400f && lastTailStartMs > now
        val baseStart = if (continueDown) minOf(lastTailStartMs + step, now + maxQueueLag) else now
        val maxSpan = step * 12L
        var tail = baseStart
        batch.forEachIndexed { idx, t ->
            val startAt = (baseStart + idx * step).coerceAtMost(now + maxSpan)
            tail = startAt
            val d = (startAt - now).coerceAtLeast(0L)
            if (!t.delayMs.isCompleted) t.delayMs.complete(d)
        }
        lastTailStartMs = tail
    }
}

/** 外置动效插件驱动的列表逐项瀑布入场（未安装动效插件时直接返回原 Modifier，零开销；启用时严格按视觉从顶到底依次滑入） */
@Composable fun Modifier.cascadeEnter(): Modifier {
    if (AnimStyle.off || AnimStyle.pluginProfile == null) return this
    val slideDp = MotionEffects.listSlideDp; val stagger = MotionEffects.listStaggerMs
    if (stagger <= 0 && slideDp < 1f) return this
    val ticket = remember { CascadeTicket() }
    val prog = remember { Animatable(0f) }
    DisposableEffect(ticket) { onDispose { ListStagger.cancel(ticket) } }
    LaunchedEffect(ticket) {
        ticket.placedSignal.await()
        withFrameMillis { }
        ListStagger.flushBatch()
        val d = ticket.delayMs.await()
        if (d > 0L) delay(d)
        prog.animateTo(1f, Motion.page())
    }
    val inh = remember { OriginBus.activeInherit() }
    val vxDp = inh?.vector?.x ?: slideDp
    val vyDp = inh?.vector?.y ?: 0f
    return this.onPlaced { coords ->
        if (!ticket.placed) {
            val pos = coords.positionInRoot()
            ListStagger.register(ticket, pos.x - OriginBus.hostOrigin.x, pos.y - OriginBus.hostOrigin.y)
        }
    }.graphicsLayer {
        val p = prog.value; val inv = 1f - p
        alpha = p.coerceIn(0f, 1f)
        translationX = inv * vxDp.dp.toPx()
        if (vyDp != 0f) translationY = inv * vyDp.dp.toPx()
        if (inh != null && inv > 0.001f) { val s = 1f - 0.04f * inv; scaleX = s; scaleY = s }
    }
}
