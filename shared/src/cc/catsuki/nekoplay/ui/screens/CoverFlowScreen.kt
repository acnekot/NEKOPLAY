package cc.catsuki.nekoplay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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
    // 纯 GPU 零重组实时倾斜状态：桌面鼠标移动零延迟 1:1 实时跟手（0 协程/移动事件），离场时轻柔回正；移动端直连陀螺仪
    val tiltX = remember { mutableFloatStateOf(0f) }
    val tiltY = remember { mutableFloatStateOf(0f) }
    var tiltExitJob by remember { mutableStateOf<Job?>(null) }
    fun setTiltImmediate(tx: Float, ty: Float) {
        if (AnimStyle.off) return
        tiltExitJob?.cancel(); tiltExitJob = null
        tiltX.floatValue = tx
        tiltY.floatValue = ty
    }
    fun resetTiltSmooth() {
        if (AnimStyle.off) { tiltX.floatValue = 0f; tiltY.floatValue = 0f; return }
        tiltExitJob?.cancel()
        val sx = tiltX.floatValue; val sy = tiltY.floatValue
        if (abs(sx) < 0.002f && abs(sy) < 0.002f) { tiltX.floatValue = 0f; tiltY.floatValue = 0f; return }
        tiltExitJob = scope.launch {
            val anim = Animatable(1f)
            anim.animateTo(0f, spring(0.86f, Anim.stiff(480f))) {
                tiltX.floatValue = sx * value
                tiltY.floatValue = sy * value
            }
        }
    }
    DisposableEffect(Unit) { TiltBus.onObserve?.invoke(true); onDispose { TiltBus.onObserve?.invoke(false) } }
    LaunchedEffect(Unit) {
        if (!Plat.isDesktop) {
            launch { TiltBus.x.collect { if (!AnimStyle.off) tiltX.floatValue = it } }
            launch { TiltBus.y.collect { if (!AnimStyle.off) tiltY.floatValue = it } }
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
                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
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
                                    if (stageW > 1f && stageH > 1f) setTiltImmediate(((pt.x / stageW) * 2f - 1f).coerceIn(-1f, 1f), ((pt.y / stageH) * 2f - 1f).coerceIn(-1f, 1f))
                                }
                            }
                            PointerEventType.Exit -> resetTiltSmooth()
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
                    CoverFlowCard(
                        index = i,
                        curIdx = curIdx,
                        item = item,
                        posValue = { pos.value },
                        expandState = expandState,
                        tiltXValue = { tiltX.floatValue },
                        tiltYValue = { tiltY.floatValue },
                        cfg = cfg,
                        ls = ls,
                        density = density,
                        stageW = stageW,
                        coverDp = coverDp,
                        coverPx = coverPx,
                        spineDp = spineDp,
                        rimDp = rimDp,
                        caseW = caseW,
                        caseH = caseH,
                        caseWPx = caseWPx,
                        caseHPx = caseHPx,
                        maxOpenScale = maxOpenScale,
                        centerGapPx = centerGapPx,
                        stackStepPx = stackStepPx,
                        reflH = reflH,
                        reflMask = reflMask,
                        canExpand = canExpand,
                        showTray = showTray,
                        lidPastHalf = lidPastHalf,
                        sideTracks = sideTracks,
                        sideLoading = sideLoading,
                        onCloseExpand = { expandedKey = null },
                        onOpenDetail = ::openDetail,
                        onTap = { if (isCenter) onCenterTap(item) else if (expandedKey == null) snapTo(i) },
                    )
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
