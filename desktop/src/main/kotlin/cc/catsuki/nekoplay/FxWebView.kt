package cc.catsuki.nekoplay

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel

/**
 * Linux 的内嵌网页：JavaFX WebView 放进 JFXPanel。
 * - Windows 与 macOS 均禁用内嵌 WebView，QQ 音乐等插件统一使用原生「QQ 扫码 / 微信扫码」或粘贴 Cookie，秒开且无 WebView 兼容问题。
 */
object FxWebView {
    val available: Boolean by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        !os.startsWith("win") && !os.contains("mac") &&
            runCatching { Class.forName("javafx.embed.swing.JFXPanel"); Class.forName("javafx.scene.web.WebView"); true }.getOrDefault(false)
    }
}

/** 网页里的地址每变一次就报给 intercept；匹配 stopOn 的页面先取消加载；cookieUrl 不为空时定时读取 Cookie */
@Composable fun FxWebPage(
    url: String,
    modifier: Modifier,
    desktopUa: Boolean,
    stopOn: String?,
    cookieUrl: String?,
    clearCookie: String?,
    onCookie: (String) -> Unit,
    onInfo: (String) -> Unit,
    intercept: (String) -> Boolean,
) {
    val cb by rememberUpdatedState(intercept)
    val info by rememberUpdatedState(onInfo)
    val cookieCb by rememberUpdatedState(onCookie)
    val stop = remember(stopOn) { stopOn?.let { runCatching { Regex(it) }.getOrNull() } }
    val cookieHost = remember(cookieUrl) { cookieUrl?.let { runCatching { java.net.URI(it).host.orEmpty().lowercase() }.getOrDefault("") }.orEmpty() }
    val holder = remember { FxHolder().apply { cleared = clearCookie.isNullOrBlank() } }
    LaunchedEffect(cookieHost, url) {
        if (cookieHost.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(900)
        while (true) {
            javafx.application.Platform.runLater {
                val e = holder.engine ?: return@runLater
                val curHost = runCatching { java.net.URI(e.location ?: "").host.orEmpty().lowercase() }.getOrDefault("")
                if (curHost == cookieHost || curHost.endsWith(".$cookieHost")) {
                    val ck = runCatching { e.executeScript("document.cookie || ''")?.toString() }.getOrNull().orEmpty()
                    if (!holder.cleared && !clearCookie.isNullOrBlank()) {
                        holder.cleared = true
                        if (Regex("(?:^|;\\s*)${Regex.escape(clearCookie)}=").containsMatchIn(ck)) {
                            val safe = org.json.JSONObject.quote(clearCookie)
                            runCatching { e.executeScript("var d=location.hostname.split('.').slice(-2).join('.');document.cookie=$safe+'=;Max-Age=0;path=/';document.cookie=$safe+'=;Max-Age=0;path=/;domain=.'+d;") }
                            e.load(url)
                            return@runLater
                        }
                    }
                    if (ck.isNotEmpty()) java.awt.EventQueue.invokeLater { cookieCb(ck) }
                }
            }
            kotlinx.coroutines.delay(1200)
        }
    }
    val panel = remember {
        javafx.embed.swing.JFXPanel().also { p ->
            javafx.application.Platform.setImplicitExit(false)
            javafx.application.Platform.runLater {
                val wv = javafx.scene.web.WebView()
                val e = wv.engine
                e.userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                e.createPopupHandler = javafx.util.Callback { e }
                e.locationProperty().addListener { _, _, u ->
                    if (u.isNullOrEmpty()) return@addListener
                    if (stop?.containsMatchIn(u) == true) { holder.stopped = true; e.loadWorker.cancel() }
                    java.awt.EventQueue.invokeLater { info(u.take(120)); cb(u) }
                }
                e.loadWorker.stateProperty().addListener { _, _, st -> if (st == javafx.concurrent.Worker.State.SUCCEEDED) java.awt.EventQueue.invokeLater { info("已打开：" + (e.title ?: e.location.take(80))) } }
                e.loadWorker.exceptionProperty().addListener { _, _, ex -> if (ex != null && !holder.stopped) java.awt.EventQueue.invokeLater { info("加载出错：${ex.message}") } }
                p.scene = javafx.scene.Scene(wv)
                holder.engine = e
                holder.pending?.let { e.load(it) }; holder.pending = null
            }
        }
    }
    LaunchedEffect(url) { javafx.application.Platform.runLater { holder.engine?.load(url) ?: run { holder.pending = url } } }
    DisposableEffect(Unit) { onDispose { javafx.application.Platform.runLater { holder.engine?.load("about:blank"); holder.engine = null } } }
    SwingPanel(factory = { panel }, modifier = modifier)
}

private class FxHolder { var engine: javafx.scene.web.WebEngine? = null; var pending: String? = null; var stopped = false; var cleared = true }
