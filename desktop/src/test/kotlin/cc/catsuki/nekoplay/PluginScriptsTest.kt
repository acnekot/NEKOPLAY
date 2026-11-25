package cc.catsuki.nekoplay

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 插件加载与规范校验：内置最小 source/feature 样例必跑；若本机旁路存在 NEKOPLAY_PLUGINS 仓库也会顺带校验 */
class PluginScriptsTest {
    private val extDir = listOf(File(TestEnv.repo, "plugins"), File(TestEnv.repo.parentFile, "NEKOPLAY_PLUGINS"))
        .firstOrNull { it.isDirectory && File(it, "index.json").isFile }

    @Test fun builtinSourceAndFeatureFixturesLoad() = runBlocking {
        TestEnv.init()
        val srcScript = """
            // @id _test_src
            // @name Test Source
            // @type source
            // @version 1.0.0
            // @api 1
            nk.register({ search() { return []; }, stream() { return null; } });
        """.trimIndent()
        val featScript = """
            // @id _test_feat
            // @name Test Feature
            // @type feature
            // @feature demo
            // @version 1.0.0
            // @api 1
            nk.registerFeature({ manifest() { return { type: 'feature', feature: 'demo' }; }, action() { return { open: 'demo' }; } });
        """.trimIndent()
        val s1 = Plugins.import(srcScript)
        try {
            assertEquals("_test_src", s1.id)
            assertTrue(!s1.isFeature && s1.has("search") && s1.has("stream"))
        } finally { Plugins.delete(s1.id) }
        val s2 = Plugins.import(featScript)
        try {
            assertEquals("_test_feat", s2.id)
            assertTrue(s2.isFeature && Plugins.hasFeature("demo") && s2.caps.isNotEmpty())
        } finally { Plugins.delete(s2.id) }
        val motionScript = """
            // @id _test_motion
            // @name Test Motion
            // @type motion
            // @version 1.0.0
            // @api 2
            nk.registerMotion({ profile() { return { name: 'test', page: { easing: 'outExpoTail', ms: 600 } }; } });
        """.trimIndent()
        val s3 = Plugins.import(motionScript)
        try {
            assertEquals("_test_motion", s3.id)
            assertTrue(s3.isMotion && s3.has("profile"))
            val p = Plugins.activeMotionProfile()
            assertNotNull(p)
            assertEquals("outExpoTail", p.second.getJSONObject("page").getString("easing"))
        } finally { Plugins.delete(s3.id) }
    }

    @Test fun externalPluginRepoMatchesIndexIfPresent() = runBlocking {
        val dir = extDir ?: return@runBlocking
        TestEnv.init()
        val scripts = dir.listFiles { f -> f.extension == "js" }.orEmpty().sortedBy { it.name }
        assertTrue(scripts.isNotEmpty())
        val idx = JSONObject(File(dir, "index.json").readText()).getJSONArray("plugins")
        val byId = (0 until idx.length()).map { idx.getJSONObject(it) }.associateBy { it.getString("id") }
        for (f in scripts) {
            val s = Plugins.import(f.readText())
            try {
                assertEquals(f.nameWithoutExtension, s.id, "${f.name} 的 @id 要和文件名一致")
                assertTrue(s.name.isNotBlank() && Regex("""^\d+\.\d+\.\d+$""").matches(s.version))
                if (s.isFeature) assertTrue(s.caps.isNotEmpty()) else assertTrue(s.has("search") && s.has("stream"))
            } finally { Plugins.delete(s.id) }
            val e = assertNotNull(byId[s.id], "index.json 缺少 ${f.name}")
            assertEquals(s.version, e.getString("version"))
            assertEquals(f.name, e.getString("url"))
        }
        assertEquals(scripts.size, idx.length())
    }
}
