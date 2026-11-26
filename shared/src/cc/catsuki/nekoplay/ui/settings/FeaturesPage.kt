package cc.catsuki.nekoplay.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.catsuki.nekoplay.ChipRow
import cc.catsuki.nekoplay.Features
import cc.catsuki.nekoplay.LinkBtn
import cc.catsuki.nekoplay.LocalNav
import cc.catsuki.nekoplay.NSwitch
import cc.catsuki.nekoplay.Nav
import cc.catsuki.nekoplay.Page
import cc.catsuki.nekoplay.PluginKind
import cc.catsuki.nekoplay.Plugins
import cc.catsuki.nekoplay.SheetItem
import cc.catsuki.nekoplay.SheetSpec
import cc.catsuki.nekoplay.SidePref
import cc.catsuki.nekoplay.SwitchRow
import cc.catsuki.nekoplay.T
import cc.catsuki.nekoplay.Tag
import cc.catsuki.nekoplay.c
import cc.catsuki.nekoplay.nbf
import cc.catsuki.nekoplay.press

/** 设置 → 功能管理：支持「关联集群」一键总控与「单功能」精细开关，并同步展示外置功能/动效插件 */
@Composable fun FeaturesPage() {
    val nav = LocalNav.current
    val p = c
    val allPlugs by Plugins.list.collectAsState()
    val rev = Plugins.rev
    val extFeatures = remember(allPlugs, rev) { Plugins.features }
    var viewMode by remember { mutableIntStateOf(0) } // 0 关联集群 / 1 单功能清单
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    val builtinOn = Features.list.count { Features.on(it.id) }
    val extOn = extFeatures.count { Plugins.on(it.id) }

    LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 36.dp)) {
        item {
            Column(Modifier.padding(bottom = 14.dp)) {
                T("内置模块 $builtinOn/${Features.list.size} 已开启 · 外置功能插件 $extOn/${extFeatures.size} 已开启", 13, p.p, 700, mono = true)
                Spacer(Modifier.height(4.dp))
                T("关闭的功能会立即隐藏入口并停用后台能力，核心播放不受影响；可按集群一键开关，也可独立微调单个功能。", 12, p.s, lh = 1.45f, lines = 3)
                Spacer(Modifier.height(12.dp))
                ChipRow(listOf("关联集群", "全部单功能"), viewMode) { viewMode = it }
            }
        }

        if (viewMode == 0) {
            Features.clusters.forEach { cluster ->
                val fs = Features.inCluster(cluster.id)
                val onCnt = Features.clusterEnabledCount(cluster.id)
                val cOn = Features.clusterOn(cluster.id)
                val isCollapsed = collapsed[cluster.id] == true
                item(key = "cl_${cluster.id}") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)
                            .background(p.sheet).nbf(1.dp, p.l2)
                            .press { collapsed[cluster.id] = !isCollapsed }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Tag(cluster.name)
                                T("$onCnt/${fs.size}", 11, if (cOn) p.p else p.s, 700, mono = true)
                                T(if (isCollapsed) "展开 ▾" else "收起 ▴", 11, p.s)
                            }
                            Spacer(Modifier.height(4.dp))
                            T(cluster.desc, 12, p.s, lines = 2)
                        }
                        Spacer(Modifier.width(10.dp))
                        NSwitch(cOn) { Features.setCluster(cluster.id, it) }
                    }
                }
                if (!isCollapsed) {
                    items(fs, key = { "f_${it.id}" }) { f ->
                        Box(Modifier.padding(start = 10.dp)) {
                            SwitchRow(f.name, f.desc, Features.on(f.id)) { Features.set(f.id, it) }
                        }
                    }
                }
            }
        } else {
            items(Features.list, key = { "flat_${it.id}" }) { f ->
                val cname = Features.CLUSTERS.firstOrNull { it.id == f.group }?.name ?: ""
                SwitchRow(f.name, if (cname.isNotEmpty()) "[$cname] ${f.desc}" else f.desc, Features.on(f.id)) { Features.set(f.id, it) }
            }
        }

        // 外置功能 / 动效 / 页面插件集群（与内置模块对齐粒度，一处总控）
        item(key = "cl_ext_plugins") {
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp)
                    .background(p.sheet).nbf(1.dp, p.l2)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag("外置功能插件")
                        T("$extOn/${extFeatures.size}", 11, if (extOn > 0) p.p else p.s, 700, mono = true)
                    }
                    Spacer(Modifier.height(4.dp))
                    T("通过插件中心按需安装的可插拔扩展（如 3D 封面墙、长按变速、外置动效引擎）", 12, p.s, lines = 2)
                }
                Spacer(Modifier.width(8.dp))
                LinkBtn("插件中心 →", 12) { nav.push(Page.PluginMgr) }
                if (extFeatures.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    NSwitch(extOn > 0) { target -> extFeatures.forEach { Plugins.set(it.id, target) } }
                }
            }
        }
        if (extFeatures.isEmpty()) {
            item(key = "ext_empty") {
                T("当前未安装外置功能插件，可前往「插件中心」按需订阅安装", 12, p.s, modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp))
            }
        } else {
            items(extFeatures, key = { "ext_${it.id}" }) { s ->
                val kindTag = when (s.kind) { PluginKind.MOTION -> "动效"; PluginKind.PAGE -> "页面"; else -> "功能" }
                val desc = listOfNotNull("[$kindTag]", s.meta["desc"]).joinToString(" ")
                Box(Modifier.padding(start = if (viewMode == 0) 10.dp else 0.dp)) {
                    SwitchRow(s.name, desc, Plugins.on(s.id)) { Plugins.set(s.id, it) }
                }
            }
        }
    }
}

/** PC 侧拉模式弹窗菜单 */
fun SidePref.menu(nav: Nav) {
    nav.sheet = SheetSpec("侧拉模式", "贴在屏幕边上，鼠标离开自动收起，碰到边缘再滑出来", buildList {
        add(SheetItem((if (active == -1) "✓  " else "    ") + "贴在左边") { enter?.invoke(-1) })
        add(SheetItem((if (active == 1) "✓  " else "    ") + "贴在右边") { enter?.invoke(1) })
        if (active != 0) add(SheetItem("退出侧拉模式") { exit?.invoke() })
    })
}
