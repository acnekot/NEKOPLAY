package cc.catsuki.nekoplay.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import cc.catsuki.nekoplay.*
import cc.catsuki.nekoplay.core.*
import cc.catsuki.nekoplay.data.*
import cc.catsuki.nekoplay.ui.*

/**
 * 私人 FM 播放界面：中间是正在播的大封面，左边露出上一首、右边露出下一首（点它们就切过去）；
 * 下面是歌名、进度和 上一首 / 不喜欢 / 播放 / 喜欢 / 下一首。
 */
@Composable fun FmPage() {
    val nav = LocalNav.current
    val p = c
    val st by Engine.state.collectAsState()
    val liked by Store.likedIds.collectAsState()
    // 进来时还没在放 FM 就开始；队列快到底就提前要下一批，保证右边总有「下一首」可看
    LaunchedEffect(Unit) { if (!st.fm) Engine.startFm { nav.toast = it } }
    LaunchedEffect(st.index, st.fm) { if (st.fm) Engine.fmEnsureNext() }
    val cur = if (st.fm) st.current else null
    val prev = if (st.fm) st.queue.getOrNull(st.index - 1) else null
    val next = if (st.fm) st.queue.getOrNull(st.index + 1) else null
    var lastIndex by remember { mutableIntStateOf(st.index) }
    val forward = st.index >= lastIndex
    SideEffect { lastIndex = st.index }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val big = minOf(maxWidth * .42f, maxHeight - 380.dp, 400.dp).coerceAtLeast(160.dp)
        val small = big * .5f
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Tag("PERSONAL FM"); Spacer(Modifier.height(4.dp)); T("私人 FM", Fs.HERO, w = 800, lh = 1.2f) }
                if (cur != null) LinkBtn("打开播放页") { nav.lyrics = false; nav.player = true }
            }
            Spacer(Modifier.weight(1f))
            // 上一首 · 当前 · 下一首
            Row(verticalAlignment = Alignment.CenterVertically) {
                SideCard(prev, small, "上一首") { if (prev != null) Engine.playAt(st.index - 1) }
                Spacer(Modifier.width(24.dp))
                AnimatedContent(cur, transitionSpec = {
                    val d = if (forward) 1 else -1
                    (slideInHorizontally(sp) { it / 2 * d } + fadeIn(tween(220))) togetherWith (slideOutHorizontally(sp) { -it / 2 * d } + fadeOut(tween(160)))
                }, label = "fm") { t ->
                    Box(Modifier.size(big).press(.98f) { if (t != null) { nav.lyrics = false; nav.player = true } }, contentAlignment = Alignment.Center) {
                        if (t == null) IconCover(NIcons.radio, big, true) else Cover(t.cover, big, t.title, decodeSize = 512.dp)
                    }
                }
                Spacer(Modifier.width(24.dp))
                SideCard(next, small, "下一首") { if (next != null) Engine.next() }
            }
            Spacer(Modifier.height(22.dp))
            Column(Modifier.width(big + small + 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                T(cur?.title ?: "正在获取…", Fs.PAGE, w = 800, lines = 1)
                T(cur?.artist ?: "", 14, p.s, modifier = Modifier.press { if (cur != null) openArtists(nav, cur) })
                Spacer(Modifier.height(14.dp))
                if (cur != null) { ScrubBar(cur.durationMs); TimeRow(cur.durationMs) }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FmBtn(NIcons.prev, "上一首", prev != null) { Engine.playAt(st.index - 1) }
                    Spacer(Modifier.width(18.dp))
                    FmBtn(NIcons.trash, "不喜欢", cur != null) { Engine.fmTrash(); nav.toast = "不再推荐这首" }
                    Spacer(Modifier.width(18.dp))
                    Box(Modifier.size(56.dp).press(.94f, true) { if (st.fm) Engine.toggle() else Engine.startFm { nav.toast = it } }.background(p.p), contentAlignment = Alignment.Center) {
                        NIcon(if (st.fm && st.playing) NIcons.pause else NIcons.play, 24.dp, Color.White)
                    }
                    Spacer(Modifier.width(18.dp))
                    val on = cur != null && cur.id in liked
                    FmBtn(if (on) NIcons.heart else NIcons.heartLine, "喜欢", cur != null, if (on) p.p else null) { if (cur != null) Store.toggleLike(cur) { nav.toast = it } }
                    Spacer(Modifier.width(18.dp))
                    FmBtn(NIcons.next, "下一首", cur != null) { Engine.next() }
                }
                Spacer(Modifier.height(10.dp))
                T(if (next != null) "接下来：${next.title} · ${next.artist}" else "", 12, p.s, mono = true)
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** 两边的小封面：淡一点，点了就切过去 */
@Composable private fun SideCard(t: Track?, size: androidx.compose.ui.unit.Dp, label: String, onClick: () -> Unit) {
    val p = c
    Column(Modifier.width(size).graphicsLayer { alpha = if (t == null) .25f else .6f }.press(.96f, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        if (t == null) Box(Modifier.size(size).background(p.ph).nb(1.dp, p.k)) else Cover(t.cover, size, t.title)
        Spacer(Modifier.height(6.dp))
        T(label, 11, p.s, 700, mono = true)
        T(t?.title ?: "—", 12, p.k, 600)
    }
}

@Composable private fun FmBtn(icon: String, label: String, enabled: Boolean, tint: Color? = null, onClick: () -> Unit) {
    Column(Modifier.graphicsLayer { alpha = if (enabled) 1f else .3f }.press(.94f, true) { if (enabled) onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).nbf(1.dp, c.k), contentAlignment = Alignment.Center) { NIcon(icon, 20.dp, tint ?: c.k) }
        Spacer(Modifier.height(4.dp)); T(label, 11, c.s)
    }
}
