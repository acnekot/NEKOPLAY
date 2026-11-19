package cc.catsuki.nekoplay

data class Word(val t: Long, val d: Long, val s: String)
data class LyricLine(val t: Long, val text: String, val tr: String?, val words: List<Word>? = null)

object Lrc {
    private val ts = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    private fun parseRaw(text: String): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        for (raw in text.lines()) {
            val line = raw.trim()
            val ms = ts.findAll(line).toList()
            if (ms.isEmpty()) continue
            val content = line.substring(ms.last().range.last + 1).trim()
            for (m in ms) {
                val f = m.groupValues[3].let { if (it.isEmpty()) 0 else it.padEnd(3, '0').take(3).toInt() }
                out += (m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 + f) to content
            }
        }
        return out.sortedBy { it.first }
    }

    /** 原文 + 译文合并；连续空行和开头的空行去掉，中间的空行保留为间奏 */
    fun parse(lrc: String, tr: String?): List<LyricLine> {
        val trMap = tr?.let { parseRaw(it).filter { p -> p.second.isNotEmpty() }.toMap() } ?: emptyMap()
        val out = ArrayList<LyricLine>()
        for ((t, s) in parseRaw(lrc)) {
            if (s.isEmpty() && (out.isEmpty() || out.last().text.isEmpty())) continue
            out += LyricLine(t, s, trMap[t])
        }
        while (out.isNotEmpty() && out.last().text.isEmpty()) out.removeAt(out.lastIndex)
        return out
    }

    private val yLine = Regex("""^\[(\d+),(\d+)]""")
    private val yWord = Regex("""\((\d+),(\d+),-?\d+\)""")

    /** 逐字歌词 yrc：[行开始,行时长](字开始,字时长,0)字…；翻译按时间就近（1.5 秒内）对上 */
    fun parseYrc(yrc: String, tr: String?): List<LyricLine> {
        val trList = tr?.let { parseRaw(it).filter { p -> p.second.isNotEmpty() } } ?: emptyList()
        val out = ArrayList<LyricLine>()
        var lastEnd = 0L
        for (raw in yrc.lines()) {
            val line = raw.trim()
            val m = yLine.find(line) ?: continue
            val t = m.groupValues[1].toLong(); val d = m.groupValues[2].toLong()
            val body = line.substring(m.range.last + 1)
            val ms = yWord.findAll(body).toList()
            val words = ms.mapIndexed { i, w ->
                val end = if (i + 1 < ms.size) ms[i + 1].range.first else body.length
                Word(w.groupValues[1].toLong(), w.groupValues[2].toLong(), body.substring(w.range.last + 1, end))
            }.filter { it.s.isNotEmpty() }
            val text = words.joinToString("") { it.s }
            if (text.isBlank()) continue
            // 和上一句之间空了 6 秒以上：插一个间奏点
            if (out.isNotEmpty() && t - lastEnd > 6000) out += LyricLine(lastEnd, "", null)
            val trT = trList.minByOrNull { kotlin.math.abs(it.first - t) }?.takeIf { kotlin.math.abs(it.first - t) < 1500 }?.second
            out += LyricLine(t, text, trT, words)
            lastEnd = t + d
        }
        return out
    }

    fun indexAt(lines: List<LyricLine>, pos: Long): Int {
        var lo = 0; var hi = lines.size - 1; var r = 0
        while (lo <= hi) { val m = (lo + hi) / 2; if (lines[m].t <= pos) { r = m; lo = m + 1 } else hi = m - 1 }
        return r
    }
}
