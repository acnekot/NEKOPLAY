package cc.catsuki.nekoplay

import androidx.compose.ui.graphics.toComposeImageBitmap

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Tray
import androidx.compose.runtime.*
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.window.WindowDraggableArea
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath

fun main() {
    if (!SingleInstance.acquire()) kotlin.system.exitProcess(0)
    ClickThrough.setAppId()
    Perf.markLaunch()
    Plat.impl = DesktopPlat()
    // 渲染方式：默认显卡（Direct3D）。软件渲染省约 170MB 内存，但实测 175% 缩放下主页约 30 帧、开了背景模糊的播放页只有约 9 帧，
    // 只适合不在乎流畅度、想省内存的情况；设置里可以切换，重启生效
    if (Plat.prefs("ui").getString("render", "gpu") == "soft" && System.getProperty("skiko.renderApi") == null) System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
    Boot.initFast()
    Perf.start()
    // 非关键初始化（插件、更新、下载记录、本地库、网络刷新）延后到后台线程，不阻塞窗口首帧
    Thread {
        Thread.sleep(150)
        Boot.initDeferred()
        (Plat.impl.vst as? DesktopVstHost)?.initSaved()
        Store.refreshAll()
    }.apply { isDaemon = true; name = "boot-deferred" }.start()
    if (System.getenv("NEKOPLAY_BENCH") == "1") {
        Thread {
            val out = java.io.File(System.getProperty("user.home"), ".nekoplay/perf-bench.log")
            out.writeText("# NEKOPLAY macOS bench start\n")
            for (sec in 1..20) {
                Thread.sleep(1000)
                val s = Perf.stat.value
                out.appendText("t=${sec}s coldStartMs=${s.coldStartMs} rssMB=${s.pss} heapMB=${s.heapMb} cpu=${"%.2f".format(s.cpu)}% fps=${s.fps} jank=${s.jank}\n")
            }
        }.apply { isDaemon = true; name = "perf-bench" }.start()
    }
    // 图片：网易云 / B 站图片带浏览器请求头，避免 403
    SingletonImageLoader.setSafe { ctx ->
        val client = Ncm.http.newBuilder().addInterceptor { ch ->
            val r = ch.request()
            val h = r.url.host
            ch.proceed(when {
                h.endsWith("126.net") -> r.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Referer", "https://music.163.com/").build()
                h.endsWith("hdslb.com") || h.endsWith("bilibili.com") -> r.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com/").build()
                else -> r
            })
        }.build()
        ImageLoader.Builder(ctx)
            .memoryCache { MemoryCache.Builder().maxSizeBytes((when { UiPref.lowMem -> 12L; UiPref.isMac -> 64L; else -> 24L }) * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(Plat.impl.cacheDir.resolve("img").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
    val w = (System.getenv("NEKOPLAY_W") ?: System.getProperty("nekoplay.width"))?.toIntOrNull() ?: 1280
    val h = (System.getenv("NEKOPLAY_H") ?: System.getProperty("nekoplay.height"))?.toIntOrNull() ?: 800
    DiscordRpc.start()
    application {
        val state = rememberWindowState(size = DpSize(w.dp, h.dp), position = if (System.getenv("NEKOPLAY_W") != null) androidx.compose.ui.window.WindowPosition(0.dp, 0.dp) else androidx.compose.ui.window.WindowPosition.PlatformDefault)
        val quit = { Engine.savePosition(); (Plat.impl.vst as? DesktopVstHost)?.shutdown(); SingleInstance.release(); exitApplication() }
        var visible by remember { mutableStateOf(true) }
        // 应用图标（音符猫）：窗口、任务栏和托盘共用
        val appIcon = remember {
            val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")!!.readBytes()
            androidx.compose.ui.graphics.painter.BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
        }
        // 系统不支持托盘（部分 Linux 桌面）时就不藏窗口，直接退出
        val trayOk = remember { runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false) }
        // 托盘：自己用 AWT 建（Compose 的 Tray 菜单在 Windows 上中文会显示成方块，这里给菜单指定中文字体）
        val winRef = remember { arrayOfNulls<java.awt.Window>(1) }
        val show = {
            visible = true
            state.isMinimized = false
            winRef[0]?.let { win ->
                win.toFront()
                win.requestFocus()
                if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
                    win.isAlwaysOnTop = true
                    win.isAlwaysOnTop = false
                }
            }
            Unit
        }
        val showRef by rememberUpdatedState(show)
        DisposableEffect(Unit) {
            SingleInstance.onWake = { showRef() }
            onDispose { SingleInstance.onWake = null }
        }
        if (UiPref.tray && trayOk) NekoTrayHost(appIcon, show, quit)
        val close = { if (UiPref.tray && trayOk) { visible = false; Thread { Thread.sleep(1500); System.gc(); WinMem.trim() }.apply { isDaemon = true }.start() } else quit() }
        if (DeskLyric.on && Features.on("desk")) DeskLyricWindow(appIcon)
        // 无边框窗口：标题栏、最小化 / 最大化 / 关闭按钮和拖边改大小都自己画
        // macOS 120fps 优化：永远使用不透明窗口（transparent = false），彻底避开透明合成与 WindowServer 每帧重算动态阴影；
        // 开了「窗口圆角」时用 macOS 原生全内容无标题栏窗口（apple.awt.fullWindowContent）+ 隐藏原生红黄绿三键，零开销获得系统硬件圆角和阴影
        val macNativeRound = androidx.compose.runtime.remember { UiPref.isMac && UiPref.round }
        Window(onCloseRequest = close, state = state, visible = visible, title = "NEKOPLAY", icon = appIcon, undecorated = !macNativeRound, transparent = false,
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) false
                else if (e.key == Key.Escape) DesktopPlat.back()   // Esc = 返回
                // 调试模式下 Ctrl+Shift+T 在鼠标位置打开托盘菜单（测试用，不用去点任务栏）
                // Ctrl+Q：直接退出（不缩到托盘）
                else if (e.key == Key.Q && (e.isCtrlPressed || e.isMetaPressed)) { quit(); true }
                else if (Debug.on && e.key == Key.T && e.isCtrlPressed && e.isShiftPressed) { TrayState.menuAt = java.awt.MouseInfo.getPointerInfo().location; true }
                else Keys.handleCtrl(e.key, e.isCtrlPressed || e.isMetaPressed)
            },
            // 空格、方向键：先让输入框处理，没人用才当快捷键
            onKeyEvent = { e -> e.type == KeyEventType.KeyDown && !e.isCtrlPressed && !e.isAltPressed && Keys.handlePlain(e.key) }) {
            window.minimumSize = java.awt.Dimension(360, 560)
            winRef[0] = window
            if (macNativeRound) androidx.compose.runtime.LaunchedEffect(Unit) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                // 等 AWT/AppKit 重建完原生标题栏再在 AppKit 主线程移除红黄绿三键，保留我们自己的自绘三键
                kotlinx.coroutines.delay(120)
                MacWindow.hideTrafficLights()
                kotlinx.coroutines.delay(400)
                MacWindow.hideTrafficLights()
            }
            // 最小化时释放图片内存缓存并回收一次，把内存还给系统
            androidx.compose.runtime.LaunchedEffect(state.isMinimized) {
                if (state.isMinimized) {
                    if (UiPref.lowMem) { SingletonImageLoader.get(coil3.PlatformContext.INSTANCE).memoryCache?.clear(); System.gc() }
                    kotlinx.coroutines.delay(1500); WinMem.trim()
                    // 最小化满 30 秒：释放图片内存缓存，降低常驻内存
                    kotlinx.coroutines.delay(28_500)
                    SingletonImageLoader.get(coil3.PlatformContext.INSTANCE).memoryCache?.clear()
                    WinMem.trim()
                }
            }
            NekoTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    Root(chrome = { TitleBar(state, close) })
                    if (!macNativeRound) ResizeEdges(state)
                    SideTracker()
                    FolderPickHost()
                }
            }
        }
    }
}


/** 桌面歌词悬浮窗：透明、置顶、不抢焦点；没锁时可以拖动，锁定后鼠标点击穿透（Windows） */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@androidx.compose.runtime.Composable
private fun androidx.compose.ui.window.ApplicationScope.DeskLyricWindow(appIcon: androidx.compose.ui.graphics.painter.Painter) {
    val ws = rememberWindowState(size = DpSize(860.dp, 140.dp),
        position = if (DeskLyric.x >= 0) androidx.compose.ui.window.WindowPosition(DeskLyric.x.dp, DeskLyric.y.dp) else WinFrame.usable().let { u ->
            // 默认放在屏幕下方偏上一点，避开最大化窗口底部的播放控制
            androidx.compose.ui.window.WindowPosition((u.x + (u.width - 860) / 2).dp, (u.y + u.height - 140 - 150).dp)
        })
    Window(onCloseRequest = { DeskLyric.on = false; DeskLyric.save() }, state = ws, title = "NEKOPLAY Lyrics", icon = appIcon, undecorated = true, transparent = true,
        alwaysOnTop = true, focusable = false, resizable = false) {
        androidx.compose.runtime.LaunchedEffect(Unit) { ClickThrough.hideFromTaskbar(window) }
        androidx.compose.runtime.LaunchedEffect(DeskLyric.locked) { ClickThrough.set(window, DeskLyric.locked) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { ws.position }.collect { p ->
                if (p is androidx.compose.ui.window.WindowPosition.Absolute) { DeskLyric.x = p.x.value.toInt(); DeskLyric.y = p.y.value.toInt(); DeskLyric.save() }
            }
        }
        var hover by remember { mutableStateOf(false) }
        NekoTheme {
            val body = @androidx.compose.runtime.Composable {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
                    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { hover = true }
                    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { hover = false }) {
                    DeskLyricView(hover) { DeskLyric.on = false; DeskLyric.save() }
                }
            }
            if (DeskLyric.locked) body() else WindowDraggableArea { body() }
        }
    }
}

/** macOS：在 AppKit 主线程上移除原生红黄绿三键（保留我们自己的 TitleBar + 零开销硬件圆角与阴影） */
object MacWindow {
    private interface ObjC : com.sun.jna.Library {
        fun objc_getClass(name: String): com.sun.jna.Pointer?
        fun sel_registerName(name: String): com.sun.jna.Pointer?
        fun objc_msgSend(receiver: com.sun.jna.Pointer?, sel: com.sun.jna.Pointer?): com.sun.jna.Pointer?
    }
    private interface ObjCLong : com.sun.jna.Library {
        fun objc_msgSend(receiver: com.sun.jna.Pointer?, sel: com.sun.jna.Pointer?, arg: Long): com.sun.jna.Pointer?
    }
    private interface ObjCCount : com.sun.jna.Library {
        fun objc_msgSend(receiver: com.sun.jna.Pointer?, sel: com.sun.jna.Pointer?): Long
    }
    private interface ObjCMain : com.sun.jna.Library {
        fun objc_msgSend(receiver: com.sun.jna.Pointer?, sel: com.sun.jna.Pointer?, action: com.sun.jna.Pointer?, obj: com.sun.jna.Pointer?, wait: Boolean)
    }
    fun hideTrafficLights() {
        if (!UiPref.isMac) return
        runCatching {
            val o0 = com.sun.jna.Native.load("objc", ObjC::class.java)
            val oL = com.sun.jna.Native.load("objc", ObjCLong::class.java)
            val oC = com.sun.jna.Native.load("objc", ObjCCount::class.java)
            val oM = com.sun.jna.Native.load("objc", ObjCMain::class.java)
            val app = o0.objc_msgSend(o0.objc_getClass("NSApplication"), o0.sel_registerName("sharedApplication")) ?: return@runCatching
            val wins = o0.objc_msgSend(app, o0.sel_registerName("windows")) ?: return@runCatching
            val n = oC.objc_msgSend(wins, o0.sel_registerName("count"))
            val selAt = o0.sel_registerName("objectAtIndex:")
            val selBtn = o0.sel_registerName("standardWindowButton:")
            val selMain = o0.sel_registerName("performSelectorOnMainThread:withObject:waitUntilDone:")
            val selRemove = o0.sel_registerName("removeFromSuperview")
            for (i in 0 until n) {
                val w = oL.objc_msgSend(wins, selAt, i) ?: continue
                for (btn in 0L..2L) {
                    oL.objc_msgSend(w, selBtn, btn)?.let { oM.objc_msgSend(it, selMain, selRemove, null, false) }
                }
            }
        }
    }
}

/** Windows：点击穿透、隐藏辅助窗口任务栏图标（WS_EX_TOOLWINDOW）与绑定进程 AppUserModelID */
object ClickThrough {
    private interface U32 : com.sun.jna.Library {
        fun GetWindowLongW(h: com.sun.jna.Pointer, i: Int): Int
        fun SetWindowLongW(h: com.sun.jna.Pointer, i: Int, v: Int): Int
    }
    private interface Shell32 : com.sun.jna.Library {
        fun SetCurrentProcessExplicitAppUserModelID(appId: com.sun.jna.WString): Int
    }
    private val isWin = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val u32 by lazy { runCatching { com.sun.jna.Native.load("user32", U32::class.java) }.getOrNull() }
    fun setAppId() {
        if (!isWin) return
        runCatching { com.sun.jna.Native.load("shell32", Shell32::class.java).SetCurrentProcessExplicitAppUserModelID(com.sun.jna.WString("cc.catsuki.nekoplay")) }
    }
    /** 标记为工具窗口：彻底隐藏桌面歌词、托盘右键菜单在任务栏 / Alt+Tab 里的独立图标（避免闪出默认 Java 图标） */
    fun hideFromTaskbar(w: java.awt.Window) {
        runCatching { w.type = java.awt.Window.Type.UTILITY }
        if (!isWin) return
        runCatching {
            val h = com.sun.jna.Native.getWindowPointer(w) ?: return
            val u = u32 ?: return
            val ex = u.GetWindowLongW(h, -20)
            u.SetWindowLongW(h, -20, (ex or 0x80) and 0x40000.inv())
        }
    }
    fun set(w: java.awt.Window, on: Boolean) {
        if (!isWin) return
        runCatching {
            val h = com.sun.jna.Native.getWindowPointer(w) ?: return
            val u = u32 ?: return
            val ex = u.GetWindowLongW(h, -20)
            u.SetWindowLongW(h, -20, if (on) ex or 0x20 or 0x80000 else ex and 0x20.inv())
        }
    }
}


/** 把 Engine.state 订阅隔离开，避免播放状态变化触发整个 application 顶层重组 */
@Composable
private fun NekoTrayHost(appIcon: androidx.compose.ui.graphics.painter.Painter, show: () -> Unit, quit: () -> Unit) {
    val st by Engine.state.collectAsState()
    NekoTray(appIcon, st.playing, st.current?.let { "${it.title} - ${it.artist}" } ?: "NEKOPLAY", show, quit)
}

/** 系统托盘：图标 + 右键菜单（中文字体），双击 / 单击图标打开窗口 */
@Composable
private fun NekoTray(appIcon: androidx.compose.ui.graphics.painter.Painter, playing: Boolean, tip: String, show: () -> Unit, quit: () -> Unit) {
    // 托盘菜单自己用 Compose 画：AWT 的原生菜单在部分 Windows 上中文显示成方块，换字体也没用
    val img = remember { javax.imageio.ImageIO.read(Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")) }
    val showS by rememberUpdatedState(show); val quitS by rememberUpdatedState(quit)
    val icon = remember {
        val size = java.awt.SystemTray.getSystemTray().trayIconSize
        java.awt.TrayIcon(img.getScaledInstance(size.width, size.height, java.awt.Image.SCALE_SMOOTH), "NEKOPLAY").apply {
            isImageAutoSize = false
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseReleased(e: java.awt.event.MouseEvent) {
                    // 用系统鼠标位置：TrayIcon 事件里的坐标在 Windows 缩放（125%、175%…）下是物理像素，菜单会偏到右边
                    val p = java.awt.MouseInfo.getPointerInfo()?.location ?: e.locationOnScreen
                    if (e.button == java.awt.event.MouseEvent.BUTTON3 || e.isPopupTrigger) java.awt.EventQueue.invokeLater { TrayState.menuAt = p }
                    else if (e.button == java.awt.event.MouseEvent.BUTTON1) java.awt.EventQueue.invokeLater { showS() }
                }
            })
        }
    }
    icon.toolTip = tip.take(120)
    DisposableEffect(Unit) {
        runCatching { java.awt.SystemTray.getSystemTray().add(icon) }
        onDispose { runCatching { java.awt.SystemTray.getSystemTray().remove(icon) } }
    }
    TrayState.menuAt?.let { at -> TrayMenu(appIcon, at, playing, { TrayState.menuAt = null }, showS, quitS) }
}

object TrayState { var menuAt by mutableStateOf<java.awt.Point?>(null) }

/** 托盘右键菜单：BenchLab 风格的小窗口，出现在鼠标旁边，点别处就关 */
@Composable
private fun TrayMenu(appIcon: androidx.compose.ui.graphics.painter.Painter, at: java.awt.Point, playing: Boolean, close: () -> Unit, show: () -> Unit, quit: () -> Unit) {
    val items = buildList<Pair<String, (() -> Unit)?>> {
        add("显示窗口" to show); add("" to null)
        add((if (playing) "暂停" else "播放") to { Engine.toggle() })
        add("上一首" to { Engine.prev() }); add("下一首" to { Engine.next() }); add("" to null)
        if (Features.on("desk")) {
            add((if (DeskLyric.on) "关闭桌面歌词" else "打开桌面歌词") to { DeskLyric.on = !DeskLyric.on; DeskLyric.save() })
            if (DeskLyric.on && DeskLyric.locked) add("解锁桌面歌词" to { DeskLyric.locked = false; DeskLyric.save() })
            add("" to null)
        }
        add("退出" to quit)
    }
    val w = 180; val h = items.sumOf { if (it.second == null) 9 else 36 } + 8
    // 放在鼠标旁边，不超出屏幕可用区域（任务栏在上 / 下 / 左 / 右都行）
    val u = WinFrame.usable(at)
    val x = (at.x - w).coerceIn(u.x, (u.x + u.width - w).coerceAtLeast(u.x))
    val y = (if (at.y - h >= u.y) at.y - h else at.y).coerceIn(u.y, (u.y + u.height - h).coerceAtLeast(u.y))
    val ws = rememberWindowState(size = DpSize(w.dp, h.dp), position = androidx.compose.ui.window.WindowPosition(x.dp, y.dp))
    Window(onCloseRequest = close, state = ws, title = "NEKOPLAY", icon = appIcon, undecorated = true, resizable = false, alwaysOnTop = true) {
        LaunchedEffect(Unit) {
            ClickThrough.hideFromTaskbar(window)
            window.toFront(); window.requestFocus()
            window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {}
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) { close() }
            })
        }
        NekoTheme {
            val p = c
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(p.page).nbf(2.dp, p.k).padding(4.dp)) {
                items.forEach { (label, act) ->
                    if (act == null) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().height(9.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().height(1.dp).background(p.l2))
                    } else {
                        val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val hover by src.collectIsHoveredAsState()
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().height(36.dp).background(if (hover) p.k else androidx.compose.ui.graphics.Color.Transparent)
                            .hoverable(src).press { close(); act() }.padding(horizontal = 14.dp), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
                            T(label, 14, if (hover) p.tagFg else if (label == "退出") p.bad else p.k)
                        }
                    }
                }
            }
        }
    }
}
