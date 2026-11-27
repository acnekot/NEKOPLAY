package cc.catsuki.nekoplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 核心流程：插件调用超时与失败恢复、搜索翻页去重、播放引擎连续失败停播 */
class CoreFlowTest {
    @BeforeTest fun setUp() { TestEnv.init() }
    @AfterTest fun tearDown() { JsSource.timeoutOverride = null; Plugins.delete("flowtest") }

    private val script = """
        // @id flowtest
        // @name 流程测试
        // @version 1.0.0
        // @search track,playlist
        nk.register({
          hang() { return new Promise(() => {}); },
          fast() { return 'ok'; },
          boom() { throw new Error('坏了'); },
          async search(q, type, page) {
            if (type === 'playlist') return { items: [{ id: 'p' + page, name: q + page, count: 3 }] };
            return [{ id: 's1', name: 'A' }, { id: 's' + page, name: 'B' + page }];
          },
        });
    """.trimIndent()

    @Test fun hungCallTimesOutAndEngineStaysUsable() = runBlocking {
        JsSource.timeoutOverride = { 300L }
        val s = Plugins.import(script)
        val t0 = System.currentTimeMillis()
        val e = assertFailsWith<NcmError> { s.call("hang") }
        assertTrue("超时" in (e.message ?: ""), e.message)
        assertTrue(System.currentTimeMillis() - t0 < 3000, "超时应该按设定时间返回")
        // 超时后锁要释放，后续调用照常；脚本异常也不影响下一次
        assertEquals("ok", s.call("fast"))
        assertFailsWith<NcmError> { s.call("boom") }
        assertEquals("ok", s.call("fast"))
    }

    @Test fun pluginSearchMapsItemsAndArrays() = runBlocking {
        Plugins.import(script)
        @Suppress("UNCHECKED_CAST") val pls = Plugins.search("flowtest", "jazz", "playlist", 2) as List<Playlist>
        assertEquals(listOf("jazz2"), pls.map { it.name })
        @Suppress("UNCHECKED_CAST") val ts = Plugins.search("flowtest", "x", "track", 1) as List<Track>
        assertEquals(2, ts.size)
    }

    @Test fun searchPagingDedupesAndStopsWhenNoNewItems() {
        fun tr(id: String) = Track(id, id, "a", "al", 1000, null)
        val p1 = SearchRes(0).plus(listOf(tr("1"), tr("2")), "track", 1)
        assertTrue(p1.more); assertEquals(2, p1.size)
        val p2 = p1.plus(listOf(tr("2"), tr("3")), "track", 2)
        assertEquals(listOf("1", "2", "3"), p2.tracks.map { it.id }); assertTrue(p2.more); assertEquals(2, p2.page)
        val p3 = p2.plus(listOf(tr("1"), tr("3")), "track", 3)
        assertFalse(p3.more, "整页都是重复条目就停止翻页"); assertEquals(3, p3.size)
        assertFalse(SearchRes(0).plus(emptyList(), "track", 1).more)
        assertFalse(p2.stopped().more); assertEquals(3, p2.stopped().size)
    }

    @Test fun myPlaylistsMatchesCaseInsensitively() {
        val all = listOf(Playlist("1", "Night Jazz", 3, null), Playlist("2", "晨跑", 5, null), Playlist("3", "jazz classics", 9, null))
        assertEquals(listOf("1", "3"), myPlaylists(" JAZZ ", all).map { it.id })
        assertTrue(myPlaylists("", all).isEmpty())
        assertEquals(listOf("2"), myPlaylists("晨", all).map { it.id })
    }

    /** 假音频内核：只记下打开了哪首，错误 / 出声由测试手动触发 */
    private class FakeAudio : Audio {
        override var listener: Audio.Listener? = null
        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun open(url: String?, cacheKey: String, startMs: Long, autoplay: Boolean, meta: Track) { opened += meta.id }
        override fun play() {}
        override fun pause() {}
        override fun seek(ms: Long) {}
        override val isPlaying = false
        override val idle = true
        override val position = 0L
        override val buffered = 0L
        override fun isCached(key: String) = false
        override fun cacheBytes() = 0L
        override fun clearCache() {}
        override var volume = 1f
        override fun applyFx(fx: Fx) {}
    }

    @Test fun engineStopsAfterThreeConsecutiveFailuresAndResetsOnPlayback() = runBlocking {
        val real = Plat.impl
        val fake = FakeAudio()
        Plat.impl = object : PlatImpl by real { override val audio: Audio = fake }
        try {
            Store.init(); Engine.init()
            val list = (0 until 6).map { Track("loc$it", "T$it", "A", "Al", 1000, null, src = "file:///nonexistent/$it.mp3") }
            suspend fun waitOpened(n: Int) = withTimeout(5000) { while (fake.opened.size < n) kotlinx.coroutines.delay(10) }
            suspend fun fail() = withContext(Dispatchers.Main) { fake.listener!!.onError("decode") }

            withContext(Dispatchers.Main) { Engine.playList(list, 0) }
            waitOpened(1)
            fail(); waitOpened(2)                      // 第 1 次失败：自动跳到下一首
            assertEquals(1, Engine.state.value.index)
            withContext(Dispatchers.Main) { fake.listener!!.onPlaying(true) }   // 出声了：失败计数清零
            fail(); waitOpened(3); fail(); waitOpened(4)
            assertEquals(3, Engine.state.value.index)
            assertTrue(Engine.state.value.want, "清零后还没到 3 次，应继续自动播下一首")
            fail()                                      // 清零后的第 3 次：停播
            withTimeout(2000) { while (Engine.state.value.want) kotlinx.coroutines.delay(10) }
            assertEquals(4, fake.opened.size, "停播后不应再打开新的曲目")
            assertTrue(Engine.error.value?.startsWith("连续播放失败已暂停") == true, Engine.error.value)
        } finally {
            Plat.impl = real
        }
    }
}
