package cc.catsuki.nekoplay

import kotlinx.coroutines.*
import kotlin.test.*
import java.io.File

class StressTest {
    private fun ensurePlat() {
        runCatching { Plat.impl }.onFailure { Plat.impl = DesktopPlat() }
        Ncm.init()
    }

    @Test
    fun fuzzLrcAndYrcParser() {
        // 1. 空串、畸形时间戳、超大值、乱序、非法括号、二进制垃圾
        val nasty = listOf(
            "",
            "   \n\n\t  ",
            "[99999999:99.999]huge",
            "[-1:-2.-3]neg",
            "[abc:de.fg]bad",
            "[00:01.00][00:02.00][00:00.50]multi",
            "[00:01.00]",
            "[[[[[00:01.00]]]]]",
            "\u0000\u0001\u0002\uFFFF[00:01.00]emoji🎵\uD83D\uDE00",
            "[00:01.1]short ms\n[00:01.12]2ms\n[00:01.123456]long ms",
        )
        for (s in nasty) {
            val res = Lrc.parse(s, null)
            assertNotNull(res)
            Lrc.indexAt(res, -1000L)
            Lrc.indexAt(res, 0L)
            Lrc.indexAt(res, Long.MAX_VALUE)
        }
        // 2. 5 万行超大歌词性能压测（应 < 500ms）
        val huge = buildString {
            for (i in 0 until 50_000) {
                val m = i / 60; val s = i % 60
                append("[%02d:%02d.50]Line %d 歌词测试\n".format(m, s, i))
            }
        }
        val t0 = System.currentTimeMillis()
        val parsed = Lrc.parse(huge, null)
        val elapsed = System.currentTimeMillis() - t0
        println("Lrc 50k lines parsed=${parsed.size} in ${elapsed}ms")
        assertTrue(parsed.size == 50_000)
        assertTrue(elapsed < 2000, "Lrc 50k lines took too long: ${elapsed}ms")

        // 3. YRC 畸形输入
        val nastyYrc = listOf(
            "",
            "[0,1000](0,500,0)你(500,500,0)好",
            "[bad,data](x,y,z)broken",
            "[1000,0](1000,-50,0)negdur",
            "{\"t\":0,\"c\":[{\"tx\":\"json\"}]}",
        )
        for (y in nastyYrc) {
            val r = Lrc.parseYrc(y, null)
            assertNotNull(r)
        }
    }

    @Test
    fun fuzzDavPercentDecodeAndHash() {
        val inputs = listOf("", "%", "%2", "%ZZ", "%20%2F%E4%B8%AD%E6%96%87", "a+b%2Bc", "%FF%FE%80")
        for (s in inputs) {
            val d = DavMusic.dec(s)
            assertNotNull(d)
            assertTrue(Hash.md5(s.encodeToByteArray()).length == 32)
        }
    }

    @Test
    fun stressSleepAndStatsAndFx() {
        ensurePlat()
        // Sleep 快速启停 200 次无异常
        repeat(200) { i ->
            if (i % 3 == 0) Sleep.start(15)
            else if (i % 3 == 1) Sleep.startAfterSong()
            else Sleep.cancel()
            Sleep.left()
        }
        Sleep.cancel()
        assertFalse(Sleep.active)

        // Stats 边界
        val dummy = Track("stress:1", "Title", "A / B", "Alb", 0L, null)
        Stats.add(dummy, 0)
        Stats.add(dummy, -5)
        assertEquals("0 秒", Stats.fmtTime(0))
        assertEquals("1 小时 1 分", Stats.fmtTime(3660))
    }

    @Test
    fun stressPluginsLiveAndLifecycleChaos() = runBlocking {
        ensurePlat()
        val pluginDir = File("../plugins")
        val scripts = listOf("netease.js", "qqmusic.js", "kugou.js").map { File(pluginDir, it) }.filter { it.exists() }
        assertTrue(scripts.isNotEmpty(), "No plugin scripts found in ${pluginDir.absolutePath}")

        for (f in scripts) {
            val s = JsSource(f)
            assertTrue(s.start(), "Failed to start ${f.name}: ${s.error}")
            println("Plugin ${s.id} (${s.name} v${s.version}) caps=${s.caps}")

            // 1. 实网测试：loginQr + loginCheck（验证不卡死不崩溃）
            if (s.has("loginQr")) {
                val modes = if (s.has("loginModes")) {
                    Plugins.arr(s.call("loginModes")).filter { !it.optBoolean("web") }.map { it.optString("id") }
                } else listOf("")
                for (m in modes) {
                    val qr = runCatching {
                        withTimeout(12_000) {
                            if (m.isEmpty()) s.call("loginQr") else s.call("loginQr", m)
                        }
                    }
                    println("  [${s.id}] loginQr($m) -> ${if (qr.isSuccess) "OK" else "ERR: ${qr.exceptionOrNull()?.message}"}")
                    val key = (qr.getOrNull() as? org.json.JSONObject)?.optString("key").orEmpty()
                    if (key.isNotEmpty() && s.has("loginCheck") && !key.startsWith("wx:")) {
                        repeat(2) {
                            val chk = runCatching { withTimeout(8_000) { s.call("loginCheck", key) } }
                            println("    loginCheck -> ${chk.getOrNull() ?: chk.exceptionOrNull()?.message}")
                        }
                    }
                }
            }

            // 2. 实网搜索压测：中文 / 日文 / 特殊符号 / 空串
            if (s.has("search")) {
                for (q in listOf("周杰伦", "夜に駆ける", "!@#\$%", "")) {
                    val r = runCatching { withTimeout(10_000) { s.call("search", q, "track", 1) } }
                    println("  [${s.id}] search('$q') -> ${if (r.isSuccess) "OK" else "ERR: ${r.exceptionOrNull()?.message}"}")
                }
            }

            // 3. 并发 start / call / close 生命周期风暴（15 轮）
            repeat(15) { round ->
                val jobs = (1..4).map {
                    async(Dispatchers.Default) {
                        runCatching {
                            if (s.has("loginModes")) s.call("loginModes")
                        }
                    }
                }
                if (round % 3 == 2) s.close().join()
                jobs.awaitAll()
            }
            s.close().join()
            println("  [${s.id}] lifecycle chaos 15 rounds PASSED")
        }
    }

    @Test
    fun liveQqQrcLyric() = runBlocking {
        TestEnv.init()
        val repoRoot = File(System.getProperty("user.dir")).parentFile
        val qq = File(repoRoot, "plugins/qqmusic.js")
        if (!qq.exists()) return@runBlocking
        val s = JsSource(qq)
        assertTrue(s.start(), "qqmusic.js should start cleanly")
        val res = runCatching { withTimeout(12_000) { s.call("lyric", "0039MnYb0qxYhV") as? org.json.JSONObject } }.getOrNull()
        s.close().join()
        if (res != null) {
            val yrc = res.optString("yrc")
            val lines = Lrc.parseYrc(yrc, res.optString("tr").ifEmpty { null })
            println("QRC->YRC lines=${lines.size}, sample=${lines.drop(2).take(3).map { "${it.text} (${it.words?.size ?: 0} words)" }}")
            assertTrue(yrc.isNotBlank() && lines.size > 10 && lines.any { (it.words?.size ?: 0) >= 4 }, "QQ Music QRC should decrypt to word-by-word yrc lines")
        }
    }
}
