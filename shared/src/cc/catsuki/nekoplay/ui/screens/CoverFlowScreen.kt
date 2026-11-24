package cc.catsuki.nekoplay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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

private data class FlowItem(
    val key: String,
    val kind: String, // "album" | "pl" | "lpl" | "tracks"
    val id: String,
    val title: String,
    val sub: String,
    val cover: String?,
    val presetTracks: List<Track>? = null,
)

/** 经典 iOS 6 Cover Flow 风格 3D 封面墙：3D 透视斜切 + 镜面倒影 + 点击中心 180° 翻转看曲目 + 底部快滑条 */
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
        val raw = when (filter) {
            1 -> albumItems + recentAlbums
            2 -> plItems
            3 -> recentAlbums
            else -> albumItems + plItems + recentAlbums
        }
        raw.distinctBy { it.key }
    }

    val pos = remember { Animatable(0f) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    var flippedKey by remember { mutableStateOf<String?>(null) }
    var stageW by remember { mutableFloatStateOf(0f) }
    var stageH by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density

    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && pos.value > items.lastIndex.toFloat()) pos.snapTo(items.lastIndex.toFloat())
        flippedKey = null
    }

    fun snapTo(targetIdx: Int) {
        if (items.isEmpty()) return
        val clamped = targetIdx.coerceIn(0, items.lastIndex).toFloat()
        if (flippedKey != null && items.getOrNull(clamped.roundToInt())?.key != flippedKey) flippedKey = null
        snapJob?.cancel()
        snapJob = scope.launch { pos.animateTo(clamped, Motion.hero()) }
    }

    val curIdx = if (items.isEmpty()) 0 else pos.value.roundToInt().coerceIn(0, items.lastIndex)
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

    val stageBg = remember {
        Brush.verticalGradient(listOf(Color(0xFF0A0C10), Color(0xFF141821), Color(0xFF090B0E)))
    }
    val reflMask = remember {
        Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.36f), 0.52f to Color.Black.copy(alpha = 0.08f), 1f to Color.Transparent)
    }

    Column(Modifier.fillMaxSize().background(stageBg)) {
        // 顶部筛选条（全部 / 专辑 / 歌单 / 最近）
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("全部", "专辑", "歌单", "最近").forEachIndexed { idx, label ->
                val on = filter == idx
                Box(Modifier.padding(end = 8.dp).press { filter = idx; flippedKey = null; scope.launch { pos.snapTo(0f) } }
                    .background(if (on) p.p else Color(0x1FFFFFFF))
                    .padding(horizontal = 12.dp, vertical = 5.dp)) {
                    T(label, 12, if (on) Color.White else Color(0xCCFFFFFF), 700)
                }
            }
            Spacer(Modifier.weight(1f))
            if (items.isNotEmpty()) {
                T("${curIdx + 1} / ${items.size}", 12, Color(0x99FFFFFF), 600, mono = true)
            }
        }

        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                T("还没有可展示的封面，先播放几首歌或收藏专辑吧", 14, Color(0x99FFFFFF))
            }
            return@Column
        }

        // 核心 3D 封面舞台（支持横向拖拽、桌面滚轮与点击）
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()
            .onSizeChanged { stageW = it.width.toFloat(); stageH = it.height.toFloat() }
            .pointerInput(items.size) {
                detectHorizontalDragGestures(
                    onDragStart = { snapJob?.cancel(); flippedKey = null },
                    onDragEnd = { snapTo(pos.value.roundToInt()) },
                    onDragCancel = { snapTo(pos.value.roundToInt()) },
                ) { change, dragAmount ->
                    change.consume()
                    val stepPx = (stageW * 0.24f).coerceIn(110f * density, 260f * density)
                    val next = (pos.value - dragAmount / stepPx).coerceIn(-0.35f, items.lastIndex + 0.35f)
                    scope.launch { pos.snapTo(next) }
                }
            }
            .pointerInput(items.size) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type == PointerEventType.Scroll) {
                            val d = ev.changes.fold(0f) { acc, ch -> acc + ch.scrollDelta.y + ch.scrollDelta.x }
                            if (abs(d) > 0.05f) {
                                ev.changes.forEach { it.consume() }
                                snapTo(pos.value.roundToInt() + if (d > 0f) 1 else -1)
                            }
                        }
                    }
                }
            }, contentAlignment = Alignment.Center) {

            val coverDp: Dp = with(LocalDensity.current) {
                (minOf(stageW * 0.56f, stageH * 0.54f).coerceIn(170f * density, 310f * density)).toDp()
            }
            val coverPx = with(LocalDensity.current) { coverDp.toPx() }
            val centerOffPx = coverPx * 0.60f
            val stackStepPx = coverPx * 0.26f

            // 舞台玻璃反光地平线
            Box(Modifier.align(Alignment.Center).offset(y = coverDp * 0.52f).fillMaxWidth().height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x28FFFFFF), Color.Transparent))))

            // 仅渲染当前视口左右各 5 张封面，保证大曲库也 120fps 零压力
            val minI = (curIdx - 5).coerceAtLeast(0)
            val maxI = (curIdx + 5).coerceAtMost(items.lastIndex)
            for (i in minI..maxI) {
                val item = items[i]
                val d = i - pos.value
                val ad = abs(d)
                val isCenter = i == curIdx && ad < 0.35f
                val isFlipped = isCenter && flippedKey == item.key
                val flipDeg by animateFloatAsState(if (isFlipped) 180f else 0f, Motion.page(), label = "cfFlip_$i")

                val sideAngle = (-58f * sign(d) * ad.coerceAtMost(1f))
                val tx = if (ad <= 1f) d * centerOffPx else sign(d) * (centerOffPx + (ad - 1f) * stackStepPx)
                val sc = 1f - 0.22f * ad.coerceAtMost(1f) - 0.03f * (ad - 1f).coerceAtLeast(0f)
                val alphaVal = (1f - 0.16f * ad).coerceIn(0.22f, 1f)
                val z = 100f - ad * 10f

                Column(
                    Modifier.zIndex(z)
                        .graphicsLayer {
                            translationX = tx
                            translationY = -coverPx * 0.08f
                            scaleX = sc; scaleY = sc
                            rotationY = if (isCenter) sideAngle + flipDeg else sideAngle
                            cameraDistance = 13f * density
                            alpha = alphaVal
                        }
                        .pointerInput(item.key, isCenter, curIdx) {
                            detectTapGestures {
                                if (isCenter) flippedKey = if (flippedKey == item.key) null else item.key
                                else snapTo(i)
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 封面主体（翻转超过 90° 时切为背面曲目清单）
                    Box(Modifier.size(coverDp).border(1.dp, Color(if (isCenter) 0x55FFFFFF else 0x22FFFFFF)).background(Color(0xFF181C24))) {
                        if (flipDeg <= 90f) {
                            Cover(item.cover, coverDp, item.title, border = false, decodeSize = 512.dp)
                            if (ad > 0.05f) {
                                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = (ad * 0.28f).coerceAtMost(0.55f))))
                            }
                        } else {
                            // 背面需要再水平翻转 180° 让文字正向可读
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
                    // 经典 iOS 6 镜面倒影（翻转时淡出）
                    val reflH = coverDp * 0.38f
                    Box(Modifier.size(coverDp, reflH).clipToBounds().graphicsLayer { alpha = (1f - flipDeg / 90f).coerceIn(0f, 1f) }) {
                        Box(Modifier.size(coverDp)
                            .graphicsLayer {
                                scaleY = -1f
                                translationY = -coverPx + reflH.toPx()
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithContent {
                                drawContent()
                                drawRect(brush = reflMask, blendMode = BlendMode.DstIn)
                            }) {
                            Cover(item.cover, coverDp, item.title, border = false, decodeSize = 256.dp)
                        }
                    }
                }
            }
        }

        // 底部专辑信息 + 操作 + iOS 6 经典底部快滑条
        if (curItem != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                T(curItem.title, 18, Color.White, 800, lines = 1, align = TextAlign.Center)
                Spacer(Modifier.height(2.dp))
                T(curItem.sub, 12, Color(0xA6FFFFFF), 500, lines = 1, align = TextAlign.Center)
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
                        .background(Color(0x22FFFFFF)).padding(horizontal = 14.dp, vertical = 7.dp)) {
                        T(if (flippedKey == curItem.key) "翻回封面" else "翻转曲目", 12, Color.White, 600)
                    }
                    if (curItem.kind != "tracks") {
                        Box(Modifier.press {
                            when (curItem.kind) {
                                "album" -> nav.push(Page.Album(curItem.id, curItem.title, curItem.cover))
                                "pl" -> nav.push(Page.Detail("pl", curItem.id, curItem.title, curItem.cover))
                                "lpl" -> nav.push(Page.Detail("lpl", curItem.id, curItem.title))
                            }
                        }.background(Color(0x18FFFFFF)).padding(horizontal = 14.dp, vertical = 7.dp)) {
                            T("进入详情", 12, Color(0xDDFFFFFF), 600)
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

@Composable private fun CoverBackTracklist(
    item: FlowItem,
    tracks: List<Track>?,
    loading: Boolean,
    onClose: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val p = c
    Column(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.background(Color(0xFF141822)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(item.title, 13, Color.White, 800, lines = 1)
                T(item.sub, 10, Color(0x99FFFFFF), lines = 1)
            }
            Box(Modifier.press(onClick = onClose).background(Color(0x22FFFFFF)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                T("返回", 10, Color.White, 700)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
        when {
            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { T("LOADING…", 11, Color(0x88FFFFFF), mono = true) }
            tracks.isNullOrEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.press(onClick = onOpenDetail).background(p.p).padding(horizontal = 12.dp, vertical = 6.dp)) { T("查看完整详情", 11, Color.White, 700) }
            }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                itemsIndexed(tracks) { idx, t ->
                    Row(Modifier.fillMaxWidth().press { Engine.playList(tracks, idx) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(two(idx + 1), 10, Color(0x77FFFFFF), mono = true, modifier = Modifier.width(22.dp))
                        T(t.title, 12, Color.White, 600, lines = 1, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
