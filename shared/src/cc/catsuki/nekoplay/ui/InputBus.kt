package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key

/** 全局键盘快捷键与媒体键调度（与动效令牌解耦） */
object Keys {
    var nav: Nav? = null
    val HELP = listOf(
        "空格" to "播放 / 暂停", "Ctrl + →" to "下一首", "Ctrl + ←" to "上一首", "→ / ←" to "快进 / 快退 5 秒",
        "Ctrl + ↑ / ↓" to "音量 + / −", "Ctrl + L" to "喜欢 / 取消喜欢", "Ctrl + P" to "打开 / 收起播放页",
        "Ctrl + F" to "搜索", "Ctrl + Q" to "播放队列", "Ctrl + D" to "歌词栏", "Ctrl + ," to "设置", "Esc" to "返回",
        "媒体键" to "播放 / 暂停、上一首、下一首（窗口在前台时）",
    )

    /** 带 Ctrl 的组合键和媒体键：输入框里也生效 */
    fun handleCtrl(key: Key, ctrl: Boolean): Boolean {
        val n = nav ?: return false
        when (key) {
            Key.MediaPlayPause -> { Engine.toggle(); return true }
            Key.MediaNext -> { Engine.next(); return true }
            Key.MediaPrevious -> { Engine.prev(); return true }
        }
        if (!ctrl) return false
        when (key) {
            Key.DirectionRight -> Engine.next()
            Key.DirectionLeft -> Engine.prev()
            Key.DirectionUp -> Vol.set(Vol.v + .05f)
            Key.DirectionDown -> Vol.set(Vol.v - .05f)
            Key.Spacebar -> Engine.toggle()
            Key.L -> Engine.state.value.current?.let { t ->
                Store.toggleLike(t) { n.toast = it }
                n.toast = if (t.id in Store.likedIds.value) "已喜欢" else "已取消喜欢"
            }
            Key.P -> { if (Engine.state.value.current != null) { n.lyrics = false; n.player = !n.player } }
            Key.F -> { n.player = false; n.stack.clear(); n.push(Page.Search) }
            Key.Q -> n.queue = true
            Key.D -> n.side = if (n.side == 1) 0 else 1
            Key.Comma -> { n.player = false; n.stack.clear(); n.push(Page.Settings) }
            else -> return false
        }
        return true
    }

    /** 不带修饰键的：输入框没用掉才会到这里 */
    fun handlePlain(key: Key): Boolean {
        when (key) {
            Key.Spacebar -> Engine.toggle()
            Key.DirectionRight -> Engine.seek(Engine.position.value + 5000)
            Key.DirectionLeft -> Engine.seek((Engine.position.value - 5000).coerceAtLeast(0))
            else -> return false
        }
        return true
    }
}

/** 标题栏搜索框 → 搜索页：把要搜的词交给搜索页 */
object SearchBus {
    var query by mutableStateOf<String?>(null)
}
