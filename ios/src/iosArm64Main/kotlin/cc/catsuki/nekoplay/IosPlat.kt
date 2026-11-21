package cc.catsuki.nekoplay

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.viewinterop.UIKitViewController
import java.io.File
import java.io.toByteArray
import java.io.toNSData
import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import platform.AVFoundation.*
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.*
import platform.PhotosUI.*
import platform.UIKit.*
import platform.darwin.*
import platform.posix.memcpy

/** iOS 平台实现 */
class IosPlat : PlatImpl {
    companion object {
        /** 返回键（iOS 没有）：页面注册的处理函数，左边缘右滑时调用最上面那个 */
        private val backStack = ArrayList<Pair<() -> Boolean, () -> Unit>>()
        fun back(): Boolean { val h = backStack.lastOrNull { it.first() } ?: return false; h.second(); return true }
        fun canBack() = backStack.any { it.first() }
        val lowEnd: Boolean = NSProcessInfo.processInfo.physicalMemory < 2_600_000_000uL

        val docs: File = File(NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String)
        private val support: File = File(NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String)
        private val caches: File = File(NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String)

        fun topController(): UIViewController? {
            var c = UIApplication.sharedApplication.keyWindow?.rootViewController
            while (c?.presentedViewController != null) c = c.presentedViewController
            return c
        }
    }

    override val name = "ios"
    override val filesDir: File = File(support, "files").apply { mkdirs() }
    override val cacheDir: File = File(caches, "nekoplay").apply { mkdirs() }
    private val kvs = HashMap<String, KV>()
    override fun prefs(name: String): KV = kvs.getOrPut(name) { JsonKV(File(File(support, "prefs").apply { mkdirs() }, "$name.json")) }

    private fun font(n: String, w: FontWeight): androidx.compose.ui.text.font.Font {
        val p = NSBundle.mainBundle.pathForResource("font/$n", "ttf") ?: NSBundle.mainBundle.pathForResource(n, "ttf")
        return Font("nekoplay-$n", File(p ?: error("缺少字体 $n")).readBytes(), w)
    }
    override val inter by lazy {
        FontFamily(font("inter_400", FontWeight.W400), font("inter_500", FontWeight.W500), font("inter_600", FontWeight.W600),
            font("inter_700", FontWeight.W700), font("inter_800", FontWeight.W800), font("inter_900", FontWeight.W900))
    }
    override val mono by lazy { FontFamily(font("mono_400", FontWeight.W400), font("mono_500", FontWeight.W500), font("mono_600", FontWeight.W600), font("mono_700", FontWeight.W700)) }
    /** 2GB 内存的老设备（iPhone SE 一代 / 6s）关掉实时模糊，省 GPU */
    override val liveBlur = !lowEnd
    override val audio: Audio by lazy { IosAudio(cacheDir) }

    /** 下载放「文件」App 里能看到的位置：我的 iPhone / NEKOPLAY / NEKOPLAY */
    override fun downloadDir(): File = File(docs, "NEKOPLAY")
    override fun dirPresets() = listOf("文件 App / NEKOPLAY" to File(docs, "NEKOPLAY").path, "文件 App 根目录" to docs.path)

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
    override fun saveJpeg(img: ImageBitmap, file: File) {
        val data = org.jetbrains.skia.Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 90) ?: return
        file.writeBytes(data.bytes)
    }
    override fun qrText(png: ByteArray): String? = runCatching {
        // 图很小，先放大 4 倍再给 CoreImage 识别
        val ci = platform.CoreImage.CIImage.imageWithData(png.toNSData())?.imageByApplyingTransform(platform.CoreGraphics.CGAffineTransformMakeScale(4.0, 4.0)) ?: return null
        val det = platform.CoreImage.CIDetector.detectorOfType(platform.CoreImage.CIDetectorTypeQRCode, null,
            mapOf<Any?, Any?>(platform.CoreImage.CIDetectorAccuracy to platform.CoreImage.CIDetectorAccuracyHigh)) ?: return null
        (det.featuresInImage(ci).firstOrNull() as? platform.CoreImage.CIQRCodeFeature)?.messageString
    }.getOrNull()

    override fun savePicture(img: ImageBitmap, name: String): String {
        val data = org.jetbrains.skia.Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG) ?: throw Exception("编码失败")
        val ui = UIImage.imageWithData(data.bytes.toNSData()) ?: throw Exception("编码失败")
        UIImageWriteToSavedPhotosAlbum(ui, null, null, null)
        return "相册"
    }

    /** 扫「文件」App 里本 App 的文件夹（用户可以把音乐拖进来） */
    override fun scanLocal(): List<LocalSong> {
        val exts = setOf("mp3", "flac", "m4a", "aac", "wav", "alac", "aiff", "caf")
        return docs.walk().filter { it.isFile && it.extension.lowercase() in exts && it.length() > 300_000 }.map { f ->
            val n = f.nameWithoutExtension
            val (ar, ti) = if (" - " in n) n.substringBefore(" - ").trim() to n.substringAfter(" - ").trim() else "未知歌手" to n
            LocalSong(f.path, ti, ar, f.parentFile?.name ?: "", 0)
        }.toList()
    }

    // ---------- 选图片：系统照片选择器（iOS 14+） ----------
    private var pickerDelegate: PickDelegate? = null
    private class PickDelegate(val onPick: (ByteArray) -> Unit, val done: () -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
        override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
            picker.dismissViewControllerAnimated(true, null)
            val r = didFinishPicking.firstOrNull() as? PHPickerResult
            if (r == null) { done(); return }
            r.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
                val bytes = data?.toByteArray()
                dispatch_async(dispatch_get_main_queue()) { if (bytes != null) onPick(bytes); done() }
            }
        }
    }
    @Composable override fun imagePicker(onPick: (ByteArray) -> Unit): () -> Unit {
        val cb by rememberUpdatedState(onPick)
        return {
            val cfg = PHPickerConfiguration().apply { setFilter(PHPickerFilter.imagesFilter); setSelectionLimit(1) }
            val p = PHPickerViewController(cfg)
            val d = PickDelegate({ cb(it) }) { pickerDelegate = null }
            pickerDelegate = d
            p.delegate = d
            topController()?.presentViewController(p, true, null)
        }
    }

    @Composable override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
        val en by rememberUpdatedState(enabled); val cb by rememberUpdatedState(onBack)
        DisposableEffect(Unit) {
            val h: Pair<() -> Boolean, () -> Unit> = { en } to { cb() }
            backStack.add(h); onDispose { backStack.remove(h) }
        }
    }

    /** MV：系统播放器 */
    @Composable override fun VideoPlayer(url: String?, modifier: Modifier, onState: (Boolean, Long, Long, String?) -> Unit, control: VideoControl) {
        val player = remember { AVPlayer() }
        DisposableEffect(Unit) {
            control.toggle = { if (player.rate != 0f) player.pause() else player.play() }
            control.seek = { player.seekToTime(CMTimeMakeWithSeconds(it / 1000.0, 1000)) }
            onDispose { player.pause(); player.replaceCurrentItemWithPlayerItem(null) }
        }
        LaunchedEffect(url) {
            if (url == null) return@LaunchedEffect
            val u = NSURL.URLWithString(url) ?: return@LaunchedEffect
            player.replaceCurrentItemWithPlayerItem(AVPlayerItem(uRL = u)); player.play()
        }
        LaunchedEffect(Unit) {
            while (true) {
                val it = player.currentItem
                val dur = it?.duration?.let { d -> CMTimeGetSeconds(d) }?.takeIf { d -> !d.isNaN() && d > 0 } ?: 0.0
                val err = if (it?.status == AVPlayerItemStatusFailed) "播放失败：" + (it.error?.localizedDescription ?: "") else null
                onState(player.rate != 0f, (CMTimeGetSeconds(player.currentTime()).takeIf { d -> !d.isNaN() } ?: 0.0).times(1000).toLong(), (dur * 1000).toLong(), err)
                delay(250)
            }
        }
        UIKitViewController(factory = { AVPlayerViewController().apply { this.player = player; showsPlaybackControls = false; videoGravity = AVLayerVideoGravityResizeAspect } }, modifier = modifier)
    }

    @Composable override fun AnimatedImage(url: String, modifier: Modifier) =
        coil3.compose.AsyncImage(if (UiPref.lowMem) img(url, 256) else url, null, modifier, contentScale = ContentScale.Crop)

    /** 内存：系统统计的本进程实占（phys_footprint，和 Xcode 显示的一致） */
    private fun footprintMb(): Long = memScoped {
        val info = alloc<task_vm_info_data_t>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<integer_tVar>()).toUInt()
        val kr = task_info(mach_task_self_, TASK_VM_INFO.toUInt(), info.ptr.reinterpret(), count.ptr)
        if (kr == KERN_SUCCESS) (info.phys_footprint / 1048576u).toLong() else 0L
    }
    override fun cpuMem(): Pair<Float, Long>? = 0f to footprintMb()
    override fun memInfo(): String = "实占 ${footprintMb()}M"
    override fun memAnalysis(): String = "iOS 只提供总的实占内存：${footprintMb()}M"
}

/** 设置存成 JSON 文件（和桌面版一样宽容：类型不对就用默认值） */
private class JsonKV(val f: File) : KV {
    private val m = LinkedHashMap<String, Any?>()
    init {
        runCatching {
            val o = JSONObject(f.readText())
            for (k in o.keySet()) {
                val v = o.opt(k)
                m[k] = if (v is JSONArray) (0 until v.length()).map { v.optString(it) }.toSet() else v
            }
        }
    }
    private fun save() {
        val o = JSONObject()
        synchronized(m) { for ((k, v) in m) o.put(k, if (v is Set<*>) JSONArray(v.toList()) else v) }
        runCatching { f.writeText(o.toString()) }
    }
    private fun raw(k: String) = synchronized(m) { m[k] }
    override fun getString(k: String, d: String?) = raw(k)?.let { if (it is Set<*>) it.joinToString("\u0001") else it.toString() } ?: d
    override fun getInt(k: String, d: Int) = when (val v = raw(k)) { is Number -> v.toInt(); is String -> v.toIntOrNull() ?: v.toFloatOrNull()?.toInt() ?: d; else -> d }
    override fun getLong(k: String, d: Long) = when (val v = raw(k)) { is Number -> v.toLong(); is String -> v.toLongOrNull() ?: d; else -> d }
    override fun getFloat(k: String, d: Float) = when (val v = raw(k)) { is Number -> v.toFloat(); is String -> v.toFloatOrNull() ?: d; else -> d }
    override fun getBoolean(k: String, d: Boolean) = when (val v = raw(k)) { is Boolean -> v; is String -> v.toBooleanStrictOrNull() ?: d; else -> d }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(k: String, d: Set<String>?): Set<String>? = when (val v = raw(k)) { is Set<*> -> v as Set<String>; is String -> if (v.isEmpty()) emptySet() else v.split('\u0001').toSet(); else -> d }
    override fun all(): Map<String, Any?> = synchronized(m) { LinkedHashMap(m) }
    override fun edit(): KV.Ed = object : KV.Ed {
        val ops = ArrayList<() -> Unit>()
        private fun put(k: String, v: Any?) = apply { ops += { if (v == null) m.remove(k) else m[k] = v } }
        override fun putString(k: String, v: String?) = put(k, v)
        override fun putInt(k: String, v: Int) = put(k, v)
        override fun putLong(k: String, v: Long) = put(k, v)
        override fun putFloat(k: String, v: Float) = put(k, v.toDouble())
        override fun putBoolean(k: String, v: Boolean) = put(k, v)
        override fun putStringSet(k: String, v: Set<String>?) = put(k, v?.toSet())
        override fun remove(k: String) = apply { ops += { m.remove(k) } }
        override fun clear() = apply { ops += { m.clear() } }
        override fun apply() { synchronized(m) { ops.forEach { it() } }; save() }
    }
}
