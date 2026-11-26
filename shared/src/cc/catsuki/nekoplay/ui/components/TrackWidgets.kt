package cc.catsuki.nekoplay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable fun TrackRow(t: Track, current: Boolean, label: String? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val p = c
    val fg = if (current) p.p else p.k
    val hSrc = remember { MutableInteractionSource() }
    val hovered by hSrc.collectIsHoveredAsState()
    val curAnim = animateFloatAsState(if (current) 1f else 0f, Motion.standard(), label = "trc")
    val hovAnim = animateFloatAsState(if (hovered && !current && MotionEffects.hoverTint) 1f else 0f, Motion.quick(), label = "trh")
    val hovShift = if (MotionEffects.hoverShiftDp > 0.1f && !AnimStyle.off) animateFloatAsState(if (hovered) MotionEffects.hoverShiftDp * 0.55f else 0f, Motion.standard(), label = "trs") else null
    Row(Modifier.fillMaxWidth().height(64.dp).cascadeEnter().then(if (hovShift != null) Modifier.graphicsLayer { translationX = hovShift.value.dp.toPx() } else Modifier).hoverable(hSrc).press(onLong = onLong, onClick = onClick).drawBehind {
        val ext = 10.dp.toPx()
        val ca = curAnim.value
        val ha = hovAnim.value
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
    val lab = if (label != null) label else {
        val act by Downloads.active.collectAsState()
        val dls by Downloads.items.collectAsState()
        act[id]?.let { if (it < 0) "等待下载" else "%.0f%%".format(it * 100) } ?: if (dls.any { it.track.id == id }) "已下载" else null
    }
    if (lab != null) T(lab, 11, c.s, mono = true, ls = .06f)
}

/** 进度条：热区 16，线 4（拖动时 6），拖动时出现方形滑块 */
@Composable fun ScrubBar(durMs: Long, hit: Dp = 16.dp, interactive: Boolean = true, onScrub: (Float?) -> Unit = {}) {
    val pos by Engine.position.collectAsState()
    val buf by Engine.buffered.collectAsState()
    val dur = durMs.coerceAtLeast(1)
    var drag by remember(dur) { mutableStateOf<Float?>(null) }
    val curScrub by rememberUpdatedState(onScrub)
    val frac = drag ?: (pos.toFloat() / dur).coerceIn(0f, 1f)
    val fill = animateFloatAsState(frac, tween(150, easing = LinearEasing), label = "fill")
    val thumbAt = frac
    val h = animateDpAsState(if (drag != null) 6.dp else 4.dp, tween(150, easing = LinearEasing), label = "h")
    val th = animateFloatAsState(if (drag != null) 1f else 0f, tween(150, easing = LinearEasing), label = "t")
    val ts = animateFloatAsState(if (drag != null) 1f else .4f, tween(150, easing = LinearEasing), label = "ts")
    val p = c
    Box(Modifier.fillMaxWidth().height(hit).then(if (!interactive) Modifier else Modifier.pointerInput(dur) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull() ?: continue
                val f = (ch.position.x / size.width).coerceIn(0f, 1f)
                if (ch.pressed) { drag = f; curScrub(f); ch.consume() }
                else if (drag != null) { Engine.seek((drag!! * dur).toLong()); drag = null; curScrub(null) }
            }
        }
    }).drawBehind {
        val y = size.height / 2; val hp = h.value.toPx()
        drawRect(p.l3, Offset(0f, y - hp / 2), Size(size.width, hp))
        if (UiPref.showBuffer && interactive) drawRect(p.k.copy(alpha = .22f), Offset(0f, y - hp / 2), Size(size.width * (buf.toFloat() / dur).coerceIn(0f, 1f), hp))
        drawRect(p.p, Offset(0f, y - hp / 2), Size(size.width * fill.value, hp))
        val thv = th.value
        if (thv > 0f) { val s = 12.dp.toPx() * ts.value; drawRect(p.p.copy(alpha = thv), Offset(size.width * thumbAt - s / 2, y - s / 2), Size(s, s)) }
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
            scope.launch { ls.animateScrollToItem(target, -(ls.layoutInfo.viewportEndOffset / 3)) }
        }.background(c.sheet).border(if (Look.flat) 0.dp else 1.dp, c.k), contentAlignment = Alignment.Center) { NIcon(NIcons.locate, 20.dp, c.p) }
    }
}
