package cc.catsuki.nekoplay

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.net.Uri
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Mode { LOOP_ALL, LOOP_ONE, SHUFFLE }

data class PlayState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val mode: Mode = Mode.LOOP_ALL,
    val format: String = "",
    val kbps: Int = 0,
    val cached: Boolean = false,
    val fm: Boolean = false, // 私人 FM：播到最后自动再要几首
) { val current: Track? get() = queue.getOrNull(index) }

/** 播放引擎：队列自己管，ExoPlayer 一次只放一首（网易云地址会过期，播到哪首才去取地址）。 */
object Engine {
    lateinit var exo: ExoPlayer
    lateinit var player: Player // 给通知栏 / 锁屏用，上一首下一首走自己的队列
    val state = MutableStateFlow(PlayState())
    val position = MutableStateFlow(0L)
    val error = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    var quality by androidx.compose.runtime.mutableIntStateOf(3) // 默认无损，见 Ncm.LEVELS（界面会跟着刷新）
    lateinit var cache: SimpleCache
    const val CACHE_MAX = 1024L * 1024 * 1024
    /** 歌词页可见时进度刷新加快到 50ms（高亮扫过更平滑），平时 250ms */
    @Volatile var fastPos = 0


    fun init(c: Context) {
        prefs = c.getSharedPreferences("ui", 0)
        quality = prefs!!.getInt("q2", 3)
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 25_000, 1_500, 4_000)
            .setTargetBufferBytes(4 * 1024 * 1024).build()
        // 播放缓存：听过的歌存在本地（最多 1GB，满了先删最久没听的），再听不用重新下载
        cache = SimpleCache(java.io.File(c.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(CACHE_MAX), StandaloneDatabaseProvider(c))
        val upstream = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0").setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
        val ds = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        exo = ExoPlayer.Builder(c).setLoadControl(lc).setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setLooper(Looper.getMainLooper()).build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { state.value = state.value.copy(playing = p) }
            override fun onPlaybackStateChanged(s: Int) {
                state.value = state.value.copy(loading = s == Player.STATE_BUFFERING)
                if (s == Player.STATE_ENDED) onEnded()
            }
            override fun onPlayerError(e: PlaybackException) { error.value = "播放失败：${e.errorCodeName}"; next() }
        })
        player = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()
            override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
            override fun seekToNext() = next()
            override fun seekToNextMediaItem() = next()
            override fun seekToPrevious() = prev()
            override fun seekToPreviousMediaItem() = prev()
            override fun hasNextMediaItem() = true
            override fun hasPreviousMediaItem() = true
        }
        // 进度：只在播放时每 250ms 更新一次
        scope.launch { while (isActive) { if (exo.isPlaying || position.value != exo.currentPosition) position.value = exo.currentPosition; delay(if (fastPos > 0) 50 else 250) } }
        Store.restoreQueue()?.let { (q, i, pos) -> state.value = state.value.copy(queue = q, index = i); load(i, false, pos) }
    }

    fun playList(list: List<Track>, start: Int, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        val q = if (shuffle) list.shuffled() else list
        state.value = state.value.copy(fm = false, queue = q, index = if (shuffle) 0 else start, mode = if (shuffle) Mode.SHUFFLE else state.value.mode.let { if (it == Mode.SHUFFLE) Mode.LOOP_ALL else it })
        load(state.value.index, true)
    }

    fun playAt(i: Int) { if (i in state.value.queue.indices) load(i, true) }

    /** 上一首听了多久：切歌时回传给网易云（听满 30 秒或播完才算一次） */
    private var reportId: String? = null
    private fun reportPrev() {
        val id = reportId ?: return
        reportId = null
        val sec = exo.currentPosition / 1000
        if (sec >= 30 && Ncm.loggedIn) scope.launch { Ncm.scrobble(id, sec) }
    }

    private fun load(i: Int, autoplay: Boolean, startMs: Long = 0) {
        val t = state.value.queue.getOrNull(i) ?: return
        reportPrev()
        reportId = t.id
        if (autoplay && Ncm.loggedIn) scope.launch { Ncm.scrobbleStart(t.id) }
        state.value = state.value.copy(index = i, loading = true, format = "", kbps = 0)
        position.value = startMs
        Store.addRecent(t)
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val meta = MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
                    .setArtworkUri(t.cover?.let { Uri.parse("$it?param=512y512") }).build()
                // 已经完整缓存过（同档位或更高档位）：直接读本地，不联网
                val hit = cachedLevel(t.id)
                if (hit != null) {
                    exo.setMediaItem(MediaItem.Builder().setUri("cache://${t.id}").setCustomCacheKey(cacheKey(t.id, hit.first)).setMediaId(t.id).setMediaMetadata(meta).build(), startMs)
                    state.value = state.value.copy(format = hit.second, kbps = 0, cached = true)
                } else {
                    val s = Ncm.stream(t.id, quality)
                    exo.setMediaItem(MediaItem.Builder().setUri(s.url).setCustomCacheKey(cacheKey(t.id, s.level)).setMediaId(t.id).setMediaMetadata(meta).build(), startMs)
                    prefs?.edit()?.putString("fmt_${cacheKey(t.id, s.level)}", s.format)?.apply()
                    state.value = state.value.copy(format = s.format, kbps = s.kbps, cached = false)
                }
                exo.prepare()
                exo.playWhenReady = autoplay
                prefetchNext()
                Store.saveQueue(state.value.queue, i, startMs)
            } catch (e: Exception) {
                state.value = state.value.copy(loading = false)
                error.value = "无法播放「${t.title}」：${e.message}"
            }
        }
    }

    fun toggle() { if (exo.playbackState == Player.STATE_IDLE) load(state.value.index, true) else if (exo.isPlaying) exo.pause() else exo.play() }
    fun seek(ms: Long) { exo.seekTo(ms); position.value = ms }

    // ---------- 私人 FM ----------
    private var fmJob: Job? = null
    fun startFm(onError: (String) -> Unit = {}) {
        scope.launch {
            try {
                val l = Ncm.fm()
                if (l.isEmpty()) { onError("私人 FM 暂时没有歌曲"); return@launch }
                state.value = state.value.copy(queue = l, index = 0, fm = true, mode = Mode.LOOP_ALL)
                load(0, true)
            } catch (e: Exception) { onError("私人 FM 加载失败：${e.message}") }
        }
    }
    /** FM 队列快到底时补货 */
    private fun fmFill(then: (() -> Unit)? = null) {
        if (fmJob?.isActive == true) { if (then != null) scope.launch { fmJob?.join(); then() }; return }
        fmJob = scope.launch {
            runCatching { Ncm.fm() }.onSuccess { more ->
                val s = state.value
                if (s.fm) state.value = s.copy(queue = s.queue + more.filter { m -> s.queue.none { it.id == m.id } })
            }
            then?.invoke()
        }
    }
    /** FM 里「不喜欢」：告诉网易云并跳到下一首 */
    fun fmTrash() {
        val t = state.value.current ?: return
        scope.launch { runCatching { Ncm.fmTrash(t.id) } }
        next()
    }

    fun next() {
        val s = state.value; if (s.queue.isEmpty()) return
        if (s.fm) {
            if (s.index + 1 < s.queue.size) { load(s.index + 1, true); if (s.index + 3 >= s.queue.size) fmFill() }
            else fmFill { val n = state.value; if (n.index + 1 < n.queue.size) load(n.index + 1, true) }
            return
        }
        load(if (s.mode == Mode.SHUFFLE) (s.queue.indices).random() else (s.index + 1) % s.queue.size, true)
    }

    fun prev() {
        val s = state.value; if (s.queue.isEmpty()) return
        if (exo.currentPosition > 3000) { seek(0); return }
        load((s.index - 1 + s.queue.size) % s.queue.size, true)
    }

    private fun onEnded() { if (state.value.mode == Mode.LOOP_ONE) { seek(0); exo.play() } else next() }

    fun cycleMode() { state.value = state.value.copy(mode = Mode.entries[(state.value.mode.ordinal + 1) % 3]) }

    fun removeAt(i: Int) {
        val s = state.value
        if (i == s.index || i !in s.queue.indices) return
        val q = s.queue.toMutableList().apply { removeAt(i) }
        state.value = s.copy(queue = q, index = if (i < s.index) s.index - 1 else s.index)
    }

    fun enqueueNext(t: Track) {
        val s = state.value
        if (s.queue.isEmpty()) { playList(listOf(t), 0); return }
        val q = s.queue.filterIndexed { i, x -> x.id != t.id || i == s.index }.toMutableList()
        val cur = q.indexOfFirst { it.id == s.current?.id }.coerceAtLeast(0)
        q.add(cur + 1, t); state.value = s.copy(queue = q, index = cur)
    }

    fun enqueue(t: Track) {
        val s = state.value
        if (s.queue.isEmpty()) { playList(listOf(t), 0); return }
        state.value = s.copy(queue = s.queue + t)
    }

    private var prefs: android.content.SharedPreferences? = null
    private fun cacheKey(id: String, level: String) = "${id}_$level"

    /** 找已经完整缓存好的档位：从选中档位往下找（也接受更高档位） → (档位, 格式) */
    private fun cachedLevel(id: String): Pair<String, String>? {
        val order = Ncm.LEVELS.reversed().sortedBy { kotlin.math.abs(Ncm.LEVELS.indexOf(it) - quality) }
        for (lv in order) {
            if (Ncm.LEVELS.indexOf(lv) > quality + 1) continue
            val key = cacheKey(id, lv)
            val len = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (len > 0 && cache.isCached(key, 0, len)) return lv to (prefs?.getString("fmt_$key", "") ?: "")
        }
        return null
    }

    /** 提前拿好下一首的播放地址，切歌更快 */
    private fun prefetchNext() {
        val s = state.value
        if (s.mode == Mode.SHUFFLE || s.queue.size < 2) return
        val n = s.queue[(s.index + 1) % s.queue.size]
        scope.launch { runCatching { if (cachedLevel(n.id) == null) Ncm.stream(n.id, quality) } }
    }

    fun cacheBytes(): Long = runCatching { cache.cacheSpace }.getOrDefault(0)
    fun clearCache() { runCatching { cache.keys.toList().forEach { cache.removeResource(it) } } }

    /** 换音质：当前这首按新档位重新加载，从原位置继续 */
    fun setQuality(q: Int, ctx: Context) {
        quality = q
        ctx.getSharedPreferences("ui", 0).edit().putInt("q2", q).apply()
        val s = state.value
        if (s.current != null) load(s.index, exo.isPlaying || exo.playWhenReady, exo.currentPosition)
    }

    fun clearUpcoming() { val s = state.value; s.current?.let { state.value = s.copy(queue = listOf(it), index = 0) } }

    fun savePosition() { val s = state.value; if (s.index >= 0) Store.saveQueue(s.queue, s.index, exo.currentPosition) }
}
