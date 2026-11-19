package cc.catsuki.nekoplay

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
import java.awt.MouseInfo

/**
 * 自绘标题栏（参考 Hydrogen 的样式）：和窗口同底色、不画分隔线；
 * 左边粗体 NEKOPLAY + 方括号角标的搜索框，右边细线条的 最小化 / 最大化 / 关闭。
 * 空白处可以拖动窗口，双击最大化 / 还原。
 */
@Composable fun FrameWindowScope.TitleBar(state: WindowState, onClose: () -> Unit) {
    val nav = LocalNav.current
    val p = c
    val max = state.placement == WindowPlacement.Maximized
    fun toggleMax() { state.placement = if (max) WindowPlacement.Floating else WindowPlacement.Maximized }
    WindowDraggableArea(Modifier.fillMaxWidth().height(60.dp)) {
        Row(Modifier.fillMaxSize().pointerInput(max) { detectTapGestures(onDoubleTap = { toggleMax() }) }.padding(start = 26.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            T("NEKOPLAY", Fs.HEAD + 2, w = 900, ls = .01f, modifier = Modifier.press { nav.player = false; nav.stack.clear(); nav.tab = 0 })
            Spacer(Modifier.width(22.dp))
            BracketSearch()
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(40.dp).press { nav.menu = !nav.menu }, contentAlignment = Alignment.Center) { Avatar(28.dp, 13) }
            Spacer(Modifier.width(10.dp))
            CaptionClick(onClick = { state.isMinimized = true }) { col -> line(col, -8f, 0f, 8f, 0f) }
            CaptionClick(onClick = { toggleMax() }) { col ->
                if (!max) square(col, 0f, 0f, 14f)
                else { square(col, -2f, 2f, 11f); line(col, -3f, -6f, 6f, -6f); line(col, 6f, -6f, 6f, 3f) }
            }
            CaptionClick(hoverFg = Color(0xFFE81123), onClick = onClose) { col -> line(col, -8f, -8f, 8f, 8f); line(col, 8f, -8f, -8f, 8f) }
        }
    }
}

/** 方括号角标的搜索框：只画四个角；回车跳到搜索页并直接搜 */
@Composable private fun BracketSearch() {
    val nav = LocalNav.current
    val p = c
    var q by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    Box(Modifier.width(230.dp).height(36.dp).drawBehind {
        val a = 7.dp.toPx(); val w = 2.dp.toPx(); val col = if (focused) p.p else p.k
        // 左上 / 左下 / 右上 / 右下 四个角
        drawRect(col, Offset(0f, 0f), Size(a, w)); drawRect(col, Offset(0f, 0f), Size(w, a))
        drawRect(col, Offset(0f, size.height - w), Size(a, w)); drawRect(col, Offset(0f, size.height - a), Size(w, a))
        drawRect(col, Offset(size.width - a, 0f), Size(a, w)); drawRect(col, Offset(size.width - w, 0f), Size(w, a))
        drawRect(col, Offset(size.width - a, size.height - w), Size(a, w)); drawRect(col, Offset(size.width - w, size.height - a), Size(w, a))
    }.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        if (q.isEmpty()) T("SEARCH", 12, p.s, 700, mono = true, ls = .1f)
        androidx.compose.foundation.text.BasicTextField(q, { q = it },
            Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && q.isNotBlank()) {
                    nav.player = false; nav.stack.clear(); SearchBus.query = q.trim(); nav.push(Page.Search); q = ""; fm.clearFocus(); true
                } else false
            }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = p.k, fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp), fontFamily = Typo.family),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(p.p))
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
    if (state.placement != WindowPlacement.Floating) return
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
