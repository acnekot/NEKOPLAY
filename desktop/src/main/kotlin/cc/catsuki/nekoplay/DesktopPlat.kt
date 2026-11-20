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
import com.sun.jna.ptr.DoubleByReference
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
    override val audio: Audio = MpvAudio()

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

    @Composable override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
        val en by rememberUpdatedState(enabled); val cb by rememberUpdatedState(onBack)
        DisposableEffect(Unit) {
            val h: Pair<() -> Boolean, () -> Unit> = { en } to { cb() }
            backStack.add(h); onDispose { backStack.remove(h) }
        }
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
    private val animCache = HashMap<String, List<Pair<ImageBitmap, Int>>>()
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
    override fun cpuMem(): Pair<Float, Long>? = runCatching {
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val cpu = (os.processCpuLoad * 100).toFloat().coerceAtLeast(0f)
        val rss = runCatching { File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.split(Regex("\\s+"))[1].toLong() / 1024 }.getOrNull()
        val mem = rss ?: run {
            val heap = java.lang.management.ManagementFactory.getMemoryMXBean()
            (heap.heapMemoryUsage.committed + heap.nonHeapMemoryUsage.committed) / 1048576 + 60 // 加上 Skia / 原生库的大致占用
        }
        cpu to mem
    }.getOrNull()

    override fun memInfo(): String {
        val rt = Runtime.getRuntime()
        val rss = runCatching { File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.split(Regex("\\s+"))[1].toLong() / 1024 }.getOrNull()
        return "${if (rss != null) "常驻内存 ${rss}M · " else ""}Java 堆 ${(rt.totalMemory() - rt.freeMemory()) / 1048576}M / 上限 ${rt.maxMemory() / 1048576}M"
    }
    override fun memAnalysis(): String = memInfo()
}

/** 简单的 properties 文件存储 */
private class FileKV(val f: File) : KV {
    private val p = Properties().apply { if (f.exists()) runCatching { f.inputStream().use { load(it) } } }
    private fun save() = synchronized(p) { runCatching { f.outputStream().use { p.store(it, null) } } }
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

/** libmpv 的最小绑定 */
private interface Mpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(h: Pointer): Int
    fun mpv_set_option_string(h: Pointer, name: String, v: String): Int
    fun mpv_command(h: Pointer, args: Array<String?>): Int
    fun mpv_set_property_string(h: Pointer, name: String, v: String): Int
    fun mpv_get_property_string(h: Pointer, name: String): Pointer?
    fun mpv_get_property(h: Pointer, name: String, format: Int, out: DoubleByReference): Int
    fun mpv_free(p: Pointer)
}

/**
 * 桌面播放内核：libmpv（支持 FLAC / 边下边播，占用低）。
 * Windows 需要把 libmpv-2.dll 放在程序目录；macOS / Linux 装 mpv 即可。
 * 加载失败时界面照常可用，只是播放会提示错误。
 */
private class MpvAudio : Audio {
    override var listener: Audio.Listener? = null
    /** 先找安装包自带的 libmpv（程序资源目录），找不到再找系统里装的 */
    private val lib: Mpv? = run {
        val bundled = System.getProperty("compose.application.resources.dir")?.let { File(it) }
            ?.listFiles { f -> f.name.startsWith("libmpv") && (f.name.endsWith(".dll") || f.name.endsWith(".dylib") || f.name.contains(".so")) }
            ?.sortedBy { it.name.length }?.map { it.absolutePath } ?: emptyList()
        (bundled + listOf("mpv", "libmpv-2", "mpv-2", "libmpv", "mpv.2")).firstNotNullOfOrNull { n -> runCatching { Native.load(n, Mpv::class.java) }.getOrNull() }
    }
    private val h: Pointer? = lib?.mpv_create()?.also { h ->
        lib.mpv_set_option_string(h, "vid", "no")
        lib.mpv_set_option_string(h, "keep-open", "yes")
        lib.mpv_set_option_string(h, "idle", "yes")
        lib.mpv_set_option_string(h, "user-agent", "Mozilla/5.0")
        lib.mpv_set_option_string(h, "referrer", "https://music.163.com/")
        lib.mpv_set_option_string(h, "cache", "yes")
        lib.mpv_initialize(h)
    }
    @Volatile private var opened = false
    @Volatile private var playing = false
    @Volatile private var ended = false
    @Volatile private var pos = 0L
    @Volatile private var bufPos = 0L

    init {
        // 每 200ms 读一次状态，转成回调（在界面线程里发）
        Thread {
            var lastPlaying = false; var lastBuf = false
            while (true) {
                Thread.sleep(200)
                if (lib == null || h == null || !opened) continue
                val paused = str("pause") == "yes"
                val eof = str("eof-reached") == "yes"
                // 无缝播放：mpv 自己切到了列表里的下一首 → 删掉上一首，通知 Engine
                val pp = str("playlist-pos")?.toIntOrNull() ?: 0
                if (pp > 0) { cmd("playlist-remove", "0"); java.awt.EventQueue.invokeLater { listener?.onAdvanced() } }
                val buf = str("paused-for-cache") == "yes"
                pos = (num("time-pos") * 1000).toLong()
                bufPos = (num("demuxer-cache-time") * 1000).toLong()
                playing = !paused && !eof
                java.awt.EventQueue.invokeLater {
                    if (playing != lastPlaying) { lastPlaying = playing; listener?.onPlaying(playing) }
                    if (buf != lastBuf) { lastBuf = buf; listener?.onBuffering(buf) }
                    if (eof && !ended) { ended = true; listener?.onEnded() }
                }
            }
        }.apply { isDaemon = true; name = "mpv-poll" }.start()
    }

    override fun info(): AudioInfo? = runCatching {
        if (lib == null || h == null) return null
        val codec = str("audio-codec-name") ?: return null
        val fmt = str("audio-params/format") ?: ""
        val bits = when { fmt.startsWith("s16") -> 16; fmt.startsWith("s32") -> if (codec == "flac") 24 else 32; fmt.startsWith("u8") -> 8; else -> 0 }
        AudioInfo(codec, str("audio-params/samplerate")?.toIntOrNull() ?: 0, bits, str("audio-params/channel-count")?.toIntOrNull() ?: 0, (str("audio-bitrate")?.toDoubleOrNull() ?: 0.0).toInt() / 1000)
    }.getOrNull()
    private fun str(n: String): String? { val p = lib!!.mpv_get_property_string(h!!, n) ?: return null; val s = p.getString(0, "UTF-8"); lib.mpv_free(p); return s }
    private fun num(n: String): Double { val r = DoubleByReference(); return if (lib!!.mpv_get_property(h!!, n, 5, r) >= 0) r.value else 0.0 }
    private fun cmd(vararg a: String) { lib?.mpv_command(h!!, arrayOf(*a, null)) }

    override fun open(url: String?, cacheKey: String, startMs: Long, autoplay: Boolean, meta: Track) {
        // 抛异常由 Engine 显示提示，不会自动跳下一首（避免整个队列连环报错）
        if (lib == null || h == null) throw Exception("播放内核 libmpv 加载失败")
        if (url == null) throw Exception("桌面版暂无本地缓存")
        ended = false; opened = true
        lib.mpv_set_property_string(h, "pause", if (autoplay) "no" else "yes")
        val src = if (url.startsWith("file:")) File(java.net.URI(url)).absolutePath else url
        cmd("loadfile", src, "replace", "-1", "start=${startMs / 1000.0}")
    }
    override fun queueNext(url: String?, cacheKey: String, meta: Track) {
        if (lib == null || h == null || url == null) return
        clearNext()
        cmd("loadfile", if (url.startsWith("file:")) File(java.net.URI(url)).absolutePath else url, "append")
    }
    override fun clearNext() { if (lib == null || h == null) return; val n = str("playlist-count")?.toIntOrNull() ?: 0; for (i in n - 1 downTo 1) cmd("playlist-remove", i.toString()) }
    override fun play() { h?.let { lib!!.mpv_set_property_string(it, "pause", "no") } }
    override fun pause() { h?.let { lib!!.mpv_set_property_string(it, "pause", "yes") } }
    override fun seek(ms: Long) { cmd("seek", (ms / 1000.0).toString(), "absolute"); pos = ms; ended = false }
    override val isPlaying get() = playing
    override val idle get() = !opened
    override val position get() = pos
    override val buffered get() = bufPos
    override fun isCached(key: String) = false
    override fun cacheBytes() = 0L
    override fun clearCache() {}
    override var volume: Float = 1f
        set(v) { field = v; h?.let { lib!!.mpv_set_property_string(it, "volume", (v * 100).toInt().toString()) } }

    /** 效果器：拼成 ffmpeg 滤镜链交给 mpv（10 个 equalizer + bass + extrastereo + volume） */
    override fun applyFx(fx: Fx) {
        val hh = h ?: return
        val f = ArrayList<String>()
        if (fx.on) {
            Fx.FREQS.forEachIndexed { i, hz -> if (fx.bands[i] != 0f) f += "equalizer=f=$hz:t=o:w=1:g=${fx.bands[i]}" }
            if (fx.bass > 0) f += "bass=g=${"%.1f".format(java.util.Locale.US, fx.bass * 12)}:f=100"
            if (fx.surround > 0) f += "extrastereo=m=${"%.2f".format(java.util.Locale.US, 1 + fx.surround * 1.5f)}"
            if (fx.loud > 0) f += "volume=${"%.1f".format(java.util.Locale.US, fx.loud * 10)}dB"
            // 有增益时防削波
            if (f.isNotEmpty()) f += "alimiter=limit=0.95"
        }
        lib!!.mpv_set_property_string(hh, "af", if (f.isEmpty()) "" else "lavfi=[${f.joinToString(",")}]")
    }
}
