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
        T(prof?.name ?: tr("本地用户"), 15, w = 800, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        val acc by PluginStore.accounts.collectAsState()
        (listOfNotNull(prof?.let { tr("我的主页") to Page.User(it.uid, it.name) }, tr("设置") to Page.Settings) +
            Plugins.enabled.filter { it.has("loginQr") || it.has("loginWeb") }.filter { acc[it.id] == null }.map { "${tr("登录")} ${it.name}" to Page.PluginLogin(it.id) }).forEach { (t, pg) ->
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
    // 去掉 BoxWithConstraints：直接在 graphicsLayer 读 size.width，缩放/切 Tab 全程 0 Subcompose、0 重组
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = -size.width * x.value }) { HomePage() }
        if (libBuilt) Box(Modifier.fillMaxSize().graphicsLayer { translationX = size.width * (1f - x.value) }) { LibraryPage() }
    }
}

@Composable fun BottomNav() {
    val nav = LocalNav.current
    val p = c
    val active = nav.stack.isEmpty()
    val indX = animateFloatAsState(nav.tab.toFloat(), spF, label = "bnx")
    val indA = animateFloatAsState(if (active) 1f else 0f, Motion.quick(), label = "bna")
    Box(Modifier.fillMaxWidth().background(barBg()).drawBehind {
        if (!Look.flat) drawRect(p.k, size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
    }.navigationBarsPadding().padding(top = 3.dp).height(56.dp)) {
        // 与桌面 Sidebar 统一的连续滑动高亮块：切 Tab 时顺滑横移，0 重组
        Box(Modifier.fillMaxWidth(.5f).fillMaxHeight().graphicsLayer {
            translationX = size.width * indX.value
            alpha = indA.value
        }.background(p.p))
        Row(Modifier.fillMaxSize()) {
            listOf(Triple(NIcons.home, tr("主页"), 0), Triple(NIcons.library, tr("曲库"), 1)).forEach { (i, l, k) ->
                val on = nav.tab == k && active
                val selA = animateFloatAsState(if (on) 1f else 0f, Motion.standard(), label = "bns$k")
                val fg = if (on) Color.White else p.k
                Column(Modifier.weight(1f).fillMaxHeight().press(haptic = true) { nav.stack.clear(); nav.tab = k },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val cGain = (MotionEffects.selectCoverScale - 1f).coerceAtLeast(0f)
                    val fGain = (MotionEffects.selectFontScale - 1f).coerceAtLeast(0f)
                    Box(Modifier.graphicsLayer { val s = 1f + cGain * selA.value; scaleX = s; scaleY = s }) { NIcon(i, 22.dp, fg) }
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.graphicsLayer { val s = 1f + fGain * selA.value; scaleX = s; scaleY = s }) { T(l, 11, fg, 700) }
                }
            }
        }
    }
}

/** 子页面层：整页从右侧推入；多级页面按前进/返回双向推拉；左边缘 22dp 支持跟手滑返与速度甩动返回 */
@Composable fun SubLayer(sub: Animatable<Float, AnimationVector1D>, w: Float) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<Page?>(null) }
    nav.stack.lastOrNull()?.let { shown = it }
    val pg = shown ?: return
    val visible by remember { derivedStateOf { sub.value > 0.001f || nav.stack.isNotEmpty() } }
    if (!visible) return
    var lastDepth by remember { mutableIntStateOf(nav.stack.size) }
    val forward = nav.stack.size >= lastDepth
    SideEffect { lastDepth = nav.stack.size }
    val p = c
    val widthState by rememberUpdatedState(w.coerceAtLeast(1f))
    val originExp = MotionEffects.originExpand
    val containerMod = if (originExp) {
        Modifier.fillMaxSize().originContainerExpand({ sub.value }, { OriginBus.activePageOrigin() })
    } else {
        Modifier.fillMaxSize().graphicsLayer { translationX = size.width * (1f - sub.value) }.drawBehind {
            val edgeW = 14.dp.toPx()
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .14f * sub.value)), -edgeW, 0f),
                Offset(-edgeW, 0f), androidx.compose.ui.geometry.Size(edgeW, size.height))
        }
    }
    Box(containerMod.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        Box(Modifier.fillMaxSize().background(p.page)) { BgLayer() }
        Column(Modifier.fillMaxSize()) {
            AnimatedContent(pg, Modifier.weight(1f), transitionSpec = {
                if (AnimStyle.off) fadeIn(androidx.compose.animation.core.snap()) togetherWith fadeOut(androidx.compose.animation.core.snap())
                else if (originExp) {
                    val to = OriginBus.originTransformOrigin(useClosing = !forward)
                    if (forward) (androidx.compose.animation.scaleIn(Motion.page(), 0.84f, to) + fadeIn(Motion.page())) togetherWith
                        (androidx.compose.animation.scaleOut(Motion.standard(), 0.95f) + fadeOut(Motion.quick()))
                    else (androidx.compose.animation.scaleIn(Motion.page(), 0.95f) + fadeIn(Motion.page())) togetherWith
                        (androidx.compose.animation.scaleOut(Motion.standard(), 0.84f, to) + fadeOut(Motion.quick()))
                } else if (forward) {
                    (androidx.compose.animation.slideInHorizontally(Motion.page()) { (it * .20f).toInt() } + fadeIn(Motion.page())) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(Motion.standard()) { -(it * .12f).toInt() } + fadeOut(Motion.quick()))
                } else {
                    (androidx.compose.animation.slideInHorizontally(Motion.page()) { -(it * .12f).toInt() } + fadeIn(Motion.page())) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(Motion.standard()) { (it * .20f).toInt() } + fadeOut(Motion.quick()))
                }
            }, label = "sub") { page ->
                PageContent(page)
            }
            SubMiniBarHost(!(Screen.landscape && pg == Page.CoverFlow))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime.union(WindowInsets.navigationBars)))
        }
        Box(Modifier.fillMaxHeight().width(24.dp).pointerInput(Unit) {
            val vt = androidx.compose.ui.input.pointer.util.VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = { vt.resetTracking() },
                onDragEnd = {
                    val vx = vt.calculateVelocity().x
                    val dismiss = sub.value < .62f || vx > 900f
                    scope.launch {
                        if (dismiss && nav.stack.size > 1) { nav.stack.removeAt(nav.stack.lastIndex); sub.animateTo(1f, spF) }
                        else if (dismiss) nav.stack.clear()
                        else sub.animateTo(1f, spF)
                    }
                },
                onDragCancel = { scope.launch { sub.animateTo(1f, spF) } },
            ) { ch, dx ->
                vt.addPosition(ch.uptimeMillis, ch.position)
                scope.launch { sub.snapTo((sub.value - dx / widthState).coerceIn(0f, 1f)) }
            }
        })
    }
}

/** 独立作用域：避免播放状态变动牵连整个 SubLayer 重组 */
@Composable private fun SubMiniBarHost(allow: Boolean) {
    if (!allow) return
    val hasTrack by remember { Engine.state }.collectAsState().let { s -> remember { derivedStateOf { s.value.current != null } } }
    if (hasTrack) MiniBar()
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
