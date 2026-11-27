package cc.catsuki.nekoplay

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 媒体规则：插件声明的 CDN 请求头与封面缩略图参数，替代宿主里写死的平台逻辑 */
class MediaHeadersTest {
    @AfterTest fun tearDown() { MediaHeaders.override = null }

    private val sample = """[
      { "hosts": ["img.example.com", "example.org"], "headers": { "Referer": "https://www.example.com/", "Origin": "https://www.example.com", "Cookie": "x=1", "X-Evil": "1" },
        "image": { "contains": "/obj/", "query": "thumb={s}x{s}&type=webp", "sizes": [160, 256, 512, 768], "keepHttp": true } },
      { "hosts": ["img.example.com"], "image": { "query": "param={s}y{s}", "sizes": [160, 256, 512, 768], "keepHttp": true } },
      { "hosts": ["pic.example.net"], "image": { "from": "/480/", "to": "/{s}/", "sizes": [150, 240, 480] } },
      { "hosts": ["localhost", "192.168.1.2"], "headers": { "Referer": "https://x/" } },
      { "hosts": ["bad.example.com"], "headers": { "Referer": "a\r\nInjected: 1" } }
    ]"""

    @Test fun parseKeepsOnlySafeRules() {
        val r = MediaHeaders.parse(sample)
        assertEquals(3, r.size, "本机地址、带换行注入的规则应被丢弃")
        assertEquals(setOf("Referer", "Origin"), r[0].headers.keys, "只允许 Referer / Origin / User-Agent")
        assertTrue(MediaHeaders.parse("not json").isEmpty())
    }

    @Test fun headersMatchBySuffix() {
        MediaHeaders.override = MediaHeaders.parse(sample)
        assertEquals("https://www.example.com/", MediaHeaders.forHost("p3.img.example.com")["Referer"])
        assertEquals("https://www.example.com/", MediaHeaders.forUrl("https://cdn.example.org/a.mp3?x=1")["Referer"])
        assertTrue(MediaHeaders.forHost("notexample.org").isEmpty(), "后缀匹配必须按域名层级，不能子串匹配")
        assertTrue(MediaHeaders.forHost("other.test").isEmpty())
    }

    @Test fun resizeFollowsPluginRules() {
        MediaHeaders.override = MediaHeaders.parse(sample)
        assertEquals("http://p1.img.example.com/a.jpg?param=256y256", MediaHeaders.resize("http://p1.img.example.com/a.jpg", 200), "keepHttp 时不升级 https")
        assertEquals("https://p1.img.example.com/obj/x.jpg?thumb=160x160&type=webp", MediaHeaders.resize("https://p1.img.example.com/obj/x.jpg", 100))
        assertEquals("https://p1.img.example.com/a.jpg?param=512y512", MediaHeaders.resize("https://p1.img.example.com/a.jpg?param=100y100", 512), "原有尺寸参数被替换")
        assertEquals("https://pic.example.net/std/240/1.jpg", MediaHeaders.resize("http://pic.example.net/std/480/1.jpg", 200))
        assertEquals("https://pic.example.net/std/1.jpg?k=v", MediaHeaders.resize("http://pic.example.net/std/1.jpg?k=v", 200), "不含 from 时只升级 https")
        assertEquals("https://other.test/a.jpg", MediaHeaders.resize("http://other.test/a.jpg", 200))
        assertEquals("https://other.test/a.jpg?s=1", MediaHeaders.resize("https://other.test/a.jpg?s=1", 200))
    }

    /** 本机有官方插件仓库时（NEKOPLAY_PLUGINS_DIR 或与主仓库同级），校验各音源插件声明的规则能被读出 */
    @Test fun officialPluginsDeclareMediaRules() = runBlocking {
        val dir = (System.getenv("NEKOPLAY_PLUGINS_DIR")?.let(::File) ?: File(TestEnv.repo.parentFile, "NEKOPLAY_PLUGINS")).takeIf { File(it, "index.json").isFile }
            ?: return@runBlocking
        TestEnv.init()
        for (name in listOf("netease", "bilibili", "kugou", "qqmusic")) {
            val f = File(dir, "$name.js").takeIf { it.isFile } ?: continue
            val s = Plugins.import(f.readText())
            try {
                assertTrue(s.start(), "$name 启动失败：${s.error}")
                assertTrue(s.media.isNotEmpty(), "$name 应声明 media 规则")
                assertTrue(s.media.all { r -> r.hosts.isNotEmpty() && (r.headers.isNotEmpty() || r.image != null) }, name)
            } finally { Plugins.delete(s.id) }
        }
    }
}
