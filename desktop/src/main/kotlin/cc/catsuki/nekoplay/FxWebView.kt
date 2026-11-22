package cc.catsuki.nekoplay

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean

/**
 * macOS / Linux 的内嵌网页：
 * - macOS：Compose 使用 Metal 渲染，SwingPanel 内嵌 JFXPanel 会与 Metal/AppKit 冲突导致卡死，
 *   因此 macOS 改用独立原生 JavaFX Stage 窗口承载 WebView（非阻塞启动、登录完成或返回时自动关闭）。
 * - Linux：仍用 JFXPanel 内嵌在 SwingPanel 中。
 * - Windows：使用系统 WebView2（见 DesktopWebView）。
 */
object FxWebView {
    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    private val fxStarted = AtomicBoolean(false)

    val available: Boolean by lazy {
        !System.getProperty("os.name").orEmpty().startsWith("Windows") &&
            runCatching { Class.forName("javafx.scene.web.WebView"); true }.getOrDefault(false)
    }

    /** 在后台线程安全启动 JavaFX Toolkit，避免阻塞 AWT-EDT 导致 macOS 死锁 */
    fun runOnFx(block: () -> Unit) {
        if (fxStarted.get()) {
            javafx.application.Platform.runLater(block)
            return
        }
        Thread {
            if (fxStarted.compareAndSet(false, true)) {
                runCatching {
                    javafx.application.Platform.startup {
                        javafx.application.Platform.setImplicitExit(false)
                        block()
                    }
                }.onFailure {
                    // Toolkit 已被初始化过，直接 runLater
                    javafx.application.Platform.runLater {
                        javafx.application.Platform.setImplicitExit(false)
                        block()
                    }
                }
            } else {
                javafx.application.Platform.runLater(block)
            }
        }.apply { isDaemon = true; name = "fx-init" }.start()
    }

    val useStage: Boolean get() = isMac
}

/** 网页里的地址每变一次就报给 intercept；匹配 stopOn 的页面先取消加载，免得页面自己用掉登录 code */
@Composable fun FxWebPage(url: String, modifier: Modifier, desktopUa: Boolean, stopOn: String?, onInfo: (String) -> Unit, intercept: (String) -> Boolean) {
    if (FxWebView.useStage) {
        FxStageWebPage(url, modifier, stopOn, onInfo, intercept)
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

/** macOS 专用：用独立原生 JavaFX Stage 打开登录网页，彻底避免 SwingPanel + Metal 卡死 */
@Composable private fun FxStageWebPage(url: String, modifier: Modifier, stopOn: String?, onInfo: (String) -> Unit, intercept: (String) -> Boolean) {
    val cb by rememberUpdatedState(intercept)
    val info by rememberUpdatedState(onInfo)
    val stop = remember(stopOn) { stopOn?.let { runCatching { Regex(it) }.getOrNull() } }
    val holder = remember { FxHolder() }
    var stageOpen by remember { mutableStateOf(false) }

    fun openOrFocus(targetUrl: String) {
        FxWebView.runOnFx {
            val st = holder.stage
            if (st != null) {
                if (!st.isShowing) st.show()
                st.toFront()
                if (targetUrl.isNotEmpty() && holder.lastUrl != targetUrl) {
                    holder.lastUrl = targetUrl
                    holder.engine?.load(targetUrl)
                }
                java.awt.EventQueue.invokeLater { stageOpen = true }
                return@runOnFx
            }
            val wv = javafx.scene.web.WebView()
            val e = wv.engine
            configureEngine(e, holder, stop, { info(it) }) { u ->
                val handled = cb(u)
                if (handled && stop?.containsMatchIn(u) == true) {
                    javafx.application.Platform.runLater { holder.stage?.hide() }
                }
                handled
            }
            val stage = javafx.stage.Stage().apply {
                title = "NEKOPLAY 登录"
                scene = javafx.scene.Scene(wv, 520.0, 620.0)
                setOnCloseRequest { java.awt.EventQueue.invokeLater { stageOpen = false } }
            }
            holder.stage = stage
            holder.engine = e
            holder.lastUrl = targetUrl
            stage.show()
            stage.toFront()
            if (targetUrl.isNotEmpty()) e.load(targetUrl)
            java.awt.EventQueue.invokeLater { stageOpen = true }
        }
    }

    LaunchedEffect(url) { openOrFocus(url) }
    DisposableEffect(Unit) {
        onDispose {
            FxWebView.runOnFx {
                holder.engine?.load("about:blank")
                holder.stage?.close()
                holder.stage = null
                holder.engine = null
            }
        }
    }

    val p = c
    Box(modifier.padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(if (stageOpen) "已在独立窗口打开登录页" else "登录窗口已关闭", 16, p.k, 700)
            T("在弹出的窗口中扫码或点击登录，完成后会自动关闭并回到应用（也可直接切换上方「QQ 扫码 / 微信扫码」）", 13, p.s, lines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineBtn(if (stageOpen) "置顶登录窗口" else "重新打开登录窗口", height = 40.dp, size = 13) { openOrFocus(url) }
                OutlineBtn("刷新网页", height = 40.dp, size = 13) { FxWebView.runOnFx { holder.engine?.reload() } }
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
    }
    e.loadWorker.exceptionProperty().addListener { _, _, ex ->
        if (ex != null && !holder.stopped) java.awt.EventQueue.invokeLater { onInfo("加载出错：${ex.message}") }
    }
}

private class FxHolder {
    var stage: javafx.stage.Stage? = null
    var engine: javafx.scene.web.WebEngine? = null
    var pending: String? = null
    var lastUrl: String? = null
    var stopped = false
}
