package cc.catsuki.nekoplay

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
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

    fun attach(w: Window) {
        val t = HandlerThread("perf").apply { start() }
        w.addOnFrameMetricsAvailableListener({ _, m, _ ->
            synchronized(lock) {
                frames++
                val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
                totalNs += total
                if (Build.VERSION.SDK_INT >= 31) gpuNs += m.getMetric(FrameMetrics.GPU_DURATION)
                val deadline = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.DEADLINE) else 16_666_667L
                if (total > deadline) jank++
            }
        }, Handler(t.looper))
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
            val pss = runCatching { File("/proc/self/smaps_rollup").readLines().first { it.startsWith("Pss:") }.split(Regex("\\s+"))[1].toLong() / 1024 }.getOrDefault(0)
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

    fun line(s: PerfStat) = "CPU %.1f%%（峰值 %.0f%%）· %d 帧/秒\n每帧 %.1fms · GPU %.1fms · 卡顿帧累计 %d\nGPU 整机占用 %s".format(s.cpu, s.cpuPeak, s.fps, s.frameMs, s.gpuMs, s.jank, s.gpu ?: "系统不允许读取")
}
