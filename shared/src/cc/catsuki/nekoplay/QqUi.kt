package cc.catsuki.nekoplay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** QQ 音乐账号的状态（曲库页显示用） */
object QqStore {
    val loggedIn = MutableStateFlow(false)
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun refresh() {
        loggedIn.value = Qqm.loggedIn
        if (!Qqm.loggedIn) { playlists.value = emptyList(); return }
        io.launch {
            try { playlists.value = Qqm.playlists(); error.value = null }
            catch (e: Exception) { error.value = e.message }
        }
    }
    fun logout() { Qqm.logout(); refresh() }
}

/** 搜索来源：0 网易云 / 1 QQ 音乐 / 2 酷狗 */
object SearchSource {
    var src by mutableIntStateOf(0)
    val qq get() = src == 1
    val kg get() = src == 2
    val labels get() = listOf("网易云") + (if (Features.on("qq")) listOf("QQ 音乐") else emptyList()) + (if (Features.on("kugou")) listOf("酷狗") else emptyList())
    fun idOf(label: String) = when (label) { "QQ 音乐" -> 1; "酷狗" -> 2; else -> 0 }
}

/** 曲库页里的 QQ 音乐区：没登录时一行入口，登录后列出歌单（第一个是「我喜欢」） */
fun LazyListScope.qqLibrary(nav: Nav) {
    item {
        val on by QqStore.loggedIn.collectAsState()
        val pls by QqStore.playlists.collectAsState()
        Column(Modifier.padding(top = 26.dp, bottom = 8.dp)) {
            TagRow("QQ MUSIC", 12.dp) {
                if (on) { T("${two(pls.size)} 个", 13, c.s, mono = true); Spacer(Modifier.width(10.dp)); LinkBtn("退出", 13) { QqStore.logout(); nav.toast = "已退出 QQ 音乐" } }
            }
        }
        if (!on) ListRow({ IconCover(NIcons.chevronRight, 56.dp) }, "登录 QQ 音乐", "扫码登录后在这里看到 QQ 音乐的歌单") { nav.push(Page.QqLogin) }
        else QqStore.error.collectAsState().value?.let { T(it, 13, c.s, lines = 3) }
    }
    item {
        val pls by QqStore.playlists.collectAsState()
        Column {
            pls.forEach { p ->
                ListRow({ Cover(p.cover, 56.dp, p.name) }, p.name, (if (p.mine) "" else "歌单 · ") + "${two(p.count)} 首") { nav.push(Page.Detail("pl", p.id, p.name, p.cover)) }
            }
        }
    }
}

/** QQ 音乐扫码登录：QQ 或微信扫码 */
@Composable fun QqLoginPage() {
    val nav = LocalNav.current
    var wx by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    val mobile = Plat.impl.name != "desktop"
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    LaunchedEffect(wx, round) {
        img = null; link = null; status = "正在获取二维码…"
        try {
            val q = if (wx) Qqm.qrWx() else Qqm.qrQq()
            img = withContext(Dispatchers.IO) {
                val f = File(Plat.impl.cacheDir, "qq_qr"); f.writeBytes(q.png)
                Plat.impl.decodeImage(f.path, 600)
            }
            if (!wx && mobile) link = withContext(Dispatchers.Default) { Plat.impl.qrText(q.png) }?.let { Qqm.qqAppLink(it) }
            status = if (wx) "用微信扫码，并在手机上点确认" else if (link != null) "点「打开手机 QQ 登录」，确认后回到这里；也可以用另一台手机扫码" else "用手机 QQ 扫码，并在手机上点确认"
            var fails = 0
            while (isActive) {
                delay(if (wx) 300 else 2000)
                // 切去手机 QQ 确认时 App 会被挂起，回来第一次请求可能失败，网络错误重试几次
                val st = try { Qqm.qrCheck(q) { status = it } } catch (e: NcmError) { throw e } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (++fails > 5) throw e; continue }
                fails = 0
                when (st) {
                    Qqm.QrState.WAIT -> {}
                    Qqm.QrState.SCANNED -> status = "已扫码，请在手机上点「确认登录」"
                    Qqm.QrState.EXPIRED -> { status = "二维码已过期，点二维码刷新"; break }
                    Qqm.QrState.REFUSED -> { status = "已在手机上取消，点二维码重试"; break }
                    Qqm.QrState.DONE -> { QqStore.refresh(); nav.toast = "QQ 音乐登录成功"; nav.back(); break }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { status = "出错了：${e.message}（点二维码重试）" }
    }
    val p = c
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        TagRow("QQ MUSIC"); Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("QQ 扫码" to false, "微信扫码" to true).forEach { (l, v) ->
                val on = wx == v
                Box(Modifier.press { if (!on) wx = v }.background(if (on) p.k else Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 6.dp)) { T(l, 13, if (on) p.tagFg else p.k, 700) }
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.size(240.dp).align(Alignment.CenterHorizontally).nb(1.dp, p.k).background(Color.White).press { round++ }, contentAlignment = Alignment.Center) {
            img?.let { Image(it, "二维码", Modifier.size(220.dp)) } ?: T("…", 20, Color.Black)
        }
        Spacer(Modifier.height(14.dp)); T(status, 13, p.s, lines = 3, lh = 1.5f)
        if (mobile) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            link?.let { l -> Box(Modifier.press { runCatching { uri.openUri(l) }.onFailure { nav.toast = "没能打开手机 QQ" } }.background(p.k).padding(horizontal = 14.dp, vertical = 8.dp)) { T("打开手机 QQ 登录", 13, p.tagFg, 700) } }
            img?.let { im -> Box(Modifier.press { nav.toast = runCatching { "已保存到" + Plat.impl.savePicture(im, "QQ音乐登录二维码") + "，在 " + (if (wx) "微信" else "QQ") + " 扫一扫里选相册" }.getOrElse { "保存失败：${it.message}" } }
                .nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 8.dp)) { T("保存二维码", 13, p.k, 700) } }
        }
        Spacer(Modifier.height(10.dp))
        T("登录后可以播放账号有权限的歌、查看 QQ 音乐的歌单；登录后 QQ 音乐的歌点喜欢会同步到 QQ 音乐的「我喜欢」", 12, p.s, lines = 3, lh = 1.5f)
    }
}

// ======================= 酷狗 =======================
object KgStore {
    val loggedIn = MutableStateFlow(false)
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun refresh() {
        loggedIn.value = Kgm.loggedIn
        if (!Kgm.loggedIn) { playlists.value = emptyList(); return }
        io.launch {
            try { playlists.value = Kgm.playlists(); error.value = null }
            catch (e: Exception) { error.value = e.message }
        }
    }
    fun logout() { Kgm.logout(); refresh() }
}

fun LazyListScope.kgLibrary(nav: Nav) {
    item {
        val on by KgStore.loggedIn.collectAsState()
        val pls by KgStore.playlists.collectAsState()
        Column(Modifier.padding(top = 26.dp, bottom = 8.dp)) {
            TagRow("KUGOU", 12.dp) {
                if (on) { T("${two(pls.size)} 个", 13, c.s, mono = true); Spacer(Modifier.width(10.dp)); LinkBtn("退出", 13) { KgStore.logout(); nav.toast = "已退出酷狗" } }
            }
        }
        if (!on) ListRow({ IconCover(NIcons.chevronRight, 56.dp) }, "登录酷狗音乐", "扫码登录后在这里看到酷狗的歌单") { nav.push(Page.KgLogin) }
        else KgStore.error.collectAsState().value?.let { T(it, 13, c.s, lines = 3) }
    }
    item {
        val pls by KgStore.playlists.collectAsState()
        Column {
            pls.forEach { p ->
                ListRow({ Cover(p.cover, 56.dp, p.name) }, p.name, (if (p.mine) "" else "歌单 · ") + "${two(p.count)} 首") { nav.push(Page.Detail("pl", p.id, p.name, p.cover)) }
            }
        }
    }
}

/** 酷狗扫码登录：用酷狗音乐 App 扫码 */
@Composable fun KgLoginPage() {
    val nav = LocalNav.current
    var round by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(round) {
        img = null; status = "正在获取二维码…"
        try {
            val (url, key) = Kgm.qrKey()
            img = withContext(Dispatchers.Default) { qrBitmap(url) }
            status = "用酷狗音乐 App 扫码，并在手机上点确认"
            while (isActive) {
                delay(2000)
                when (Kgm.qrCheck(key)) {
                    0 -> { status = "二维码已过期，点二维码刷新"; break }
                    2 -> status = "已扫码，请在手机上点确认"
                    4 -> { KgStore.refresh(); nav.toast = "酷狗登录成功"; nav.back(); break }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { status = "出错了：${e.message}（点二维码重试）" }
    }
    val p = c
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        TagRow("KUGOU"); Spacer(Modifier.height(18.dp))
        Box(Modifier.size(240.dp).align(Alignment.CenterHorizontally).nb(1.dp, p.k).background(Color.White).press { round++ }, contentAlignment = Alignment.Center) {
            img?.let { Image(it, "二维码", Modifier.size(220.dp)) } ?: T("…", 20, Color.Black)
        }
        Spacer(Modifier.height(14.dp)); T(status, 13, p.s, lines = 3, lh = 1.5f)
        Spacer(Modifier.height(10.dp))
        T("登录后可以播放账号有权限的歌、查看酷狗的歌单；酷狗的歌点喜欢只记在本机", 12, p.s, lines = 3, lh = 1.5f)
    }
}
