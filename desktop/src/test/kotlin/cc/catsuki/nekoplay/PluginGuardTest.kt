package cc.catsuki.nekoplay

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 插件守门：调用超时分级、响应体大小上限、调试日志凭据打码 */
class PluginGuardTest {
    @Test fun callTimeoutTiers() {
        assertEquals(180_000L, JsSource.callTimeout("loginQr"))
        assertEquals(180_000L, JsSource.callTimeout("loginCheck"))
        assertEquals(120_000L, JsSource.callTimeout("myPlaylists"))
        assertEquals(60_000L, JsSource.callTimeout("search"))
    }

    @Test fun readCappedAllowsSmallAndRejectsLarge() {
        val small = ByteArray(200_000) { (it % 251).toByte() }
        val got = JsSource.readCapped(small.toResponseBody("application/octet-stream".toMediaType()), 1L shl 20)
        assertTrue(small.contentEquals(got))
        val big = ByteArray((1 shl 20) + 1)
        assertFailsWith<NcmError> { JsSource.readCapped(big.toResponseBody(), 1L shl 20) }
    }

    @Test fun redactMasksSecrets() {
        val j = Debug.redact("""{"access_token":"abcdef123456","name":"cat","cookie":"MUSIC_U=zzzzzzzz; os=pc"}""")
        assertFalse("abcdef123456" in j); assertTrue("\"abcd***\"" in j); assertTrue("\"name\":\"cat\"" in j)
        assertFalse("zzzzzzzz" in j)
        val u = Debug.redact("https://x.test/api?client_id=1&oauth_token=2-123456-abcdef&q=hi")
        assertFalse("2-123456-abcdef" in u); assertTrue("q=hi" in u); assertTrue("client_id=1" in u)
        assertEquals("plugin loaded", Debug.redact("plugin loaded"))
        assertFalse("Bearer-xyz-987" in Debug.redact("Authorization: Bearer-xyz-987"))
    }

    @Test fun privateHostDetection() {
        listOf("http://localhost:8080/x", "http://127.0.0.1/", "https://10.1.2.3/a", "http://192.168.1.5:4533", "http://172.20.0.1/",
            "http://169.254.169.254/latest", "http://[::1]:80/", "http://nas.local/", "http://user:pw@192.168.0.2/").forEach {
            assertTrue(JsSource.isPrivateHost(JsSource.hostOf(it)), it)
        }
        listOf("https://api.example.com/x", "https://8.8.8.8/", "https://172.32.0.1/", "https://192.169.0.1/", "https://music.test:443/a?b=1").forEach {
            assertFalse(JsSource.isPrivateHost(JsSource.hostOf(it)), it)
        }
    }

    private fun plugin(id: String, api: Int, type: String, perms: String?) = """
        // @id $id
        // @name $id
        // @type $type
        // @version 1.0.0
        // @api $api
        ${if (perms != null) "// @permissions $perms" else ""}
        nk.${if (type == "source") "register" else "registerFeature"}({ async get(u) { return (await nk.http(u)).status; } });
    """.trimIndent()

    @Test fun v2PluginsNeedNetworkAndLanPermissions() = runBlocking {
        TestEnv.init()
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("ok")); enqueue(MockResponse().setBody("ok")); start() }
        val url = server.url("/x").toString()
        val ids = mutableListOf<String>()
        try {
            // v2 音源没声明 network：拦截
            val noNet = Plugins.import(plugin("_g_nonet", 2, "source", "storage")).also { ids += it.id }
            assertFalse(noNet.canNetwork)
            assertTrue(runCatching { noNet.call("get", url) }.exceptionOrNull()?.message?.contains("network") == true)
            // v2 声明了 network 但没声明 lan：内网地址拦截
            val net = Plugins.import(plugin("_g_net", 2, "source", "network, storage")).also { ids += it.id }
            assertTrue(net.canNetwork); assertFalse(net.canLan)
            assertTrue(runCatching { net.call("get", url) }.exceptionOrNull()?.message?.contains("lan") == true)
            // v2 声明了 network + lan：放行
            val lan = Plugins.import(plugin("_g_lan", 2, "source", "network, lan")).also { ids += it.id }
            assertEquals(200, (lan.call("get", url) as Number).toInt())
            // v1 旧脚本：保持兼容
            val v1 = Plugins.import(plugin("_g_v1", 1, "source", null)).also { ids += it.id }
            assertTrue(v1.canNetwork && v1.canLan)
            assertEquals(200, (v1.call("get", url) as Number).toInt())
        } finally {
            ids.forEach { Plugins.delete(it) }; server.shutdown()
        }
    }
}
