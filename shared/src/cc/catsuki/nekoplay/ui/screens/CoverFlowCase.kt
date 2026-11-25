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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal data class FlowItem(val key: String, val kind: String, val id: String, val title: String, val sub: String, val cover: String?, val presetTracks: List<Track>? = null)

/** 外包式透明亚克力 CD 塑料壳前盖：左侧独立防滑脊柱条 + 四周透明边框完全包裹 1:1 封面，绝不遮挡画作 */
@Composable internal fun CdCaseFrontLid(
    item: FlowItem, coverDp: Dp, spineDp: Dp, rimDp: Dp, isCenter: Boolean,
    dist: () -> Float, tiltX: () -> Float, tiltY: () -> Float,
) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x32FFFFFF), Color(0x14B8C8DC), Color(0x28101620))))
        .border(1.2.dp, Color(if (isCenter) 0x88FFFFFF else 0x4EFFFFFF))) {
        Box(Modifier.padding(start = spineDp, top = rimDp, end = rimDp, bottom = rimDp)) {
            Cover(item.cover, coverDp, item.title, border = false, decodeSize = if (isCenter) 512.dp else 256.dp)
        }
        Box(Modifier.matchParentSize().drawWithContent {
            val w = size.width; val h = size.height; val spW = spineDp.toPx(); val rm = rimDp.toPx(); val d = dist(); val ad = abs(d)
            drawRect(Brush.horizontalGradient(listOf(Color(0x3EFFFFFF), Color(0x16FFFFFF), Color(0x2C000000), Color(0x44FFFFFF)), 0f, spW), size = Size(spW, h))
            drawRect(Color(0x52000000), Offset(spW - 1.5f, 0f), Size(1.5f, h)); drawRect(Color(0x52FFFFFF), Offset(spW, 0f), Size(1.2f, h))
            for (r in 1..4) drawRect(Color(0x28FFFFFF), Offset(spW * r / 5f, rm), Size(1f, h - rm * 2f))
            listOf(0.14f, 0.86f).forEach { fy -> val hy = h * fy; drawRect(Color(0x54000000), Offset(2f, hy - 3f), Size(spW - 4.5f, 3f)); drawRect(Color(0x6EFFFFFF), Offset(2f, hy), Size(spW - 4.5f, 2f)) }
            listOf(0.28f, 0.72f).forEach { fx -> val tx = spW + (w - spW - rm) * fx; drawRect(Color(0x55FFFFFF), Offset(tx - 6f, 0f), Size(12f, rm)); drawRect(Color(0x44FFFFFF), Offset(tx - 6f, h - rm), Size(12f, rm)) }
            if (ad < 2.6f) {
                val cw = (1f - kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat()).coerceIn(0f, 1f)
                val sh = (-tiltX() * 0.28f - d.coerceIn(-1f, 1f) * 0.16f) * w
                drawRect(Brush.linearGradient(0f to Color.Transparent, 0.40f to Color.Transparent, 0.50f to Color.White.copy(alpha = 0.04f + 0.08f * cw), 0.56f to Color.White.copy(alpha = 0.02f + 0.03f * cw), 0.65f to Color.Transparent, 1f to Color.Transparent,
                    start = Offset(sh - w * 0.15f, -tiltY() * h * 0.2f), end = Offset(sh + w * 1.05f, h * 0.85f - tiltY() * h * 0.2f)))
            }
            drawRect(Color(0x62FFFFFF), size = Size(w, 1.8f)); drawRect(Color(0x4CFFFFFF), Offset(w - 1.8f, 0f), Size(1.8f, h)); drawRect(Color(0x4C000000), Offset(0f, h - 2f), Size(w, 2f))
        })
        Box(Modifier.matchParentSize().graphicsLayer {
            val ad = abs(dist()); val s = kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat()
            alpha = (0.14f * s + 0.06f * (ad - 1f).coerceAtLeast(0f)).coerceAtMost(0.45f)
        }.background(Color.Black))
    }
}

/** 翻开后的透明 CD 壳左盖内侧（歌词本插页视图），点击可合上 CD 盒 */
@Composable internal fun CdCaseInnerLid(item: FlowItem, coverDp: Dp, spineDp: Dp, rimDp: Dp, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.press(onClick = onClose)
        .background(Brush.verticalGradient(listOf(Color(0x38FFFFFF), Color(0x16B8C8DC), Color(0x2E101620))))
        .border(1.2.dp, Color(0x88FFFFFF))) {
        // 左盖翻开后铰链脊柱在右侧（连接中缝），1:1 歌词本封面完整展示在左侧透明插槽内
        Box(Modifier.padding(start = rimDp, top = rimDp, end = spineDp, bottom = rimDp)) {
            Cover(item.cover, coverDp, item.title, border = false, decodeSize = 512.dp)
        }
        Box(Modifier.matchParentSize().drawWithContent {
            val w = size.width; val h = size.height; val spW = spineDp.toPx(); val rm = rimDp.toPx(); val sx = w - spW
            drawRect(Brush.horizontalGradient(listOf(Color(0x44FFFFFF), Color(0x2A000000), Color(0x18FFFFFF), Color(0x3EFFFFFF)), sx, w), Offset(sx, 0f), Size(spW, h))
            drawRect(Color(0x52000000), Offset(sx, 0f), Size(1.5f, h))
            for (r in 1..4) drawRect(Color(0x28FFFFFF), Offset(sx + spW * r / 5f, rm), Size(1f, h - rm * 2f))
            listOf(0.28f, 0.72f).forEach { fx -> val tx = rm + (w - spW - rm) * fx; drawRect(Color(0x66FFFFFF), Offset(tx - 8f, 0f), Size(16f, rm + 1.5f)); drawRect(Color(0x55FFFFFF), Offset(tx - 8f, h - rm - 1.5f), Size(16f, rm + 1.5f)) }
            drawRect(Color(0x58FFFFFF), size = Size(w, 1.8f)); drawRect(Color(0x44000000), Offset(0f, h - 2f), Size(w, 2f))
        })
    }
}

/** 翻开后的右侧 CD 托盘 + 完整曲目列表（带若隐若现的光盘碟片与中心卡扣底纹） */
@Composable internal fun CdTrayTracklist(
    item: FlowItem, tracks: List<Track>?, loading: Boolean, compact: Boolean,
    onClose: () -> Unit, onOpenDetail: () -> Unit,
) {
    val p = c
    val playingId = Engine.state.collectAsState().value.current?.id
    Column(Modifier.fillMaxSize()
        .background(p.sheet.copy(alpha = 0.90f))
        .border(1.2.dp, Color(0x78FFFFFF))
        .drawBehind {
            val cx = size.width * 0.52f; val cy = size.height * 0.54f; val rad = minOf(size.width, size.height) * 0.42f
            drawCircle(Brush.sweepGradient(listOf(Color(0x16FFFFFF), Color(0x0E7CB8FF), Color(0x16E8B8FF), Color(0x0E90FFE0), Color(0x16FFFFFF)), Offset(cx, cy)), rad, Offset(cx, cy))
            drawCircle(Color(0x1FFFFFFF), rad, Offset(cx, cy), style = Stroke(1.2f))
            drawCircle(Color(0x14FFFFFF), rad * 0.72f, Offset(cx, cy), style = Stroke(0.8f))
            drawCircle(Color(0x22FFFFFF), rad * 0.28f, Offset(cx, cy), style = Stroke(1.5f))
            drawCircle(Color(0x32000000), rad * 0.14f, Offset(cx, cy))
            drawCircle(Color(0x2EFFFFFF), rad * 0.14f, Offset(cx, cy), style = Stroke(1.2f))
        }
        .padding(if (compact) 10.dp else 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(item.title, if (compact) 13 else 15, p.k, 800, lines = 1)
                T(item.sub + (tracks?.let { " · ${it.size} 首" } ?: ""), if (compact) 10 else 11, p.s, lines = 1)
            }
            if (!tracks.isNullOrEmpty()) Box(Modifier.padding(end = 5.dp).press { Engine.playList(tracks, 0) }.background(p.p).padding(horizontal = 8.dp, vertical = 4.dp)) { T("播放", 10, Color.White, 700) }
            if (item.kind != "tracks") Box(Modifier.padding(end = 5.dp).press(onClick = onOpenDetail).nbf(1.dp, p.l2).padding(horizontal = 8.dp, vertical = 4.dp)) { T("详情", 10, p.k, 600) }
            Box(Modifier.press(onClick = onClose).nbf(1.dp, p.l2).padding(horizontal = 8.dp, vertical = 4.dp)) { T("合上", 10, p.k, 700) }
        }
        Spacer(Modifier.height(if (compact) 6.dp else 8.dp)); Box(Modifier.fillMaxWidth().height(1.dp).background(p.l1))
        when {
            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { T("LOADING…", 11, p.s, mono = true) }
            tracks.isNullOrEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { T("暂无曲目信息", 12, p.s) }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                itemsIndexed(tracks, key = { idx, t -> "${t.id}_$idx" }) { idx, t ->
                    val on = playingId == t.id
                    Row(Modifier.fillMaxWidth().press { Engine.playList(tracks, idx) }.background(if (on) p.p.copy(alpha = 0.14f) else Color.Transparent).padding(horizontal = 5.dp, vertical = if (compact) 4.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
