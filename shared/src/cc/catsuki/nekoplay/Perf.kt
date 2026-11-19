package cc.catsuki.nekoplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class PerfStat(val cpu: Float = 0f, val cpuPeak: Float = 0f, val fps: Int = 0, val frameMs: Float = 0f, val gpuMs: Float = 0f, val jank: Int = 0, val gpu: String? = null, val pss: Long = 0)

/** 性能监测：每秒统计本进程 CPU、实际渲染帧数、每帧耗时和 GPU 耗时（系统 FrameMetrics）、卡顿帧、整机 GPU 占用（能读到时） */
object Perf {
    val stat = MutableStateFlow(PerfStat())
    val overlay = MutableStateFlow(false)
    private var frames = 0; private var totalNs = 0L; private var gpuNs = 0L; private var jank = 0
    private var lastTicks = -1L; private var lastAt = 0L; private var peak = 0f
    private val cores = Runtime.getRuntime().availableProcessors()
    private val lock = Any()

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
            synchronized(lock) {
                stat.value = PerfStat(cpu, peak, frames, if (frames == 0) 0f else totalNs / frames / 1e6f, if (frames == 0) 0f else gpuNs / frames / 1e6f, jank, gpu(), pss)
                frames = 0; totalNs = 0; gpuNs = 0
            }
        }
    }

    private val gpuPaths = listOf("/sys/kernel/ged/hal/gpu_utilization", "/sys/class/misc/mali0/device/utilization", "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/kernel/gpu/gpu_busy")
    private fun gpu(): String? {
        for (p in gpuPaths) runCatching { val v = File(p).readText().trim(); if (v.isNotEmpty()) return v.split(Regex("\\s+")).first().replace("%", "") + "%" }
        return null
    }

    fun line(s: PerfStat) = if (Plat.isDesktop) "CPU %.1f%%（峰值 %.0f%%）· %d 帧/秒\n每帧间隔 %.1fms · 卡顿帧累计 %d\n内存 %dMB".format(s.cpu, s.cpuPeak, s.fps, s.frameMs, s.jank, s.pss)
        else "CPU %.1f%%（峰值 %.0f%%）· %d 帧/秒\n每帧 %.1fms · GPU %.1fms · 卡顿帧累计 %d\nGPU 整机占用 %s".format(s.cpu, s.cpuPeak, s.fps, s.frameMs, s.gpuMs, s.jank, s.gpu ?: "系统不允许读取")
}
