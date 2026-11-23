package cc.catsuki.nekoplay

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock

/**
 * 桌面端单实例锁：同一用户只允许运行一个 NEKOPLAY 进程。
 * 再次启动时通过本地回环端口通知已运行实例把窗口带到前台（含托盘唤醒），然后当前新进程直接退出。
 */
object SingleInstance {
    @Volatile var onWake: (() -> Unit)? = null
    private var raf: RandomAccessFile? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null
    private var portFile: File? = null

    private fun homeDir(): File = File(
        System.getProperty("user.home"),
        if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) "AppData/Roaming/NEKOPLAY" else ".nekoplay",
    ).apply { mkdirs() }

    /** 获取单实例锁；返回 false 表示已有实例在运行（已尝试唤醒它，调用方应立刻退出）。 */
    fun acquire(): Boolean {
        val dir = homeDir()
        val lockFile = File(dir, "instance.lock")
        val pf = File(dir, "instance.port")
        portFile = pf
        val f = runCatching { RandomAccessFile(lockFile, "rw") }.getOrNull() ?: return true
        val l = runCatching { f.channel.tryLock() }.getOrNull()
        if (l == null) {
            runCatching { f.close() }
            wakeExisting(pf)
            return false
        }
        raf = f
        lock = l
        runCatching {
            val srv = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            server = srv
            pf.writeText(srv.localPort.toString())
            Thread({
                while (!srv.isClosed) {
                    val s = runCatching { srv.accept() }.getOrNull() ?: break
                    runCatching {
                        s.soTimeout = 500
                        val line = s.getInputStream().bufferedReader().readLine()
                        s.close()
                        if (line == "SHOW") java.awt.EventQueue.invokeLater { onWake?.invoke() }
                    }
                }
            }, "single-instance").apply { isDaemon = true }.start()
        }
        Runtime.getRuntime().addShutdownHook(Thread { release() })
        return true
    }

    private fun wakeExisting(pf: File) {
        val port = runCatching { pf.readText().trim().toInt() }.getOrNull() ?: return
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 400)
                s.getOutputStream().write("SHOW\n".toByteArray())
                s.getOutputStream().flush()
            }
        }
    }

    fun release() {
        runCatching { server?.close() }; server = null
        runCatching { portFile?.delete() }
        runCatching { lock?.release() }; lock = null
        runCatching { raf?.close() }; raf = null
    }
}
