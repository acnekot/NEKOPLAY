# 更新日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。
版本号只在 `shared/src/cc/catsuki/nekoplay/core/Version.kt` 里改；发版时把「未发布」改成版本号和日期，再打 `v版本号` 标签。

## [未发布]

### 新增
- 单元测试（歌词解析、版本比较、md5、WebDAV、插件引擎、官方插件），CI 每次推送自动跑
- 代码风格检查（ktlint）和 `.editorconfig`
- 贡献指南、架构说明、Issue / PR 模板

### 变更
- `shared` 代码按职责拆成 `core` / `data` / `player` / `ui` 四个子包
- 性能浮窗的掉帧改为每秒统计，桌面版按实际刷新率判断
- macOS：关掉「窗口圆角」时用不透明窗口；JVM 参数改为偏流畅

### 修复
- QQ 音乐插件 1.2.1：扫码后一直没反应（校验值计算丢精度，QQ 返回 403）

## [0.5.0] - 2026-11-22

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

### 变更
- 插件调用改为自己的事件循环，一个慢调用不再挡住其它调用
- 播放地址缓存 8 分钟，减少取流请求
- 安卓允许 http（局域网 WebDAV、自建插件）
- 版本号统一由 `Version.kt` 提供，三端打包都从这里读

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

[未发布]: https://github.com/acnekot/NEKOPLAY/compare/main...HEAD
