# NEKOPLAY 插件接口（v1）

NEKOPLAY 本体不带任何在线音源。音源和其它在线服务都写成 **JS 插件**，由用户在「设置 → 插件」里从文件或网址导入。
插件运行在本体内置的 QuickJS 里（安卓、桌面、iOS 都一样），没有 DOM、没有 `fetch`，网络、加密、存储都通过本体提供的 `nk` 对象完成。

插件实现了哪些方法，本体就打开哪些功能：实现了 `search` 就出现在搜索的来源里，实现了 `toplists` 首页就出现它的排行榜，实现了 `loginQr` 插件管理和曲库里就有登录入口。

## 文件头

脚本开头用注释写插件信息，`@id` 必填，只能用字母、数字、`_`、`-`，同 id 再次导入会覆盖旧版本。

```js
// @id qqmusic
// @name QQ 音乐
// @version 1.0.0
// @author 你的名字
// @desc 一句话说明
// @api 1
// @search track,album,artist,playlist,user,lyric   // 能搜的类型，省略时只有 track,album,playlist
```

## 注册

```js
nk.register({
  async search(q, type, page) { ... },
  async stream(id, quality) { ... },
  // ……
});
```

所有方法都可以是 `async`。抛出的错误（`throw new Error('原因')`）会以原文提示给用户。

## 数据格式

插件里的 id 都是插件自己的原始 id。本体会统一加上 `@插件id:` 前缀，调回插件时再去掉，插件不用关心前缀。

| 类型 | 字段 |
|---|---|
| Track 歌曲 | `id`、`title`、`artist`、`album?`、`duration`（毫秒）、`cover?`、`artists?: [{id, name}]`、`albumId?`、`vip?` |
| Playlist 歌单 | `id`、`name`、`count?`、`cover?`、`liked?`（是「我喜欢」）、`mine?`（自己建的）、`desc?` |
| Album 专辑 | `id`、`name`、`cover?`、`artist?`、`year?`、`count?` |

## 方法

| 方法 | 参数 | 返回 | 打开的功能 |
|---|---|---|---|
| `search` | `q`、`type`（`@search` 里写的类型）、`page`（从 1 开始） | 对应类型的数组；`artist` 是 `{id, name, cover?}`，`user` 是 `{uid, name, avatar?}` | 搜索来源 |
| `stream` | `id`、`quality`（0 标准、1 较高、2 极高、3 无损、4 Hi-Res，拿不到就往下降） | `{url, ext?, kbps?, level?, trial?, message?}`；没有地址时返回 `{error: '原因'}` | 播放 |
| `lyric` | `id` | `{lrc, tr?}`（LRC 文本，`tr` 是翻译） | 歌词 |
| `playlist` | 歌单 `id` | Track 数组 | 歌单详情 |
| `album` | 专辑 `id` | `{album: Album, tracks: Track[]}` | 专辑页 |
| `artist` | 歌手 `id` | `{name, cover?, desc?, tracks: Track[]}` | 歌手页 |
| `toplists` | 无 | Playlist 数组（id 交给 `playlist` 取歌） | 首页排行榜 |
| `newAlbums` | 无 | Album 数组 | 首页新碟 |
| `account` | 无 | `{name, avatar?}`，没登录返回 `null` | 账号显示 |
| `myPlaylists` | 无 | Playlist 数组 | 曲库里的歌单 |
| `like` | `id`、`on` | 无 | 点喜欢同步到账号 |
| `loginModes` | 无 | `[{id, name, web?}]`，登录页上的切换按钮；`web: true` 的走网页登录，其它的把 `id` 传给 `loginQr` | 多种登录方式 |
| `loginQr` | `mode`（`loginModes` 里的 id） | `{image?(PNG base64), text?(二维码内容), key, hint?, poll?(轮询间隔毫秒), saveApp?(手机上「存图并打开」的地址), saveAppName?}` | 扫码登录 |
| `loginCheck` | `key` | `{state: 'wait' / 'scanned' / 'done' / 'expired' / 'refused', message?}` | 扫码登录 |
| `loginWeb` | 无 | `{url, done(正则), open?(正则), hint?, pc?}`：内嵌打开 `url`，跳到匹配 `done` 的地址算登录完成，匹配 `open` 的交给系统打开（拉起别的 App） | 网页登录（只在有内嵌浏览器的平台） |
| `loginWebDone` | 匹配 `done` 的地址 | `{state: 'done'}`，失败就抛错 | 网页登录 |
| `loginWebOpen` | 地址、`{ios, id(本 App 包名), scheme('nekoplay://')}` | 改写后的地址（比如带上回跳 scheme） | 网页登录 |
| `loginWebBack` | 别的 App 跳回来的 `nekoplay://…` | 要继续加载的网页地址，没有就 `null` | 网页登录 |
| `setCookie` | 粘贴的文本 | 无，不认就抛错 | 登录页的「粘贴 Cookie」 |
| `logout` | 无 | 无 | 退出登录 |

首页来源选了哪个插件，账号、日推、私人 FM、推荐歌单、歌单广场、评论这些没有 id 的功能就找它；它没实现的再找第一个实现了的插件。
首页模块按首页来源里的插件有没有对应方法显示：每日推荐 `daily`、私人 FM `fm`、推荐歌单 `recPlaylists`、排行榜 `toplists`、新碟 `newAlbums`、歌单广场 `square`。
点喜欢时，来源插件实现了 `like` 并且登录了就同步到账号；否则记在本机的「本机喜欢」里。

`stream` 返回 `trial: true` 时本体会提示「只能放试听」，提示文字用 `message`。

## 本体提供的 `nk`

```js
// 网络：不受跨域限制；默认跟随跳转，redirect: false 时把 3xx 原样返回
const r = await nk.http({
  url: 'https://example.com/api',
  method: 'POST',              // 省略时：有 body / form / json 就 POST，否则 GET
  headers: { Referer: '...' }, // 不写 User-Agent 时用桌面 Chrome 的
  json: { a: 1 },              // 或 form: {k: 'v'}，或 body: '文本'
  redirect: false,
  binary: true,                // 返回 r.bodyBase64，不返回 r.body
  timeout: 35,                 // 读超时（秒），长轮询用
});
r.status; r.url; r.body; r.json(); r.header('location'); r.cookies(); r.headers['set-cookie'] // 值都是数组

nk.md5('文本')                    // 十六进制
nk.md5Base64('base64 数据')
nk.aes({ data: '文本', key: '16 位', iv: '16 位', mode: 'cbc' /* 或 ecb */, padding: true, decrypt: false }) // 返回 base64；解密时传 dataBase64
nk.base64.encode('文本'); nk.base64.decode('base64')
nk.store.get('k'); nk.store.set('k', 任意可 JSON 化的值)   // 每个插件独立保存，卸载前一直在
nk.log('调试信息')                 // 显示在 设置 → 高级 → 请求记录（打开调试后）
```

插件在一个长期存在的脚本环境里运行，全局变量会保留（比如扫码登录过程中的 cookie）。
关闭插件、重新加载或重新导入时环境会重建。

## 例子

完整的例子见 `plugins/qqmusic.js`。
