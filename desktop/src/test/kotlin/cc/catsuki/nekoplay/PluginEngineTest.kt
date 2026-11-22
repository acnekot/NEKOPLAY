package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.data.NcmError
import cc.catsuki.nekoplay.data.Plugins
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 插件引擎：脚本加载、宿主接口（http / store / md5）、并发、出错 */
class PluginEngineTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() {
        TestEnv.init()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/slow" -> MockResponse().setBody("""{"v":"slow"}""").setBodyDelay(2, TimeUnit.SECONDS)
                "/echo" -> MockResponse().setBody(JSONObject().put("method", request.method).put("body", request.body.readUtf8()).put("ua", request.getHeader("User-Agent")).toString())
                    .addHeader("Set-Cookie", "k=v; Path=/")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }
    @AfterTest fun tearDown() { Plugins.delete("unittest"); server.shutdown() }

    private fun script(base: String) = """
        // @id unittest
        // @name 测试插件
        // @version 1.0.0
        // @api 1
        nk.register({
          async echo(x) { const r = await nk.http({ url: '$base/echo', form: { a: x } }); return { status: r.status, json: r.json(), cookies: r.cookies() }; },
          async slow() { return (await nk.http('$base/slow')).json(); },
          fast() { return 'fast'; },
          remember(v) { nk.store.set('k', v); return nk.store.get('k'); },
          md5(s) { return nk.md5(s); },
          boom() { throw new Error('出错了'); },
        });
    """.trimIndent()

    @Test fun metaAndCapsAreRead() = runBlocking {
        val s = Plugins.import(script(server.url("").toString().trimEnd('/')))
        assertEquals("unittest", s.id)
        assertEquals("测试插件", s.name)
        assertEquals("1.0.0", s.version)
        assertTrue(s.has("echo") && s.has("fast") && !s.has("nothing"))
        // 没写 @search 时默认能搜单曲 / 专辑 / 歌单
        assertEquals(setOf("track", "album", "playlist"), s.searchTypes)
    }

    @Test fun hostHttpStoreAndMd5() = runBlocking {
        val s = Plugins.import(script(server.url("").toString().trimEnd('/')))
        val r = s.call("echo", "你好") as JSONObject
        assertEquals(200, r.getInt("status"))
        assertEquals("POST", r.getJSONObject("json").getString("method"))
        assertEquals("a=%E4%BD%A0%E5%A5%BD", r.getJSONObject("json").getString("body"))
        assertEquals("v", r.getJSONObject("cookies").getString("k"))
        assertEquals(JSONObject().put("n", 1).toString(), (s.call("remember", JSONObject().put("n", 1)) as JSONObject).toString())
        assertEquals("e10adc3949ba59abbe56e057f20f883e", s.call("md5", "123456"))
    }

    @Test fun slowCallDoesNotBlockOthers() = runBlocking {
        val s = Plugins.import(script(server.url("").toString().trimEnd('/')))
        val slow = async { s.call("slow") }
        kotlinx.coroutines.delay(200)
        val t0 = System.currentTimeMillis()
        assertEquals("fast", s.call("fast"))
        assertTrue(System.currentTimeMillis() - t0 < 1000, "慢调用在跑的时候，别的调用应该马上返回")
        assertEquals("slow", (slow.await() as JSONObject).getString("v"))
    }

    @Test fun scriptErrorsBecomeNcmError() = runBlocking {
        val s = Plugins.import(script(server.url("").toString().trimEnd('/')))
        val e = assertFailsWith<NcmError> { s.call("boom") }
        assertEquals("出错了", e.message)
    }
}
