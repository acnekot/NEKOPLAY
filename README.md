<p align="center">
  <img src="design/icon/full.png" width="128" alt="NEKOPLAY 图标">
</p>

<h1 align="center">NEKOPLAY</h1>

<p align="center">
  BenchLab 风格的音乐播放器 · Kotlin + Compose Multiplatform<br>
  安卓 / Windows / macOS / Linux / iOS 共用一套界面
</p>

<p align="center">
  <img alt="版本" src="https://img.shields.io/badge/版本-0.6.6-1f1f23">
  <img alt="平台" src="https://img.shields.io/badge/平台-Android%20·%20Windows%20·%20macOS%20·%20Linux%20·%20iOS-1f1f23">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Compose%20Multiplatform-7F52FF">
  <a href="https://nekoplay.catsuki.cc"><img alt="官网" src="https://img.shields.io/badge/官网-nekoplay.catsuki.cc-1A4BE0"></a>
</p>

---

## 简介

NEKOPLAY 是一个**本地音乐播放器**：播放你自己设备上或自己网盘（WebDAV）里的音乐，喜欢和歌单都存在本机，不需要账号。

软件本身**不提供、不存储、不分发任何音乐内容**，也不内置任何在线音乐服务。
如需使用在线服务，可以自行导入插件（JS 脚本）；插件以你的身份（未登录，或登录你自己的账号）访问对应服务，能获取的内容以该服务对你开放的权限为准。详见下方[插件说明](#插件)和[免责声明](#免责声明)。

## 界面预览

<table>
  <tr>
    <td align="center" width="38%">
      <img src="docs/demo/desktop-home-light.png" alt="桌面端浅色主页" width="100%">
      <br><sub><b>桌面端 · 宽屏主页（浅色）</b></sub>
    </td>
    <td align="center" width="38%">
      <img src="docs/demo/desktop-player-dark.png" alt="桌面端深色播放与歌词页" width="100%">
      <br><sub><b>桌面端 · 全屏播放与歌词（深色）</b></sub>
    </td>
    <td align="center" width="24%">
      <img src="docs/demo/mobile-home.png" alt="移动端自适应布局" width="76%">
      <br><sub><b>移动端 · 响应式布局</b></sub>
    </td>
  </tr>
</table>

## 功能

**本体**
- 本地音乐：手机读媒体库，电脑扫「音乐」文件夹，同名 `.lrc` 当歌词
- WebDAV 音乐：直接浏览网盘文件夹播放，文件名「歌手 - 歌名」自动拆开
- 本机喜欢 / 本地歌单：不用登录；本地歌和没登录的来源点喜欢都记在这里
- 播放：1GB 播放缓存、音质选择、无缝切歌、定时关闭、10 段均衡器与音效
- 歌词：逐字浮光 / 平滑跟进 / 大字流动多风格切换、歌词样式 JSON 导入导出、双语翻译、歌词偏移、可高度自定义的桌面悬浮歌词（PC）、歌词卡片
- 界面与个性化：手机布局和 PC 宽屏三栏布局按窗口宽度自动切换；内置主题系统（制作 / 导入 / 导出 JSON）、可视化贝塞尔曲线动效编辑器、动态取色、自定义背景、首页模块自由排序与曲库专辑卡片架
- 数据与维护：听歌统计、下载、WebDAV / 本地配置备份（包括插件脚本）、启动自动检查更新

<a id="插件"></a>
**插件**

插件是运行在软件里的 JS 脚本，可在 设置 → 插件 中从文件、链接或订阅导入；接口见 [docs/plugin-api.md](docs/plugin-api.md)。

- 本仓库 `plugins/` 目录下的脚本是**接口示例**，演示插件如何对接第三方服务的公开网页接口；它们不是相关服务的官方客户端，也未获得其授权或认可
- 插件只按服务对你（未登录或你的账号）开放的权限获取内容：不破解、不绕过付费 / 会员 / 试听限制，也不去除任何版权保护措施
- 第三方服务的接口可能随时变化，示例插件不保证可用；使用任何插件前，请先阅读并遵守对应服务的用户协议
- 插件由使用者自行选择导入，第三方插件的行为由其作者负责

## 下载

正式版在 [Releases](https://github.com/acnekot/NEKOPLAY/releases) 下载；测试版在 GitHub Actions 的「打包全部版本」工作流里：

| 平台 | 文件 |
|---|---|
| 安卓 | `NEKOPLAY-android-arm64.apk`（大部分手机）/ `armv7` |
| Windows | `.msi` / `.exe` 安装包，或免安装 zip |
| macOS | `.dmg` |
| Linux | `.deb` |
| iOS | 未签名 `.ipa`，需自行签名安装 |

Windows / macOS 安装包目前**没有代码签名**，系统可能提示「无法验证发布者」或拦截运行（例如 Windows 的智能应用控制）。请只从本仓库的 Releases 下载。

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
- 版本号只在 `shared/src/cc/catsuki/nekoplay/Version.kt` 改，三端打包都从这里读
  - Windows / macOS 安装包要求主版本号 ≥ 1，安装包元数据用 `1.次版本.修订`（0.5.0 → 1.5.0），程序里显示真实版本号

## 版本历史

| 版本 | 分支 | 内容 |
|---|---|---|
| 0.6.6 | `main` | 色彩主题、边框几何、动效曲线与空间微交互四层彻底解耦，全局动效与配色令牌规范化收口 |
| 0.6.5 | `main` | 多风格逐字歌词引擎、自定义主题系统、可视化贝塞尔动效曲线编辑器、桌面歌词增强、曲库专辑架、启动自动检查更新与多端性能优化 |
| 0.5.0 | `main` | 插件系统和插件订阅；本体去掉内置音源（干湿分离）；本机喜欢、本地歌单、WebDAV 音乐 |
| 0.4.0 | `DEV` | 多个在线服务的内置对接（0.5.0 起改为可选插件）；歌曲来源标签 |
| 0.3.0 | — | Kotlin + Compose 原生版：手机 / PC 双布局、逐字歌词、评论、桌面歌词、效果器 |
| 0.2.0 | `flutter` / `android-native` | Flutter 和安卓原生的试验版 |
| 0.1.0 | `tauri-web` | 最早的 Tauri 网页版 |

## 第三方组件

本软件使用或在安装包中附带了以下开源组件，它们各自遵循自己的许可证：

| 组件 | 用途 | 许可证 |
|---|---|---|
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform)、Kotlin、kotlinx.coroutines | 界面和运行时 | Apache-2.0 |
| [AndroidX Media3（ExoPlayer）](https://github.com/androidx/media) | 安卓播放 | Apache-2.0 |
| [mpv / libmpv](https://mpv.io/) | 桌面播放（随 Windows / macOS / Linux 安装包附带） | GPL-2.0+ 或 LGPL-2.1+（取决于所用构建） |
| [quickjs-kt](https://github.com/dokar3/quickjs-kt)（内含 [QuickJS](https://bellard.org/quickjs/)） | 插件引擎 | Apache-2.0（QuickJS 为 MIT） |
| [webview](https://github.com/webview/webview) | Windows 内嵌网页 | MIT |
| [OpenJFX](https://openjfx.io/) | macOS / Linux 内嵌网页 | GPL-2.0 with Classpath Exception |
| [OkHttp](https://github.com/square/okhttp)、[Coil](https://github.com/coil-kt/coil)、[ZXing](https://github.com/zxing/zxing) | 网络、图片、二维码 | Apache-2.0 |
| [JNA](https://github.com/java-native-access/jna) | 调用系统库 | Apache-2.0 / LGPL-2.1 |
| [JSON-java](https://github.com/stleary/JSON-java) | JSON 解析 | Public Domain |
| [Inter](https://rsms.me/inter/)、[JetBrains Mono](https://www.jetbrains.com/lp/mono/) | 字体 | SIL OFL 1.1 |

部分示例插件中的接口参数参考了社区开源项目（MIT License），对应版权声明保留在各脚本文件开头。

## 软件许可协议

本项目采用 **保留全部所有权（All Rights Reserved）** 协议，详见 [LICENSE](LICENSE)：

- 本软件的全部源代码、设计、资源与成品所有权均归作者所有，**不采用任何开源许可证**
- 仅允许通过官方 Releases 下载并用于**个人非商业使用**
- **未经作者书面授权，禁止 Fork 二次开发、修改、重新打包、分发或用于任何商业用途**，作者亦不提供任何 Fork 或二开支持
- 所使用的第三方开源组件继续遵循其各自许可证（见上表）

## 免责声明

- 本项目为个人学习和技术研究用途的非营利项目，不以营利为目的，与任何在线音乐服务及其运营公司**无关联、未获授权、未获认可**
- 文中提到的产品名称和商标归各自所有者所有，仅用于说明兼容性
- 软件本身不提供、不存储、不分发任何音乐、歌词、封面等受版权保护的内容；通过插件获取的内容均来自相关服务对用户本人开放的接口，版权归相关权利人所有
- 请在遵守所在地法律法规和相关服务用户协议的前提下使用；因使用本软件或第三方插件产生的任何后果由使用者自行承担
- 如果你是相关权利人，认为本仓库的内容侵犯了你的权益，请通过 [Issues](https://github.com/acnekot/NEKOPLAY/issues) 联系，我们会尽快核实并处理（包括删除相关内容）
