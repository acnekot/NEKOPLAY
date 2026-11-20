package cc.catsuki.nekoplay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.window.FrameWindowScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window

/**
 * 侧拉模式：窗口固定成手机宽度、整屏高，贴在屏幕左 / 右边并置顶。
 * 鼠标离开窗口一小会儿就滑进屏幕边缘（只留 4px），鼠标碰到那条边再滑出来。
 * 窗口藏起来时收不到鼠标事件，所以用定时读鼠标位置来判断。
 */
object SideMode {
    private const val PEEK = 4
    private var normal: Rectangle? = null
    private var wasMax = false
    private var wasHalf = 0
    /** 调试模式下把侧拉的计算结果写到程序目录的 side-debug.txt，方便查不同缩放比例下的问题 */
    fun log(s: String) = if (!Debug.on) Unit else runCatching {
        val dir = System.getProperty("compose.application.resources.dir")?.let { java.io.File(it).parentFile?.parentFile } ?: return@runCatching
        java.io.File(dir, "side-debug.txt").appendText("${System.currentTimeMillis() % 100000} $s\n")
    }
    /** 进入时所在屏幕的可用区域（去掉任务栏） */
    var area = Rectangle()

    fun enter(w: Window, side: Int) {
        if (SidePref.active == 0) {
            wasMax = WinFrame.maxed; wasHalf = WinFrame.half
            if (WinFrame.maxed || WinFrame.half != 0) WinFrame.restore(w)
            normal = w.bounds
            area = WinFrame.usable(Point(w.x + w.width / 2, w.y + 20), w)
            val gc = w.graphicsConfiguration
            log("enter side=$side area=$area win=${w.bounds} gc=${gc.bounds} ins=${java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)} scale=${gc.defaultTransform.scaleX}")
        }
        SidePref.active = side
        w.isAlwaysOnTop = true
        place(w, 1f)
    }

    fun exit(w: Window) {
        SidePref.active = 0
        w.isAlwaysOnTop = false
        normal?.let { w.bounds = it }
        // 进入前是最大化 / 半屏的，退出后恢复回去
        if (wasMax) WinFrame.maximize(w) else if (wasHalf != 0) WinFrame.snap(w, wasHalf, Point(area.x + area.width / 2, area.y + 10))
        w.toFront()
    }

    /** f：1 完全露出，0 收进边缘 */
    fun place(w: Window, f: Float) {
        val u = area; val wd = SidePref.width
        val shown = if (SidePref.active < 0) u.x else u.x + u.width - wd
        val hidden = if (SidePref.active < 0) u.x - wd + PEEK else u.x + u.width - PEEK
        val x = (hidden + (shown - hidden) * f).toInt()
        val r = Rectangle(x, u.y, wd, u.height)
        if (w.bounds != r) { w.bounds = r; if (f == 1f || f == 0f) log("place f=$f want=$r got=${w.bounds}") }
    }
}

/** 侧拉模式的鼠标跟踪：放在主窗口里 */
@Composable fun FrameWindowScope.SideTracker() {
    SideEffect {
        SidePref.enter = { s -> SideMode.enter(window, s) }
        SidePref.exit = { SideMode.exit(window) }
    }
    val side = SidePref.active
    LaunchedEffect(side) {
        if (side == 0) return@LaunchedEffect
        var f = 1f          // 当前露出比例
        var show = true     // 目标
        var outSince = 0L
        var lastW = SidePref.width
        // 刚进入时鼠标多半还在窗口外（点菜单的位置），先停 2 秒再开始自动收起
        val grace = System.currentTimeMillis() + 2000
        while (isActive) {
            val m = MouseInfo.getPointerInfo()?.location
            val b = window.bounds; val u = SideMode.area
            val inside = m != null && Rectangle(b.x - 6, b.y - 6, b.width + 12, b.height + 12).contains(m)
            val atEdge = m != null && m.y in u.y..(u.y + u.height) && (if (side < 0) m.x <= u.x + 1 else m.x >= u.x + u.width - 2)
            val now = System.currentTimeMillis()
            if (inside || atEdge) { outSince = 0; if (!show) SideMode.log("show m=$m b=$b"); show = true }
            else if (show) { if (outSince == 0L) outSince = now; if (now - outSince > 450 && now > grace) { show = false; SideMode.log("hide m=$m b=$b") } }
            val target = if (show) 1f else 0f
            val moving = f != target
            if (moving) {
                // 缓动：每帧走剩下距离的三分之一，最后一点直接到位
                f += (target - f) * .32f
                if (kotlin.math.abs(target - f) < .01f) f = target
                val e = f * f * (3 - 2 * f)
                SideMode.place(window, e)
            } else if (SidePref.width != lastW) SideMode.place(window, f)
            lastW = SidePref.width
            delay(if (moving) 12 else 40)
        }
    }
}
