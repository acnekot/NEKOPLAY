package cc.catsuki.nekoplay


import cc.catsuki.nekoplay.core.*
import cc.catsuki.nekoplay.data.*
import cc.catsuki.nekoplay.player.*
import cc.catsuki.nekoplay.ui.*
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 桌面小部件：封面 + 歌名歌手 + 上一首 / 播放暂停 / 下一首 */
class NekoWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { WidgetSync.push(ctx) }

    override fun onReceive(ctx: Context, i: Intent) {
        super.onReceive(ctx, i)
        when (i.action) {
            A_PREV -> Engine.prev()
            A_NEXT -> Engine.next()
            A_TOGGLE -> {
                // App 没开着时先连上播放服务，播放才能在后台继续
                runCatching { MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync() }
                Engine.toggle()
            }
        }
    }

    companion object {
        const val A_PREV = "cc.catsuki.nekoplay.W_PREV"
        const val A_NEXT = "cc.catsuki.nekoplay.W_NEXT"
        const val A_TOGGLE = "cc.catsuki.nekoplay.W_TOGGLE"
    }
}

object WidgetSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cover: Pair<String, Bitmap>? = null

    /** 播放状态一变就刷新小部件（没放小部件时什么都不做） */
    fun start(ctx: Context) {
        scope.launch {
            Engine.state.map { Triple(it.current?.id, it.playing, it.current?.cover) }.distinctUntilChanged().collect { push(ctx) }
        }
    }

    fun push(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, NekoWidget::class.java))
        if (ids.isEmpty()) return
        val st = Engine.state.value; val t = st.current
        fun pi(action: String, code: Int) = PendingIntent.getBroadcast(ctx, code, Intent(ctx, NekoWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val v = RemoteViews(ctx.packageName, R.layout.widget).apply {
            setTextViewText(R.id.w_title, t?.title ?: "NEKOPLAY")
            setTextViewText(R.id.w_artist, t?.artist ?: "点一下开始播放")
            setImageViewResource(R.id.w_play, if (st.playing) R.drawable.w_pause else R.drawable.w_play)
            setOnClickPendingIntent(R.id.w_prev, pi(NekoWidget.A_PREV, 1))
            setOnClickPendingIntent(R.id.w_play, pi(NekoWidget.A_TOGGLE, 2))
            setOnClickPendingIntent(R.id.w_next, pi(NekoWidget.A_NEXT, 3))
            setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 4, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            cover?.takeIf { it.first == t?.cover }?.let { setImageViewBitmap(R.id.w_cover, it.second) } ?: setImageViewResource(R.id.w_cover, R.mipmap.ic_launcher)
        }
        mgr.updateAppWidget(ids, v)
        // 封面换了：后台加载一张小图，好了再刷新一次
        val url = t?.cover ?: return
        if (cover?.first == url) return
        scope.launch {
            val r = SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data("$url?param=192y192").size(192).allowHardware(false).build())
            val bm = (r as? SuccessResult)?.image?.toBitmap() ?: return@launch
            cover = url to bm
            push(ctx)
        }
    }
}
