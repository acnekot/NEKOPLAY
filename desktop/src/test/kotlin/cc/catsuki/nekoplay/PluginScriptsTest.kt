package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.Plugins
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 仓库里的官方插件：都能加载、头注释齐全、订阅列表 index.json 和脚本一致（不联网） */
class PluginScriptsTest {
    private val dir = File(TestEnv.repo, "plugins")
    private val scripts = dir.listFiles { f -> f.extension == "js" }!!.sortedBy { it.name }

    @Test fun everyPluginLoadsWithMetadata() = runBlocking {
        TestEnv.init()
        assertTrue(scripts.isNotEmpty())
        for (f in scripts) {
            val s = Plugins.import(f.readText())
            try {
                assertEquals(f.nameWithoutExtension, s.id, "${f.name} 的 @id 要和文件名一致")
                assertTrue(s.name.isNotBlank() && Regex("""^\d+\.\d+\.\d+$""").matches(s.version), "${f.name} 要有 @name 和 x.y.z 的 @version")
                if (s.isFeature) {
                    assertTrue(s.caps.isNotEmpty(), "${f.name} 功能插件至少要通过 nk.registerFeature 导出一个能力：${s.caps}")
                } else {
                    assertTrue(s.has("search") && s.has("stream"), "${f.name} 音源插件至少要能搜索和播放：${s.caps}")
                }
            } finally { Plugins.delete(s.id) }
        }
    }

    @Test fun indexJsonMatchesScripts() {
        val idx = JSONObject(File(dir, "index.json").readText()).getJSONArray("plugins")
        val byId = (0 until idx.length()).map { idx.getJSONObject(it) }.associateBy { it.getString("id") }
        for (f in scripts) {
            val head = f.readLines().take(40)
            fun meta(k: String) = head.firstNotNullOfOrNull { Regex("""^\s*//\s*@$k\s+(.*?)\s*$""").find(it)?.groupValues?.get(1) }
            val e = assertNotNull(byId[meta("id")], "index.json 里没有 ${f.name}：改完插件要运行 python3 plugins/gen-index.py")
            assertEquals(meta("version"), e.getString("version"), "${f.name} 的版本号和 index.json 不一致：运行 python3 plugins/gen-index.py")
            assertEquals(f.name, e.getString("url"))
        }
        assertEquals(scripts.size, idx.length(), "index.json 里有已经删掉的插件")
    }
}
