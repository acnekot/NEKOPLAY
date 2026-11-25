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

/** 绘制磁带卷带齿轮轴（磁带盘 + 6 齿白轴） */
private fun DrawScope.drawTapeReel(cx: Float, cy: Float, outerR: Float, innerR: Float, angleDeg: Float, tapeRatio: Float) {
    val tapeR = innerR + (outerR - innerR) * tapeRatio
    drawCircle(Color(0xB818100C), tapeR, Offset(cx, cy))
    drawCircle(Color(0x36FFFFFF), tapeR, Offset(cx, cy), style = Stroke(0.9f))
    drawCircle(Color(0x46FFFFFF), innerR, Offset(cx, cy))
    drawCircle(Color(0xD80A0D12), innerR * 0.70f, Offset(cx, cy))
    drawCircle(Color(0x99FFFFFF), innerR, Offset(cx, cy), style = Stroke(1.2f))
    rotate(angleDeg, Offset(cx, cy)) {
        for (k in 0 until 6) {
            val a = k * PI / 3.0
            val p1 = Offset(cx + cos(a).toFloat() * innerR * 0.52f, cy + sin(a).toFloat() * innerR * 0.52f)
            val p2 = Offset(cx + cos(a).toFloat() * innerR * 0.94f, cy + sin(a).toFloat() * innerR * 0.94f)
            drawLine(Color(0xDDFFFFFF), p1, p2, strokeWidth = 1.6f)
        }
    }
}

/** 绘制真实横向实体磁带本体（左右对称双 6 齿卷带轴 + 中央透明带窗 + 四角螺丝 + 底部梯形磁头桥） */
private fun DrawScope.drawHorizontalCassette(x: Float, y: Float, w: Float, h: Float, spinDeg: Float, accent: Color) {
    val cr = CornerRadius(6f)
    drawRoundRect(Brush.verticalGradient(listOf(Color(0x44FFFFFF), Color(0x28546478), Color(0x4E121720)), y, y + h), Offset(x, y), Size(w, h), cr)
    drawRoundRect(Color(0x88FFFFFF), Offset(x, y), Size(w, h), cr, style = Stroke(1.2f))
    // 顶部彩色贴纸条
    val lblH = h * 0.24f
    drawRoundRect(accent.copy(alpha = 0.28f), Offset(x + w * 0.07f, y + h * 0.08f), Size(w * 0.86f, lblH), CornerRadius(3f))
    drawLine(Color(0x55FFFFFF), Offset(x + w * 0.07f, y + h * 0.08f + lblH), Offset(x + w * 0.93f, y + h * 0.08f + lblH), 0.9f)
    // 中央横向双轴透明视窗（左轴 30% / 右轴 70%，经典横向卡带结构）
    val winX = x + w * 0.14f; val winY = y + h * 0.36f; val winW = w * 0.72f; val winH = h * 0.34f
    drawRoundRect(Color(0x78090C12), Offset(winX, winY), Size(winW, winH), CornerRadius(winH * 0.45f))
    drawRoundRect(Color(0x55FFFFFF), Offset(winX, winY), Size(winW, winH), CornerRadius(winH * 0.45f), style = Stroke(1f))
    val rCy = winY + winH * 0.5f; val outerR = winH * 0.46f; val innerR = outerR * 0.52f
    drawTapeReel(x + w * 0.31f, rCy, outerR, innerR, spinDeg, 0.78f)
    drawTapeReel(x + w * 0.69f, rCy, outerR, innerR, spinDeg * 1.12f, 0.46f)
    // 中央剩余磁带刻度窗口
    drawRect(Color(0x38FFFFFF), Offset(x + w * 0.43f, rCy - winH * 0.22f), Size(w * 0.14f, winH * 0.44f), style = Stroke(0.8f))
    for (k in 1..4) drawLine(Color(0x44FFFFFF), Offset(x + w * (0.43f + k * 0.028f), rCy - 3f), Offset(x + w * (0.43f + k * 0.028f), rCy + 3f), 0.8f)
    // 底部经典梯形磁头保护桥与导带孔
    val bwTop = w * 0.46f; val bwBot = w * 0.58f; val bh = h * 0.22f
    val bridge = Path().apply {
        moveTo(x + (w - bwBot) / 2f, y + h); lineTo(x + (w - bwTop) / 2f, y + h - bh)
        lineTo(x + (w + bwTop) / 2f, y + h - bh); lineTo(x + (w + bwBot) / 2f, y + h); close()
    }
    drawPath(bridge, Brush.verticalGradient(listOf(Color(0x48FFFFFF), Color(0x3812161E)), y + h - bh, y + h))
    drawPath(bridge, Color(0x66FFFFFF), style = Stroke(1f))
    val hy = y + h - bh * 0.45f; val hr = (bh * 0.18f).coerceIn(1.8f, 3.6f)
    listOf(0.32f, 0.68f).forEach { fx -> drawCircle(Color(0x9906080C), hr, Offset(x + w * fx, hy)) }
    listOf(0.42f, 0.58f).forEach { fx -> drawRoundRect(Color(0x8806080C), Offset(x + w * fx - hr, hy - hr * 0.75f), Size(hr * 2f, hr * 1.5f), CornerRadius(1.5f)) }
    // 四角十字螺丝
    val sr = (minOf(w, h) * 0.028f).coerceIn(1.8f, 3.2f); val sp = sr * 2.4f
    listOf(Offset(x + sp, y + sp), Offset(x + w - sp, y + sp), Offset(x + sp, y + h - sp), Offset(x + w - sp, y + h - sp)).forEach { pt ->
        drawCircle(Color(0x99CFD8E4), sr, pt)
        drawLine(Color(0x991C222B), Offset(pt.x - sr * 0.6f, pt.y), Offset(pt.x + sr * 0.6f, pt.y), 0.9f)
    }
}

/** 真实竖版透明亚克力磁带专辑盒（Norelco Case）：左侧铰链脊柱 + 上方 1:1 完整无遮挡专辑封面 + 下方复古 J-Card 铭牌条 */
@Composable internal fun CassetteFrontLid(
    item: FlowItem, artDp: Dp, spineDp: Dp, rimDp: Dp, isCenter: Boolean,
    dist: () -> Float, tiltX: () -> Float, tiltY: () -> Float, lidSweep: () -> Float = { 0f },
) {
    val p = c
    Box(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(Color(0x38FFFFFF), Color(0x18B8C8DC), Color(0x2E101620))))
        .border(1.3.dp, Color(if (isCenter) 0x8EFFFFFF else 0x52FFFFFF))) {
        // J-Card 纸卡（完全放在透明外壳内部，左侧留出独立脊柱条，上方 1:1 专辑封面零遮挡）
        Column(Modifier.fillMaxSize().padding(start = spineDp, top = rimDp, end = rimDp, bottom = rimDp)) {
            Cover(item.cover, artDp, item.title, border = false, decodeSize = if (isCenter) 512.dp else 256.dp)
            // 下方经典磁带专辑 J-Card 铭牌区
            Column(Modifier.fillMaxWidth().weight(1f).background(p.sheet.copy(alpha = 0.92f)).padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(p.p.copy(alpha = 0.22f)).padding(horizontal = 4.dp, vertical = 1.dp)) { T("STEREO", 7, p.p, 800, mono = true) }
                    Spacer(Modifier.weight(1f))
                    T("C-90 · TYPE II", 7, p.s, 700, mono = true)
                }
                Column {
                    T(item.title, 10, p.k, 800, lines = 1)
                    T(item.sub, 8, p.s, 500, lines = 1)
                }
            }
        }
        // 外包透明亚克力磁带盒光影：左脊柱 + 上下翻盖铰链销 + 透明棱边 + 斜向塑料反光
        Box(Modifier.matchParentSize().drawWithContent {
            val w = size.width; val h = size.height; val spW = spineDp.toPx(); val rm = rimDp.toPx(); val d = dist(); val ad = abs(d); val sw = lidSweep()
            drawRect(Brush.horizontalGradient(listOf(Color(0x46FFFFFF), Color(0x18FFFFFF), Color(0x30000000), Color(0x4CFFFFFF)), 0f, spW), size = Size(spW, h))
            drawRect(Color(0x55000000), Offset(spW - 1.5f, 0f), Size(1.5f, h)); drawRect(Color(0x58FFFFFF), Offset(spW, 0f), Size(1.2f, h))
            listOf(0.10f, 0.90f).forEach { fy -> val hy = h * fy; drawRect(Color(0x58000000), Offset(2f, hy - 3f), Size(spW - 4f, 3f)); drawRect(Color(0x78FFFFFF), Offset(2f, hy), Size(spW - 4f, 2f)) }
            if (ad < 2.6f) {
                val cw = (1f - kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat()).coerceIn(0f, 1f)
                val sh = (-tiltX() * 0.28f - d.coerceIn(-1f, 1f) * 0.16f + sw * 0.35f) * w
                drawRect(Brush.linearGradient(0f to Color.Transparent, 0.38f to Color.Transparent, 0.50f to Color.White.copy(alpha = 0.04f + 0.08f * cw + 0.14f * sw), 0.58f to Color.White.copy(alpha = 0.02f + 0.03f * cw + 0.06f * sw), 0.68f to Color.Transparent, 1f to Color.Transparent,
                    start = Offset(sh - w * 0.15f, -tiltY() * h * 0.2f), end = Offset(sh + w * 1.05f, h * 0.88f - tiltY() * h * 0.2f)))
            }
            drawRect(Color(0x66FFFFFF), size = Size(w, 1.8f)); drawRect(Color(0x4CFFFFFF), Offset(w - 1.8f, 0f), Size(1.8f, h)); drawRect(Color(0x4C000000), Offset(0f, h - 2f), Size(w, 2f))
        })
        Box(Modifier.matchParentSize().graphicsLayer {
            val ad = abs(dist()); val s = kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat()
            alpha = (0.14f * s + 0.06f * (ad - 1f).coerceAtLeast(0f)).coerceAtMost(0.45f)
        }.background(Color.Black))
    }
}

/** 翻开后的磁带盒左盖内侧（透明插槽内的完整 J-Card 专辑封面与铭牌），点击可合上 */
@Composable internal fun CassetteInnerLid(item: FlowItem, artDp: Dp, spineDp: Dp, rimDp: Dp, lidSweep: () -> Float, onClose: () -> Unit) {
    val p = c
    Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.press(onClick = onClose)
        .background(Brush.verticalGradient(listOf(Color(0x3CFFFFFF), Color(0x18B8C8DC), Color(0x32101620))))
        .border(1.3.dp, Color(0x8EFFFFFF))) {
        Column(Modifier.fillMaxSize().padding(start = rimDp, top = rimDp, end = spineDp, bottom = rimDp)) {
            Cover(item.cover, artDp, item.title, border = false, decodeSize = 512.dp)
            Column(Modifier.fillMaxWidth().weight(1f).background(p.sheet.copy(alpha = 0.92f)).padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(p.p.copy(alpha = 0.22f)).padding(horizontal = 4.dp, vertical = 1.dp)) { T("J-CARD", 7, p.p, 800, mono = true) }
                    Spacer(Modifier.weight(1f))
                    T("DOLBY B NR", 7, p.s, 700, mono = true)
                }
                Column { T(item.title, 10, p.k, 800, lines = 1); T(item.sub, 8, p.s, 500, lines = 1) }
            }
        }
        Box(Modifier.matchParentSize().drawWithContent {
            val w = size.width; val h = size.height; val spW = spineDp.toPx(); val sx = w - spW; val sw = lidSweep()
            drawRect(Brush.horizontalGradient(listOf(Color(0x48FFFFFF), Color(0x2C000000), Color(0x18FFFFFF), Color(0x42FFFFFF)), sx, w), Offset(sx, 0f), Size(spW, h))
            drawRect(Color(0x52000000), Offset(sx, 0f), Size(1.5f, h))
            if (sw > 0.01f) drawRect(Brush.linearGradient(0f to Color.Transparent, 0.36f to Color.Transparent, 0.52f to Color.White.copy(alpha = 0.16f * sw), 0.68f to Color.Transparent, 1f to Color.Transparent, start = Offset(-w * 0.1f + sw * w * 0.3f, 0f), end = Offset(w * 0.9f + sw * w * 0.3f, h)))
            drawRect(Color(0x58FFFFFF), size = Size(w, 1.8f)); drawRect(Color(0x44000000), Offset(0f, h - 2f), Size(w, 2f))
        })
    }
}

/** 翻开后的磁带盒右侧托盘：上方躺着真实横向透明磁带本体（双轴随开合旋转）+ 下方完整 SIDE A 曲目表 */
@Composable internal fun CassetteTrayTracklist(
    item: FlowItem, tracks: List<Track>?, loading: Boolean, compact: Boolean,
    openProg: () -> Float, onClose: () -> Unit, onOpenDetail: () -> Unit,
) {
    val p = c
    val playingId = Engine.state.collectAsState().value.current?.id
    Column(Modifier.fillMaxSize().background(p.sheet.copy(alpha = 0.92f)).border(1.3.dp, Color(0x82FFFFFF)).padding(if (compact) 8.dp else 11.dp)) {
        // 顶部实体横向卡带本体（左右双 6 齿轴 + 底部梯形桥 + 四角螺丝）
        Box(Modifier.fillMaxWidth().aspectRatio(1.62f).drawBehind {
            drawHorizontalCassette(0f, 0f, size.width, size.height, openProg() * 140f, p.p)
        })
        Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 5.dp).background(p.p.copy(alpha = 0.18f)).padding(horizontal = 4.dp, vertical = 1.dp)) { T("SIDE A", 8, p.p, 800, mono = true) }
            T(tracks?.let { "${it.size} 首" } ?: "", 9, p.s, 600, mono = true, modifier = Modifier.weight(1f))
            if (!tracks.isNullOrEmpty()) Box(Modifier.padding(end = 4.dp).press { Engine.playList(tracks, 0) }.background(p.p).padding(horizontal = 7.dp, vertical = 3.dp)) { T("播放", 9, Color.White, 700) }
            if (item.kind != "tracks") Box(Modifier.padding(end = 4.dp).press(onClick = onOpenDetail).nbf(1.dp, p.l2).padding(horizontal = 6.dp, vertical = 3.dp)) { T("详情", 9, p.k, 600) }
            Box(Modifier.press(onClick = onClose).nbf(1.dp, p.l2).padding(horizontal = 6.dp, vertical = 3.dp)) { T("合上", 9, p.k, 700) }
        }
        Spacer(Modifier.height(4.dp)); Box(Modifier.fillMaxWidth().height(1.dp).background(p.l1))
        when {
            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { T("LOADING…", 10, p.s, mono = true) }
            tracks.isNullOrEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { T("暂无曲目信息", 11, p.s) }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 3.dp)) {
                itemsIndexed(tracks, key = { idx, t -> "${t.id}_$idx" }) { idx, t ->
                    val on = playingId == t.id
                    Row(Modifier.fillMaxWidth().press { Engine.playList(tracks, idx) }.background(if (on) p.p.copy(alpha = 0.14f) else Color.Transparent).padding(horizontal = 4.dp, vertical = if (compact) 3.dp else 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(two(idx + 1), 9, if (on) p.p else p.s, 600, mono = true, modifier = Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) {
                            T(t.title, if (compact) 10 else 11, if (on) p.p else p.k, if (on) 700 else 600, lines = 1)
                            if (t.artist.isNotBlank()) T(t.artist, 8, p.s, lines = 1)
                        }
                        if (t.durationMs > 0) T(fmt(t.durationMs), 9, p.s, mono = true, modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
        }
    }
}
