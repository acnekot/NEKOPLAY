package cc.catsuki.nekoplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class PerfStat(
    val cpu: Float = 0f,
    val cpuPeak: Float = 0f,
    val fps: Int = 0,
    val frameMs: Float = 0f,
    val gpuMs: Float = 0f,
    val jank: Int = 0,
    val gpu: String? = null,
    val pss: Long = 0,
    val heapMb: Long = 0,
    val coldStartMs: Long = 0,
)

/** 性能监测：每秒统计本进程 CPU、实际渲染帧数、每帧耗时和 GPU 耗时（系统 FrameMetrics）、卡顿帧、内存（RSS/堆）与冷启动耗时 */
object Perf {
    val stat = MutableStateFlow(PerfStat())
    val overlay = MutableStateFlow(false)
    private var frames = 0; private var totalNs = 0L; private var gpuNs = 0L; private var jank = 0
    private var lastTicks = -1L; private var lastAt = 0L; private var peak = 0f
    private var launchNs = 0L
    @kotlin.concurrent.Volatile var coldStartMs: Long = 0L
        private set
    private val cores = Runtime.getRuntime().availableProcessors()
    private val lock = Any()

    /** 进程入口调用：记录冷启动起点 */
    fun markLaunch() { if (launchNs == 0L) launchNs = System.nanoTime() }

    /** 首页首帧绘制完成后调用：记录冷启动耗时（毫秒） */
    fun markHomeReady() {
        if (coldStartMs == 0L && launchNs != 0L) {
            coldStartMs = ((System.nanoTime() - launchNs) / 1_000_000L).coerceAtLeast(1L)
        }
    }

    /** 平台每渲染一帧调用一次：总耗时、GPU 耗时、是否超时（卡顿） */
    fun frame(totalNs: Long, gpuNs: Long, late: Boolean) {
        synchronized(lock) { frames++; this.totalNs += totalNs; this.gpuNs += gpuNs; if (late) jank++ }
    }

    fun start() = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        while (true) {
            delay(1000)
            var cpu = 0f
            runCatching {
                val s = File("/proc/self/stat").readText()
                val f = s.substring(s.lastIndexOf(')') + 2).split(' ')
                val ticks = f[11].toLong() + f[12].toLong()
                val now = System.nanoTime()
                if (lastTicks >= 0) cpu = (ticks - lastTicks) / 100f / ((now - lastAt) / 1e9f) / cores * 100
                lastTicks = ticks; lastAt = now
            }
            if (cpu > peak) peak = cpu
            var pss = runCatching { File("/proc/self/smaps_rollup").readLines().first { it.startsWith("Pss:") }.split(Regex("\\s+"))[1].toLong() / 1024 }.getOrDefault(0)
            // 桌面版（Windows / macOS 没有 /proc）：用 JVM 的系统接口
            runCatching { Plat.impl.cpuMem() }.getOrNull()?.let { (c0, m) -> cpu = c0; pss = m }
            if (cpu > peak) peak = cpu
            val heap = runCatching { Plat.impl.heapMb() }.getOrDefault(0L)
            synchronized(lock) {
                stat.value = PerfStat(cpu, peak, frames, if (frames == 0) 0f else totalNs / frames / 1e6f, if (frames == 0) 0f else gpuNs / frames / 1e6f, jank, gpu(), pss, heap, coldStartMs)
                frames = 0; totalNs = 0; gpuNs = 0; jank = 0   // 掉帧数按每秒算，不累计
            }
        }
    }

    private val gpuPaths = listOf("/sys/kernel/ged/hal/gpu_utilization", "/sys/class/misc/mali0/device/utilization", "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/kernel/gpu/gpu_busy")
    private fun gpu(): String? {
        for (p in gpuPaths) runCatching { val v = File(p).readText().trim(); if (v.isNotEmpty()) return v.split(Regex("\\s+")).first().replace("%", "") + "%" }
        return null
    }

    fun line(s: PerfStat) = if (Plat.isDesktop) "CPU %.1f%%（峰值 %.0f%%）· %d 帧/秒\n每帧间隔 %.1fms · 掉帧 %d/秒\nRSS %dMB（堆 %dMB）· 启动 %dms".format(s.cpu, s.cpuPeak, s.fps, s.frameMs, s.jank, s.pss, s.heapMb, s.coldStartMs)
        else "CPU %.1f%%（峰值 %.0f%%）· %d 帧/秒\n每帧间隔 %.1fms · GPU %.1fms · 掉帧 %d/秒\n内存 %dMB · 启动 %dms · GPU %s".format(s.cpu, s.cpuPeak, s.fps, s.frameMs, s.gpuMs, s.jank, s.pss, s.coldStartMs, s.gpu ?: "不可读")
}
