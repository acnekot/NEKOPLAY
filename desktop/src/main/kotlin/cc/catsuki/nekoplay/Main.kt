package cc.catsuki.nekoplay

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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
    if (Plat.prefs("ui").getString("render", "gpu") == "soft" && System.getProperty("skiko.renderApi") == null) System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
    Boot.initFast()
    Perf.start()
    Thread {
        Thread.sleep(150)
        Boot.initDeferred()
        if (Features.on("vst")) (Plat.impl.vst as? DesktopVstHost)?.initSaved()
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
    SingletonImageLoader.setSafe { ctx ->
        val client = SourceApi.http.newBuilder().addInterceptor { ch ->
            // 图片 CDN 需要的 Referer / UA 由音源插件声明（MediaHeaders），宿主不写死任何平台
            val r = ch.request()
            val hs = MediaHeaders.forHost(r.url.host)
            ch.proceed(if (hs.isEmpty()) r else r.newBuilder().apply { hs.forEach { (k, v) -> header(k, v) } }.build())
        }.build()
        ImageLoader.Builder(ctx)
            .memoryCache { MemoryCache.Builder().maxSizeBytes((if (UiPref.lowMem) 16L else 64L) * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(Plat.impl.cacheDir.resolve("img").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
    val w = (System.getenv("NEKOPLAY_W") ?: System.getProperty("nekoplay.width"))?.toIntOrNull() ?: 1280
    val h = (System.getenv("NEKOPLAY_H") ?: System.getProperty("nekoplay.height"))?.toIntOrNull() ?: 800
    DiscordRpc.start()
    application {
        val state = rememberWindowState(size = DpSize(w.dp, h.dp), position = if (System.getenv("NEKOPLAY_W") != null) androidx.compose.ui.window.WindowPosition(0.dp, 0.dp) else androidx.compose.ui.window.WindowPosition.PlatformDefault)
        val quit = { Engine.savePosition(); Stats.flushSync(); (Plat.impl.vst as? DesktopVstHost)?.shutdown(); SingleInstance.release(); exitApplication() }
        var visible by remember { mutableStateOf(true) }
        val appIcon = remember {
            val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")!!.readBytes()
            androidx.compose.ui.graphics.painter.BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
        }
        val trayOk = remember { runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false) }
        val winRef = remember { arrayOfNulls<java.awt.Window>(1) }
        val show = {
            visible = true
            state.isMinimized = false
            runCatching { if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().requestForeground(true) }
            java.awt.EventQueue.invokeLater {
                winRef[0]?.let { win ->
                    if (!win.isVisible) win.isVisible = true
                    (win as? java.awt.Frame)?.let { it.extendedState = it.extendedState and java.awt.Frame.ICONIFIED.inv() }
                    win.toFront()
                    win.requestFocus()
                    if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
                        win.isAlwaysOnTop = true
                        win.isAlwaysOnTop = false
                    }
                }
            }
            Unit
        }
        val showRef by rememberUpdatedState(show)
        DisposableEffect(Unit) {
            SingleInstance.onWake = { java.awt.EventQueue.invokeLater { showRef() } }
            val reopen = java.awt.desktop.AppReopenedListener { java.awt.EventQueue.invokeLater { showRef() } }
            runCatching { if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().addAppEventListener(reopen) }
            onDispose {
                SingleInstance.onWake = null
                runCatching { if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().removeAppEventListener(reopen) }
            }
        }
        if (UiPref.tray && trayOk) NekoTrayHost(appIcon, show, quit)
        val close = { if (UiPref.tray && trayOk) { visible = false; Thread { Thread.sleep(1500); System.gc(); WinMem.trim() }.apply { isDaemon = true }.start() } else quit() }
        if (DeskLyric.on && Features.on("desk")) DeskLyricWindow(appIcon)
        val macNativeRound = remember { UiPref.isMac && UiPref.round }
        Window(onCloseRequest = close, state = state, visible = visible, title = "NEKOPLAY", icon = appIcon, undecorated = !macNativeRound, transparent = false,
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) false
                else if (e.key == Key.Escape) DesktopPlat.back()
                else if (e.key == Key.Q && (e.isCtrlPressed || e.isMetaPressed)) { quit(); true }
                else if (Debug.on && e.key == Key.T && e.isCtrlPressed && e.isShiftPressed) { TrayState.menuAt = java.awt.MouseInfo.getPointerInfo().location; true }
                else Keys.handleCtrl(e.key, e.isCtrlPressed || e.isMetaPressed)
            },
            onKeyEvent = { e -> e.type == KeyEventType.KeyDown && !e.isCtrlPressed && !e.isAltPressed && Keys.handlePlain(e.key) }) {
            window.minimumSize = java.awt.Dimension(360, 560)
            winRef[0] = window
            if (macNativeRound) LaunchedEffect(Unit) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                kotlinx.coroutines.delay(120)
                MacWindow.hideTrafficLights()
                kotlinx.coroutines.delay(400)
                MacWindow.hideTrafficLights()
            }
            LaunchedEffect(state.isMinimized) {
                if (state.isMinimized) {
                    if (UiPref.lowMem) { SingletonImageLoader.get(coil3.PlatformContext.INSTANCE).memoryCache?.clear(); System.gc() }
                    kotlinx.coroutines.delay(1500); WinMem.trim()
                    kotlinx.coroutines.delay(28_500)
                    SingletonImageLoader.get(coil3.PlatformContext.INSTANCE).memoryCache?.clear()
                    WinMem.trim()
                }
            }
            NekoTheme {
                val winBg = c.page
                LaunchedEffect(winBg) {
                    val awtBg = java.awt.Color((winBg.red * 255f + 0.5f).toInt().coerceIn(0, 255), (winBg.green * 255f + 0.5f).toInt().coerceIn(0, 255), (winBg.blue * 255f + 0.5f).toInt().coerceIn(0, 255))
                    window.background = awtBg
                    window.contentPane?.background = awtBg
                }
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

private fun defaultDeskLyricPos(ref: java.awt.Point? = null): androidx.compose.ui.window.WindowPosition {
    val u = if (ref != null) WinFrame.usable(ref) else WinFrame.usable()
    return androidx.compose.ui.window.WindowPosition((u.x + (u.width - 860) / 2).dp, (u.y + u.height - 140 - 150).dp)
}

/** 桌面歌词悬浮窗：透明、置顶、不抢焦点；没锁时可拖动与一键回正，锁定后点击穿透且悬停 3 秒自动唤出解锁按钮 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun androidx.compose.ui.window.ApplicationScope.DeskLyricWindow(appIcon: androidx.compose.ui.graphics.painter.Painter) {
    val ws = rememberWindowState(size = DpSize(860.dp, 140.dp),
        position = if (DeskLyric.x >= 0) androidx.compose.ui.window.WindowPosition(DeskLyric.x.dp, DeskLyric.y.dp) else defaultDeskLyricPos())
    Window(onCloseRequest = { DeskLyric.on = false; DeskLyric.save() }, state = ws, title = "NEKOPLAY Lyrics", icon = appIcon, undecorated = true, transparent = true,
        alwaysOnTop = true, focusable = false, resizable = false) {
        var hover by remember { mutableStateOf(false) }
        var unlockReady by remember { mutableStateOf(false) }
        var unlockProgress by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(Unit) { ClickThrough.hideFromTaskbar(window) }
        LaunchedEffect(DeskLyric.locked, unlockReady) {
            ClickThrough.set(window, DeskLyric.locked && !unlockReady)
        }
        LaunchedEffect(DeskLyric.resetRev) {
            if (DeskLyric.resetRev > 0) {
                val centerPt = runCatching { java.awt.Point(window.x + window.width / 2, window.y + window.height / 2) }.getOrNull()
                val target = defaultDeskLyricPos(centerPt)
                ws.position = target
                if (target is androidx.compose.ui.window.WindowPosition.Absolute) {
                    runCatching { window.setLocation(target.x.value.toInt(), target.y.value.toInt()) }
                }
            }
        }
        LaunchedEffect(DeskLyric.locked) {
            if (!DeskLyric.locked) {
                unlockReady = false; unlockProgress = 0f
                return@LaunchedEffect
            }
            var hoverMs = 0L
            var leaveMs = 0L
            while (true) {
                kotlinx.coroutines.delay(100L)
                val inside = runCatching {
                    if (!window.isShowing) return@runCatching false
                    val pt = java.awt.MouseInfo.getPointerInfo()?.location ?: return@runCatching false
                    val loc = window.locationOnScreen
                    pt.x in loc.x..(loc.x + window.width) && pt.y in loc.y..(loc.y + window.height)
                }.getOrDefault(false)
                if (inside) {
                    leaveMs = 0L
                    if (!unlockReady) {
                        hoverMs = (hoverMs + 100L).coerceAtMost(3000L)
                        unlockProgress = hoverMs / 3000f
                        if (hoverMs >= 3000L) unlockReady = true
                    }
                } else {
                    if (unlockReady) {
                        leaveMs += 100L
                        if (leaveMs >= 1200L) { unlockReady = false; hoverMs = 0L; unlockProgress = 0f; leaveMs = 0L }
                    } else if (hoverMs > 0L) {
                        hoverMs = 0L; unlockProgress = 0f
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { ws.position }.collect { p ->
                if (p is androidx.compose.ui.window.WindowPosition.Absolute) { DeskLyric.x = p.x.value.toInt(); DeskLyric.y = p.y.value.toInt(); DeskLyric.save() }
            }
        }
        NekoTheme {
            val body = @Composable {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
                    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { hover = true }
                    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { hover = false }) {
                    DeskLyricView(hover, unlockReady, unlockProgress) { DeskLyric.on = false; DeskLyric.save() }
                }
            }
            if (DeskLyric.locked) body() else WindowDraggableArea { body() }
        }
    }
}
