package cc.catsuki.nekoplay

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.detectHorizontalDragGestures

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

sealed interface Page {
    data class Detail(val kind: String, val id: String, val title: String, val cover: String? = null) : Page
    data object Search : Page
    data object Settings : Page
    data object Login : Page
    data class Artist(val id: String, val name: String) : Page
    data class Album(val id: String, val name: String, val cover: String? = null) : Page
    data class User(val uid: String, val name: String = "") : Page
    data class Playlists(val kind: String, val arg: String, val title: String) : Page
    data object Square : Page
    data object NewAlbums : Page
    data object History : Page
    data class Subs(val kind: String) : Page
    data class Rank(val uid: String, val name: String) : Page
    data class Mv(val id: String, val name: String, val artist: String = "") : Page
    data object Mvs : Page
    data object Radios : Page
    data object Background : Page
    data object HomeLayout : Page
    data object Effects : Page
    data object Fm : Page
    data object Tuning : Page
    data object KeyMap : Page
    data object DlList : Page
    data object Local : Page
    data object Backup : Page
    data object Stats : Page
    data class LyricCard(val t: Track) : Page
}

/** 全局界面状态 */
class Nav {
    var tab by mutableIntStateOf(0)
    val stack = mutableStateListOf<Page>()
    var player by mutableStateOf(false)
    var lyrics by mutableStateOf(false)
    var queue by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    var menu by mutableStateOf(false)
    var sheet by mutableStateOf<SheetSpec?>(null)
    var comments by mutableStateOf<Track?>(null)
    /** 宽屏右侧面板：0 关 / 1 歌词 / 2 播放队列 */
    var side by mutableIntStateOf(0) // 默认不打开
    var input by mutableStateOf<InputSpec?>(null)
    fun push(p: Page) { stack.add(p) }
    fun back(): Boolean = when {
        input != null -> { input = null; true }
        sheet != null -> { sheet = null; true }
        comments != null -> { comments = null; true }
        menu -> { menu = false; true }
        queue -> { queue = false; true }
        player -> { player = false; true }
        stack.isNotEmpty() -> { stack.removeAt(stack.lastIndex); true }
        tab == 1 -> { tab = 0; true }
        else -> false
    }
}
val LocalNav = staticCompositionLocalOf { Nav() }

/** 两端共用的启动初始化（在 Plat.impl 赋值之后调用） */
object Boot {
    fun init() {
        Ncm.init(); Store.init(); Engine.init(); Bg.init(); HomeLayout.init(); FxStore.init(); Downloads.init(); LocalMusic.load(); Stats.load()
        reloadPrefs(); Backup.load()
    }
    /** 重新读一遍设置（启动时、从备份恢复后） */
    fun reloadPrefs() {
        val ui = Plat.prefs("ui")
        Anim.level = ui.getInt("anim", 1)
        ThemePref.load(); Typo.load(); Look.load(); LyricPref.load(); AnimStyle.load(); UiPref.load(); Vol.load(); Monet.load(); Sleep.load(); DeskLyric.load()
        Perf.overlay.value = ui.getBoolean("perf", false)
        Store.reloadLiked(); FxStore.init(); HomeLayout.init()
    }
}

/** 动效速度：0 慢 / 1 标准 / 2 快 / 3 很快。弹簧的时长约与 1/√刚度 成正比，所以刚度按速度的平方缩放 */
object Anim {
    val labels = listOf("慢", "标准", "快", "很快")
    private val factors = listOf(0.7f, 1f, 1.4f, 2f)
    var level by mutableIntStateOf(1)
    val f get() = factors[level]
    fun stiff(base: Float) = base * f * f
}
/** 动效形式：0 弹簧（带一点回弹感的物理动画）/ 1 缓动（先快后慢）/ 2 线性 / 3 关闭（瞬间切换） */
object AnimStyle {
    val labels = listOf("弹簧", "缓动", "线性", "关闭")
    var style by mutableIntStateOf(0)
    fun load() { style = Plat.prefs("ui").getInt("animStyle", 0) }
    fun set(v: Int) { style = v; Plat.prefs("ui").edit().putInt("animStyle", v).apply() }
    val ms get() = (320 / Anim.f).toInt()
}
private fun <T> styled(stiff: Float): androidx.compose.animation.core.FiniteAnimationSpec<T> = when (AnimStyle.style) {
    1 -> androidx.compose.animation.core.tween(AnimStyle.ms, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    2 -> androidx.compose.animation.core.tween(AnimStyle.ms, easing = androidx.compose.animation.core.LinearEasing)
    3 -> androidx.compose.animation.core.snap()
    else -> spring(dampingRatio = .92f, stiffness = Anim.stiff(stiff))
}
val sp: androidx.compose.animation.core.FiniteAnimationSpec<IntOffset> get() = styled(Spring.StiffnessMediumLow)
val spF: androidx.compose.animation.core.FiniteAnimationSpec<Float> get() = styled(Spring.StiffnessMediumLow)

/** chrome：桌面版的自绘标题栏（放在最上面，能用 LocalNav）；手机传 null */
@Composable fun Root(chrome: (@Composable () -> Unit)? = null) {
    val nav = remember { Nav() }
    LaunchedEffect(Unit) { Downloads.onMessage = { nav.toast = it } }
    SideEffect { Keys.nav = nav }
    val st by Engine.state.collectAsState()
    val err by Engine.error.collectAsState()
    LaunchedEffect(err) { if (err != null) { nav.toast = err; Engine.error.value = null } }
    LaunchedEffect(nav.toast) { if (nav.toast != null) { delay(2200); nav.toast = null } }
    Plat.impl.BackHandler(nav.input != null || nav.sheet != null || nav.comments != null || nav.menu || nav.queue || nav.player || nav.stack.isNotEmpty() || nav.tab == 1) { nav.back() }
    val hasTrack = st.current != null

    CompositionLocalProvider(LocalNav provides nav) {
        BoxWithConstraints(Modifier.fillMaxSize().backdrop()) {
            // 窗口宽度 ≥ 900dp：PC 三栏布局；否则就是手机布局（拖动窗口大小会实时切换）
            val wide = maxWidth >= 900.dp
            SideEffect { Screen.wide = wide }
            BgLayer()
            Column(Modifier.fillMaxSize()) {
            chrome?.invoke()
            Box(Modifier.weight(1f).fillMaxWidth()) {
            if (wide) WideLayout() else Column(Modifier.fillMaxSize()) {
                if (chrome == null) TopBar()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    val w = constraints.maxWidth.toFloat()
                    val sub = remember { Animatable(0f) }
                    val inSub = nav.stack.isNotEmpty()
                    LaunchedEffect(inSub) { sub.animateTo(if (inSub) 1f else 0f, spF) }
                    // 首页 / 曲库：子页面推入时往左退 28% 并变暗（视差），和 Flutter 版一致
                    Column(Modifier.fillMaxSize().graphicsLayer { translationX = -w * .28f * sub.value }) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { Tabs() }
                        if (hasTrack) MiniBar()
                        BottomNav()
                    }
                    if (sub.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .16f * sub.value)))
                    SubLayer(sub, w)
                    PlayerOverlay()
                }
            }
            }
            }
            if (nav.menu) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { nav.menu = false } })
                AccountMenu(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = if (chrome != null) 54.dp else 59.dp, end = 12.dp))
            }
            if (!wide) QueueSheet()
            if (!wide) CommentSheet() // PC 宽屏：评论放在右栏
            ActionSheet(wide)
            InputDialog()
            AnimatedVisibility(nav.toast != null, Modifier.align(Alignment.BottomCenter).padding(bottom = if (hasTrack) 150.dp else 90.dp, start = 24.dp, end = 24.dp).navigationBarsPadding(),
                enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
                Box(Modifier.background(c.k).padding(horizontal = 16.dp, vertical = 11.dp)) { T(nav.toast ?: "", 14, c.tagFg, lines = 3, lh = 1.4f, align = TextAlign.Center) }
            }
            val po by Perf.overlay.collectAsState()
            // 桌面版没有系统帧统计：浮窗开着时自己数帧（两帧间隔超过 1.5 倍刷新周期算卡顿）
            if (po && Plat.isDesktop) LaunchedEffect(Unit) {
                var last = 0L
                while (true) androidx.compose.runtime.withFrameNanos { t -> if (last != 0L) { val d = t - last; Perf.frame(d, 0, d > 25_000_000) }; last = t }
            }
            if (po) {
                val ps by Perf.stat.collectAsState()
                Box(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 6.dp, bottom = 140.dp).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 4.dp)) {
                    T(if (Plat.isDesktop) "CPU %.0f%%  %dfps\n帧 %.1fms 卡%d\n内存 %dM".format(ps.cpu, ps.fps, ps.frameMs, ps.jank, ps.pss)
                      else "CPU %.0f%%  %dfps\n帧 %.1f GPU %.1fms 卡%d\nGPU %s  PSS %dM".format(ps.cpu, ps.fps, ps.frameMs, ps.gpuMs, ps.jank, ps.gpu ?: "-", ps.pss), 10, Color.White, mono = true, lines = 3, lh = 1.3f)
                }
            }
        }
    }
}

@Composable fun TopBar() {
    val nav = LocalNav.current
    val p = c
    Row(Modifier.fillMaxWidth().background(barBg()).statusBarsPadding().height(56.dp).drawBehind {
        if (!Look.flat) drawRect(p.k, Offset(0f, size.height - 3.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
    }.padding(start = 18.dp, end = 6.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        T("NEKOPLAY", Fs.PAGE, w = 900, ls = .02f, modifier = Modifier.press { nav.player = false; nav.stack.clear(); nav.tab = 0 })
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(44.dp).press(.92f, true) { nav.player = false; nav.push(Page.Search) }, contentAlignment = Alignment.Center) { NIcon(NIcons.search, 22.dp) }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(44.dp).press { nav.menu = !nav.menu }, contentAlignment = Alignment.Center) { Avatar(34.dp, 16) }
        Spacer(Modifier.width(6.dp))
    }
}

@Composable fun AccountMenu(modifier: Modifier) {
    val nav = LocalNav.current
    val prof by Store.profile.collectAsState()
    Column(modifier.width(190.dp).background(if (c.dark) c.pop else Color(0xFFF1F7F8)).nb(1.dp, c.k)) {
        T(prof?.name ?: "好奇猫a", 15, w = 800, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        listOfNotNull(prof?.let { "我的主页" to Page.User(it.uid, it.name) }, "设置" to Page.Settings, if (prof == null) "登录网易云" to Page.Login else null).forEach { (t, pg) ->
            Box(Modifier.fillMaxWidth().height(48.dp).press { nav.menu = false; nav.player = false; nav.push(pg) }.drawBehind { drawRect(Color(0x1F888888), size = androidx.compose.ui.geometry.Size(size.width, 1f)) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart) { T(t, 15) }
        }
    }
}

@Composable fun Tabs() {
    val nav = LocalNav.current
    var libBuilt by remember { mutableStateOf(false) }
    if (nav.tab == 1) libBuilt = true
    val x by animateFloatAsState(nav.tab.toFloat(), spF, label = "tab")
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        if (x < 1f) Box(Modifier.fillMaxSize().graphicsLayer { translationX = -w * x }) { HomePage() }
        if (libBuilt && x > 0f) Box(Modifier.fillMaxSize().graphicsLayer { translationX = w * (1 - x) }) { LibraryPage() }
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
    Box(Modifier.fillMaxSize().graphicsLayer { translationX = w * (1 - sub.value) }) {
        // 子页面底色；有自定义背景时铺同一张背景
        Box(Modifier.fillMaxSize().background(p.page)) { BgLayer() }
        Column(Modifier.fillMaxSize()) {
            // 子页面之间切换（例如设置 → 登录）：快速淡入淡出
            AnimatedContent(pg, Modifier.weight(1f), transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "sub") { page ->
                PageContent(page)
            }
            if (Engine.state.collectAsState().value.current != null) MiniBar()
            // 键盘弹出时迷你播放条跟着键盘顶部一起上移（吸附在键盘上），收起时回到导航栏上方
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
    if (page !is Page.Search) Row(Modifier.fillMaxWidth().height(48.dp).drawBehind { if (!Look.flat) drawRect(p.l2, Offset(0f, size.height - 1.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }.padding(start = 6.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).press { nav.back() }, contentAlignment = Alignment.Center) { NIcon(NIcons.back, 20.dp) }
        T(when (page) { is Page.Detail -> "LIST"; Page.Settings -> "SETTINGS"; Page.Login -> "LOGIN"; is Page.Artist -> "ARTIST"; is Page.Album -> "ALBUM"; is Page.User -> "USER"; is Page.Playlists -> "PLAYLISTS"; Page.Square -> "PLAYLISTS"; Page.NewAlbums -> "ALBUMS"; Page.History -> "HISTORY"; is Page.Subs -> if (page.kind == "album") "ALBUMS" else "ARTISTS"; is Page.Rank -> "RANKING"; is Page.Mv -> "MV"; Page.Mvs -> "MV"; Page.Radios -> "PODCAST"; Page.Background -> "BACKGROUND"; Page.HomeLayout -> "LAYOUT"; Page.Effects -> "EFFECTS"; Page.Fm -> "FM"; Page.Tuning -> "MOTION"; Page.KeyMap -> "KEYS"; Page.DlList -> "DOWNLOADS"; Page.Local -> "LOCAL"; Page.Backup -> "BACKUP"; Page.Stats -> "STATS"; is Page.LyricCard -> "CARD"; else -> "" }, 12, p.s, mono = true, ls = .1f)
    }
    Box(Modifier.weight(1f)) {
        when (page) {
            is Page.Detail -> DetailPage(page)
            Page.Search -> SearchPage()
            Page.Settings -> SettingsPage()
            Page.Login -> LoginPage()
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
    Page.Fm -> FmPage()
    Page.Tuning -> TuningPage()
    Page.KeyMap -> KeysPage()
    Page.DlList -> DownloadsPage()
    Page.Local -> LocalMusicPage()
    Page.Backup -> BackupPage()
    Page.Stats -> StatsPage()
    is Page.LyricCard -> LyricCardPage(page.t)
        }
    }
}
}

/** 键盘快捷键（桌面）：拿到当前界面状态 */
object Keys {
    var nav: Nav? = null
    val HELP = listOf(
        "空格" to "播放 / 暂停", "Ctrl + →" to "下一首", "Ctrl + ←" to "上一首", "→ / ←" to "快进 / 快退 5 秒",
        "Ctrl + ↑ / ↓" to "音量 + / −", "Ctrl + L" to "喜欢 / 取消喜欢", "Ctrl + P" to "打开 / 收起播放页",
        "Ctrl + F" to "搜索", "Ctrl + Q" to "播放队列", "Ctrl + D" to "歌词栏", "Ctrl + ," to "设置", "Esc" to "返回",
        "媒体键" to "播放 / 暂停、上一首、下一首（窗口在前台时）",
    )
    /** 带 Ctrl 的组合键和媒体键：输入框里也生效 */
    fun handleCtrl(key: androidx.compose.ui.input.key.Key, ctrl: Boolean): Boolean {
        val n = nav ?: return false
        val K = androidx.compose.ui.input.key.Key
        when (key) {
            K.MediaPlayPause -> { Engine.toggle(); return true }
            K.MediaNext -> { Engine.next(); return true }
            K.MediaPrevious -> { Engine.prev(); return true }
        }
        if (!ctrl) return false
        when (key) {
            K.DirectionRight -> Engine.next()
            K.DirectionLeft -> Engine.prev()
            K.DirectionUp -> Vol.set(Vol.v + .05f)
            K.DirectionDown -> Vol.set(Vol.v - .05f)
            K.Spacebar -> Engine.toggle()
            K.L -> Engine.state.value.current?.let { t -> Store.toggleLike(t) { n.toast = it }; n.toast = if (t.id in Store.likedIds.value) "已喜欢" else "已取消喜欢" }
            K.P -> { if (Engine.state.value.current != null) { n.lyrics = false; n.player = !n.player } }
            K.F -> { n.player = false; n.stack.clear(); n.push(Page.Search) }
            K.Q -> n.queue = true
            K.D -> n.side = if (n.side == 1) 0 else 1
            K.Comma -> { n.player = false; n.stack.clear(); n.push(Page.Settings) }
            else -> return false
        }
        return true
    }
    /** 不带修饰键的：输入框没用掉才会到这里 */
    fun handlePlain(key: androidx.compose.ui.input.key.Key): Boolean {
        val K = androidx.compose.ui.input.key.Key
        when (key) {
            K.Spacebar -> Engine.toggle()
            K.DirectionRight -> Engine.seek(Engine.position.value + 5000)
            K.DirectionLeft -> Engine.seek((Engine.position.value - 5000).coerceAtLeast(0))
            else -> return false
        }
        return true
    }
}

/** 标题栏搜索框 → 搜索页：把要搜的词交给搜索页 */
object SearchBus { var query by mutableStateOf<String?>(null) }
