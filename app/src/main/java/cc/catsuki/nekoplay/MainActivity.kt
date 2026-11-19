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
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        AndroidPlat.attachFrameMetrics(window)
        if (s == null) Perf.start()
        if (s == null) Store.refreshAll()
        setContent { NekoTheme { Root() } }
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

