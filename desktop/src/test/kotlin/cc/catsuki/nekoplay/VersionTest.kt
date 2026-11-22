package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.core.APP_VERSION
import cc.catsuki.nekoplay.core.Hash
import cc.catsuki.nekoplay.core.Updater
import cc.catsuki.nekoplay.data.PluginSubs
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionTest {
    @Test fun appVersionIsSemver() {
        assertTrue(Regex("""^\d+\.\d+\.\d+$""").matches(APP_VERSION), "APP_VERSION 要是 x.y.z：$APP_VERSION")
    }

    @Test fun newerComparesNumerically() {
        assertTrue(PluginSubs.newer("1.10.0", "1.9.9"))
        assertTrue(PluginSubs.newer("1.2.1", "1.2"))
        assertTrue(PluginSubs.newer("2", "1.99.99"))
        assertFalse(PluginSubs.newer("1.0", "1.0.0"))
        assertFalse(PluginSubs.newer("1.2.0", "1.2.1"))
        assertFalse(PluginSubs.newer("abc", "0"))
    }

    @Test fun epochParsesIsoTimestamps() {
        assertEquals(0L, Updater.epoch("1970-01-01T00:00:00Z"))
        assertEquals(1795272800L, Updater.epoch("2026-11-21T14:53:20Z"))
        // 带时区偏移：+08:00 比 UTC 早 8 小时
        assertEquals(Updater.epoch("2026-11-21T06:53:20Z"), Updater.epoch("2026-11-21T14:53:20+08:00"))
        assertEquals(0L, Updater.epoch("不是时间"))
    }

    @Test fun md5MatchesJdk() {
        val r = Random(42)
        repeat(50) {
            val s = buildString { repeat(r.nextInt(0, 200)) { append(Char(r.nextInt(32, 0x4E00 + 200))) } }
            val want = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals(want, Hash.md5(s), "md5(\"$s\")")
        }
    }
}
