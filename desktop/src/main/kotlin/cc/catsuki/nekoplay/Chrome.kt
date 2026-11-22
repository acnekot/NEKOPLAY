package cc.catsuki.nekoplay


import cc.catsuki.nekoplay.core.*
import cc.catsuki.nekoplay.data.*
import cc.catsuki.nekoplay.player.*
import cc.catsuki.nekoplay.ui.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Cursor
import androidx.compose.ui.input.pointer.isSecondaryPressed
import java.awt.MouseInfo

/**
 * 自绘标题栏（参考 Hydrogen 的样式）：和窗口同底色、不画分隔线；
 * 左边粗体 NEKOPLAY，右边头像（账号菜单：主页 / 设置 / 登录）和细线条的 最小化 / 最大化 / 关闭。
 * 空白处可以拖动窗口，双击最大化 / 还原。
 */
@Composable fun FrameWindowScope.TitleBar(state: WindowState, onClose: () -> Unit) {
    val nav = LocalNav.current
    val p = c
    val max = WinFrame.maxed || WinFrame.half != 0
    fun toggleMax() { if (max) WinFrame.restore(window) else WinFrame.maximize(window) }
    // 整条标题栏都能拖（按钮、头像除外）；双击最大化 / 还原。自己处理拖动，比 WindowDraggableArea 的判定范围大
    var lastDown by remember { mutableStateOf(0L) }
    val side = SidePref.active != 0
    Box(Modifier.fillMaxWidth().height(60.dp).pointerInput(max, side) {
        // 侧拉模式：窗口位置固定，不能拖也不能最大化
        if (side) return@pointerInput
        awaitEachGesture {
            awaitFirstDown()
            val now = System.currentTimeMillis()
            if (now - lastDown < 350) { lastDown = 0; toggleMax(); return@awaitEachGesture }
            lastDown = now
            val start = MouseInfo.getPointerInfo().location
            var w0 = window.location
            var moved = false
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.none { it.pressed }) break
                val p0 = MouseInfo.getPointerInfo().location
                val dx = p0.x - start.x; val dy = p0.y - start.y
                if (!moved && dx * dx + dy * dy < 16) continue
                if (!moved && (WinFrame.maxed || WinFrame.half != 0)) {
                    // 最大化 / 贴边时拖动：还原成原来的大小，鼠标仍按比例落在标题栏同一位置
                    val b = window.bounds
                    val fx = (start.x - b.x).toFloat() / b.width.coerceAtLeast(1)
                    WinFrame.restore(window, keepPos = false)
                    val r = window.bounds
                    window.setLocation(start.x - (r.width * fx).toInt(), start.y - (start.y - b.y))
                    w0 = window.location
                }
                moved = true
                window.setLocation(w0.x + dx, w0.y + dy)
                WinFrame.preview(p0)
                ev.changes.forEach { it.consume() }
            }
            // 松手：拖到屏幕顶边 = 最大化，拖到左 / 右边 = 占半屏（和 Windows 的贴边一样，但不会盖住任务栏）
            if (moved) WinFrame.drop(window, MouseInfo.getPointerInfo().location)
        }
    }) {
        Row(Modifier.fillMaxSize().padding(start = 26.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // 右键 NEKOPLAY：侧拉模式菜单
            T("NEKOPLAY", Fs.HEAD + 2, w = 900, ls = .01f, modifier = Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press && e.buttons.isSecondaryPressed) { e.changes.forEach { it.consume() }; SidePref.menu(nav) }
                    }
                }
            }.press { nav.player = false; nav.stack.clear(); nav.tab = 0 })
            Spacer(Modifier.weight(1f))
            TopActions(40.dp, 20.dp, 28.dp)
            Spacer(Modifier.width(10.dp))
            if (!side) CaptionClick(onClick = { state.isMinimized = true }) { col -> line(col, -8f, 0f, 8f, 0f) }
            // 侧拉模式：这个位置换成「退出侧拉」（两个方框 → 一个箭头）
            if (side) CaptionClick(onClick = { SidePref.exit?.invoke() }) { col -> if (SidePref.active < 0) { line(col, -4f, -7f, 4f, 0f); line(col, 4f, 0f, -4f, 7f) } else { line(col, 4f, -7f, -4f, 0f); line(col, -4f, 0f, 4f, 7f) } }
            else CaptionClick(onClick = { toggleMax() }) { col ->
                if (!max) square(col, 0f, 0f, 14f)
                else { square(col, -2f, 2f, 11f); line(col, -3f, -6f, 6f, -6f); line(col, 6f, -6f, 6f, 3f) }
            }
            CaptionClick(hoverFg = Color(0xFFE81123), onClick = onClose) { col -> line(col, -8f, -8f, 8f, 8f); line(col, 8f, -8f, -8f, 8f) }
        }
    }
}

/** 标题栏按钮：48 宽，悬停变色；图标以按钮中心为原点、单位 dp 画线 */
@Composable private fun CaptionClick(hoverFg: Color? = null, onClick: () -> Unit, icon: DrawScope.(Color) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    // 细线图标、无底色；悬停时变成主题色（关闭键变红）
    val fg = if (hover) hoverFg ?: c.p else c.k
    Box(Modifier.width(48.dp).fillMaxHeight().hoverable(src)
        .pointerInput(Unit) { detectTapGestures { onClick() } }.drawBehind { icon(fg) })
}

private fun DrawScope.line(col: Color, x1: Float, y1: Float, x2: Float, y2: Float) {
    val cx = size.width / 2; val cy = size.height / 2; val d = 1.dp.toPx()
    drawLine(col, Offset(cx + x1 * d, cy + y1 * d), Offset(cx + x2 * d, cy + y2 * d), 1.6f * d)
}
private fun DrawScope.square(col: Color, ox: Float, oy: Float, s: Float) {
    val d = 1.dp.toPx(); val cx = size.width / 2 + ox * d; val cy = size.height / 2 + oy * d
    drawRect(col, Offset(cx - s / 2 * d, cy - s / 2 * d), Size(s * d, s * d), style = Stroke(1.6f * d))
}

/** 无边框窗口拖边 / 拖角改大小（最大化时不生效） */
@Composable fun FrameWindowScope.ResizeEdges(state: WindowState) {
    if (WinFrame.maxed || WinFrame.half != 0 || SidePref.active != 0) return
    val e = 5.dp
    Box(Modifier.fillMaxSize()) {
        Edge(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(e), Cursor.N_RESIZE_CURSOR, top = true)
        Edge(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(e), Cursor.S_RESIZE_CURSOR, bottom = true)
        Edge(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(e), Cursor.W_RESIZE_CURSOR, left = true)
        Edge(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(e), Cursor.E_RESIZE_CURSOR, right = true)
        val k = 10.dp
        Edge(Modifier.align(Alignment.TopStart).size(k), Cursor.NW_RESIZE_CURSOR, top = true, left = true)
        Edge(Modifier.align(Alignment.TopEnd).size(k), Cursor.NE_RESIZE_CURSOR, top = true, right = true)
        Edge(Modifier.align(Alignment.BottomStart).size(k), Cursor.SW_RESIZE_CURSOR, bottom = true, left = true)
        Edge(Modifier.align(Alignment.BottomEnd).size(k), Cursor.SE_RESIZE_CURSOR, bottom = true, right = true)
    }
}

@Composable private fun FrameWindowScope.Edge(m: Modifier, cursor: Int, top: Boolean = false, bottom: Boolean = false, left: Boolean = false, right: Boolean = false) {
    Box(m.pointerHoverIcon(PointerIcon(Cursor(cursor))).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent()
                if (down.changes.none { it.pressed }) continue
                val start = MouseInfo.getPointerInfo().location
                val b0 = window.bounds
                val minW = window.minimumSize.width; val minH = window.minimumSize.height
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.none { it.pressed }) break
                    ev.changes.forEach { it.consume() }
                    val now = MouseInfo.getPointerInfo().location
                    val dx = now.x - start.x; val dy = now.y - start.y
                    var x = b0.x; var y = b0.y; var w = b0.width; var h = b0.height
                    if (right) w = maxOf(minW, b0.width + dx)
                    if (bottom) h = maxOf(minH, b0.height + dy)
                    if (left) { w = maxOf(minW, b0.width - dx); x = b0.x + b0.width - w }
                    if (top) { h = maxOf(minH, b0.height - dy); y = b0.y + b0.height - h }
                    window.setBounds(x, y, w, h)
                }
            }
        }
    })
}


/**
 * 无边框窗口的最大化 / 贴边：系统的「最大化」会把任务栏盖住，所以自己算屏幕的可用区域（去掉任务栏）来摆窗口。
 * half：0 不贴边，-1 左半屏，1 右半屏。
 */
object WinFrame {
    var maxed by mutableStateOf(false)
    var half by mutableIntStateOf(0)
    private var normal: java.awt.Rectangle? = null

    /** 某个点所在屏幕的可用区域（去掉任务栏） */
    fun usable(at: java.awt.Point? = null, w: java.awt.Window? = null): java.awt.Rectangle {
        val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        val gc = at?.let { p -> ge.screenDevices.map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(p) } } ?: w?.graphicsConfiguration ?: ge.defaultScreenDevice.defaultConfiguration
        val b = gc.bounds; val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
        return java.awt.Rectangle(b.x + ins.left, b.y + ins.top, b.width - ins.left - ins.right, b.height - ins.top - ins.bottom)
    }
    private fun remember(w: java.awt.Window) { if (!maxed && half == 0) normal = w.bounds }

    fun maximize(w: java.awt.Window, at: java.awt.Point? = null) { remember(w); w.bounds = usable(at, w); maxed = true; half = 0 }
    fun snap(w: java.awt.Window, side: Int, at: java.awt.Point) {
        remember(w); val u = usable(at, w)
        w.bounds = java.awt.Rectangle(if (side < 0) u.x else u.x + u.width / 2, u.y, u.width / 2, u.height); half = side; maxed = false
    }
    fun restore(w: java.awt.Window, keepPos: Boolean = true) {
        normal?.let { n -> w.bounds = if (keepPos) n else java.awt.Rectangle(w.x, w.y, n.width, n.height) }
        maxed = false; half = 0
    }
    /** 拖动中：暂时不画预览，只记录 */
    fun preview(p: java.awt.Point) {}
    fun drop(w: java.awt.Window, p: java.awt.Point) {
        val u = usable(p, w)
        when {
            p.y <= u.y + 2 -> maximize(w, p)
            p.x <= u.x + 2 -> snap(w, -1, p)
            p.x >= u.x + u.width - 3 -> snap(w, 1, p)
            // 标题栏拖到屏幕上方外面了：拉回来，保证还能抓到
            w.y < u.y -> w.setLocation(w.x, u.y)
        }
    }
}
