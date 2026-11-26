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

### 1. 功能槽位（`@feature <slot>`）与热更新 `config()`

| 槽位 | 说明 | `config()` 支持字段 |
|---|---|---|
| `coverflow` | 解锁 3D 封面墙入口与手机横屏自动展开 | `wingAngle`（25–80）、`maxOpenScale`（1.0–2.0）、`spinDeg`（0–45）、`spineRatio`（0.06–0.18）、`thicknessDp`（0–14）、`reflectionAlpha`（0–0.55）、`portraitExpand`（bool）、`desktopSidebarToggle`（bool） |
| `scrub` | 解锁切歌按钮长按变速快进 / 快退 | `preset`（string）、`pitchShift`（bool，是否随转速变调）、`holdMs`（长按触发延迟 ms）、`minRate` / `maxRate`（倍率区间）、`accelMs`（电机加速时长 ms） |

### 2. 生命周期与管道钩子
- `onTrackChange(track)`：切歌回调
- `onPlayState(state)`：播放状态变化回调
- `transformLyric(track, lyric)`：歌词后处理管道
- `action(track)`：自定义快捷动作，返回 `{ toast?, open? }`（`open` 支持 `"coverflow"` / `"settings"` / `"plugin:<id>"`）

### 3. 声明式页面（`render(ctx)` / `onAction(action, payload)`）
- 宿主传入 `ctx`：`{ hostVersion, platform, lang, wide, landscape, playing, track?, playlistCount, recentCount }`
- 支持 9 种原生 Compose 节点（跨五端一致渲染）：
  - 容器：`column` / `row` / `card`（支持 `pad`, `gap`, `action`, `payload`, `grow`, `children`）
  - 展示：`tag`（`text`）、`text`（`text`, `size`, `tone: "primary"|"sub"|"bad"`, `weight`, `lines`, `mono`）、`cover`（`url`, `size`, `title`）、`divider`、`spacer`（`size`）
  - 交互：`button`（`text`, `primary`, `action`, `payload`）
- `onAction(action, payload)` 返回 `{ toast?, open?, refresh? }`（`refresh` 默认 `true`，触发重新渲染并刷新 `config()`/`profile()`）。

### 4. 动效引擎（`@type motion` · `profile()`）
```json
{
  "name": "示例动效",
  "presetLabel": "标准",
  "quick": { "easing": "outExpo", "ms": 140 },
  "standard": { "easing": "outQuint", "ms": 300 },
  "page": { "easing": "outQuint", "ms": 420 },
  "crossfade": { "easing": "outQuint", "ms": 460 },
  "hero": { "easing": "spring", "damping": 0.82, "stiffness": 420 },
  "effects": {
    "hoverShiftDp": 5, "hoverTint": true, "pressScale": 0.985, "pageScale": true,
    "listStaggerMs": 24, "listSlideDp": 36,
    "originExpand": true, "originRipple": true, "originInherit": true,
    "originCornerDp": 18, "selectCoverScale": 1.10, "selectFontScale": 1.06
  }
}
```
支持缓动名称：`outQuad` / `outCubic` / `outQuart` / `outQuint` / `outExpo` / `outExpoTail` / `outPow10` / `outCirc` / `outBack` / `outElasticHalf` / `outElasticQuarter` / `bezier`（配 `x1,y1,x2,y2`） / `spring`（配 `damping,stiffness`）。
`effects` 可选源点空间字段：`originExpand`（源点容器展开与原路回缩）、`originRipple`（列表点击邻近物理波纹联动）、`originInherit`（多级跳转与目标页内容动量继承）、`originCornerDp`（展开圆角变形 0–36dp）、`selectCoverScale` / `selectFontScale`（选中项封面与字体微放大倍率）。

---

## 宿主 API（`nk`）

```js
const r = await nk.http({ url, method, headers, json, form, body, redirect, binary, timeout });
// r = { status, url, headers: { [lowerName]: string[] }, body | bodyBase64 }
// 便捷方法：r.header('content-type') / r.cookies() / r.json()

nk.md5(text);
nk.md5Base64(b64);
nk.aes({ data, dataBase64, key, iv, mode: 'cbc', padding: true, decrypt: false });
nk.base64.encode(s); nk.base64.decode(b64);
nk.store.get(k); nk.store.set(k, v);
nk.log(...args);
nk.host; // { version, platform, lang } — lang 为当前界面语言（zh-CN / zh-TW / en / ja）
```

---

## 许可与责任说明

- NEKOPLAY 本体遵循仓库根目录 [LICENSE](../LICENSE)（保留全部所有权）；官方插件仓库 [`acnekot/NEKOPLAY_PLUGINS`](https://github.com/acnekot/NEKOPLAY_PLUGINS) 独立采用 **MIT** 开源许可证，两者边界清晰互不影响。
- 用户导入第三方插件或订阅时，客户端将展示首次安全与责任确认提示；第三方插件的内容与合规责任由其作者及使用者自行承担。

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
