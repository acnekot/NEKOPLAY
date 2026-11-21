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
| `search` | `q`、`type`（`track` / `album` / `playlist`）、`page`（从 1 开始） | 对应类型的数组 | 搜索来源 |
| `stream` | `id`、`quality`（0 标准、1 较高、2 极高、3 无损、4 Hi-Res，拿不到就往下降） | `{url, ext?, kbps?, level?, trial?, message?}`；没有地址时返回 `{error: '原因'}` | 播放 |
| `lyric` | `id` | `{lrc, tr?}`（LRC 文本，`tr` 是翻译） | 歌词 |
| `playlist` | 歌单 `id` | Track 数组 | 歌单详情 |
| `album` | 专辑 `id` | `{album: Album, tracks: Track[]}` | 专辑页 |
| `artist` | 歌手 `id` | `{name, cover?, desc?, tracks: Track[]}` | 歌手页 |
| `toplists` | 无 | Playlist 数组（id 交给 `playlist` 取歌） | 首页排行榜 |
| `account` | 无 | `{name, avatar?}`，没登录返回 `null` | 账号显示 |
| `myPlaylists` | 无 | Playlist 数组 | 曲库里的歌单 |
| `like` | `id`、`on` | 无 | 点喜欢同步到账号 |
| `loginQr` | 无 | `{image?(PNG base64), text?(二维码内容), key, hint?}` | 扫码登录 |
| `loginCheck` | `key` | `{state: 'wait' / 'scanned' / 'done' / 'expired' / 'refused', message?}` | 扫码登录 |
| `logout` | 无 | 无 | 退出登录 |

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
