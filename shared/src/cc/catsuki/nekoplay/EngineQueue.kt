package cc.catsuki.nekoplay

import kotlinx.coroutines.launch

/**
 * 播放队列辅助：随机播放历史栈 + 缓存档位匹配（纯核心逻辑，零 UI 依赖）。
 * - SHUFFLE 模式下切下一首时记录真实听过的索引，「上一首」按历史栈原路回退；
 * - 选下一首随机曲目时避开最近刚听过的几首，减少重复感。
 */
internal object EngineHistory {
    private val stack = ArrayDeque<Int>()
    private const val MAX = 64

    fun clear() = stack.clear()

    fun push(index: Int) {
        if (index < 0) return
        if (stack.lastOrNull() == index) return
        stack.addLast(index)
        while (stack.size > MAX) stack.removeFirst()
    }

    fun popValid(size: Int, current: Int): Int? {
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (i in 0 until size && i != current) return i
        }
        return null
    }

    fun onRemove(removed: Int) {
        val next = stack.mapNotNull { i ->
            when {
                i == removed -> null
                i > removed -> i - 1
                else -> i
            }
        }
        stack.clear()
        stack.addAll(next)
    }

    fun pickNextShuffle(size: Int, current: Int): Int {
        if (size <= 1) return 0
        val recent = stack.takeLast(minOf(4, (size - 2).coerceAtLeast(0))).toSet() + current
        val pool = (0 until size).filter { it !in recent }.ifEmpty { (0 until size).filter { it != current } }
        return pool.random()
    }

    fun size(): Int = stack.size
}

/** 播放音频缓存键与可用档位检索 */
internal object EngineCache {
    fun key(id: String, level: String) = "${id}_$level"

    fun cachedLevel(id: String, targetQuality: Int, audio: Audio, prefs: KV?): Pair<String, String>? {
        val order = SourceApi.LEVELS.reversed().sortedBy { kotlin.math.abs(SourceApi.LEVELS.indexOf(it) - targetQuality) }
        for (lv in order) {
            if (SourceApi.LEVELS.indexOf(lv) > targetQuality + 1) continue
            val k = key(id, lv)
            if (audio.isCached(k)) return lv to (prefs?.getString("fmt_$k", "") ?: "")
        }
        return null
    }
}

/** 私人 FM 续播与不喜欢管理（从 Engine.kt 拆出，保持主引擎文件轻量可读） */
internal object EngineFm {
    private var fmJob: kotlinx.coroutines.Job? = null

    fun start(
        scope: kotlinx.coroutines.CoroutineScope,
        setState: (List<Track>) -> Unit,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            try {
                val l = SourceApi.fm()
                if (l.isEmpty()) { onError("私人 FM 暂时没有歌曲"); return@launch }
                setState(l)
                onReady()
            } catch (e: Exception) { onError("私人 FM 加载失败：${e.message}") }
        }
    }

    fun fill(
        scope: kotlinx.coroutines.CoroutineScope,
        getState: () -> PlayState,
        append: (List<Track>) -> Unit,
        then: (() -> Unit)? = null,
    ) {
        if (fmJob?.isActive == true) {
            if (then != null) scope.launch { fmJob?.join(); then() }
            return
        }
        fmJob = scope.launch {
            runCatching { SourceApi.fm() }.onSuccess { more ->
                val s = getState()
                if (s.fm) append(more.filter { m -> s.queue.none { it.id == m.id } })
            }
            then?.invoke()
        }
    }
}
