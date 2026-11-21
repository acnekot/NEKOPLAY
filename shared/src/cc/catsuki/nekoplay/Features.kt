package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 内置插件：可选功能都登记在这里，「功能管理」里可以整块关掉。
 * 关掉后，这个功能的按钮、菜单项、设置入口和后台工作一起停掉；核心播放不受影响。
 * desktop：null 两端都有，true 只有 PC，false 只有手机。
 */
object Features {
    class F(val id: String, val name: String, val desc: String, val desktop: Boolean? = null)
    private val ALL = listOf(
        F("ncm", "网易云音乐", "音源：搜索、播放、歌词，日推、私人 FM、歌单、评论、MV，登录后看网易云的歌单"),
        F("dl", "下载", "把歌下载到本地，附带歌词；下载管理页"),
        F("local", "本地音乐", "扫描并播放本机的音乐文件"),
        F("qq", "QQ 音乐", "音源：搜索、播放、歌词、排行榜、新碟，扫码登录后看 QQ 音乐的歌单"),
        F("kugou", "酷狗音乐", "音源：搜索、播放、歌词、排行榜，扫码登录后看酷狗的歌单"),
        F("sleep", "定时关闭", "到点自动停止播放"),
        F("card", "歌词卡片", "把选中的歌词做成分享图片"),
        F("offset", "歌词偏移", "单首歌调整歌词提前 / 推后"),
        F("stats", "听歌统计", "记录本机的播放次数和时长"),
        F("webdav", "WebDAV 备份", "设置、喜欢和最近播放备份到网盘"),
        F("desk", "桌面歌词", "置顶的悬浮歌词窗", desktop = true),
        F("discord", "Discord 状态", "在 Discord 上显示正在听的歌", desktop = true),
    )
    val list get() = ALL.filter { it.desktop == null || it.desktop == Plat.isDesktop }
    var off by mutableStateOf(setOf<String>())
    /** 这个功能在本平台存在并且没被关掉 */
    fun on(id: String) = id !in off && list.any { it.id == id }
    fun set(id: String, v: Boolean) {
        off = if (v) off - id else off + id
        Plat.prefs("ui").edit().putStringSet("featOff", off).apply()
        if (id == "ncm" && v) Store.refreshAll()
        SearchSource.fix()
    }
    fun load() { off = Plat.prefs("ui").getStringSet("featOff", null) ?: emptySet() }
    /** 开着的在线音源（0 网易云 / 1 QQ 音乐 / 2 酷狗）；全关时本体只剩本地功能 */
    val sources: List<Int> get() = listOfNotNull(if (on("ncm")) 0 else null, if (on("qq")) 1 else null, if (on("kugou")) 2 else null)
}

/** 设置 → 功能管理 */
@Composable fun FeaturesPage() {
    val p = c
    LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 28.dp)) {
        item { T("关掉的功能会隐藏它的按钮、菜单和设置，也不再在后台运行", 13, p.s, lh = 1.4f, modifier = Modifier.padding(bottom = 10.dp)) }
        items(Features.list, key = { it.id }) { f -> SwitchRow(f.name, f.desc, Features.on(f.id)) { Features.set(f.id, it) } }
    }
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
    fun menu(nav: Nav) {
        nav.sheet = SheetSpec("侧拉模式", "贴在屏幕边上，鼠标离开自动收起，碰到边缘再滑出来", buildList {
            add(SheetItem((if (active == -1) "✓  " else "    ") + "贴在左边") { enter?.invoke(-1) })
            add(SheetItem((if (active == 1) "✓  " else "    ") + "贴在右边") { enter?.invoke(1) })
            if (active != 0) add(SheetItem("退出侧拉模式") { exit?.invoke() })
        })
    }
}
