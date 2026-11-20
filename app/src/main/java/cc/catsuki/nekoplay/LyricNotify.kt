package cc.catsuki.nekoplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.*

/**
 * 手机歌词：
 * 1. 播放通知 / 锁屏的第二行换成当前这句歌词（改 MediaItem 的元数据，不会重新缓冲）；
 * 2. 同时发一条「魅族状态栏歌词」格式的通知（ticker + 两个专用标志），状态栏歌词类模块可以直接读到。
 */
object LyricNotify {
    private const val CH = "lyric"
    private const val ID = 7301
    private const val FLAG_ALWAYS_SHOW_TICKER = 0x1000000
    private const val FLAG_ONLY_UPDATE_TICKER = 0x2000000
    private var last = ""
    private var lastTrack: String? = null

    fun start(ctx: Context, audio: ExoAudio) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "状态栏歌词", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false); setSound(null, null) })
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            while (isActive) {
                delay(300)
                val st = Engine.state.value; val t = st.current
                if (!DeskLyric.notify || t == null || !st.playing) {
                    if (last.isNotEmpty()) { nm.cancel(ID); audio.setLyric(null); last = "" }
                    continue
                }
                val lines = LyricCache.peek(t.id) ?: runCatching { LyricCache.get(t.id) }.getOrNull() ?: continue
                val line = DeskLyric.lineAt(lines, Engine.position.value + LyricOffset.of(t.id))?.first ?: ""
                if (line == last && t.id == lastTrack) continue
                last = line; lastTrack = t.id
                audio.setLyric(line.ifEmpty { null })
                if (line.isEmpty()) { nm.cancel(ID); continue }
                val b = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH) else @Suppress("DEPRECATION") Notification.Builder(ctx))
                    .setSmallIcon(R.mipmap.ic_launcher_mono).setTicker(line).setContentTitle(line).setContentText("${t.title} - ${t.artist}")
                    .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                val n = b.build()
                n.flags = n.flags or FLAG_ALWAYS_SHOW_TICKER or FLAG_ONLY_UPDATE_TICKER
                runCatching { nm.notify(ID, n) }
            }
        }
    }
}
