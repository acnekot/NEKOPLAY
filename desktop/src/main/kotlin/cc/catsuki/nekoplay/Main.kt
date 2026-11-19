package cc.catsuki.nekoplay

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath

fun main() {
    Plat.impl = DesktopPlat()
    Boot.init()
    Store.refreshAll()
    Perf.start()
    // 图片：网易云图片带浏览器请求头，避免 403
    SingletonImageLoader.setSafe { ctx ->
        val client = Ncm.http.newBuilder().addInterceptor { ch ->
            val r = ch.request()
            ch.proceed(if (r.url.host.endsWith("126.net")) r.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Referer", "https://music.163.com/").build() else r)
        }.build()
        ImageLoader.Builder(ctx)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(48L * 1024 * 1024).build() }
            .diskCache { DiskCache.Builder().directory(Plat.impl.cacheDir.resolve("img").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
    val w = (System.getenv("NEKOPLAY_W") ?: System.getProperty("nekoplay.width"))?.toIntOrNull() ?: 1280
    val h = (System.getenv("NEKOPLAY_H") ?: System.getProperty("nekoplay.height"))?.toIntOrNull() ?: 800
    application {
        val state = rememberWindowState(size = DpSize(w.dp, h.dp), position = if (System.getenv("NEKOPLAY_W") != null) androidx.compose.ui.window.WindowPosition(0.dp, 0.dp) else androidx.compose.ui.window.WindowPosition.PlatformDefault)
        val quit = { Engine.savePosition(); exitApplication() }
        // 无边框窗口：标题栏、最小化 / 最大化 / 关闭按钮和拖边改大小都自己画
        Window(onCloseRequest = quit, state = state, title = "NEKOPLAY", undecorated = true,
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) {
                    Key.Escape -> DesktopPlat.back()   // Esc = 返回
                    else -> false
                }
            }) {
            window.minimumSize = java.awt.Dimension(360, 560)
            NekoTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    Root(chrome = { TitleBar(state, quit) })
                    ResizeEdges(state)
                }
            }
        }
    }
}
