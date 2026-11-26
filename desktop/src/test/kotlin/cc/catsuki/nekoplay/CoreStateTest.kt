package cc.catsuki.nekoplay

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 核心状态机、原子写盘、功能集群、动效插件剥离与加密备份往返单元测试 */
class CoreStateTest {
    @Test fun writeTextAtomicReplacesCleanly() {
        TestEnv.init()
        val f = File(Plat.impl.cacheDir, "atomic_test.json")
        f.writeTextAtomic("""{"v":1}""")
        assertEquals("""{"v":1}""", f.readText())
        f.writeTextAtomic("""{"v":2,"ok":true}""")
        assertEquals("""{"v":2,"ok":true}""", f.readText())
        assertFalse(File(f.parentFile, "${f.name}.tmp").exists())
        f.delete()
    }

    @Test fun engineShuffleHistoryTracksAndReindexes() {
        EngineHistory.clear()
        EngineHistory.push(1)
        EngineHistory.push(4)
        EngineHistory.push(4) // dedup consecutive
        EngineHistory.push(7)
        assertEquals(3, EngineHistory.size())
        // Remove index 2 -> indices > 2 shift down (4->3, 7->6)
        EngineHistory.onRemove(2)
        assertEquals(6, EngineHistory.popValid(10, current = 5))
        assertEquals(3, EngineHistory.popValid(10, current = 6))
        assertEquals(1, EngineHistory.popValid(10, current = 3))
        assertNull(EngineHistory.popValid(10, current = 1))
        val picked = EngineHistory.pickNextShuffle(5, current = 2)
        assertNotEquals(2, picked)
        assertTrue(picked in 0 until 5)
    }

    @Test fun featuresClusterToggleAndMotionGate() {
        TestEnv.init()
        Features.load()
        try {
            assertTrue(Features.on("motion"))
            Features.setCluster("visual", false)
            assertFalse(Features.clusterOn("visual"))
            assertFalse(Features.on("bg"))
            assertFalse(Features.on("monet"))
            assertFalse(Features.on("motion"))
            assertTrue(AnimStyle.off)

            Features.set("motion", true)
            assertTrue(Features.clusterOn("visual"))
            assertTrue(Features.on("motion"))
            assertFalse(Features.on("bg"))

            Features.setCluster("visual", true)
            assertEquals(Features.inCluster("visual").size, Features.clusterEnabledCount("visual"))
        } finally {
            Features.clusters.forEach { Features.setCluster(it.id, true) }
        }
    }

    @Test fun motionCascadeStrippedWithoutPluginAndActiveWithPlugin() = runBlocking {
        TestEnv.init()
        AnimStyle.applyPlugin(null)
        assertEquals(0, MotionEffects.listStaggerMs)
        assertEquals(0f, MotionEffects.listSlideDp)

        val script = """
            // @id _test_motion_cas
            // @name Test Cascade Motion
            // @type motion
            // @version 1.0.0
            // @api 2
            nk.registerMotion({
              profile() {
                return {
                  name: 'TestMotion',
                  page: { easing: 'outExpoTail', ms: 500 },
                  effects: { listStaggerMs: 28, listSlideDp: 48, hoverShiftDp: 10 },
                };
              }
            });
        """.trimIndent()
        val s = Plugins.import(script)
        try {
            AnimStyle.applyPlugin(Plugins.activeMotionProfile())
            assertEquals(28, MotionEffects.listStaggerMs)
            assertEquals(48f, MotionEffects.listSlideDp)
            assertEquals(10f, MotionEffects.hoverShiftDp)
        } finally {
            AnimStyle.applyPlugin(null)
            Plugins.delete(s.id)
        }
        assertEquals(0, MotionEffects.listStaggerMs)
        assertEquals(0f, MotionEffects.listSlideDp)
    }

    @Test fun backupSealUnsealRoundTripAndWrongPasswordRejects() {
        TestEnv.init()
        val raw = JSONObject().put("v", 1).put("at", 123456L).put("ui", JSONObject().put("theme", 2))
        val plain = Backup.seal(raw, "")
        assertFalse(Backup.isEncrypted(plain))
        assertEquals(2, Backup.unseal(plain, "").getJSONObject("ui").getInt("theme"))

        val sealed = Backup.seal(raw, "neko-secret-2026")
        assertTrue(Backup.isEncrypted(sealed))
        assertFalse(sealed.has("ui"))

        val opened = Backup.unseal(sealed, "neko-secret-2026")
        assertEquals(123456L, opened.getLong("at"))
        assertEquals(2, opened.getJSONObject("ui").getInt("theme"))

        assertFails { Backup.unseal(sealed, "wrong-password") }
        assertFails { Backup.unseal(sealed, "") }
    }

    @Test fun statsAsyncAndDumpRestoreWorks() {
        TestEnv.init()
        Stats.clear()
        val t = Track("t1", "Song One", "Artist", "Album", 180_000L, null)
        Stats.add(t, 45L)
        Stats.flushSync()
        val dumped = Stats.dump()
        Stats.clear()
        assertTrue(Stats.songs.value.isEmpty())
        Stats.restore(dumped)
        assertEquals(1, Stats.songs.value["t1"]?.plays)
        assertEquals(45L, Stats.songs.value["t1"]?.sec)
        Stats.clear()
    }

    @Test fun pluginNetworkPermissionBlocksUnauthorizedFeaturePlugin() = runBlocking {
        TestEnv.init()
        val offlineFeat = """
            // @id _test_offline_feat
            // @name Offline Feature
            // @type feature
            // @permissions storage
            // @version 1.0.0
            // @api 2
            nk.registerFeature({
              async action() {
                return await nk.http('https://example.com/');
              }
            });
        """.trimIndent()
        val s = Plugins.import(offlineFeat)
        try {
            assertFalse(s.canNetwork)
            val err = runCatching { s.call("action") }.exceptionOrNull()
            assertTrue(err != null && err.message?.contains("network") == true, "应拦截未声明 network 权限的请求，实际: $err")
        } finally {
            Plugins.delete(s.id)
        }
    }
}
