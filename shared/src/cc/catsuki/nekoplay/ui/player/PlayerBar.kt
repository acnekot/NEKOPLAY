package cc.catsuki.nekoplay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * PC 宽屏布局（窗口 ≥ 900dp）：
 * ┌──────────┬──────────────────────────┬─────────────┐
 * │ 侧边栏    │ 内容区（主页 / 曲库 / 子页面） │ 歌词 / 队列  │
 * ├──────────┴──────────────────────────┴─────────────┤
 * │ 底部播放条：封面 · 歌名 | 控制 + 进度 | 喜欢 评论 歌词 队列 音量 │
 * └────────────────────────────────────────────────────┘
 * 页面组件和手机版是同一套，只是摆放方式不同。
 */
@Composable internal fun PlayerBar() {
    val nav = LocalNav.current
    val p = c
    val st by Engine.state.collectAsState()
    val liked by Store.likedIds.collectAsState()
    val t = st.current
    Box(Modifier.fillMaxWidth().height(84.dp).background(barBg()).drawBehind {
        if (!Look.flat) drawRect(p.k, Offset.Zero, Size(size.width, 3.dp.toPx()))
    }) {
    Row(Modifier.fillMaxSize().padding(top = 3.dp, start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        // 左：封面 + 歌名（切歌时丝滑上下滑入淡入淡出）
        AnimatedContent(t, Modifier.width(300.dp), contentKey = { it?.id ?: "" }, transitionSpec = {
            val ms = Motion.standardMs
            val ease = AnimStyle.activeEasing
            (androidx.compose.animation.slideInVertically(tween(ms, easing = ease)) { it / 3 } + fadeIn(tween(ms, easing = ease))) togetherWith
                (androidx.compose.animation.slideOutVertically(tween((ms * .7f).toInt(), easing = ease)) { -it / 3 } + fadeOut(tween((ms * .6f).toInt())))
        }, label = "barTrack") { cur ->
            Row(Modifier.fillMaxWidth().press { if (cur != null) { nav.lyrics = false; nav.player = true } }, verticalAlignment = Alignment.CenterVertically) {
                Cover(cur?.cover, 56.dp, cur?.title ?: "")
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    T(cur?.title ?: "还没有曲目", 15, w = 800, lh = 1.3f)
                    T(cur?.artist ?: "选一首歌开始播放", 12, p.s, lh = 1.3f, modifier = Modifier.press { if (cur != null) openArtists(nav, cur) })
                }
            }
        }
        // 中：控制 + 进度
        Column(Modifier.weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).press { Engine.cycleMode(); nav.toast = listOf("列表循环", "单曲循环", "随机播放")[Engine.state.value.mode.ordinal] }, contentAlignment = Alignment.Center) {
                    NIcon(when (st.mode) { Mode.LOOP_ALL -> NIcons.modeSeq; Mode.LOOP_ONE -> NIcons.modeOne; Mode.SHUFFLE -> NIcons.modeShuffle }, 18.dp, p.s)
                }
                Spacer(Modifier.width(14.dp))
                Box(Modifier.size(36.dp).holdScrub(-1, enabled = !st.fm && t != null) { if (st.fm) Engine.fmTrash() else Engine.prev() }, contentAlignment = Alignment.Center) { NIcon(if (st.fm) NIcons.trash else NIcons.prev, 18.dp, if (ScrubController.dir < 0) p.p else p.k) }
                Spacer(Modifier.width(14.dp))
                Box(Modifier.size(40.dp).press(.94f) { Engine.toggle() }.background(p.p), contentAlignment = Alignment.Center) {
                    AnimatedContent(st.playing, transitionSpec = {
                        (fadeIn(Motion.quick()) + scaleIn(Motion.standard(), initialScale = if (MotionEffects.pageScale) .75f else 1f)) togetherWith
                            (fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = if (MotionEffects.pageScale) .75f else 1f))
                    }, label = "playIcon") { playing ->
                        NIcon(if (playing) NIcons.pause else NIcons.play, 18.dp, Color.White)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Box(Modifier.size(36.dp).holdScrub(1, enabled = t != null) { Engine.next() }, contentAlignment = Alignment.Center) { NIcon(NIcons.next, 18.dp, if (ScrubController.dir > 0) p.p else p.k) }
                if (ScrubController.dir != 0) T((if (ScrubController.dir > 0) " >> " else " << ") + "%.1f×".format(ScrubController.rate), 11, p.p, 700, mono = true)
                Spacer(Modifier.width(14.dp))
                Box(Modifier.size(36.dp).press { if (t != null) trackActions(nav, t) }, contentAlignment = Alignment.Center) { NIcon(NIcons.more, 18.dp, p.s) }
            }
            Row(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PosText(p.p, Modifier.width(44.dp))
                Box(Modifier.weight(1f)) { ScrubBar(t?.durationMs ?: 0, 14.dp, interactive = t != null) }
                T(fmt(t?.durationMs ?: 0), 11, p.s, mono = true, modifier = Modifier.width(44.dp).padding(start = 8.dp))
            }
        }
        // 右：喜欢 · 评论 · 歌词 · 队列 · 音量
        Row(Modifier.width(300.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val on = t != null && t.id in liked
            BarBtn(if (on) NIcons.heart else NIcons.heartLine, on) { if (t != null) Store.toggleLike(t) { nav.toast = it } }
            BarBtn(NIcons.comment, nav.comments != null) { if (t != null) nav.comments = if (nav.comments != null) null else t }
            if (Features.on("desk")) BarText("桌", DeskLyric.on) { DeskLyric.on = !DeskLyric.on; DeskLyric.save(); nav.toast = if (DeskLyric.on) "已打开桌面歌词" else "已关闭桌面歌词" }
            BarText("词", nav.side == 1) { nav.side = if (nav.side == 1) 0 else 1 }
            BarBtn(NIcons.queue, nav.side == 2) { nav.side = if (nav.side == 2) 0 else 2 }
            Spacer(Modifier.width(8.dp))
            VolumeBar()
        }
    }
    }
}

/** 独立作用域 + 按秒去重：避免进度条每 50ms/250ms 触发整个底部播放条重组 */
@Composable private fun PosText(color: Color, modifier: Modifier = Modifier) {
    val sec by remember { Engine.position.map { it / 1000L }.distinctUntilChanged() }.collectAsState(Engine.position.value / 1000L)
    T(fmt(sec * 1000L), 11, color, mono = true, modifier = modifier)
}

@Composable private fun BarBtn(icon: String, on: Boolean, onClick: () -> Unit) =
    Box(Modifier.size(38.dp).press(onClick = onClick), contentAlignment = Alignment.Center) { NIcon(icon, 18.dp, if (on) c.p else c.k) }

@Composable private fun BarText(s: String, on: Boolean, onClick: () -> Unit) =
    Box(Modifier.size(38.dp).press(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).background(if (on) c.p else Color.Transparent).nbf(1.dp, c.k), contentAlignment = Alignment.Center) { T(s, 12, if (on) Color.White else c.k, 700) }
    }

/** 全局音量（音量条和快捷键共用） */
object Vol {
    var v by mutableFloatStateOf(1f)
    fun load() { v = Plat.prefs("ui").getFloat("vol", 1f); runCatching { Plat.impl.audio.volume = v } }
    fun set(x: Float, save: Boolean = true) { v = x.coerceIn(0f, 1f); Plat.impl.audio.volume = v; if (save) Plat.prefs("ui").edit().putFloat("vol", v).apply() }
}

/** 音量：点或拖动设置（存起来，下次打开沿用） */
@Composable private fun VolumeBar() {
    val p = c
    var v by Vol::v
    Box(Modifier.width(90.dp).height(24.dp).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(); val ch = e.changes.firstOrNull() ?: continue
                if (ch.pressed) { Vol.set((ch.position.x / size.width).coerceIn(0f, 1f), save = false); ch.consume() }
                else if (ch.previousPressed) Vol.set(Vol.v)
            }
        }
    }.drawBehind {
        val y = size.height / 2; val h = 4.dp.toPx()
        drawRect(p.l3, Offset(0f, y - h / 2), Size(size.width, h))
        drawRect(p.k, Offset(0f, y - h / 2), Size(size.width * v, h))
        val s = 10.dp.toPx(); drawRect(p.k, Offset((size.width * v - s / 2).coerceIn(0f, (size.width - s).coerceAtLeast(0f)), y - s / 2), Size(s, s))
    })
}
