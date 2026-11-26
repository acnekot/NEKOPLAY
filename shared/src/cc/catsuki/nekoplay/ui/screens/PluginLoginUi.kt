package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 插件的扫码 / 网页 / Cookie 通用登录页 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable fun PluginLoginPage(pid: String) {
    val nav = LocalNav.current
    val p = c
    val s = Plugins.get(pid)
    val mobile = Plat.impl.name != "desktop"
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val modes by produceState<List<org.json.JSONObject>>(emptyList(), pid) {
        val l = runCatching { Plugins.arr(s?.takeIf { it.start() && it.has("loginModes") }?.call("loginModes")) }.getOrDefault(emptyList())
        value = l.filter { !it.optBoolean("web") || Plat.impl.hasWebView }.ifEmpty {
            listOfNotNull(
                if (s?.has("loginWeb") == true && Plat.impl.hasWebView) org.json.JSONObject().put("id", "web").put("name", "网页登录").put("web", true) else null,
                if (s?.has("loginQr") == true) org.json.JSONObject().put("id", "qr").put("name", "扫码登录") else null,
            )
        }
    }
    var mode by remember { mutableStateOf<org.json.JSONObject?>(null) }
    LaunchedEffect(modes) {
        if (mode == null) mode = if (Plat.isDesktop) (modes.firstOrNull { !it.optBoolean("web") } ?: modes.firstOrNull()) else modes.firstOrNull()
    }
    var round by remember { mutableIntStateOf(0) }
    var img by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var qrInfo by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var status by remember { mutableStateOf("") }
    var ck by remember { mutableStateOf("") }
    fun done() { nav.toast = "${s?.name} 登录成功"; PluginStore.refresh(); if (pid == Plugins.main()?.id) Store.refreshAll(); nav.back() }
    val m = mode
    val web = m?.optBoolean("web") == true
    LaunchedEffect(m?.optString("id"), round) {
        if (m == null || web || s == null) return@LaunchedEffect
        img = null; qrInfo = null; status = "正在获取二维码…"
        try {
            val q = s.call("loginQr", m.optString("id")) as? org.json.JSONObject ?: throw NcmError("插件不支持扫码登录")
            val bytes = q.optString("image").takeIf { it.isNotEmpty() }?.let { kotlin.io.encoding.Base64.decode(it) }
            img = if (bytes != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { val f = java.io.File(Plat.impl.cacheDir, "plugin_qr"); f.writeBytes(bytes); Plat.impl.decodeImage(f.path, 600) }
                else q.optString("text").takeIf { it.isNotEmpty() }?.let { qrBitmap(it) } ?: throw NcmError("插件没有给出二维码")
            qrInfo = q
            status = q.optString("hint").ifEmpty { "扫码并在手机上确认" }
            val key = q.optString("key")
            var fails = 0
            while (true) {
                kotlinx.coroutines.delay(q.optLong("poll", 2000))
                val r = try { s.call("loginCheck", key) as? org.json.JSONObject ?: continue } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (++fails > 5 || e.message?.startsWith("网络错误") != true) throw e; continue }
                fails = 0
                when (r.optString("state")) {
                    "scanned" -> status = "已扫码，请在手机上确认"
                    "expired" -> { status = "二维码已过期，点二维码刷新"; break }
                    "refused" -> { status = "已在手机上取消，点二维码重试"; break }
                    "done" -> { done(); break }
                }
                r.optString("message").takeIf { it.isNotEmpty() }?.let { status = it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { status = "出错了：${e.message}（点二维码重试）" }
    }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        TagRow((s?.name ?: pid).uppercase()); Spacer(Modifier.height(14.dp))
        if (modes.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { md ->
                val on = md.optString("id") == m?.optString("id")
                Box(Modifier.press { if (!on) mode = md }.background(if (on) p.k else androidx.compose.ui.graphics.Color.Transparent).nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 6.dp)) { T(md.optString("name"), 13, if (on) p.tagFg else p.k, 700) }
            }
        }
        if (s == null) { T("插件没有安装", 13, p.s); return@Column }
        if (web) { Spacer(Modifier.height(12.dp)); PluginWebLogin(s, Modifier.weight(1f)) { done() }; return@Column }
        Spacer(Modifier.height(18.dp))
        if (m != null) {
            Box(Modifier.size(240.dp).align(Alignment.CenterHorizontally).nb(1.dp, p.k).background(androidx.compose.ui.graphics.Color.White).press { round++ }, contentAlignment = Alignment.Center) {
                img?.let { androidx.compose.foundation.Image(it, "二维码", Modifier.size(220.dp)) } ?: T("…", 20, androidx.compose.ui.graphics.Color.Black)
            }
            Spacer(Modifier.height(14.dp)); T(status, 13, p.s, lines = 3, lh = 1.5f)
            if (mobile) img?.let { im ->
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val app = qrInfo?.optString("saveApp")?.ifEmpty { null }
                    if (app != null) Box(Modifier.press {
                        runCatching { Plat.impl.savePicture(im, "登录二维码") }.onFailure { nav.toast = "保存失败：${it.message}"; return@press }
                        status = "二维码已存到相册：在${qrInfo?.optString("saveAppName")}扫一扫里点「相册」选它，确认后回到这里"
                        runCatching { uri.openUri(app) }.onFailure { nav.toast = "没能打开${qrInfo?.optString("saveAppName")}" }
                    }.background(p.k).padding(horizontal = 14.dp, vertical = 8.dp)) { T("存图并打开${qrInfo?.optString("saveAppName")}", 13, p.tagFg, 700) }
                    Box(Modifier.press { nav.toast = runCatching { "已保存到" + Plat.impl.savePicture(im, "登录二维码") }.getOrElse { "保存失败：${it.message}" } }
                        .nbf(1.dp, p.k).padding(horizontal = 14.dp, vertical = 8.dp)) { T("保存二维码", 13, p.k, 700) }
                }
            }
        }
        if (s.has("setCookie")) {
            Spacer(Modifier.height(28.dp)); TagRow("COOKIE"); Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(44.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (ck.isEmpty()) T("粘贴 Cookie", 13, p.s, mono = true)
                BasicTextField(ck, { ck = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = Fs.SUB.sp, fontFamily = Mono), cursorBrush = SolidColor(p.p))
            }
            Spacer(Modifier.height(10.dp))
            PrimaryBar("保存 Cookie", NIcons.chevronRight, Modifier.fillMaxWidth()) {
                scope.launch { try { s.call("setCookie", ck); done() } catch (e: Exception) { nav.toast = e.message } }
            }
        }
    }
}

/** 网页登录：内嵌插件提供的登录页，拦截 done 地址换凭证、open 地址拉起外部应用 */
@Composable private fun PluginWebLogin(s: JsSource, modifier: Modifier, onDone: () -> Unit) {
    val p = c
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val cfg by produceState<org.json.JSONObject?>(null, s.id) { value = runCatching { s.call("loginWeb", org.json.JSONObject().put("desktop", Plat.isDesktop)) as? org.json.JSONObject }.getOrNull() }
    val c0 = cfg ?: run { Box(modifier) { T("LOADING", 12, p.s, mono = true) }; return }
    var url by remember(c0) { mutableStateOf(c0.optString("url")) }
    var status by remember(c0) { mutableStateOf(c0.optString("hint")) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    val doneRe = remember(c0) { c0.optString("done").takeIf { it.isNotEmpty() }?.let { Regex(it) } }
    val openRe = remember(c0) { c0.optString("open").takeIf { it.isNotEmpty() }?.let { Regex(it) } }
    val back by DeepLink.url.collectAsState()
    LaunchedEffect(back) {
        val b = back ?: return@LaunchedEffect
        DeepLink.url.value = null
        val next = runCatching { if (s.has("loginWebBack")) s.call("loginWebBack", b) as? String else null }.getOrNull()
        if (next != null) { url = next; status = "已返回，正在继续登录…" } else status = "已返回，但没带回登录信息（$b）"
    }
    Column(modifier) {
        T(status, 13, p.s, lines = 3, lh = 1.5f)
        if (info.isNotEmpty()) T(info, 12, p.s, lines = 2, mono = true)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f).nbf(1.dp, p.k)) {
            Plat.impl.WebPage(url, Modifier.fillMaxSize(), desktopUa = c0.optBoolean("pc"), onInfo = { info = it }, stopOn = c0.optString("done").ifEmpty { null }) { u ->
                when {
                    doneRe?.containsMatchIn(u) == true -> {
                        if (!busy) { busy = true; scope.launch {
                            status = "正在换取凭证…"
                            try { s.call("loginWebDone", u); onDone() }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { status = "出错了：${e.message}"; busy = false; url = c0.optString("url") + (if ('?' in c0.optString("url")) "&" else "?") + "r=" + System.currentTimeMillis() }
                        } }
                        true
                    }
                    openRe?.containsMatchIn(u) == true -> {
                        status = "正在打开 App…"
                        scope.launch {
                            val ios = Plat.impl.name == "ios"
                            val app = org.json.JSONObject().put("ios", ios).put("id", if (ios) "cc.catsuki.nekoplay" else "cc.catsuki.nekoplay.native").put("scheme", "nekoplay://")
                            val target = runCatching { if (s.has("loginWebOpen")) s.call("loginWebOpen", u, app) as? String else null }.getOrNull() ?: u
                            runCatching { uri.openUri(target) }.onFailure { status = "没能打开对应的 App（没装？）" }
                        }
                        true
                    }
                    u.startsWith("http") || u.startsWith("about:") -> false
                    else -> true
                }
            }
        }
    }
}
