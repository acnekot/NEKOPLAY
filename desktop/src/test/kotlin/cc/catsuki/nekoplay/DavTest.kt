package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.data.DavMusic
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DavTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() { TestEnv.init(); server = MockWebServer().apply { start() } }
    @AfterTest fun tearDown() { server.shutdown() }

    private val multistatus = """<?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response><D:href>/music/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
          <D:response><D:href>/music/%E5%91%A8%E6%9D%B0%E4%BC%A6/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
          <D:response><D:href>/music/%E5%91%A8%E6%9D%B0%E4%BC%A6%20-%20%E6%99%B4%E5%A4%A9.flac</D:href><D:propstat><D:prop><D:resourcetype/><D:getcontentlength>1234</D:getcontentlength></D:prop></D:propstat></D:response>
          <D:response><D:href>/music/notes.txt</D:href><D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
        </D:multistatus>"""

    @Test fun listsFoldersFirstAndOnlyAudio() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))
        DavMusic.save(server.url("/music").toString(), "neko", "p@ss word")
        val l = DavMusic.list(DavMusic.root)
        assertEquals(listOf("周杰伦", "周杰伦 - 晴天.flac"), l.map { it.name })
        assertTrue(l[0].dir)
        assertEquals(1234L, l[1].size)
        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("1", req.getHeader("Depth"))
        assertTrue(req.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test fun trackSplitsArtistAndTitle() {
        DavMusic.save("https://pan.example.com/dav", "", "")
        val t = DavMusic.trackOf(DavMusic.Entry("周杰伦 - 晴天.flac", "/dav/%E4%B8%93%E8%BE%91/a.flac", false, 0))
        assertEquals("周杰伦", t.artist)
        assertEquals("晴天", t.title)
        assertEquals("专辑", t.album)
        assertEquals("dav:/dav/%E4%B8%93%E8%BE%91/a.flac", t.src)
        // 没有「 - 」的文件名：整个当歌名
        assertEquals("纯音乐", DavMusic.trackOf(DavMusic.Entry("纯音乐.mp3", "/x.mp3", false, 0)).title)
    }

    @Test fun playUrlCarriesEncodedCredentials() {
        DavMusic.save("https://pan.example.com:8443/dav", "neko@home", "p@ss word")
        assertEquals("https://neko%40home:p%40ss%20word@pan.example.com:8443/dav/a.flac", DavMusic.playUrl("dav:/dav/a.flac"))
        DavMusic.save("http://192.168.1.2:5244/dav", "", "")
        assertEquals("http://192.168.1.2:5244/dav/a.flac", DavMusic.playUrl("dav:/dav/a.flac"))
    }

    @Test fun percentDecodingKeepsPlusAndUtf8() {
        assertEquals("a+b 周", DavMusic.dec("a+b%20%E5%91%A8"))
        assertEquals("100%", DavMusic.dec("100%"))
    }
}
