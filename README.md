# NEKOPLAY（Flutter，iOS + Android 同一套代码）

本地音乐播放器：BenchLab 设计、后台播放、歌词、歌单、音源插件。

## 运行
```
flutter pub get
flutter run                      # 连接手机或模拟器
flutter build apk --release --split-per-abi    # Android
```
iOS（需要 Mac + Xcode）：
```
flutter build ios --release
open ios/Runner.xcworkspace      # Signing & Capabilities 里选你的 Team，改 Bundle Identifier
```
没有 Mac 时可用 `.github/workflows/build.yml`（GitHub Actions）出 APK 与未签名 IPA，
未签名 IPA 用 AltStore / Sideloadly 等自行签名安装。

## 已实现
首页 / 曲库 / 歌单详情 / 搜索 / 设置 / 音源插件 / 播放页（封面⇄歌词飞入、逐行平滑滚动、高亮条、译文开关）/ 队列（3/4 屏）/ 迷你条拖动进度 + 时间气泡 /
本地导入（元数据、封面、同名 .lrc）/ 后台播放与锁屏控制 / 会话恢复 / 淡入淡出 / 深浅色 / 插件沙箱（flutter_js，host 白名单）。
曲库为空时显示示例数据（无音频，使用虚拟时钟）。

## 已知限制
- 无缝播放（gapless）只保存开关，尚未实现。
- 插件不支持 aes/rsa、openWebView（登录只支持表单）。
- 不支持整个文件夹扫描，只能选文件导入。
- 插件运行时（QuickJS / JavaScriptCore）和 iOS 端未在真机验证，iOS 未在本环境编译。
- Android 安装包用 debug 签名，上架前请配置自己的 keystore。
