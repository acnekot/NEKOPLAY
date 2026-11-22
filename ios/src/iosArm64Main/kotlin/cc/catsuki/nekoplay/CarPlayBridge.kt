package cc.catsuki.nekoplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.*
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithURL
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoMediaTypeAudio
import platform.MediaPlayer.MPNowPlayingInfoPropertyDefaultPlaybackRate
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyMediaType
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPNowPlayingPlaybackStatePaused
import platform.MediaPlayer.MPNowPlayingPlaybackStatePlaying
import platform.UIKit.UIImage

/** CarPlay 曲目数据传输对象 */
data class CarPlayTrack(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
)

/** CarPlay 歌单数据传输对象 */
data class CarPlayPlaylist(
    val id: String,
    val name: String,
    val count: Int,
    val coverUrl: String?,
    val isLocal: Boolean = false,
)

/**
 * CarPlay 桥接层（供 Swift 端 CarPlaySceneDelegate 调用）：
 * - 复用共享层 Engine / Store / LocalLib / Ncm，零重复播放逻辑
 * - 即使 iPhone 主界面尚未打开，接入车载 CarPlay 时也能独立唤醒内核并播放音乐
 */
object CarPlayBridge {
    private val mainScope: CoroutineScope = MainScope()
    private var observeJob: Job? = null
    private val loadedLists = HashMap<String, List<Track>>()
    private var cachedArtwork: MPMediaItemArtwork? = null
    private var cachedArtworkFor: String? = null

    private fun Track.toCp() = CarPlayTrack(id, title, artist, cover)

    fun ensureReady() {
        ensureBooted()
    }

    fun recents(): List<CarPlayTrack> = Store.recents.value.take(50).map { it.toCp() }

    fun queue(): List<CarPlayTrack> = Engine.state.value.queue.take(100).map { it.toCp() }

    fun currentIndex(): Int = Engine.state.value.index

    fun currentTrackId(): String? = Engine.state.value.current?.id

    fun isPlaying(): Boolean = Engine.state.value.playing

    fun isCurrentLiked(): Boolean {
        val id = Engine.state.value.current?.id ?: return false
        return id in Store.likedIds.value
    }

    fun toggleLikeCurrent() {
        val cur = Engine.state.value.current ?: return
        Store.toggleLike(cur) {}
    }

    /** 合并云端歌单、本地「我喜欢」与本地自建歌单 */
    fun playlists(): List<CarPlayPlaylist> = buildList {
        Store.playlists.value.forEach { pl ->
            add(CarPlayPlaylist(pl.id, pl.name, pl.count, pl.cover, isLocal = false))
        }
        val localLiked = LocalLib.liked.value
        if (localLiked.isNotEmpty()) {
            add(CarPlayPlaylist("local:liked", "本地喜欢", localLiked.size, localLiked.firstOrNull()?.cover, isLocal = true))
        }
        LocalLib.lists.value.forEach { ll ->
            add(CarPlayPlaylist(ll.id, ll.name, ll.tracks.size, ll.tracks.firstOrNull()?.cover, isLocal = true))
        }
        val localSongs = LocalMusic.list.value
        if (localSongs.isNotEmpty()) {
            add(CarPlayPlaylist("local:all", "本地音乐", localSongs.size, localSongs.firstOrNull()?.cover, isLocal = true))
        }
    }

    /** 异步加载指定歌单曲目列表（结果缓存在 loadedLists 供点击直接播放） */
    fun loadPlaylistTracks(playlistId: String, onResult: (List<CarPlayTrack>) -> Unit) {
        mainScope.launch {
            val tracks = when {
                playlistId == "local:liked" -> LocalLib.liked.value
                playlistId == "local:all" -> LocalMusic.list.value
                playlistId.startsWith("lpl:") -> LocalLib.lists.value.firstOrNull { it.id == playlistId }?.tracks ?: emptyList()
                else -> runCatching { withContext(Dispatchers.IO) { Ncm.playlistTracks(playlistId) } }.getOrDefault(emptyList())
            }
            loadedLists[playlistId] = tracks
            onResult(tracks.take(100).map { it.toCp() })
        }
    }

    /** 异步加载每日推荐曲目 */
    fun loadDailyTracks(onResult: (List<CarPlayTrack>) -> Unit) {
        mainScope.launch {
            val tracks = runCatching { withContext(Dispatchers.IO) { Ncm.daily() } }.getOrDefault(emptyList())
            loadedLists["__daily__"] = tracks
            onResult(tracks.take(60).map { it.toCp() })
        }
    }

    fun playRecents(index: Int) {
        val list = Store.recents.value
        if (list.isEmpty()) return
        Engine.playList(list, index.coerceIn(0, list.lastIndex))
    }

    fun playQueueAt(index: Int) {
        val list = Engine.state.value.queue
        if (list.isEmpty()) return
        Engine.playAt(index.coerceIn(0, list.lastIndex))
    }

    fun playLoadedList(listKey: String, index: Int) {
        val list = loadedLists[listKey] ?: return
        if (list.isEmpty()) return
        Engine.playList(list, index.coerceIn(0, list.lastIndex))
    }

    fun startFm() {
        Engine.startFm()
    }

    /** 监听播放状态、喜欢、最近播放与歌单变更，通知 CarPlay 刷新界面并同步 MPNowPlayingInfoCenter */
    fun startObserving(onChange: () -> Unit) {
        observeJob?.cancel()
        syncNowPlaying(Engine.state.value)
        observeJob = mainScope.launch {
            combine(Engine.state, Store.recents, Store.playlists, Store.likedIds) { st, _, _, _ -> st }
                .collect { st ->
                    syncNowPlaying(st)
                    onChange()
                }
        }
    }

    private fun syncNowPlaying(st: PlayState) {
        val t = st.current ?: return
        runCatching { AVAudioSession.sharedInstance().setActive(true, null) }
        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to t.title,
            MPMediaItemPropertyArtist to t.artist,
            MPMediaItemPropertyAlbumTitle to t.album,
            MPMediaItemPropertyPlaybackDuration to t.durationMs / 1000.0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to Engine.position.value / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate to if (st.playing) 1.0 else 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate to 1.0,
            MPNowPlayingInfoPropertyMediaType to MPNowPlayingInfoMediaTypeAudio,
        )
        if (cachedArtworkFor == t.id) {
            cachedArtwork?.let { info[MPMediaItemPropertyArtwork] = it }
        } else {
            loadNowPlayingArtwork(t)
        }
        val center = MPNowPlayingInfoCenter.defaultCenter()
        center.nowPlayingInfo = info
        center.playbackState = if (st.playing) MPNowPlayingPlaybackStatePlaying else MPNowPlayingPlaybackStatePaused
    }

    private fun loadNowPlayingArtwork(t: Track) {
        val cover = t.cover ?: return
        val sized = if ('?' in cover) cover else "$cover?param=512y512"
        val url = NSURL.URLWithString(sized) ?: return
        NSURLSession.sharedSession.dataTaskWithURL(url) { data, _, _ ->
            val img = data?.let { UIImage.imageWithData(it) } ?: return@dataTaskWithURL
            NSOperationQueue.mainQueue.addOperationWithBlock {
                if (Engine.state.value.current?.id != t.id) return@addOperationWithBlock
                cachedArtwork = MPMediaItemArtwork(boundsSize = img.size) { _ -> img }
                cachedArtworkFor = t.id
                syncNowPlaying(Engine.state.value)
            }
        }.resume()
    }

    fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
    }
}
