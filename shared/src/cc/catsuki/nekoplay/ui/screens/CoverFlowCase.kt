package cc.catsuki.nekoplay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal data class FlowItem(val key: String, val kind: String, val id: String, val title: String, val sub: String, val cover: String?, val presetTracks: List<Track>? = null)

/** Cover Flow 热更新配置：直接从 coverflow.js 的 config() 读取，更新插件即可实时调整外观、3D 角度与交互策略 */
internal data class CoverFlowCfg(
    val wingAngle: Float = 64f, val maxOpenScale: Float = 1.65f, val spinDeg: Float = 22f,
    val spineRatio: Float = 0.11f, val thicknessDp: Float = 6.5f, val reflectionAlpha: Float = 0.30f,
    val portraitExpand: Boolean = false, val desktopSidebarToggle: Boolean = true,
) {
    companion object {
        fun from(o: org.json.JSONObject?) = if (o == null) CoverFlowCfg() else CoverFlowCfg(
            o.optDouble("wingAngle", 64.0).toFloat().coerceIn(25f, 80f),
            o.optDouble("maxOpenScale", 1.65).toFloat().coerceIn(1.0f, 2.0f),
            o.optDouble("spinDeg", 22.0).toFloat().coerceIn(0f, 45f),
            o.optDouble("spineRatio", 0.11).toFloat().coerceIn(0.06f, 0.18f),
            o.optDouble("thicknessDp", 6.5).toFloat().coerceIn(0f, 14f),
            o.optDouble("reflectionAlpha", 0.30).toFloat().coerceIn(0f, 0.55f),
            o.optBoolean("portraitExpand", false),
            o.optBoolean("desktopSidebarToggle", true),
        )
    }
}

internal fun smoothStep(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

/** 仅在外框边缘无缝挤出透明亚克力 3D 侧边厚度（绝不垫在透明外壳后面造成错位重影） */
internal fun Modifier.cdThicknessEdge(dist: () -> Float, tiltX: () -> Float, tiltY: () -> Float, depthDp: Float = 6.5f, vis: () -> Float) = drawBehind {
    val v = vis(); if (v <= 0.01f || depthDp <= 0.1f) return@drawBehind
    val d = dist(); val s = kotlin.math.tanh((abs(d) * 1.45f).toDouble()).toFloat()
    val ex = kotlin.math.sign(d) * s * depthDp.dp.toPx() - tiltX() * 3.dp.toPx()
    val ey = -tiltY() * 2.5.dp.toPx()
    val side = Brush.verticalGradient(listOf(Color(0x6CE4EEFA).copy(alpha = 0.44f * v), Color(0x528292A8).copy(alpha = 0.34f * v), Color(0x5C202632).copy(alpha = 0.40f * v)))
    val rim = Color.White.copy(alpha = 0.46f * v); val shd = Color.Black.copy(alpha = 0.36f * v)
    if (ex > 0.5f) {
        drawRect(side, Offset(size.width, 0f), Size(ex, size.height))
        drawRect(rim, Offset(size.width + ex - 1f, 0f), Size(1f, size.height))
        drawRect(rim, Offset(size.width, 0f), Size(ex, 1f))
        drawRect(shd, Offset(size.width, size.height - 1.2f), Size(ex, 1.2f))
    } else if (ex < -0.5f) {
        val w = -ex
        drawRect(side, Offset(-w, 0f), Size(w, size.height))
        drawRect(rim, Offset(-w, 0f), Size(1f, size.height))
        drawRect(rim, Offset(-w, 0f), Size(w, 1f))
        drawRect(shd, Offset(-w, size.height - 1.2f), Size(w, 1.2f))
    }
    if (ey > 0.5f) {
        drawRect(Color(0x448292A8).copy(alpha = 0.28f * v), Offset(0f, size.height), Size(size.width, ey))
        drawRect(shd, Offset(0f, size.height + ey - 1f), Size(size.width, 1f))
    } else if (ey < -0.5f) {
        val h = -ey
        drawRect(Color(0x58E4EEFA).copy(alpha = 0.36f * v), Offset(0f, -h), Size(size.width, h))
        drawRect(rim, Offset(0f, -h), Size(size.width, 1f))
    }
}

/** 外包式透明亚克力 CD 塑料壳前盖：左侧独立防滑脊柱条 + 四周透明边框完全包裹 1:1 封面，绝不遮挡画作 */
@Composable internal fun CdCaseFrontLid(
    item: FlowItem, coverDp: Dp, spineDp: Dp, rimDp: Dp, isCenter: Boolean,
    dist: () -> Float, tiltX: () -> Float, tiltY: () -> Float, lidSweep: () -> Float = { 0f },
) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x32FFFFFF), Color(0x14B8C8DC), Color(0x28101620))))
        .border(1.2.dp, Color(if (isCenter) 0x88FFFFFF else 0x4EFFFFFF))) {
        Box(Modifier.padding(start = spineDp, top = rimDp, end = rimDp, bottom = rimDp)) {
            Cover(item.cover, coverDp, item.title, border = false, decodeSize = if (isCenter) 512.dp else 256.dp)
        }
        Box(Modifier.matchParentSize().drawWithContent {
            val w = size.width; val h = size.height; val spW = spineDp.toPx(); val rm = rimDp.toPx(); val d = dist(); val ad = abs(d); val sw = lidSweep()
            drawRect(Brush.horizontalGradient(listOf(Color(0x3EFFFFFF), Color(0x16FFFFFF), Color(0x2C000000), Color(0x44FFFFFF)), 0f, spW), size = Size(spW, h))
            drawRect(Color(0x52000000), Offset(spW - 1.5f, 0f), Size(1.5f, h)); drawRect(Color(0x52FFFFFF), Offset(spW, 0f), Size(1.2f, h))
            for (r in 1..4) drawRect(Color(0x28FFFFFF), Offset(spW * r / 5f, rm), Size(1f, h - rm * 2f))
            listOf(0.14f, 0.86f).forEach { fy -> val hy = h * fy; drawRect(Color(0x54000000), Offset(2f, hy - 3f), Size(spW - 4.5f, 3f)); drawRect(Color(0x6EFFFFFF), Offset(2f, hy), Size(spW - 4.5f, 2f)) }
            listOf(0.28f, 0.72f).forEach { fx -> val tx = spW + (w - spW - rm) * fx; drawRect(Color(0x55FFFFFF), Offset(tx - 6f, 0f), Size(12f, rm)); drawRect(Color(0x44FFFFFF), Offset(tx - 6f, h - rm), Size(12f, rm)) }
            if (ad < 2.6f) {
                val cw = (1f - kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat()).coerceIn(0f, 1f)
                val sh = (-tiltX() * 0.28f - d.coerceIn(-1f, 1f) * 0.16f + sw * 0.35f) * w
                drawRect(Brush.linearGradient(0f to Color.Transparent, 0.38f to Color.Transparent, 0.50f to Color.White.copy(alpha = 0.04f + 0.08f * cw + 0.14f * sw), 0.57f to Color.White.copy(alpha = 0.02f + 0.03f * cw + 0.06f * sw), 0.68f to Color.Transparent, 1f to Color.Transparent,
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
@Composable internal fun CdCaseInnerLid(item: FlowItem, coverDp: Dp, spineDp: Dp, rimDp: Dp, lidSweep: () -> Float, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.press(onClick = onClose)
        .background(Brush.verticalGradient(listOf(Color(0x38FFFFFF), Color(0x16B8C8DC), Color(0x2E101620))))
        .border(1.2.dp, Color(0x88FFFFFF))) {
        // 左盖翻开后铰链脊柱在右侧（连接中缝），1:1 歌词本封面完整展示在左侧透明插槽内
        Box(Modifier.padding(start = rimDp, top = rimDp, end = spineDp, bottom = rimDp)) {
            Cover(item.cover, coverDp, item.title, border = false, decodeSize = 512.dp)
        }
        Box(Modifier.matchParentSize().drawWithContent {
            val w = size.width; val h = size.height; val spW = spineDp.toPx(); val rm = rimDp.toPx(); val sx = w - spW; val sw = lidSweep()
            drawRect(Brush.horizontalGradient(listOf(Color(0x44FFFFFF), Color(0x2A000000), Color(0x18FFFFFF), Color(0x3EFFFFFF)), sx, w), Offset(sx, 0f), Size(spW, h))
            drawRect(Color(0x52000000), Offset(sx, 0f), Size(1.5f, h))
            for (r in 1..4) drawRect(Color(0x28FFFFFF), Offset(sx + spW * r / 5f, rm), Size(1f, h - rm * 2f))
            listOf(0.28f, 0.72f).forEach { fx -> val tx = rm + (w - spW - rm) * fx; drawRect(Color(0x66FFFFFF), Offset(tx - 8f, 0f), Size(16f, rm + 1.5f)); drawRect(Color(0x55FFFFFF), Offset(tx - 8f, h - rm - 1.5f), Size(16f, rm + 1.5f)) }
            if (sw > 0.01f) drawRect(Brush.linearGradient(0f to Color.Transparent, 0.36f to Color.Transparent, 0.52f to Color.White.copy(alpha = 0.16f * sw), 0.68f to Color.Transparent, 1f to Color.Transparent, start = Offset(-w * 0.1f + sw * w * 0.3f, 0f), end = Offset(w * 0.9f + sw * w * 0.3f, h)))
            drawRect(Color(0x58FFFFFF), size = Size(w, 1.8f)); drawRect(Color(0x44000000), Offset(0f, h - 2f), Size(w, 2f))
        })
    }
}

/** 翻开后的右侧 CD 托盘 + 完整曲目列表（适配放大倍率的高密度唱片内页排版：全宽标题、总时长、智能省略重复歌手、单屏展示 8~10 首） */
@Composable internal fun CdTrayTracklist(
    item: FlowItem, tracks: List<Track>?, loading: Boolean, compact: Boolean,
    openProg: () -> Float, onClose: () -> Unit, onOpenDetail: () -> Unit,
) {
    val p = c
    val playingId = Engine.state.collectAsState().value.current?.id
    val showArtist = tracks != null && tracks.map { it.artist }.distinct().size > 1
    val totalMs = tracks?.sumOf { it.durationMs.coerceAtLeast(0L) } ?: 0L
    val metaLine = buildList {
        if (item.sub.isNotBlank()) add(item.sub)
        if (tracks != null && !item.sub.contains("首")) add("${tracks.size} 首")
        if (totalMs > 0L) add(fmt(totalMs))
    }.joinToString(" · ")
    Column(Modifier.fillMaxSize()
        .background(p.sheet.copy(alpha = 0.90f))
        .border(1.2.dp, Color(0x78FFFFFF))
        .drawBehind {
            val cx = size.width * 0.52f; val cy = size.height * 0.54f; val rad = minOf(size.width, size.height) * 0.42f
            rotate(openProg() * 75f, Offset(cx, cy)) {
                drawCircle(Brush.sweepGradient(listOf(Color(0x1CFFFFFF), Color(0x147CB8FF), Color(0x1CE8B8FF), Color(0x1490FFE0), Color(0x1CFFFFFF)), Offset(cx, cy)), rad, Offset(cx, cy))
            }
            drawCircle(Color(0x22FFFFFF), rad, Offset(cx, cy), style = Stroke(1.2f))
            drawCircle(Color(0x16FFFFFF), rad * 0.72f, Offset(cx, cy), style = Stroke(0.8f))
            drawCircle(Color(0x24FFFFFF), rad * 0.28f, Offset(cx, cy), style = Stroke(1.5f))
            drawCircle(Color(0x34000000), rad * 0.14f, Offset(cx, cy))
            drawCircle(Color(0x32FFFFFF), rad * 0.14f, Offset(cx, cy), style = Stroke(1.2f))
        }
        .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 6.dp else 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(item.title, if (compact) 10 else 11, p.k, 800, lines = 1)
                if (metaLine.isNotEmpty()) T(metaLine, if (compact) 8 else 9, p.s, 500, lines = 1, mono = false)
            }
            if (item.kind != "tracks") Box(Modifier.padding(start = 4.dp).press(onClick = onOpenDetail).nbf(0.8.dp, p.l2).padding(horizontal = 5.dp, vertical = 2.dp)) { T("详情", 8, p.k, 600) }
            Box(Modifier.padding(start = 4.dp).press(onClick = onClose).nbf(0.8.dp, p.l2).padding(horizontal = 5.dp, vertical = 2.dp)) { T("×", 9, p.k, 700) }
        }
        Spacer(Modifier.height(4.dp)); Box(Modifier.fillMaxWidth().height(0.8.dp).background(p.l1))
        when {
            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { T("LOADING…", 9, p.s, mono = true) }
            tracks.isNullOrEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { T("暂无曲目信息", 10, p.s) }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 2.dp)) {
                itemsIndexed(tracks, key = { idx, t -> "${t.id}_$idx" }) { idx, t ->
                    val on = playingId == t.id
                    val selA = androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, Motion.standard(), label = "cdSel")
                    Row(Modifier.fillMaxWidth().press { Engine.playList(tracks, idx) }.drawBehind {
                        if (selA.value > 0.01f) drawRect(p.p.copy(alpha = 0.15f * selA.value))
                    }.padding(horizontal = 3.dp, vertical = if (showArtist) 3.dp else 3.5.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(two(idx + 1), 8, if (on) p.p else p.s, 600, mono = true, modifier = Modifier.width(18.dp))
                        Column(Modifier.weight(1f).graphicsLayer {
                            val s = 1f + 0.045f * selA.value
                            scaleX = s; scaleY = s
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        }) {
                            T(t.title, if (compact) 9 else 10, if (on) p.p else p.k, if (on) 700 else 600, lines = 1)
                            if (showArtist && t.artist.isNotBlank()) T(t.artist, 7, p.s, lines = 1)
                        }
                        if (t.durationMs > 0) T(fmt(t.durationMs), 8, p.s, mono = true, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

/** 封面墙底部专辑信息、操作按钮与快滑条（支持手机横屏超紧凑单行与常规两行排布） */
@Composable internal fun CoverFlowFooter(
    curItem: FlowItem,
    ls: Boolean,
    canExpand: Boolean,
    isExpanded: Boolean,
    itemCount: Int,
    sliderValue: () -> Float,
    onPlay: () -> Unit,
    onToggleExpand: () -> Unit,
    onToggleLandscapeExpand: () -> Unit,
    onOpenDetail: () -> Unit,
    onSlide: (Float) -> Unit,
    onSlideEnd: () -> Unit,
) {
    val p = c
    if (ls) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.align(Alignment.Center).padding(horizontal = 112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            T(curItem.title, 14, p.k, 800, lines = 1, align = TextAlign.Center)
            T(curItem.sub, 11, p.s, lines = 1, align = TextAlign.Center)
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.press(onClick = onPlay).background(p.p).padding(horizontal = 12.dp, vertical = 5.dp)) { T("播放", 11, Color.White, 700) }
            Box(Modifier.press(onClick = onToggleLandscapeExpand).nbf(1.dp, p.k).padding(horizontal = 10.dp, vertical = 5.dp)) { T(if (isExpanded) "合上" else "翻开", 11, p.k, 600) }
        }
    } else Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(curItem, contentKey = { it.key }, transitionSpec = { fadeIn(Motion.quick()) togetherWith fadeOut(Motion.quick()) }, label = "cfMeta") { it ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                T(it.title, 18, p.k, 800, lines = 1, align = TextAlign.Center); Spacer(Modifier.height(2.dp)); T(it.sub, 12, p.s, 500, lines = 1, align = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.press(onClick = onPlay).background(p.p).padding(horizontal = 16.dp, vertical = 7.dp)) { T("立即播放", 12, Color.White, 700) }
            if (canExpand) Box(Modifier.press(onClick = onToggleExpand).nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 7.dp)) { T(if (isExpanded) "合上 CD 盒" else "翻开 CD 盒", 12, p.k, 600) }
            if (curItem.kind != "tracks") Box(Modifier.press(onClick = onOpenDetail).nbf(1.dp, p.l2).padding(horizontal = 14.dp, vertical = 7.dp)) { T("进入详情", 12, p.k, 600) }
        }
        if (itemCount > 1) {
            Spacer(Modifier.height(10.dp))
            CoverFlowSlider(itemCount, sliderValue, onSlideEnd, onSlide)
        }
    }
}

/** 独立读取 pos.value 的窄作用域滑条，避免 3D 封面墙滑动/惯性回弹时触发整页重组 */
@Composable private fun CoverFlowSlider(itemCount: Int, valueProvider: () -> Float, onDone: () -> Unit, onChange: (Float) -> Unit) {
    val maxV = (itemCount - 1).toFloat()
    NSlider(valueProvider().coerceIn(0f, maxV), 0f..maxV, onDone = onDone, onChange = onChange)
}
