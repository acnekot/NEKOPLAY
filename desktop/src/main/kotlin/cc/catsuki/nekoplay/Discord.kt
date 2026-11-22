package cc.catsuki.nekoplay


import cc.catsuki.nekoplay.core.*
import cc.catsuki.nekoplay.data.*
import cc.catsuki.nekoplay.player.*
import cc.catsuki.nekoplay.ui.*
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.UUID

/**
 * Discord Rich Presence：通过本机 Discord 客户端的 IPC（Windows 命名管道 / macOS、Linux 的 Unix socket）
 * 发 SET_ACTIVITY，显示「正在听」歌名、歌手、封面和进度。需要一个 Discord 应用的 Client ID。
 */
object DiscordRpc {
    private interface Conn { fun write(b: ByteArray); fun read(n: Int): ByteArray; fun close() }
    private var conn: Conn? = null
    private var connectedId = ""
    private var lastKey = ""

    fun start() {
        Thread {
            while (true) {
                Thread.sleep(2000)
                runCatching { tick() }.onFailure { close(); DiscordPref.status = "未连接：${it.message}" }
            }
        }.apply { isDaemon = true; name = "discord-rpc" }.start()
    }

    private fun tick() {
        val id = DiscordPref.clientId.trim()
        if (!DiscordPref.on || !Features.on("discord") || id.isEmpty()) { if (conn != null) { clear(); close() }; DiscordPref.status = if (DiscordPref.on) "请先填 Client ID" else ""; return }
        if (conn == null || connectedId != id) { close(); connect(id) }
        val st = Engine.state.value; val t = st.current
        val pos = Engine.position.value
        // 歌、播放状态变了，或者进度跳了（拖动）才更新
        val key = "${t?.id}|${st.playing}|${pos / 5000}"
        if (key == lastKey && st.playing) return
        if (t == null || !st.playing) { if (lastKey != "idle") { clear(); lastKey = "idle" }; return }
        lastKey = key
        val now = System.currentTimeMillis(); val start = now - pos
        val act = JSONObject().put("type", 2).put("details", t.title.take(120)).put("state", t.artist.take(120))
            .put("timestamps", JSONObject().put("start", start).apply { if (t.durationMs > 0) put("end", start + t.durationMs) })
            .put("assets", JSONObject().apply { t.cover?.let { put("large_image", "$it?param=300y300") }; put("large_text", t.album.ifEmpty { "NEKOPLAY" }.take(120)) })
        send(1, JSONObject().put("cmd", "SET_ACTIVITY").put("args", JSONObject().put("pid", ProcessHandle.current().pid()).put("activity", act)).put("nonce", UUID.randomUUID().toString()))
        DiscordPref.status = "已连接"
    }

    private fun clear() = runCatching {
        send(1, JSONObject().put("cmd", "SET_ACTIVITY").put("args", JSONObject().put("pid", ProcessHandle.current().pid())).put("nonce", UUID.randomUUID().toString()))
    }

    private fun connect(id: String) {
        val win = System.getProperty("os.name").lowercase().contains("win")
        var c: Conn? = null
        for (i in 0..9) {
            c = runCatching {
                if (win) {
                    val f = RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw")
                    object : Conn { override fun write(b: ByteArray) = f.write(b); override fun read(n: Int) = ByteArray(n).also { f.readFully(it) }; override fun close() = f.close() }
                } else {
                    val dirs = listOfNotNull(System.getenv("XDG_RUNTIME_DIR"), System.getenv("TMPDIR"), System.getenv("TMP"), "/tmp").flatMap { listOf(it, "$it/app/com.discordapp.Discord", "$it/snap.discord") }
                    val path = dirs.map { File(it, "discord-ipc-$i") }.firstOrNull { it.exists() } ?: error("no socket")
                    val ch = SocketChannel.open(UnixDomainSocketAddress.of(path.toPath()))
                    object : Conn {
                        override fun write(b: ByteArray) { val bb = ByteBuffer.wrap(b); while (bb.hasRemaining()) ch.write(bb) }
                        override fun read(n: Int): ByteArray { val bb = ByteBuffer.allocate(n); while (bb.hasRemaining()) if (ch.read(bb) < 0) error("closed"); return bb.array() }
                        override fun close() = ch.close()
                    }
                }
            }.getOrNull()
            if (c != null) break
        }
        conn = c ?: error("没找到 Discord（先打开 Discord 客户端）")
        send(0, JSONObject().put("v", 1).put("client_id", id))
        connectedId = id; lastKey = ""
    }

    private fun send(op: Int, o: JSONObject) {
        val c = conn ?: return
        val body = o.toString().toByteArray()
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(body.size).array()
        c.write(head + body)
        // 读回复（READY / 命令结果），不读的话管道会塞满
        val h = ByteBuffer.wrap(c.read(8)).order(ByteOrder.LITTLE_ENDIAN)
        val rop = h.int; val len = h.int
        val resp = String(c.read(len))
        if (rop == 2) error("Discord 断开：$resp")
        if (resp.contains("\"evt\":\"ERROR\"")) DiscordPref.status = "出错：" + (runCatching { JSONObject(resp).getJSONObject("data").optString("message") }.getOrNull() ?: resp.take(80))
    }

    private fun close() { runCatching { conn?.close() }; conn = null; connectedId = "" }
}
