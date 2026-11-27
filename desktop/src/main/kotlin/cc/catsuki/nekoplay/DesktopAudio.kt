package cc.catsuki.nekoplay

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import java.io.File

/** libmpv 的最小 JNA 绑定 */
private interface Mpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(h: Pointer): Int
    fun mpv_set_option_string(h: Pointer, name: String, v: String): Int
    fun mpv_command(h: Pointer, args: Array<String?>): Int
    fun mpv_set_property_string(h: Pointer, name: String, v: String): Int
    fun mpv_get_property_string(h: Pointer, name: String): Pointer?
    fun mpv_get_property(h: Pointer, name: String, format: Int, out: DoubleByReference): Int
    fun mpv_free(p: Pointer)
}

/**
 * 桌面播放内核：libmpv（支持 FLAC / 边下边播，占用低）。
 * Windows 需要把 libmpv-2.dll 放在程序目录；macOS / Linux 装 mpv 即可。
 * 加载失败时界面照常可用，只是播放会提示错误。
 */
internal class MpvAudio : Audio {
    override var listener: Audio.Listener? = null
    private val logFile = File(System.getProperty("user.home"), if (System.getProperty("os.name").lowercase().contains("win")) "AppData/Roaming/NEKOPLAY/mpv.log" else ".nekoplay/mpv.log").apply { parentFile?.mkdirs() }
    private fun lastLogError(): String? = runCatching {
        logFile.readLines().takeLast(200).lastOrNull { l -> Regex("\\]\\[[ef]\\]|error|failed|Failed|Cannot|cannot", RegexOption.IGNORE_CASE).containsMatchIn(l) }
            ?.substringAfter("] ")?.trim()?.take(160)
    }.getOrNull()
    @Volatile private var openedAt = 0L
    private val newLoadfile: Boolean by lazy {
        val v = runCatching { str("mpv-version") }.getOrNull() ?: return@lazy true
        val m = Regex("(\\d+)\\.(\\d+)").find(v) ?: return@lazy true
        val (a, b) = m.destructured
        a.toInt() > 0 || b.toInt() >= 38
    }
    @Volatile private var failed = false
    private val loadErrors = ArrayList<String>()
    private val lib: Mpv? = run {
        val bundled = System.getProperty("compose.application.resources.dir")?.let { File(it) }
            ?.listFiles { f -> f.name.startsWith("libmpv") && (f.name.endsWith(".dll") || f.name.endsWith(".dylib") || f.name.contains(".so")) }
            ?.sortedBy { it.name.length }?.map { it.absolutePath } ?: emptyList()
        (bundled + listOf("mpv", "libmpv-2", "mpv-2", "libmpv", "mpv.2")).firstNotNullOfOrNull { n ->
            runCatching { Native.load(n, Mpv::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")) }.onFailure { e -> if (loadErrors.size < 3 && (n.contains('/') || n.contains('\\'))) loadErrors += "${File(n).name}: ${e.message?.lineSequence()?.firstOrNull()}" }.getOrNull()
        }
    }
    private val h: Pointer? = lib?.mpv_create()?.also { h ->
        lib.mpv_set_option_string(h, "vid", "no")
        lib.mpv_set_option_string(h, "keep-open", "yes")
        lib.mpv_set_option_string(h, "idle", "yes")
        lib.mpv_set_option_string(h, "user-agent", "Mozilla/5.0")
        lib.mpv_set_option_string(h, "cache", "yes")
        lib.mpv_set_option_string(h, "demuxer-max-bytes", "8MiB")
        lib.mpv_set_option_string(h, "demuxer-max-back-bytes", "2MiB")
        lib.mpv_set_option_string(h, "audio-buffer", "0.5")
        lib.mpv_set_option_string(h, "log-file", logFile.absolutePath)
        lib.mpv_initialize(h)
    }
    @Volatile private var opened = false
    @Volatile private var playing = false
    @Volatile private var ended = false
    @Volatile private var resetState = false
    @Volatile private var pos = 0L
    @Volatile private var bufPos = 0L

    init {
        Thread {
            var lastPlaying = false; var lastBuf = false
            while (true) {
                Thread.sleep(when { !opened -> 800L; !playing -> 500L; else -> 200L })
                if (lib == null || h == null || !opened) continue
                if (resetState) { resetState = false; lastPlaying = false; lastBuf = false }
                val paused = str("pause") == "yes"
                val eof = str("eof-reached") == "yes"
                val pp = str("playlist-pos")?.toIntOrNull() ?: 0
                if (pp > 0) { cmd("playlist-remove", "0"); java.awt.EventQueue.invokeLater { listener?.onAdvanced() } }
                val buf = str("paused-for-cache") == "yes"
                if (!failed && System.currentTimeMillis() - openedAt > 4000 && str("idle-active") == "yes" && str("path").isNullOrEmpty()) {
                    failed = true; opened = false; playing = false
                    val why = lastLogError()
                    java.awt.EventQueue.invokeLater { lastPlaying = false; listener?.onPlaying(false); listener?.onError("播放内核打不开这首歌" + (why?.let { "：$it" } ?: "") + "（日志：${logFile.absolutePath}）") }
                    continue
                }
                pos = (num("time-pos") * 1000).toLong()
                bufPos = (num("demuxer-cache-time") * 1000).toLong()
                playing = !paused && !eof
                java.awt.EventQueue.invokeLater {
                    if (playing != lastPlaying) { lastPlaying = playing; listener?.onPlaying(playing) }
                    if (buf != lastBuf) { lastBuf = buf; listener?.onBuffering(buf) }
                    if (eof && !ended) { ended = true; listener?.onEnded() }
                }
            }
        }.apply { isDaemon = true; name = "mpv-poll" }.start()
    }

    override fun info(): AudioInfo? = runCatching {
        if (lib == null || h == null) return null
        val codec = str("audio-codec-name") ?: return null
        val fmt = str("audio-params/format") ?: ""
        val bits = when { fmt.startsWith("s16") -> 16; fmt.startsWith("s32") -> if (codec == "flac") 24 else 32; fmt.startsWith("u8") -> 8; else -> 0 }
        AudioInfo(codec, str("audio-params/samplerate")?.toIntOrNull() ?: 0, bits, str("audio-params/channel-count")?.toIntOrNull() ?: 0, (str("audio-bitrate")?.toDoubleOrNull() ?: 0.0).toInt() / 1000)
    }.getOrNull()
    private fun str(n: String): String? { val p = lib!!.mpv_get_property_string(h!!, n) ?: return null; val s = p.getString(0, "UTF-8"); lib.mpv_free(p); return s }
    private fun num(n: String): Double { val r = DoubleByReference(); return if (lib!!.mpv_get_property(h!!, n, 5, r) >= 0) r.value else 0.0 }
    private fun cmd(vararg a: String) { lib?.mpv_command(h!!, arrayOf(*a, null)) }

    /** 每首歌按地址取音源插件声明的 CDN 请求头（MediaHeaders），没有就恢复默认 */
    private fun applyStreamHeaders(url: String) {
        val hh = h ?: return
        val hs = MediaHeaders.forUrl(url)
        lib?.mpv_set_property_string(hh, "referrer", hs["Referer"] ?: "")
        lib?.mpv_set_property_string(hh, "user-agent", hs["User-Agent"] ?: "Mozilla/5.0")
        lib?.mpv_set_property_string(hh, "http-header-fields", mpvHeaderFields(hs))
    }

    override fun open(url: String?, cacheKey: String, startMs: Long, autoplay: Boolean, meta: Track) {
        if (lib == null || h == null) throw Exception("播放内核 libmpv 加载失败" + if (lib != null) "（mpv_create 失败）" else if (loadErrors.isNotEmpty()) "（可能被安全软件误删，请在保护历史中还原）：" + loadErrors.joinToString("；") else "（程序目录里没有找到 libmpv，可能被安全软件误删）")
        if (url == null) throw Exception("桌面版暂无本地缓存")
        val ll = lib; val hh = h
        ended = false; opened = true; failed = false; playing = false; resetState = true; pos = startMs; openedAt = System.currentTimeMillis()
        synchronized(rateLock) {
            if (lastSpeed != 1f || lastPitchLinked == true) {
                runCatching {
                    ll.mpv_set_property_string(hh, "audio-pitch-correction", "yes")
                    ll.mpv_set_property_string(hh, "speed", "1.000")
                }
            }
            lastSpeed = 1f; lastPitchLinked = false
        }
        applyStreamHeaders(url)
        lib.mpv_set_property_string(h, "pause", if (autoplay) "no" else "yes")
        val src = if (url.startsWith("file:")) File(java.net.URI(url)).absolutePath else url
        if (newLoadfile) cmd("loadfile", src, "replace", "-1", "start=${startMs / 1000.0}")
        else cmd("loadfile", src, "replace", "start=${startMs / 1000.0}")
    }
    override fun queueNext(url: String?, cacheKey: String, meta: Track) {
        if (lib == null || h == null || url == null) return
        clearNext()
        applyStreamHeaders(url)
        cmd("loadfile", if (url.startsWith("file:")) File(java.net.URI(url)).absolutePath else url, "append")
    }
    override fun clearNext() { if (lib == null || h == null) return; val n = str("playlist-count")?.toIntOrNull() ?: 0; for (i in n - 1 downTo 1) cmd("playlist-remove", i.toString()) }
    override fun play() { h?.let { lib!!.mpv_set_property_string(it, "pause", "no") } }
    override fun pause() { playing = false; h?.let { lib!!.mpv_set_property_string(it, "pause", "yes") } }
    override fun seek(ms: Long) { cmd("seek", (ms / 1000.0).toString(), "absolute"); pos = ms; ended = false }
    override val isPlaying get() = playing
    override val idle get() = !opened
    override val position get() = pos
    override val buffered get() = bufPos
    override fun isCached(key: String) = false
    override fun cacheBytes() = 0L
    override fun clearCache() {}
    override var volume: Float = 1f
        set(v) { field = v; h?.let { lib!!.mpv_set_property_string(it, "volume", (v * 100).toInt().toString()) } }

    private val rateLock = Any()
    private var lastPitchLinked: Boolean? = null
    private var lastSpeed = 1f

    override fun setRate(speed: Float, pitchLinked: Boolean) {
        val hh = h ?: return
        val ll = lib ?: return
        val clamped = if (speed.isFinite()) speed.coerceIn(0.25f, 4f) else 1f
        synchronized(rateLock) {
            runCatching {
                if (lastPitchLinked != pitchLinked) {
                    ll.mpv_set_property_string(hh, "audio-pitch-correction", if (pitchLinked) "no" else "yes")
                    lastPitchLinked = pitchLinked
                }
                if (kotlin.math.abs(clamped - lastSpeed) >= 0.02f || clamped == 1f) {
                    ll.mpv_set_property_string(hh, "speed", "%.3f".format(java.util.Locale.US, clamped))
                    lastSpeed = clamped
                }
            }
        }
    }

    override fun applyFx(fx: Fx) {
        val hh = h ?: return
        val f = ArrayList<String>()
        if (fx.on) {
            Fx.FREQS.forEachIndexed { i, hz -> if (fx.bands[i] != 0f) f += "equalizer=f=$hz:t=o:w=1:g=${fx.bands[i]}" }
            if (fx.bass > 0) f += "bass=g=${"%.1f".format(java.util.Locale.US, fx.bass * 12)}:f=100"
            if (fx.surround > 0) f += "extrastereo=m=${"%.2f".format(java.util.Locale.US, 1 + fx.surround * 1.5f)}"
            if (fx.loud > 0) f += "volume=${"%.1f".format(java.util.Locale.US, fx.loud * 10)}dB"
            val boost = (fx.bands.maxOrNull() ?: 0f).coerceAtLeast(0f) + fx.bass * 6f
            if (boost > 0f && fx.loud <= 0f) f += "volume=-${"%.1f".format(java.util.Locale.US, (boost * 0.5f).coerceAtMost(6f))}dB"
        }
        lib!!.mpv_set_property_string(hh, "af", if (f.isEmpty()) "" else "lavfi=[${f.joinToString(",")}]")
    }

    fun setPcmPipe(pipe: String?) {
        val hh = h ?: return
        if (pipe != null) {
            lib!!.mpv_set_property_string(hh, "ao-pcm-waveheader", "no")
            lib.mpv_set_property_string(hh, "ao-pcm-append", "no")
            lib.mpv_set_property_string(hh, "ao-pcm-file", pipe)
            lib.mpv_set_property_string(hh, "audio-samplerate", "48000")
            lib.mpv_set_property_string(hh, "audio-format", "float")
            lib.mpv_set_property_string(hh, "audio-channels", "stereo")
            lib.mpv_set_property_string(hh, "ao", "pcm")
        } else {
            lib!!.mpv_set_property_string(hh, "ao", "")
        }
        cmd("ao-reload")
    }
}

/** mpv 的 http-header-fields：Referer / User-Agent 有专门选项，其余（如 Origin）拼成 "Key: Value" 列表；值里有逗号的跳过（mpv 用逗号分隔） */
internal fun mpvHeaderFields(hs: Map<String, String>): String =
    hs.filterKeys { it != "Referer" && it != "User-Agent" }.filterValues { ',' !in it }.entries.joinToString(",") { "${it.key}: ${it.value}" }
