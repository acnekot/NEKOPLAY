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

    init { exoAudio = ExoAudio(ctx) }

    override val name = "android"
    override val filesDir: File = ctx.filesDir
    override val cacheDir: File = ctx.cacheDir
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
    override fun saveJpeg(img: ImageBitmap, file: File) { file.outputStream().use { img.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) } }

    @Composable override fun imagePicker(onPick: (ByteArray) -> Unit): () -> Unit {
        val c = LocalContext.current
        val l = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) c.contentResolver.openInputStream(uri)?.use { onPick(it.readBytes()) }
        }
        return { l.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
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
    override fun getString(k: String, d: String?) = sp.getString(k, d)
    override fun getInt(k: String, d: Int) = sp.getInt(k, d)
    override fun getLong(k: String, d: Long) = sp.getLong(k, d)
    override fun getFloat(k: String, d: Float) = sp.getFloat(k, d)
    override fun getBoolean(k: String, d: Boolean) = sp.getBoolean(k, d)
    override fun getStringSet(k: String, d: Set<String>?): Set<String>? = sp.getStringSet(k, d)
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
class ExoAudio(c: Context) : Audio {
    override var listener: Audio.Listener? = null
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
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { listener?.onPlaying(p) }
            override fun onPlaybackStateChanged(s: Int) {
                listener?.onBuffering(s == Player.STATE_BUFFERING)
                if (s == Player.STATE_ENDED) listener?.onEnded()
            }
            override fun onPlayerError(e: PlaybackException) { listener?.onError(e.errorCodeName) }
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
        exo.setMediaItem(MediaItem.Builder().setUri(url ?: "cache://${meta.id}").setCustomCacheKey(cacheKey).setMediaId(meta.id).setMediaMetadata(m).build(), startMs)
        exo.prepare(); exo.playWhenReady = autoplay
    }
    override fun play() = exo.play()
    override fun pause() = exo.pause()
    override fun seek(ms: Long) = exo.seekTo(ms)
    override val isPlaying get() = exo.isPlaying
    override val idle get() = exo.playbackState == Player.STATE_IDLE
    override val position get() = exo.currentPosition
    override fun isCached(key: String): Boolean {
        val len = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return len > 0 && cache.isCached(key, 0, len)
    }
    override fun cacheBytes() = cache.cacheSpace
    override fun clearCache() { cache.keys.toList().forEach { cache.removeResource(it) } }
    override var volume: Float get() = exo.volume; set(v) { exo.volume = v }
}
