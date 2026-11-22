package cc.catsuki.nekoplay

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp

/**
 * macOS / Linux 的内嵌网页：
 * - macOS：Compose 使用 Metal 渲染，SwingPanel 内嵌 JFXPanel 会与 Metal 层冲突导致卡死；
 *   同时在 AWT 主循环下原生 JavaFX Stage 容易白屏，因此 macOS 用独立 Swing JFrame 承载 JFXPanel（官方 AWT-JavaFX 桥接，既不碰 Metal 又能正常渲染）。
 * - Linux：直接用 JFXPanel 放进 SwingPanel。
 * - Windows：使用系统 WebView2（见 DesktopWebView）。
 */
object FxWebView {
    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    val available: Boolean by lazy {
        !System.getProperty("os.name").orEmpty().startsWith("Windows") &&
            runCatching { Class.forName("javafx.embed.swing.JFXPanel"); Class.forName("javafx.scene.web.WebView"); true }.getOrDefault(false)
    }

    val useSeparateFrame: Boolean get() = isMac
}

/** 网页里的地址每变一次就报给 intercept；匹配 stopOn 的页面先取消加载，免得页面自己用掉登录 code */
@Composable fun FxWebPage(url: String, modifier: Modifier, desktopUa: Boolean, stopOn: String?, onInfo: (String) -> Unit, intercept: (String) -> Boolean) {
    if (FxWebView.useSeparateFrame) {
        FxFrameWebPage(url, modifier, stopOn, onInfo, intercept)
        return
    }
    val cb by rememberUpdatedState(intercept)
    val info by rememberUpdatedState(onInfo)
    val stop = remember(stopOn) { stopOn?.let { runCatching { Regex(it) }.getOrNull() } }
    val holder = remember { FxHolder() }
    val panel = remember {
        javafx.embed.swing.JFXPanel().also { p ->
            javafx.application.Platform.setImplicitExit(false)
            javafx.application.Platform.runLater {
                val wv = javafx.scene.web.WebView()
                val e = wv.engine
                configureEngine(e, holder, stop, { info(it) }, { cb(it) })
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

/** macOS 专用：用独立 Swing JFrame + JFXPanel 打开登录网页，避开 Compose Metal SwingPanel 冲突且保证 JavaFX 正常渲染 */
@Composable private fun FxFrameWebPage(url: String, modifier: Modifier, stopOn: String?, onInfo: (String) -> Unit, intercept: (String) -> Boolean) {
    val cb by rememberUpdatedState(intercept)
    val info by rememberUpdatedState(onInfo)
    val stop = remember(stopOn) { stopOn?.let { runCatching { Regex(it) }.getOrNull() } }
    val holder = remember { FxHolder() }
    var frameOpen by remember { mutableStateOf(false) }

    fun openOrFocus(targetUrl: String) {
        javax.swing.SwingUtilities.invokeLater {
            val existing = holder.frame
            if (existing != null) {
                existing.isVisible = true
                existing.toFront()
                if (targetUrl.isNotEmpty() && holder.lastUrl != targetUrl) {
                    holder.lastUrl = targetUrl
                    javafx.application.Platform.runLater { holder.engine?.load(targetUrl) }
                }
                frameOpen = true
                return@invokeLater
            }
            val jfxPanel = javafx.embed.swing.JFXPanel()
            val frame = javax.swing.JFrame("NEKOPLAY 登录").apply {
                defaultCloseOperation = javax.swing.WindowConstants.HIDE_ON_CLOSE
                setSize(540, 640)
                setLocationRelativeTo(null)
                contentPane.add(jfxPanel)
                addWindowListener(object : java.awt.event.WindowAdapter() {
                    override fun windowClosing(e: java.awt.event.WindowEvent?) { frameOpen = false }
                })
                isVisible = true
                toFront()
            }
            holder.frame = frame
            holder.lastUrl = targetUrl
            frameOpen = true
            javafx.application.Platform.setImplicitExit(false)
            javafx.application.Platform.runLater {
                val wv = javafx.scene.web.WebView()
                val e = wv.engine
                configureEngine(e, holder, stop, { info(it) }) { u ->
                    val handled = cb(u)
                    if (handled && stop?.containsMatchIn(u) == true) {
                        javax.swing.SwingUtilities.invokeLater { holder.frame?.isVisible = false; frameOpen = false }
                    }
                    handled
                }
                jfxPanel.scene = javafx.scene.Scene(wv)
                holder.engine = e
                if (targetUrl.isNotEmpty()) e.load(targetUrl)
            }
        }
    }

    LaunchedEffect(url) { openOrFocus(url) }
    DisposableEffect(Unit) {
        onDispose {
            javafx.application.Platform.runLater {
                holder.engine?.load("about:blank")
                holder.engine = null
            }
            javax.swing.SwingUtilities.invokeLater {
                holder.frame?.dispose()
                holder.frame = null
            }
        }
    }

    val p = c
    Box(modifier.padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(if (frameOpen) "已在独立窗口打开登录页" else "登录窗口已关闭", 16, p.k, 700)
            T("在弹出的窗口中扫码或登录，完成后会自动关闭并回到应用（也可直接切换上方「QQ 扫码 / 微信扫码」）", 13, p.s, lines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineBtn(if (frameOpen) "置顶登录窗口" else "重新打开登录窗口", height = 40.dp, size = 13) { openOrFocus(url) }
                OutlineBtn("刷新网页", height = 40.dp, size = 13) { javafx.application.Platform.runLater { holder.engine?.reload() } }
            }
        }
    }
}

private fun configureEngine(
    e: javafx.scene.web.WebEngine,
    holder: FxHolder,
    stop: Regex?,
    onInfo: (String) -> Unit,
    onIntercept: (String) -> Boolean,
) {
    e.userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    e.createPopupHandler = javafx.util.Callback { e }
    e.locationProperty().addListener { _, _, u ->
        if (u.isNullOrEmpty()) return@addListener
        if (stop?.containsMatchIn(u) == true) { holder.stopped = true; e.loadWorker.cancel() }
        java.awt.EventQueue.invokeLater { onInfo(u.take(120)); onIntercept(u) }
    }
    e.loadWorker.stateProperty().addListener { _, _, st ->
        if (st == javafx.concurrent.Worker.State.SUCCEEDED) java.awt.EventQueue.invokeLater { onInfo("已打开：" + (e.title ?: e.location.take(80))) }
        else if (st == javafx.concurrent.Worker.State.FAILED) java.awt.EventQueue.invokeLater { onInfo("加载失败：" + (e.loadWorker.exception?.message ?: "请检查网络或切换扫码登录")) }
    }
    e.loadWorker.exceptionProperty().addListener { _, _, ex ->
        if (ex != null && !holder.stopped) java.awt.EventQueue.invokeLater { onInfo("加载出错：${ex.message}") }
    }
}

private class FxHolder {
    var frame: javax.swing.JFrame? = null
    var engine: javafx.scene.web.WebEngine? = null
    var pending: String? = null
    var lastUrl: String? = null
    var stopped = false
}
