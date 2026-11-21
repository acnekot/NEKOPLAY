package cc.catsuki.nekoplay

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.awt.Canvas
import java.awt.Graphics
import java.io.File

/**
 * 电脑版内嵌网页：用系统自带的 WebView2（Windows 10 / 11 都有），嵌进一个 AWT Canvas 里。
 * 原生库是 webview/webview 的 webview.dll（MIT，见 resources/webview/LICENSE.md），只带了 Windows x64 的。
 * 网页里每打开一个页面，注入的脚本就把地址报回来；地址匹配 stopOn 时先停止加载，免得页面自己用掉登录 code。
 */
object DesktopWebView {
    @Suppress("FunctionName")
    private interface WV : Library {
        fun webview_create(debug: Int, window: PointerByReference?): Pointer?
        fun webview_destroy(w: Pointer)
        fun webview_run(w: Pointer)
        fun webview_terminate(w: Pointer)
        fun webview_navigate(w: Pointer, url: String)
        fun webview_init(w: Pointer, js: String)
        fun webview_set_size(w: Pointer, width: Int, height: Int, hints: Int)
        fun webview_bind(w: Pointer, name: String, fn: BindFn, arg: Pointer?)
        fun webview_return(w: Pointer, seq: String, status: Int, result: String)
        fun webview_dispatch(w: Pointer, fn: DispatchFn, arg: Pointer?)
    }
    interface BindFn : Callback { fun invoke(seq: String, req: String, arg: Pointer?) }
    interface DispatchFn : Callback { fun invoke(w: Pointer, arg: Pointer?) }

    private val isWinX64 = System.getProperty("os.name").orEmpty().startsWith("Windows") && System.getProperty("os.arch").orEmpty().let { it == "amd64" || it == "x86_64" }

    /** 解出 dll 并加载；不支持的系统或加载失败返回 null */
    private val lib: WV? by lazy {
        if (!isWinX64) return@lazy null
        runCatching {
            val dir = File(System.getProperty("java.io.tmpdir"), "nekoplay-webview").apply { mkdirs() }
            val f = File(dir, "webview-${APP_VERSION}.dll")
            if (!f.exists()) DesktopWebView::class.java.getResourceAsStream("/webview/webview-win-x64.dll")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            Native.load(f.absolutePath, WV::class.java)
        }.onFailure { Debug.log("webview", 0, 0, "load", it.toString()) }.getOrNull()
    }
    val available get() = lib != null

    /** 一个嵌入的网页：Canvas 第一次画出来时在自己的线程里建 WebView2 并跑消息循环 */
    class View(private val stopOn: String?, private val onUrl: (String) -> Unit) : Canvas() {
        @Volatile private var w: Pointer? = null
        private var started = false
        private var pending: String? = null
        private var lastW = 0; private var lastH = 0
        // JNA 回调要一直有强引用，否则被回收后原生层一调就崩
        private val nav = object : BindFn {
            override fun invoke(seq: String, req: String, arg: Pointer?) {
                val u = runCatching { org.json.JSONArray(req).optString(0) }.getOrDefault("")
                w?.let { lib?.webview_return(it, seq, 0, "null") }
                if (u.isNotEmpty()) onUrl(u)
            }
        }
        private val jobs = java.util.concurrent.ConcurrentLinkedQueue<(Pointer) -> Unit>()
        private val runJobs = object : DispatchFn { override fun invoke(w: Pointer, arg: Pointer?) { while (true) { val j = jobs.poll() ?: break; runCatching { j(w) } } } }
        private fun onUi(block: (Pointer) -> Unit) { val v = w ?: return; jobs += block; lib?.webview_dispatch(v, runJobs, null) }

        fun load(url: String) { if (w == null) pending = url else onUi { lib!!.webview_navigate(it, url) } }

        override fun paint(g: Graphics) {
            if (width != lastW || height != lastH) { lastW = width; lastH = height; onUi { lib!!.webview_set_size(it, width, height, 3) } }
            if (started) return
            started = true
            val hwnd = Native.getComponentPointer(this) ?: return
            Thread({
                val l = lib ?: return@Thread
                val v = l.webview_create(0, PointerByReference(hwnd)) ?: return@Thread
                w = v
                l.webview_set_size(v, width, height, 3)
                l.webview_bind(v, "__nkNav", nav, null)
                val stop = stopOn?.let { org.json.JSONObject.quote(it) } ?: "null"
                l.webview_init(v, "(function(){var s=$stop;var u=location.href;try{__nkNav(u)}catch(e){};if(s&&new RegExp(s).test(u)){try{window.stop()}catch(e){}}})();")
                l.webview_navigate(v, pending ?: "about:blank")
                l.webview_run(v)
                w = null
                l.webview_destroy(v)
            }, "NEKOPLAY WebView").apply { isDaemon = true }.start()
        }

        fun dispose() { onUi { lib!!.webview_terminate(it) } }
    }
}

/** DesktopPlat.WebPage 用：intercept 返回 true 的地址算处理掉了（登录完成），不再管 */
@Composable fun DesktopWebPage(url: String, modifier: Modifier, stopOn: String?, onInfo: (String) -> Unit, intercept: (String) -> Boolean) {
    val cb by rememberUpdatedState(intercept)
    val info by rememberUpdatedState(onInfo)
    val view = remember { DesktopWebView.View(stopOn) { u -> java.awt.EventQueue.invokeLater { info(u.take(120)); cb(u) } } }
    LaunchedEffect(url) { view.load(url) }
    DisposableEffect(Unit) { onDispose { view.dispose() } }
    SwingPanel(factory = { view }, modifier = modifier)
}
