package cc.catsuki.nekoplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 歌词缓存（最近 30 首）。
 * 功能插件的歌词后处理（翻译 / 罗马音等，可能要等网络或大模型几秒到几十秒）放到后台：
 * 先把原文交给界面，处理完再替换缓存并 rev+1，界面跟着刷新，不会因为插件慢而一直显示加载中。
 */
object LyricCache {
    // 读一次就挪到末尾，超出时丢掉最前面的（各平台通用写法）
    private val map = LinkedHashMap<String, List<LyricLine>>()
    private val pending = HashSet<String>()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 后台处理完成 / 缓存清空时 +1 */
    val rev = MutableStateFlow(0)

    private fun put(id: String, l: List<LyricLine>) = synchronized(this) {
        map.remove(id); map[id] = l
        while (map.size > 30) map.remove(map.keys.first())
    }
    fun peek(id: String): List<LyricLine>? = synchronized(this) { map.remove(id)?.also { map[id] = it } }
    /** 插件设置变了（换了翻译模型 / 目标语言）：丢掉缓存，下次重新取 */
    fun clear() { synchronized(this) { map.clear() }; rev.value++ }

    private fun parse(d: LyricData) = d.yrc?.let { Lrc.parseYrc(it, d.tr) }?.takeIf { it.isNotEmpty() } ?: Lrc.parse(d.lrc, d.tr)

    suspend fun get(id: String): List<LyricLine> = peek(id) ?: run {
        val raw: LyricData? = when {
            // 本地音乐：读同名 .lrc
            id.startsWith("local:") -> runCatching {
                val f = java.io.File(java.net.URI(LocalMusic.byId(id)?.src))
                java.io.File(f.parentFile, f.nameWithoutExtension + ".lrc").takeIf { it.exists() }?.readText()
            }.getOrNull()?.let { LyricData(it, null, null) }
            id.startsWith("dav:") -> DavMusic.lyric(id)?.let { LyricData(it, null, null) }
            else -> SourceApi.lyricRaw(id)
        }
        val l = raw?.let { parse(it) } ?: emptyList()
        put(id, l)
        if (raw != null && l.isNotEmpty()) transformLater(id, raw)
        l
    }

    private fun transformLater(id: String, raw: LyricData) {
        if (!Plugins.hasLyricTransform()) return
        synchronized(this) { if (!pending.add(id)) return }
        bg.launch {
            try {
                val d = Plugins.transformLyric(Plugins.trackFor(id), raw)
                if (d != raw) { parse(d).takeIf { it.isNotEmpty() }?.let { put(id, it); rev.value++ } }
            } catch (_: Exception) {
            } finally { synchronized(this@LyricCache) { pending.remove(id) } }
        }
    }
}
