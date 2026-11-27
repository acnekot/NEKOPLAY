package cc.catsuki.nekoplay

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
}
