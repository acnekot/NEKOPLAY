package cc.catsuki.nekoplay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.TransformOrigin
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

/** 经典 iOS 6 Apple Music Cover Flow 3D 封面墙：外包式透明 CD 塑料壳 + 镜面倒影 + 惯性甩动 + 点击铰链翻开 CD 盒看完整曲目 */
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
        val recentAlbums = rec.distinctBy { it.id }.groupBy { it.album.ifBlank { it.title } }.entries.mapNotNull { (alb, ts) ->
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
    var expandedKey by remember { mutableStateOf<String?>(null) }
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
        expandedKey = null
    }

    fun snapTo(idx: Int, initialVel: Float = 0f) {
        if (items.isEmpty()) return
        val clamped = idx.coerceIn(0, items.lastIndex)
        targetIdx = clamped
        if (expandedKey != null && items.getOrNull(clamped)?.key != expandedKey) expandedKey = null
        snapJob?.cancel()
        snapJob = scope.launch {
            if (AnimStyle.off) pos.snapTo(clamped.toFloat())
            else pos.animateTo(clamped.toFloat(), spring(dampingRatio = 0.90f, stiffness = Anim.stiff(185f)), initialVelocity = initialVel)
        }
    }

    val curIdx by remember(items.size) { derivedStateOf { if (items.isEmpty()) 0 else pos.value.roundToInt().coerceIn(0, items.lastIndex) } }
    val curItem = items.getOrNull(curIdx)
    val cfg by produceState(CoverFlowCfg(), Plugins.rev) { value = CoverFlowCfg.from(Plugins.featureConfig("coverflow")) }
    // 交互逻辑分流：桌面宽屏与手机横屏支持 3D 翻开 CD 盒展开曲目；手机竖屏默认直进专辑/歌单详情页（可由插件 config() 热更新）
    val canExpand = Screen.wide || ls || cfg.portraitExpand
    LaunchedEffect(canExpand) { if (!canExpand) expandedKey = null }
    val isExpanded = canExpand && expandedKey != null && curItem?.key == expandedKey
    val expandState = animateFloatAsState(if (isExpanded) 1f else 0f, if (AnimStyle.off) snap() else spring(dampingRatio = 0.85f, stiffness = Anim.stiff(88f)), label = "cfExp")
    val showTray by remember { derivedStateOf { expandState.value > 0.02f } }
    val lidPastHalf by remember { derivedStateOf { smoothStep(0.02f, 0.72f, expandState.value) >= 0.5f } }

    fun openDetail(it: FlowItem) = when (it.kind) {
        "album" -> { SharedHero.capture("alb:${it.id}", it.cover, it.title); nav.push(Page.Album(it.id, it.title, it.cover)) }
        "pl" -> { SharedHero.capture("pl:${it.id}", it.cover, it.title); nav.push(Page.Detail("pl", it.id, it.title, it.cover)) }
        "lpl" -> { SharedHero.capture("pl:${it.id}", it.cover, it.title); nav.push(Page.Detail("lpl", it.id, it.title)) }
        else -> it.presetTracks?.let { ts -> Engine.playList(ts, 0); nav.player = true }
    }
    fun onCenterTap(it: FlowItem) {
        if (canExpand) expandedKey = if (expandedKey == it.key) null else it.key else openDetail(it)
    }

    // 点开封面放大时按需拉取当前专辑/歌单的完整曲目列表
    var sideTracks by remember { mutableStateOf<List<Track>?>(null) }
    var sideLoading by remember { mutableStateOf(false) }
    LaunchedEffect(expandedKey) {
        val it = curItem
        if (expandedKey == null || it == null || it.key != expandedKey) { sideTracks = null; sideLoading = false; return@LaunchedEffect }
        if (it.presetTracks != null) { sideTracks = it.presetTracks; sideLoading = false; return@LaunchedEffect }
        sideLoading = true
        sideTracks = runCatching {
            when (it.kind) {
                "album" -> Ncm.album(it.id).second
                "pl" -> Ncm.playlistTracks(it.id)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        sideLoading = false
    }

    val reflMask = remember(cfg.reflectionAlpha) { val a = cfg.reflectionAlpha; Brush.verticalGradient(0f to Color.Black.copy(alpha = a), 0.18f to Color.Black.copy(alpha = a * 0.46f), 0.42f to Color.Black.copy(alpha = a * 0.10f), 0.68f to Color.Transparent, 1f to Color.Transparent) }

    Column(Modifier.fillMaxSize().then(if (ls) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)) {
        // 顶部筛选条（手机横屏时自带返回键并极致压缩高度，把全部视野留给 3D 封面）
        Row(Modifier.fillMaxWidth().padding(horizontal = if (ls) 12.dp else 18.dp, vertical = if (ls) 4.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ls) { Box(Modifier.size(32.dp).press { nav.back() }, contentAlignment = Alignment.Center) { NIcon(NIcons.back, 18.dp) }; Spacer(Modifier.width(6.dp)) }
            listOf("全部", "专辑", "歌单", "最近").forEachIndexed { idx, label ->
                val on = filter == idx
                Box(Modifier.padding(end = 6.dp).press { filter = idx; expandedKey = null; scope.launch { pos.snapTo(0f) } }
                    .background(if (on) p.p else p.k.copy(alpha = 0.08f)).padding(horizontal = if (ls) 10.dp else 12.dp, vertical = if (ls) 3.dp else 5.dp)) {
                    T(label, if (ls) 11 else 12, if (on) Color.White else p.k, 700)
                }
            }
            Spacer(Modifier.weight(1f))
            if (Screen.wide && cfg.desktopSidebarToggle) Box(Modifier.padding(end = 10.dp).press { nav.hideSidebar = !nav.hideSidebar }
                .background(if (nav.hideSidebar) p.p else p.k.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                T(if (nav.hideSidebar) "展开侧栏" else "收起侧栏", 11, if (nav.hideSidebar) Color.White else p.k, 700)
            }
            if (items.isNotEmpty()) T("${curIdx + 1} / ${items.size}", 11, p.s, 600, mono = true)
        }

        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                T("还没有可展示的封面，先播放几首歌或收藏专辑吧", 14, p.s)
            }
            return@Column
        }

        // 核心 3D 封面舞台（纯黑镜面地板 + 惯性甩动 + 滚轮 + 点开放大看完整曲目）
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()
            .onSizeChanged { stageW = it.width.toFloat(); stageH = it.height.toFloat() }
            .pointerInput(items.size) {
                val vt = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { vt.resetTracking(); snapJob?.cancel(); expandedKey = null },
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
                            PointerEventType.Scroll -> if (expandedKey == null) {
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
            val spineDp = (coverDp * cfg.spineRatio).coerceIn(12.dp, 32.dp)
            val rimDp = (coverDp * 0.018f).coerceIn(2.5.dp, 4.5.dp)
            val caseW = spineDp + coverDp + rimDp
            val caseH = coverDp + rimDp * 2
            val caseWPx = with(LocalDensity.current) { caseW.toPx() }
            val caseHPx = with(LocalDensity.current) { caseH.toPx() }
            val maxOpenScale = if (caseWPx > 1f && caseHPx > 1f) minOf(stageW * 0.95f / (caseWPx * 2f), stageH * 0.95f / caseHPx).coerceIn(0.80f, cfg.maxOpenScale) else 1.40f
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
                val z = 100f - abs(i - curIdx) * 10f

                Column(
                    Modifier.zIndex(z)
                        .graphicsLayer {
                            val d = i - pos.value
                            val ad = abs(d)
                            val s = kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat()
                            val wingAngle = -cfg.wingAngle * sign(d) * s
                            val extraGap = (centerGapPx - stackStepPx) * kotlin.math.tanh((ad * 1.35f).toDouble()).toFloat()
                            val tx = d * stackStepPx + sign(d) * extraGap
                            val ep = expandState.value.coerceIn(0f, 1.05f)
                            // 分段编排：先优雅 3D 旋身翻盖，再平滑舒展放大居中（旋转 → 放大）
                            val zoomP = smoothStep(0.28f, 1.0f, ep)
                            val shiftP = smoothStep(0.08f, 0.92f, ep)
                            val swirl = kotlin.math.sin((ep.coerceIn(0f, 1f) * kotlin.math.PI)).toFloat()
                            val sc = (1f - 0.20f * s) * (if (isCenter) 1f + (maxOpenScale - 1f) * zoomP else 1f)
                            val centerWeight = (1f - s).coerceIn(0f, 1f)
                            val tX = tiltX.value; val tY = tiltY.value
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
                    // 实体 CD 塑料盒：外侧无缝挤出透明亚克力侧边厚度 + 右侧托盘 + 沿左铰链 180° 翻开的透明前盖
                    val lidP = { if (isCenter) smoothStep(0.02f, 0.72f, expandState.value) else 0f }
                    val heroKey = if (isCenter) when (item.kind) { "album" -> "alb:${item.id}"; "pl", "lpl" -> "pl:${item.id}"; else -> null } else null
                    Box(Modifier.size(caseW, caseH).heroAnchor(heroKey, item.cover, item.title, border = false).cdThicknessEdge({ i - pos.value }, { tiltX.value }, { tiltY.value }, cfg.thicknessDp) { if (isCenter) (1f - smoothStep(0f, 0.45f, expandState.value)) else 1f }) {

                        // 右侧 CD 托盘层（随前盖沿左铰链翻开自然显露，炫彩光盘随开合微转）
                        if (isCenter && showTray) {
                            Box(Modifier.matchParentSize().graphicsLayer { alpha = smoothStep(0.10f, 0.58f, expandState.value) }) {
                                CdTrayTracklist(item, sideTracks, sideLoading, compact = ls || stageW < 640f * density, openProg = { expandState.value }, onClose = { expandedKey = null }, onOpenDetail = { openDetail(item) })
                            }
                        }

                        // 透明亚克力前盖：以左缘铰链为轴翻开 -180°（手机竖屏点击直进专辑页，横屏/桌面点击翻开 CD 盒）
                        Box(Modifier.matchParentSize()
                            .graphicsLayer { rotationY = -180f * lidP(); transformOrigin = TransformOrigin(0f, 0.5f); cameraDistance = 18f * density }
                            .pointerInput(item.key, isCenter, canExpand) {
                                detectTapGestures {
                                    if (isCenter) onCenterTap(item)
                                    else if (expandedKey == null) snapTo(i)
                                }
                            }) {
                            val sweep = { kotlin.math.sin((lidP() * kotlin.math.PI)).toFloat() }
                            if (!isCenter || !lidPastHalf) {
                                CdCaseFrontLid(item, coverDp, spineDp, rimDp, isCenter, { i - pos.value }, { tiltX.value }, { tiltY.value }, sweep)
                            } else {
                                CdCaseInnerLid(item, coverDp, spineDp, rimDp, sweep, onClose = { expandedKey = null })
                            }
                        }
                    }
                    // 轻量高效底部镜面倒影：精确对齐外包 CD 盒尺寸与左脊柱（翻开 CD 盒时平滑淡出）
                    if (abs(i - curIdx) <= 3) Box(Modifier.size(caseW, reflH).clipToBounds()
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
                            Box(Modifier.matchParentSize().graphicsLayer { val ad = abs(i - pos.value); val s = kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat(); alpha = (0.14f * s + 0.06f * (ad - 1f).coerceAtLeast(0f)).coerceAtMost(0.45f) }.background(Color.Black))
                        }
                    } else Spacer(Modifier.height(reflH))
                }
                }
            }
        }

        // 底部专辑信息（手机横屏单行紧凑排布，不挡封面）+ 操作 + 底部快滑条
        if (curItem != null) {
            CoverFlowFooter(
                curItem = curItem,
                ls = ls,
                canExpand = canExpand,
                isExpanded = isExpanded,
                itemCount = items.size,
                sliderValue = { pos.value },
                onPlay = {
                    if (curItem.presetTracks != null && curItem.presetTracks.isNotEmpty()) { Engine.playList(curItem.presetTracks, 0); nav.player = true }
                    else scope.launch {
                        val ts = runCatching { when (curItem.kind) { "album" -> Ncm.album(curItem.id).second; "pl" -> Ncm.playlistTracks(curItem.id); else -> emptyList() } }.getOrDefault(emptyList())
                        if (ts.isNotEmpty()) { Engine.playList(ts, 0); nav.player = true } else nav.toast = "暂无可播曲目"
                    }
                },
                onToggleExpand = { onCenterTap(curItem) },
                onToggleLandscapeExpand = { expandedKey = if (expandedKey == curItem.key) null else curItem.key },
                onOpenDetail = { openDetail(curItem) },
                onSlide = { v -> snapJob?.cancel(); expandedKey = null; scope.launch { pos.snapTo(v) } },
                onSlideEnd = { snapTo(pos.value.roundToInt()) },
            )
        }
    }
}
