package cc.catsuki.nekoplay

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel

/**
 * macOS / Linux 的内嵌网页：JavaFX WebView 放进 JFXPanel（轻量 Swing 组件，和 Compose 界面叠放没有问题）。
 * JavaFX 只打进 macOS / Linux 的安装包；Windows 用系统 WebView2（见 DesktopWebView）。
 */
object FxWebView {
    val available: Boolean by lazy {
        !System.getProperty("os.name").orEmpty().startsWith("Windows") &&
            runCatching { Class.forName("javafx.embed.swing.JFXPanel"); Class.forName("javafx.scene.web.WebView"); true }.getOrDefault(false)
    }
}

/** 网页里的地址每变一次就报给 intercept；匹配 stopOn 的页面先取消加载，免得页面自己用掉登录 code */
@Composable fun FxWebPage(url: String, modifier: Modifier, desktopUa: Boolean, stopOn: String?, onInfo: (String) -> Unit, intercept: (String) -> Boolean) {
    val cb by rememberUpdatedState(intercept)
    val info by rememberUpdatedState(onInfo)
    val stop = remember(stopOn) { stopOn?.let { runCatching { Regex(it) }.getOrNull() } }
    val holder = remember { FxHolder() }
    val panel = remember {
        javafx.embed.swing.JFXPanel().also { p ->
            javafx.application.Platform.setImplicitExit(false)   // 关掉网页时不要把 JavaFX 整个退出
            javafx.application.Platform.runLater {
                val wv = javafx.scene.web.WebView()
                val e = wv.engine
                // 用桌面 Chrome 的标识，有的登录页不认 JavaFX 自带的
                e.userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                // 新窗口（window.open / target=_blank）就在当前页打开
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

private class FxHolder { var engine: javafx.scene.web.WebEngine? = null; var pending: String? = null; var stopped = false }
