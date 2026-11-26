package cc.catsuki.nekoplay

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.delay
import java.io.File
import java.util.Properties

/** 桌面平台实现（Windows / macOS / Linux） */
class DesktopPlat : PlatImpl {
    companion object {
        private var backStack = ArrayList<Pair<() -> Boolean, () -> Unit>>()
        /** Esc：交给当前启用的返回处理 */
        fun back(): Boolean { val h = backStack.lastOrNull { it.first() } ?: return false; h.second(); return true }
    }

    override val name = "desktop"
    override fun systemLang(): String = java.util.Locale.getDefault().toLanguageTag()
    private val home: File = File(System.getProperty("user.home"), if (System.getProperty("os.name").lowercase().contains("win")) "AppData/Roaming/NEKOPLAY" else ".nekoplay").apply { mkdirs() }
    override val filesDir: File = File(home, "files").apply { mkdirs() }
    override val cacheDir: File = File(home, "cache").apply { mkdirs() }
    private val kvs = HashMap<String, KV>()
    override fun prefs(name: String): KV = kvs.getOrPut(name) { FileKV(File(home, "$name.properties")) }

    private fun font(n: String, w: FontWeight) = Font("nekoplay-$n", javaClass.getResourceAsStream("/font/$n.ttf")!!.readBytes(), w)
    override val inter = FontFamily(font("inter_400", FontWeight.W400), font("inter_500", FontWeight.W500), font("inter_600", FontWeight.W600),
        font("inter_700", FontWeight.W700), font("inter_800", FontWeight.W800), font("inter_900", FontWeight.W900))
    override val mono = FontFamily(font("mono_400", FontWeight.W400), font("mono_500", FontWeight.W500), font("mono_600", FontWeight.W600), font("mono_700", FontWeight.W700))
    override val liveBlur = true
    private val mpvAudio = MpvAudio()
    override val audio: Audio = mpvAudio
    private val vstHost by lazy { DesktopVstHost { pipe -> mpvAudio.setPcmPipe(pipe) } }
    override val vst: VstHost get() = vstHost

    override fun decodeImage(path: String, maxSide: Int): ImageBitmap? {
        val img = org.jetbrains.skia.Image.makeFromEncoded(File(path).readBytes()).toComposeImageBitmap()
        val m = maxOf(img.width, img.height)
        return if (m <= maxSide) img else scaled(img, img.width * maxSide / m, img.height * maxSide / m)
    }
    override fun pixels(img: ImageBitmap): IntArray { val px = IntArray(img.width * img.height); img.readPixels(px); return px }
    override fun fromPixels(px: IntArray, w: Int, h: Int): ImageBitmap {
        val b = org.jetbrains.skia.Bitmap()
        b.allocPixels(org.jetbrains.skia.ImageInfo.makeN32(w, h, org.jetbrains.skia.ColorAlphaType.PREMUL))
        val bytes = ByteArray(w * h * 4)
        for (i in px.indices) { val c = px[i]; bytes[i * 4] = (c and 255).toByte(); bytes[i * 4 + 1] = ((c shr 8) and 255).toByte(); bytes[i * 4 + 2] = ((c shr 16) and 255).toByte(); bytes[i * 4 + 3] = (c ushr 24).toByte() }
        b.installPixels(bytes)
        return b.asComposeImageBitmap()
    }
    override fun scaled(img: ImageBitmap, w: Int, h: Int): ImageBitmap {
        val src = org.jetbrains.skia.Image.makeFromBitmap(img.asSkiaBitmap())
        val surf = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
        surf.canvas.drawImageRect(src, org.jetbrains.skia.Rect.makeWH(src.width.toFloat(), src.height.toFloat()), org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()), org.jetbrains.skia.SamplingMode.LINEAR, null, true)
        return surf.makeImageSnapshot().toComposeImageBitmap()
    }
    /** 扫「音乐」文件夹（最多 6 层）；文件名是「歌手 - 歌名」时拆开 */
    override fun scanLocal(): List<LocalSong> {
        val exts = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "wma", "alac")
        val root = File(System.getProperty("user.home"), "Music")
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().maxDepth(6).filter { it.isFile && it.extension.lowercase() in exts && it.length() > 300_000 }.map { f ->
            val n = f.nameWithoutExtension
            val (ar, ti) = if (" - " in n) n.substringBefore(" - ").trim() to n.substringAfter(" - ").trim() else "未知歌手" to n
            LocalSong(f.absolutePath, ti, ar, f.parentFile?.name ?: "", 0)
        }.toList()
    }
    override fun savePicture(img: ImageBitmap, name: String): String {
        val dir = File(System.getProperty("user.home"), "Pictures/NEKOPLAY").apply { mkdirs() }
        val f = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".png")
        val data = org.jetbrains.skia.Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG) ?: throw Exception("编码失败")
        f.writeBytes(data.bytes); return f.absolutePath
    }
    override fun saveJpeg(img: ImageBitmap, file: File) {
        val data = org.jetbrains.skia.Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 90) ?: return
        file.writeBytes(data.bytes)
    }

    /** 用应用内的选择器（缩略图网格 + 预览），代替系统的文件对话框 */
    @Composable override fun imagePicker(onPick: (ByteArray) -> Unit): () -> Unit {
        var open by remember { mutableStateOf(false) }
        if (open) ImagePickerDialog(onDismiss = { open = false }) { f -> open = false; onPick(f.readBytes()) }
        return { open = true }
    }

    @Composable override fun filePicker(onPick: (ByteArray) -> Unit): (() -> Unit)? = {
        val d = java.awt.FileDialog(null as java.awt.Frame?, "选择文件", java.awt.FileDialog.LOAD); d.isVisible = true
        if (d.file != null) onPick(File(d.directory, d.file).readBytes())
    }
    @Composable override fun fileSaver(onDone: (String?) -> Unit): ((String, ByteArray) -> Unit)? = { name, bytes ->
        val d = java.awt.FileDialog(null as java.awt.Frame?, "保存", java.awt.FileDialog.SAVE); d.file = name; d.isVisible = true
        if (d.file == null) onDone(null) else { val f = File(d.directory, d.file); f.writeBytes(bytes); onDone(f.absolutePath) }
    }

    @Composable override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
        val en by rememberUpdatedState(enabled); val cb by rememberUpdatedState(onBack)
        DisposableEffect(Unit) {
            val h: Pair<() -> Boolean, () -> Unit> = { en } to { cb() }
            backStack.add(h); onDispose { backStack.remove(h) }
        }
    }

    // 内嵌网页（插件的网页登录）：Windows 与 macOS 均对齐禁用内嵌 WebView，统一走原生扫码 / Cookie 登录；仅 Linux 保留 JavaFX WebView
    override val hasWebView get() = DesktopWebView.available || FxWebView.available
    @Composable override fun WebPage(url: String, modifier: Modifier, desktopUa: Boolean, onInfo: (String) -> Unit, stopOn: String?, intercept: (String) -> Boolean) {
        if (DesktopWebView.available) DesktopWebPage(url, modifier, stopOn, onInfo, intercept)
        else FxWebPage(url, modifier, desktopUa, stopOn, onInfo, intercept)
    }

    /** MV：另开一个 mpv 窗口播放（嵌入播放以后再做） */
    @Composable override fun VideoPlayer(url: String?, modifier: Modifier, onState: (Boolean, Long, Long, String?) -> Unit, control: VideoControl) {
        LaunchedEffect(url) {
            if (url == null) return@LaunchedEffect
            val ok = runCatching { ProcessBuilder("mpv", "--force-window=yes", "--title=NEKOPLAY MV", "--referrer=https://music.163.com/", url).start() }.isSuccess
            onState(false, 0, 0, if (ok) "已在 mpv 窗口中播放" else "需要安装 mpv 才能播放 MV")
        }
    }

    /** 动态头像：下载原图，用 Skia 解出每一帧自己播；只有一帧就当静态图 */
    @Composable override fun AnimatedImage(url: String, modifier: Modifier) {
        if (UiPref.lowMem) { coil3.compose.AsyncImage(img(url, 256), null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop); return }
        var frames by remember(url) { mutableStateOf(animCache[url]) }
        LaunchedEffect(url) {
            if (frames == null) frames = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { decodeFrames(url) }.getOrNull() }?.also { animCache[url] = it }
        }
        val f = frames
        if (f == null) { coil3.compose.AsyncImage(img(url, 256), null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop); return }
        var i by remember(f) { mutableIntStateOf(0) }
        if (f.size > 1) LaunchedEffect(f) { while (true) { delay(f[i].second.coerceAtLeast(20).toLong()); i = (i + 1) % f.size } }
        androidx.compose.foundation.Image(f[i % f.size].first, null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
    /** 动图头像解码后的帧：只留最近 8 个，免得翻评论区时内存一直涨 */
    private val animCache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, List<Pair<ImageBitmap, Int>>>(16, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<Pair<ImageBitmap, Int>>>?) = size > 8
    })
    private fun decodeFrames(url: String): List<Pair<ImageBitmap, Int>> {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").header("Referer", "https://music.163.com/").build()
        val bytes = Ncm.http.newCall(req).execute().use { it.body!!.bytes() }
        val codec = org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes))
        val n = codec.frameCount.coerceIn(1, 120)
        val info = codec.framesInfo
        val full = org.jetbrains.skia.Bitmap(); full.allocPixels(codec.imageInfo)
        val m = maxOf(codec.width, codec.height)
        val tw = (codec.width * 160 / m).coerceAtLeast(1); val th = (codec.height * 160 / m).coerceAtLeast(1)
        // 逐帧解到同一块缓冲（GIF 帧会叠加在前一帧上），再缩成 160px 的小图存起来
        return (0 until n).map { k ->
            codec.readPixels(full, k)
            val small = if (m <= 160) full.makeClone() else {
                val surf = org.jetbrains.skia.Surface.makeRasterN32Premul(tw, th)
                surf.canvas.drawImageRect(org.jetbrains.skia.Image.makeFromBitmap(full), org.jetbrains.skia.Rect.makeWH(codec.width.toFloat(), codec.height.toFloat()),
                    org.jetbrains.skia.Rect.makeWH(tw.toFloat(), th.toFloat()), org.jetbrains.skia.SamplingMode.LINEAR, null, true)
                org.jetbrains.skia.Bitmap.makeFromImage(surf.makeImageSnapshot())
            }
            small.setImmutable(); small.asComposeImageBitmap() to (info.getOrNull(k)?.duration ?: 100)
        }
    }

    /** CPU：JVM 报告的本进程占用；内存：已提交的内存（接近任务管理器里的值） */
    override fun downloadDir(): File = File(System.getProperty("user.home"), "Music/NEKOPLAY")
    override fun dirPresets(): List<Pair<String, String>> {
        val home = System.getProperty("user.home")
        return listOf("音乐/NEKOPLAY" to "$home${File.separator}Music${File.separator}NEKOPLAY", "下载/NEKOPLAY" to "$home${File.separator}Downloads${File.separator}NEKOPLAY")
    }
    /** 选文件夹：用应用内的选择器（文件夹模式），和选图片同一套界面，也能粘贴路径、收藏位置 */
    override fun pickFolder(start: String?, done: (String?) -> Unit) { FolderPick.req = start to done }
    private var lastRssAt = 0L
    private var cachedMacRss = 0L
    private fun macRssMB(): Long? {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return null
        val now = System.currentTimeMillis()
        if (now - lastRssAt < 3000L && cachedMacRss > 0L) return cachedMacRss
        val rss = runCatching {
            val pid = ProcessHandle.current().pid()
            val out = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString()).start().inputStream.bufferedReader().readText().trim()
            out.toLong() / 1024L
        }.getOrNull() ?: return cachedMacRss.takeIf { it > 0L }
        cachedMacRss = rss; lastRssAt = now
        return rss
    }
    override fun heapMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1048576L
    }
    override fun cpuMem(): Pair<Float, Long>? = runCatching {
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val cpu = (os.processCpuLoad * 100).toFloat().coerceAtLeast(0f)
        val rss = WinMem.workingSetMB()
            ?: runCatching { File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.split(Regex("\\s+"))[1].toLong() / 1024 }.getOrNull()
            ?: macRssMB()
        val mem = rss ?: run {
            val heap = java.lang.management.ManagementFactory.getMemoryMXBean()
            (heap.heapMemoryUsage.committed + heap.nonHeapMemoryUsage.committed) / 1048576 + 60 // 加上 Skia / 原生库的大致占用
        }
        cpu to mem
    }.getOrNull()

    override fun memInfo(): String {
        val rt = Runtime.getRuntime()
        val rss = WinMem.workingSetMB() ?: runCatching { File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.split(Regex("\\s+"))[1].toLong() / 1024 }.getOrNull()
        return "${if (rss != null) "常驻内存 ${rss}M · " else ""}Java 堆 ${(rt.totalMemory() - rt.freeMemory()) / 1048576}M / 上限 ${rt.maxMemory() / 1048576}M"
    }
    /** 内存分项：Java 堆 / 类元数据 / 代码缓存 / 直接内存 / 线程，剩下的是原生部分（Skia 渲染、mpv、JVM 自身） */
    override fun memAnalysis(): String {
        val mx = java.lang.management.ManagementFactory.getMemoryMXBean()
        val pools = java.lang.management.ManagementFactory.getMemoryPoolMXBeans()
        fun mb(b: Long) = b / 1048576
        val heap = mx.heapMemoryUsage; val non = mx.nonHeapMemoryUsage
        val meta = pools.filter { it.name.contains("Metaspace") || it.name.contains("Class") }.sumOf { it.usage.committed }
        val code = pools.filter { it.name.contains("CodeHeap") || it.name.contains("Code Cache") }.sumOf { it.usage.committed }
        val direct = java.lang.management.ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean::class.java).sumOf { it.memoryUsed }
        val threads = Thread.activeCount()
        val ws = WinMem.workingSetMB(); val priv = WinMem.privateMB()
        val javaPart = mb(heap.committed) + mb(non.committed) + mb(direct)
        return buildString {
            if (ws != null) append("工作集 ${ws}M · 私有 ${priv}M\n")
            append("Java 堆 已用 ${mb(heap.used)}M / 已申请 ${mb(heap.committed)}M\n")
            append("类元数据 ${mb(meta)}M · 代码缓存 ${mb(code)}M · 直接内存 ${mb(direct)}M · 线程 $threads\n")
            if (priv != null) append("原生（Skia/mpv/JVM）约 ${priv - javaPart}M")
        }
    }
}

/** 简单的 properties 文件存储 */
private class FileKV(val f: File) : KV {
    private val p = Properties().apply { if (f.exists()) runCatching { f.inputStream().use { load(it) } } }
    private fun save() = synchronized(p) {
        runCatching {
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.outputStream().use { p.store(it, null) }
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
    override fun getString(k: String, d: String?) = p.getProperty(k) ?: d
    override fun all(): Map<String, Any?> = synchronized(p) { p.stringPropertyNames().associateWith { p.getProperty(it) } }
    override fun getInt(k: String, d: Int) = p.getProperty(k)?.toIntOrNull() ?: d
    override fun getLong(k: String, d: Long) = p.getProperty(k)?.toLongOrNull() ?: d
    override fun getFloat(k: String, d: Float) = p.getProperty(k)?.toFloatOrNull() ?: d
    override fun getBoolean(k: String, d: Boolean) = p.getProperty(k)?.toBooleanStrictOrNull() ?: d
    override fun getStringSet(k: String, d: Set<String>?): Set<String>? = p.getProperty(k)?.let { if (it.isEmpty()) emptySet() else it.split('\u0001').toSet() } ?: d
    override fun edit(): KV.Ed = object : KV.Ed {
        val ops = ArrayList<() -> Unit>()
        override fun putString(k: String, v: String?) = apply { ops += { if (v == null) p.remove(k) else p.setProperty(k, v) } }
        override fun putInt(k: String, v: Int) = putString(k, v.toString())
        override fun putLong(k: String, v: Long) = putString(k, v.toString())
        override fun putFloat(k: String, v: Float) = putString(k, v.toString())
        override fun putBoolean(k: String, v: Boolean) = putString(k, v.toString())
        override fun putStringSet(k: String, v: Set<String>?) = putString(k, v?.joinToString("\u0001"))
        override fun remove(k: String) = apply { ops += { p.remove(k) } }
        override fun clear() = apply { ops += { p.clear() } }
        override fun apply() { synchronized(p) { ops.forEach { it() } }; save() }
    }
}

/** Windows：用 psapi 读当前进程的工作集 / 私有内存（和任务管理器的数字对得上） */
object WinMem {
    @com.sun.jna.Structure.FieldOrder("cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
        "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage", "PrivateUsage")
    class Counters : com.sun.jna.Structure() {
        @JvmField var cb = 0; @JvmField var PageFaultCount = 0
        @JvmField var PeakWorkingSetSize = 0L; @JvmField var WorkingSetSize = 0L
        @JvmField var QuotaPeakPagedPoolUsage = 0L; @JvmField var QuotaPagedPoolUsage = 0L
        @JvmField var QuotaPeakNonPagedPoolUsage = 0L; @JvmField var QuotaNonPagedPoolUsage = 0L
        @JvmField var PagefileUsage = 0L; @JvmField var PeakPagefileUsage = 0L; @JvmField var PrivateUsage = 0L
    }
    private interface Psapi : Library { fun GetProcessMemoryInfo(p: Pointer, c: Counters, cb: Int): Boolean }
    private interface K32 : Library { fun GetCurrentProcess(): Pointer; fun SetProcessWorkingSetSize(p: Pointer, min: Long, max: Long): Boolean }
    private val win = System.getProperty("os.name").startsWith("Windows")
    private val ps by lazy { runCatching { Native.load("psapi", Psapi::class.java) }.getOrNull() }
    private val k by lazy { runCatching { Native.load("kernel32", K32::class.java) }.getOrNull() }
    private fun read(): Counters? {
        if (!win) return null
        val c = Counters(); c.cb = c.size()
        return c.takeIf { runCatching { ps!!.GetProcessMemoryInfo(k!!.GetCurrentProcess(), it, it.cb) }.getOrDefault(false) }
    }
    fun workingSetMB(): Long? = read()?.WorkingSetSize?.div(1048576)
    fun privateMB(): Long? = read()?.PrivateUsage?.div(1048576)
    /** 把暂时不用的页还给系统（最小化时用） */
    fun trim() { if (win) runCatching { k!!.SetProcessWorkingSetSize(k!!.GetCurrentProcess(), -1, -1) } }
}
