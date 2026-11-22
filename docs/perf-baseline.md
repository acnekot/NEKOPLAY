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

| 指标 | 基线（0.6.0 前） | 0.6.0 优化后实测 | 变化 | 备注 |
| --- | --- | --- | --- | --- |
| 冷启动到首页首帧（`coldStartMs`） | **1160 ms** | **749 ms** | **-35.4%** | 已达成 ≥30% 目标（延后非关键启动 + 插件 caps 缓存按需拉起） |
| 启动瞬间 RSS（t=2s） | **401 MB** | **313 MB** | **-21.9%** | 统一 `SerialGC + C2 + 256M`（避开 ZGC 多视图映射膨胀） |
| 首页稳态空闲 RSS（t=10s） | **540 MB** | **393 MB** | **-27.2%** | 直降 147 MB；最小化 30s 后自动清空 Coil 内存缓存 |
| 首页空闲堆已用（t=10s） | **48 MB**（峰值 148 MB） | **26 MB**（峰值 26 MB） | **-45.8%**（峰值 **-82.4%**） | 按需启动插件 + 空闲 5 分钟自动回收 QuickJS |
| 首页静止空闲 CPU（暂停、未操作） | **1.14% ~ 1.98%** | **0.88% ~ 1.08%** | 下降约 35% | `Engine` 暂停挂起 + `mpv-poll` 降频至 500~800ms |
| `.app` 免安装体积 | **195 MB** | **195 MB** | — | 已移除未使用的 `java.sql` 模块（主体积为 JavaFX + libmpv） |

### 优化后采样序列（`NEKOPLAY_BENCH=1`，`SerialGC + C2 + 256M`）

```text
t=2s  coldStartMs=749 rssMB=313 heapMB=18 cpu=0.00%
t=4s  coldStartMs=749 rssMB=313 heapMB=21 cpu=4.28%
t=6s  coldStartMs=749 rssMB=392 heapMB=25 cpu=1.19%
t=9s  coldStartMs=749 rssMB=393 heapMB=26 cpu=0.88%
t=10s coldStartMs=749 rssMB=393 heapMB=26 cpu=1.08%
```

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
