package cc.catsuki.nekoplay

import android.app.Application
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Ncm.init(this)
        Store.init(this)
        Engine.init(this)
        Bg.init(this)
        HomeLayout.init(this)
    }

    // 图片：内存缓存 24MB，磁盘缓存 200MB；网易云图片带浏览器请求头，避免 403
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = Ncm.http.newBuilder().addInterceptor { ch ->
            val r = ch.request()
            ch.proceed(if (r.url.host.endsWith("126.net")) r.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                .header("Referer", "https://music.163.com/").build() else r)
        }.build()
        return ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(24L * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("img").toOkioPath()).maxSizeBytes(200L * 1024 * 1024).build() }
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) SingletonImageLoader.get(this).memoryCache?.clear()
    }
}

/** 网易云图片按显示尺寸取小图 */
fun img(url: String?, px: Int): String? {
    if (url == null) return null
    val b = when { px <= 160 -> 160; px <= 256 -> 256; px <= 512 -> 512; else -> 768 }
    val base = url.substringBefore('?')
    return if (base.contains("/obj/")) "$base?imageView=1&thumbnail=${b}x$b&type=webp" else "$base?param=${b}y$b"
}

/** 通知栏 / 锁屏 / 耳机按键的播放控制 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, Engine.player).build()
    }
    override fun onGetSession(info: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        Engine.savePosition()
        if (!Engine.exo.isPlaying) stopSelf()
    }
    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}
