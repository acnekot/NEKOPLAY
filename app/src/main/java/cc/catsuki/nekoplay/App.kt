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
        Plat.impl = AndroidPlat(this)
        Boot.init()
    }

    // 图片：内存缓存 24MB，磁盘缓存 200MB；音源插件声明的 CDN 请求头（MediaHeaders）在这里附加，避免 403
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = SourceApi.http.newBuilder().addInterceptor { ch ->
            // 图片 CDN 需要的 Referer / UA 由音源插件声明（MediaHeaders），宿主不写死任何平台
            val r = ch.request()
            val hs = MediaHeaders.forHost(r.url.host)
            ch.proceed(if (hs.isEmpty()) r else r.newBuilder().apply { hs.forEach { (k, v) -> header(k, v) } }.build())
        }.build()
        return ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes((if (UiPref.lowMem) 10L else 24L) * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("img").toOkioPath()).maxSizeBytes(200L * 1024 * 1024).build() }
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                // 动态头像：Android 9+ 用系统解码器，老系统用 GIF 解码器
                if (android.os.Build.VERSION.SDK_INT >= 28) add(coil3.gif.AnimatedImageDecoder.Factory()) else add(coil3.gif.GifDecoder.Factory())
            }
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) SingletonImageLoader.get(this).memoryCache?.clear()
    }
}

/** 通知栏 / 锁屏 / 耳机按键的播放控制 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, AndroidPlat.exoAudio.player)
            .setSessionActivity(MainActivity.openPlayerPendingIntent(this, 100))
            .build()
    }
    override fun onGetSession(info: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        Engine.savePosition()
        if (!Engine.isPlaying) stopSelf()
    }
    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}
