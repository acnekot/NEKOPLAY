package cc.catsuki.nekoplay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 演示录制（REC 分支）：用离屏 Compose 场景把真实界面一帧帧画出来，虚拟时钟每帧固定前进 1/fps 秒，
 * 所以不管电脑画一帧要多久，成片都是严格的帧率；鼠标移动 / 点击 / 滚轮按脚本时间线精确执行。
 *
 * 触发：程序目录下有 rec/script.txt 就进入录制模式（不开窗口），帧输出到 rec/frames/，录完把脚本改名为 script.done。
 *
 * 脚本格式（一行一条，# 后是注释；坐标是逻辑像素，按 size 里的布局尺寸）：
 *   size 1920 1080 2          布局宽 高 缩放（输出 = 宽×缩放）
 *   fps 120 / duration 30
 *   play liked "Lament Rain" 87660     录制前：从「我喜欢」里找歌名包含这段文字的歌开始播放，并跳到这个毫秒位置
 *   cursor 1500 700           鼠标初始位置
 *   <秒> move x y 时长        鼠标平滑移过去（缓动）
 *   <秒> click [x y 时长]     （先移过去再）单击
 *   <秒> scroll 格数 时长     在鼠标位置滚轮，正数向下
 *   <秒> key back|settings|player|search    返回 / 设置 / 播放页 / 搜索
 */
object Rec {
    @Volatile var vtMs = 0L        // 虚拟时间（毫秒）
    val dir: File? get() = System.getProperty("compose.application.resources.dir")?.let { File(it).parentFile?.parentFile?.resolve("rec") }
        ?: System.getenv("NEKOPLAY_REC")?.let { File(it) }
    fun script(): File? = dir?.resolve("script.txt")?.takeIf { it.isFile }

    private sealed class Ev(val t: Double)
    private class Move(t: Double, val x: Float, val y: Float, val d: Double) : Ev(t)
    private class Click(t: Double, val x: Float?, val y: Float?, val d: Double) : Ev(t)
    private class Scroll(t: Double, val n: Float, val d: Double) : Ev(t)
    private class KeyEv(t: Double, val k: String) : Ev(t)

    private fun log(s: String) { println(s); runCatching { dir!!.resolve("log.txt").appendText("${java.time.LocalTime.now()} $s\n") } }

    @OptIn(ExperimentalComposeUiApi::class)
    fun run(f: File) {
        var lw = 1920; var lh = 1080; var scale = 2f; var fps = 120; var dur = 30.0
        var play: Triple<String, String, Long>? = null
        var cx = 960f; var cy = 540f
        val evs = ArrayList<Ev>()
        val tok = Regex("\"[^\"]*\"|\\S+")
        f.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val a = tok.findAll(line).map { it.value.trim('"') }.toList()
            when (a[0]) {
                "size" -> { lw = a[1].toInt(); lh = a[2].toInt(); scale = a[3].toFloat() }
                "fps" -> fps = a[1].toInt()
                "duration" -> dur = a[1].toDouble()
                "play" -> play = Triple(a[1], a[2], a.getOrNull(3)?.toLong() ?: 0L)
                "cursor" -> { cx = a[1].toFloat(); cy = a[2].toFloat() }
                else -> {
                    val t = a[0].toDouble()
                    when (a[1]) {
                        "move" -> evs += Move(t, a[2].toFloat(), a[3].toFloat(), a.getOrNull(4)?.toDouble() ?: .4)
                        "click" -> evs += if (a.size >= 4) Click(t, a[2].toFloat(), a[3].toFloat(), a.getOrNull(4)?.toDouble() ?: .4) else Click(t, null, null, 0.0)
                        "scroll" -> evs += Scroll(t, a[2].toFloat(), a.getOrNull(3)?.toDouble() ?: .5)
                        "key" -> evs += KeyEv(t, a[2])
                    }
                }
            }
        }
        evs.sortBy { it.t }
        val W = (lw * scale).toInt(); val H = (lh * scale).toInt()
        val out = dir!!.resolve("frames").apply { mkdirs() }
        log("REC start ${W}x$H @$fps ${dur}s, ${evs.size} events")

        // 录制前：开始播放指定的歌
        play?.let { (src, q, pos) ->
            runBlocking {
                val list = runCatching {
                    if (src == "liked") Store.playlists.value.firstOrNull { it.liked }?.let { Ncm.playlistTracks(it.id) } ?: emptyList()
                    else Ncm.search(q)
                }.getOrDefault(emptyList())
                val i = list.indexOfFirst { it.title.contains(q, true) }.coerceAtLeast(0)
                log("play: ${list.getOrNull(i)?.title} (${list.size} tracks)")
                if (list.isNotEmpty()) { Engine.playList(list, i); Thread.sleep(1500); Engine.seek(pos) }
            }
        }
        val audio = Plat.impl.audio as RecAudio
        val startPos = Engine.position.value

        val writer = Executors.newFixedThreadPool(2)
        // 两遍：第一遍只跑不存（把图片、歌词、列表都加载进缓存），第二遍正式录
        for (pass in 0..1) {
            vtMs = 0; audio.reset(startPos); Engine.seek(startPos)
            val scene = ImageComposeScene(W, H, Density(scale)) {
                NekoTheme { Box(Modifier.fillMaxSize()) { Root(chrome = { RecTitleBar() }) } }
            }
            // 先让数据加载一会儿（真实时间），虚拟时间停在 0
            repeat(40) { scene.render(0); Thread.sleep(100) }
            var px = cx * scale; var py = cy * scale
            scene.sendPointerEvent(PointerEventType.Move, Offset(px, py))
            var mv: Triple<Offset, Offset, Pair<Double, Double>>? = null   // 从、到、(开始, 时长)
            var pendingClick = -1.0; var releaseAt = -1.0
            var sc: Triple<Float, Double, Double>? = null                   // 格数、开始、时长
            var ei = 0
            val total = (dur * fps).toInt()
            val step = if (pass == 0) 6 else 1
            for (fr in 0 until total) {
                val t = fr.toDouble() / fps
                vtMs = (t * 1000).toLong()
                while (ei < evs.size && evs[ei].t <= t) {
                    when (val e = evs[ei]) {
                        is Move -> mv = Triple(Offset(px, py), Offset(e.x * scale, e.y * scale), e.t to e.d)
                        is Click -> if (e.x != null) { mv = Triple(Offset(px, py), Offset(e.x * scale, e.y!! * scale), e.t to e.d); pendingClick = e.t + e.d } else pendingClick = e.t
                        is Scroll -> sc = Triple(e.n, e.t, e.d)
                        is KeyEv -> when (e.k) {
                            "back" -> DesktopPlat.back()
                            "settings" -> Keys.handleCtrl(androidx.compose.ui.input.key.Key.Comma, true)
                            "player" -> Keys.handleCtrl(androidx.compose.ui.input.key.Key.P, true)
                            "search" -> Keys.handleCtrl(androidx.compose.ui.input.key.Key.F, true)
                        }
                    }
                    ei++
                }
                mv?.let { (a, b, td) ->
                    val p = ((t - td.first) / td.second).coerceIn(0.0, 1.0).toFloat()
                    val e = if (p < .5f) 4 * p * p * p else 1 - Math.pow((-2.0 * p + 2), 3.0).toFloat() / 2   // 缓入缓出
                    px = a.x + (b.x - a.x) * e; py = a.y + (b.y - a.y) * e
                    scene.sendPointerEvent(PointerEventType.Move, Offset(px, py))
                    if (p >= 1f) mv = null
                }
                if (pendingClick >= 0 && t >= pendingClick && mv == null) {
                    scene.sendPointerEvent(PointerEventType.Press, Offset(px, py), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    pendingClick = -1.0; releaseAt = t + .09
                }
                if (releaseAt >= 0 && t >= releaseAt) {
                    scene.sendPointerEvent(PointerEventType.Release, Offset(px, py), buttons = PointerButtons(), button = PointerButton.Primary)
                    releaseAt = -1.0
                }
                sc?.let { (n, a, d) ->
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(px, py), scrollDelta = Offset(0f, (n / (d * fps)).toFloat()))
                    if (t >= a + d) sc = null
                }
                // 播放进度跟虚拟时钟走（歌词、进度条都用它）
                Engine.position.value = audio.position
                if (fr % step != 0) continue
                val img = scene.render(vtMs * 1_000_000)
                if (pass == 1) {
                    val surf = org.jetbrains.skia.Surface.makeRasterN32Premul(W, H)
                    surf.canvas.drawImage(img, 0f, 0f)
                    drawCursor(surf.canvas, px, py, scale)
                    val snap = surf.makeImageSnapshot()
                    val n = fr
                    writer.submit {
                        val data = snap.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 94)!!
                        out.resolve("f%05d.jpg".format(n)).writeBytes(data.bytes)
                    }
                    if (fr % fps == 0) log("frame $fr / $total")
                } else if (fr % (fps * 3) == 0) log("warm $fr / $total")
            }
            scene.close()
        }
        writer.shutdown(); writer.awaitTermination(1, TimeUnit.HOURS)
        f.renameTo(f.resolveSibling("script.done"))
        log("REC done")
    }

    /** 画一个系统风格的箭头光标（白底黑边） */
    private fun drawCursor(cv: org.jetbrains.skia.Canvas, x: Float, y: Float, s: Float) {
        val k = s * 1.15f
        val pts = floatArrayOf(0f, 0f, 0f, 17f, 4.2f, 13.2f, 6.9f, 19.3f, 9.6f, 18.1f, 7f, 12.2f, 12.5f, 12.2f)
        val pb = org.jetbrains.skia.PathBuilder()
        pb.moveTo(x + pts[0] * k, y + pts[1] * k)
        var i = 2; while (i < pts.size) { pb.lineTo(x + pts[i] * k, y + pts[i + 1] * k); i += 2 }
        pb.closePath()
        val path = pb.detach()
        cv.drawPath(path, org.jetbrains.skia.Paint().apply { color = 0x55000000; maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, 2.5f * s) })
        cv.drawPath(path, org.jetbrains.skia.Paint().apply { color = -1; isAntiAlias = true })
        cv.drawPath(path, org.jetbrains.skia.Paint().apply { color = 0xFF000000.toInt(); isAntiAlias = true; mode = org.jetbrains.skia.PaintMode.STROKE; strokeWidth = 1.2f * s })
    }
}

/** 录制用的「播放器」：不出声，进度跟虚拟时钟走 */
class RecAudio : Audio {
    override var listener: Audio.Listener? = null
    private var base = 0L; private var since = 0L; private var playing = false
    fun reset(pos: Long) { base = pos; since = Rec.vtMs }
    override fun open(url: String?, cacheKey: String, startMs: Long, autoplay: Boolean, meta: Track) {
        base = startMs; since = Rec.vtMs; playing = autoplay
        listener?.onBuffering(false); listener?.onPlaying(autoplay)
    }
    override fun play() { if (!playing) { since = Rec.vtMs; playing = true; listener?.onPlaying(true) } }
    override fun pause() { if (playing) { base = position; playing = false; listener?.onPlaying(false) } }
    override fun seek(ms: Long) { base = ms; since = Rec.vtMs }
    override val isPlaying get() = playing
    override val idle get() = false
    override val position: Long get() = if (playing) base + (Rec.vtMs - since) else base
    override val buffered: Long get() = position + 120_000
    override fun isCached(key: String) = true
    override fun cacheBytes() = 0L
    override fun clearCache() {}
    override var volume = 1f
    override fun info() = AudioInfo("flac", 48000, 24, 2, 1918)
    override fun applyFx(fx: Fx) {}
}
