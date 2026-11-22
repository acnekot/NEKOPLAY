package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.Lrc
import cc.catsuki.nekoplay.LyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LrcTest {
    @Test fun parsesTimestampsAndSortsLines() {
        val l = Lrc.parse("[00:12.50]第二句\n[00:01.00]第一句\n[ti:标题]\n", null)
        assertEquals(listOf(1000L, 12500L), l.map { it.t })
        assertEquals(listOf("第一句", "第二句"), l.map { it.text })
    }

    @Test fun fractionDigitsAreMilliseconds() {
        // [mm:ss.x] / [mm:ss.xx] / [mm:ss.xxx] 都按毫秒补齐
        val l = Lrc.parse("[00:01.5]a\n[00:02.05]b\n[00:03.123]c\n[01:00]d", null)
        assertEquals(listOf(1500L, 2050L, 3123L, 60000L), l.map { it.t })
    }

    @Test fun repeatedTimestampsShareOneText() {
        val l = Lrc.parse("[00:01.00][00:05.00]副歌", null)
        assertEquals(listOf(1000L, 5000L), l.map { it.t })
        assertTrue(l.all { it.text == "副歌" })
    }

    @Test fun translationIsMatchedByTimestamp() {
        val l = Lrc.parse("[00:01.00]hello\n[00:02.00]world", "[00:01.00]你好\n[00:03.00]对不上")
        assertEquals("你好", l[0].tr)
        assertNull(l[1].tr)
    }

    @Test fun leadingTrailingAndRepeatedBlankLinesAreDropped() {
        val l = Lrc.parse("[00:00.00]\n[00:01.00]a\n[00:02.00]\n[00:03.00]\n[00:04.00]b\n[00:05.00]", null)
        // 中间只留一个空行当间奏
        assertEquals(listOf("a", "", "b"), l.map { it.text })
    }

    @Test fun yrcWordsAndInterlude() {
        val yrc = "[1000,2000](1000,500,0)你(1500,500,0)好\n[10000,1000](10000,1000,0)再见"
        val l = Lrc.parseYrc(yrc, "[00:01.00]hello")
        assertEquals("你好", l[0].text)
        assertEquals(2, l[0].words?.size)
        assertEquals("hello", l[0].tr)
        // 上一句 3 秒结束，下一句 10 秒开始：中间插一个间奏点
        assertEquals("", l[1].text)
        assertEquals(3000L, l[1].t)
        assertEquals("再见", l[2].text)
    }

    @Test fun indexAtFindsCurrentLine() {
        val lines = listOf(LyricLine(0, "a", null), LyricLine(1000, "b", null), LyricLine(2000, "c", null))
        assertEquals(0, Lrc.indexAt(lines, 500))
        assertEquals(1, Lrc.indexAt(lines, 1000))
        assertEquals(2, Lrc.indexAt(lines, 99999))
    }
}
