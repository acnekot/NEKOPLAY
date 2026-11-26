package cc.catsuki.nekoplay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable fun TrackRow(t: Track, current: Boolean, label: String? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val p = c
    val fg = if (current) p.p else p.k
    val curAnim = remember { Animatable(if (current) 1f else 0f) }
    LaunchedEffect(current) {
        val target = if (current) 1f else 0f
        if (curAnim.value != target) curAnim.animateTo(target, Motion.standard())
    }
    val desktopHover = Plat.isDesktop && !AnimStyle.off && (MotionEffects.hoverTint || MotionEffects.hoverShiftDp > 0.1f)
    val src = remember { MutableInteractionSource() }
    val hovAnim = if (desktopHover) remember(src) { Animatable(0f) } else null
    if (desktopHover && hovAnim != null) {
        LaunchedEffect(src) {
            var animJob: kotlinx.coroutines.Job? = null
            src.interactions.collect { ev ->
                when (ev) {
                    is HoverInteraction.Enter -> { animJob?.cancel(); animJob = launch { hovAnim.animateTo(1f, Motion.quick()) } }
                    is HoverInteraction.Exit -> { animJob?.cancel(); animJob = launch { hovAnim.animateTo(0f, Motion.standard()) } }
                }
            }
        }
    }
    val shiftDp = if (desktopHover && MotionEffects.hoverShiftDp > 0.1f) MotionEffects.hoverShiftDp * 0.55f else 0f
    val showTint = desktopHover && MotionEffects.hoverTint
    Row(Modifier.fillMaxWidth().height(64.dp).cascadeEnter()
        .then(if (hovAnim != null && shiftDp > 0f) Modifier.graphicsLayer { val h = hovAnim.value; if (h > 0.001f) translationX = (h * shiftDp).dp.toPx() } else Modifier)
        .then(if (desktopHover) Modifier.hoverable(src) else Modifier)
        .press(onLong = onLong, interactionSource = src, onClick = onClick)
        .drawBehind {
            val ext = 10.dp.toPx()
            val ca = curAnim.value
            val ha = if (showTint && !current && hovAnim != null) hovAnim.value else 0f
            if (ha > 0.01f) drawRect(p.k.copy(alpha = .045f * ha), Offset(-ext, 0f), Size(size.width + ext * 2, size.height))
            if (ca > 0.01f) {
                drawRect(p.hl.copy(alpha = p.hl.alpha * ca), Offset(-ext, 0f), Size(size.width + ext * 2, size.height))
                val barH = size.height * ca
                drawRect(p.p.copy(alpha = ca), Offset(-ext, (size.height - barH) / 2f), Size(3.dp.toPx(), barH))
            }
            if (!Look.flat) drawRect(p.l1, Offset(if (current) -ext else 0f, size.height - 1.dp.toPx()), Size(size.width + if (current) ext * 2 else 0f, 1.dp.toPx()))
        }, verticalAlignment = Alignment.CenterVertically) {
        Cover(t.cover, 44.dp, t.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(t.title, 16, fg, if (current) 700 else 600, lh = 1.4f, modifier = Modifier.weight(1f, fill = false))
                if (t.vip) { Spacer(Modifier.width(6.dp)); VipTag() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceTag(t)
                T(t.artist, 13, p.s, lh = 1.4f)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            T(if (t.durationMs > 0) fmt(t.durationMs) else "--:--", 12, fg, mono = true)
            TrackDownloadLabel(t.id, label)
        }
    }
}

@Composable private fun TrackDownloadLabel(id: String, label: String?) {
    val lab = label ?: remember(id) { Downloads.labelFlow(id) }.collectAsState(Downloads.labelNow(id)).value
    if (lab != null) T(trKnown(lab), 11, c.s, mono = true, ls = .06f)
}

/** 进度条：热区 16，线 4（拖动时 6），拖动时出现方形滑块；全程纯绘制层驱动，播放中 0 重组 */
@Composable fun ScrubBar(durMs: Long, hit: Dp = 16.dp, interactive: Boolean = true, onScrub: (Float?) -> Unit = {}) {
    val dur = durMs.coerceAtLeast(1L)
    val scope = rememberCoroutineScope()
    val dragState = remember(dur) { mutableStateOf<Float?>(null) }
    val fill = remember(dur) { Animatable((Engine.position.value.toFloat() / dur).coerceIn(0f, 1f)) }
    val bufFrac = remember(dur) { mutableFloatStateOf((Engine.buffered.value.toFloat() / dur).coerceIn(0f, 1f)) }
    val dragAnim = remember { Animatable(0f) }
    val curScrub by rememberUpdatedState(onScrub)
    LaunchedEffect(dur) {
        Engine.position.collectLatest { pos ->
            if (dragState.value != null) return@collectLatest
            val target = (pos.toFloat() / dur).coerceIn(0f, 1f)
            if (AnimStyle.off || kotlin.math.abs(target - fill.value) > 0.08f) fill.snapTo(target)
            else fill.animateTo(target, tween(if (Engine.fastPos > 0) 70 else 240, easing = LinearEasing))
        }
    }
    if (interactive && UiPref.showBuffer) {
        LaunchedEffect(dur) {
            Engine.buffered.collect { b -> bufFrac.floatValue = (b.toFloat() / dur).coerceIn(0f, 1f) }
        }
    }
    val p = c
    Box(Modifier.fillMaxWidth().height(hit).then(if (!interactive) Modifier else Modifier.pointerInput(dur) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull() ?: continue
                val f = (ch.position.x / size.width).coerceIn(0f, 1f)
                if (ch.pressed) {
                    val first = dragState.value == null
                    dragState.value = f; curScrub(f); ch.consume()
                    scope.launch { fill.snapTo(f) }
                    if (first) scope.launch { dragAnim.animateTo(1f, tween(130, easing = LinearEasing)) }
                } else if (dragState.value != null) {
                    val d = dragState.value!!
                    dragState.value = null; curScrub(null)
                    Engine.seek((d * dur).toLong())
                    scope.launch { dragAnim.animateTo(0f, tween(150, easing = LinearEasing)) }
                }
            }
        }
    }).drawBehind {
        val y = size.height / 2f
        val da = dragAnim.value
        val hp = (4f + 2f * da).dp.toPx()
        val fv = fill.value
        drawRect(p.l3, Offset(0f, y - hp / 2f), Size(size.width, hp))
        if (UiPref.showBuffer && interactive) drawRect(p.k.copy(alpha = .22f), Offset(0f, y - hp / 2f), Size(size.width * bufFrac.floatValue, hp))
        drawRect(p.p, Offset(0f, y - hp / 2f), Size(size.width * fv, hp))
        if (da > 0.005f) {
            val s = 12.dp.toPx() * (0.4f + 0.6f * da)
            val thumbAt = dragState.value ?: fv
            drawRect(p.p.copy(alpha = da), Offset(size.width * thumbAt - s / 2f, y - s / 2f), Size(s, s))
        }
    })
}

@Composable fun TimeRow(durMs: Long, scrub: Float? = null, size: Int = 12) {
    val sec by remember { Engine.position.map { it / 1000L }.distinctUntilChanged() }.collectAsState(Engine.position.value / 1000L)
    Row(Modifier.fillMaxWidth()) {
        T(fmt(if (scrub != null) (scrub * durMs).toLong() else sec * 1000L), size, c.p, mono = true, modifier = Modifier.weight(1f))
        T(fmt(durMs), size, c.s, mono = true)
    }
}

/** 封面图片按显示尺寸取合适缩略图（兼容各图床尺寸参数） */
fun img(url: String?, px: Int): String? {
    if (url == null) return null
    if (url.startsWith("http://") && !url.contains("music.126.net")) return img("https://" + url.removePrefix("http://"), px)
    val b = when { px <= 160 -> 160; px <= 256 -> 256; px <= 512 -> 512; else -> 768 }
    val base = url.substringBefore('?')
    if (base.contains("kugou.com") && base.contains("/480/")) { val q = when { px <= 160 -> 150; px <= 256 -> 240; else -> 480 }; return base.replace("/480/", "/$q/") }
    if (base.contains("gtimg.cn") && base.contains("R500x500")) { val q = when { px <= 160 -> 150; px <= 300 -> 300; px <= 512 -> 500; else -> 800 }; return base.replace("R500x500", "R${q}x$q") }
    if (!base.contains("music.126.net")) return url
    return if (base.contains("/obj/")) "$base?imageView=1&thumbnail=${b}x$b&type=webp" else "$base?param=${b}y$b"
}

/** 定位到正在播放：当前曲目不在可见视口内时右下角显示定位按钮 */
@Composable fun BoxScope.LocateBtn(ls: LazyListState, target: Int, bottom: Dp = 16.dp) {
    val scope = rememberCoroutineScope()
    val show by remember(target) { derivedStateOf {
        target >= 0 && ls.layoutInfo.visibleItemsInfo.let { v -> v.isEmpty() || v.none { it.index == target } || v.first { it.index == target }.let { it.offset < 0 || it.offset + it.size > ls.layoutInfo.viewportEndOffset } }
    } }
    AnimatedVisibility(show, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottom),
        enter = fadeIn(Motion.quick()) + scaleIn(Motion.quick(), if (MotionEffects.pageScale) .8f else 1f),
        exit = fadeOut(Motion.quick()) + scaleOut(Motion.quick(), if (MotionEffects.pageScale) .8f else 1f)) {
        Box(Modifier.size(44.dp).press(.92f, true) {
            scope.launch {
                val off = -(ls.layoutInfo.viewportEndOffset / 3)
                // 远距离跳转直接落位到目标视口，避免先跳顶再回滑把上下行拆成两股同时入场
                if (kotlin.math.abs(ls.firstVisibleItemIndex - target) > 25) ls.scrollToItem(target, off)
                else ls.animateScrollToItem(target, off)
            }
        }.background(c.sheet).border(if (Look.flat) 0.dp else 1.dp, c.k), contentAlignment = Alignment.Center) { NIcon(NIcons.locate, 20.dp, c.p) }
    }
}
