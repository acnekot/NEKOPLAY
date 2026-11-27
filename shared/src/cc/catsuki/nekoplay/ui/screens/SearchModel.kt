package cc.catsuki.nekoplay

/** 一次搜索的结果：按类型放在不同的列表里；mine 是本地匹配到的「我的歌单」，page / more 用于滚动翻页 */
internal class SearchRes(
    val type: Int,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val users: List<UserBrief> = emptyList(),
    val mine: List<Playlist> = emptyList(),
    val page: Int = 1,
    val more: Boolean = false,
) {
    val empty get() = size == 0 && mine.isEmpty()
    val size get() = tracks.size + albums.size + artists.size + playlists.size + users.size

    /** 翻页：追加第 p 页并按 id 去重；这一页没带来新条目就当作到底了 */
    @Suppress("UNCHECKED_CAST")
    fun plus(v: List<Any>, kind: String, p: Int): SearchRes {
        var t = tracks; var al = albums; var ar = artists; var pl = playlists; var us = users
        when (kind) {
            "album" -> al = (albums + v as List<Album>).distinctBy { it.id }
            "artist" -> ar = (artists + v as List<Artist>).distinctBy { it.id }
            "playlist" -> pl = (playlists + v as List<Playlist>).distinctBy { it.id }
            "user" -> us = (users + v as List<UserBrief>).distinctBy { it.uid }
            else -> t = (tracks + v as List<Track>).distinctBy { it.id }
        }
        val grown = t.size + al.size + ar.size + pl.size + us.size > size
        return SearchRes(type, t, al, ar, pl, us, mine, p, grown)
    }

    /** 翻页失败：保留已有结果，停止继续加载 */
    fun stopped() = SearchRes(type, tracks, albums, artists, playlists, users, mine, page, false)
}

/** 本地已有的歌单（主账号 + 其它音源曲库）里按名字匹配，最多 20 个 */
internal fun myPlaylists(k: String, all: List<Playlist> = Store.playlists.value): List<Playlist> {
    val q = k.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return all.filter { it.name.lowercase().contains(q) }.take(20)
}
