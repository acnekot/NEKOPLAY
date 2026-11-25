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
    var pointerX by remember { mutableFloatStateOf(0f) } // -1..1 相对舞台中心
    var pointerY by remember { mutableFloatStateOf(0f) }
    val tiltX by animateFloatAsState(if (AnimStyle.off) 0f else pointerX, spring(0.88f, Anim.stiff(Spring.StiffnessLow)), label = "cfTx")
    val tiltY by animateFloatAsState(if (AnimStyle.off) 0f else pointerY, spring(0.88f, Anim.stiff(Spring.StiffnessLow)), label = "cfTy")
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

    val reflMask = remember {
        Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.34f), 0.48f to Color.Black.copy(alpha = 0.08f), 1f to Color.Transparent)
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部筛选条（完全融入当前主题与毛玻璃背景）
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("全部", "专辑", "歌单", "最近").forEachIndexed { idx, label ->
                val on = filter == idx
                Box(Modifier.padding(end = 8.dp).press { filter = idx; flippedKey = null; scope.launch { pos.snapTo(0f) } }
                    .background(if (on) p.p else p.k.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 5.dp)) {
                    T(label, 12, if (on) Color.White else p.k, 700)
                }
            }
            Spacer(Modifier.weight(1f))
            if (items.isNotEmpty()) {
                T("${curIdx + 1} / ${items.size}", 12, p.s, 600, mono = true)
            }
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
                                    if (stageW > 1f && stageH > 1f) { pointerX = ((pt.x / stageW) * 2f - 1f).coerceIn(-1f, 1f); pointerY = ((pt.y / stageH) * 2f - 1f).coerceIn(-1f, 1f) }
                                }
                            }
                            PointerEventType.Exit -> { pointerX = 0f; pointerY = 0f }
                        }
                    }
                }
            }, contentAlignment = Alignment.Center) {

            val coverDp: Dp = with(LocalDensity.current) { (minOf(stageW * 0.44f, stageH * 0.56f).coerceIn(160f * density, 300f * density)).toDp() }
            val coverPx = with(LocalDensity.current) { coverDp.toPx() }
            // 经典 iOS 6 几何参数：中心封面无遮挡，两侧外缘靠前、内缘向内折向中心形成 V 型唱片架
            val centerGapPx = coverPx * 0.78f
            val stackStepPx = coverPx * 0.22f
            val reflH = coverDp * 0.36f

            // 左右各渲染 6 张封面，使用 key(item.key) 稳定节点身份并在 graphicsLayer 内读 pos.value，避免切卡闪烁与整树重组
            val minI = (curIdx - 6).coerceAtLeast(0)
            val maxI = (curIdx + 6).coerceAtMost(items.lastIndex)
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
                            // C1 连续平滑曲线：消除 |d|=1 处速度突变造成的生硬顿挫
                            val s = kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat()
                            val wingAngle = -64f * sign(d) * s
                            val extraGap = (centerGapPx - stackStepPx) * kotlin.math.tanh((ad * 1.35f).toDouble()).toFloat()
                            val tx = d * stackStepPx + sign(d) * extraGap
                            val sc = 1f - 0.20f * s
                            val centerWeight = (1f - s).coerceIn(0f, 1f)
                            val rotYExtra = if (isFlipped) 0f else tiltX * (4.5f + 7.5f * centerWeight)
                            val rotXExtra = if (isFlipped) 0f else -tiltY * (3.2f + 5.8f * centerWeight)
                            translationX = tx + tiltX * (10f * density * (0.35f + 0.65f * centerWeight))
                            translationY = coverPx * 0.14f + tiltY * (5f * density * centerWeight)
                            scaleX = sc; scaleY = sc
                            rotationY = (if (isCenter) wingAngle + flipDeg else wingAngle) + rotYExtra
                            rotationX = rotXExtra
                            cameraDistance = 12f * density
                            alpha = if (ad <= 3.6f) 1f else (1f - (ad - 3.6f) * 0.42f).coerceIn(0f, 1f)
                        }
                        .pointerInput(item.key, isCenter) {
                            detectTapGestures {
                                if (isCenter) flippedKey = if (flippedKey == item.key) null else item.key
                                else snapTo(i)
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 封面主体（翻转超过 90° 时切为背面曲目清单）
                    Box(Modifier.size(coverDp).border(1.dp, p.k.copy(alpha = if (isCenter) 0.22f else 0.10f)).background(p.sheet)) {
                        if (flipDeg <= 90f) {
                            Cover(item.cover, coverDp, item.title, border = false, decodeSize = 512.dp)
                            if (isCenter && (abs(tiltX) > 0.02f || abs(tiltY) > 0.02f)) {
                                // 跟随鼠标移动的柔和镜面高光，增强 3D 立体转动质感
                                Box(Modifier.matchParentSize().drawWithContent {
                                    val gx = size.width * (0.5f - tiltX * 0.35f)
                                    val gy = size.height * (0.5f - tiltY * 0.35f)
                                    drawRect(Brush.radialGradient(
                                        listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                                        center = androidx.compose.ui.geometry.Offset(gx, gy),
                                        radius = size.width * 0.85f,
                                    ))
                                })
                            }
                            Box(Modifier.matchParentSize().graphicsLayer {
                                val ad = abs(i - pos.value)
                                val s = kotlin.math.tanh((ad * 1.45f).toDouble()).toFloat()
                                alpha = (0.14f * s + 0.06f * (ad - 1f).coerceAtLeast(0f)).coerceAtMost(0.45f)
                            }.background(Color.Black))
                        } else {
                            CoverBackTracklist(item, backTracks, backLoading, onClose = { flippedKey = null }, onOpenDetail = {
                                when (item.kind) {
                                    "album" -> nav.push(Page.Album(item.id, item.title, item.cover))
                                    "pl" -> nav.push(Page.Detail("pl", item.id, item.title, item.cover))
                                    "lpl" -> nav.push(Page.Detail("lpl", item.id, item.title))
                                    else -> item.presetTracks?.let { Engine.playList(it, 0); nav.player = true }
                                }
                            })
                        }
                    }
                    Spacer(Modifier.height(1.dp))
                    // 精确 iOS 6 底部镜面倒影：外层未翻转容器做顶部→底部渐隐，内层原位 scaleY = -1f 精准镜像封面底边
                    Box(Modifier.size(coverDp, reflH).clipToBounds()
                        .graphicsLayer {
                            alpha = (1f - flipDeg / 90f).coerceIn(0f, 1f)
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            drawRect(brush = reflMask, blendMode = BlendMode.DstIn)
                        }) {
                        Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { scaleY = -1f }) {
                            Cover(item.cover, coverDp, item.title, border = false, decodeSize = 256.dp)
                        }
                    }
                }
                }
            }
        }

        // 底部专辑信息（柔和淡入淡出）+ 操作 + 底部快滑条
        if (curItem != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(curItem, contentKey = { it.key }, transitionSpec = {
                    fadeIn(Motion.quick()) togetherWith fadeOut(Motion.quick())
                }, label = "cfMeta") { it ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        T(it.title, 18, p.k, 800, lines = 1, align = TextAlign.Center)
                        Spacer(Modifier.height(2.dp))
                        T(it.sub, 12, p.s, 500, lines = 1, align = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.press {
                        if (curItem.presetTracks != null && curItem.presetTracks.isNotEmpty()) {
                            Engine.playList(curItem.presetTracks, 0); nav.player = true
                        } else {
                            scope.launch {
                                val ts = runCatching {
                                    when (curItem.kind) {
                                        "album" -> Ncm.album(curItem.id).second
                                        "pl" -> Ncm.playlistTracks(curItem.id)
                                        else -> emptyList()
                                    }
                                }.getOrDefault(emptyList())
                                if (ts.isNotEmpty()) { Engine.playList(ts, 0); nav.player = true } else nav.toast = "暂无可播曲目"
                            }
                        }
                    }.background(p.p).padding(horizontal = 16.dp, vertical = 7.dp)) {
                        T("立即播放", 12, Color.White, 700)
                    }
                    Box(Modifier.press { flippedKey = if (flippedKey == curItem.key) null else curItem.key }
                        .nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 7.dp)) {
                        T(if (flippedKey == curItem.key) "翻回封面" else "翻转曲目", 12, p.k, 600)
                    }
                    if (curItem.kind != "tracks") {
                        Box(Modifier.press {
                            when (curItem.kind) {
                                "album" -> nav.push(Page.Album(curItem.id, curItem.title, curItem.cover))
                                "pl" -> nav.push(Page.Detail("pl", curItem.id, curItem.title, curItem.cover))
                                "lpl" -> nav.push(Page.Detail("lpl", curItem.id, curItem.title))
                            }
                        }.nbf(1.dp, p.l2).padding(horizontal = 14.dp, vertical = 7.dp)) {
                            T("进入详情", 12, p.k, 600)
                        }
                    }
                }
                if (items.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    NSlider(pos.value.coerceIn(0f, items.lastIndex.toFloat()), 0f..items.lastIndex.toFloat(),
                        onDone = { snapTo(pos.value.roundToInt()) }) { v ->
                        snapJob?.cancel(); flippedKey = null
                        scope.launch { pos.snapTo(v) }
                    }
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
