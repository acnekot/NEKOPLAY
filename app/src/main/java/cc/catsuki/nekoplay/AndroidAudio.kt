package cc.catsuki.nekoplay

import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File

/** ExoPlayer + 1GB 播放缓存；player 给通知栏 / 锁屏用（上一首下一首走 Engine 的队列） */
class ExoAudio(private val c: Context) : Audio {
    override var listener: Audio.Listener? = null
    // 固定一个音频会话，效果器都挂在这个会话上
    private val sessionId = (c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).generateAudioSessionId()
    private var dyn: android.media.audiofx.DynamicsProcessing? = null
    private var eqOld: android.media.audiofx.Equalizer? = null
    private var bassFx: android.media.audiofx.BassBoost? = null
    private var virtFx: android.media.audiofx.Virtualizer? = null
    private var loudFx: android.media.audiofx.LoudnessEnhancer? = null
    val exo: ExoPlayer
    val player: Player
    private val cache = SimpleCache(File(c.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(1024L * 1024 * 1024), StandaloneDatabaseProvider(c))
    private val upstream: DataSource.Factory
    private val hlsFactory: HlsMediaSource.Factory
    init {
        val lc = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 25_000, 1_500, 4_000).setTargetBufferBytes(4 * 1024 * 1024).build()
        val http = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0").setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
        // 地址里带账号密码（WebDAV 音乐 https://用户:密码@主机/…）：去掉并改成 Basic 认证头；特定 CDN 自动补 Referer 防 403
        upstream = androidx.media3.datasource.ResolvingDataSource.Factory(http) { spec ->
            val host = spec.uri.host.orEmpty().lowercase()
            val s0 = if ("bilivideo" in host || "hdslb" in host || "bilibili" in host || "akamaized" in host) {
                spec.withAdditionalHeaders(mapOf("Referer" to "https://www.bilibili.com/", "Origin" to "https://www.bilibili.com"))
            } else spec
            val ui = s0.uri.encodedUserInfo
            if (ui.isNullOrEmpty()) s0
            else s0.withUri(s0.uri.buildUpon().encodedAuthority(s0.uri.encodedAuthority!!.substringAfter('@')).build())
                .withAdditionalHeaders(mapOf("Authorization" to "Basic " + android.util.Base64.encodeToString(android.net.Uri.decode(ui).toByteArray(), android.util.Base64.NO_WRAP)))
        }
        hlsFactory = HlsMediaSource.Factory(upstream)
        val ds = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        exo = ExoPlayer.Builder(c).setLoadControl(lc).setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setLooper(Looper.getMainLooper()).build()
        exo.setAudioSessionId(sessionId)
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { listener?.onPlaying(p) }
            override fun onPlaybackStateChanged(s: Int) {
                listener?.onBuffering(s == Player.STATE_BUFFERING)
                if (s == Player.STATE_ENDED) listener?.onEnded()
            }
            override fun onPlayerError(e: PlaybackException) { listener?.onError(e.errorCodeName) }
            // 无缝播放：自动接到排好的下一首 → 把上一首从播放器里拿掉，告诉 Engine
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && exo.currentMediaItemIndex > 0) {
                    exo.removeMediaItems(0, exo.currentMediaItemIndex); listener?.onAdvanced()
                }
            }
        })
        player = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()
            override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
            override fun seekToNext() = Engine.next()
            override fun seekToNextMediaItem() = Engine.next()
            override fun seekToPrevious() = Engine.prev()
            override fun seekToPreviousMediaItem() = Engine.prev()
            override fun hasNextMediaItem() = true
            override fun hasPreviousMediaItem() = true
        }
    }
    private fun isHls(url: String?) = url != null && (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) || ".m3u8" in url.lowercase())
    private fun artworkUri(cover: String?) = cover?.let { android.net.Uri.parse(if ("music.126.net" in it && '?' !in it) "$it?param=512y512" else it) }
    override fun open(url: String?, cacheKey: String, startMs: Long, autoplay: Boolean, meta: Track) {
        val hls = isHls(url)
        val item = item(url, cacheKey, meta, withCacheKey = !hls)
        when {
            url?.startsWith("file:") == true || url?.startsWith("content:") == true ->
                exo.setMediaSource(androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(c)).createMediaSource(item), startMs)
            hls -> exo.setMediaSource(hlsFactory.createMediaSource(item), startMs)
            else -> exo.setMediaItem(item, startMs)
        }
        if (lastRateSpeed != 1f || lastRatePitch) { lastRateSpeed = 1f; lastRatePitch = false; runCatching { exo.playbackParameters = androidx.media3.common.PlaybackParameters.DEFAULT } }
        exo.prepare(); exo.playWhenReady = autoplay
    }
    private fun item(url: String?, cacheKey: String, meta: Track, withCacheKey: Boolean = true): MediaItem {
        val m = MediaMetadata.Builder().setTitle(meta.title).setArtist(meta.artist).setAlbumTitle(meta.album)
            .setArtworkUri(artworkUri(meta.cover)).build()
        return MediaItem.Builder().setUri(url ?: "cache://${meta.id}").apply { if (withCacheKey) setCustomCacheKey(cacheKey) }.setMediaId(meta.id).setMediaMetadata(m).build()
    }
    override fun queueNext(url: String?, cacheKey: String, meta: Track) {
        clearNext()
        val hls = isHls(url)
        val it = item(url, cacheKey, meta, withCacheKey = !hls)
        when {
            url?.startsWith("file:") == true || url?.startsWith("content:") == true ->
                exo.addMediaSource(androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(c)).createMediaSource(it))
            hls -> exo.addMediaSource(hlsFactory.createMediaSource(it))
            else -> exo.addMediaItem(it)
        }
    }
    override fun clearNext() { val n = exo.mediaItemCount; val cur = exo.currentMediaItemIndex; if (n > cur + 1) exo.removeMediaItems(cur + 1, n) }
    override fun play() = exo.play()
    override fun pause() = exo.pause()
    override fun seek(ms: Long) = exo.seekTo(ms)
    override val isPlaying get() = exo.isPlaying
    override val idle get() = exo.playbackState == Player.STATE_IDLE
    override val position get() = exo.currentPosition
    override val buffered get() = exo.bufferedPosition
    override fun isCached(key: String): Boolean {
        val len = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return len > 0 && cache.isCached(key, 0, len)
    }
    override fun cacheBytes() = cache.cacheSpace
    override fun clearCache() { cache.keys.toList().forEach { cache.removeResource(it) } }
    override var volume: Float get() = exo.volume; set(v) { exo.volume = v }

    /** 通知栏歌词：把当前 MediaItem 的「歌手」那行换成歌词（null = 恢复原样）。同一个地址只改元数据，不会重新加载 */
    fun setLyric(line: String?) {
        val item = exo.currentMediaItem ?: return
        val t = Engine.state.value.current ?: return
        if (item.mediaId != t.id) return
        val m = item.mediaMetadata.buildUpon().setArtist(line ?: t.artist).setSubtitle(line ?: t.artist).build()
        runCatching { exo.replaceMediaItem(exo.currentMediaItemIndex, item.buildUpon().setMediaMetadata(m).build()) }
    }

    override fun info(): AudioInfo? {
        val f = exo.audioFormat ?: return null
        val codec = when (f.sampleMimeType) { "audio/flac" -> "flac"; "audio/mpeg" -> "mp3"; "audio/mp4a-latm" -> "aac"; "audio/opus" -> "opus"; "audio/vorbis" -> "ogg"; "audio/raw" -> "pcm"; else -> f.sampleMimeType?.substringAfter('/') ?: "?" }
        var bits = when (f.pcmEncoding) { C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24; C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32; C.ENCODING_PCM_8BIT -> 8; else -> 0 }
        if (bits == 0 && codec == "flac") runCatching {
            val b = f.initializationData.firstOrNull() ?: return@runCatching
            val o = if (b.size >= 4 && b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte()) 8 else 0
            bits = (((b[o + 12].toInt() and 1) shl 4) or ((b[o + 13].toInt() and 0xF0) ushr 4)) + 1
        }
        val kbps = listOf(f.bitrate, f.averageBitrate, f.peakBitrate).firstOrNull { it > 0 }?.div(1000) ?: 0
        return AudioInfo(codec, f.sampleRate.coerceAtLeast(0), bits, f.channelCount.coerceAtLeast(0), kbps)
    }

    private var lastRateSpeed = 1f
    private var lastRatePitch = false

    override fun setRate(speed: Float, pitchLinked: Boolean) {
        val s = if (speed.isFinite()) speed.coerceIn(0.25f, 4f) else 1f
        val p = if (pitchLinked) s.coerceAtMost(3.2f) else 1f
        android.os.Handler(Looper.getMainLooper()).post {
            if (kotlin.math.abs(s - lastRateSpeed) < 0.02f && pitchLinked == lastRatePitch && s != 1f) return@post
            lastRateSpeed = s; lastRatePitch = pitchLinked
            runCatching { exo.playbackParameters = androidx.media3.common.PlaybackParameters(s, p) }
        }
    }

    override fun applyFx(fx: Fx) {
        val on = fx.on
        if (Build.VERSION.SDK_INT >= 28) runCatching {
            val d = dyn ?: android.media.audiofx.DynamicsProcessing(0, sessionId,
                android.media.audiofx.DynamicsProcessing.Config.Builder(android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    true, 10, false, 0, false, 0, false).build()).also { dyn = it }
            val eq = android.media.audiofx.DynamicsProcessing.Eq(true, true, 10)
            for (i in 0 until 10) {
                val upper = if (i < 9) kotlin.math.sqrt(Fx.FREQS[i].toFloat() * Fx.FREQS[i + 1]) else 20000f
                eq.getBand(i).apply { isEnabled = true; cutoffFrequency = upper; gain = fx.bands[i] }
            }
            d.setPreEqAllChannelsTo(eq)
            d.enabled = on
        } else runCatching {
            val e = eqOld ?: android.media.audiofx.Equalizer(0, sessionId).also { eqOld = it }
            val (lo, hi) = e.bandLevelRange.let { it[0] to it[1] }
            for (b in 0 until e.numberOfBands) {
                val center = e.getCenterFreq(b.toShort()) / 1000f
                val i = Fx.FREQS.indices.minBy { kotlin.math.abs(kotlin.math.ln(Fx.FREQS[it] / center)) }
                e.setBandLevel(b.toShort(), (fx.bands[i] * 100).toInt().coerceIn(lo.toInt(), hi.toInt()).toShort())
            }
            e.enabled = on
        }
        runCatching { (bassFx ?: android.media.audiofx.BassBoost(0, sessionId).also { bassFx = it }).apply { setStrength((fx.bass * 1000).toInt().toShort()); enabled = on && fx.bass > 0 } }
        runCatching { (virtFx ?: android.media.audiofx.Virtualizer(0, sessionId).also { virtFx = it }).apply { setStrength((fx.surround * 1000).toInt().toShort()); enabled = on && fx.surround > 0 } }
        runCatching { (loudFx ?: android.media.audiofx.LoudnessEnhancer(sessionId).also { loudFx = it }).apply { setTargetGain((fx.loud * 1000).toInt()); enabled = on && fx.loud > 0 } }
    }
}
