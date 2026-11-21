# QQ 音乐接口说明（净室文档）

这份文档只记录**接口事实**：网关、模块名、方法名、参数名和返回字段。
来源：阅读 luren-dc/QQMusicApi 的提交历史（含 2026-04-02 之后的 GPL 版本）整理而成，**不含其代码**。
NEKOPLAY 的 `Qqm.kt` 只依据本文档和 MIT 时期（v0.4.1 及更早）的实现编写。

## 网关

- `POST https://u.y.qq.com/cgi-bin/musicu.fcg`，JSON 正文：`{"comm": {...}, "req_0": {"module", "method", "param"}}`
- `comm` 用 PC 客户端身份：`ct=19, cv=2201, platform="", chid="0", g_tk, guid`；登录后再带 `uin`、`tmeLoginType`，Cookie 带 `uin / qqmusic_uin / qm_keyst / qqmusic_key`
- 返回 `req_0.code`：0 成功，1000 登录过期，2001 风控
- 安卓身份的版本号在 2026-10 升到 20.9.0.8（我们暂不使用安卓身份）

## 已用接口（MIT 时期即有）

| 功能 | module | method | 关键参数 | 返回 |
|---|---|---|---|---|
| 搜索 | music.search.SearchCgiService | DoSearchForQQMusicDesktop | query, search_type(0 歌 2 专辑 3 歌单), num_per_page, page_num | body.song.list / album.list / songlist.list |
| 歌曲信息 | music.trackInfo.UniformRuleCtrl | CgiGetTrackInfo | mids[], types[], modify_stamp[], ctx, client | tracks[] |
| 播放地址 | music.vkey.GetVkey | UrlGetVkey | uin, filename[], guid, songmid[], songtype[], ctx | midurlinfo[].purl, sip[] |
| 歌词 | music.musichallSong.PlayLyricInfo | GetPlayLyricInfo | songMid, crypt=0, trans=1, qrc=0, type=1 | lyric / trans（base64） |
| 专辑 | music.musichallAlbum.AlbumSongList | GetAlbumSongList | albumMid, begin, num, order | songList[].songInfo |
| 歌单 | music.srfDissInfo.DissInfo | CgiGetDiss | disstid, dirid（我喜欢=201）, song_begin, song_num | songlist[], dirinfo |
| 用户歌单 | music.musicasset.PlaylistBaseRead | GetPlaylistByUin | uin | v_playlist[]（tid, dirId, dirName, songNum, picUrl） |
| 加歌到歌单 | music.musicasset.PlaylistDetailWrite | AddSonglist / DelSonglist | dirId, tid, bFmtUtf8=true, v_songInfo[{songId, songType}] | retCode（80092 = 已存在 / 不存在） |

## GPL 版本新增的接口事实（2026-04 之后）

| 功能 | module | method | 参数 | 说明 |
|---|---|---|---|---|
| 收藏歌曲（我喜欢） | music.musicasset.PlaylistDetailWrite | AddSonglist / DelSonglist | dirId=201，其余同上 | 用数字 songId，不是 mid |
| 新碟上架 | newalbum.NewAlbumServer | get_new_album_info | area, num, start | albums[]：mid, name, singers, release_time；total |
| 收藏专辑 | music.musicasset.AlbumFavWrite | FavAlbum / CancelFavAlbum | v_albumId[]（数字 id） | result, v_failedAlbumId |
| 多风格翻译歌词 | music.musichallSong.PlayLyricInfo | BatchGetMultiStyleTransLyric | songID（数字） | — |
| MV 分类列表 | MvService.MvInfoProServer | GetAllocMvInfo | area, version, order, start, size | — |

其它变化：设备指纹（QIMEI）生成规则改了几轮；安卓 comm 增加 traceid。只用 PC 身份时不受影响。
