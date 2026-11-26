package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState

/** macOS：在 AppKit 主线程上移除原生红黄绿三键（保留自绘 TitleBar + 零开销硬件圆角与阴影） */
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
internal fun NekoTrayHost(appIcon: androidx.compose.ui.graphics.painter.Painter, show: () -> Unit, quit: () -> Unit) {
    val st by Engine.state.collectAsState()
    NekoTray(appIcon, st.playing, st.current?.let { "${it.title} - ${it.artist}" } ?: "NEKOPLAY", show, quit)
}

/** 系统托盘：图标 + 自绘 Compose 右键菜单 */
@Composable
private fun NekoTray(appIcon: androidx.compose.ui.graphics.painter.Painter, playing: Boolean, tip: String, show: () -> Unit, quit: () -> Unit) {
    val img = remember { javax.imageio.ImageIO.read(Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")) }
    val showS by rememberUpdatedState(show); val quitS by rememberUpdatedState(quit)
    val icon = remember {
        val size = java.awt.SystemTray.getSystemTray().trayIconSize
        java.awt.TrayIcon(img.getScaledInstance(size.width, size.height, java.awt.Image.SCALE_SMOOTH), "NEKOPLAY").apply {
            isImageAutoSize = false
            addActionListener { java.awt.EventQueue.invokeLater { showS() } }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseReleased(e: java.awt.event.MouseEvent) {
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

/** 托盘右键菜单：出现在鼠标旁边，失焦自动关闭 */
@Composable
private fun TrayMenu(appIcon: androidx.compose.ui.graphics.painter.Painter, at: java.awt.Point, playing: Boolean, close: () -> Unit, show: () -> Unit, quit: () -> Unit) {
    val items = buildList<Pair<String, (() -> Unit)?>> {
        add("显示窗口" to show); add("" to null)
        add((if (playing) "暂停" else "播放") to { Engine.toggle() })
        add("上一首" to { Engine.prev() }); add("下一首" to { Engine.next() }); add("" to null)
        if (Features.on("desk")) {
            add((if (DeskLyric.on) "关闭桌面歌词" else "打开桌面歌词") to { DeskLyric.on = !DeskLyric.on; DeskLyric.save() })
            if (DeskLyric.on) add("回正桌面歌词" to { DeskLyric.resetPos() })
            if (DeskLyric.on && DeskLyric.locked) add("解锁桌面歌词" to { DeskLyric.locked = false; DeskLyric.save() })
            add("" to null)
        }
        add("退出" to quit)
    }
    val w = 180; val h = items.sumOf { if (it.second == null) 9 else 36 } + 8
    val u = WinFrame.usable(at)
    val x = (at.x - w).coerceIn(u.x, (u.x + u.width - w).coerceAtLeast(u.x))
    val y = (if (at.y - h >= u.y) at.y - h else at.y).coerceIn(u.y, (u.y + u.height - h).coerceAtLeast(u.y))
    val ws = rememberWindowState(size = DpSize(w.dp, h.dp), position = WindowPosition(x.dp, y.dp))
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
            Column(Modifier.fillMaxSize().background(p.page).nbf(2.dp, p.k).padding(4.dp)) {
                items.forEach { (label, act) ->
                    if (act == null) Box(Modifier.fillMaxWidth().height(9.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(p.l2))
                    } else {
                        val src = remember { MutableInteractionSource() }
                        val hover by src.collectIsHoveredAsState()
                        Box(Modifier.fillMaxWidth().height(36.dp).background(if (hover) p.k else Color.Transparent)
                            .hoverable(src).press { close(); act() }.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                            T(label, 14, if (hover) p.tagFg else if (label == "退出") p.bad else p.k)
                        }
                    }
                }
            }
        }
    }
}
