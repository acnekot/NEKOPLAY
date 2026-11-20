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
    val want: Boolean = false, // 加载完要不要自动播放（加载中点暂停会改成 false）
) { val current: Track? get() = queue.getOrNull(index) }

/** 播放引擎：队列自己管，ExoPlayer 一次只放一首（网易云地址会过期，播到哪首才去取地址）。 */
object Engine {
    private val audio get() = Plat.impl.audio
    val state = MutableStateFlow(PlayState())
    val position = MutableStateFlow(0L)
    val buffered = MutableStateFlow(0L)
    val error = MutableStateFlow<String?>(null)
    /** 真实音质参数（开始出声后从解码器读） */
    val info = MutableStateFlow<AudioInfo?>(null)
    private var localKbps = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    var quality by androidx.compose.runtime.mutableIntStateOf(3) // 默认无损，见 Ncm.LEVELS（界面会跟着刷新）
    /** 用流量时的音质（-1 = 和 Wi-Fi 一样） */
    var qualityCell by androidx.compose.runtime.mutableIntStateOf(-1)
    /** 现在实际用的音质：按流量计费的网络下用流量音质 */
    val q: Int get() = if (qualityCell >= 0 && Plat.impl.metered()) qualityCell else quality
    /** 歌词页可见时进度刷新加快到 50ms（高亮扫过更平滑），平时 250ms */
    @Volatile var fastPos = 0
    val isPlaying get() = audio.isPlaying
    fun pause() = fadePause()

    // ---------- 淡入淡出 ----------
    /** 暂停 / 播放 / 切歌时音量渐变的时长（毫秒，0 = 关闭） */
    var fadeMs by androidx.compose.runtime.mutableIntStateOf(300)
    private var fadeJob: Job? = null
    private var rampOnPlay = false
    /** 这次 load 已经把新歌交给播放器了 */
    private var opened = false
    fun setFade(ms: Int) { fadeMs = ms; prefs?.edit()?.putInt("fade", ms)?.apply() }

    // ---------- 无缝播放 ----------
    var gapless by androidx.compose.runtime.mutableStateOf(true)
    fun changeGapless(v: Boolean) { gapless = v; prefs?.edit()?.putBoolean("gapless", v)?.apply(); if (!v) dropNext() }
    private var queuedIndex = -1
    private var queuedFmt = ""
    private var queuing = false
    /** 快播完时（剩 12 秒）把下一首排到播放器里 */
    private fun maybeQueueNext() {
        val s = state.value
        if (!gapless || queuedIndex >= 0 || queuing || !audio.isPlaying || s.queue.size < 2 || s.mode == Mode.LOOP_ONE || Sleep.holdNext) return
        val cur = s.current ?: return
        if (cur.durationMs <= 0 || cur.durationMs - audio.position > 12_000) return
        if (s.fm && s.index + 1 >= s.queue.size) return
        val ni = if (s.mode == Mode.SHUFFLE && !s.fm) s.queue.indices.filter { it != s.index }.random() else (s.index + 1) % s.queue.size
        val nt = s.queue[ni]
        queuing = true
        scope.launch {
            try {
                val dl = Downloads.file(nt.id)
                val hit = cachedLevel(nt.id)
                when {
                    nt.src != null -> { audio.queueNext(nt.src, "loc_${nt.id}", nt); queuedFmt = nt.src.substringAfterLast('.', "").lowercase() }
                    dl != null -> { audio.queueNext(dl.toURI().toString(), "dl_${nt.id}", nt); queuedFmt = dl.extension.lowercase() }
                    hit != null && !Plat.isDesktop -> { audio.queueNext(null, cacheKey(nt.id, hit.first), nt); queuedFmt = hit.second }
                    else -> { val st = Ncm.stream(nt.id, q); audio.queueNext(st.url, cacheKey(nt.id, st.level), nt); queuedFmt = st.format; prefs?.edit()?.putString("fmt_${cacheKey(nt.id, st.level)}", st.format)?.apply() }
                }
                if (state.value.current?.id == cur.id) queuedIndex = ni else audio.clearNext()
            } catch (_: Exception) { } finally { queuing = false }
        }
    }
    private fun dropNext() { if (queuedIndex >= 0) audio.clearNext(); queuedIndex = -1 }
    /** 播放器已经自己接上了下一首：只更新状态，不重新加载 */
    private fun onAdvanced() {
        val ni = queuedIndex; queuedIndex = -1
        val s = state.value
        if (ni !in s.queue.indices) return
        reportPrev()
        val t = s.queue[ni]
        reportId = t.id
        if (Ncm.loggedIn) scope.launch { Ncm.scrobbleStart(t.id) }
        state.value = s.copy(index = ni, loading = false, format = queuedFmt, kbps = 0, cached = false)
        position.value = 0; info.value = null; localKbps = 0
        Store.addRecent(t); Store.saveQueue(s.queue, ni, 0)
        if (s.fm && ni + 3 >= s.queue.size) fmFill()
    }
    private suspend fun ramp(from: Float, to: Float, ms: Int) {
        val steps = (ms / 16).coerceAtLeast(1)
        for (i in 1..steps) { audio.volume = Vol.v * (from + (to - from) * i / steps); delay(16) }
    }
    fun fadePause() {
        fadeJob?.cancel()
        if (fadeMs == 0 || !audio.isPlaying) { audio.pause(); return }
        fadeJob = scope.launch { ramp(1f, 0f, fadeMs); audio.pause(); audio.volume = Vol.v }
    }
    fun fadePlay() {
        fadeJob?.cancel()
        if (fadeMs == 0) { audio.volume = Vol.v; audio.play(); return }
        audio.volume = 0f; audio.play()
        fadeJob = scope.launch { ramp(0f, 1f, fadeMs) }
    }

    fun init() {
        prefs = Plat.prefs("ui")
        quality = prefs!!.getInt("q2", 3)
        qualityCell = prefs!!.getInt("qCell", -1)
        fadeMs = prefs!!.getInt("fade", 300)
        gapless = prefs!!.getBoolean("gapless", true)
        audio.listener = object : Audio.Listener {
            override fun onPlaying(p: Boolean) {
                state.value = state.value.copy(playing = p)
                if (p && rampOnPlay) { rampOnPlay = false; fadeJob?.cancel(); fadeJob = scope.launch { ramp(0f, 1f, fadeMs) } }
            }
            override fun onBuffering(b: Boolean) { state.value = state.value.copy(loading = b) }
            override fun onEnded() = this@Engine.onEnded()
            override fun onAdvanced() = this@Engine.onAdvanced()
            override fun onError(msg: String) { error.value = "播放失败：$msg"; next() }
        }
        // 进度：播放时定时更新
        // 音质参数：开始播放后读一次（读不到就过一会再试）
        scope.launch { while (isActive) { if (info.value == null && audio.isPlaying) info.value = audio.info()?.let { if (it.kbps == 0 && localKbps > 0) it.copy(kbps = localKbps) else it }; delay(700) } }
        scope.launch { while (isActive) { maybeQueueNext(); delay(1000) } }
        scope.launch { var t0 = System.currentTimeMillis(); while (isActive) { delay(1000); val now = System.currentTimeMillis(); if (audio.isPlaying) listenedMs += (now - t0).coerceAtMost(3000); t0 = now } }
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
    /** 这首实际听了多久（按播放时间累加，拖动进度不算） */
    private var listenedMs = 0L
    private fun reportPrev() {
        val id = reportId ?: return
        reportId = null
        val sec = listenedMs / 1000; listenedMs = 0
        state.value.queue.firstOrNull { it.id == id }?.let { if (Features.on("stats")) Stats.add(it, sec) }
        if (sec >= 30 && Ncm.loggedIn && !id.startsWith("local:") && !RecMode.on) scope.launch { Ncm.scrobble(id, sec) }
    }

    private fun load(i: Int, autoplay: Boolean, startMs: Long = 0) {
        val t = state.value.queue.getOrNull(i) ?: return
        reportPrev()
        reportId = t.id
        if (autoplay && Ncm.loggedIn && !t.local && !RecMode.on) scope.launch { Ncm.scrobbleStart(t.id) }
        dropNext()
        state.value = state.value.copy(index = i, loading = true, format = "", kbps = 0, want = autoplay)
        opened = false
        audio.pause() // 换歌时先停掉上一首，避免取地址期间旧歌还在响
        info.value = null; localKbps = 0
        position.value = startMs
        if (!RecMode.on) Store.addRecent(t)
        loadJob?.cancel()
        // 切歌淡入：先静音，真正出声时再渐变上来
        if (autoplay && fadeMs > 0) { fadeJob?.cancel(); audio.volume = 0f; rampOnPlay = true } else if (!rampOnPlay) audio.volume = Vol.v
        loadJob = scope.launch {
            try {
                // 已经完整缓存过（同档位或更高档位）：直接读本地，不联网
                val hit = cachedLevel(t.id)
                val dl = Downloads.file(t.id)
                if (t.src != null) {
                    audio.open(t.src, "loc_${t.id}", startMs, state.value.want, t); opened = true
                    state.value = state.value.copy(format = t.src.substringAfterLast('.', "").takeIf { it.length in 2..4 }?.lowercase() ?: "", kbps = 0, cached = true)
                } else if (dl != null) {
                    if (t.durationMs > 0) localKbps = (dl.length() * 8 / t.durationMs).toInt()
                    audio.open(dl.toURI().toString(), "dl_${t.id}", startMs, state.value.want, t); opened = true
                    state.value = state.value.copy(format = dl.extension.lowercase(), kbps = 0, cached = true)
                } else if (hit != null) {
                    audio.open(null, cacheKey(t.id, hit.first), startMs, state.value.want, t); opened = true
                    state.value = state.value.copy(format = hit.second, kbps = 0, cached = true)
                } else {
                    val s = Ncm.stream(t.id, q)
                    audio.open(s.url, cacheKey(t.id, s.level), startMs, state.value.want, t); opened = true
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

    fun toggle() {
        val s = state.value
        // 加载中点一下 = 打断：取消「加载完自动播放」，已经在缓冲的也停下；再点一下恢复
        if (s.loading) {
            if (s.want) { state.value = s.copy(want = false); rampOnPlay = false; fadeJob?.cancel(); audio.pause(); audio.volume = Vol.v }
            else { state.value = s.copy(want = true); if (opened) fadePlay() }   // 还在取地址：等打开时自动播
            return
        }
        if (audio.idle) load(s.index, true)
        else if (audio.isPlaying) { state.value = s.copy(want = false); fadePause() }
        else { state.value = s.copy(want = true); fadePlay() }
    }
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

    private fun onEnded() {
        // 定时关闭：停在这里，顺手把下一首准备好（不自动播）
        if (Sleep.stopAtEnd()) { val s = state.value; if (s.mode != Mode.LOOP_ONE && s.queue.size > 1 && !s.fm) load((s.index + 1) % s.queue.size, false) else seek(0); return }
        if (state.value.mode == Mode.LOOP_ONE) { seek(0); audio.play() } else next()
    }

    fun cycleMode() { dropNext(); state.value = state.value.copy(mode = Mode.entries[(state.value.mode.ordinal + 1) % 3]) }

    fun removeAt(i: Int) { dropNext();
        val s = state.value
        if (i == s.index || i !in s.queue.indices) return
        val q = s.queue.toMutableList().apply { removeAt(i) }
        state.value = s.copy(queue = q, index = if (i < s.index) s.index - 1 else s.index)
    }

    fun enqueueNext(t: Track) { dropNext()
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
        val q = q
        val order = Ncm.LEVELS.reversed().sortedBy { kotlin.math.abs(Ncm.LEVELS.indexOf(it) - q) }
        for (lv in order) {
            if (Ncm.LEVELS.indexOf(lv) > q + 1) continue
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
        scope.launch { runCatching { if (cachedLevel(n.id) == null) Ncm.stream(n.id, q) } }
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

    fun changeCellQuality(v: Int) {
        qualityCell = v
        prefs?.edit()?.putInt("qCell", v)?.apply()
        val s = state.value
        if (s.current != null && Plat.impl.metered()) load(s.index, audio.isPlaying, audio.position)
    }

    fun clearUpcoming() { dropNext(); val s = state.value; s.current?.let { state.value = s.copy(queue = listOf(it), index = 0) } }

    fun savePosition() { if (RecMode.on) return; val s = state.value; if (s.index >= 0) Store.saveQueue(s.queue, s.index, audio.position) }
}
