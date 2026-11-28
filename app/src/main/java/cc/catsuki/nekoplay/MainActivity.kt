package cc.catsuki.nekoplay

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_PLAYER = "cc.catsuki.nekoplay.OPEN_PLAYER"
        fun openPlayerPendingIntent(ctx: android.content.Context, reqCode: Int): android.app.PendingIntent {
            val intent = android.content.Intent(ctx, MainActivity::class.java).apply {
                action = ACTION_OPEN_PLAYER
                flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return android.app.PendingIntent.getActivity(
                ctx, reqCode, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(s: Bundle?) {
        if (s == null) Perf.markLaunch()
        super.onCreate(s)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        AndroidPlat.attachFrameMetrics(window, this)
        if (s == null) Perf.start()
        if (s == null) Store.refreshAll()
        setContent { NekoTheme { Root() } }
        handleIntent(intent)
    }

    override fun onNewIntent(i: android.content.Intent) { super.onNewIntent(i); setIntent(i); handleIntent(i) }
    /** 处理外部跳回（登录回调 / 点击通知栏直接进入播放页） */
    private fun handleIntent(i: android.content.Intent?) {
        if (i?.action == ACTION_OPEN_PLAYER) PlayerBus.open()
        i?.dataString?.takeIf { it.startsWith("nekoplay:") || "ptlogin2.qq.com/jump" in it }?.let { DeepLink.url.value = it }
    }

    override fun onStart() {
        super.onStart()
        // 连上播放服务：通知栏、锁屏和耳机按键靠它
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        Engine.savePosition()
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }
}

