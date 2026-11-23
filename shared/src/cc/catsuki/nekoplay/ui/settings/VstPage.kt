package cc.catsuki.nekoplay.ui.settings

import cc.catsuki.nekoplay.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 桌面端 VST2 / VST3 实时插件链管理页：支持扫描目录、串行插件链、原生浮窗（置顶悬浮，适配 MiniMeters 等示波/频谱仪）与参数调校 */
@Composable fun VstPage() {
    val nav = LocalNav.current
    val p = c
    val host = Plat.impl.vst
    val scope = rememberCoroutineScope()

    if (host == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T("当前平台不支持 VST 插件", 14, p.s) }
        return
    }

    var found by remember { mutableStateOf<List<VstFound>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<Int?>(null) }
    var params by remember { mutableStateOf<List<VstParam>>(emptyList()) }

    fun doScan() {
        if (scanning) return
        scanning = true
        scope.launch {
            found = withContext(Dispatchers.IO) { host.scan() }
            scanning = false
        }
    }

    LaunchedEffect(Unit) { if (host.available) doScan() }
    LaunchedEffect(expandedId) {
        val id = expandedId
        params = if (id != null) withContext(Dispatchers.IO) { host.params(id) } else emptyList()
    }

    val shown = remember(found, filter) {
        val q = filter.trim().lowercase()
        if (q.isEmpty()) found else found.filter { it.name.lowercase().contains(q) || it.format.lowercase().contains(q) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 120.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("VST 插件", Fs.PAGE, w = 800)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.background(p.p).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    T("BETA", 10, Color.White, 800, mono = true, ls = .06f)
                }
            }
            T("实验性功能：支持实时加载 VST3 / VST2 音频效果器与可视化仪（如 MiniMeters / Pro-Q / SPAN），音频经 48kHz PCM 管线实时处理。", 13, p.s, lines = 3, lh = 1.45f, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
        }

        if (!host.available) {
            item {
                Column(Modifier.fillMaxWidth().background(p.sheet).border(1.dp, p.bad).padding(14.dp)) {
                    T("未检测到原生 VST 宿主库（libnekoplay_vst）", 14, p.bad, 700)
                    host.loadError?.let { T(it, 12, p.s, lines = 4, mono = true, modifier = Modifier.padding(top = 6.dp)) }
                    T("请确认使用完整桌面安装包，或在源码目录运行 native/vst/build.sh 编译宿主库。", 12, p.s, lines = 3, modifier = Modifier.padding(top = 6.dp))
                }
            }
            return@LazyColumn
        }

        item {
            SwitchRow("启用 VST 实时处理", if (host.enabled) "音频正通过 VST 管线实时输出（48kHz Stereo Float）" else "关闭时直出系统声卡，零额外延迟", host.enabled) { on ->
                host.setEnabled(on)?.let { nav.toast = it }
            }
        }
        item {
            SwitchRow("插件窗口置顶悬浮", "打开 MiniMeters 等频谱/示波器插件界面时保持置顶，方便贴边实时观察", host.onTop) { host.setOnTop(it) }
        }

        // ---------- 已加载插件链 ----------
        item {
            Spacer(Modifier.height(18.dp))
            TagRow("ACTIVE CHAIN · ${host.chain.size}")
            Spacer(Modifier.height(8.dp))
            if (host.chain.isEmpty()) T("还没有加载任何 VST 插件，请在下方扫描列表点击「添加」。", 13, p.s, modifier = Modifier.padding(vertical = 8.dp))
        }

        itemsIndexed(host.chain, key = { _, s -> s.id }) { idx, slot ->
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).background(p.sheet).nb(1.dp, p.k).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(if (slot.bypass) p.l2 else p.p).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        T("${idx + 1} · ${slot.format}", 10, if (slot.bypass) p.s else Color.White, 800, mono = true)
                    }
                    Spacer(Modifier.width(8.dp))
                    T(slot.name, 15, if (slot.bypass) p.s else p.k, 700, modifier = Modifier.weight(1f))
                    LinkBtn(if (slot.bypass) "已旁通" else "生效中", 12) { host.setBypass(slot.id, !slot.bypass) }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.press {
                        scope.launch {
                            val err = withContext(Dispatchers.IO) { host.openEditor(slot.id) }
                            if (err != null) nav.toast = err
                        }
                    }.background(p.k).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        T(if (slot.editorOpen) "置前界面" else "打开界面", 12, p.tagFg, 700)
                    }
                    Box(Modifier.press { expandedId = if (expandedId == slot.id) null else slot.id }.nbf(1.dp, p.k).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        T(if (expandedId == slot.id) "收起参数" else "参数", 12, p.k, 700)
                    }
                    if (idx > 0) LinkBtn("上移", 12) { host.movePlugin(slot.id, idx - 1) }
                    if (idx < host.chain.lastIndex) LinkBtn("下移", 12) { host.movePlugin(slot.id, idx + 1) }
                    Spacer(Modifier.weight(1f))
                    LinkBtn("移除", 12) {
                        if (expandedId == slot.id) expandedId = null
                        host.removePlugin(slot.id)
                    }
                }
                if (expandedId == slot.id) {
                    Spacer(Modifier.height(10.dp))
                    if (params.isEmpty()) T("该插件未暴露可调参数（可直接点击「打开界面」操作）", 12, p.s)
                    else params.take(24).forEach { pm ->
                        var v by remember(slot.id, pm.index) { mutableFloatStateOf(pm.value) }
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(pm.name, 12, p.k, modifier = Modifier.weight(1f))
                            T(pm.display.ifEmpty { "%.0f%%".format(v * 100f) }, 11, p.p, mono = true)
                        }
                        NSlider(v, 0f..1f, onDone = {}) { nv -> v = nv; host.setParam(slot.id, pm.index, nv) }
                    }
                }
            }
        }

        // ---------- 扫描目录与插件浏览器 ----------
        item {
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TagRow("AVAILABLE · ${shown.size}") }
                LinkBtn(if (scanning) "扫描中…" else "重新扫描", 13) { doScan() }
                Spacer(Modifier.width(12.dp))
                LinkBtn("+ 添加目录", 13) {
                    Plat.impl.pickFolder(null) { dir ->
                        if (dir != null) { host.addDir(dir); doScan() }
                    }
                }
            }
            if (host.customDirs.isNotEmpty()) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    host.customDirs.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T(d, 11, p.s, mono = true, modifier = Modifier.weight(1f))
                            LinkBtn("删除", 11) { host.removeDir(d); doScan() }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(40.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (filter.isEmpty()) T("搜索已安装的 VST3 / VST2 插件（如 MiniMeters）", 13, p.s)
                BasicTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = TextStyle(color = p.k, fontSize = 13.sp, fontFamily = Typo.family), cursorBrush = SolidColor(p.p))
            }
            Spacer(Modifier.height(8.dp))
            if (!scanning && shown.isEmpty()) {
                T("系统默认 VST3 / VST2 目录中未发现插件；可点击「+ 添加目录」指定你的插件文件夹。", 13, p.s, lines = 3, lh = 1.45f, modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        items(shown, key = { it.path }) { item ->
            Row(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.border(1.dp, p.l2).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    T(item.format, 10, p.s, 700, mono = true)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    T(item.name, 14, p.k, 600)
                    T(item.path, 11, p.s, mono = true)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.press {
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { host.addPlugin(item.path) }
                        nav.toast = err ?: "已加载 ${item.name}"
                    }
                }.nbf(1.dp, p.k).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    T("添加", 12, p.k, 700)
                }
            }
        }
    }
}
