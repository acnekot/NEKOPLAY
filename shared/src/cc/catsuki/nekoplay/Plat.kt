package cc.catsuki.nekoplay

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * 平台接口：共用代码只通过这里碰平台相关的东西。
 * 安卓（app/）和桌面（desktop/）各自实现一份，启动时赋给 Plat.impl。
 */

/** 键值存储（安卓 SharedPreferences / 桌面 properties 文件），方法名照 SharedPreferences 取，迁移最省事 */
interface KV {
    fun getString(k: String, d: String?): String?
    fun getInt(k: String, d: Int): Int
    fun getLong(k: String, d: Long): Long
    fun getFloat(k: String, d: Float): Float
    fun getBoolean(k: String, d: Boolean): Boolean
    fun getStringSet(k: String, d: Set<String>?): Set<String>?
    fun edit(): Ed
    /** 全部键值（备份用） */
    fun all(): Map<String, Any?>
    interface Ed {
        fun putString(k: String, v: String?): Ed
        fun putInt(k: String, v: Int): Ed
        fun putLong(k: String, v: Long): Ed
        fun putFloat(k: String, v: Float): Ed
        fun putBoolean(k: String, v: Boolean): Ed
        fun putStringSet(k: String, v: Set<String>?): Ed
        fun remove(k: String): Ed
        fun clear(): Ed
        fun apply()
    }
}

/** 播放内核：一次只放一首，队列由 Engine 管 */
/** 真实音质参数：编码、采样率、位深（0 = 未知）、声道、码率（kbps，0 = 未知） */
data class AudioInfo(val codec: String, val rate: Int, val bits: Int, val ch: Int, val kbps: Int) {
    /** 精简显示：FLAC 48k/24bit 1918k（立体声不写） */
    fun line(): String = buildList {
        add(codec.uppercase())
        if (rate > 0) add((if (rate % 1000 == 0) "${rate / 1000}k" else "%.1fk".format(rate / 1000f)) + if (bits > 0) "/${bits}bit" else "")
        if (ch == 1) add("单声道") else if (ch > 2) add("${ch}ch")
        if (kbps > 0) add("${kbps}k")
    }.joinToString(" ")
}

interface Audio {
    interface Listener {
        fun onPlaying(p: Boolean)
        fun onBuffering(b: Boolean)
        /** 无缝接上了提前排好的下一首 */
        fun onAdvanced() {}
        fun onEnded()
        fun onError(msg: String)
    }
    var listener: Listener?
    /** url 为空表示读本地缓存（cacheKey 对应的那份）；meta 给通知栏 / 系统媒体控制用 */
    fun open(url: String?, cacheKey: String, startMs: Long, autoplay: Boolean, meta: Track)
    fun play()
    fun pause()
    fun seek(ms: Long)
    val isPlaying: Boolean
    val idle: Boolean
    val position: Long
    /** 已经缓冲（下载）到的位置 */
    val buffered: Long
    /** 某个缓存键是否已经完整缓存 */
    fun isCached(key: String): Boolean
    fun cacheBytes(): Long
    fun clearCache()
    /** 音量 0..1（桌面用） */
    var volume: Float
    /** 效果器 / 均衡器 */
    /** 无缝播放：提前把下一首排在后面，当前这首一播完就直接接上（不经过 onEnded） */
    fun queueNext(url: String?, cacheKey: String, meta: Track) {}
    /** 撤掉提前排好的下一首（用户切歌、改队列时） */
    fun clearNext() {}
    /** 正在播放的真实音频参数（解码器读到的），拿不到时 null */
    fun info(): AudioInfo? = null
    fun applyFx(fx: Fx)
}

interface PlatImpl {
    val name: String               // "android" / "desktop"
    val filesDir: File
    val cacheDir: File
    fun prefs(name: String): KV
    val inter: FontFamily
    val mono: FontFamily
    /** 系统是否支持实时模糊（歌词远处的轻微模糊用） */
    val liveBlur: Boolean
    val audio: Audio
    /** 解码图片文件，长边不超过 maxSide */
    fun decodeImage(path: String, maxSide: Int): ImageBitmap?
    /** 读像素 / 用像素造图（背景模糊用） */
    fun pixels(img: ImageBitmap): IntArray
    fun fromPixels(px: IntArray, w: Int, h: Int): ImageBitmap
    fun scaled(img: ImageBitmap, w: Int, h: Int): ImageBitmap
    fun saveJpeg(img: ImageBitmap, file: File)
    /** 存一张 PNG 到相册 / 图片文件夹，返回给人看的位置 */
    fun savePicture(img: ImageBitmap, name: String): String
    /** 识别二维码图片里的文字；不支持的平台返回 null */
    fun qrText(png: ByteArray): String? = null
    /** 下载歌曲存放的目录 */
    fun downloadDir(): File = File(filesDir, "downloads")
    /** 下载位置的常用选项（名字 to 路径），设置里直接点选 */
    fun dirPresets(): List<Pair<String, String>> = emptyList()
    /** 弹出系统的选文件夹窗口；不支持的平台直接回调 null */
    fun pickFolder(start: String?, done: (String?) -> Unit) { done(null) }
    /** 下载完通知系统媒体库（Android 让其它播放器也能看到） */
    fun mediaScan(f: File) {}
    /** 系统壁纸取出的主色（Android 12+），其它平台 null */
    fun systemSeed(): Int? = null
    /** 当前网络是否按流量计费（手机流量 / 热点） */
    fun metered(): Boolean = false
    /** 扫描本地音乐 */
    fun scanLocal(): List<LocalSong>
    /** 读取音乐的权限：(已允许, 去申请) */
    @Composable fun audioPermission(): Pair<Boolean, () -> Unit> = true to {}
    /** 选图片：返回一个「打开选择器」的函数，选好后回调图片的字节 */
    @Composable fun imagePicker(onPick: (ByteArray) -> Unit): () -> Unit
    /** 选一个文件读出来（备份导入、导入音源脚本用）；不支持的平台返回 null */
    @Composable fun filePicker(onPick: (ByteArray) -> Unit): (() -> Unit)? = null
    /** 「另存为」：返回启动函数 (默认文件名, 内容)，保存后回调位置（取消为 null）；不支持返回 null */
    @Composable fun fileSaver(onDone: (String?) -> Unit): ((String, ByteArray) -> Unit)? = null
    @Composable fun BackHandler(enabled: Boolean, onBack: () -> Unit)
    /** MV 播放区域 */
    @Composable fun VideoPlayer(url: String?, modifier: Modifier, onState: (playing: Boolean, pos: Long, dur: Long, err: String?) -> Unit, control: VideoControl)
    /** 能动的图片（动态头像 GIF 等），不支持就按静态图显示 */
    @Composable fun AnimatedImage(url: String, modifier: Modifier)
    /** 内嵌网页（QQ 网页登录用）。intercept 返回 true 表示拦下这次跳转；url 变了就重新加载。不支持的平台不显示 */
    /** stopOn：地址匹配这个正则的页面打开时先停止加载（电脑版网页看不到跳转前的地址，只能这样拦） */
    @Composable fun WebPage(url: String, modifier: Modifier, desktopUa: Boolean = false, onInfo: (String) -> Unit = {}, stopOn: String? = null, intercept: (String) -> Boolean) {}
    val hasWebView: Boolean get() = false
    /** 桌面版：本进程 CPU（%）和内存 RSS/WorkingSet（MB）；安卓返回 null（用 /proc） */
    fun cpuMem(): Pair<Float, Long>? = null
    /** 当前已用堆内存（MB）；不支持的平台返回 0 */
    fun heapMb(): Long = 0L
    fun memInfo(): String
    fun memAnalysis(): String
    /** 桌面端 VST2 / VST3 实时宿主；移动端返回 null */
    val vst: VstHost? get() = null
}

data class VstFound(val path: String, val name: String, val format: String)
data class VstSlot(val id: Int, val path: String, val name: String, val format: String, val bypass: Boolean, val editorOpen: Boolean)
data class VstParam(val index: Int, val id: Long, val name: String, val value: Float, val display: String)

interface VstHost {
    val available: Boolean
    val loadError: String?
    val enabled: Boolean
    val onTop: Boolean
    val customDirs: List<String>
    val chain: List<VstSlot>
    fun setEnabled(on: Boolean): String?
    fun setOnTop(on: Boolean)
    fun addDir(dir: String)
    fun removeDir(dir: String)
    fun scan(): List<VstFound>
    fun addPlugin(path: String): String?
    fun removePlugin(id: Int)
    fun movePlugin(id: Int, newIndex: Int)
    fun setBypass(id: Int, bypass: Boolean)
    fun params(id: Int): List<VstParam>
    fun setParam(id: Int, index: Int, value: Float)
    fun openEditor(id: Int): String?
    fun closeEditor(id: Int)
}

/** MV 播放控制（由平台实现填充） */
class VideoControl {
    var toggle: () -> Unit = {}
    var seek: (Long) -> Unit = {}
}

/** 别的 App 跳回来的地址（nekoplay://…），QQ 授权后用 */
object DeepLink { val url = kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }

object Plat {
    lateinit var impl: PlatImpl
    val isDesktop get() = impl.name == "desktop"
    fun prefs(name: String) = impl.prefs(name)
}
