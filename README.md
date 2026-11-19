# NEKOPLAY

> 旧的 Tauri 网页版在 `tauri-web` 分支。


BenchLab 风格的网易云音乐播放器，Kotlin + Jetpack Compose + Media3。

- 网易云 weapi 直连：登录（扫码 / Cookie）、歌单、每日推荐、私人 FM、逐字歌词、评论、排行榜、歌单广场、MV、播客等
- 播放缓存（1GB LRU）、音质选择、播放记录回传
- 自定义背景、主题（BenchLab / 无边框）、首页布局调整

## 目录

- `shared/` 共用代码：网易云接口、播放队列、歌词、全部界面（手机布局 + PC 宽屏三栏布局，按窗口宽度自动切换）
- `app/` 安卓壳：ExoPlayer 播放、通知栏、播放缓存
- `desktop/` 桌面壳（Windows / macOS / Linux）：Compose Desktop + libmpv 播放

## 构建

- 安卓：`./gradlew assembleRelease`（需要 Android SDK，compileSdk 36）
- 桌面：`cd desktop && ./gradlew run`；打包 `./gradlew packageDistributionForCurrentOS`
  - 播放需要 libmpv：macOS `brew install mpv`，Linux 装 `libmpv2`，Windows 把 `libmpv-2.dll` 放到程序目录
- GitHub Actions 的 desktop 工作流可以一次打出三个系统的安装包
