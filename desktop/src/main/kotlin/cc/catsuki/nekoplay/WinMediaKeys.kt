package cc.catsuki.nekoplay

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary

/**
 * Windows 键盘媒体键（播放/暂停、下一首、上一首、停止）：
 * 用 RegisterHotKey 在专用线程上注册为全局热键，窗口不在前台、最小化到托盘时也能用。
 * 某个键已被其他程序占用时注册会失败，跳过那个键即可，不影响其他键。
 */
object WinMediaKeys {
    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun RegisterHotKey(hWnd: Pointer?, id: Int, fsModifiers: Int, vk: Int): Boolean
        fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean
        fun GetMessageW(msg: Msg, hWnd: Pointer?, wMsgFilterMin: Int, wMsgFilterMax: Int): Int
    }

    /** Win32 MSG（x64 布局：WPARAM / LPARAM 为 8 字节） */
    @Structure.FieldOrder("hwnd", "message", "wParam", "lParam", "time", "x", "y", "lPrivate")
    class Msg : Structure() {
        @JvmField var hwnd: Pointer? = null
        @JvmField var message: Int = 0
        @JvmField var wParam: Long = 0
        @JvmField var lParam: Long = 0
        @JvmField var time: Int = 0
        @JvmField var x: Int = 0
        @JvmField var y: Int = 0
        @JvmField var lPrivate: Int = 0
    }

    private const val WM_HOTKEY = 0x0312
    private const val MOD_NOREPEAT = 0x4000
    /** 热键 id → (虚拟键码, 动作) */
    private val KEYS: Map<Int, Pair<Int, () -> Unit>> = mapOf(
        1 to (0xB3 to { Engine.toggle() }),   // VK_MEDIA_PLAY_PAUSE
        2 to (0xB0 to { Engine.next() }),     // VK_MEDIA_NEXT_TRACK
        3 to (0xB1 to { Engine.prev() }),     // VK_MEDIA_PREV_TRACK
        4 to (0xB2 to { Engine.pause() }),    // VK_MEDIA_STOP
    )
    @Volatile var registered: Set<Int> = emptySet(); private set

    fun start() {
        if (!System.getProperty("os.name").lowercase().contains("win")) return
        Thread {
            val u = runCatching { Native.load("user32", User32::class.java) }.getOrNull() ?: return@Thread
            registered = KEYS.filter { (id, k) -> u.RegisterHotKey(null, id, MOD_NOREPEAT, k.first) }.keys
            if (registered.isEmpty()) return@Thread
            val msg = Msg()
            // GetMessage 返回 0（WM_QUIT）或 -1（出错）时退出；守护线程随进程结束
            while (u.GetMessageW(msg, null, 0, 0) > 0) {
                if (msg.message != WM_HOTKEY) continue
                val action = KEYS[msg.wParam.toInt()]?.second ?: continue
                java.awt.EventQueue.invokeLater { runCatching { action() } }
            }
            registered.forEach { u.UnregisterHotKey(null, it) }
        }.apply { isDaemon = true; name = "win-media-keys" }.start()
    }
}
