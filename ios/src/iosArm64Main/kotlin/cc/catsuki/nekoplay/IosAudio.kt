package cc.catsuki.nekoplay

import java.io.File
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.AVFoundation.*
import platform.AVFAudio.*
import platform.CoreMedia.*
import platform.Foundation.*
import platform.MediaPlayer.*
import platform.UIKit.*
import platform.darwin.NSObject

/**
 * iOS 播放内核：AVQueuePlayer（排好下一首就能无缝接上）。
 * 边播边把整首歌下载进缓存（最多 1GB），下次播放直接读本地文件。
 * 锁屏 / 控制中心 / 耳机按键：MPNowPlayingInfoCenter + MPRemoteCommandCenter。
 */
class IosAudio(cacheRoot: File) : Audio {
    override var listener: Audio.Listener? = null
    private val player = AVQueuePlayer()
    private val dir = File(cacheRoot, "audio").apply { mkdirs() }
    private val scope = MainScope()
    private var cur: AVPlayerItem? = null
    private var next: AVPlayerItem? = null
    private var curMeta: Track? = null
    private var nextMeta: Track? = null
    private var nextKey: Pair<String?, String>? = null
    private var lastPlaying = false
    private var lastBuffering = false
    private var reportedError: AVPlayerItem? = null
    private var observer: Any? = null
    private val downloading = HashSet<String>()
    private var artwork: MPMediaItemArtwork? = null
    private var artworkFor: String? = null

    init {
        // 旧版本存成 .a 的缓存放不了，清掉
        dir.listFiles()?.filter { it.name.endsWith(".a") }?.forEach { it.delete() }
        runCatching {
            val s = AVAudioSession.sharedInstance()
            s.setCategory(AVAudioSessionCategoryPlayback, null)
            s.setActive(true, null)
        }
        player.automaticallyWaitsToMinimizeStalling = true
        observer = NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, null, NSOperationQueue.mainQueue) { n ->
            val item = n?.`object` as? AVPlayerItem ?: return@addObserverForName
            if (item != cur) return@addObserverForName
            val nx = next
            if (nx != null) {
                // 队列自动接到了下一首
                cur = nx; curMeta = nextMeta; next = null; nextMeta = null; nextKey = null
                updateNowPlaying(); listener?.onAdvanced()
            } else listener?.onEnded()
        }
        remote()
        // 状态轮询：播放 / 缓冲 / 出错
        scope.launch {
            while (isActive) {
                val playing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying
                val buffering = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
                if (playing != lastPlaying) { lastPlaying = playing; listener?.onPlaying(playing); updateNowPlaying() }
                if (buffering != lastBuffering) { lastBuffering = buffering; listener?.onBuffering(buffering) }
                val c = cur
                if (c != null && c.status == AVPlayerItemStatusFailed && reportedError != c) {
                    reportedError = c; player.pause(); lastPlaying = false; listener?.onPlaying(false); listener?.onBuffering(false)
                    listener?.onError(c.error?.localizedDescription ?: "播放失败")
                }
                delay(200)
            }
        }
    }

    private fun hash(key: String) = key.encodeToByteArray().fold(1125899906842597L) { h, b -> 31 * h + b }.toULong().toString(16)
    /** 缓存文件带上原来的扩展名（.flac / .mp3 / .m4a）：系统播放器靠扩展名认格式，认不出就「无法打开」 */
    private fun cachedFile(key: String): File? { val h = hash(key) + "."; return dir.listFiles()?.firstOrNull { it.name.startsWith(h) && !it.name.endsWith(".part") } }
    private fun extOf(url: String) = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it in setOf("flac", "mp3", "m4a", "aac", "mp4", "wav") }

    private fun itemFor(url: String?, key: String): AVPlayerItem? {
        val f = cachedFile(key)
        val u = when {
            f != null -> NSURL.fileURLWithPath(f.path)
            url == null -> return null
            url.startsWith("/") -> NSURL.fileURLWithPath(url)
            else -> NSURL.URLWithString(url) ?: return null
        }
        if (f == null && url != null && url.startsWith("http")) fetch(url, key)
        // 地址里带账号密码（WebDAV）：去掉，改成 Authorization 头，有的服务不会先回 401 让播放器补认证
        val auth = if (f == null && url != null) basicOf(url) else null
        if (auth != null) {
            val clean = NSURL.URLWithString(auth.first) ?: return null
            val asset = AVURLAsset(uRL = clean, options = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to mapOf("Authorization" to auth.second)))
            return AVPlayerItem(asset = asset)
        }
        return AVPlayerItem(uRL = u)
    }

    /** https://用户:密码@主机/… → (不带账号的地址, "Basic …")；没有账号返回 null */
    private fun basicOf(url: String): Pair<String, String>? {
        val m = Regex("^(https?://)([^/@]+)@(.*)$").find(url) ?: return null
        val ui = m.groupValues[2]
        val plain = NSString.create(string = ui).stringByRemovingPercentEncoding ?: ui
        val b64 = NSString.create(string = plain).dataUsingEncoding(NSUTF8StringEncoding)?.base64EncodedStringWithOptions(0u) ?: return null
        return m.groupValues[1] + m.groupValues[3] to "Basic $b64"
    }

    /** 整首下载进缓存（和播放并行）；下完以后下次就读本地 */
    private fun fetch(url: String, key: String) {
        val ext = extOf(url) ?: return // 认不出格式的不缓存，免得存了也放不了
        if (!downloading.add(key)) return
        val target = File(dir, hash(key) + "." + ext)
        val auth = basicOf(url)
        val req = NSMutableURLRequest.requestWithURL(NSURL.URLWithString(auth?.first ?: url) ?: return)
        req.setValue("Mozilla/5.0", forHTTPHeaderField = "User-Agent")
        auth?.let { req.setValue(it.second, forHTTPHeaderField = "Authorization") }
        NSURLSession.sharedSession.downloadTaskWithRequest(req) { loc, resp, err ->
            val ok = err == null && loc != null && ((resp as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0) in 200..299
            if (ok) {
                val fm = NSFileManager.defaultManager
                fm.removeItemAtPath(target.path, null)
                fm.moveItemAtPath(loc.path!!, target.path, null)
                trim()
            }
            NSOperationQueue.mainQueue.addOperationWithBlock { downloading.remove(key) }
        }.resume()
    }

    /** 缓存超过 1GB 时，从最久没用的删起 */
    private fun trim() {
        val files = dir.listFiles()?.toList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= 1024L * 1024 * 1024) return
        for (f in files.sortedBy { it.lastModified() }) { total -= f.length(); f.delete(); if (total <= 900L * 1024 * 1024) break }
    }

    override fun open(url: String?, cacheKey: String, startMs: Long, autoplay: Boolean, meta: Track) {
        val item = itemFor(url, cacheKey)
        player.removeAllItems()
        next = null; nextMeta = null; nextKey = null
        cur = item; curMeta = meta; reportedError = null; lastPitchLinked = null
        if (item == null) { listener?.onError("没有可播放的地址"); return }
        player.insertItem(item, null)
        if (startMs > 0) item.seekToTime(CMTimeMakeWithSeconds(startMs / 1000.0, 1000), CMTimeMake(0, 1), CMTimeMake(0, 1), null)
        if (autoplay) {
            runCatching { AVAudioSession.sharedInstance().setActive(true, null) }
            player.play()
        } else {
            player.pause()
        }
        updateNowPlaying()
    }

    override fun queueNext(url: String?, cacheKey: String, meta: Track) {
        clearNext()
        val item = itemFor(url, cacheKey) ?: return
        val c = cur ?: return
        if (player.canInsertItem(item, c)) { player.insertItem(item, c); next = item; nextMeta = meta; nextKey = url to cacheKey }
    }

    override fun clearNext() { next?.let { player.removeItem(it) }; next = null; nextMeta = null; nextKey = null }
    override fun play() { runCatching { AVAudioSession.sharedInstance().setActive(true, null) }; player.play(); updateNowPlaying() }
    override fun pause() { player.pause(); updateNowPlaying() }
    override fun seek(ms: Long) {
        player.seekToTime(CMTimeMakeWithSeconds(ms / 1000.0, 1000), CMTimeMake(0, 1), CMTimeMake(0, 1)) { _ -> updateNowPlaying() }
    }
    override val isPlaying get() = player.timeControlStatus != AVPlayerTimeControlStatusPaused && cur?.status != AVPlayerItemStatusFailed
    override val idle get() = cur == null
    override val position: Long get() = CMTimeGetSeconds(player.currentTime()).let { if (it.isNaN()) 0 else (it * 1000).toLong() }
    override val buffered: Long get() {
        val r = cur?.loadedTimeRanges?.lastOrNull() as? NSValue ?: return 0
        return r.CMTimeRangeValue.useContents { (CMTimeGetSeconds(start.readValue()) + CMTimeGetSeconds(duration.readValue())) * 1000 }.toLong()
    }
    override fun isCached(key: String) = cachedFile(key) != null
    override fun cacheBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0
    override fun clearCache() { dir.listFiles()?.forEach { it.delete() } }
    override var volume: Float get() = player.volume; set(v) { player.volume = v }
    override fun info(): AudioInfo? {
        val u = (cur?.asset as? AVURLAsset)?.URL?.absoluteString ?: return null
        val ext = u.substringBefore('?').substringAfterLast('.', "").lowercase()
        val codec = when (ext) { "flac" -> "flac"; "mp3" -> "mp3"; "m4a", "aac", "mp4" -> "aac"; else -> return null }
        return AudioInfo(codec, 0, 0, 0, 0)
    }
    private var lastPitchLinked: Boolean? = null

    override fun setRate(speed: Float, pitchLinked: Boolean) {
        val s = if (speed.isFinite()) speed.coerceIn(0.25f, 4f) else 1f
        NSOperationQueue.mainQueue.addOperationWithBlock {
            if (lastPitchLinked != pitchLinked) {
                cur?.audioTimePitchAlgorithm = if (pitchLinked) AVAudioTimePitchAlgorithmVarispeed else AVAudioTimePitchAlgorithmSpectral
                lastPitchLinked = pitchLinked
            }
            if (player.rate > 0f) player.rate = s
        }
    }
    /** 均衡器等效果：iOS 版暂不支持 */
    override fun applyFx(fx: Fx) {}

    // ---------- 锁屏 / 控制中心 ----------
    private fun remote() {
        NSOperationQueue.mainQueue.addOperationWithBlock {
            UIApplication.sharedApplication.beginReceivingRemoteControlEvents()
        }
        val c = MPRemoteCommandCenter.sharedCommandCenter()
        c.playCommand.enabled = true
        c.pauseCommand.enabled = true
        c.togglePlayPauseCommand.enabled = true
        c.nextTrackCommand.enabled = true
        c.previousTrackCommand.enabled = true
        c.changePlaybackPositionCommand.enabled = true
        c.playCommand.addTargetWithHandler { if (!Engine.isPlaying) Engine.toggle(); MPRemoteCommandHandlerStatusSuccess }
        c.pauseCommand.addTargetWithHandler { Engine.pause(); MPRemoteCommandHandlerStatusSuccess }
        c.togglePlayPauseCommand.addTargetWithHandler { Engine.toggle(); MPRemoteCommandHandlerStatusSuccess }
        c.nextTrackCommand.addTargetWithHandler { Engine.next(); MPRemoteCommandHandlerStatusSuccess }
        c.previousTrackCommand.addTargetWithHandler { Engine.prev(); MPRemoteCommandHandlerStatusSuccess }
        c.changePlaybackPositionCommand.addTargetWithHandler { e ->
            (e as? MPChangePlaybackPositionCommandEvent)?.let { Engine.seek((it.positionTime * 1000).toLong()) }
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    private fun updateNowPlaying() {
        val t = curMeta ?: return
        val playing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying
        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to t.title,
            MPMediaItemPropertyArtist to t.artist,
            MPMediaItemPropertyAlbumTitle to t.album,
            MPMediaItemPropertyPlaybackDuration to t.durationMs / 1000.0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to position / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate to if (playing) 1.0 else 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate to 1.0,
            MPNowPlayingInfoPropertyMediaType to MPNowPlayingInfoMediaTypeAudio,
        )
        if (artworkFor == t.id) artwork?.let { info[MPMediaItemPropertyArtwork] = it } else loadArtwork(t)
        val center = MPNowPlayingInfoCenter.defaultCenter()
        center.nowPlayingInfo = info
        center.playbackState = if (playing) MPNowPlayingPlaybackStatePlaying else MPNowPlayingPlaybackStatePaused
    }

    private fun loadArtwork(t: Track) {
        val cover = t.cover ?: return
        val sized = if ("music.126.net" in cover && '?' !in cover) "$cover?param=512y512" else cover
        val u = NSURL.URLWithString(sized) ?: return
        NSURLSession.sharedSession.dataTaskWithURL(u) { data, _, _ ->
            val img = data?.let { UIImage.imageWithData(it) } ?: return@dataTaskWithURL
            NSOperationQueue.mainQueue.addOperationWithBlock {
                if (curMeta?.id != t.id) return@addOperationWithBlock
                artwork = MPMediaItemArtwork(boundsSize = img.size) { _ -> img }
                artworkFor = t.id
                updateNowPlaying()
            }
        }.resume()
    }
}
