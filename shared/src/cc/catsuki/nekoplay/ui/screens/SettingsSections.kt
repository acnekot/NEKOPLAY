package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 设置二级页分组（外观 / 歌词 / 高级 / 关于）拆分，保持 SettingsScreen 简洁可维护 */
internal fun LazyListScope.settingsLookSection(nav: Nav, p: Pal, sysDark: Boolean) {
    item { SwitchRow("跟随系统", "跟随系统的深浅色设置", ThemePref.mode == 0) { ThemePref.set(if (it) 0 else if (sysDark) 2 else 1) } }
    item { SwitchRow("深色模式", if (ThemePref.mode == 0) "当前跟随系统，点击可手动切换" else "切换为暗色界面", if (ThemePref.mode == 0) sysDark else ThemePref.mode == 2) { ThemePref.set(if (it) 2 else 1) } }
    item { NavRow("主题", "界面风格：BenchLab 粗线框 / 无边框（默认）", Look.NAMES[Look.style]) { nav.sheet = SheetSpec("选择主题", null, Look.NAMES.mapIndexed { i, n -> SheetItem((if (i == Look.style) "✓  " else "    ") + n) { Look.set(i) } }) } }
    item { ThemeStudioSection() }
    if (Plat.isDesktop) item { SwitchRow("自适应大小", "按窗口宽度自动微调界面比例（以 1280 宽为基准），拖大窗口更饱满、缩窄更紧凑", UiPref.autoScale) { UiPref.autoScale = it; UiPref.save() } }
    item {
        var draft by remember { mutableIntStateOf(UiPref.scale) }
        LaunchedEffect(UiPref.scale) { if (UiScalePreview.liveUserScale == null) draft = UiPref.scale }
        DisposableEffect(Unit) { onDispose { UiScalePreview.liveUserScale = null } }
        SetLine("界面缩放", "拖动实时预览界面缩放，松手无感切回原生清晰渲染；小屏手机调小能看到更多内容") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    NSlider(
                        draft.toFloat(),
                        60f..150f,
                        stableDrag = true,
                        onDone = {
                            UiPref.scale = draft
                            UiPref.save()
                            UiScalePreview.liveUserScale = null
                        },
                    ) { raw ->
                        val snapped = raw.roundToInt().let { if (it in 98..102) 100 else it }.coerceIn(60, 150)
                        if (snapped != draft) {
                            draft = snapped
                            UiScalePreview.liveUserScale = snapped / 100f
                        }
                    }
                }
                Spacer(Modifier.width(10.dp)); T("$draft%", 14, p.p, 700, mono = true)
                if (draft != 100) { Spacer(Modifier.width(8.dp)); T(tr("重置"), 13, p.s, modifier = Modifier.press { draft = 100; UiScalePreview.liveUserScale = null; UiPref.scale = 100; UiPref.save() }) }
            }
        }
    }
    item { NavRow("字体", "默认 Inter 无衬线；也可以改用系统字体", if (Typo.sans == 0) "系统字体" else "Inter") { Typo.set(1 - Typo.sans) } }
    item {
        SetLine("界面语言", "立即生效，无需重启应用") {
            ChipRow(I18n.options.map { it.nativeName }, I18n.options.indexOfFirst { it.code == I18n.code }.coerceAtLeast(0)) { idx ->
                val opt = I18n.options[idx]
                I18n.set(opt.code)
                nav.toast = "已切换语言：${I18n.currentLabel}"
            }
        }
    }
    if (Features.on("monet")) item { MonetSettings() }
    if (Features.on("bg")) item { NavRow("自定义背景", "选一张图片，可调模糊、透明度、位置和大小", if (Bg.on) "已设置" else "未设置") { nav.push(Page.Background) } }
    if (Features.on("bg") && Bg.on) item {
        SetLine("栏位不透明度", "有自定义背景时，侧边栏、顶栏、播放条的不透明度") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { NSlider(UiPref.barAlpha, 0f..1f, onDone = { UiPref.save() }) { UiPref.barAlpha = it } }
                Spacer(Modifier.width(10.dp)); T("%.0f%%".format(UiPref.barAlpha * 100), 14, p.p, 700, mono = true)
            }
        }
    }
    if (Features.on("motion")) item { NavRow("动效", "简单配置 / 高级曲线与外置动效插件", "${AnimStyle.activeLabel} · ${Anim.labels[Anim.level]}") { nav.push(Page.Tuning) } }
}

internal fun LazyListScope.settingsLyricSection(nav: Nav, p: Pal) {
    item {
        val copy = rememberCopy()
        SetLine("歌词滚动样式", "选择内置滚动风格（含 580ms 平滑跟进与逐字浮光效果），或通过 JSON 导入 / 导出自定义样式") {
            ChipRow(LyricPref.STYLES, LyricPref.style) { LyricPref.applyPreset(it) }
            if (LyricPref.customName.isNotBlank()) T("当前导入样式：${LyricPref.customName}", 12, p.p, mono = true, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.press { copy(LyricPref.exportJson(), "歌词样式 JSON") }.nbf(1.dp, p.k).padding(horizontal = 12.dp, vertical = 6.dp)) { T("导出样式 JSON", 12, p.k, 700) }
                Box(Modifier.press {
                    nav.input = InputSpec("导入歌词样式", "粘贴导出的歌词样式 JSON（支持 style / size / anchor / align / gap / scrollMs / staggerMs / activeScale / accentBar 等字段）") { raw ->
                        val name = runCatching { LyricPref.importJson(raw) }.getOrElse { nav.toast = "样式 JSON 格式有误：${it.message}"; return@InputSpec }
                        nav.toast = "已应用歌词样式：$name"
                    }
                }.background(p.k).padding(horizontal = 12.dp, vertical = 6.dp)) { T("导入样式 JSON", 12, p.tagFg, 700) }
            }
        }
    }
    item {
        SetLine("歌词字号", "歌词页、播放页右栏") {
            T("今も頭上を回って輝く", LyricPref.size.toFloat(), p.k, 800)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { NSlider(LyricPref.size.toFloat(), 16f..40f, onDone = { LyricPref.set(LyricPref.size) }) { LyricPref.size = it.toInt() } }
                Spacer(Modifier.width(10.dp)); T("${LyricPref.size}", 14, p.p, 700, mono = true)
            }
        }
    }
    item { SetLine("当前句位置", "正在唱的那一句停在哪：顶部、第二行（上面保留上一句）、偏上或居中") { ChipRow(LyricPref.ANCHORS, LyricPref.anchor) { LyricPref.changeAnchor(it) } } }
    item { SetLine("歌词对齐", "左对齐或居中排版") { ChipRow(LyricPref.ALIGNS, LyricPref.align) { LyricPref.changeAlign(it) } } }
    item { SwitchRow("当前句指示条", "在当前演唱句左侧显示主题色竖条（Hydrogen 风格默认开启）", LyricPref.accentBar) { LyricPref.accentBar = it; LyricPref.save() } }
    item { SwitchRow("逐字高亮", "有逐字歌词的歌，当前句一个字一个字亮起来", UiPref.karaoke) { UiPref.karaoke = it; UiPref.save() } }
    item { SwitchRow("远处歌词模糊", "离当前句较远的行轻微模糊（更有层次，略耗性能）", UiPref.lyricBlur) { UiPref.lyricBlur = it; UiPref.save() } }
    if (!Plat.isDesktop && Features.on("notify")) item { SwitchRow("通知栏歌词", "播放通知里显示当前这句歌词；也会发送状态栏歌词（魅族协议，配合状态栏歌词模块）", DeskLyric.notify) { DeskLyric.notify = it; DeskLyric.save() } }
    if (Features.on("desk")) item { SwitchRow("桌面歌词", "置顶的悬浮歌词窗：鼠标移上去可以切歌、调字号、锁定；托盘菜单也能开关", DeskLyric.on) { DeskLyric.on = it; DeskLyric.save() } }
    if (Features.on("desk") && DeskLyric.on) deskLyricSettings(p)
}

internal fun LazyListScope.settingsAdvSection(
    nav: Nav,
    p: Pal,
    prefs: KV,
    pctx: coil3.PlatformContext,
    scope: CoroutineScope,
    mem: String,
    analysis: String,
    onAnalysis: (String) -> Unit,
) {
    item { Group("PERFORMANCE") }
    item {
        // 看得到这一行时才让性能采样跑起来
        DisposableEffect(Unit) { Perf.watchers.value++; onDispose { Perf.watchers.value-- } }
        val ps by Perf.stat.collectAsState(); T(Perf.line(ps), 11, p.s, mono = true, lines = 3, lh = 1.5f, modifier = Modifier.padding(vertical = 10.dp))
    }
    item { val po by Perf.overlay.collectAsState(); SwitchRow("性能浮窗", "在所有页面左下角显示 CPU、帧率和 GPU", po) { Perf.overlay.value = it; prefs.edit().putBoolean("perf", it).apply() } }
    if (Plat.isDesktop) item {
        val soft = prefs.getString("render", "gpu") == "soft"
        SwitchRow("软件渲染（省内存）", "用 CPU 画界面，少占约 170MB 内存，但帧率会掉到 30 帧以下（开背景模糊时更低）。重启生效", soft) { prefs.edit().putString("render", if (it) "soft" else "gpu").apply(); nav.toast = "重启后生效" }
    }
    item { SwitchRow("省内存模式", "图片缓存变小、头像不播放动画、逐字高亮降到约 20 帧${if (Plat.isDesktop) "、最小化时释放内存" else ""}；图片缓存大小重启后生效", UiPref.lowMem) { UiPref.lowMem = it; UiPref.save() } }
    item {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).bottomLine().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(tr("内存占用"), 16)
                T(mem, 13, p.s, lines = 3, lh = 1.4f)
                if (analysis.isNotEmpty()) T(analysis, 11, p.k, mono = true, lines = 8, lh = 1.4f)
            }
            Column(horizontalAlignment = Alignment.End) {
                LinkBtn("分析", 14) { scope.launch { onAnalysis(withContext(Dispatchers.IO) { Plat.impl.memAnalysis() }) } }
                LinkBtn("清理缓存", 14) { SingletonImageLoader.get(pctx).memoryCache?.clear(); SingletonImageLoader.get(pctx).diskCache?.clear(); nav.toast = "已清理图片缓存" }
            }
        }
    }
    item { Group("DEBUG") }
    item { SwitchRow("调试模式", "出错时显示接口的真实返回（状态码、内容开头），并记录最近的请求", Debug.on) { Debug.set(it) } }
    if (Debug.on) item { NavRow("请求记录", "最近的接口请求和返回内容", "${Debug.reqs.collectAsState().value.size} 条") { nav.push(Page.DebugLog) } }
}

internal fun LazyListScope.settingsAboutSection(nav: Nav, p: Pal, scope: CoroutineScope) {
    item { NavRow("版本", null, Updater.current, clickable = false) {} }
    if (Edition.store) item { NavRow("更新", "此版本由 Microsoft Store 自动更新", null, clickable = false) {} }
    if (Edition.selfUpdate) item { SwitchRow("启动时检查更新", "打开应用时自动检查新版本并弹窗提醒", Updater.autoOn) { Updater.setAuto(it) } }
    if (Edition.selfUpdate) item {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).bottomLine().padding(bottom = 12.dp)) {
            T(tr("更新通道"), 16); T(tr("正式版：GitHub Release；测试版：GitHub Pre-release 预发布包"), 13, p.s, lines = 2, lh = 1.4f)
            Spacer(Modifier.height(10.dp))
            ChipRow(listOf("正式版", "测试版"), if (Updater.nightly) 1 else 0) { Updater.useNightly(it == 1) }
        }
    }
    if (Edition.selfUpdate) item {
        val uri = androidx.compose.ui.platform.LocalUriHandler.current
        val info = Updater.latest
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).bottomLine().padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(tr("检查更新"), 16)
                    T(trKnown(when {
                        Updater.checking -> "正在检查…"
                        Updater.error != null -> "检查失败：${Updater.error}"
                        info == null -> "还没检查"
                        info.found -> "有新版本：${info.title}"
                        else -> "已经是最新（${info.title}）"
                    }), 13, if (info?.found == true) p.p else p.s, lines = 2, lh = 1.4f)
                }
                LinkBtn("检查", 14) { if (!Updater.checking) scope.launch { runCatching { Updater.check() }.onSuccess { if (it.found) Updater.dialog = it } } }
            }
            if (info != null && info.found) {
                if (info.notes.isNotBlank()) T(info.notes, 12, p.s, lines = 8, lh = 1.4f, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 10.dp)) {
                    info.url?.let { u -> PrimaryBar("下载", NIcons.download, Modifier.weight(1f)) { runCatching { uri.openUri(u) }.onFailure { nav.toast = "打不开浏览器" } }; Spacer(Modifier.width(8.dp)) }
                    OutlineBtn("查看页面", pad = 14.dp) { runCatching { uri.openUri(info.page) } }
                }
                if (Updater.nightly) T(tr("预发布包包含最新修复与试验功能，直接下载对应平台安装包即可"), 11, p.s, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    item {
        val uri = androidx.compose.ui.platform.LocalUriHandler.current
        NavRow("隐私政策", "本应用如何处理你的数据", null) { runCatching { uri.openUri(Edition.PRIVACY_URL) }.onFailure { nav.toast = "打不开浏览器" } }
    }
    item { NavRow("源代码", "github.com/acnekot/NEKOPLAY", null, clickable = false) {} }
    item {
        // 发布签名公钥：git 提交与 Release 安装包都用它签名，指纹可和 gpg --fingerprint 逐段比对
        val uri = androidx.compose.ui.platform.LocalUriHandler.current
        @Suppress("DEPRECATION") val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).bottomLine().padding(bottom = 12.dp)) {
            T(tr("签名公钥（OpenPGP）"), 16)
            T(tr("git 提交和 GitHub Release 安装包都用这把钥匙签名，可以用它校验下载的安装包是否被篡改"), 13, p.s, lines = 3, lh = 1.4f)
            T(Signing.pretty, 13, p.k, 600, mono = true, lines = 2, lh = 1.5f, modifier = Modifier.padding(top = 8.dp))
            T("ID ${Signing.longId}", 11, p.s, mono = true)
            Row(Modifier.padding(top = 8.dp)) {
                LinkBtn("复制指纹", 13) { clip.setText(androidx.compose.ui.text.AnnotatedString(Signing.FINGERPRINT)); nav.toast = "已复制公钥指纹" }
                Spacer(Modifier.width(16.dp))
                LinkBtn("查看公钥", 13) { runCatching { uri.openUri(Signing.PUBKEY_URL) }.onFailure { nav.toast = "打不开浏览器" } }
            }
        }
    }
}
