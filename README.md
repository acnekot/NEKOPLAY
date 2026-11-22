<p align="center">
  <img src="design/icon/full.png" width="128" alt="NEKOPLAY 图标">
</p>

<h1 align="center">NEKOPLAY</h1>

<p align="center">
  BenchLab 风格的音乐播放器 · Kotlin + Compose Multiplatform<br>
  安卓 / Windows / macOS / Linux / iOS 共用一套界面
</p>

<p align="center">
  <img alt="版本" src="https://img.shields.io/badge/版本-0.5.0-1f1f23">
  <img alt="平台" src="https://img.shields.io/badge/平台-Android%20·%20Windows%20·%20macOS%20·%20Linux%20·%20iOS-1f1f23">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Compose%20Multiplatform-7F52FF">
</p>

---

## 简介

NEKOPLAY 本体只负责播放、界面和本地功能，**在线音源全部做成 JS 插件**，需要哪个导入哪个。
没有任何插件时，它就是一个本地音乐 / WebDAV 网盘播放器，喜欢和歌单都存在本机，不需要账号。

## 功能

**本体**
- 本地音乐：手机读媒体库，电脑扫「音乐」文件夹，同名 `.lrc` 当歌词
- WebDAV 音乐：直接浏览网盘文件夹播放，文件名「歌手 - 歌名」自动拆开
- 本机喜欢 / 本地歌单：不用登录；本地歌和没登录的来源点喜欢都记在这里
- 播放：1GB 播放缓存、音质选择、无缝切歌、定时关闭、效果器 / 均衡器
- 歌词：逐字歌词、翻译、歌词偏移、桌面歌词（PC）、歌词卡片
- 界面：手机布局和 PC 宽屏三栏布局按窗口宽度自动切换；主题、取色、自定义背景、首页模块自由排序和并排组合
- 数据：听歌统计、下载、WebDAV / 本地配置备份（包括插件脚本）

**插件**（`plugins/` 目录；在 设置 → 插件 的「订阅」里一键安装 / 更新，也可以导入文件或链接）

| 插件 | 能力 |
|---|---|
| `netease.js` 网易云音乐 | 搜索、播放、逐字歌词、日推、私人 FM、歌单广场、排行榜、新碟、评论、MV、电台、云盘、扫码 / Cookie 登录 |
| `qqmusic.js` QQ 音乐 | 搜索、播放、歌词、歌单、专辑、歌手、排行榜；网页一键登录（拉起手机 QQ）、QQ 扫码、微信扫码 |
| `kugou.js` 酷狗音乐 | 搜索、播放、歌词、歌单、专辑、歌手、排行榜、扫码登录 |

- 首页来源可以选一个插件，也可以选「混合」；首页模块按插件实现了哪些功能自动显示
- 列表里的歌会标出来自哪个插件
- 插件订阅：默认带官方订阅（`plugins/index.json`），也可以添加别人的订阅地址
- 自己写插件、做订阅：见 [docs/plugin-api.md](docs/plugin-api.md)

## 下载

在 GitHub Actions 的「打包全部版本」工作流里下载对应平台的产物：

| 平台 | 文件 |
|---|---|
| 安卓 | `NEKOPLAY-android-arm64.apk`（大部分手机）/ `armv7` |
| Windows | `.msi` / `.exe` 安装包，或免安装 zip |
| macOS | `.dmg` |
| Linux | `.deb` |
| iOS | 未签名 `.ipa`，用 AltStore / Sideloadly / 巨魔自签安装 |

## 目录

```
shared/    共用代码：core（平台接口）/ data（数据和插件）/ player（播放）/ ui（界面）
app/       安卓壳：ExoPlayer 播放、通知栏、播放缓存、内嵌网页登录
desktop/   桌面壳：Compose Desktop + libmpv 播放、内嵌网页；单元测试也在这里
ios/       iOS 壳：AVPlayer 播放
plugins/   音源插件脚本和订阅列表
docs/      插件接口、架构说明
design/    图标源文件
```

开发流程、分支和提交规范见 [CONTRIBUTING.md](CONTRIBUTING.md)，模块关系见 [docs/architecture.md](docs/architecture.md)，版本变化见 [CHANGELOG.md](CHANGELOG.md)。

## 构建

- 检查：`cd desktop && ./gradlew compileKotlin test ktlintCheck`（编译 + 单元测试 + 代码风格），推送后 CI 也会自动跑
- 安卓：`./gradlew assembleRelease`（需要 Android SDK，compileSdk 37）
- 桌面：`cd desktop && ./gradlew run`；打包 `./gradlew packageDistributionForCurrentOS`
  - 播放需要 libmpv：macOS `brew install mpv`，Linux 装 `libmpv2`，Windows 把 `libmpv-2.dll` 放到程序目录
- iOS：在 macOS 上 `cd ios && ./gradlew linkReleaseExecutableIosArm64 && ./package.sh`
- 版本号只在 `shared/src/cc/catsuki/nekoplay/core/Version.kt` 改，三端打包都从这里读
  - Windows / macOS 安装包要求主版本号 ≥ 1，安装包元数据用 `1.次版本.修订`（0.5.0 → 1.5.0），程序里显示真实版本号

## 版本历史

| 版本 | 分支 | 内容 |
|---|---|---|
| 0.5.0 | `main` | 插件系统和插件订阅；本体去掉内置音源（干湿分离）；本机喜欢、本地歌单、WebDAV 音乐 |
| 0.4.0 | `DEV` | 加入 QQ 音乐、酷狗音源和登录；歌曲来源标签；VIP 标记 |
| 0.3.0 | — | Kotlin + Compose 原生版：手机 / PC 双布局、逐字歌词、评论、桌面歌词、效果器 |
| 0.2.0 | `flutter` / `android-native` | Flutter 和安卓原生的试验版 |
| 0.1.0 | `tauri-web` | 最早的 Tauri 网页版 |

## 致谢

- 酷狗插件的签名算法和接口参数参考 [MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi)（MIT License）
- 插件引擎：[dokar3/quickjs-kt](https://github.com/dokar3/quickjs-kt)
- 播放内核：[ExoPlayer / Media3](https://developer.android.com/media/media3)、[mpv](https://mpv.io/)

本项目仅供学习交流，音乐版权归各平台和权利人所有。
