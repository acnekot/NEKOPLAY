package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
    private val audio get() = Plat.impl.audio
    val state = MutableStateFlow(PlayState())
    val position = MutableStateFlow(0L)
    val buffered = MutableStateFlow(0L)
    val error = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    var quality by androidx.compose.runtime.mutableIntStateOf(3) // 默认无损，见 Ncm.LEVELS（界面会跟着刷新）
    /** 歌词页可见时进度刷新加快到 50ms（高亮扫过更平滑），平时 250ms */
    @Volatile var fastPos = 0
    val isPlaying get() = audio.isPlaying
    fun pause() = audio.pause()

    fun init() {
        prefs = Plat.prefs("ui")
        quality = prefs!!.getInt("q2", 3)
        audio.listener = object : Audio.Listener {
            override fun onPlaying(p: Boolean) { state.value = state.value.copy(playing = p) }
            override fun onBuffering(b: Boolean) { state.value = state.value.copy(loading = b) }
            override fun onEnded() = this@Engine.onEnded()
            override fun onError(msg: String) { error.value = "播放失败：$msg"; next() }
        }
        // 进度：播放时定时更新
        scope.launch { while (isActive) { val p = audio.position; if (audio.isPlaying || position.value != p) position.value = p; runCatching { buffered.value = if (state.value.cached) Long.MAX_VALUE else audio.buffered }; delay(if (fastPos > 0) 50 else 250) } }
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
        val sec = audio.position / 1000
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
                // 已经完整缓存过（同档位或更高档位）：直接读本地，不联网
                val hit = cachedLevel(t.id)
                val dl = Downloads.file(t.id)
                if (dl != null) {
                    audio.open(dl.toURI().toString(), "dl_${t.id}", startMs, autoplay, t)
                    state.value = state.value.copy(format = dl.extension.lowercase(), kbps = 0, cached = true)
                } else if (hit != null) {
                    audio.open(null, cacheKey(t.id, hit.first), startMs, autoplay, t)
                    state.value = state.value.copy(format = hit.second, kbps = 0, cached = true)
                } else {
                    val s = Ncm.stream(t.id, quality)
                    audio.open(s.url, cacheKey(t.id, s.level), startMs, autoplay, t)
                    prefs?.edit()?.putString("fmt_${cacheKey(t.id, s.level)}", s.format)?.apply()
                    state.value = state.value.copy(format = s.format, kbps = s.kbps, cached = false)
                }
                prefetchNext()
                Store.saveQueue(state.value.queue, i, startMs)
            } catch (e: Exception) {
                state.value = state.value.copy(loading = false)
                error.value = "无法播放「${t.title}」：${e.message}"
            }
        }
    }

    fun toggle() { if (audio.idle) load(state.value.index, true) else if (audio.isPlaying) audio.pause() else audio.play() }
    fun seek(ms: Long) { audio.seek(ms); position.value = ms }

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
    /** FM 页：下一首不够了就先要一批 */
    fun fmEnsureNext() { val s = state.value; if (s.fm && s.index + 2 >= s.queue.size) fmFill() }

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
        if (audio.position > 3000) { seek(0); return }
        load((s.index - 1 + s.queue.size) % s.queue.size, true)
    }

    private fun onEnded() { if (state.value.mode == Mode.LOOP_ONE) { seek(0); audio.play() } else next() }

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

    private var prefs: KV? = null
    private fun cacheKey(id: String, level: String) = "${id}_$level"

    /** 找已经完整缓存好的档位：从选中档位往下找（也接受更高档位） → (档位, 格式) */
    private fun cachedLevel(id: String): Pair<String, String>? {
        val order = Ncm.LEVELS.reversed().sortedBy { kotlin.math.abs(Ncm.LEVELS.indexOf(it) - quality) }
        for (lv in order) {
            if (Ncm.LEVELS.indexOf(lv) > quality + 1) continue
            val key = cacheKey(id, lv)
            if (audio.isCached(key)) return lv to (prefs?.getString("fmt_$key", "") ?: "")
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

    fun cacheBytes(): Long = runCatching { audio.cacheBytes() }.getOrDefault(0)
    fun clearCache() { runCatching { audio.clearCache() } }

    /** 换音质：当前这首按新档位重新加载，从原位置继续 */
    fun changeQuality(q: Int) {
        quality = q
        prefs?.edit()?.putInt("q2", q)?.apply()
        val s = state.value
        if (s.current != null) load(s.index, audio.isPlaying, audio.position)
    }

    fun clearUpcoming() { val s = state.value; s.current?.let { state.value = s.copy(queue = listOf(it), index = 0) } }

    fun savePosition() { val s = state.value; if (s.index >= 0) Store.saveQueue(s.queue, s.index, audio.position) }
}
