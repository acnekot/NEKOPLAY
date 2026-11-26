package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 内置功能开关管理（对齐单功能与关联集群两级粒度）：
 * - 按 5 大功能集群组织全部可选内置模块（曲库与存储 / 音频增强 / 播放与歌词 / 外观与动效 / 数据与扩展）；
 * - 支持单个模块精细开关，也支持整个关联集群一键总控；
 * - 关闭后立即隐藏对应按钮、菜单、设置入口并停用后台能力，核心播放不受影响。
 * desktop：null 两端都有，true 仅桌面端，false 仅移动端。
 */
object Features {
    class Cluster(val id: String, val name: String, val desc: String)
    class F(val id: String, val name: String, val desc: String, val desktop: Boolean? = null, val group: String = "")

    val CLUSTERS = listOf(
        Cluster("library", "曲库与存储", "本地音乐、网盘曲库与离线下载"),
        Cluster("audio", "音频增强", "均衡器音效与桌面外部音频插件"),
        Cluster("play", "播放与歌词", "悬浮/通知栏歌词、歌词工具与定时停止"),
        Cluster("visual", "外观与动效", "自定义背景、动态取色与界面动效系统"),
        Cluster("ext", "数据与扩展", "听歌统计、云备份、侧拉模式与社交状态"),
    )

    private val ALL = listOf(
        F("local", "本地音乐", "扫描并播放本机的音乐文件", group = "library"),
        F("davmusic", "WebDAV 音乐", "直接播放网盘（WebDAV）里的歌", group = "library"),
        F("dl", "本地下载", "按音质下载歌曲与歌词；下载管理页", group = "library"),
        F("fx", "效果器与均衡器", "10 段均衡器、低音增强、环绕与响度", group = "audio"),
        F("vst", "外部音频插件 (Beta)", "桌面端加载外部音频插件与悬浮窗口", desktop = true, group = "audio"),
        F("desk", "桌面歌词", "置顶悬浮歌词窗（支持一键回正与悬停解锁）", desktop = true, group = "play"),
        F("notify", "通知栏歌词", "播放通知与状态栏同步显示当前歌词", desktop = false, group = "play"),
        F("offset", "歌词偏移", "单首歌实时微调歌词提前 / 推后", group = "play"),
        F("card", "歌词卡片", "把选中的歌词生成分享图片", group = "play"),
        F("sleep", "定时关闭", "到点自动停止播放", group = "play"),
        F("bg", "自定义背景", "自定义背景图片、模糊、缩放与栏位透明度", group = "visual"),
        F("monet", "动态取色", "按壁纸、背景图或歌曲封面自动生成配色", group = "visual"),
        F("motion", "动效与曲线", "界面过渡动画、曲线调参与动效插件接管", group = "visual"),
        F("stats", "听歌统计", "记录本机的播放次数和听歌时长", group = "ext"),
        F("webdav", "WebDAV 备份", "配置、喜欢和最近播放备份到网盘", group = "ext"),
        F("side", "侧拉模式", "窗口贴边自动收起与滑出", desktop = true, group = "ext"),
        F("discord", "Discord 状态", "在 Discord 上展示正在听的歌", desktop = true, group = "ext"),
    )

    val list get() = ALL.filter { it.desktop == null || it.desktop == Plat.isDesktop }
    val clusters get() = CLUSTERS.filter { c -> list.any { it.group == c.id } }
    var off by mutableStateOf(setOf<String>())

    /** 这个功能在本平台存在并且没被关掉 */
    fun on(id: String) = id !in off && list.any { it.id == id }
    fun inCluster(cid: String) = list.filter { it.group == cid }
    fun clusterOn(cid: String) = inCluster(cid).any { on(it.id) }
    fun clusterEnabledCount(cid: String) = inCluster(cid).count { on(it.id) }

    fun set(id: String, v: Boolean) {
        off = if (v) off - id else off + id
        persistAndApply(setOf(id))
    }

    /** 关联集群一键开启或关闭该组全部功能 */
    fun setCluster(cid: String, v: Boolean) {
        val ids = inCluster(cid).map { it.id }.toSet()
        if (ids.isEmpty()) return
        off = if (v) off - ids else off + ids
        persistAndApply(ids)
    }

    private fun persistAndApply(changed: Set<String>) {
        Plat.prefs("ui").edit().putStringSet("featOff", off).apply()
        if (changed.any { it == "local" || it == "davmusic" || it == "dl" }) SearchSource.fix()
        if ("fx" in changed) FxStore.applyEffective()
        if ("vst" in changed && !on("vst")) runCatching { Plat.impl.vst?.setEnabled(false) }
        if ("side" in changed && !on("side") && SidePref.active != 0) SidePref.exit?.invoke()
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
