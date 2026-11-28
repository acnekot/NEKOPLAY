package cc.catsuki.nekoplay

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

import androidx.compose.foundation.clickable
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.withFrameMillis

// ======================= 迷你播放条 =======================
@Composable fun QueueSheet() {
    val nav = LocalNav.current
    val p = c
    AnimatedVisibility(nav.queue, enter = fadeIn(Motion.standard()), exit = fadeOut(Motion.quick())) {
        Box(Modifier.fillMaxSize().background(Color(0x80000000)).pointerInput(Unit) { detectTapGestures { nav.queue = false } })
    }
    AnimatedVisibility(nav.queue, Modifier.fillMaxSize(), enter = slideInVertically(sp) { it }, exit = slideOutVertically(sp) { it }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val st by Engine.state.collectAsState()
            val ls = rememberLazyListState((st.index - 2).coerceAtLeast(0))
            Column(Modifier.fillMaxWidth().fillMaxHeight(.75f).background(p.sheet).drawBehind { if (!Look.flat) drawRect(p.k, size = Size(size.width, 3.dp.toPx())) }
                .pointerInput(Unit) { detectTapGestures { } }) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { TagRow("QUEUE · ${st.queue.size}", 8.dp) }
                    LinkBtn("清空待播", 14) { Engine.clearUpcoming() }
                    Box(Modifier.size(44.dp).press { nav.queue = false }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 14.dp) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(if (Look.flat) Color.Transparent else p.l2))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), ls, contentPadding = PaddingValues(start = 18.dp, end = 6.dp, bottom = 12.dp)) {
                    itemsIndexed(st.queue, key = { i, t -> "$i:${t.id}" }, contentType = { _, _ -> "queue" }) { i, t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { TrackRow(t, i == st.index, st.format.uppercase().takeIf { i == st.index && it.isNotEmpty() }) { Engine.playAt(i) } }
                            Box(Modifier.size(44.dp).press { Engine.removeAt(i) }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 12.dp, p.s) }
                        }
                    }
                }
                LocateBtn(ls, st.index)
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}


/** 间奏三点进度：只在当前句才订阅进度，不连累整份歌词 */

@Composable fun CommentSheet() {
    val nav = LocalNav.current
    val p = c
    var last by remember { mutableStateOf<Track?>(null) }
    nav.comments?.let { last = it }
    AnimatedVisibility(nav.comments != null, Modifier.fillMaxSize(), enter = slideInVertically(sp) { it }, exit = slideOutVertically(sp) { it }) {
        val t = last ?: return@AnimatedVisibility
        CommentsBody(t) { nav.comments = null }
    }
}

/** 评论内容（手机是全屏弹层；PC 宽屏放在右栏替换歌词） */
@Composable fun CommentsBody(t: Track, onClose: () -> Unit) {
    val nav = LocalNav.current
    val p = c
    val me = Store.profile.collectAsState().value
    var hot by remember(t.id) { mutableStateOf(true) }
    val list = remember(t.id, hot) { mutableStateListOf<Comment>() }
    var total by remember(t.id, hot) { mutableIntStateOf(-1) }
    var busy by remember(t.id, hot) { mutableStateOf(false) }
    var end by remember(t.id, hot) { mutableStateOf(false) }
    var err by remember(t.id, hot) { mutableStateOf<String?>(null) }
    var text by remember(t.id) { mutableStateOf("") }
    var replyTo by remember(t.id) { mutableStateOf<Comment?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    fun more() {
        if (busy || end) return
        busy = true
        scope.launch {
            try {
                val (l, n) = SourceApi.comments(t.id, hot, list.size, if (!hot) list.lastOrNull()?.time ?: 0 else 0)
                list += l.filter { x -> list.none { it.id == x.id } }; total = n; if (l.isEmpty() || (total in 0..list.size)) end = true
            } catch (e: Exception) { err = e.message; end = true }
            busy = false
        }
    }
    LaunchedEffect(t.id, hot) { more() }
    val ls = remember(t.id, hot) { androidx.compose.foundation.lazy.LazyListState() }
    val nearEnd by remember(ls) { derivedStateOf { (ls.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= ls.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, list.size) { if (nearEnd && list.isNotEmpty()) more() }
    fun send() {
        val v = text.trim(); if (v.isEmpty() || sending) return
        if (me == null) { nav.toast = "登录后才能评论"; return }
        sending = true
        val r = replyTo
        scope.launch {
            try {
                SourceApi.commentPost(t.id, v, r?.id)
                text = ""; replyTo = null; fm.clearFocus()
                nav.toast = if (r != null) "回复已发送" else "评论已发送"
                if (!hot) list.add(0, Comment(System.currentTimeMillis(), me.name, me.uid, me.avatar, v, 0, System.currentTimeMillis(), r?.let { "@${it.user}：${it.content}" }))
            } catch (e: Exception) { nav.toast = "发送失败：${e.message}" }
            sending = false
        }
    }
    Column(Modifier.fillMaxSize().background(p.sheet).statusBarsPadding().drawBehind { if (!Look.flat) drawRect(p.k, size = Size(size.width, 3.dp.toPx())) }) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TagRow("COMMENTS" + if (total > 0) " · $total" else "", 8.dp) }
            Box(Modifier.size(44.dp).press { fm.clearFocus(); onClose() }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 14.dp) }
        }
        Row(Modifier.padding(horizontal = 18.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to "热门", false to "最新").forEach { (k, l) ->
                Box(Modifier.press { hot = k }.background(if (hot == k) p.k else Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 6.dp)) { T(l, 13, if (hot == k) p.tagFg else p.k, 700) }
            }
            Spacer(Modifier.weight(1f)); T(t.title, 13, p.s, modifier = Modifier.align(Alignment.CenterVertically).widthIn(max = 180.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (Look.flat) Color.Transparent else p.l2))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), ls, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
            itemsIndexed(list, key = { _, x -> x.id }, contentType = { _, _ -> "comment" }) { _, x ->
                val replyOf: (Comment) -> Unit = { r -> if (me == null) nav.toast = "登录后才能回复" else { replyTo = r; fr.requestFocus() } }
                val userOf: (Comment) -> Unit = { r -> fm.clearFocus(); nav.comments = null; nav.player = false; nav.push(Page.User(r.uid, r.user)) }
                CommentRow(x, t.id, onReply = { replyOf(x) }, onUserOf = userOf, onReplyOf = replyOf) { userOf(x) }
            }
            item {
                T(when { err != null -> "加载失败：$err"; busy -> "LOADING"; list.isEmpty() -> "NO COMMENTS"; end -> "END"; else -> "" },
                    12, p.s, mono = true, ls = .08f, lines = 3, modifier = Modifier.padding(vertical = 20.dp))
            }
        }
        // 聊天式输入栏：贴在键盘上
        Column(Modifier.fillMaxWidth().background(p.bar).drawBehind { if (!Look.flat) drawRect(p.k, size = Size(size.width, 2.dp.toPx())) }) {
            replyTo?.let { r ->
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(30.dp).background(p.p)); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) { T("回复 @${r.user}", 12, p.p, 700); T(r.content, 12, p.s) }
                    Box(Modifier.size(40.dp).press { replyTo = null }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 12.dp) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f).heightIn(min = 44.dp).nbf(1.dp, p.k).background(p.page).padding(horizontal = 12.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) T(if (me == null) "登录后才能评论" else if (replyTo != null) "回复 @${replyTo!!.user}…" else "说点什么…", 15, p.s)
                    androidx.compose.foundation.text.BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(fr), enabled = me != null, maxLines = 5,
                        textStyle = androidx.compose.ui.text.TextStyle(color = p.k, fontSize = 15.sp, fontFamily = Typo.family, lineHeight = 21.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(p.p))
                }
                Spacer(Modifier.width(8.dp))
                val can = text.isNotBlank() && !sending
                Box(Modifier.size(44.dp).press(.92f, true) { send() }.background(if (can) p.p else p.l3), contentAlignment = Alignment.Center) {
                    if (sending) T("…", 16, Color.White, 800) else NIcon(NIcons.send, 20.dp, Color.White)
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars)))
        }
    }
}

@Composable private fun CommentRow(x: Comment, songId: String, nested: Boolean = false, parentUser: String? = null, onReply: () -> Unit, onUserOf: (Comment) -> Unit = {}, onReplyOf: (Comment) -> Unit = {}, onUser: () -> Unit) {
    val p = c
    val nav = LocalNav.current
    var liked by remember(x.id) { mutableStateOf(x.likedByMe) }
    val n = x.liked + (if (liked) 1 else 0) - (if (x.likedByMe) 1 else 0)
    Row(Modifier.fillMaxWidth().drawBehind { if (!Look.flat) drawRect(p.l1, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }.padding(vertical = 14.dp)) {
        Box(Modifier.size(36.dp).background(p.ph).nb(1.dp, p.k).press(onClick = onUser)) {
            if (x.avatar != null) coil3.compose.AsyncImage(img(x.avatar, 96), null, Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(x.user, 13, p.s, 600, modifier = Modifier.weight(1f).press(onClick = onUser))
                Row(Modifier.press(.9f, true) {
                    if (!SourceApi.loggedIn) { nav.toast = "登录后才能点赞"; return@press }
                    val on = !liked; liked = on
                    Store.run { try { SourceApi.commentLike(songId, x.id, on) } catch (e: Exception) { liked = !on; nav.toast = "点赞失败：${e.message}" } }
                }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 点了赞：红色实心；没点：空心
                    NIcon(if (liked) NIcons.heart else NIcons.heartLine, 15.dp, if (liked) LikeRed else p.s)
                    Spacer(Modifier.width(4.dp)); T(likeNum(n), 12, if (liked) LikeRed else p.s, mono = true)
                }
            }
            Spacer(Modifier.height(4.dp))
            T(x.content, 15, p.k, lines = 40, lh = 1.5f, modifier = Modifier.press(onClick = onReply))
            // 楼里的回复不再引用原文：回楼主的什么都不加，回楼里别人的只标一行「回复 @某人」
            if (x.reply != null) {
                if (!nested) T(x.reply, 13, p.s, lines = 4, lh = 1.5f, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().background(p.hl).padding(8.dp))
                else { val who = x.reply.removePrefix("@").substringBefore("："); if (who != parentUser) T("回复 @$who", 12, p.s, modifier = Modifier.padding(top = 4.dp)) }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ago = remember(x.id, x.time) { timeAgo(x.time) }
                T(ago, 11, p.s, mono = true, modifier = Modifier.weight(1f))
                T("回复", 12, p.s, 600, modifier = Modifier.press(onClick = onReply).padding(vertical = 4.dp, horizontal = 2.dp))
            }
            if (!nested && x.replies > 0) FloorReplies(x, songId, onUserOf, onReplyOf)
        }
    }
}

val LikeRed = Color(0xFFE5484D)

/** 楼层回复：点「N 条回复」展开，楼里的回复缩进显示，可以继续加载更多 */
@Composable private fun FloorReplies(x: Comment, songId: String, onUserOf: (Comment) -> Unit, onReplyOf: (Comment) -> Unit) {
    val p = c
    val nav = LocalNav.current
    var open by remember(x.id) { mutableStateOf(false) }
    var list by remember(x.id) { mutableStateOf<List<Comment>>(emptyList()) }
    var cursor by remember(x.id) { mutableStateOf(-1L) }
    var busy by remember(x.id) { mutableStateOf(false) }
    var loadedOnce by remember(x.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun more() {
        if (busy) return; busy = true
        scope.launch {
            try { val (l, cur) = SourceApi.commentFloor(songId, x.id, if (loadedOnce) cursor else -1); list = list + l.filter { a -> list.none { it.id == a.id } }; cursor = cur; loadedOnce = true }
            catch (e: Exception) { nav.toast = "回复加载失败：${e.message}" }
            busy = false
        }
    }
    Column(Modifier.padding(top = 6.dp)) {
        if (open) Column(Modifier.fillMaxWidth().drawBehind { drawRect(p.l2, size = Size(2.dp.toPx(), size.height)) }.padding(start = 10.dp)) {
            list.forEach { r -> CommentRow(r, songId, nested = true, parentUser = x.user, onReply = { onReplyOf(r) }, onUser = { onUserOf(r) }, onUserOf = onUserOf) }
            if (busy) T("LOADING", 11, p.s, mono = true, ls = .08f, modifier = Modifier.padding(vertical = 8.dp))
            else if (cursor != -1L) T("加载更多回复", 12, p.s, 600, modifier = Modifier.press { more() }.padding(vertical = 8.dp))
        }
        T(if (open) "收起回复" else "${x.replies} 条回复 ›", 12, p.p, 600, modifier = Modifier.press {
            open = !open; if (open && !loadedOnce) more()
        }.padding(vertical = 4.dp))
    }
}

/** 像聊天软件那样的时间：刚刚 / 5 分钟前 / 今天 13:20 / 昨天 / 2024-03-01 */
fun timeAgo(ms: Long): String {
    val d = System.currentTimeMillis() - ms
    val now = java.util.Calendar.getInstance(); val c0 = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = now.get(java.util.Calendar.YEAR) == c0.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == c0.get(java.util.Calendar.DAY_OF_YEAR)
    return when {
        d < 60_000 -> "刚刚"
        d < 3_600_000 -> "${d / 60_000} 分钟前"
        sameDay -> java.text.SimpleDateFormat("今天 HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
        d < 172_800_000 -> java.text.SimpleDateFormat("昨天 HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
        now.get(java.util.Calendar.YEAR) == c0.get(java.util.Calendar.YEAR) -> java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(ms))
    }
}

fun likeNum(n: Int) = if (n >= 10000) "%.1fw".format(n / 10000f) else n.toString()


/** 宽屏播放页：左栏封面、歌名、控制（限制宽度居中），右栏歌词 */
