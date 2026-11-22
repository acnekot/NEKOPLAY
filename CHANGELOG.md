# 更新日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。
版本号只在 `shared/src/cc/catsuki/nekoplay/Version.kt` 里改；发版时把「未发布」改成版本号和日期，再打 `v版本号` 标签。

## [未发布]

## [0.6.1] - 2026-11-23

QQ 音乐与酷狗插件全面增强、桌面端体验细节修复与架构预拆。

### 新增
- QQ 音乐插件 `1.7.0`：接入纯 JS MIT 协议 QRC 逐字歌词解密（卡拉 OK 逐字高亮）、精准 VIP 标识（`action.switch` 权限位）、个人主页与头像/昵称同步、听歌排行与播放历史回传、歌手搜索/热搜/搜索联想、私人 FM、每日推荐、推荐歌单、歌单广场、新碟/新歌、MV 精选与播放、歌曲热评/点赞、相似歌曲/歌手/歌单、歌单管理与专辑收藏
- 酷狗音乐插件 `1.1.0`：新增热搜、搜索联想、推荐歌单、新歌速递与每日推荐

### 变更
- 预拆接近 400 行上限的核心文件：拆出 `ui/RootNav.kt` 与 `Plugins.kt`（`Root.kt` 396→249 行、`Plugin.kt` 393→233 行）
- 清理 UI 中残留的网易云硬编码文案与个人默认用户名，统一按主音源动态显示
- 修正 4 处文档中的 `Version.kt` 路径并清零 `Updater.kt` 编译警告

### 修复
- 修复桌面端歌词页无法使用鼠标滚轮/触控板滚动浏览的问题
- 修复设置页「关于」入口版本号写死 `0.3.0` 的问题

## [0.6.0] - 2026-11-22

架构解耦、资源优化与 Apple CarPlay 车载支持。

### 新增
- iOS 支持 Apple CarPlay 车载音频：提供「推荐 / 歌单 / 队列」三标签页、私人 FM、原生「播放中」页面（含喜欢按钮与 Up Next 队列）及 `MPNowPlayingInfoCenter` 实时同步
- 新增 `Motion` 动效令牌系统，接入播放页 400ms 换歌封面交叉淡化与喜欢/循环模式微动效
- 新增架构守门任务 `archCheck` 与自动化暴力压测套件 `StressTest`
- 新增 `AppGraph` 轻量服务容器与 `docs/perf-baseline.md` 性能基准记录

### 变更
- 完成共享层单向分层解耦，拆分全部超 400 行 UI 大文件至 `ui/` 子目录，清零 `core/data/player` 反向 UI 依赖
- 桌面端性能优化：冷启动耗时 `-35.4%`、稳态内存 RSS `-30%`、空载堆内存降至 `26 MB`（插件按需启动 + 5 分钟空闲回收 QuickJS、启动任务延后、暂停挂起轮询、三端桌面统一 SerialGC + 256MB 堆上限）
- macOS 移除进程内 JavaFX 依赖（保留手机端网页登录与 Linux/Windows 桌面端），`.app` 体积从 `195 MB` 降至 `155 MB`（`-40 MB`）

### 修复
- 彻底修复 macOS QQ 网页登录窗口卡死与白屏问题，桌面端默认优先原生扫码登录
- 修复 5 处边界/竞态潜在崩溃（`PlayerOverlay` / `Main` / `PlayerLayout` / `HomeLayout`）
- 修复 iOS 锁屏与 CarPlay 远程控制事件注册及播放状态同步

## [0.5.0] - 2026-11-22

第一个正式发布的版本。

### 新增
- 插件系统：在线音源全部做成 JS 插件（QuickJS），本体不带任何音源
- 官方插件：网易云音乐、QQ 音乐、酷狗音乐
- 插件订阅：从订阅列表一键安装、更新插件
- 通用登录页：扫码、网页登录（手机拉起 QQ、电脑内嵌网页）、粘贴 Cookie
- 本机喜欢、本地歌单（不需要账号）
- WebDAV 音乐：直接浏览网盘文件夹播放，同名 `.lrc` 当歌词
- 检查更新：正式版走 GitHub Release，测试版走 main 分支的 Actions
- 电脑版内嵌网页：Windows 用 WebView2，macOS / Linux 用 JavaFX
- 首页来源可选单个插件或「混合」，模块按插件能力显示
- 配置备份带上插件脚本、插件存储、本地歌单
- 单元测试（歌词解析、版本比较、md5、WebDAV、插件引擎、官方插件），CI 每次推送自动跑
- 代码风格检查（ktlint）和 `.editorconfig`
- 贡献指南、架构说明、Issue / PR 模板

### 变更
- 插件调用改为自己的事件循环，一个慢调用不再挡住其它调用
- 播放地址缓存 8 分钟，减少取流请求
- 安卓允许 http（局域网 WebDAV、自建插件）
- 版本号统一由 `Version.kt` 提供，三端打包都从这里读
- `shared` 代码按职责拆成 `core` / `data` / `player` / `ui` 四个子包
- 性能浮窗的掉帧改为每秒统计，桌面版按实际刷新率判断
- macOS：关掉「窗口圆角」时用不透明窗口；JVM 参数改为偏流畅

### 修复
- QQ 音乐插件 1.2.1：扫码后一直没反应（校验值计算丢精度，QQ 返回 403）

## [0.4.0] - 2026-11-21

### 新增
- QQ 音乐、酷狗音乐音源和登录（内置实现，后来在 0.5.0 改成插件）
- 歌曲来源标签、VIP 标记

## [0.3.0] - 2026-11-20

### 新增
- Kotlin + Compose Multiplatform 原生版（安卓 / Windows / macOS / Linux / iOS）
- 手机 / 电脑宽屏两种布局、逐字歌词、评论、桌面歌词、效果器、Discord 状态
- WebDAV 备份和恢复设置、喜欢、最近播放

## [0.2.0]

- Flutter 和安卓原生的试验版（`flutter` / `android-native` 分支）

## [0.1.0]

- 最早的 Tauri 网页版（`tauri-web` 分支）

[未发布]: https://github.com/acnekot/NEKOPLAY/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/acnekot/NEKOPLAY/releases/tag/v0.5.0
