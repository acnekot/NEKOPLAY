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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay
import java.io.File

/** 安卓平台实现 */
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

    init { exoAudio = ExoAudio(ctx); LyricNotify.start(ctx, exoAudio); WidgetSync.start(ctx) }

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

    @Composable override fun AnimatedImage(url: String, modifier: Modifier) =
        coil3.compose.AsyncImage(if (UiPref.lowMem) img(url, 256) else url, null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)

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

/** ExoPlayer + 1GB 播放缓存；player 给通知栏 / 锁屏用（上一首下一首走 Engine 的队列） */
class ExoAudio(private val c: Context) : Audio {
    override var listener: Audio.Listener? = null
    // 固定一个音频会话，效果器都挂在这个会话上
    private val sessionId = (c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).generateAudioSessionId()
    private var dyn: android.media.audiofx.DynamicsProcessing? = null
    private var eqOld: android.media.audiofx.Equalizer? = null
    private var bassFx: android.media.audiofx.BassBoost? = null
    private var virtFx: android.media.audiofx.Virtualizer? = null
    private var loudFx: android.media.audiofx.LoudnessEnhancer? = null
    val exo: ExoPlayer
    val player: Player
    private val cache = SimpleCache(File(c.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(1024L * 1024 * 1024), StandaloneDatabaseProvider(c))
    init {
        val lc = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 25_000, 1_500, 4_000).setTargetBufferBytes(4 * 1024 * 1024).build()
        val upstream = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0").setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
        val ds = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        exo = ExoPlayer.Builder(c).setLoadControl(lc).setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setLooper(Looper.getMainLooper()).build()
        exo.setAudioSessionId(sessionId)
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { listener?.onPlaying(p) }
            override fun onPlaybackStateChanged(s: Int) {
                listener?.onBuffering(s == Player.STATE_BUFFERING)
                if (s == Player.STATE_ENDED) listener?.onEnded()
            }
            override fun onPlayerError(e: PlaybackException) { listener?.onError(e.errorCodeName) }
            // 无缝播放：自动接到排好的下一首 → 把上一首从播放器里拿掉，告诉 Engine
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && exo.currentMediaItemIndex > 0) {
                    exo.removeMediaItems(0, exo.currentMediaItemIndex); listener?.onAdvanced()
                }
            }
        })
        player = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()
            override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
            override fun seekToNext() = Engine.next()
            override fun seekToNextMediaItem() = Engine.next()
            override fun seekToPrevious() = Engine.prev()
            override fun seekToPreviousMediaItem() = Engine.prev()
            override fun hasNextMediaItem() = true
            override fun hasPreviousMediaItem() = true
        }
    }
    override fun open(url: String?, cacheKey: String, startMs: Long, autoplay: Boolean, meta: Track) {
        val m = MediaMetadata.Builder().setTitle(meta.title).setArtist(meta.artist).setAlbumTitle(meta.album)
            .setArtworkUri(meta.cover?.let { android.net.Uri.parse("$it?param=512y512") }).build()
        val item = MediaItem.Builder().setUri(url ?: "cache://${meta.id}").setCustomCacheKey(cacheKey).setMediaId(meta.id).setMediaMetadata(m).build()
        // 下载好的本地文件：直接读文件，不再往播放缓存里写一份
        if (url?.startsWith("file:") == true || url?.startsWith("content:") == true) exo.setMediaSource(androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(c)).createMediaSource(item), startMs)
        else exo.setMediaItem(item, startMs)
        exo.prepare(); exo.playWhenReady = autoplay
    }
    private fun item(url: String?, cacheKey: String, meta: Track): MediaItem {
        val m = MediaMetadata.Builder().setTitle(meta.title).setArtist(meta.artist).setAlbumTitle(meta.album)
            .setArtworkUri(meta.cover?.let { android.net.Uri.parse("$it?param=512y512") }).build()
        return MediaItem.Builder().setUri(url ?: "cache://${meta.id}").setCustomCacheKey(cacheKey).setMediaId(meta.id).setMediaMetadata(m).build()
    }
    override fun queueNext(url: String?, cacheKey: String, meta: Track) {
        clearNext()
        val it = item(url, cacheKey, meta)
        if (url?.startsWith("file:") == true || url?.startsWith("content:") == true) exo.addMediaSource(androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(c)).createMediaSource(it))
        else exo.addMediaItem(it)
    }
    override fun clearNext() { val n = exo.mediaItemCount; val cur = exo.currentMediaItemIndex; if (n > cur + 1) exo.removeMediaItems(cur + 1, n) }
    override fun play() = exo.play()
    override fun pause() = exo.pause()
    override fun seek(ms: Long) = exo.seekTo(ms)
    override val isPlaying get() = exo.isPlaying
    override val idle get() = exo.playbackState == Player.STATE_IDLE
    override val position get() = exo.currentPosition
    override val buffered get() = exo.bufferedPosition
    override fun isCached(key: String): Boolean {
        val len = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return len > 0 && cache.isCached(key, 0, len)
    }
    override fun cacheBytes() = cache.cacheSpace
    override fun clearCache() { cache.keys.toList().forEach { cache.removeResource(it) } }
    override var volume: Float get() = exo.volume; set(v) { exo.volume = v }

    /** 通知栏歌词：把当前 MediaItem 的「歌手」那行换成歌词（null = 恢复原样）。同一个地址只改元数据，不会重新加载 */
    fun setLyric(line: String?) {
        val item = exo.currentMediaItem ?: return
        val t = Engine.state.value.current ?: return
        if (item.mediaId != t.id) return
        val m = item.mediaMetadata.buildUpon().setArtist(line ?: t.artist).setSubtitle(line ?: t.artist).build()
        runCatching { exo.replaceMediaItem(exo.currentMediaItemIndex, item.buildUpon().setMediaMetadata(m).build()) }
    }

    override fun info(): AudioInfo? {
        val f = exo.audioFormat ?: return null
        val codec = when (f.sampleMimeType) { "audio/flac" -> "flac"; "audio/mpeg" -> "mp3"; "audio/mp4a-latm" -> "aac"; "audio/opus" -> "opus"; "audio/vorbis" -> "ogg"; "audio/raw" -> "pcm"; else -> f.sampleMimeType?.substringAfter('/') ?: "?" }
        var bits = when (f.pcmEncoding) { C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24; C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32; C.ENCODING_PCM_8BIT -> 8; else -> 0 }
        // FLAC：位深写在 STREAMINFO 里（初始化数据：fLaC + 块头 4 字节 + STREAMINFO）
        if (bits == 0 && codec == "flac") runCatching {
            val b = f.initializationData.firstOrNull() ?: return@runCatching
            val o = if (b.size >= 4 && b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte()) 8 else 0
            bits = (((b[o + 12].toInt() and 1) shl 4) or ((b[o + 13].toInt() and 0xF0) ushr 4)) + 1
        }
        val kbps = listOf(f.bitrate, f.averageBitrate, f.peakBitrate).firstOrNull { it > 0 }?.div(1000) ?: 0
        return AudioInfo(codec, f.sampleRate.coerceAtLeast(0), bits, f.channelCount.coerceAtLeast(0), kbps)
    }

    /**
     * 效果器：Android 9+ 用 DynamicsProcessing 做 10 段均衡（频段可以任意定）；更老的系统退回 Equalizer（按最接近的频段）。
     * 每个效果单独 try，设备不支持就跳过。
     */
    override fun applyFx(fx: Fx) {
        val on = fx.on
        if (Build.VERSION.SDK_INT >= 28) runCatching {
            val d = dyn ?: android.media.audiofx.DynamicsProcessing(0, sessionId,
                android.media.audiofx.DynamicsProcessing.Config.Builder(android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    true, 10, false, 0, false, 0, false).build()).also { dyn = it }
            val eq = android.media.audiofx.DynamicsProcessing.Eq(true, true, 10)
            for (i in 0 until 10) {
                // 每段的上边界取到下一个中心频率的几何中点
                val upper = if (i < 9) kotlin.math.sqrt(Fx.FREQS[i].toFloat() * Fx.FREQS[i + 1]) else 20000f
                eq.getBand(i).apply { isEnabled = true; cutoffFrequency = upper; gain = fx.bands[i] }
            }
            d.setPreEqAllChannelsTo(eq)
            d.enabled = on
        } else runCatching {
            val e = eqOld ?: android.media.audiofx.Equalizer(0, sessionId).also { eqOld = it }
            val (lo, hi) = e.bandLevelRange.let { it[0] to it[1] }
            for (b in 0 until e.numberOfBands) {
                val center = e.getCenterFreq(b.toShort()) / 1000f
                val i = Fx.FREQS.indices.minBy { kotlin.math.abs(kotlin.math.ln(Fx.FREQS[it] / center)) }
                e.setBandLevel(b.toShort(), (fx.bands[i] * 100).toInt().coerceIn(lo.toInt(), hi.toInt()).toShort())
            }
            e.enabled = on
        }
        runCatching { (bassFx ?: android.media.audiofx.BassBoost(0, sessionId).also { bassFx = it }).apply { setStrength((fx.bass * 1000).toInt().toShort()); enabled = on && fx.bass > 0 } }
        runCatching { (virtFx ?: android.media.audiofx.Virtualizer(0, sessionId).also { virtFx = it }).apply { setStrength((fx.surround * 1000).toInt().toShort()); enabled = on && fx.surround > 0 } }
        runCatching { (loudFx ?: android.media.audiofx.LoudnessEnhancer(sessionId).also { loudFx = it }).apply { setTargetGain((fx.loud * 1000).toInt()); enabled = on && fx.loud > 0 } }
    }
}
