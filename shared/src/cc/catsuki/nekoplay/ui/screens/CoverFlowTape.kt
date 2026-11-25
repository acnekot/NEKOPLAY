package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 绘制磁带卷带齿轮轴（透明壳内可见的磁带盘 + 6 齿白轴） */
private fun DrawScope.drawTapeReel(cx: Float, cy: Float, outerR: Float, innerR: Float, angleDeg: Float, tapeRatio: Float) {
    val tapeR = innerR + (outerR - innerR) * tapeRatio
    drawCircle(Color(0x991A120E), tapeR, Offset(cx, cy))
    drawCircle(Color(0x33FFFFFF), tapeR, Offset(cx, cy), style = Stroke(1f))
    drawCircle(Color(0x40FFFFFF), innerR, Offset(cx, cy))
    drawCircle(Color(0xCC0D1016), innerR * 0.72f, Offset(cx, cy))
    drawCircle(Color(0x88FFFFFF), innerR, Offset(cx, cy), style = Stroke(1.3f))
    rotate(angleDeg, Offset(cx, cy)) {
        for (k in 0 until 6) {
            val a = k * PI / 3.0
            val p1 = Offset(cx + cos(a).toFloat() * innerR * 0.56f, cy + sin(a).toFloat() * innerR * 0.56f)
            val p2 = Offset(cx + cos(a).toFloat() * innerR * 0.92f, cy + sin(a).toFloat() * innerR * 0.92f)
            drawLine(Color(0xCCFFFFFF), p1, p2, strokeWidth = 1.8f)
        }
    }
}

/** 绘制透明磁带外壳机械细节（四角螺丝、底部梯形磁头桥、导带孔与外壳高光） */
private fun DrawScope.drawTapeShellFrame(w: Float, h: Float, sw: Float, tiltX: Float, tiltY: Float, cw: Float) {
    val screwR = (minOf(w, h) * 0.022f).coerceIn(2.2f, 4.5f)
    val pad = screwR * 2.4f
    listOf(Offset(pad, pad), Offset(w - pad, pad), Offset(pad, h - pad), Offset(w - pad, h - pad)).forEach { pt ->
        drawCircle(Color(0x55000000), screwR * 1.15f, pt)
        drawCircle(Color(0x88CFD8E4), screwR * 0.85f, pt)
        drawLine(Color(0x88202630), Offset(pt.x - screwR * 0.55f, pt.y), Offset(pt.x + screwR * 0.55f, pt.y), 1.1f)
        drawLine(Color(0x88202630), Offset(pt.x, pt.y - screwR * 0.55f), Offset(pt.x, pt.y + screwR * 0.55f), 1.1f)
    }
    // 底部经典梯形磁头保护桥
    val bwTop = w * 0.44f; val bwBot = w * 0.56f; val bh = h * 0.145f
    val bridge = Path().apply {
        moveTo((w - bwBot) / 2f, h); lineTo((w - bwTop) / 2f, h - bh)
        lineTo((w + bwTop) / 2f, h - bh); lineTo((w + bwBot) / 2f, h); close()
    }
    drawPath(bridge, Brush.verticalGradient(listOf(Color(0x46FFFFFF), Color(0x34161C26)), h - bh, h))
    drawPath(bridge, Color(0x66FFFFFF), style = Stroke(1.1f))
    val holeY = h - bh * 0.44f; val holeR = (bh * 0.18f).coerceIn(2.2f, 4.5f)
    listOf(w * 0.34f, w * 0.66f).forEach { hx ->
        drawCircle(Color(0x88080A0E), holeR, Offset(hx, holeY))
        drawCircle(Color(0x55FFFFFF), holeR, Offset(hx, holeY), style = Stroke(1f))
    }
    listOf(w * 0.43f, w * 0.57f).forEach { hx ->
        drawRoundRect(Color(0x78080A0E), Offset(hx - holeR * 0.8f, holeY - holeR * 0.7f), Size(holeR * 1.6f, holeR * 1.4f), CornerRadius(2f))
    }
    // 斜向透明塑料外壳反光
    val sh = (-tiltX * 0.28f + sw * 0.35f) * w
    drawRect(Brush.linearGradient(0f to Color.Transparent, 0.38f to Color.Transparent, 0.50f to Color.White.copy(alpha = 0.04f + 0.08f * cw + 0.14f * sw), 0.58f to Color.White.copy(alpha = 0.02f + 0.03f * cw + 0.05f * sw), 0.68f to Color.Transparent, 1f to Color.Transparent,
        start = Offset(sh - w * 0.12f, -tiltY * h * 0.2f), end = Offset(sh + w * 1.02f, h * 0.88f - tiltY * h * 0.2f)))
    drawRect(Color(0x66FFFFFF), size = Size(w, 1.8f)); drawRect(Color(0x48FFFFFF), Offset(w - 1.8f, 0f), Size(1.8f, h)); drawRect(Color(0x4C000000), Offset(0f, h - 2f), Size(w, 2f))
}

/** 透明亚克力复古磁带正面：左侧完整无遮挡 1:1 专辑封面贴纸 + 右侧透明双卷带齿轮视窗 + 四角螺丝与底部梯形桥 */
@Composable internal fun CassetteFrontLid(
    item: FlowItem, caseH: Dp, isCenter: Boolean,
    dist: () -> Float, tiltX: () -> Float, tiltY: () -> Float, lidSweep: () -> Float = { 0f },
) {
    val rim = (caseH * 0.065f).coerceIn(8.dp, 14.dp)
    val artDp = (caseH - rim * 2.5f).coerceAtLeast(64.dp)
    Box(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(Color(0x3CFFFFFF), Color(0x228496B0), Color(0x42141923))))
        .border(1.3.dp, Color(if (isCenter) 0x8EFFFFFF else 0x52FFFFFF))) {
        // 左侧完整 1:1 专辑封面（完全无遮挡，贴合磁带 J-Card 画幅）
        Box(Modifier.align(Alignment.TopStart).padding(start = rim, top = rim).border(1.dp, Color(0x55FFFFFF))) {
            Cover(item.cover, artDp, item.title, border = false, decodeSize = if (isCenter) 512.dp else 256.dp)
        }
        // 右侧透明卷带机械视窗 + 经典外壳细节
        Box(Modifier.matchParentSize().drawWithContent {
            val w = size.width; val h = size.height; val rm = rim.toPx(); val artPx = artDp.toPx()
            val d = dist(); val ad = abs(d); val cw = (1f - kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat()).coerceIn(0f, 1f)
            val winX = rm + artPx + rm * 0.65f; val winW = (w - winX - rm).coerceAtLeast(24f); val winH = artPx
            // 透明视窗凹槽
            drawRoundRect(Color(0x520B0F16), Offset(winX, rm), Size(winW, winH), CornerRadius(6f))
            drawRoundRect(Color(0x48FFFFFF), Offset(winX, rm), Size(winW, winH), CornerRadius(6f), style = Stroke(1.1f))
            val rCx = winX + winW * 0.5f; val outerR = minOf(winW * 0.42f, winH * 0.23f); val innerR = outerR * 0.52f
            val spin = d * 35f + lidSweep() * 90f
            drawTapeReel(rCx, rm + winH * 0.28f, outerR, innerR, spin, 0.78f)
            drawTapeReel(rCx, rm + winH * 0.72f, outerR, innerR, -spin * 0.85f, 0.46f)
            // 刻度线装饰
            for (k in 0..4) {
                val ly = rm + winH * (0.36f + k * 0.07f)
                drawLine(Color(0x44FFFFFF), Offset(winX + 4f, ly), Offset(winX + 9f, ly), 1f)
                drawLine(Color(0x44FFFFFF), Offset(winX + winW - 9f, ly), Offset(winX + winW - 4f, ly), 1f)
            }
            drawTapeShellFrame(w, h, lidSweep(), tiltX(), tiltY(), cw)
        })
        Box(Modifier.matchParentSize().graphicsLayer {
            val ad = abs(dist()); val s = kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat()
            alpha = (0.14f * s + 0.06f * (ad - 1f).coerceAtLeast(0f)).coerceAtMost(0.45f)
        }.background(Color.Black))
    }
}

/** 翻开后的磁带盒左盖内侧（完整 1:1 封面 + 复古磁带双轴透视窗口），点击可合上 */
@Composable internal fun CassetteInnerLid(item: FlowItem, caseH: Dp, lidSweep: () -> Float, openProg: () -> Float, onClose: () -> Unit) {
    val rim = (caseH * 0.065f).coerceIn(8.dp, 14.dp)
    val artDp = (caseH - rim * 2.5f).coerceAtLeast(64.dp)
    Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.press(onClick = onClose)
        .background(Brush.verticalGradient(listOf(Color(0x3CFFFFFF), Color(0x1E8496B0), Color(0x3E141923))))
        .border(1.3.dp, Color(0x8EFFFFFF))) {
        Box(Modifier.align(Alignment.TopStart).padding(start = rim, top = rim).border(1.dp, Color(0x55FFFFFF))) {
            Cover(item.cover, artDp, item.title, border = false, decodeSize = 512.dp)
        }
        Box(Modifier.matchParentSize().drawWithContent {
            val w = size.width; val h = size.height; val rm = rim.toPx(); val artPx = artDp.toPx()
            val winX = rm + artPx + rm * 0.65f; val winW = (w - winX - rm).coerceAtLeast(24f); val winH = artPx
            drawRoundRect(Color(0x560B0F16), Offset(winX, rm), Size(winW, winH), CornerRadius(6f))
            drawRoundRect(Color(0x52FFFFFF), Offset(winX, rm), Size(winW, winH), CornerRadius(6f), style = Stroke(1.1f))
            val rCx = winX + winW * 0.5f; val outerR = minOf(winW * 0.42f, winH * 0.23f); val innerR = outerR * 0.52f
            val spin = openProg() * 140f
            drawTapeReel(rCx, rm + winH * 0.28f, outerR, innerR, spin, 0.72f)
            drawTapeReel(rCx, rm + winH * 0.72f, outerR, innerR, spin * 1.15f, 0.52f)
            drawTapeShellFrame(w, h, lidSweep(), 0f, 0f, 1f)
        })
    }
}

/** 翻开后的磁带盒右侧 J-Card 完整曲目卡（带双卷带透视底纹 + SIDE A/B 风格列表） */
@Composable internal fun CassetteTrayTracklist(
    item: FlowItem, tracks: List<Track>?, loading: Boolean, compact: Boolean,
    openProg: () -> Float, onClose: () -> Unit, onOpenDetail: () -> Unit,
) {
    val p = c
    val playingId = Engine.state.collectAsState().value.current?.id
    Column(Modifier.fillMaxSize()
        .background(p.sheet.copy(alpha = 0.92f))
        .border(1.3.dp, Color(0x82FFFFFF))
        .drawBehind {
            val r = minOf(size.width, size.height) * 0.22f
            val cy = size.height * 0.56f; val spin = openProg() * 120f
            drawTapeReel(size.width * 0.30f, cy, r, r * 0.48f, spin, 0.75f)
            drawTapeReel(size.width * 0.70f, cy, r, r * 0.48f, spin * 1.1f, 0.48f)
            drawRect(Color(0x16FFFFFF), Offset(size.width * 0.22f, cy - r * 0.55f), Size(size.width * 0.56f, r * 1.1f), style = Stroke(1f))
        }
        .padding(if (compact) 9.dp else 13.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 6.dp).background(p.p.copy(alpha = 0.16f)).border(1.dp, p.p.copy(alpha = 0.5f)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                T("SIDE A", 9, p.p, 800, mono = true)
            }
            Column(Modifier.weight(1f)) {
                T(item.title, if (compact) 12 else 14, p.k, 800, lines = 1)
                T(item.sub + (tracks?.let { " · ${it.size} 首" } ?: ""), if (compact) 9 else 10, p.s, lines = 1)
            }
            if (!tracks.isNullOrEmpty()) Box(Modifier.padding(end = 5.dp).press { Engine.playList(tracks, 0) }.background(p.p).padding(horizontal = 8.dp, vertical = 4.dp)) { T("播放", 10, Color.White, 700) }
            if (item.kind != "tracks") Box(Modifier.padding(end = 5.dp).press(onClick = onOpenDetail).nbf(1.dp, p.l2).padding(horizontal = 7.dp, vertical = 4.dp)) { T("详情", 10, p.k, 600) }
            Box(Modifier.press(onClick = onClose).nbf(1.dp, p.l2).padding(horizontal = 7.dp, vertical = 4.dp)) { T("合上", 10, p.k, 700) }
        }
        Spacer(Modifier.height(if (compact) 5.dp else 7.dp)); Box(Modifier.fillMaxWidth().height(1.dp).background(p.l1))
        when {
            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { T("LOADING…", 11, p.s, mono = true) }
            tracks.isNullOrEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { T("暂无曲目信息", 12, p.s) }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 3.dp)) {
                itemsIndexed(tracks, key = { idx, t -> "${t.id}_$idx" }) { idx, t ->
                    val on = playingId == t.id
                    Row(Modifier.fillMaxWidth().press { Engine.playList(tracks, idx) }.background(if (on) p.p.copy(alpha = 0.14f) else Color.Transparent).padding(horizontal = 5.dp, vertical = if (compact) 4.dp else 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(two(idx + 1), 10, if (on) p.p else p.s, 600, mono = true, modifier = Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) {
                            T(t.title, if (compact) 11 else 12, if (on) p.p else p.k, if (on) 700 else 600, lines = 1)
                            if (t.artist.isNotBlank()) T(t.artist, 9, p.s, lines = 1)
                        }
                        if (t.durationMs > 0) T(fmt(t.durationMs), 10, p.s, mono = true, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}
