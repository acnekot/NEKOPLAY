package cc.catsuki.nekoplay

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import java.io.File

/** 安卓平台实现 */
private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

class AndroidPlat(private val ctx: Context) : PlatImpl {
    companion object {
        lateinit var exoAudio: ExoAudio
        /** 系统 FrameMetrics → 性能统计 */
        fun attachFrameMetrics(w: Window) {
            val t = HandlerThread("perf").apply { start() }
            w.addOnFrameMetricsAvailableListener({ _, m, _ ->
                val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
                val gpu = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else 0L
                val deadline = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.DEADLINE) else 16_666_667L
                Perf.frame(total, gpu, total > deadline)
            }, Handler(t.looper))
        }
    }

    init { exoAudio = ExoAudio(ctx); LyricNotify.start(ctx, exoAudio); WidgetSync.start(ctx); attachTiltSensor(ctx) }

    /** 移动端按需陀螺仪 / 重力倾斜传感（仅封面墙打开时监听，自动以初始握持角度为零点平滑偏移） */
    private fun attachTiltSensor(context: Context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        var baseX = Float.NaN; var baseY = Float.NaN
        var smoothX = 0f; var smoothY = 0f
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val gx = e.values[0]; val gy = e.values[1]
                // 横屏时传感器坐标轴旋转 90°：手机左右倾斜对应 gy，前后俯仰对应 gx
                val rawX = if (Screen.landscape) gy else -gx
                val rawY = if (Screen.landscape) gx else gy
                if (baseX.isNaN()) { baseX = rawX; baseY = rawY }
                // 缓慢漂移基准角，改变握持姿势后几秒自动回中
                baseX += (rawX - baseX) * 0.012f
                baseY += (rawY - baseY) * 0.012f
                val tx = ((rawX - baseX) / 3.2f).coerceIn(-1f, 1f)
                val ty = ((rawY - baseY) / 3.2f).coerceIn(-1f, 1f)
                smoothX += (tx - smoothX) * 0.18f
                smoothY += (ty - smoothY) * 0.18f
                if (kotlin.math.abs(TiltBus.x.value - smoothX) > 0.004f) TiltBus.x.value = smoothX
                if (kotlin.math.abs(TiltBus.y.value - smoothY) > 0.004f) TiltBus.y.value = smoothY
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, accuracy: Int) {}
        }
        TiltBus.onObserve = { active ->
            if (active) {
                baseX = Float.NaN; baseY = Float.NaN; smoothX = 0f; smoothY = 0f
                runCatching { sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
            } else {
                runCatching { sm.unregisterListener(listener) }
                TiltBus.x.value = 0f; TiltBus.y.value = 0f
            }
        }
    }

    override val name = "android"
    override val filesDir: File = ctx.filesDir
    override val cacheDir: File = ctx.cacheDir
    /** 公共「音乐/NEKOPLAY」：Android 10 以下没写权限、或者写不进去时，退回 App 自己的音乐目录 */
    override fun downloadDir(): File {
        val pub = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "NEKOPLAY")
        if (android.os.Build.VERSION.SDK_INT >= 30 && (pub.isDirectory || pub.mkdirs()) && runCatching { File(pub, ".probe").apply { writeText("") }.delete() }.getOrDefault(false)) return pub
        return File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: ctx.filesDir, "NEKOPLAY")
    }
    override fun dirPresets(): List<Pair<String, String>> {
        val ext = android.os.Environment.getExternalStorageDirectory().absolutePath
        return listOf("音乐/NEKOPLAY" to "$ext/Music/NEKOPLAY", "下载/NEKOPLAY" to "$ext/Download/NEKOPLAY", "音乐" to "$ext/Music")
    }
    override fun metered(): Boolean = runCatching { ctx.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(false)
    override fun scanLocal(): List<LocalSong> {
        val out = ArrayList<LocalSong>()
        @Suppress("DEPRECATION") val proj = arrayOf(android.provider.MediaStore.Audio.Media.DATA, android.provider.MediaStore.Audio.Media.TITLE, android.provider.MediaStore.Audio.Media.ARTIST, android.provider.MediaStore.Audio.Media.ALBUM, android.provider.MediaStore.Audio.Media.DURATION)
        ctx.contentResolver.query(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "${android.provider.MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${android.provider.MediaStore.Audio.Media.DURATION} >= 30000", null, null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                out += LocalSong(path, c.getString(1) ?: File(path).nameWithoutExtension, c.getString(2)?.takeIf { it != "<unknown>" } ?: "未知歌手", c.getString(3) ?: "", c.getLong(4))
            }
        }
        return out
    }
    @Composable override fun audioPermission(): Pair<Boolean, () -> Unit> {
        val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        val c = LocalContext.current
        var ok by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(c, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok = it }
        return ok to { l.launch(perm) }
    }
    override fun systemSeed(): Int? = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getColor(android.R.color.system_accent1_500) else null
    override fun mediaScan(f: File) { android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null) }
    private val kvs = HashMap<String, KV>()
    override fun prefs(name: String): KV = kvs.getOrPut(name) { SpKV(ctx.getSharedPreferences(name, Context.MODE_PRIVATE)) }
    override val inter = FontFamily(
        Font(R.font.inter_400, FontWeight.W400), Font(R.font.inter_500, FontWeight.W500), Font(R.font.inter_600, FontWeight.W600),
        Font(R.font.inter_700, FontWeight.W700), Font(R.font.inter_800, FontWeight.W800), Font(R.font.inter_900, FontWeight.W900),
    )
    override val mono = FontFamily(Font(R.font.mono_400, FontWeight.W400), Font(R.font.mono_500, FontWeight.W500), Font(R.font.mono_600, FontWeight.W600), Font(R.font.mono_700, FontWeight.W700))
    override val liveBlur = Build.VERSION.SDK_INT >= 31
    override val audio: Audio get() = exoAudio

    override fun decodeImage(path: String, maxSide: Int): ImageBitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > maxSide) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
    }
    override fun pixels(img: ImageBitmap): IntArray { val b = img.asAndroidBitmap(); val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); return px }
    override fun fromPixels(px: IntArray, w: Int, h: Int): ImageBitmap = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    override fun scaled(img: ImageBitmap, w: Int, h: Int): ImageBitmap = android.graphics.Bitmap.createScaledBitmap(img.asAndroidBitmap(), w, h, true).asImageBitmap()
    override fun qrText(png: ByteArray): String? = runCatching {
        val b = BitmapFactory.decodeByteArray(png, 0, png.size)
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(b.width, b.height, px))),
            mapOf(com.google.zxing.DecodeHintType.PURE_BARCODE to true)).text
    }.getOrNull()

    override fun savePicture(img: ImageBitmap, name: String): String {
        val v = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NEKOPLAY")
        }
        if (Build.VERSION.SDK_INT < 29) {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "NEKOPLAY").apply { mkdirs() }
            val f = File(dir, "$name.png"); f.outputStream().use { img.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.path), null, null); return f.path
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw Exception("无法写入相册")
        ctx.contentResolver.openOutputStream(uri)!!.use { img.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return "相册 / Pictures/NEKOPLAY"
    }
    override fun saveJpeg(img: ImageBitmap, file: File) { file.outputStream().use { img.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) } }

    @Composable override fun imagePicker(onPick: (ByteArray) -> Unit): () -> Unit {
        val c = LocalContext.current
        val l = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) c.contentResolver.openInputStream(uri)?.use { onPick(it.readBytes()) }
        }
        return { l.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }

    @Composable override fun filePicker(onPick: (ByteArray) -> Unit): (() -> Unit)? {
        val c = LocalContext.current
        val l = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) c.contentResolver.openInputStream(uri)?.use { onPick(it.readBytes()) } }
        return { l.launch(arrayOf("*/*")) }
    }
    @Composable override fun fileSaver(onDone: (String?) -> Unit): ((String, ByteArray) -> Unit)? {
        val c = LocalContext.current
        val data = remember { arrayOfNulls<ByteArray>(1) }
        val l = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri == null) onDone(null) else { c.contentResolver.openOutputStream(uri)?.use { it.write(data[0]) }; onDone(uri.lastPathSegment ?: "已保存") }
        }
        return { name, bytes -> data[0] = bytes; l.launch(name) }
    }

    @Composable override fun AnimatedImage(url: String, modifier: Modifier) =
        coil3.compose.AsyncImage(if (UiPref.lowMem) img(url, 256) else url, null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)

    override val hasWebView get() = true
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    @Composable override fun WebPage(url: String, modifier: Modifier, desktopUa: Boolean, onInfo: (String) -> Unit, stopOn: String?, intercept: (String) -> Boolean) {
        val cb by rememberUpdatedState(intercept); val info by rememberUpdatedState(onInfo)
        val loaded = remember { arrayOfNulls<String>(1) }
        androidx.compose.ui.viewinterop.AndroidView(factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.databaseEnabled = true
                settings.loadWithOverviewMode = true; settings.useWideViewPort = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                if (desktopUa) settings.userAgentString = DESKTOP_UA
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onProgressChanged(v: android.webkit.WebView, p: Int) { info(if (p < 100) "加载中 $p% · ${android.net.Uri.parse(v.url ?: "").host ?: ""}" else "") }
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: android.webkit.WebView, r: android.webkit.WebResourceRequest): Boolean = r.isForMainFrame && cb(r.url.toString())
                    override fun onReceivedError(v: android.webkit.WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceError) {
                        if (r.isForMainFrame) info("加载失败：${e.description}（${e.errorCode}）")
                    }
                    override fun onReceivedHttpError(v: android.webkit.WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceResponse) {
                        if (r.isForMainFrame) info("加载失败：HTTP ${e.statusCode}")
                    }
                }
            }
        }, modifier = modifier, update = { w -> if (loaded[0] != url) { loaded[0] = url; w.loadUrl(url) } }, onRelease = { it.destroy() })
    }

    @Composable override fun BackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

    @Composable override fun VideoPlayer(url: String?, modifier: Modifier, onState: (Boolean, Long, Long, String?) -> Unit, control: VideoControl) {
        val c = LocalContext.current
        val player = remember {
            ExoPlayer.Builder(c).setMediaSourceFactory(DefaultMediaSourceFactory(
                DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0").setDefaultRequestProperties(mapOf("Referer" to "https://music.163.com/")))).build()
        }
        var err by remember { mutableStateOf<String?>(null) }
        DisposableEffect(Unit) {
            val l = object : Player.Listener { override fun onPlayerError(e: PlaybackException) { err = "播放失败：${e.errorCodeName}" } }
            player.addListener(l)
            control.toggle = { if (player.isPlaying) player.pause() else player.play() }
            control.seek = { player.seekTo(it) }
            onDispose { player.removeListener(l); player.release() }
        }
        LaunchedEffect(url) { if (url != null) { player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play() } }
        LaunchedEffect(Unit) { while (true) { onState(player.isPlaying, player.currentPosition, player.duration.coerceAtLeast(0), err); delay(250) } }
        androidx.compose.ui.viewinterop.AndroidView({ android.view.SurfaceView(it).also { v -> player.setVideoSurfaceView(v) } }, modifier)
    }

    /** 实占内存（PSS，和系统设置里一致）+ 分项 */
    override fun memInfo(): String = try {
        val m = HashMap<String, Long>()
        File("/proc/self/smaps_rollup").readLines().forEach { l -> val p = l.split(Regex("\\s+")); if (p.size >= 2) p[1].toLongOrNull()?.let { m[p[0]] = it } }
        val rt = Runtime.getRuntime()
        "实占(PSS) ${m["Pss:"]!! / 1024}M · 匿名 ${(m["Pss_Anon:"] ?: 0) / 1024}M · 文件 ${(m["Pss_File:"] ?: 0) / 1024}M\nJava 堆 ${(rt.totalMemory() - rt.freeMemory()) / 1048576}M · 原生堆 ${android.os.Debug.getNativeHeapAllocatedSize() / 1048576}M"
    } catch (e: Exception) { "读取失败" }
    
    /** 逐段读取 /proc/self/smaps，按用途汇总 PSS */
    override fun memAnalysis(): String {
        val sum = HashMap<String, Long>(); var cat = "其他"
        val head = Regex("^[0-9a-f]+-[0-9a-f]+ ")
        File("/proc/self/smaps").forEachLine { l ->
            if (head.containsMatchIn(l)) {
                val parts = l.split(Regex("\\s+")); val n = if (parts.size >= 6) parts.subList(5, parts.size).joinToString(" ").lowercase() else ""
                cat = when {
                    n.contains("dalvik") || n.contains(".art") || n.contains("jit") -> "Java/ART"
                    n.contains("kgsl") || n.contains("mali") || n.contains("dmabuf") || n.contains("gpu") || n.startsWith("/dev/") -> "显存/驱动"
                    n.contains("scudo") || n.contains("malloc") || n.contains("jemalloc") -> "原生堆(绘制/解码)"
                    n.contains("stack") -> "线程栈"
                    n.startsWith("/") -> "文件映射"
                    n.isEmpty() -> "匿名(未命名)"
                    else -> "其他"
                }
            } else if (l.startsWith("Pss:")) sum[cat] = (sum[cat] ?: 0) + (l.split(Regex("\\s+"))[1].toLongOrNull() ?: 0)
        }
        return sum.entries.sortedByDescending { it.value }.take(7).joinToString("\n") { "${it.key} ${it.value / 1024}M" }
    }
}

/** SharedPreferences 套一层 KV */
private class SpKV(val sp: android.content.SharedPreferences) : KV {
    // 读的时候容忍类型不对（从电脑版备份恢复的值可能是字符串），转不过来就用默认值
    private fun raw(k: String): Any? = sp.all[k]
    override fun getString(k: String, d: String?) = raw(k)?.let { if (it is Set<*>) it.joinToString("\u0001") else it.toString() } ?: d
    override fun getInt(k: String, d: Int) = when (val v = raw(k)) { is Int -> v; is Number -> v.toInt(); is String -> v.toIntOrNull() ?: v.toFloatOrNull()?.toInt() ?: d; else -> d }
    override fun getLong(k: String, d: Long) = when (val v = raw(k)) { is Long -> v; is Number -> v.toLong(); is String -> v.toLongOrNull() ?: d; else -> d }
    override fun getFloat(k: String, d: Float) = when (val v = raw(k)) { is Float -> v; is Number -> v.toFloat(); is String -> v.toFloatOrNull() ?: d; else -> d }
    override fun getBoolean(k: String, d: Boolean) = when (val v = raw(k)) { is Boolean -> v; is String -> v.toBooleanStrictOrNull() ?: d; else -> d }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(k: String, d: Set<String>?): Set<String>? = when (val v = raw(k)) { is Set<*> -> v as Set<String>; is String -> if (v.isEmpty()) emptySet() else v.split('\u0001').toSet(); else -> d }
    override fun all(): Map<String, Any?> = sp.all
    override fun edit(): KV.Ed = E(sp.edit())
    private class E(val e: android.content.SharedPreferences.Editor) : KV.Ed {
        override fun putString(k: String, v: String?) = apply { e.putString(k, v) }
        override fun putInt(k: String, v: Int) = apply { e.putInt(k, v) }
        override fun putLong(k: String, v: Long) = apply { e.putLong(k, v) }
        override fun putFloat(k: String, v: Float) = apply { e.putFloat(k, v) }
        override fun putBoolean(k: String, v: Boolean) = apply { e.putBoolean(k, v) }
        override fun putStringSet(k: String, v: Set<String>?) = apply { e.putStringSet(k, v) }
        override fun remove(k: String) = apply { e.remove(k) }
        override fun clear() = apply { e.clear() }
        override fun apply() { e.apply() }
    }
}
