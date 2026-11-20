package cc.catsuki.nekoplay

import androidx.compose.ui.graphics.toComposeImageBitmap

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
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
    Plat.impl = DesktopPlat()
    Boot.init()
    Store.refreshAll()
    Perf.start()
    // 图片：网易云图片带浏览器请求头，避免 403
    SingletonImageLoader.setSafe { ctx ->
        val client = Ncm.http.newBuilder().addInterceptor { ch ->
            val r = ch.request()
            ch.proceed(if (r.url.host.endsWith("126.net")) r.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Referer", "https://music.163.com/").build() else r)
        }.build()
        ImageLoader.Builder(ctx)
            .memoryCache { MemoryCache.Builder().maxSizeBytes((if (UiPref.lowMem) 12L else 40L) * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(Plat.impl.cacheDir.resolve("img").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
    val w = (System.getenv("NEKOPLAY_W") ?: System.getProperty("nekoplay.width"))?.toIntOrNull() ?: 1280
    val h = (System.getenv("NEKOPLAY_H") ?: System.getProperty("nekoplay.height"))?.toIntOrNull() ?: 800
    DiscordRpc.start()
    application {
        val state = rememberWindowState(size = DpSize(w.dp, h.dp), position = if (System.getenv("NEKOPLAY_W") != null) androidx.compose.ui.window.WindowPosition(0.dp, 0.dp) else androidx.compose.ui.window.WindowPosition.PlatformDefault)
        val quit = { Engine.savePosition(); exitApplication() }
        var visible by remember { mutableStateOf(true) }
        // 应用图标（音符猫）：窗口、任务栏和托盘共用
        val appIcon = remember {
            val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")!!.readBytes()
            androidx.compose.ui.graphics.painter.BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
        }
        val trayIcon = appIcon
        val st by Engine.state.collectAsState()
        // 系统不支持托盘（部分 Linux 桌面）时就不藏窗口，直接退出
        val trayOk = remember { runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false) }
        if (UiPref.tray && trayOk) Tray(trayIcon, tooltip = st.current?.let { "${it.title} - ${it.artist}" } ?: "NEKOPLAY", onAction = { visible = true; state.isMinimized = false }) {
            Item("显示窗口") { visible = true; state.isMinimized = false }
            Separator()
            Item(if (st.playing) "暂停" else "播放") { Engine.toggle() }
            Item("上一首") { Engine.prev() }
            Item("下一首") { Engine.next() }
            Separator()
            Item(if (DeskLyric.on) "关闭桌面歌词" else "打开桌面歌词") { DeskLyric.on = !DeskLyric.on; DeskLyric.save() }
            if (DeskLyric.on && DeskLyric.locked) Item("解锁桌面歌词") { DeskLyric.locked = false; DeskLyric.save() }
            Separator()
            Item("退出") { quit() }
        }
        val close = { if (UiPref.tray && trayOk) visible = false else quit() }
        if (DeskLyric.on) DeskLyricWindow()
        // 无边框窗口：标题栏、最小化 / 最大化 / 关闭按钮和拖边改大小都自己画
        Window(onCloseRequest = close, state = state, visible = visible, title = "NEKOPLAY", icon = appIcon, undecorated = true,
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) false
                else if (e.key == Key.Escape) DesktopPlat.back()   // Esc = 返回
                else Keys.handleCtrl(e.key, e.isCtrlPressed || e.isMetaPressed)
            },
            // 空格、方向键：先让输入框处理，没人用才当快捷键
            onKeyEvent = { e -> e.type == KeyEventType.KeyDown && !e.isCtrlPressed && !e.isAltPressed && Keys.handlePlain(e.key) }) {
            window.minimumSize = java.awt.Dimension(360, 560)
            // 最小化时释放图片内存缓存并回收一次，把内存还给系统
            androidx.compose.runtime.LaunchedEffect(state.isMinimized) {
                if (state.isMinimized && UiPref.lowMem) { SingletonImageLoader.get(coil3.PlatformContext.INSTANCE).memoryCache?.clear(); System.gc() }
            }
            NekoTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    Root(chrome = { TitleBar(state, close) })
                    ResizeEdges(state)
                }
            }
        }
    }
}


/** 桌面歌词悬浮窗：透明、置顶、不抢焦点；没锁时可以拖动，锁定后鼠标点击穿透（Windows） */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@androidx.compose.runtime.Composable
private fun androidx.compose.ui.window.ApplicationScope.DeskLyricWindow() {
    val ws = rememberWindowState(size = DpSize(860.dp, 140.dp),
        position = if (DeskLyric.x >= 0) androidx.compose.ui.window.WindowPosition(DeskLyric.x.dp, DeskLyric.y.dp) else androidx.compose.ui.window.WindowPosition(androidx.compose.ui.Alignment.BottomCenter))
    Window(onCloseRequest = { DeskLyric.on = false; DeskLyric.save() }, state = ws, title = "NEKOPLAY Lyrics", undecorated = true, transparent = true,
        alwaysOnTop = true, focusable = false, resizable = false) {
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

/** Windows：给窗口加 / 去掉 WS_EX_TRANSPARENT，让鼠标点击穿过歌词落到下面的窗口 */
object ClickThrough {
    private interface U32 : com.sun.jna.Library {
        fun GetWindowLongW(h: com.sun.jna.Pointer, i: Int): Int
        fun SetWindowLongW(h: com.sun.jna.Pointer, i: Int, v: Int): Int
    }
    private val u32 by lazy { runCatching { com.sun.jna.Native.load("user32", U32::class.java) }.getOrNull() }
    fun set(w: java.awt.Window, on: Boolean) {
        if (!System.getProperty("os.name").lowercase().contains("win")) return
        runCatching {
            val h = com.sun.jna.Native.getWindowPointer(w) ?: return
            val u = u32 ?: return
            val ex = u.GetWindowLongW(h, -20)
            u.SetWindowLongW(h, -20, if (on) ex or 0x20 or 0x80000 else ex and 0x20.inv())
        }
    }
}
