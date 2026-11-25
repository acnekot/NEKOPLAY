package cc.catsuki.nekoplay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

private data class FlowItem(val key: String, val kind: String, val id: String, val title: String, val sub: String, val cover: String?, val presetTracks: List<Track>? = null)

/** 经典 iOS 6 Apple Music Cover Flow 3D 封面墙：纯黑镜面舞台 + 平行 70° 侧翼唱片架 + 精确底部镜面倒影 + 惯性甩动 + 点击中心 180° 翻转看曲目 */
@Composable fun CoverFlowPage() {
    val nav = LocalNav.current
    val p = c
    val scope = rememberCoroutineScope()
    val pls by Store.playlists.collectAsState()
    val rec by Store.recents.collectAsState()
    val prof by Store.profile.collectAsState()
    val lls by LocalLib.lists.collectAsState()
    val main = Plugins.main()
    val subAlbums by produceState<List<Album>>(emptyList(), prof, main?.id) {
        if (prof != null && main?.has("subAlbums") == true) runCatching { value = Ncm.subAlbums() }
    }

    var filter by remember { mutableIntStateOf(0) } // 0 全部 / 1 专辑 / 2 歌单 / 3 最近
    val items = remember(filter, subAlbums, pls, lls, rec) {
        val albumItems = subAlbums.map { a ->
            FlowItem("alb:${a.id}", "album", a.id, a.name, listOfNotNull(a.artist.ifBlank { null }, a.year.ifBlank { null }, "${a.size} 首").joinToString(" · "), a.pic)
        }
        val recentAlbums = rec.groupBy { it.album.ifBlank { it.title } }.entries.mapNotNull { (alb, ts) ->
            val first = ts.firstOrNull() ?: return@mapNotNull null
            val hasAlb = first.albumId.isNotEmpty()
            val key = if (hasAlb) "alb:${first.albumId}" else "rec:$alb"
            FlowItem(key, if (hasAlb) "album" else "tracks", if (hasAlb) first.albumId else alb, alb, "${first.artist} · ${ts.size} 首", first.cover, ts)
        }
        val plItems = pls.map { pl ->
            FlowItem("pl:${pl.id}", "pl", pl.id, pl.name, "歌单 · ${two(pl.count)} 首", pl.cover)
        } + lls.map { l ->
            FlowItem("lpl:${l.id}", "lpl", l.id, l.name, "本地歌单 · ${two(l.tracks.size)} 首", l.tracks.firstOrNull { it.cover != null }?.cover, l.tracks)
        }
        val raw = when (filter) { 1 -> albumItems + recentAlbums; 2 -> plItems; 3 -> recentAlbums; else -> albumItems + plItems + recentAlbums }
        raw.distinctBy { it.key }
    }

    val pos = remember { Animatable(0f) }
    var targetIdx by remember { mutableIntStateOf(0) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    var flippedKey by remember { mutableStateOf<String?>(null) }
    var stageW by remember { mutableFloatStateOf(0f) }
    var stageH by remember { mutableFloatStateOf(0f) }
    // 纯 GPU 零重组倾斜状态：桌面鼠标与手机陀螺仪均在协程里平滑驱动 Animatable，仅在 graphicsLayer / draw 阶段读取，全程 0 重组
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    var tiltJob by remember { mutableStateOf<Job?>(null) }
    fun setTiltTarget(tx: Float, ty: Float) {
        if (AnimStyle.off) return
        tiltJob?.cancel()
        tiltJob = scope.launch {
            launch { tiltX.animateTo(tx, spring(0.88f, Anim.stiff(Spring.StiffnessLow))) }
            launch { tiltY.animateTo(ty, spring(0.88f, Anim.stiff(Spring.StiffnessLow))) }
        }
    }
    DisposableEffect(Unit) { TiltBus.onObserve?.invoke(true); onDispose { TiltBus.onObserve?.invoke(false) } }
    LaunchedEffect(Unit) {
        if (!Plat.isDesktop) {
            launch { TiltBus.x.collect { if (!AnimStyle.off) tiltX.snapTo(it) } }
            launch { TiltBus.y.collect { if (!AnimStyle.off) tiltY.snapTo(it) } }
        }
    }
    val ls = Screen.landscape
    val density = LocalDensity.current.density

    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && pos.value > items.lastIndex.toFloat()) { pos.snapTo(items.lastIndex.toFloat()); targetIdx = items.lastIndex }
        flippedKey = null
    }

    fun snapTo(idx: Int, initialVel: Float = 0f) {
        if (items.isEmpty()) return
        val clamped = idx.coerceIn(0, items.lastIndex)
        targetIdx = clamped
        if (flippedKey != null && items.getOrNull(clamped)?.key != flippedKey) flippedKey = null
        snapJob?.cancel()
        snapJob = scope.launch {
            if (AnimStyle.off) pos.snapTo(clamped.toFloat())
            else pos.animateTo(clamped.toFloat(), spring(dampingRatio = 0.90f, stiffness = Anim.stiff(185f)), initialVelocity = initialVel)
        }
    }

    val curIdx by remember(items.size) { derivedStateOf { if (items.isEmpty()) 0 else pos.value.roundToInt().coerceIn(0, items.lastIndex) } }
    val curItem = items.getOrNull(curIdx)

    // 翻转背面时按需拉取当前专辑/歌单的曲目列表
    var backTracks by remember { mutableStateOf<List<Track>?>(null) }
    var backLoading by remember { mutableStateOf(false) }
    LaunchedEffect(flippedKey) {
        val it = curItem
        if (flippedKey == null || it == null || it.key != flippedKey) { backTracks = null; backLoading = false; return@LaunchedEffect }
        if (it.presetTracks != null) { backTracks = it.presetTracks; backLoading = false; return@LaunchedEffect }
        backLoading = true
        backTracks = runCatching {
            when (it.kind) {
                "album" -> Ncm.album(it.id).second
                "pl" -> Ncm.playlistTracks(it.id)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        backLoading = false
    }

    val reflMask = remember { Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.30f), 0.18f to Color.Black.copy(alpha = 0.14f), 0.42f to Color.Black.copy(alpha = 0.03f), 0.68f to Color.Transparent, 1f to Color.Transparent) }

    Column(Modifier.fillMaxSize().then(if (ls) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)) {
        // 顶部筛选条（手机横屏时自带返回键并极致压缩高度，把全部视野留给 3D 封面）
        Row(Modifier.fillMaxWidth().padding(horizontal = if (ls) 12.dp else 18.dp, vertical = if (ls) 4.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ls) { Box(Modifier.size(32.dp).press { nav.back() }, contentAlignment = Alignment.Center) { NIcon(NIcons.back, 18.dp) }; Spacer(Modifier.width(6.dp)) }
            listOf("全部", "专辑", "歌单", "最近").forEachIndexed { idx, label ->
                val on = filter == idx
                Box(Modifier.padding(end = 6.dp).press { filter = idx; flippedKey = null; scope.launch { pos.snapTo(0f) } }
                    .background(if (on) p.p else p.k.copy(alpha = 0.08f)).padding(horizontal = if (ls) 10.dp else 12.dp, vertical = if (ls) 3.dp else 5.dp)) {
                    T(label, if (ls) 11 else 12, if (on) Color.White else p.k, 700)
                }
            }
            Spacer(Modifier.weight(1f))
            if (items.isNotEmpty()) T("${curIdx + 1} / ${items.size}", 11, p.s, 600, mono = true)
        }

        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                T("还没有可展示的封面，先播放几首歌或收藏专辑吧", 14, p.s)
            }
            return@Column
        }

        // 核心 3D 封面舞台（纯黑镜面地板 + 惯性甩动 + 滚轮 + 点击）
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()
            .onSizeChanged { stageW = it.width.toFloat(); stageH = it.height.toFloat() }
            .pointerInput(items.size) {
                val vt = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { vt.resetTracking(); snapJob?.cancel(); flippedKey = null },
                    onDragEnd = {
                        val vx = vt.calculateVelocity().x
                        val stepPx = (stageW * 0.22f).coerceIn(100f * density, 240f * density)
                        val velCards = (-vx / stepPx).coerceIn(-12f, 12f)
                        val flingCards = (velCards * 0.24f).coerceIn(-5f, 5f)
                        snapTo((pos.value + flingCards).roundToInt(), initialVel = velCards * 0.65f)
                    },
                    onDragCancel = { snapTo(pos.value.roundToInt()) },
                ) { change, dragAmount ->
                    change.consume()
                    vt.addPosition(change.uptimeMillis, change.position)
                    val stepPx = (stageW * 0.22f).coerceIn(100f * density, 240f * density)
                    val next = (pos.value - dragAmount / stepPx).coerceIn(-0.28f, items.lastIndex + 0.28f)
                    scope.launch { pos.snapTo(next) }
                }
            }
            .pointerInput(items.size) {
                awaitPointerEventScope {
                    var wheelAcc = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        when (ev.type) {
                            PointerEventType.Scroll -> {
                                val d = ev.changes.fold(0f) { acc, ch -> acc + ch.scrollDelta.y + ch.scrollDelta.x }
                                if (abs(d) > 0.01f) {
                                    ev.changes.forEach { it.consume() }
                                    wheelAcc += d
                                    val steps = wheelAcc.toInt()
                                    if (steps != 0) {
                                        wheelAcc -= steps
                                        snapTo(targetIdx + steps.coerceIn(-2, 2))
                                    } else if (abs(wheelAcc) >= 0.45f) {
                                        val s = if (wheelAcc > 0f) 1 else -1
                                        wheelAcc = 0f
                                        snapTo(targetIdx + s)
                                    }
                                }
                            }
                            PointerEventType.Move, PointerEventType.Enter -> {
                                ev.changes.firstOrNull()?.position?.let { pt ->
                                    if (stageW > 1f && stageH > 1f) setTiltTarget(((pt.x / stageW) * 2f - 1f).coerceIn(-1f, 1f), ((pt.y / stageH) * 2f - 1f).coerceIn(-1f, 1f))
                                }
                            }
                            PointerEventType.Exit -> setTiltTarget(0f, 0f)
                        }
                    }
                }
            }, contentAlignment = Alignment.Center) {

            val coverDp: Dp = with(LocalDensity.current) {
                val raw = if (ls) minOf(stageW * 0.30f, stageH * 0.64f).coerceIn(108f * density, 220f * density)
                else minOf(stageW * 0.44f, stageH * 0.56f).coerceIn(160f * density, 300f * density)
                raw.toDp()
            }
            val coverPx = with(LocalDensity.current) { coverDp.toPx() }
            val spineDp = (coverDp * 0.11f).coerceIn(14.dp, 28.dp)
            val rimDp = (coverDp * 0.018f).coerceIn(2.5.dp, 4.5.dp)
            val caseW = spineDp + coverDp + rimDp
            val caseH = coverDp + rimDp * 2
            // 经典 iOS 6 几何参数：中心 CD 盒无遮挡，两侧外缘靠前、内缘向内折向中心形成 V 型唱片架
            val centerGapPx = coverPx * 0.84f
            val stackStepPx = coverPx * 0.23f
            val reflH = caseH * 0.36f

            // 桌面左右各 5 张、移动端各 4 张，显著减少离屏合成与 GPU 填充率开销，稳定高帧率
            val wingSpan = if (Plat.isDesktop) 5 else 4
            val minI = (curIdx - wingSpan).coerceAtLeast(0)
            val maxI = (curIdx + wingSpan).coerceAtMost(items.lastIndex)
            for (i in minI..maxI) {
                val item = items[i]
                key(item.key) {
                val isCenter = i == curIdx
                val isFlipped = isCenter && flippedKey == item.key
                val flipDeg by animateFloatAsState(if (isFlipped) 180f else 0f, Motion.page(), label = "cfFlip")
                val z = 100f - abs(i - curIdx) * 10f

                Column(
                    Modifier.zIndex(z)
                        .graphicsLayer {
                            val d = i - pos.value
                            val ad = abs(d)
                            val s = kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat()
                            val wingAngle = -64f * sign(d) * s
                            val extraGap = (centerGapPx - stackStepPx) * kotlin.math.tanh((ad * 1.35f).toDouble()).toFloat()
                            val tx = d * stackStepPx + sign(d) * extraGap
                            val sc = 1f - 0.20f * s
                            val centerWeight = (1f - s).coerceIn(0f, 1f)
                            val tX = tiltX.value; val tY = tiltY.value
                            val rotYExtra = if (isFlipped) 0f else tX * (4.5f + 7.5f * centerWeight)
                            val rotXExtra = if (isFlipped) 0f else -tY * (3.2f + 5.8f * centerWeight)
                            translationX = tx + tX * (10f * density * (0.35f + 0.65f * centerWeight))
                            translationY = coverPx * (if (ls) 0.08f else 0.14f) + tY * (5f * density * centerWeight)
                            scaleX = sc; scaleY = sc
                            rotationY = (if (isCenter) wingAngle + flipDeg else wingAngle) + rotYExtra
                            rotationX = rotXExtra
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.368f)
                            cameraDistance = 12f * density
                            alpha = if (ad <= 3.2f) 1f else (1f - (ad - 3.2f) * 0.48f).coerceIn(0f, 1f)
                        }
                        .pointerInput(item.key, isCenter) {
                            detectTapGestures {
                                if (isCenter) flippedKey = if (flippedKey == item.key) null else item.key
                                else snapTo(i)
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 外包式透明亚克力 CD 塑料壳：左侧托盘脊柱 + 四周透明边框完全包裹在 1:1 封面外部，绝不遮挡专辑画作
                    Box(Modifier.size(caseW, caseH)) {
                        Box(Modifier.matchParentSize().graphicsLayer {
                            val d = i - pos.value; val s = kotlin.math.tanh((abs(d) * 1.45f).toDouble()).toFloat()
                            translationX = if (isFlipped) 0f else (sign(d) * s * 6.5.dp.toPx() - tiltX.value * 4.dp.toPx())
                            translationY = if (isFlipped) 0f else (1.5.dp.toPx() - tiltY.value * 2.dp.toPx())
                            alpha = (1f - flipDeg / 90f).coerceIn(0f, 1f)
                        }.background(Brush.verticalGradient(listOf(Color(0x5CE4EEFA), Color(0x308292A8), Color(0x46202632)))).border(1.dp, Color(0x5CFFFFFF)))
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x32FFFFFF), Color(0x14B8C8DC), Color(0x28101620)))).border(1.2.dp, Color(if (isCenter) 0x88FFFFFF else 0x4EFFFFFF))) {
                        if (flipDeg <= 90f) {
                            Box(Modifier.padding(start = spineDp, top = rimDp, end = rimDp, bottom = rimDp)) {
                                Cover(item.cover, coverDp, item.title, border = false, decodeSize = if (isCenter) 512.dp else 256.dp)
                            }
                            // 外壳透明塑料细节（单 Pass Canvas）：左侧独立脊柱条 + 合页卡扣 + 外框折边与内缘定位扣 + 极淡表层斜向反光
                            Box(Modifier.matchParentSize().drawWithContent {
                                val w = size.width; val h = size.height; val spW = spineDp.toPx(); val rm = rimDp.toPx(); val d = i - pos.value; val ad = abs(d)
                                drawRect(Brush.horizontalGradient(listOf(Color(0x3EFFFFFF), Color(0x16FFFFFF), Color(0x2C000000), Color(0x44FFFFFF)), 0f, spW), size = Size(spW, h))
                                drawRect(Color(0x52000000), Offset(spW - 1.5f, 0f), Size(1.5f, h)); drawRect(Color(0x52FFFFFF), Offset(spW, 0f), Size(1.2f, h))
                                for (r in 1..4) drawRect(Color(0x28FFFFFF), Offset(spW * r / 5f, rm), Size(1f, h - rm * 2f))
                                listOf(0.14f, 0.86f).forEach { fy -> val hy = h * fy; drawRect(Color(0x54000000), Offset(2f, hy - 3f), Size(spW - 4.5f, 3f)); drawRect(Color(0x6EFFFFFF), Offset(2f, hy), Size(spW - 4.5f, 2f)) }
                                listOf(0.28f, 0.72f).forEach { fx -> val tx = spW + (w - spW - rm) * fx; drawRect(Color(0x55FFFFFF), Offset(tx - 6f, 0f), Size(12f, rm)); drawRect(Color(0x44FFFFFF), Offset(tx - 6f, h - rm), Size(12f, rm)) }
                                if (ad < 2.6f) {
                                    val cw = (1f - kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat()).coerceIn(0f, 1f)
                                    val sh = (-tiltX.value * 0.28f - d.coerceIn(-1f, 1f) * 0.16f) * w
                                    drawRect(Brush.linearGradient(0f to Color.Transparent, 0.40f to Color.Transparent, 0.50f to Color.White.copy(alpha = 0.04f + 0.08f * cw), 0.56f to Color.White.copy(alpha = 0.02f + 0.03f * cw), 0.65f to Color.Transparent, 1f to Color.Transparent,
                                        start = Offset(sh - w * 0.15f, -tiltY.value * h * 0.2f), end = Offset(sh + w * 1.05f, h * 0.85f - tiltY.value * h * 0.2f)))
                                }
                                drawRect(Color(0x62FFFFFF), size = Size(w, 1.8f)); drawRect(Color(0x4CFFFFFF), Offset(w - 1.8f, 0f), Size(1.8f, h)); drawRect(Color(0x4C000000), Offset(0f, h - 2f), Size(w, 2f))
                            })
                            Box(Modifier.matchParentSize().graphicsLayer { val ad = abs(i - pos.value); val s = kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat(); alpha = (0.14f * s + 0.06f * (ad - 1f).coerceAtLeast(0f)).coerceAtMost(0.45f) }.background(Color.Black))
                        } else CoverBackTracklist(item, backTracks, backLoading, onClose = { flippedKey = null }, onOpenDetail = {
                            when (item.kind) { "album" -> nav.push(Page.Album(item.id, item.title, item.cover)); "pl" -> nav.push(Page.Detail("pl", item.id, item.title, item.cover)); "lpl" -> nav.push(Page.Detail("lpl", item.id, item.title)); else -> item.presetTracks?.let { Engine.playList(it, 0); nav.player = true } }
                        })
                        }
                    }
                    // 轻量高效底部镜面倒影：精确对齐外包 CD 盒尺寸与左脊柱
                    if (abs(i - curIdx) <= 3) Box(Modifier.size(caseW, reflH).clipToBounds()
                        .graphicsLayer { alpha = (1f - flipDeg / 90f).coerceIn(0f, 1f); compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(brush = reflMask, topLeft = Offset(-4f, -4f), size = Size(size.width + 8f, size.height + 8f), blendMode = BlendMode.DstIn)
                        }) {
                        Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(caseH).graphicsLayer { scaleY = -1f }.background(Color(0x1CFFFFFF)).border(1.dp, Color(0x38FFFFFF))) {
                            Box(Modifier.width(spineDp).fillMaxHeight().background(Color(0x28FFFFFF)))
                            Box(Modifier.padding(start = spineDp, top = rimDp, end = rimDp, bottom = rimDp)) { Cover(item.cover, coverDp, item.title, border = false, decodeSize = 200.dp) }
                            Box(Modifier.matchParentSize().graphicsLayer { val ad = abs(i - pos.value); val s = kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat(); alpha = (0.14f * s + 0.06f * (ad - 1f).coerceAtLeast(0f)).coerceAtMost(0.45f) }.background(Color.Black))
                        }
                    } else Spacer(Modifier.height(reflH))
                }
                }
            }
        }

        // 底部专辑信息（手机横屏单行紧凑排布，不挡封面）+ 操作 + 底部快滑条
        if (curItem != null) {
            fun playCurrent() {
                if (curItem.presetTracks != null && curItem.presetTracks.isNotEmpty()) { Engine.playList(curItem.presetTracks, 0); nav.player = true }
                else scope.launch {
                    val ts = runCatching { when (curItem.kind) { "album" -> Ncm.album(curItem.id).second; "pl" -> Ncm.playlistTracks(curItem.id); else -> emptyList() } }.getOrDefault(emptyList())
                    if (ts.isNotEmpty()) { Engine.playList(ts, 0); nav.player = true } else nav.toast = "暂无可播曲目"
                }
            }
            if (ls) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { T(curItem.title, 14, p.k, 800, lines = 1); T(curItem.sub, 11, p.s, lines = 1) }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.press { playCurrent() }.background(p.p).padding(horizontal = 12.dp, vertical = 5.dp)) { T("播放", 11, Color.White, 700) }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.press { flippedKey = if (flippedKey == curItem.key) null else curItem.key }.nbf(1.dp, p.k).padding(horizontal = 10.dp, vertical = 5.dp)) { T(if (flippedKey == curItem.key) "封面" else "曲目", 11, p.k, 600) }
            } else Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(curItem, contentKey = { it.key }, transitionSpec = { fadeIn(Motion.quick()) togetherWith fadeOut(Motion.quick()) }, label = "cfMeta") { it ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        T(it.title, 18, p.k, 800, lines = 1, align = TextAlign.Center); Spacer(Modifier.height(2.dp)); T(it.sub, 12, p.s, 500, lines = 1, align = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.press { playCurrent() }.background(p.p).padding(horizontal = 16.dp, vertical = 7.dp)) { T("立即播放", 12, Color.White, 700) }
                    Box(Modifier.press { flippedKey = if (flippedKey == curItem.key) null else curItem.key }.nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 7.dp)) { T(if (flippedKey == curItem.key) "翻回封面" else "翻转曲目", 12, p.k, 600) }
                    if (curItem.kind != "tracks") Box(Modifier.press {
                        when (curItem.kind) { "album" -> nav.push(Page.Album(curItem.id, curItem.title, curItem.cover)); "pl" -> nav.push(Page.Detail("pl", curItem.id, curItem.title, curItem.cover)); "lpl" -> nav.push(Page.Detail("lpl", curItem.id, curItem.title)) }
                    }.nbf(1.dp, p.l2).padding(horizontal = 14.dp, vertical = 7.dp)) { T("进入详情", 12, p.k, 600) }
                }
                if (items.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    NSlider(pos.value.coerceIn(0f, items.lastIndex.toFloat()), 0f..items.lastIndex.toFloat(), onDone = { snapTo(pos.value.roundToInt()) }) { v -> snapJob?.cancel(); flippedKey = null; scope.launch { pos.snapTo(v) } }
                }
            }
        }
    }
}

@Composable private fun CoverBackTracklist(item: FlowItem, tracks: List<Track>?, loading: Boolean, onClose: () -> Unit, onOpenDetail: () -> Unit) {
    val p = c
    Column(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.background(p.sheet.copy(alpha = 0.96f)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T(item.title, 13, p.k, 800, lines = 1); T(item.sub, 10, p.s, lines = 1) }
            Box(Modifier.press(onClick = onClose).nbf(1.dp, p.l2).padding(horizontal = 8.dp, vertical = 4.dp)) { T("返回", 10, p.k, 700) }
        }
        Spacer(Modifier.height(8.dp)); Box(Modifier.fillMaxWidth().height(1.dp).background(p.l1))
        when {
            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { T("LOADING…", 11, p.s, mono = true) }
            tracks.isNullOrEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.press(onClick = onOpenDetail).background(p.p).padding(horizontal = 12.dp, vertical = 6.dp)) { T("查看完整详情", 11, Color.White, 700) }
            }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                itemsIndexed(tracks) { idx, t ->
                    Row(Modifier.fillMaxWidth().press { Engine.playList(tracks, idx) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(two(idx + 1), 10, p.s, mono = true, modifier = Modifier.width(22.dp))
                        T(t.title, 12, p.k, 600, lines = 1, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
