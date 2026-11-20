package cc.catsuki.nekoplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

@Composable fun LocalMusicPage() {
    val nav = LocalNav.current
    val p = c
    val list by LocalMusic.list.collectAsState()
    val st by Engine.state.collectAsState()
    val scope = rememberCoroutineScope()
    val (granted, ask) = Plat.impl.audioPermission()
    LaunchedEffect(granted) { if (granted && list.isEmpty() && !LocalMusic.scanning) LocalMusic.scan() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("LOCAL"); Spacer(Modifier.height(4.dp))
                T("本地音乐", Fs.HERO, w = 800, lh = 1.3f)
                T(if (Plat.isDesktop) "扫描「音乐」文件夹里的歌（mp3 / flac / m4a / ogg / wav …）" else "读取手机媒体库里的歌，同名 .lrc 会当作歌词", 13, p.s, lines = 2)
                T("${two(list.size)} 首", 12, p.s, mono = true)
                Spacer(Modifier.height(14.dp))
                ActionRow {
                    if (!granted) PrimaryBar("允许读取音乐", NIcons.chevronRight, Modifier.weight(1f)) { ask() }
                    else {
                        if (list.isNotEmpty()) { PrimaryBar("播放全部", NIcons.play, Modifier.weight(1f)) { Engine.playList(list, 0) }; Spacer(Modifier.width(10.dp)) }
                        OutlineBtn(if (LocalMusic.scanning) "扫描中…" else "重新扫描", pad = 14.dp) { if (!LocalMusic.scanning) scope.launch { runCatching { LocalMusic.scan() }.onFailure { nav.toast = "扫描失败：${it.message}" }; nav.toast = "找到 ${LocalMusic.list.value.size} 首" } }
                    }
                }
                Spacer(Modifier.height(12.dp)); Rule()
                if (granted && list.isEmpty() && !LocalMusic.scanning) T("没有找到本地音乐", 13, p.s, modifier = Modifier.padding(vertical = 28.dp))
            }
        }
        itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
            TrackRow(t, st.current?.id == t.id, t.src?.substringAfterLast('.')?.uppercase()?.take(4)) { Engine.playList(list, i) }
        }
    }
}
