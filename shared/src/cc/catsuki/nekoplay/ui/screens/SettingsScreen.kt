package cc.catsuki.nekoplay

import cc.catsuki.nekoplay.ui.settings.dirSheet
import cc.catsuki.nekoplay.ui.settings.menu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                Column(Modifier.weight(1f)) { T(prof?.name ?: tr("本地用户"), 20, w = 800); T(if (prof == null) "LOCAL ACCOUNT" else (Plugins.main()?.name ?: "").uppercase(), 11, p.s, mono = true, ls = .08f) }
                if (prof != null) NIcon(NIcons.chevronRight, 16.dp, p.s)
            }
        }
        // 每个能登录的插件一行；首页来源（主插件）的账号就是上面这个
        if (Plugins.enabledSources.isEmpty()) item { NavRow("还没有音源插件", "本体只带本地播放；到「插件」导入音源插件或功能插件脚本", null) { nav.push(Page.PluginMgr) } }
        Plugins.enabledSources.filter { it.has("account") || it.canLogin }.forEach { s ->
            item(key = "acc" + s.id) {
                val acc by PluginStore.accounts.collectAsState()
                val name = acc[s.id]
                val isMain = s.id == Plugins.main()?.id
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).bottomLine().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { T(s.name + if (isMain) " · 主账号" else "", 16, w = 700); T(if (name == null) "未登录 · 登录后可使用它的歌单和「我喜欢」" else "已登录 · $name", 13, p.s, lh = 1.4f) }
                    if (name == null) { if (s.canLogin) LinkBtn("登录", 14) { nav.push(Page.PluginLogin(s.id)) } }
                    else LinkBtn("退出登录", 14, p.bad) { confirmLogout(nav, s.name) { if (isMain) Store.logout() else PluginStore.logout(s.id) } }
                }
            }
        }
        item { Group("SETTINGS") }
        item { SecRow("外观", "主题、深色、字体、取色、背景、动效") { nav.push(Page.SetSub("look")) } }
        item {
            NavRow("语言", "跟随系统 / 简体中文 / 繁體中文 / English / 日本語", I18n.currentLabel) {
                nav.sheet = SheetSpec("选择语言", "立即生效，无需重启应用", I18n.options.map { opt ->
                    SheetItem((if (opt.code == I18n.code) "✓  " else "    ") + I18n.optionLabel(opt)) {
                        I18n.set(opt.code)
                        nav.toast = "已切换语言：${I18n.currentLabel}"
                    }
                })
            }
        }
        item { SecRow("布局", "首页模块、播放页按钮的位置") { nav.push(Page.SetSub("layout")) } }
        item { SecRow("歌词", "字号、当前句位置、逐字高亮" + if (Plat.isDesktop) "、桌面歌词" else "、通知栏歌词") { nav.push(Page.SetSub("lyric")) } }
        item { SecRow("播放", "音质、效果器、淡入淡出、无缝播放") { nav.push(Page.SetSub("play")) } }
        item { SecRow("存储", "下载、本地音乐、播放缓存") { nav.push(Page.SetSub("storage")) } }
        if (Plat.isDesktop) item { SecRow("桌面", "托盘、侧拉模式、Discord、快捷键") { nav.push(Page.SetSub("desktop")) } }
        item { val pl by Plugins.list.collectAsState(); SecRow("插件", if (pl.isEmpty()) "导入 JS 插件：在线音源、歌词等服务" else "${pl.count { Plugins.on(it.id) }}/${pl.size} 个已开启") { nav.push(Page.PluginMgr) } }
        item {
            val allPlugs by Plugins.list.collectAsState()
            val rev = Plugins.rev
            val extAll = remember(allPlugs, rev) { Plugins.features }
            val extOn = extAll.count { Plugins.on(it.id) }
            SecRow("功能管理", "内置模块 ${Features.list.count { Features.on(it.id) }}/${Features.list.size} · 外置功能 $extOn/${extAll.size}") { nav.push(Page.Feats) }
        }
        item { SecRow("数据", "配置备份 / 导入、WebDAV、听歌统计") { nav.push(Page.SetSub("data")) } }
        item { SecRow("高级", "性能、内存、调试") { nav.push(Page.SetSub("adv")) } }
        item { SecRow("关于", APP_VERSION) { nav.push(Page.SetSub("about")) } }
       }
       "look" -> settingsLookSection(nav, p, sysDark)
       "layout" -> {
        item { NavRow("首页布局", "调整首页模块的顺序，或关掉不需要的", "${HomeLayout.visible.size}/${HomeLayout.ALL.size}") { nav.push(Page.HomeLayout) } }
        if (HomeSrc.options.size > 1) item {
            val opts = HomeSrc.options
            SetLine("首页内容来源", "决定首页与默认搜索使用的音源插件；「混合」将自动聚合各已启用音源的推荐与排行榜") {
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
       "lyric" -> settingsLyricSection(nav, p)
       "play" -> {
        item { NavRow(if (Plat.isDesktop) "音质" else "Wi-Fi 音质", "在线播放的档位，拿不到时自动降一档", SourceApi.LEVEL_LABELS[Engine.quality.coerceIn(0, 4)]) { qualitySheet(nav) } }
        if (!Plat.isDesktop) item { NavRow("流量音质", "用手机流量时换成这个档位", if (Engine.qualityCell < 0) "同 Wi-Fi" else SourceApi.LEVEL_LABELS[Engine.qualityCell]) { cellQualitySheet(nav) } }
        if (Features.on("fx")) item { NavRow("效果器", "10 段均衡器、低音增强、环绕、响度", if (FxStore.fx.on) FxStore.fx.preset else "关") { nav.push(Page.Effects) } }
        if (Features.on("vst")) Plat.impl.vst?.let { v ->
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
        if (Features.on("side")) item { NavRow("侧拉模式", "窗口变成手机宽度贴在屏幕边上，鼠标离开就收起；也可以右键左上角 NEKOPLAY 进入", if (SidePref.active != 0) "使用中" else null) { SidePref.menu(nav) } }
        if (Features.on("side")) item {
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
       "adv" -> settingsAdvSection(nav, p, prefs, pctx, scope, mem, analysis) { analysis = it }
       "about" -> settingsAboutSection(nav, p, scope)
      }
    }
}

/** 设置首页的分类入口 */
@Composable internal fun SecRow(title: String, sub: String, onClick: () -> Unit) = NavRow(title, sub, null, onClick = onClick)

@Composable internal fun Group(tag: String) = Box(Modifier.padding(top = 22.dp, bottom = 8.dp)) { Tag(tag) }

@Composable fun SwitchRow(title: String, sub: String?, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).cascadeEnter().originRippleItem().bottomLine().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(tr(title), 16, if (enabled) c.k else c.s); if (sub != null) T(tr(sub), 13, c.s, lh = 1.4f, lines = 3) }
        Box(Modifier.graphicsAlpha(if (enabled) 1f else .45f)) { NSwitch(on) { if (enabled) onChange(it) } }
    }

private fun Modifier.graphicsAlpha(a: Float) = this.graphicsLayer { alpha = a }

@Composable internal fun NavRow(title: String, sub: String?, value: String?, clickable: Boolean = true, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).cascadeEnter().originRippleItem().then(if (clickable) Modifier.press(onClick = onClick) else Modifier).bottomLine().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(tr(title), 16); if (sub != null) T(tr(sub), 13, c.s, lh = 1.4f, lines = 3) }
        if (value != null) T(trKnown(value), 13, c.s)
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
