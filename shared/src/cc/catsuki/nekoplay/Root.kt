package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.ui.settings.BackupPage
import cc.catsuki.nekoplay.ui.settings.DavMusicPage
import cc.catsuki.nekoplay.ui.settings.DebugPage
import cc.catsuki.nekoplay.ui.settings.DownloadsPage
import cc.catsuki.nekoplay.ui.settings.FeaturesPage
import cc.catsuki.nekoplay.ui.settings.FxPage
import cc.catsuki.nekoplay.ui.settings.LocalMusicPage
import cc.catsuki.nekoplay.ui.settings.StatsPage

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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
    /** 登录主插件（首页来源）的账号 */
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
    data object Vst : Page
    data object Fm : Page
    data object Tuning : Page
    data object KeyMap : Page
    data object DlList : Page
    data object Local : Page
    data object Dav : Page
    data object Backup : Page
    data object DebugLog : Page
    data class ArtistAlbums(val id: String, val name: String) : Page
    data object Stats : Page
    /** 设置的二级分类页 */
    data class SetSub(val sec: String) : Page
    data object Feats : Page
    data object PluginMgr : Page
    data class PluginLogin(val pid: String) : Page
    data object BtnLayout : Page
    data class LyricCard(val t: Track) : Page
    data object CoverFlow : Page
    data class PluginPage(val id: String) : Page
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
    /** 封面墙宽屏模式下是否收起左侧边栏 */
    var hideSidebar by mutableStateOf(false)
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
    /** 快速首屏初始化：只加载界面渲染必需的轻量偏好与本地队列 */
    fun initFast() {
        AppGraph.current.playerConfig = PlayerConfig(preferredHomePlugin = { UiPref.homePlugin })
        AppGraph.current.initFast { Bg.init(); HomeLayout.init(); reloadPrefs() }
    }
    /** 延后后台初始化：首帧画完后再加载插件、WebDAV、更新检查、本地库与统计 */
    fun initDeferred() = AppGraph.current.initDeferred()
    fun init() { initFast(); initDeferred() }
    /** 重新读一遍设置（启动时、从备份恢复后） */
    fun reloadPrefs() {
        ThemePref.load(); ThemeStore.load(); Typo.load(); Look.load(); LyricPref.load(); AnimStyle.load(); UiPref.load(); Vol.load(); Monet.load(); Sleep.load(); DeskLyric.load(); DiscordPref.load(); Debug.load(); Features.load(); PlayerLayout.load(); SidePref.load(); DlPref.load()
        Perf.overlay.value = Plat.prefs("ui").getBoolean("perf", false)
        Store.reloadLiked(); FxStore.init(); HomeLayout.init()
    }
}

/** 动效速度：0 慢 / 1 标准 / 2 快 / 3 很快。弹簧的时长约与 1/√刚度 成正比，所以刚度按速度的平方缩放 */
/** chrome：桌面版的自绘标题栏（放在最上面，能用 LocalNav）；手机传 null */
@Composable fun Root(chrome: (@Composable () -> Unit)? = null) {
    val nav = remember { Nav() }
    LaunchedEffect(Unit) { Downloads.onMessage = { nav.toast = it } }
    // 启动自动检查更新：首页渲染稳定后静默请求，有新版本弹出带动效的更新提示框
    LaunchedEffect(Unit) { delay(2500); Updater.autoCheck() }
    SideEffect { Keys.nav = nav }
    LaunchedEffect(Unit) { withFrameNanos { Perf.markHomeReady() } }
    val st by Engine.state.collectAsState()
    val err by Engine.error.collectAsState()
    val openPlayer by PlayerBus.openSeq.collectAsState()
    LaunchedEffect(openPlayer) { if (openPlayer > 0 && Engine.state.value.current != null) nav.player = true }
    // 手机横屏且已启用 3D 封面墙功能插件时自动进入封面墙；转回竖屏自动退出
    val allPlugs by Plugins.list.collectAsState()
    LaunchedEffect(Plugins.rev) { AnimStyle.applyPlugin(Plugins.activeMotionProfile()) }
    val cfEnabled = remember(allPlugs, Plugins.off) { Plugins.hasFeature("coverflow") }
    var autoCf by remember { mutableStateOf(false) }
    LaunchedEffect(Screen.landscape, cfEnabled) {
        if (Screen.landscape && cfEnabled) {
            if (nav.stack.lastOrNull() != Page.CoverFlow) { nav.player = false; nav.push(Page.CoverFlow); autoCf = true }
        } else if (!Screen.landscape && autoCf) {
            if (nav.stack.lastOrNull() == Page.CoverFlow) nav.stack.removeAt(nav.stack.lastIndex)
            autoCf = false
        }
    }
    LaunchedEffect(err) { if (err != null) { nav.toast = err; Engine.error.value = null } }
    LaunchedEffect(nav.toast) { if (nav.toast != null) { delay(2200); nav.toast = null } }
    Plat.impl.BackHandler(Updater.dialog != null || nav.input != null || nav.sheet != null || nav.comments != null || nav.menu || nav.queue || nav.player || nav.stack.isNotEmpty() || nav.tab == 1) {
        if (Updater.dialog != null) Updater.dialog = null else nav.back()
    }
    val hasTrack = st.current != null

    CompositionLocalProvider(LocalNav provides nav) {
        val dens = LocalDensity.current
        // 用普通 Box + onSizeChanged 代替顶层 BoxWithConstraints（SubcomposeLayout），
        // 拖动缩放窗口时只要没跨过 900dp 宽窄阈值，整个 Root 树 0 重组、0 Subcompose，原生丝滑
        Box(Modifier.fillMaxSize().backdrop().onSizeChanged { sz ->
            val wDp = sz.width / dens.density
            val hDp = sz.height / dens.density
            val w = wDp >= 900f; val sh = hDp < 720f; val nr = wDp < 360f; val ls = !Plat.isDesktop && wDp > hDp && hDp < 560f
            if (Screen.wide != w) Screen.wide = w
            if (Screen.short != sh) Screen.short = sh
            if (Screen.narrow != nr) Screen.narrow = nr
            if (Screen.landscape != ls) Screen.landscape = ls
        }) {
            val wide = Screen.wide
            BgLayer()
            Column(Modifier.fillMaxSize()) {
            chrome?.invoke()
            Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(wide, Modifier.fillMaxSize(), transitionSpec = {
                fadeIn(Motion.standard()) togetherWith fadeOut(Motion.quick())
            }, label = "layoutMode") { isWide ->
            if (isWide) WideLayout() else Column(Modifier.fillMaxSize()) {
                val hideTop = (Screen.short && nav.player) || (Screen.landscape && nav.stack.lastOrNull() == Page.CoverFlow)
                if (chrome == null) androidx.compose.animation.AnimatedVisibility(!hideTop,
                    enter = androidx.compose.animation.expandVertically(Motion.hero()),
                    exit = androidx.compose.animation.shrinkVertically(Motion.hero())) { TopBar() }
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
            }
            if (nav.menu) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { nav.menu = false } })
            AnimatedVisibility(nav.menu, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = if (chrome != null) 54.dp else 59.dp, end = 12.dp),
                enter = fadeIn(Motion.standard()) + scaleIn(Motion.standard(), initialScale = if (MotionEffects.pageScale) .92f else 1f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)),
                exit = fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = if (MotionEffects.pageScale) .95f else 1f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f))) {
                AccountMenu(Modifier)
            }
            if (!wide) QueueSheet()
            if (!wide) CommentSheet() // PC 宽屏：评论放在右栏
            ActionSheet(wide)
            InputDialog()
            UpdateDialog(wide)
            AnimatedVisibility(nav.toast != null, Modifier.align(Alignment.BottomCenter).padding(bottom = if (hasTrack) 150.dp else 90.dp, start = 24.dp, end = 24.dp).navigationBarsPadding(),
                enter = fadeIn(Motion.standard()) + slideInVertically(Motion.standard()) { it / 2 } + scaleIn(Motion.standard(), initialScale = if (MotionEffects.pageScale) .95f else 1f),
                exit = fadeOut(Motion.quick()) + slideOutVertically(Motion.quick()) { it / 3 }) {
                Box(Modifier.background(c.k).padding(horizontal = 16.dp, vertical = 11.dp)) { T(nav.toast ?: "", 14, c.tagFg, lines = 3, lh = 1.4f, align = TextAlign.Center) }
            }
            PerfOverlay(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 6.dp, bottom = 140.dp))
        }
    }
}

/** 独立作用域：每秒刷新性能数据只重组这个小浮窗，不牵连整个 Root */
@Composable private fun PerfOverlay(modifier: Modifier) {
    val po by Perf.overlay.collectAsState()
    if (!po) return
    // 桌面版没有系统帧统计：浮窗开着时自己数帧。
    // 刷新周期取最近 120 帧的中位数（60Hz 稳在 16.7ms，120Hz 稳在 8.3ms，不会被单次提前提交的短帧带偏误报掉帧），
    // 间隔超过 1.5 倍算掉帧；超过 0.5 秒的空档是窗口在后台 / 被系统节流，不算
    if (Plat.isDesktop) LaunchedEffect(Unit) {
        var last = 0L
        val win = ArrayDeque<Long>()
        while (true) androidx.compose.runtime.withFrameNanos { t ->
            val d = t - last
            if (last != 0L && d < 500_000_000L) {
                win.addLast(d); if (win.size > 120) win.removeFirst()
                val period = if (win.size >= 10) win.sorted()[win.size / 2].coerceIn(7_000_000L, 18_000_000L) else 16_666_667L
                Perf.frame(d, 0, win.size >= 10 && d > period * 3 / 2)
            }
            last = t
        }
    }
    val ps by Perf.stat.collectAsState()
    Box(modifier.background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 4.dp)) {
        T(if (Plat.isDesktop) "CPU %.0f%%  %dfps\n帧 %.1fms 掉帧%d/s\n内存 %dM".format(ps.cpu, ps.fps, ps.frameMs, ps.jank, ps.pss)
          else "CPU %.0f%%  %dfps\n帧 %.1f GPU %.1fms 掉帧%d/s\nGPU %s  PSS %dM".format(ps.cpu, ps.fps, ps.frameMs, ps.gpuMs, ps.jank, ps.gpu ?: "-", ps.pss), 10, Color.White, mono = true, lines = 3, lh = 1.3f)
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
        TopActions(44.dp, 22.dp, 34.dp)
        Spacer(Modifier.width(6.dp))
    }
}
