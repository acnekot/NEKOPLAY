package cc.catsuki.nekoplay.data

import cc.catsuki.nekoplay.*
import cc.catsuki.nekoplay.core.*
import cc.catsuki.nekoplay.player.*
import cc.catsuki.nekoplay.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本机的「我喜欢」和歌单：不需要任何账号。
 * 本地音乐、WebDAV 的歌，以及来源插件没登录 / 不支持收藏的歌，点喜欢都记在这里。
 * 本地歌单的 id 是「lpl:编号」。
 */
object LocalLib {
    class LocalList(val id: String, val name: String, val tracks: List<Track>, val created: Long)

    val liked = MutableStateFlow<List<Track>>(emptyList())
    val lists = MutableStateFlow<List<LocalList>>(emptyList())
    private val file by lazy { File(Plat.impl.filesDir, "locallib.json") }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load() {
        runCatching {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            liked.value = tracks(o.optJSONArray("liked"))
            lists.value = o.optJSONArray("lists")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { LocalList(it.getString("id"), it.optString("name"), tracks(it.optJSONArray("tracks")), it.optLong("at")) } } ?: emptyList()
        }
    }
    private fun tracks(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> Track.fromJson(it.getJSONObject(i)) } } ?: emptyList()
    private fun save() {
        val o = JSONObject().put("liked", JSONArray(liked.value.map { it.toJson() }))
            .put("lists", JSONArray(lists.value.map { JSONObject().put("id", it.id).put("name", it.name).put("at", it.created).put("tracks", JSONArray(it.tracks.map { t -> t.toJson() })) }))
        io.launch { runCatching { file.writeText(o.toString()) } }
    }
    /** 备份 / 恢复用 */
    fun dump(): String = if (file.exists()) file.readText() else "{}"
    fun restore(text: String) { runCatching { JSONObject(text); file.writeText(text); load() } }

    fun isLiked(id: String) = liked.value.any { it.id == id }
    /** 返回切换后是否喜欢 */
    fun toggleLike(t: Track): Boolean {
        val on = !isLiked(t.id)
        liked.value = if (on) listOf(t) + liked.value else liked.value.filter { it.id != t.id }
        save(); return on
    }

    fun get(id: String) = lists.value.firstOrNull { it.id == id }
    fun create(name: String): LocalList {
        val l = LocalList("lpl:" + System.currentTimeMillis().toString(36), name, emptyList(), System.currentTimeMillis())
        lists.value = lists.value + l; save(); return l
    }
    fun rename(id: String, name: String) { lists.value = lists.value.map { if (it.id == id) LocalList(it.id, name, it.tracks, it.created) else it }; save() }
    fun delete(id: String) { lists.value = lists.value.filter { it.id != id }; save() }
    /** 加歌：已经在里面的跳过；返回加进去几首 */
    fun add(id: String, ts: List<Track>): Int {
        var n = 0
        lists.value = lists.value.map { l -> if (l.id != id) l else { val have = l.tracks.map { it.id }.toSet(); val add = ts.filter { it.id !in have }; n = add.size; LocalList(l.id, l.name, l.tracks + add, l.created) } }
        save(); return n
    }
    fun remove(id: String, trackId: String) { lists.value = lists.value.map { l -> if (l.id != id) l else LocalList(l.id, l.name, l.tracks.filter { it.id != trackId }, l.created) }; save() }

    /** 给界面用的歌单卡片 */
    fun asPlaylists() = lists.value.map { Playlist(it.id, it.name, it.tracks.size, it.tracks.firstOrNull { t -> t.cover != null }?.cover, false, true) }
}
