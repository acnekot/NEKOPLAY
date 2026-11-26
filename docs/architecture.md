# 架构说明

## 总体

NEKOPLAY 是一份 Kotlin + Compose Multiplatform 代码运行在三大平台（Desktop / Android / iOS）：

```
            ┌──────────────── shared/src（共用代码，单文件 ≤ 400 行）────────┐
            │  ui ──► player ──► data ──► core                              │
            │  （页面）  （播放）   （数据 / 插件）  （平台接口与守门）          │
            └───────────────────────────▲───────────────────────────────────┘
                                        │ PlatImpl
            ┌───────────────┬───────────┴───────┬──────────────────────────┐
            │ app（安卓）     │ desktop（桌面）     │ ios（iOS）               │
            │ ExoPlayer     │ libmpv            │ AVPlayer / CarPlay       │
            │ WebView       │ 扫码 / Cookie      │ WKWebView                │
            └───────────────┴───────────────────┴──────────────────────────┘
```

`shared/src` 以源码目录形式参与三端联合编译；iOS 缺失的最小 JVM 兼容层在 `ios/src/iosArm64Main` 补齐。每次提交均由 `./gradlew archCheck` 强制校验 **0 反向 UI 依赖** 与 **共享 `.kt` 单文件 ≤ 400 行**。

## 分层职责

| 层 | 核心模块 | 依赖方向 |
|---|---|---|
| `core` | `Plat` / `PlatImpl`、`AppGraph`、`Version`、`Hash`（MD5 + 原子写盘）、`Features`（5 大关联功能集群）、`Backup`（可选 AES-256 加密）、`Updater`、`Perf`、`Debug` | 无 |
| `data` | `Ncm`、`Plugin` / `Plugins` / `PluginSubs`（QuickJS API v2）、`Store`、`LocalLib`、`LocalMusic`、`Dav`、`Download`、`Stats`（异步防抖原子落盘） | `core` |
| `player` | `Engine` / `EngineQueue`（随机历史栈、FM、缓存检索）、`EngineScrub`（`@feature scrub` 插件槽位）、`Fx`、`Sleep`、`Lrc`、`DeskLyric` | `core`、`data` |
| `ui` | `ui/Motion` / `ui/MotionEasings`（简单/高级分层 + `@type motion` 剥离）、`ui/InputBus`、`ui/RootNav`、`ui/components/`、`ui/player/`、`ui/screens/`、`ui/settings/` | 全部 |

## 插件体系（Plugin API v2）

- 本体不内置任何在线音源，官方插件托管于独立 MIT 仓库 `acnekot/NEKOPLAY_PLUGINS`。
- 划分四大插件类型：`source`（音源）、`feature`（功能槽位，如 `coverflow` / `scrub`）、`page`（声明式原生页）、`motion`（外置动效引擎，如瀑布入场与长留尾缓动）。
- 权限守门：声明 `@permissions` 的非音源插件未包含 `network` 时自动拦截 `nk.http`。
- 并发调度：`JsSource` 内建事件循环，慢速网络请求在 QuickJS 锁外异步并发执行；`Plugins` 使用单一托管 `SupervisorJob` 作用域并对并发刷新去重。
- 详细接口规范见 [plugin-api.md](plugin-api.md)。

## 功能开关集群（`Features`）

全部内置可选模块统一对齐为 5 大关联集群（支持「关联集群」一键总控与「单功能」精细开关，并在功能管理页同步总控外置功能插件）：
1. **曲库与存储**：本地音乐 / WebDAV 音乐 / 本地下载
2. **音频增强**：效果器与均衡器（按平台 `supportsFx` 门控） / 外部音频插件宿主（桌面）
3. **播放与歌词**：桌面歌词（桌面） / 通知栏歌词（移动） / 歌词偏移 / 歌词卡片 / 定时关闭
4. **外观与动效**：自定义背景 / 动态取色 / 动效与曲线
5. **数据与扩展**：听歌统计 / WebDAV 备份 / 侧拉模式（桌面） / Discord 状态（桌面）

## 数据与可靠性

- 所有高频 JSON 状态文件（`stats.json`、`locallib.json`、`store` 缓存、插件脚本）统一使用 `File.writeTextAtomic()`（同目录 `.tmp` + 原子替换）防止强杀损坏。
- 备份支持可选口令 AES-256-CBC + MAC 完整性校验加密，涵盖设置、主题、统计、本机曲库、插件脚本与可选凭证。

## 构建与质量守门

- `.github/workflows/check.yml`：每次推送与 PR 自动执行 `archCheck`、桌面单测、Android 与 iOS 三端编译校验。
- `.github/workflows/build.yml`：支持全平台标签发版与按平台勾选的 `workflow_dispatch` 预发布构建。
