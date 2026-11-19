# NEKOPLAY（原生版）

BenchLab 风格的网易云音乐播放器，Kotlin + Jetpack Compose + Media3。

- 网易云 weapi 直连：登录（扫码 / Cookie）、歌单、每日推荐、私人 FM、逐字歌词、评论、排行榜、歌单广场、MV、播客等
- 播放缓存（1GB LRU）、音质选择、播放记录回传
- 自定义背景、主题（BenchLab / 无边框）、首页布局调整

构建：`./gradlew assembleRelease`（需要 Android SDK，compileSdk 36）
