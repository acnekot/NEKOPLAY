package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 内置插件：可选功能都登记在这里，「功能管理」里可以整块关掉。
 * 关掉后，这个功能的按钮、菜单项、设置入口和后台工作一起停掉；核心播放不受影响。
 * desktop：null 两端都有，true 只有 PC，false 只有手机。
 */
object Features {
    class F(val id: String, val name: String, val desc: String, val desktop: Boolean? = null, val group: String = "")
    private val ALL = listOf(
        F("local", "本地音乐", "扫描并播放本机的音乐文件", group = "本地"),
        F("davmusic", "WebDAV 音乐", "直接播放网盘（WebDAV）里的歌", group = "本地"),
        F("dl", "下载", "把歌下载到本地，附带歌词；下载管理页", group = "本地"),
        F("offset", "歌词偏移", "单首歌调整歌词提前 / 推后", group = "播放与歌词"),
        F("card", "歌词卡片", "把选中的歌词做成分享图片", group = "播放与歌词"),
        F("desk", "桌面歌词", "置顶的悬浮歌词窗", desktop = true, group = "播放与歌词"),
        F("sleep", "定时关闭", "到点自动停止播放", group = "播放与歌词"),
        F("stats", "听歌统计", "记录本机的播放次数和时长", group = "数据"),
        F("webdav", "WebDAV 备份", "设置、喜欢和最近播放备份到网盘", group = "数据"),
        F("discord", "Discord 状态", "在 Discord 上显示正在听的歌", desktop = true, group = "其它"),
    )
    val list get() = ALL.filter { it.desktop == null || it.desktop == Plat.isDesktop }
    var off by mutableStateOf(setOf<String>())
    /** 这个功能在本平台存在并且没被关掉 */
    fun on(id: String) = id !in off && list.any { it.id == id }
    fun set(id: String, v: Boolean) {
        off = if (v) off - id else off + id
        Plat.prefs("ui").edit().putStringSet("featOff", off).apply()
        SearchSource.fix()
    }
    fun load() { off = Plat.prefs("ui").getStringSet("featOff", null) ?: emptySet() }
}

/**
 * PC 侧拉模式：窗口变成手机宽度贴在屏幕左 / 右边，鼠标离开收进边缘，贴边时滑出。
 * 设置项放在共享代码里，真正摆窗口的是桌面端（enter / exit 由桌面端注册）。
 */
object SidePref {
    var width by mutableIntStateOf(400)
    /** 0 没开，-1 左边，1 右边 */
    var active by mutableIntStateOf(0)
    var enter: ((Int) -> Unit)? = null
    var exit: (() -> Unit)? = null
    fun load() { width = Plat.prefs("ui").getInt("sideW", 400) }
    fun save() { Plat.prefs("ui").edit().putInt("sideW", width).apply() }
}
