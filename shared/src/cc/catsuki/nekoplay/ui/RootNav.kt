package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.ui.settings.BackupPage
import cc.catsuki.nekoplay.ui.settings.DavMusicPage
import cc.catsuki.nekoplay.ui.settings.DebugPage
import cc.catsuki.nekoplay.ui.settings.DownloadsPage
import cc.catsuki.nekoplay.ui.settings.FeaturesPage
import cc.catsuki.nekoplay.ui.settings.FxPage
import cc.catsuki.nekoplay.ui.settings.VstPage
import cc.catsuki.nekoplay.ui.settings.LocalMusicPage
import cc.catsuki.nekoplay.ui.settings.StatsPage

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable fun AccountMenu(modifier: Modifier) {
    val nav = LocalNav.current
    val p = c
    val prof by Store.profile.collectAsState()
    Column(modifier.width(190.dp).background(p.pop).nb(1.dp, p.k)) {
        T(prof?.name ?: "本地用户", 15, w = 800, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        val acc by PluginStore.accounts.collectAsState()
        (listOfNotNull(prof?.let { "我的主页" to Page.User(it.uid, it.name) }, "设置" to Page.Settings) +
            Plugins.enabled.filter { it.has("loginQr") || it.has("loginWeb") }.filter { acc[it.id] == null }.map { "登录${it.name}" to Page.PluginLogin(it.id) }).forEach { (t, pg) ->
            Box(Modifier.fillMaxWidth().height(48.dp).press { nav.menu = false; nav.player = false; nav.push(pg) }.drawBehind { drawRect(p.l1, size = androidx.compose.ui.geometry.Size(size.width, 1f)) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart) { T(t, 15) }
        }
    }
}

@Composable fun Tabs() {
    val nav = LocalNav.current
    var libBuilt by remember { mutableStateOf(false) }
    if (nav.tab == 1) libBuilt = true
    val x = animateFloatAsState(nav.tab.toFloat(), spF, label = "tab")
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = -w * x.value }) { HomePage() }
        if (libBuilt) Box(Modifier.fillMaxSize().graphicsLayer { translationX = w * (1 - x.value) }) { LibraryPage() }
    }
}

@Composable fun BottomNav() {
    val nav = LocalNav.current
    val p = c
    Row(Modifier.fillMaxWidth().background(barBg()).drawBehind { if (!Look.flat) drawRect(p.k, size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx())) }.navigationBarsPadding().padding(top = 3.dp).height(56.dp)) {
        listOf(Triple(NIcons.home, "主页", 0), Triple(NIcons.library, "曲库", 1)).forEach { (i, l, k) ->
            val on = nav.tab == k && nav.stack.isEmpty()
            Column(Modifier.weight(1f).fillMaxHeight().press(haptic = true) { nav.stack.clear(); nav.tab = k }.background(if (on) p.p else Color.Transparent),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                NIcon(i, 22.dp, if (on) Color.White else p.k); Spacer(Modifier.height(2.dp)); T(l, 11, if (on) Color.White else p.k, 700)
            }
        }
    }
}

/** 子页面层：整页从右侧推入；左边缘 22dp 内可以跟手滑动返回 */
@Composable fun SubLayer(sub: Animatable<Float, AnimationVector1D>, w: Float) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<Page?>(null) }
    nav.stack.lastOrNull()?.let { shown = it }
    val pg = shown ?: return
    if (sub.value <= 0f && nav.stack.isEmpty()) return
    val p = c
    Box(Modifier.fillMaxSize().graphicsLayer { translationX = w * (1 - sub.value) }
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        Box(Modifier.fillMaxSize().background(p.page)) { BgLayer() }
        Column(Modifier.fillMaxSize()) {
            AnimatedContent(pg, Modifier.weight(1f), transitionSpec = { fadeIn(Motion.standard()) togetherWith fadeOut(Motion.quick()) }, label = "sub") { page ->
                PageContent(page)
            }
            if (Engine.state.collectAsState().value.current != null && !(Screen.landscape && pg == Page.CoverFlow)) MiniBar()
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars)))
        }
        Box(Modifier.fillMaxHeight().width(22.dp).pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    scope.launch {
                        if (sub.value < .6f && nav.stack.size > 1) { nav.stack.removeAt(nav.stack.lastIndex); sub.animateTo(1f, spF) }
                        else if (sub.value < .6f) nav.stack.clear()
                        else sub.animateTo(1f, spF)
                    }
                },
                onDragCancel = { scope.launch { sub.animateTo(1f, spF) } },
            ) { _, dx -> scope.launch { sub.snapTo((sub.value - dx / w).coerceIn(0f, 1f)) } }
        })
    }
}

/** 子页面：顶部返回栏 + 页面内容（手机子页面层和宽屏内容区共用） */
@Composable fun PageContent(page: Page) {
    val nav = LocalNav.current
    val p = c
    Column(Modifier.fillMaxSize()) {
        if (page !is Page.Search && !(Screen.landscape && page == Page.CoverFlow)) Row(Modifier.fillMaxWidth().height(48.dp).drawBehind { if (!Look.flat) drawRect(p.l2, Offset(0f, size.height - 1.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
            .press { nav.back() }.padding(start = 6.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { NIcon(NIcons.back, 20.dp) }
            T(when (page) { is Page.Detail -> "LIST"; Page.Settings -> "SETTINGS"; Page.Login -> "LOGIN"; is Page.Artist -> "ARTIST"; is Page.Album -> "ALBUM"; is Page.User -> "USER"; is Page.Playlists -> "PLAYLISTS"; Page.Square -> "PLAYLISTS"; Page.NewAlbums -> "ALBUMS"; Page.History -> "HISTORY"; is Page.Subs -> if (page.kind == "album") "ALBUMS" else "ARTISTS"; is Page.Rank -> "RANKING"; is Page.Mv -> "MV"; Page.Mvs -> "MV"; Page.Radios -> "PODCAST"; Page.Background -> "BACKGROUND"; Page.HomeLayout -> "LAYOUT"; Page.Effects -> "EFFECTS"; Page.Vst -> "VST · BETA"; Page.Fm -> "FM"; Page.Tuning -> "MOTION"; Page.KeyMap -> "KEYS"; Page.DlList -> "DOWNLOADS"; Page.Local -> "LOCAL"; Page.Dav -> "WEBDAV"; Page.Backup -> "BACKUP"; Page.DebugLog -> "DEBUG"; is Page.ArtistAlbums -> "ALBUMS"; Page.Stats -> "STATS"; is Page.SetSub -> "SETTINGS / " + page.sec.uppercase(); Page.Feats -> "FEATURES"; Page.PluginMgr -> "PLUGINS"; is Page.PluginLogin -> "LOGIN"; Page.BtnLayout -> "PLAYER LAYOUT"; is Page.LyricCard -> "CARD"; Page.CoverFlow -> "COVER FLOW"; is Page.PluginPage -> (Plugins.get(page.id)?.name ?: page.id).uppercase() }, 12, p.s, mono = true, ls = .1f)
        }
        Box(Modifier.weight(1f)) {
            when (page) {
                is Page.Detail -> DetailPage(page)
                Page.Search -> SearchPage()
                Page.Settings -> SettingsPage()
                Page.Login -> Plugins.main()?.let { PluginLoginPage(it.id) } ?: PluginsPage()
                is Page.Artist -> ArtistPage(page)
                is Page.Album -> AlbumPage(page)
                is Page.User -> UserPage(page)
                is Page.Playlists -> PlaylistsPage(page)
                Page.Square -> SquarePage()
                Page.NewAlbums -> NewAlbumsPage()
                Page.History -> HistoryPage()
                is Page.Subs -> SubsPage(page)
                is Page.Rank -> RankPage(page)
                is Page.Mv -> MvPage(page)
                Page.Mvs -> MvsPage()
                Page.Radios -> RadiosPage()
                Page.Background -> BgEditorPage()
                Page.HomeLayout -> HomeLayoutPage()
                Page.Effects -> FxPage()
                Page.Vst -> VstPage()
                Page.Fm -> FmPage()
                Page.Tuning -> TuningPage()
                Page.KeyMap -> KeysPage()
                Page.DlList -> DownloadsPage()
                Page.Local -> LocalMusicPage()
                Page.Dav -> DavMusicPage()
                Page.Backup -> BackupPage()
                Page.DebugLog -> DebugPage()
                is Page.ArtistAlbums -> ArtistAlbumsPage(page)
                Page.Stats -> StatsPage()
                is Page.SetSub -> SettingsPage(page.sec)
                Page.Feats -> FeaturesPage()
                Page.PluginMgr -> PluginsPage()
                is Page.PluginLogin -> PluginLoginPage(page.pid)
                Page.BtnLayout -> PlayerLayoutPage()
                is Page.LyricCard -> LyricCardPage(page.t)
                Page.CoverFlow -> CoverFlowPage()
                is Page.PluginPage -> PluginPageScreen(page.id)
            }
        }
    }
}

/** 顶栏右边的搜索 + 头像：在搜索页时搜索图标收起（淡出），播放页打开时不显示头像。两端共用。 */
@Composable fun TopActions(box: Dp, icon: Dp, avatar: Dp) {
    val nav = LocalNav.current
    val onSearch = nav.stack.lastOrNull() is Page.Search && !nav.player
    AnimatedVisibility(!onSearch, enter = fadeIn(Motion.standard()) + expandHorizontally(Motion.standard()), exit = fadeOut(Motion.quick()) + shrinkHorizontally(Motion.quick())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(box).press(.92f, true) { nav.player = false; nav.comments = null; if (nav.stack.lastOrNull() !is Page.Search) nav.push(Page.Search) }, contentAlignment = Alignment.Center) { NIcon(NIcons.search, icon) }
            Spacer(Modifier.width(6.dp))
        }
    }
    AnimatedVisibility(!nav.player, enter = fadeIn(Motion.standard()), exit = fadeOut(Motion.quick())) {
        Box(Modifier.size(box).press { nav.menu = !nav.menu }, contentAlignment = Alignment.Center) { Avatar(avatar, (avatar.value * .45f).toInt()) }
    }
}
