package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.ui.settings.downloadSheet

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.focusRequester
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState

// ======================= 操作菜单（底部弹出） =======================
data class SheetItem(val label: String, val danger: Boolean = false, val run: () -> Unit)
data class SheetSpec(val title: String, val sub: String?, val items: List<SheetItem>)

/** 曲目长按：下一首播放 / 加入队列 / 喜欢 / 歌手 / 专辑 */
fun trackActions(nav: Nav, t: Track, fromPl: String? = null) {
    val liked = t.id in Store.likedIds.value
    // 本地 / WebDAV 的歌：没有在线来源的那几项
    if (t.local) {
        nav.sheet = SheetSpec(t.title, t.artist, buildList {
            add(SheetItem("下一首播放") { Engine.enqueueNext(t); nav.toast = "已加入「下一首播放」" })
            add(SheetItem("加入播放队列") { Engine.enqueue(t); nav.toast = "已加入播放队列" })
            add(SheetItem(if (liked) "取消喜欢" else "加入「本机喜欢」") { Store.toggleLike(t) { nav.toast = it }; nav.toast = if (liked) "已取消喜欢" else "已加入「本机喜欢」" })
            add(SheetItem("添加到歌单…") { addToPlaylist(nav, t) })
            if (fromPl?.startsWith("lpl:") == true) add(SheetItem("从这个歌单删除", danger = true) { LocalLib.remove(fromPl, t.id); nav.toast = "已从歌单删除" })
            if (Features.on("card")) add(SheetItem("歌词卡片") { nav.player = false; nav.push(Page.LyricCard(t)) })
            if (Features.on("offset") && Engine.state.value.current?.id == t.id) add(SheetItem("歌词偏移") { offsetSheet(nav, t) })
            add(SheetItem("效果器 / 均衡器") { nav.player = false; nav.push(Page.Effects) })
            if (Plat.impl.vst != null) add(SheetItem("VST 插件 / 可视化仪 (Beta)") { nav.player = false; nav.push(Page.Vst) })
        })
        return
    }
    nav.sheet = SheetSpec(t.title, t.artist, buildList {
        add(SheetItem("下一首播放") { Engine.enqueueNext(t); nav.toast = "已加入「下一首播放」" })
        add(SheetItem("加入播放队列") { Engine.enqueue(t); nav.toast = "已加入播放队列" })
        add(SheetItem(if (liked) "取消喜欢" else "加入「我喜欢」") { Store.toggleLike(t) { nav.toast = it }; nav.toast = if (liked) "已取消喜欢" else "已加入「我喜欢」" })
        t.artists.forEach { (id, name) -> add(SheetItem("歌手：$name") { nav.player = false; nav.push(Page.Artist(id, name)) }) }
        if (t.albumId.isNotEmpty() && t.albumId != "0") add(SheetItem("专辑：${t.album}") { nav.player = false; nav.push(Page.Album(t.albumId, t.album, t.cover)) })
        add(SheetItem("添加到歌单…") { addToPlaylist(nav, t) })
        if (fromPl != null) add(SheetItem("从这个歌单删除", danger = true) {
            if (fromPl.startsWith("lpl:")) { LocalLib.remove(fromPl, t.id); nav.toast = "已从歌单删除"; return@SheetItem }
            Store.run { try { Ncm.playlistTracks(fromPl, "del", listOf(t.id)); nav.toast = "已从歌单删除"; Store.refreshPlaylists(fromPl) } catch (e: Exception) { nav.toast = "删除失败：${e.message}" } }
        })
        if (Downloads.has(t.id)) add(SheetItem("删除下载", danger = true) { Downloads.delete(t.id); nav.toast = "已删除下载" })
        else if (Features.on("dl")) add(SheetItem("下载…") { downloadSheet(nav, listOf(t)) })
        add(SheetItem("查看评论") { nav.comments = t })
        if (Features.on("card")) add(SheetItem("歌词卡片") { nav.player = false; nav.push(Page.LyricCard(t)) })
        if (Features.on("offset") && Engine.state.value.current?.id == t.id) add(SheetItem("歌词偏移" + LyricOffset.of(t.id).let { if (it == 0L) "" else "（%+.1f 秒）".format(it / 1000f) }) { offsetSheet(nav, t) })
        add(SheetItem("效果器 / 均衡器") { nav.player = false; nav.push(Page.Effects) })
        add(SheetItem("相似歌曲") { nav.player = false; nav.push(Page.Detail("simi", t.id, "相似：${t.title}", t.cover)) })
        add(SheetItem("包含这首歌的歌单") { nav.player = false; nav.push(Page.Playlists("simi", t.id, "相关歌单")) })
    })
}

/** 选择要加入的歌单：账号里自己创建的（同一个来源才能加）和本地歌单，或新建一个 */
fun addToPlaylist(nav: Nav, t: Track) {
    // 账号歌单只能放同一个插件的歌
    val sameSrc = Ncm.loggedIn && Plugins.isPlugin(t.id) && Plugins.pidOf(t.id) == Plugins.main()?.id && Plugins.main()?.has("editPlaylist") == true
    val mine = if (sameSrc) Store.playlists.value.filter { it.mine } else emptyList()
    fun add(pl: Playlist) = Store.run {
        try { Ncm.playlistTracks(pl.id, "add", listOf(t.id)); nav.toast = "已加入「${if (pl.liked) "我喜欢" else pl.name}」"; Store.refreshPlaylists(pl.id) }
        catch (e: Exception) { nav.toast = "添加失败：${e.message}" }
    }
    fun addLocal(id: String, name: String) { nav.toast = if (LocalLib.add(id, listOf(t)) > 0) "已加入「$name」" else "「$name」里已经有这首" }
    nav.sheet = SheetSpec("添加到歌单", t.title, buildList {
        add(SheetItem("＋ 新建本地歌单") { nav.input = InputSpec("新建本地歌单", "歌单名称") { name -> addLocal(LocalLib.create(name).id, name) } })
        if (sameSrc) add(SheetItem("＋ 新建${Plugins.main()?.name}歌单") {
            nav.input = InputSpec("新建歌单", "歌单名称") { name ->
                Store.run { try { val id = Ncm.createPlaylist(name, false); add(Playlist(id, name, 0, null)); Store.refreshPlaylists() } catch (e: Exception) { nav.toast = "新建失败：${e.message}" } }
            }
        })
        LocalLib.lists.value.forEach { l -> add(SheetItem("本地 · " + l.name) { addLocal(l.id, l.name) }) }
        mine.forEach { pl -> add(SheetItem(if (pl.liked) "我喜欢" else pl.name) { add(pl) }) }
    })
}

/** 新建歌单（曲库页用）：没有账号或主插件不支持时直接建本地的 */
fun newPlaylist(nav: Nav) {
    if (!Ncm.loggedIn || Plugins.main()?.has("createPlaylist") != true) { newLocalPlaylist(nav); return }
    nav.sheet = SheetSpec("新建歌单", "", listOf(
        SheetItem("${Plugins.main()?.name}歌单") {
            nav.input = InputSpec("新建歌单", "歌单名称") { name ->
                Store.run { try { Ncm.createPlaylist(name, false); nav.toast = "已新建「$name」"; Store.refreshPlaylists() } catch (e: Exception) { nav.toast = "新建失败：${e.message}" } }
            }
        },
        SheetItem("本地歌单") { newLocalPlaylist(nav) },
    ))
}
fun newLocalPlaylist(nav: Nav) { nav.input = InputSpec("新建本地歌单", "歌单名称") { name -> LocalLib.create(name); nav.toast = "已新建「$name」" } }

/** 长按本地歌单：改名 / 删除 */
fun localPlaylistActions(nav: Nav, id: String, name: String) {
    nav.sheet = SheetSpec(name, "本地歌单", listOf(
        SheetItem("改名") { nav.input = InputSpec("歌单改名", name) { n -> LocalLib.rename(id, n); nav.toast = "已改名" } },
        SheetItem("删除歌单", danger = true) {
            nav.sheet = SheetSpec("确定删除「$name」？", "删除后无法恢复", listOf(SheetItem("删除", danger = true) { LocalLib.delete(id); nav.toast = "已删除" }))
        },
    ))
}

/** 曲库里长按歌单：自己的可以删除，收藏的可以取消收藏 */
fun playlistActions(nav: Nav, pl: Playlist) {
    nav.sheet = SheetSpec(pl.name, "${pl.count} 首", listOf(
        if (pl.mine) SheetItem("删除歌单", danger = true) {
            nav.sheet = SheetSpec("确定删除「${pl.name}」？", "删除后无法恢复", listOf(SheetItem("删除", danger = true) {
                Store.run { try { Ncm.deletePlaylist(pl.id); nav.toast = "已删除"; Store.refreshPlaylists() } catch (e: Exception) { nav.toast = "删除失败：${e.message}" } }
            }))
        } else SheetItem("取消收藏", danger = true) {
            Store.run { try { Ncm.subscribePlaylist(pl.id, false); nav.toast = "已取消收藏"; Store.refreshPlaylists() } catch (e: Exception) { nav.toast = "操作失败：${e.message}" } }
        }
    ))
}

// ======================= 文字输入框 =======================
data class InputSpec(val title: String, val hint: String, val onDone: (String) -> Unit)

@Composable fun InputDialog() {
    val nav = LocalNav.current
    val spec = nav.input ?: return
    val p = c
    var text by remember(spec) { mutableStateOf("") }
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(spec) { fr.requestFocus() }
    fun done() { val v = text.trim(); if (v.isEmpty()) return; nav.input = null; spec.onDone(v) }
    Box(Modifier.fillMaxSize().background(Color(0x80000000)).pointerInput(Unit) { detectTapGestures { nav.input = null } }.imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 28.dp).fillMaxWidth().background(p.sheet).nb(1.dp, p.k).topLine().pointerInput(Unit) { detectTapGestures { } }.padding(18.dp)) {
            T(spec.title, 17, w = 800); Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().nbf(1.dp, p.k).padding(horizontal = 12.dp, vertical = 12.dp)) {
                if (text.isEmpty()) T(spec.hint, 15, p.s)
                androidx.compose.foundation.text.BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(fr), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = p.k, fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp), fontFamily = Typo.family),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(p.p),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { done() }))
            }
            Spacer(Modifier.height(16.dp))
            Row { Box(Modifier.weight(1f)) { OutlineBtn("取消", 44.dp) { nav.input = null } }; Spacer(Modifier.width(10.dp)); Box(Modifier.weight(1f).press(.96f) { done() }.height(44.dp).background(p.p), contentAlignment = Alignment.Center) { T("确定", 15, Color.White, 700) } }
        }
    }
}

/** 打开歌手：多位歌手时先让用户选 */
fun openArtists(nav: Nav, t: Track) {
    when (t.artists.size) {
        0 -> nav.toast = "没有歌手信息"
        1 -> { nav.player = false; nav.push(Page.Artist(t.artists[0].first, t.artists[0].second)) }
        else -> nav.sheet = SheetSpec("选择歌手", t.title, t.artists.map { (id, n) -> SheetItem(n) { nav.player = false; nav.push(Page.Artist(id, n)) } })
    }
}

@Composable fun ActionSheet(wide: Boolean = false) {
    val nav = LocalNav.current
    val p = c
    var last by remember { mutableStateOf<SheetSpec?>(null) }
    nav.sheet?.let { last = it }
    AnimatedVisibility(nav.sheet != null, enter = fadeIn(Motion.standard()), exit = fadeOut(Motion.quick())) {
        Box(Modifier.fillMaxSize().background(Color(if (wide) 0x55000000 else 0x80000000)).pointerInput(Unit) { detectTapGestures { nav.sheet = null } })
    }
    // PC 宽屏：居中的小弹窗（缩放 + 淡入）；手机：底部弹出
    if (wide) AnimatedVisibility(nav.sheet != null, Modifier.fillMaxSize(),
        enter = fadeIn(Motion.standard()) + scaleIn(Motion.hero(), initialScale = if (MotionEffects.pageScale) .92f else 1f),
        exit = fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = if (MotionEffects.pageScale) .96f else 1f)) {
        val s = last ?: return@AnimatedVisibility
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.width(380.dp).heightIn(max = 620.dp).background(p.sheet).nb(1.dp, p.k).topLine().pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp)) {
                    T(s.title, 16, w = 800, lines = 2); if (s.sub != null) T(s.sub, 12, p.s, lines = 2)
                }
                s.items.forEach { it ->
                    Box(Modifier.fillMaxWidth().height(42.dp).press { nav.sheet = null; it.run() }.padding(horizontal = 18.dp).topLine(p.l1, 1.dp), contentAlignment = Alignment.CenterStart) {
                        T(it.label, 14, if (it.danger) p.bad else p.k)
                    }
                }
            }
        }
    }
    else AnimatedVisibility(nav.sheet != null, Modifier.fillMaxSize(), enter = slideInVertically(sp) { it }, exit = slideOutVertically(sp) { it }) {
        val s = last ?: return@AnimatedVisibility
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // 最多占半屏：标题和「取消」固定，中间的选项可以滚动
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight * .5f).background(p.sheet).topLine().pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding().padding(bottom = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp)) {
                    T(s.title, 17, w = 800); if (s.sub != null) T(s.sub, 13, p.s)
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    s.items.forEach { it ->
                        Box(Modifier.fillMaxWidth().height(52.dp).press { nav.sheet = null; it.run() }.padding(horizontal = 18.dp).topLine(p.l1, 1.dp), contentAlignment = Alignment.CenterStart) {
                            T(it.label, 16, if (it.danger) p.bad else p.k)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) { OutlineBtn("取消", 46.dp) { nav.sheet = null } }
            }
        }
    }
}

// ======================= 歌手页 =======================

fun cellQualitySheet(nav: Nav) {
    nav.sheet = SheetSpec("流量音质", "用手机流量时自动换成这个档位，省流量", listOf(SheetItem((if (Engine.qualityCell < 0) "✓  " else "    ") + "和 Wi-Fi 一样") { Engine.changeCellQuality(-1); nav.toast = "流量音质：和 Wi-Fi 一样" }) +
        Ncm.LEVEL_LABELS.mapIndexed { i, l -> SheetItem((if (i == Engine.qualityCell) "✓  " else "    ") + l) { Engine.changeCellQuality(i); nav.toast = "流量音质：$l" } }.reversed())
}

fun qualitySheet(nav: Nav) {
    nav.sheet = SheetSpec("选择音质", "选中的档位拿不到时会自动降一档；无损和 Hi-Res 需要会员", Ncm.LEVEL_LABELS.mapIndexed { i, l ->
        SheetItem((if (i == Engine.quality) "✓  " else "    ") + l) { Engine.changeQuality(i); nav.toast = "音质：$l" }
    }.reversed())
}


// ======================= 歌单列表页（相关歌单 / 以后的歌单广场共用） =======================

@Composable fun MultiChipRow(items: List<String>, on: (Int) -> Boolean, onToggle: (Int) -> Unit) {
    val p = c
    LazyRow(Modifier.fillMaxWidth().layoutNoPad(18.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(items) { i, l ->
            val o = on(i)
            Box(Modifier.press { onToggle(i) }.background(if (o) p.k else Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 6.dp)) { T(l, 13, if (o) p.tagFg else p.k, 700) }
        }
    }
}

@Composable fun ChipRow(items: List<String>, sel: Int, onSel: (Int) -> Unit) {
    val p = c
    LazyRow(Modifier.fillMaxWidth().layoutNoPad(18.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(items) { i, l ->
            val on = i == sel
            Box(Modifier.press { if (!on) onSel(i) }.background(if (on) p.k else Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 6.dp)) { T(l, 13, if (on) p.tagFg else p.k, 700) }
        }
    }
}

/** 滑到列表底部附近时触发 */
@Composable fun androidx.compose.foundation.lazy.LazyListState.nearEnd(): Boolean {
    val e by remember(this) { derivedStateOf { (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layoutInfo.totalItemsCount - 4 } }
    return e
}

// ======================= 歌单广场 =======================

@Composable fun FollowBtn(initial: Boolean, off: String, on: String, ready: Boolean = true, act: suspend (Boolean) -> Unit) {
    val nav = LocalNav.current
    var v by remember(initial) { mutableStateOf(initial) }
    val p = c
    val bg by androidx.compose.animation.animateColorAsState(if (v) Color.Transparent else p.k, Motion.standard(), label = "fb")
    val fg by androidx.compose.animation.animateColorAsState(if (v) p.k else p.tagFg, Motion.standard(), label = "ff")
    Box(Modifier.press(.96f, true) {
        if (!ready) return@press
        val n = !v; v = n
        Store.run { try { act(n); nav.toast = if (n) "已$off" else "已取消$off" } catch (e: Exception) { v = !n; nav.toast = "操作失败：${e.message}" } }
    }.height(48.dp).background(bg).nbf(1.dp, p.k).padding(horizontal = 14.dp).animateContentSize(Motion.standard()), contentAlignment = Alignment.Center) {
        androidx.compose.animation.AnimatedContent(v, transitionSpec = { fadeIn(Motion.standard()) togetherWith fadeOut(Motion.quick()) }, label = "ft") { x ->
            T(if (x) on else "+ $off", 15, fg, 700)
        }
    }
}

// ======================= 启动更新弹窗（带动效） =======================

@Composable fun UpdateDialog(wide: Boolean) {
    val nav = LocalNav.current
    val p = c
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var last by remember { mutableStateOf<Updater.Info?>(null) }
    Updater.dialog?.let { last = it }
    val show = Updater.dialog != null
    // 顶部强调条扫过 + 内容阶梯渐入（受 AnimStyle.off 与速度档位统一控制）
    val sweep = remember { androidx.compose.animation.core.Animatable(0f) }
    val step = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(show) {
        if (show) {
            if (AnimStyle.off) { sweep.snapTo(1f); step.snapTo(1f) }
            else {
                sweep.snapTo(0f); step.snapTo(0f)
                launch { sweep.animateTo(1f, Motion.page()) }
                launch { step.animateTo(1f, Motion.standard()) }
            }
        }
    }
    val pulse = if (show && !AnimStyle.off) {
        val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "updPulse")
        inf.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(
            tween(950, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ), label = "dot").value
    } else 1f

    AnimatedVisibility(show, enter = fadeIn(Motion.standard()), exit = fadeOut(Motion.quick())) {
        Box(Modifier.fillMaxSize().background(Color(if (wide) 0x5C000000 else 0x80000000))
            .pointerInput(Unit) { detectTapGestures { Updater.dialog = null } })
    }
    AnimatedVisibility(show, Modifier.fillMaxSize(),
        enter = fadeIn(Motion.standard()) + scaleIn(Motion.hero(), initialScale = .88f) + slideInVertically(Motion.hero()) { it / 8 },
        exit = fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = .94f) + slideOutVertically(Motion.quick()) { it / 12 }) {
        val info = last ?: return@AnimatedVisibility
        val s1 = (step.value * 1.4f).coerceIn(0f, 1f)
        val s2 = ((step.value - 0.18f) * 1.4f).coerceIn(0f, 1f)
        val s3 = ((step.value - 0.34f) * 1.55f).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = if (wide) 440.dp else 380.dp).fillMaxWidth()
                .background(p.sheet).nb(1.dp, p.k)
                .pointerInput(Unit) { detectTapGestures { } }) {
                // 顶部扫描强调条
                Box(Modifier.fillMaxWidth().height(3.dp).background(p.l2)) {
                    Box(Modifier.fillMaxWidth(sweep.value).fillMaxHeight().background(p.p))
                }
                Column(Modifier.padding(20.dp)) {
                    // 头部：徽章 + 版本号对比 + 关闭按钮
                    Row(Modifier.fillMaxWidth().graphicsLayer { alpha = s1; translationY = (1f - s1) * 10f }, verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.background(p.p).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(Color.White.copy(alpha = pulse), androidx.compose.foundation.shape.CircleShape))
                            Spacer(Modifier.width(6.dp))
                            T(if (Updater.nightly) "NIGHTLY" else "UPDATE", 11, Color.White, 800, mono = true, ls = .06f)
                        }
                        Spacer(Modifier.width(10.dp))
                        T("发现新版本", 18, p.k, 800, modifier = Modifier.weight(1f))
                        Box(Modifier.size(32.dp).press { Updater.dialog = null }, contentAlignment = Alignment.Center) {
                            NIcon(NIcons.close, 16.dp, p.s)
                        }
                    }
                    Row(Modifier.padding(top = 10.dp).graphicsLayer { alpha = s1; translationY = (1f - s1) * 10f }, verticalAlignment = Alignment.CenterVertically) {
                        T("v$APP_VERSION", 13, p.s, 600, mono = true)
                        T("  →  ", 13, p.s, 700, mono = true)
                        T(info.title, 14, p.p, 800, mono = true)
                    }
                    // 更新说明区（可滚动）
                    Box(Modifier.padding(top = 14.dp).fillMaxWidth().heightIn(min = 64.dp, max = 210.dp)
                        .graphicsLayer { alpha = s2; translationY = (1f - s2) * 12f }
                        .background(p.page).border(1.dp, p.l2).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        T(info.notes.ifBlank { "包含新功能与稳定性修复，建议更新体验。" }, 13, p.k, lines = 40, lh = 1.55f)
                    }
                    if (Updater.nightly) {
                        T("测试版下载的是 zip 压缩包，解压后安装即可。", 11, p.s, modifier = Modifier.padding(top = 6.dp).graphicsLayer { alpha = s2 })
                    }
                    // 操作按钮区
                    Column(Modifier.padding(top = 16.dp).graphicsLayer { alpha = s3; translationY = (1f - s3) * 12f }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryBar("立即下载更新", NIcons.download, Modifier.fillMaxWidth()) {
                            val target = info.url ?: info.page
                            Updater.dialog = null
                            runCatching { uri.openUri(target) }.onFailure { nav.toast = "打不开浏览器" }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { OutlineBtn("查看详情", 40.dp, 13) { Updater.dialog = null; runCatching { uri.openUri(info.page) } } }
                            Box(Modifier.weight(1f)) { OutlineBtn("跳过此版本", 40.dp, 13) { Updater.skip(info.title); nav.toast = "已跳过 ${info.title}" } }
                            Box(Modifier.weight(1f)) { OutlineBtn("稍后再说", 40.dp, 13) { Updater.dialog = null } }
                        }
                    }
                }
            }
        }
    }
}

// ======================= 收藏的专辑 / 关注的歌手 =======================
