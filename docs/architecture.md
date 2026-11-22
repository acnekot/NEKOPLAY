# 架构说明

## 总体

NEKOPLAY 是一份 Kotlin + Compose 代码跑在三个平台上：

```
            ┌──────────────── shared/src（共用代码）────────────────┐
            │  ui ──► player ──► data ──► core                     │
            │  （页面）  （播放）   （数据 / 插件）  （平台接口）         │
            └───────────────────────────▲──────────────────────────┘
                                        │ PlatImpl
            ┌───────────────┬───────────┴───────┬──────────────────┐
            │ app（安卓）     │ desktop（桌面）     │ ios（iOS）        │
            │ ExoPlayer     │ libmpv            │ AVPlayer         │
            │ WebView       │ WebView2 / JavaFX │ WKWebView        │
            └───────────────┴───────────────────┴──────────────────┘
```

`shared/src` 不是一个单独的 Gradle 模块：三个平台模块各自把它加进自己的源码目录一起编译。
所以共用代码里能用的 API = 三个平台都有的 API（iOS 缺的 JVM 类在 `ios/src/iosArm64Main` 里补）。

## 子包

| 包 | 内容 | 依赖 |
|---|---|---|
| `core` | `Plat` / `PlatImpl`（平台接口：文件、配置、音频、图片、网页）、版本号、调试日志、性能统计、功能开关、检查更新、备份 | 无 |
| `data` | 数据模型（`Track`、`Playlist`…）、`Ncm`（对插件的统一门面）、插件引擎 `Plugins` / `JsSource`、插件订阅、`Store`（账号歌单和喜欢）、本地库、本地音乐、WebDAV、下载、统计 | core |
| `player` | 播放引擎 `Engine`（队列、无缝切歌、预加载）、效果器、睡眠定时、歌词解析和显示、私人 FM、桌面歌词、播放页 | core、data |
| `ui` | 导航 `Nav` / `Page`、根布局、通用组件和主题、各页面、首页模块、插件管理和登录页、宽屏布局、背景和取色 | 全部 |

目前各子包之间通过通配导入互相引用，有少量反向依赖（比如 `data` 里的页面用到 `ui` 的组件）。新代码尽量按上表的方向依赖。

## 插件

在线音源全部是 `plugins/*.js`，在 QuickJS 里运行（`data/Plugin.kt`）：

- 脚本用 `nk.register({...})` 注册方法，宿主按方法名判断插件有哪些能力（搜索、播放、歌词、登录…）
- 宿主接口 `nk.http` / `nk.store` / `nk.md5` / `nk.aes` 等由 Kotlin 实现
- **调度**：quickjs-kt 的一次 `evaluate` 要等它里面所有异步任务结束，多次 `evaluate` 之间排队。
  所以引擎自己做事件循环：`evaluate` 只负责「开始一次调用」（`__call`）或「把网络结果交回脚本」（`__resolve`），马上返回；
  网络请求在脚本外并发执行。这样一个慢调用（长轮询、大歌单）不会挡住切歌和歌词
- 歌曲 / 歌单 id 带插件前缀 `@插件id:原始id`（网易云沿用不带前缀的数字 id），`Plugins.of(id)` 据此找到插件
- 接口文档：[plugin-api.md](plugin-api.md)

## 播放

`player/Engine.kt` 管理队列和播放状态，通过 `PlatImpl.audio` 调平台播放器：

- 在线歌曲：`Ncm.stream` → 插件的 `stream` 拿地址（缓存 8 分钟）→ 平台播放器
- 本地 / WebDAV：曲目自带 `src`；WebDAV 的 `dav:/路径` 在播放时才拼上账号密码
- 快播完时预取下一首地址并交给平台播放器排队，实现无缝切歌

## 数据存放

- 配置：`Plat.prefs(名字)`，安卓是 SharedPreferences，桌面是用户目录下的 `.properties` 文件，iOS 是应用支持目录下的 JSON 文件
- 插件脚本：`filesDir/plugins/<id>.js`；每个插件的存储：`prefs("plugin_<id>")`
- 本机喜欢 / 本地歌单：`filesDir/locallib.json`

## 构建和发布

- `.github/workflows/check.yml`：每次推送和 PR，Linux 上编译、测试、风格检查
- `.github/workflows/build.yml`：手动运行或推 `v*` 标签时打包五个平台，推标签时同时发布 Release
- 版本号：`core/Version.kt`；打包时生成 `BuildMeta`（提交号、提交时间），检查更新用
