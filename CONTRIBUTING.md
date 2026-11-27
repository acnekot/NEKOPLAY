# 贡献指南

## 环境

- JDK 21
- Android SDK（compileSdk 37），用于安卓模块
- macOS + Xcode，只有编 iOS 时需要
- Node.js 和 Python 3，用于检查插件脚本和生成订阅列表

## 目录

```
shared/src/cc/catsuki/nekoplay/   三端共用的代码
  core/     平台接口、版本号、调试、性能、功能开关、检查更新、备份
  data/     数据模型、插件引擎和订阅、本地库、WebDAV、下载、统计
  player/   播放引擎、效果器、歌词、私人 FM、桌面歌词、播放页
  ui/       导航和根布局、通用组件、各页面、首页、插件管理
app/        安卓壳（ExoPlayer、通知、WebView）
desktop/    桌面壳（Compose Desktop、libmpv、WebView2 / JavaFX）和单元测试
ios/        iOS 壳（AVPlayer、iOS 缺的 JVM 类的替代实现）
plugins/    官方音源插件和订阅列表 index.json
docs/       插件接口、架构说明
```

更详细的模块关系见 [docs/architecture.md](docs/architecture.md)。

## 分支

| 分支 | 用途 |
|---|---|
| `main` | 主线，随时可以打包；测试版（nightly）从这里出 |
| 功能分支 | 从 `main` 拉出来，名字写清楚做什么，比如 `feat/visualizer`、`fix/qq-login`；做完提 PR 合回 `main` |
| `PLUGIN` | 历史分支，目前和 `main` 保持一致 |
| `DEV` | 历史分支，不再更新 |

不要直接往 `main` 推没编译过的代码；改动大的功能先开分支。

## 提交说明

一行标题说清楚改了什么，必要时空一行再写细节。用中文，格式：

```
范围：做了什么

- 细节 1
- 细节 2
```

范围用模块或功能名，比如 `QQ 音乐插件 1.2.1：…`、`播放页：…`、`CI：…`。一次提交只做一件事，重组目录、格式化这类大改动单独提交。

## 提交前检查

```bash
# 桌面编译 + 单元测试 + 代码风格（shared 跟着桌面模块一起编译）
cd desktop && ./gradlew compileKotlin test ktlintCheck

# 安卓编译
./gradlew :app:compileReleaseKotlin

# iOS 编译（只能在 macOS 上，CI 打包时会编）
cd ios && ./gradlew compileKotlinIosArm64

# 插件脚本语法
for f in plugins/*.js; do node --check "$f"; done
```

推送后 GitHub Actions 的「检查」工作流会在 Linux 上自动跑上面除 iOS 外的检查。

代码风格不对时，`./gradlew ktlintFormat` 能自动修好大部分；规则在 `.editorconfig`。

## 代码约定

- 平台相关的能力放进 `core/Plat.kt` 的 `PlatImpl` 接口，由三个壳各自实现；共用代码里不要直接写某个平台的 API
- 共用代码也会在 iOS 上编译，iOS 没有的 JVM 类在 `ios/src/iosArm64Main` 里有替代实现。用新的 JVM 类之前先确认 iOS 那边有没有
- 共用代码里不要通配导入 `kotlinx.coroutines.*`：iOS 上 `Dispatchers.IO` 是根包补的，会撞名
- 新增的纯逻辑（解析、计算、协议）写单元测试，放在 `desktop/src/test/kotlin`
- 注释写「为什么」，不写「做了什么」

## 插件

- 接口文档：[docs/plugin-api.md](docs/plugin-api.md)
- 改了插件要升 `@version`，然后运行 `python3 plugins/gen-index.py` 更新 `index.json`（CI 会检查两者是否一致）
- 插件里不要放账号、密钥；登录信息用 `nk.store` 存在用户本机

## 代码规范检查（`archCheck`）

提交前在 `desktop/` 下运行 `./gradlew archCheck test`，CI 也会跑同样的检查：

- `shared/src` 里每个 `.kt` 文件不超过 400 行，核心层（`Engine` / `Plugins` / `Store` 等）不能依赖界面代码
- **单行不超过 200 字符**、**`T("…")` 里的中文要包 `tr()`**：存量记在 `shared/arch-baseline.txt`，每个文件的数量只能减少不能增加；清理了存量后运行 `./gradlew archBaseline` 更新基线

## 版本和发布

1. 改 `shared/src/cc/catsuki/nekoplay/Version.kt` 里的 `APP_VERSION`
2. 把 `CHANGELOG.md` 的「未发布」改成版本号和日期
3. 提交后打标签并推送：`git tag v0.6.0 && git push origin v0.6.0`
4. 「打包全部版本」工作流会自动打包并发布 GitHub Release，软件里「正式版」通道就能检测到

不打标签时，在 Actions 里手动运行「打包全部版本」并选 `main`，就是测试版（nightly）。

## Microsoft Store 版

商店版与直装版是同一份代码，打包时用环境变量 `NEKOPLAY_CHANNEL=store` 切换渠道（见 `shared/src/cc/catsuki/nekoplay/Edition.kt`）：

- 首次启动**不默认订阅任何插件源**，由用户自行添加；不内置任何音源插件
- 关闭应用内检查更新，设置页显示「由 Microsoft Store 自动更新」
- 「打包全部版本」工作流在 Windows 上会额外产出 `store-msix` 构建产物（`NEKOPLAY-store-x64.msix`，未签名，上传 Partner Center 后由微软签名），**不会**进入 GitHub Release
- MSIX 身份信息在仓库 **Variables**（不是 Secrets）里配置，值取自 Partner Center → 产品管理 → 产品标识：
  - `MSSTORE_IDENTITY_NAME`：Package/Identity/Name
  - `MSSTORE_PUBLISHER`：Package/Identity/Publisher（形如 `CN=XXXXXXXX-XXXX-…`）
  - `MSSTORE_PUBLISHER_DISPLAY`：Package/Properties/PublisherDisplayName
  - 未配置时用占位身份打包，只能验证结构，不能上传
- 商店要求版本号单调递增：MSIX 版本为 `1.次版本.修订.0`（与 msi / exe 相同），同一版本号只能提交一次，因此只用正式版提交商店
- 隐私政策页面：`docs/privacy.html`（官网 `/privacy.html`），设置 → 关于 中有入口

## 安全

- 不要提交 `*.keystore`、`*.jks`、`local.properties` 等密钥和本机配置（`.gitignore` 已经排除）
- 安卓正式签名用仓库 Secrets（`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASS` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASS`），见 `.github/workflows/build.yml`
  - 缺少密钥时打包直接失败；打包后会校验 APK 证书 SHA-256 指纹必须是 `e1b0261b…87ea9d`（完整值见工作流），防止误用每次都不同的 debug 签名导致用户无法覆盖升级
  - 密钥库原件只保存在维护者本机并离线备份，**丢失后将永远无法发布可覆盖升级的安卓版本**，切勿提交到仓库
