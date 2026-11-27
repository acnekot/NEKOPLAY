package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.sign

/** 3D 封面墙单张实体 CD 盒与镜面倒影（纯 GPU graphicsLayer 变换，独立拆分降低 CoverFlowScreen 复杂度） */
@Composable internal fun CoverFlowCard(
    index: Int,
    curIdx: Int,
    item: FlowItem,
    posValue: () -> Float,
    expandState: State<Float>,
    tiltXValue: () -> Float,
    tiltYValue: () -> Float,
    cfg: CoverFlowCfg,
    ls: Boolean,
    density: Float,
    stageW: Float,
    coverDp: Dp,
    coverPx: Float,
    spineDp: Dp,
    rimDp: Dp,
    caseW: Dp,
    caseH: Dp,
    caseWPx: Float,
    caseHPx: Float,
    maxOpenScale: Float,
    centerGapPx: Float,
    stackStepPx: Float,
    reflH: Dp,
    reflMask: Brush,
    canExpand: Boolean,
    showTray: Boolean,
    lidPastHalf: Boolean,
    sideTracks: List<Track>?,
    sideLoading: Boolean,
    onCloseExpand: () -> Unit,
    onOpenDetail: (FlowItem) -> Unit,
    onTap: () -> Unit,
) {
    val isCenter = index == curIdx
    val z = 100f - abs(index - curIdx) * 10f

    Column(
        Modifier.zIndex(z)
            .graphicsLayer {
                val d = index - posValue()
                val ad = abs(d)
                val s = kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat()
                val wingAngle = -cfg.wingAngle * sign(d) * s
                val extraGap = (centerGapPx - stackStepPx) * kotlin.math.tanh((ad * 1.35f).toDouble()).toFloat()
                val tx = d * stackStepPx + sign(d) * extraGap
                val ep = expandState.value.coerceIn(0f, 1.05f)
                val zoomP = smoothStep(0.28f, 1.0f, ep)
                val shiftP = smoothStep(0.08f, 0.92f, ep)
                val swirl = kotlin.math.sin((ep.coerceIn(0f, 1f) * kotlin.math.PI)).toFloat()
                val sc = (1f - 0.20f * s) * (if (isCenter) 1f + (maxOpenScale - 1f) * zoomP else 1f)
                val centerWeight = (1f - s).coerceIn(0f, 1f)
                val tX = tiltXValue(); val tY = tiltYValue()
                val tiltDamp = 1f - 0.55f * ep.coerceAtMost(1f)
                val expX = if (isCenter) caseWPx * 0.50f * sc * shiftP else sign(d) * smoothStep(0f, 0.65f, ep) * 76f * density
                val liftY = if (isCenter) -14f * density * swirl else 0f
                translationX = tx + expX + tX * (10f * density * (0.35f + 0.65f * centerWeight) * tiltDamp)
                val baseY = coverPx * (if (ls) 0.08f else 0.14f)
                translationY = (if (isCenter) baseY + (caseHPx * 0.18f - baseY) * zoomP else baseY) + liftY + tY * (5f * density * centerWeight * tiltDamp)
                scaleX = sc; scaleY = sc
                rotationY = wingAngle + (if (isCenter) cfg.spinDeg * swirl else 0f) + tX * (4.5f + 7.5f * centerWeight) * tiltDamp
                rotationX = (if (isCenter) 8f * swirl else 0f) - tY * (3.2f + 5.8f * centerWeight) * tiltDamp
                rotationZ = if (isCenter) -3.2f * swirl else 0f
                transformOrigin = TransformOrigin(0.5f, 0.368f)
                cameraDistance = 18f * density
                val baseAlpha = if (ad <= 3.2f) 1f else (1f - (ad - 3.2f) * 0.48f).coerceIn(0f, 1f)
                alpha = if (isCenter) baseAlpha else baseAlpha * (1f - smoothStep(0f, 0.58f, ep))
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val lidP = { if (isCenter) smoothStep(0.02f, 0.72f, expandState.value) else 0f }
        val heroKey = if (isCenter) when (item.kind) { "album" -> "alb:${item.id}"; "pl", "lpl" -> "pl:${item.id}"; else -> null } else null
        Box(Modifier.size(caseW, caseH).heroAnchor(heroKey, item.cover, item.title, border = false).cdThicknessEdge({ index - posValue() }, tiltXValue, tiltYValue, cfg.thicknessDp) { if (isCenter) (1f - smoothStep(0f, 0.45f, expandState.value)) else 1f }) {
            if (isCenter && showTray) {
                Box(Modifier.matchParentSize().graphicsLayer { alpha = smoothStep(0.10f, 0.58f, expandState.value) }) {
                    CdTrayTracklist(item, sideTracks, sideLoading, compact = ls || stageW < 640f * density, openProg = { expandState.value }, onClose = onCloseExpand, onOpenDetail = { onOpenDetail(item) })
                }
            }
            Box(Modifier.matchParentSize()
                .graphicsLayer { rotationY = -180f * lidP(); transformOrigin = TransformOrigin(0f, 0.5f); cameraDistance = 18f * density }
                .pointerInput(item.key, isCenter, canExpand) {
                    detectTapGestures { onTap() }
                }) {
                val sweep = { kotlin.math.sin((lidP() * kotlin.math.PI)).toFloat() }
                if (!isCenter || !lidPastHalf) {
                    CdCaseFrontLid(item, coverDp, spineDp, rimDp, isCenter, { index - posValue() }, tiltXValue, tiltYValue, sweep)
                } else {
                    CdCaseInnerLid(item, coverDp, spineDp, rimDp, sweep, onClose = onCloseExpand)
                }
            }
        }
        if (abs(index - curIdx) <= 3) Box(Modifier.size(caseW, reflH).clipToBounds()
            .graphicsLayer {
                alpha = if (isCenter) (1f - expandState.value * 1.6f).coerceIn(0f, 1f) else 1f
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(brush = reflMask, topLeft = Offset(-4f, -4f), size = Size(size.width + 8f, size.height + 8f), blendMode = BlendMode.DstIn)
            }) {
            Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(caseH).graphicsLayer { scaleY = -1f }.background(Color(0x1CFFFFFF)).border(1.dp, Color(0x38FFFFFF))) {
                Box(Modifier.width(spineDp).fillMaxHeight().background(Color(0x28FFFFFF)))
                Box(Modifier.padding(start = spineDp, top = rimDp, end = rimDp, bottom = rimDp)) { Cover(item.cover, coverDp, item.title, border = false, decodeSize = 200.dp) }
                Box(Modifier.matchParentSize().graphicsLayer { val ad = abs(index - posValue()); val s = kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat(); alpha = (0.14f * s + 0.06f * (ad - 1f).coerceAtLeast(0f)).coerceAtMost(0.45f) }.background(Color.Black))
            }
        } else Spacer(Modifier.height(reflH))
    }
}
