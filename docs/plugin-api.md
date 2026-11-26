# NEKOPLAY 插件接口（Plugin API v2）

NEKOPLAY 本体完全中立，不内置任何在线音源与重型特色扩展。在线音源、功能扩展、声明式页面与特色动效引擎均以 **JS 插件** 形式运行在跨端内置的 QuickJS 环境中（Android / Windows / macOS / Linux / iOS 一致），通过宿主注入的 `nk` 对象与系统交互。

---

## 插件类型（`@type`）

| `@type` | 注册入口 | 定位与职责 |
|---|---|---|
| `source`（默认） | `nk.register({...})` | 音源插件：曲库、搜索、取流、歌词、评论、歌单、登录等 |
| `feature` | `nk.registerFeature({...})` | 功能插件：槽位扩展（如 `@feature coverflow`、`@feature scrub`）、生命周期钩子、歌词后处理、热更新 `config()` |
| `page` | `nk.registerPage({...})` | 声明式页面插件：导出 `render(ctx)` 与 `onAction(action, payload)`，由原生 Compose 五端渲染 |
| `motion` | `nk.registerMotion({...})` | 动效引擎插件：导出 `profile()` 声明解析缓动曲线、通道时长与列表瀑布入场参数（多动效插件互斥生效） |

---

## 文件头元数据

脚本开头注释声明元数据，`@id` 必填（仅允许字母、数字、`_`、`-`），同 id 再次导入覆盖旧版本：

```js
// @id demo_plugin
// @name 示例插件
// @type feature
// @feature scrub
// @version 1.0.0
// @minHost 0.6.8
// @permissions player.control, storage
// @author 你的名字
// @desc 一句话说明
// @api 2
// @search track,album,artist,playlist,user,lyric   // 仅 source 插件：支持的搜索类型
```

### 权限声明与守门（`@permissions`）
- 常用权限：`network`、`storage`、`library.read`、`player.control`、`sensor.tilt`、`ui.sidebar`、`motion.profile`。
- **运行期守门**：`feature` / `page` / `motion` 插件若声明了 `@permissions` 且未包含 `network`，宿主会自动拦截 `nk.http` 请求，防止纯本地插件意外外联。

---

## 音源插件（`@type source`）

插件内所有 id 均为插件原始 id，宿主自动添加 `@插件id:` 前缀并在回调时剥离。

### 数据模型
| 类型 | 字段 |
|---|---|
| Track | `id`、`title`、`artist`、`album?`、`duration`（毫秒）、`cover?`、`artists?: [{id, name}]`、`albumId?`、`vip?` |
| Playlist | `id`、`name`、`count?`、`cover?`、`liked?`、`mine?`、`desc?` |
| Album | `id`、`name`、`cover?`、`artist?`、`year?`、`count?` |

### 常用能力方法
| 方法 | 说明 |
|---|---|
| `search(q, type, page)` | 按 `@search` 声明的类型分页搜索 |
| `stream(id, quality)` | 返回 `{url, ext?, kbps?, level?, trial?, message?}` 或 `{error}` |
| `lyric(id)` | 返回 `{lrc, tr?, yrc?}` |
| `playlist(id)` / `album(id)` / `artist(id)` / `artistAlbums(id, page)` | 歌单、专辑、歌手详情 |
| `toplists()` / `newAlbums()` / `newSongs()` / `daily()` / `fm()` / `recPlaylists()` / `square(cat, page)` | 首页与发现模块 |
| `comments(id, page)` / `commentFloor(...)` / `commentLike(...)` / `commentPost(...)` | 评论与楼中楼互动 |
| `account()` / `user(uid)` / `myPlaylists()` / `like(id, on)` / `signin()` | 账号、主页、收藏与签到 |
| `loginModes()` / `loginQr(mode)` / `loginCheck(key)` / `loginWeb(opt)` / `setCookie(raw)` / `logout()` | 扫码 / 网页 / Cookie 通用登录 |

---

## 功能 / 页面 / 动效插件（API v2）

- **功能槽位（`@feature <slot>`）**：
  - `coverflow`：解锁 3D 封面墙入口与手机横屏自动展开，通过 `config()` 热更新 3D 外观与交互策略。
  - `scrub`：解锁切歌按钮长按变速快进 / 快退，通过 `config()` 热更新预设、变调开关与触发时长。
- **生命周期与管道钩子**：`onTrackChange(track)`、`onPlayState(state)`、`transformLyric(track, lyric)`、`action(track)`。
- **声明式页面（`render(ctx)` / `onAction(act, payload)`）**：支持 `column`、`row`、`card`、`tag`、`text`、`button`、`cover`、`divider`、`spacer` 原生节点，返回 `{ toast?, open?, refresh? }`。
- **动效引擎（`profile()`）**：返回 `{ name, presetLabel, quick, standard, page, crossfade, hero, effects }`，支持 `outQuad / outCubic / outQuart / outQuint / outExpo / outExpoTail / outPow10 / outCirc / outBack / outElasticHalf / outElasticQuarter / bezier / spring` 以及 `listStaggerMs` / `listSlideDp` 瀑布入场参数。

---

## 宿主 API（`nk`）

```js
const r = await nk.http({ url, method, headers, json, form, body, redirect, binary, timeout });
nk.md5(text);
nk.md5Base64(b64);
nk.aes({ data, dataBase64, key, iv, mode: 'cbc', padding: true, decrypt: false });
nk.base64.encode(s); nk.base64.decode(b64);
nk.store.get(k); nk.store.set(k, v);
nk.log(...args);
nk.host; // { version, platform }
```

---

## 插件订阅（`index.json`）

官方插件仓库独立托管于 [`acnekot/NEKOPLAY_PLUGINS`](https://github.com/acnekot/NEKOPLAY_PLUGINS)（MIT 协议），默认订阅地址指向其 `index.json`（并自动以 jsDelivr 作为镜像兜底）：

```json
{
  "name": "订阅名",
  "plugins": [
    { "id": "demo", "name": "示例插件", "type": "feature", "version": "1.0.0", "author": "…", "desc": "…", "url": "demo.js" }
  ]
}
```
