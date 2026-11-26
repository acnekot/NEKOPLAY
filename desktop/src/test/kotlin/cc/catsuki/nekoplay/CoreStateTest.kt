package cc.catsuki.nekoplay

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

    @Test fun i18nResolvesSystemAndTranslatesWithoutTouchingMetadata() {
        TestEnv.init()
        val prev = I18n.code
        try {
            assertEquals(I18n.EN, I18n.resolveSystem("en-US"))
            assertEquals(I18n.JA, I18n.resolveSystem("ja_JP"))
            assertEquals(I18n.ZH_TW, I18n.resolveSystem("zh-Hant-TW"))
            assertEquals(I18n.ZH_TW, I18n.resolveSystem("zh-HK"))
            assertEquals(I18n.ZH_CN, I18n.resolveSystem("zh-Hans-CN"))

            I18n.set(I18n.EN)
            assertEquals("Settings", tr("设置"))
            assertEquals("Beta", tr("测试版"))
            assertEquals("✓  Stable", trKnown("✓  正式版"))
            assertEquals("12 tracks", trKnown("12 首"))
            assertEquals("Update available: v0.6.9", trKnown("有新版本：v0.6.9"))
            assertEquals("普通中文歌名", trKnown("普通中文歌名"))

            I18n.set(I18n.JA)
            assertEquals("設定", tr("设置"))
            assertEquals("テスト版", tr("测试版"))
            assertEquals("12 曲", trKnown("12 首"))

            I18n.set(I18n.ZH_TW)
            assertEquals("設定", tr("设置"))
            assertEquals("音樂庫", tr("曲库"))
            assertEquals("測試版", tr("测试版"))
            assertEquals("語言與介面切換立即生效", tr("语言与界面切换立即生效"))
            // trKnown 不应盲目将未收录的普通歌名做简繁转换
            assertEquals("普通中文歌名", trKnown("普通中文歌名"))
        } finally {
            I18n.set(prev)
        }
    }

    @Test fun uiScalePreviewComputesExactGpuRatioAndFreezesOnlyDuringUserDrag() {
        UiScalePreview.clear()
        UiScalePreview.committedUserScale = 1.0f
        UiScalePreview.committedAutoScale = 1.0f
        assertEquals(1.0f, UiScalePreview.gpuRatio(), 0.0001f)
        assertFalse(UiScalePreview.freezeBreakpoint)

        // 拖动界面缩放滑块：实时按比例缩放且冻结宽窄屏断点
        UiScalePreview.liveUserScale = 1.25f
        assertTrue(UiScalePreview.freezeBreakpoint)
        assertEquals(1.25f, UiScalePreview.gpuRatio(), 0.0001f)

        // 松手原子提交后恢复 1:1
        UiScalePreview.committedUserScale = 1.25f
        UiScalePreview.liveUserScale = null
        assertFalse(UiScalePreview.freezeBreakpoint)
        assertEquals(1.0f, UiScalePreview.gpuRatio(), 0.0001f)

        // 桌面 autoScale 拖窗期间实时跟随且不冻结断点
        UiScalePreview.liveAutoScale = 1.12f
        assertFalse(UiScalePreview.freezeBreakpoint)
        assertEquals(1.12f, UiScalePreview.gpuRatio(), 0.0001f)
        UiScalePreview.clear()
        UiScalePreview.committedUserScale = 1.0f
        UiScalePreview.committedAutoScale = 1.0f
    }

    @Test fun pluginHostExposesActiveLanguage() = runBlocking {
        TestEnv.init()
        val prev = I18n.code
        val langPlug = """
            // @id _test_host_lang
            // @name Lang Probe
            // @type feature
            // @permissions storage
            // @version 1.0.0
            // @api 2
            nk.registerFeature({
              action() { return { message: nk.host.lang }; }
            });
        """.trimIndent()
        val s = Plugins.import(langPlug)
        try {
            I18n.set(I18n.EN)
            val r1 = s.call("action") as org.json.JSONObject
            assertEquals(I18n.EN, r1.getString("message"))
            I18n.set(I18n.JA)
            val r2 = s.call("action") as org.json.JSONObject
            assertEquals(I18n.JA, r2.getString("message"))
        } finally {
            I18n.set(prev)
            Plugins.delete(s.id)
        }
    }

    @Test fun sharedHeroNeverFiresWithoutExplicitIntentAndRespectsTagAndPriority() {
        TestEnv.init()
        val prevStyle = AnimStyle.style
        AnimStyle.style = 0
        SharedHero.resetForTest()
        try {
            val homeRect = androidx.compose.ui.geometry.Rect(18f, 108f, 146f, 236f)
            val miniRect = androidx.compose.ui.geometry.Rect(14f, 700f, 54f, 740f)
            val playerRect = androidx.compose.ui.geometry.Rect(28f, 165f, 358f, 495f)
            val homeTok = SharedHero.allocToken()
            val miniTok = SharedHero.allocToken()
            val playerTok = SharedHero.allocToken()

            SharedHero.register(HeroAnchorEntry(homeTok, "now:t1", "u1", "Song", true, isTarget = false, tag = "home", priority = 1) { homeRect })
            SharedHero.register(HeroAnchorEntry(miniTok, "now:t1", "u1", "Song", true, isTarget = false, tag = "mini", priority = 2) { miniRect })
            SharedHero.register(HeroAnchorEntry(playerTok, "now:t1", "u1", "Song", true, isTarget = true, tag = "", priority = 0) { playerRect })

            // 1. 在播放页内切歌（未显式武装 capture）：绝不能误触发任何飞行
            SharedHero.onAnchorFirstPlaced(homeTok, "now:t1", "u1", "Song", true, isTarget = false, destRect = homeRect)
            SharedHero.onAnchorFirstPlaced(playerTok, "now:t1", "u1", "Song", true, isTarget = true, destRect = playerRect)
            assertNull(SharedHero.flight)

            // 2. 锚点卸载（如滚动离屏或切歌换 key）：绝不能误触发任何飞行
            SharedHero.unregister(playerTok)
            assertNull(SharedHero.flight)

            // 3. 点击 MiniBar 显式武装后打开播放页：精准从 MiniBar 飞向播放页大封面，且背景 Home 不能抢消费
            SharedHero.capture("now:t1", "u1", "Song", tag = "mini")
            SharedHero.onAnchorFirstPlaced(homeTok, "now:t1", "u1", "Song", true, isTarget = false, destRect = homeRect)
            assertNull(SharedHero.flight)
            val playerTok2 = SharedHero.allocToken()
            SharedHero.register(HeroAnchorEntry(playerTok2, "now:t1", "u1", "Song", true, isTarget = true, tag = "", priority = 0) { playerRect })
            SharedHero.onAnchorFirstPlaced(playerTok2, "now:t1", "u1", "Song", true, isTarget = true, destRect = playerRect)
            assertEquals(miniRect, SharedHero.flight?.from)
            assertEquals(playerTok2, SharedHero.flight?.destToken)

            // 4. 显式关闭播放页回飞：优先飞向高优先级的 MiniBar 而非背景 Home
            SharedHero.finish(SharedHero.flight!!.seq)
            SharedHero.flyBack("now:t1")
            assertEquals(playerRect, SharedHero.flight?.from)
            assertEquals(miniTok, SharedHero.flight?.destToken)
            assertTrue(SharedHero.isHidden(playerTok2))
            assertTrue(SharedHero.isHidden(miniTok))
        } finally {
            SharedHero.resetForTest()
            AnimStyle.style = prevStyle
        }
    }

    @Test fun originMotionPluginParsesAndTracksStackedOriginsWithInheritance() = runBlocking {
        TestEnv.init()
        val prevStyle = AnimStyle.style
        AnimStyle.style = 0
        OriginBus.resetForTest()
        val script = """
            // @id _test_origin_motion
            // @name Origin Test
            // @type motion
            // @permissions motion.profile, storage
            // @version 1.0.0
            // @api 2
            nk.registerMotion({
              profile() {
                return {
                  name: '源点测试',
                  presetLabel: '丝滑',
                  page: { easing: 'spring', ms: 340, damping: 0.86, stiffness: 420 },
                  effects: {
                    originExpand: true, originRipple: true, originInherit: true,
                    originCornerDp: 20, selectCoverScale: 1.12, selectFontScale: 1.07,
                    listStaggerMs: 20, listSlideDp: 32
                  }
                };
              }
            });
        """.trimIndent()
        val s = Plugins.import(script)
        try {
            val prof = Plugins.activeMotionProfile()
            assertNotNull(prof)
            AnimStyle.applyPlugin(prof)
            assertTrue(MotionEffects.originExpand)
            assertTrue(MotionEffects.originRipple)
            assertTrue(MotionEffects.originInherit)
            assertEquals(20f, MotionEffects.originCornerDp, 0.01f)
            assertEquals(1.12f, MotionEffects.selectCoverScale, 0.001f)
            assertEquals(1.07f, MotionEffects.selectFontScale, 0.001f)

            // 模拟 A -> B -> C 多级跳转与「从哪来回哪去」
            val rCardA = androidx.compose.ui.geometry.Rect(20f, 600f, 160f, 740f)
            val rItemB = androidx.compose.ui.geometry.Rect(240f, 120f, 380f, 200f)
            OriginBus.recordTapRect(rCardA)
            assertNotNull(OriginBus.wave)
            val p1 = Page.Detail("pl", "1", "List1")
            val p2 = Page.Album("2", "Alb2")
            OriginBus.syncStack(listOf(p1))
            assertEquals(rCardA, OriginBus.activePageOrigin())
            assertNotNull(OriginBus.activeInherit())
            assertTrue(OriginBus.activeInherit()!!.vector.y > 0f) // 源点在下方 → 继承向量向下起点向上承接

            OriginBus.recordTapRect(rItemB)
            OriginBus.syncStack(listOf(p1, p2))
            assertEquals(rItemB, OriginBus.activePageOrigin())

            // 点返回按钮（即使 lastTapRect 被返回按钮覆盖），弹出 C 时仍按栈精准缩回 rItemB 并发出回响波纹
            val waveBeforePop = OriginBus.wave!!.seq
            OriginBus.recordTapRect(androidx.compose.ui.geometry.Rect(0f, 0f, 44f, 44f))
            OriginBus.syncStack(listOf(p1))
            assertEquals(rItemB, OriginBus.closingPageRect)
            assertEquals(rCardA, OriginBus.activePageOrigin())
            assertTrue(OriginBus.wave!!.seq > waveBeforePop)
            assertEquals(rItemB.center.y, OriginBus.wave!!.cy, 0.01f)

            // 源点容器展开启用时，SharedHero 应自动静默，避免与容器展开叠出双封面重影
            SharedHero.resetForTest()
            val tok = SharedHero.allocToken()
            SharedHero.register(HeroAnchorEntry(tok, "now:t9", "u9", "S", true, isTarget = false, tag = "bar", priority = 2) { rCardA })
            SharedHero.capture("now:t9", "u9", "S", tag = "bar")
            SharedHero.flyBack("now:t9")
            assertNull(SharedHero.flight)
        } finally {
            AnimStyle.applyPlugin(null)
            OriginBus.resetForTest()
            SharedHero.resetForTest()
            Plugins.delete(s.id)
            AnimStyle.style = prevStyle
        }
    }

    @Test fun geomMotionPluginParsesRevealParametersAndDisablesCleanly() = runBlocking {
        TestEnv.init()
        val prevStyle = AnimStyle.style
        AnimStyle.style = 0
        AnimStyle.applyPlugin(null)
        assertFalse(MotionEffects.geomReveal)
        assertFalse(MotionEffects.geomContainer)
        val script = """
            // @id _test_geom_motion
            // @name Geom Test
            // @type motion
            // @permissions motion.profile, storage
            // @version 1.0.0
            // @api 2
            nk.registerMotion({
              profile() {
                return {
                  name: '点线面测试',
                  presetLabel: 'PRTS 标准',
                  crossfade: { easing: 'outQuint', ms: 480 },
                  effects: {
                    geomReveal: true, geomLineRatio: 0.36, geomLineDp: 2.6,
                    geomReverse: true, geomContainer: true, geomAccent: false
                  }
                };
              }
            });
        """.trimIndent()
        val s = Plugins.import(script)
        try {
            val prof = Plugins.activeMotionProfile()
            assertNotNull(prof)
            AnimStyle.applyPlugin(prof)
            assertTrue(MotionEffects.geomReveal)
            assertTrue(MotionEffects.geomContainer)
            val cfg = MotionEffects.geom
            assertNotNull(cfg)
            assertEquals(0.36f, cfg.lineRatio, 0.001f)
            assertEquals(2.6f, cfg.lineDp, 0.001f)
            assertTrue(cfg.reverse)
            assertTrue(cfg.container)
            assertFalse(cfg.accent)
        } finally {
            AnimStyle.applyPlugin(null)
            Plugins.delete(s.id)
            AnimStyle.style = prevStyle
        }
        assertFalse(MotionEffects.geomReveal)
    }

    @Test fun recentHistoryHasNo100CapGroupsByDayAndPreservesCrossDayAndLegacy() {
        TestEnv.init()
        Store.init()
        Store.clearRecents()
        try {
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 14)
                set(java.util.Calendar.MINUTE, 30)
                set(java.util.Calendar.SECOND, 0)
            }
            val dayTodayMs = cal.timeInMillis
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            val dayYesterdayMs = cal.timeInMillis
            cal.add(java.util.Calendar.DAY_OF_YEAR, -2)
            val dayEarlierMs = cal.timeInMillis

            // 写入超过 100 首（135 首）跨越 3 天，验证无 100 条截断
            for (i in 1..45) {
                Store.addRecent(Track("e$i", "Early $i", "A", "Al", 180_000L, null), dayEarlierMs + i * 1000L)
            }
            for (i in 1..45) {
                Store.addRecent(Track("y$i", "Yest $i", "A", "Al", 180_000L, null), dayYesterdayMs + i * 1000L)
            }
            for (i in 1..45) {
                Store.addRecent(Track("t$i", "Today $i", "A", "Al", 180_000L, null), dayTodayMs + i * 1000L)
            }
            assertEquals(135, Store.recentEntries.value.size)
            assertEquals(135, Store.recents.value.size)

            // 同一首歌在不同天播放应分别保留；同一天重复播放应置顶去重
            val shared = Track("shared_1", "Cross Day Song", "A", "Al", 200_000L, null)
            Store.addRecent(shared, dayYesterdayMs + 90_000L)
            Store.addRecent(shared, dayTodayMs + 90_000L)
            Store.addRecent(shared, dayTodayMs + 120_000L)
            assertEquals(137, Store.recentEntries.value.size)
            assertEquals(2, Store.recentEntries.value.count { it.track.id == "shared_1" })
            assertEquals(dayTodayMs + 120_000L, Store.recentEntries.value.first().playedAt)

            // 按天分组且日期降序排列 + 无时间戳旧记录放最末「更早记录」
            val legacy = RecentEntry(Track("old_1", "Legacy Song", "A", "Al", 150_000L, null), 0L, "")
            val groups = Store.groupRecentByDay(Store.recentEntries.value + legacy)
            assertEquals(4, groups.size)
            assertEquals(Store.dayKey(dayTodayMs), groups[0].day)
            assertEquals(Store.dayKey(dayYesterdayMs), groups[1].day)
            assertEquals(Store.dayKey(dayEarlierMs), groups[2].day)
            assertEquals("", groups[3].day)
            assertEquals("更早记录", Store.dayLabel("", dayTodayMs))
            assertTrue(Store.dayLabel(groups[0].day, dayTodayMs).startsWith("今天"))
            assertTrue(Store.dayLabel(groups[1].day, dayTodayMs).startsWith("昨天"))

            // 持久化往返（含 >100 条记录与时间戳）
            Store.flushRecentsSync()
            Store.init()
            assertEquals(137, Store.recentEntries.value.size)
            assertEquals(dayTodayMs + 120_000L, Store.recentEntries.value.first().playedAt)
        } finally {
            Store.clearRecents()
            Store.flushRecentsSync()
        }
    }
}
