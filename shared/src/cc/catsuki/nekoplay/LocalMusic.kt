package cc.catsuki.nekoplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** 扫描到的一首本地歌 */
data class LocalSong(val path: String, val title: String, val artist: String, val album: String, val durationMs: Long)

/** 本地音乐：手机读系统媒体库，电脑扫「音乐」文件夹；结果存一份，下次打开直接显示 */
object LocalMusic {
    val list = MutableStateFlow<List<Track>>(emptyList())
    var scanning by mutableStateOf(false)
    private val kv by lazy { Plat.prefs("local") }

    fun load() {
        list.value = runCatching { JSONArray(kv.getString("list", "[]")).let { a -> (0 until a.length()).map { Track.fromJson(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    }
    fun byId(id: String) = list.value.firstOrNull { it.id == id }

    suspend fun scan() {
        scanning = true
        try {
            val songs = withContext(Dispatchers.IO) { Plat.impl.scanLocal() }
            val tracks = songs.map { s ->
                val uri = java.io.File(s.path).toURI().toString()
                Track("local:" + Integer.toHexString(s.path.hashCode()), s.title, s.artist, s.album, s.durationMs, null, emptyList(), "", uri)
            }.sortedBy { it.title.lowercase() }
            list.value = tracks
            val a = JSONArray(); tracks.forEach { a.put(it.toJson()) }
            kv.edit().putString("list", a.toString()).apply()
        } finally { scanning = false }
    }
}
