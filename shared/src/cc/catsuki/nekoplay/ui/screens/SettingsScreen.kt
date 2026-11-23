package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.ui.settings.dirSheet
import cc.catsuki.nekoplay.ui.settings.downloadSheet
import cc.catsuki.nekoplay.ui.settings.menu

import kotlin.math.roundToInt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/** 底部细线 */
@Composable fun SettingsPage(sec: String? = null) {
    val nav = LocalNav.current
    val prof by Store.profile.collectAsState()
    val prefs = remember { Plat.prefs("ui") }
    val pctx = coil3.compose.LocalPlatformContext.current
    var cacheSize by remember { mutableStateOf(0L) }
    LaunchedEffect(nav.sheet) { cacheSize = withContext(Dispatchers.IO) { Engine.cacheBytes() } }
    var mem by remember { mutableStateOf("") }
    var analysis by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (isActive) { mem = withContext(Dispatchers.IO) { Plat.impl.memInfo() }; delay(2000) } }
    val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
    val p = c
    LazyColumn(Modifier.widthIn(max = 860.dp).fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 28.dp)) {
      when (sec) {
       null -> {
        item { Group("ACCOUNT") }
        item {
            Row(Modifier.fillMaxWidth().bottomLine(p.k, 2.dp).press { prof?.let { nav.push(Page.User(it.uid, it.name)) } }.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(54.dp, 24); Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) { T(prof?.name ?: "本地用户", 20, w = 800); T(if (prof == null) "LOCAL ACCOUNT" else (Plugins.main()?.name ?: "").uppercase(), 11, p.s, mono = true, ls = .08f) }
                if (prof != null) NIcon(NIcons.chevronRight, 16.dp, p.s)
            }
        }
        // 每个能登录的插件一行；首页来源（主插件）的账号就是上面这个
        if (Plugins.enabled.isEmpty()) item { NavRow("还没有音源插件", "本体只带本地播放；到「插件」导入网易云、QQ 音乐等脚本", null) { nav.push(Page.PluginMgr) } }
        Plugins.enabled.filter { it.has("account") || it.has("loginQr") }.forEach { s ->
            item(key = "acc" + s.id) {
                val acc by PluginStore.accounts.collectAsState()
                val name = acc[s.id]
                val isMain = s.id == Plugins.main()?.id
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).bottomLine().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { T(s.name + if (isMain) " · 主账号" else "", 16, w = 700); T(if (name == null) "未登录 · 登录后可使用它的歌单和「我喜欢」" else "已登录 · $name", 13, p.s, lh = 1.4f) }
                    if (name == null) { if (s.has("loginQr")) LinkBtn("登录", 14) { nav.push(Page.PluginLogin(s.id)) } }
                    else LinkBtn("退出登录", 14, p.bad) { confirmLogout(nav, s.name) { if (isMain) Store.logout() else PluginStore.logout(s.id) } }
                }
            }
        }
        item { Group("SETTINGS") }
        item { SecRow("外观", "主题、深色、字体、取色、背景、动效") { nav.push(Page.SetSub("look")) } }
        item { SecRow("布局", "首页模块、播放页按钮的位置") { nav.push(Page.SetSub("layout")) } }
        item { SecRow("歌词", "字号、当前句位置、逐字高亮" + if (Plat.isDesktop) "、桌面歌词" else "、通知栏歌词") { nav.push(Page.SetSub("lyric")) } }
        item { SecRow("播放", "音质、效果器、淡入淡出、无缝播放") { nav.push(Page.SetSub("play")) } }
        item { SecRow("存储", "下载、本地音乐、播放缓存") { nav.push(Page.SetSub("storage")) } }
        if (Plat.isDesktop) item { SecRow("桌面", "托盘、侧拉模式、Discord、快捷键") { nav.push(Page.SetSub("desktop")) } }
        item { val pl by Plugins.list.collectAsState(); SecRow("插件", if (pl.isEmpty()) "导入 JS 插件：在线音源、歌词等服务" else "${pl.count { Plugins.on(it.id) }}/${pl.size} 个已开启") { nav.push(Page.PluginMgr) } }
        item { SecRow("功能管理", "开关内置插件：${Features.list.count { Features.on(it.id) }}/${Features.list.size} 已开启") { nav.push(Page.Feats) } }
        item { SecRow("数据", "配置备份 / 导入、WebDAV、听歌统计") { nav.push(Page.SetSub("data")) } }
        item { SecRow("高级", "性能、内存、调试") { nav.push(Page.SetSub("adv")) } }
        item { SecRow("关于", APP_VERSION) { nav.push(Page.SetSub("about")) } }
       }
       "look" -> {
        item { SwitchRow("跟随系统", "跟随系统的深浅色设置", ThemePref.mode == 0) { ThemePref.set(if (it) 0 else if (sysDark) 2 else 1) } }
        item { SwitchRow("深色模式", if (ThemePref.mode == 0) "当前跟随系统，点击可手动切换" else "切换为暗色界面", if (ThemePref.mode == 0) sysDark else ThemePref.mode == 2) { ThemePref.set(if (it) 2 else 1) } }
        item { NavRow("主题", "界面风格：BenchLab 粗线框 / 无边框（默认）", Look.NAMES[Look.style]) { nav.sheet = SheetSpec("选择主题", null, Look.NAMES.mapIndexed { i, n -> SheetItem((if (i == Look.style) "✓  " else "    ") + n) { Look.set(i) } }) } }
        item { ThemeStudioSection() }
        if (Plat.isDesktop) item { SwitchRow("自适应大小", "按窗口宽度自动微调界面比例（以 1280 宽为基准），拖大窗口更饱满、缩窄更紧凑", UiPref.autoScale) { UiPref.autoScale = it; UiPref.save() } }
        item {
            // 拖动时只改数字，松手才应用（边拖边缩放会让滑条跑出手指下面）
            var draft by remember { mutableIntStateOf(UiPref.scale) }
            SetLine("界面缩放", "整个界面连同文字一起放大或缩小；小屏手机调小能看到更多内容") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { NSlider(draft.toFloat(), 60f..150f, onDone = { UiPref.scale = draft; UiPref.save() }) { draft = (it / 5f).roundToInt() * 5 } }
                    Spacer(Modifier.width(10.dp)); T("$draft%", 14, p.p, 700, mono = true)
                    if (draft != 100) { Spacer(Modifier.width(8.dp)); T("重置", 13, p.s, modifier = Modifier.press { draft = 100; UiPref.scale = 100; UiPref.save() }) }
                }
            }
        }
        item { NavRow("字体", "默认 Inter 无衬线；也可以改用系统字体", if (Typo.sans == 0) "系统字体" else "Inter") { Typo.set(1 - Typo.sans) } }
        item { MonetSettings() }
        item { NavRow("自定义背景", "选一张图片，可调模糊、透明度、位置和大小", if (Bg.on) "已设置" else "未设置") { nav.push(Page.Background) } }
        if (Bg.on) item {
            SetLine("栏位不透明度", "有自定义背景时，侧边栏、顶栏、播放条的不透明度") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { NSlider(UiPref.barAlpha, 0f..1f, onDone = { UiPref.save() }) { UiPref.barAlpha = it } }
                    Spacer(Modifier.width(10.dp)); T("%.0f%%".format(UiPref.barAlpha * 100), 14, p.p, 700, mono = true)
                }
            }
        }
        item { NavRow("动效", "动画形式和速度", "${AnimStyle.labels[AnimStyle.style]} · ${Anim.labels[Anim.level]}") { nav.push(Page.Tuning) } }
       }
       "layout" -> {
        item { NavRow("首页布局", "调整首页模块的顺序，或关掉不需要的", "${HomeLayout.visible.size}/${HomeLayout.ALL.size}") { nav.push(Page.HomeLayout) } }
        if (HomeSrc.options.size > 1) item {
            val opts = HomeSrc.options
            SetLine("首页内容来源", "首页显示哪个音源的内容，搜索也默认用它；混合 = 网易云的日推 / 推荐，加上 QQ 音乐、酷狗的排行榜") {
                ChipRow(opts.map { it.first }, opts.indexOfFirst { it.second == HomeSrc.current }.coerceAtLeast(0)) { HomeSrc.set(opts[it].second) }
            }
        }
        item { NavRow("播放页布局", "每个按钮放在哪、什么顺序；手机和 PC 各一套", null) { nav.push(Page.BtnLayout) } }
        item {
            SwitchRow("PC 按钮竖栏自动隐藏", "歌词旁的按钮平时隐藏，鼠标靠近才显示", UiPref.autoHideSide) { UiPref.autoHideSide = it; UiPref.save() }
        }
        item {
            SetLine("PC 播放页封面大小", "宽屏播放页左边封面占可用空间的比例") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { NSlider(UiPref.coverPct.toFloat(), 50f..100f, onDone = { UiPref.save() }) { UiPref.coverPct = it.toInt() } }
                    Spacer(Modifier.width(10.dp)); T("${UiPref.coverPct}%", 14, p.p, 700, mono = true)
                }
            }
        }
        item { SwitchRow("进度条显示缓存", "进度条上用深一点的颜色标出已经下载好的部分", UiPref.showBuffer) { UiPref.showBuffer = it; UiPref.save() } }
       }
       "lyric" -> {
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
        if (!Plat.isDesktop) item { SwitchRow("通知栏歌词", "播放通知里显示当前这句歌词；也会发送状态栏歌词（魅族协议，配合状态栏歌词模块）", DeskLyric.notify) { DeskLyric.notify = it; DeskLyric.save() } }
        if (Features.on("desk")) item { SwitchRow("桌面歌词", "置顶的悬浮歌词窗：鼠标移上去可以切歌、调字号、锁定；托盘菜单也能开关", DeskLyric.on) { DeskLyric.on = it; DeskLyric.save() } }
        if (Features.on("desk") && DeskLyric.on) deskLyricSettings(p)
       }
       "play" -> {
        item { NavRow(if (Plat.isDesktop) "音质" else "Wi-Fi 音质", "在线播放的档位，拿不到时自动降一档", Ncm.LEVEL_LABELS[Engine.quality.coerceIn(0, 4)]) { qualitySheet(nav) } }
        if (!Plat.isDesktop) item { NavRow("流量音质", "用手机流量时换成这个档位", if (Engine.qualityCell < 0) "同 Wi-Fi" else Ncm.LEVEL_LABELS[Engine.qualityCell]) { cellQualitySheet(nav) } }
        item { NavRow("效果器", "10 段均衡器、低音增强、环绕、响度", if (FxStore.fx.on) FxStore.fx.preset else "关") { nav.push(Page.Effects) } }
        Plat.impl.vst?.let { v ->
            item { NavRow("VST 插件 (Beta)", "实验性：加载 VST3 / VST2 音效与可视化仪（如 MiniMeters），支持置顶浮窗", if (v.enabled) "${v.chain.size} 个运行中" else if (v.chain.isNotEmpty()) "已停用（${v.chain.size}）" else "关") { nav.push(Page.Vst) } }
        }
        item { val ms = listOf(0, 150, 300, 800); SetLine("淡入淡出", "暂停、继续和切歌时音量渐变") { ChipRow(listOf("关", "短", "中", "长"), ms.indexOf(Engine.fadeMs).coerceAtLeast(0)) { Engine.setFade(ms[it]) } } }
        item { SwitchRow("无缝播放", "快播完时提前把下一首接上，歌和歌之间不会断一下（现场专辑、连续曲目）", Engine.gapless) { Engine.changeGapless(it) } }
       }
       "storage" -> {
        if (Features.on("dl")) item { NavRow("下载位置", "新下载的歌放在哪个文件夹；已经下载的不会移动", Downloads.dir.absolutePath.let { if (it.length > 28) "…" + it.takeLast(27) else it }) { dirSheet(nav) } }
        if (Features.on("dl")) item { val dls by Downloads.items.collectAsState(); NavRow("本地下载", "按当前音质下载，附带歌词 · ${Downloads.dir.absolutePath}", "${dls.size} 首") { nav.push(Page.DlList) } }
        if (Features.on("local")) item { val loc by LocalMusic.list.collectAsState(); NavRow("本地音乐", "扫描手机 / 电脑里的歌", "${loc.size} 首") { nav.push(Page.Local) } }
        item {
            NavRow("播放缓存", "听过的歌存在本地，再听不用重新下载（上限 1GB）", "%.0f MB".format(cacheSize / 1048576.0)) {
                nav.sheet = SheetSpec("播放缓存", "已用 %.0f MB / 1024 MB".format(cacheSize / 1048576.0), listOf(SheetItem("清空播放缓存", danger = true) {
                    scope.launch { withContext(Dispatchers.IO) { Engine.clearCache() }; cacheSize = 0; nav.toast = "已清空播放缓存" }
                }))
            }
        }
       }
       "desktop" -> {
        if (UiPref.isMac) item { SwitchRow("窗口圆角", "使用 macOS 原生不透明圆角窗口与系统阴影（关掉后为直角无边框窗口，重启后生效）", UiPref.round) { UiPref.round = it; UiPref.save(); nav.toast = "重启后生效" } }
        item { SwitchRow("后台留存", "关闭窗口时缩到系统托盘继续播放；托盘图标可以播放 / 暂停、切歌、退出", UiPref.tray) { UiPref.tray = it; UiPref.save() } }
        item { NavRow("侧拉模式", "窗口变成手机宽度贴在屏幕边上，鼠标离开就收起；也可以右键左上角 NEKOPLAY 进入", if (SidePref.active != 0) "使用中" else null) { SidePref.menu(nav) } }
        item {
            SetLine("侧拉宽度", "侧拉模式下窗口的宽度") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { NSlider(SidePref.width.toFloat(), 360f..560f, onDone = { SidePref.save() }) { SidePref.width = it.toInt() } }
                    Spacer(Modifier.width(10.dp)); T("${SidePref.width}", 14, p.p, 700, mono = true)
                }
            }
        }
        if (Features.on("discord")) item { SwitchRow("Discord 状态", "在 Discord 上显示正在听的歌（需要打开 Discord 客户端）" + if (DiscordPref.status.isNotEmpty()) " · ${DiscordPref.status}" else "", DiscordPref.on) { DiscordPref.on = it; DiscordPref.save() } }
        if (Features.on("discord") && DiscordPref.on) item {
            SetLine("Discord Client ID", "在 discord.com/developers 新建一个应用（名字会显示成「正在听 xxx」），把 Application ID 填这里") {
                Box(Modifier.fillMaxWidth().height(40.dp).nbf(1.dp, p.k).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (DiscordPref.clientId.isEmpty()) T("Application ID", 13, p.s, mono = true)
                    BasicTextField(DiscordPref.clientId, { DiscordPref.clientId = it.filter { ch -> ch.isDigit() }; DiscordPref.save() }, Modifier.fillMaxWidth(), singleLine = true,
                        textStyle = TextStyle(color = p.k, fontSize = Fs.SUB.sp, fontFamily = Mono), cursorBrush = SolidColor(p.p))
                }
            }
        }
        item { NavRow("快捷键", "播放、切歌、音量等键盘操作", "${Keys.HELP.size} 个") { nav.push(Page.KeyMap) } }
       }
       "data" -> {
        item { NavRow("备份 / 导入", "导出配置文件、从文件或剪贴板导入" + if (Features.on("webdav")) "，或备份到 WebDAV 网盘" else "", if (Backup.last > 0) java.text.SimpleDateFormat("MM-dd").format(java.util.Date(Backup.last)) else null) { nav.push(Page.Backup) } }
        if (Features.on("stats")) item { NavRow("听歌统计", "本机的播放次数和时长", null) { nav.push(Page.Stats) } }
       }
       "adv" -> {
        item { Group("PERFORMANCE") }
        item { val ps by Perf.stat.collectAsState(); T(Perf.line(ps), 11, p.s, mono = true, lines = 3, lh = 1.5f, modifier = Modifier.padding(vertical = 10.dp)) }
        item { val po by Perf.overlay.collectAsState(); SwitchRow("性能浮窗", "在所有页面左下角显示 CPU、帧率和 GPU", po) { Perf.overlay.value = it; prefs.edit().putBoolean("perf", it).apply() } }
        if (Plat.isDesktop) item {
            val soft = prefs.getString("render", "gpu") == "soft"
            SwitchRow("软件渲染（省内存）", "用 CPU 画界面，少占约 170MB 内存，但帧率会掉到 30 帧以下（开背景模糊时更低）。重启生效", soft) { prefs.edit().putString("render", if (it) "soft" else "gpu").apply(); nav.toast = "重启后生效" }
        }
        item { SwitchRow("省内存模式", "图片缓存变小、头像不播放动画、逐字高亮降到约 20 帧${if (Plat.isDesktop) "、最小化时释放内存" else ""}；图片缓存大小重启后生效", UiPref.lowMem) { UiPref.lowMem = it; UiPref.save() } }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).bottomLine().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("内存占用", 16)
                    T(mem, 13, p.s, lines = 3, lh = 1.4f)
                    if (analysis.isNotEmpty()) T(analysis, 11, p.k, mono = true, lines = 8, lh = 1.4f)
                }
                Column(horizontalAlignment = Alignment.End) {
                    LinkBtn("分析", 14) { scope.launch { analysis = withContext(Dispatchers.IO) { Plat.impl.memAnalysis() } } }
                    LinkBtn("清理缓存", 14) { SingletonImageLoader.get(pctx).memoryCache?.clear(); SingletonImageLoader.get(pctx).diskCache?.clear(); nav.toast = "已清理图片缓存" }
                }
            }
        }
        item { Group("DEBUG") }
        item { SwitchRow("调试模式", "出错时显示接口的真实返回（状态码、内容开头），并记录最近的请求", Debug.on) { Debug.set(it) } }
        if (Debug.on) item { NavRow("请求记录", "最近的接口请求和返回内容", "${Debug.reqs.collectAsState().value.size} 条") { nav.push(Page.DebugLog) } }
       }
       "about" -> {
        item { NavRow("版本", null, Updater.current, clickable = false) {} }
        item { SwitchRow("启动时检查更新", "打开应用时自动检查新版本并弹窗提醒", Updater.autoOn) { Updater.setAuto(it) } }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).bottomLine().padding(bottom = 12.dp)) {
                T("更新通道", 16); T("正式版：GitHub Release；测试版：main 分支最新一次 Actions 打包", 13, p.s, lines = 2, lh = 1.4f)
                Spacer(Modifier.height(10.dp))
                ChipRow(listOf("正式版", "测试版 nightly"), if (Updater.nightly) 1 else 0) { Updater.useNightly(it == 1) }
            }
        }
        item {
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            val info = Updater.latest
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).bottomLine().padding(bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("检查更新", 16)
                        T(when {
                            Updater.checking -> "正在检查…"
                            Updater.error != null -> "检查失败：${Updater.error}"
                            info == null -> "还没检查"
                            info.found -> "有新版本：${info.title}"
                            else -> "已经是最新（${info.title}）"
                        }, 13, if (info?.found == true) p.p else p.s, lines = 2, lh = 1.4f)
                    }
                    LinkBtn("检查", 14) { if (!Updater.checking) scope.launch { runCatching { Updater.check() }.onSuccess { if (it.found) Updater.dialog = it } } }
                }
                if (info != null && info.found) {
                    if (info.notes.isNotBlank()) T(info.notes, 12, p.s, lines = 8, lh = 1.4f, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 10.dp)) {
                        info.url?.let { u -> PrimaryBar("下载", NIcons.download, Modifier.weight(1f)) { runCatching { uri.openUri(u) }.onFailure { nav.toast = "打不开浏览器" } }; Spacer(Modifier.width(8.dp)) }
                        OutlineBtn("查看页面", pad = 14.dp) { runCatching { uri.openUri(info.page) } }
                    }
                    if (Updater.nightly) T("测试版下载的是 zip，解压后安装里面的文件", 11, p.s, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item { NavRow("源代码", "github.com/acnekot/NEKOPLAY", null, clickable = false) {} }
       }
      }
    }
}

/** 设置首页的分类入口 */
@Composable private fun SecRow(title: String, sub: String, onClick: () -> Unit) = NavRow(title, sub, null, onClick = onClick)

@Composable private fun Group(tag: String) = Box(Modifier.padding(top = 22.dp, bottom = 8.dp)) { Tag(tag) }

@Composable fun SwitchRow(title: String, sub: String?, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).bottomLine().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(title, 16, if (enabled) c.k else c.s); if (sub != null) T(sub, 13, c.s, lh = 1.4f, lines = 3) }
        Box(Modifier.graphicsAlpha(if (enabled) 1f else .45f)) { NSwitch(on) { if (enabled) onChange(it) } }
    }

private fun Modifier.graphicsAlpha(a: Float) = this.graphicsLayer { alpha = a }

@Composable private fun NavRow(title: String, sub: String?, value: String?, clickable: Boolean = true, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).then(if (clickable) Modifier.press(onClick = onClick) else Modifier).bottomLine().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(title, 16); if (sub != null) T(sub, 13, c.s, lh = 1.4f, lines = 3) }
        if (value != null) T(value, 13, c.s)
        if (clickable) { Spacer(Modifier.width(6.dp)); NIcon(NIcons.chevronRight, 16.dp, c.s) }
    }


/** 二维码画成 ImageBitmap（两端通用） */
fun qrBitmap(text: String): androidx.compose.ui.graphics.ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 440, 440)
    val b = androidx.compose.ui.graphics.ImageBitmap(m.width, m.height)
    val cv = androidx.compose.ui.graphics.Canvas(b)
    val white = androidx.compose.ui.graphics.Paint().apply { color = Color.White }
    val black = androidx.compose.ui.graphics.Paint().apply { color = Color.Black }
    cv.drawRect(0f, 0f, m.width.toFloat(), m.height.toFloat(), white)
    for (x in 0 until m.width) for (y in 0 until m.height) if (m[x, y]) cv.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, black)
    return b
}


/** 可选布局：继续收听和每日推荐各占一半（窄屏时上下排） */
/** 退出登录前再确认一次 */
fun confirmLogout(nav: Nav, name: String, run: () -> Unit) {
    nav.sheet = SheetSpec("退出$name？", "退出后要重新登录才能看到这个账号的歌单和「我喜欢」", listOf(SheetItem("退出登录", danger = true) { run(); nav.toast = "已退出$name" }))
}
