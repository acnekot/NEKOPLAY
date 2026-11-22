# NEKOPLAY 性能与资源基准（0.6.0 前基线）

- **采集时间**：2026-11-22
- **基线提交**：`4460b7e` + Perf 埋点（冷启动 / RSS / 堆内存）
- **采集方法**：
  - 桌面端：`NEKOPLAY_BENCH=1 /Applications/NEKOPLAY.app/Contents/MacOS/NEKOPLAY`（输出至 `~/.nekoplay/perf-bench.log`）
  - 指标口径：
    - `coldStartMs`：进程入口 `Perf.markLaunch()` 到 `Root` 首页首帧 `withFrameNanos { Perf.markHomeReady() }`
    - `rssMB`：进程真实常驻内存（macOS `ps -o rss=` / Linux `/proc/self/status VmRSS` / Windows `WorkingSet`）
    - `heapMB`：JVM `totalMemory - freeMemory`
    - `cpu`：`OperatingSystemMXBean.processCpuLoad * 100`

## 1. macOS 基准（Apple Silicon arm64 / macOS 27.2.1 / 16 GB RAM）

> JVM 参数：`-Xms96m -Xmx384m -XX:+UseZGC -XX:+ZGenerational -XX:ReservedCodeCacheSize=96m -XX:MaxMetaspaceSize=128m`

| 指标 | 基线（0.6.0 前） | 0.6.0 目标 | 备注 |
| --- | --- | --- | --- |
| 冷启动到首页首帧（`coldStartMs`） | **1160 ms** | ≤ 812 ms（-30%） | 启动期同步执行 `Plugins.load()` / `DavMusic` / `Updater` / `Store.refreshAll` |
| 首页空闲 RSS（t=10s） | **540 MB** | ≤ 378 MB（-30%） | 启动瞬间 401 MB，t=5s 拉满插件与缓存后涨至 542 MB |
| 首页空闲堆已用（t=10s） | **48 MB**（峰值 148 MB） | — | ZGC 回收后回落至 48 MB，但 RSS 未明显归还 |
| 首页静止空闲 CPU（暂停、未操作） | **1.14% ~ 1.98%** | 最小化/暂停时 ≈ 0% | 仍存在 `Bg` 轮询、`mpv-poll`、协程定时唤醒 |
| `.app` 免安装体积 | **195 MB** | 精简 jlink 模块 | 含 Temurin JDK 21 runtime + JavaFX + libmpv |

### 原始采样序列（`NEKOPLAY_BENCH=1`）

```text
t=2s  coldStartMs=1160 rssMB=401 heapMB=112 cpu=0.00%
t=3s  coldStartMs=1160 rssMB=401 heapMB=56  cpu=3.49%
t=4s  coldStartMs=1160 rssMB=401 heapMB=136 cpu=4.40%
t=5s  coldStartMs=1160 rssMB=542 heapMB=146 cpu=2.31%
t=8s  coldStartMs=1160 rssMB=540 heapMB=148 cpu=1.14%
t=10s coldStartMs=1160 rssMB=540 heapMB=48  cpu=1.22%
```

## 2. 代码结构基准（`shared/src`）

| 指标 | 基线（0.6.0 前） | 0.6.0 目标 |
| --- | --- | --- |
| 共享 `.kt` 文件数 / 总行数 | 38 文件 / 8,610 行 | 按 `core / data / player / ui` 单向分层 |
| 超过 400 行的文件数 | **6 个**（`Screens` 880 / `People` 792 / `Player` 759 / `Ui` 562 / `Root` 468 / `Wide` 407） | **0 个**（单文件 ≤ 400 行） |
| 逻辑文件混写 `@Composable` | 7 个核心逻辑文件（`Backup` / `Features` / `Debug` / `Dav` / `Download` / `Stats` / `LocalMusic`）+ `Fx` / `Sleep` / `DeskLyric` 等 | **0 个**（全部迁至 `ui/`） |
| 全局 `object` 单例数 | 49 个 | 核心有状态服务收进 `AppGraph` |
| 反向依赖（`core/data/player → ui`） | 待分包后由 `ArchTest` 精确计数 | **0 处**（CI 守门） |

## 3. Windows / Linux / Android / iOS 基准

> 待在对应真机或 CI 跑完同一剧本后补充。
