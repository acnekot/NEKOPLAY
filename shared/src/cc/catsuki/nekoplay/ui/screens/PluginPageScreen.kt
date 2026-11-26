package cc.catsuki.nekoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plugin API v2 声明式页面宿主：
 * 任意 @type page / feature 插件只要导出 render(ctx) 和 onAction(action, payload)，
 * 即可在 Android / macOS / Windows / Linux / iOS 五端原生 60/120fps 渲染并随插件热更新，无需重编本体。
 */
@Composable fun PluginPageScreen(pid: String) {
    val nav = LocalNav.current
    val p = c
    val scope = rememberCoroutineScope()
    val st by Engine.state.collectAsState()
    val pls by Store.playlists.collectAsState()
    val rec by Store.recents.collectAsState()
    var seq by remember(pid) { mutableIntStateOf(0) }
    var tree by remember(pid) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(pid) { mutableStateOf(true) }
    var err by remember(pid) { mutableStateOf<String?>(null) }

    fun buildCtx(): JSONObject = JSONObject().apply {
        put("hostVersion", APP_VERSION)
        put("platform", Plat.impl.name)
        put("lang", I18n.active)
        put("wide", Screen.wide)
        put("landscape", Screen.landscape)
        put("playing", st.playing)
        st.current?.let { t ->
            put("track", JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album).put("cover", t.cover ?: ""))
        }
        put("playlistCount", pls.size)
        put("recentCount", rec.size)
    }

    fun applyHostResult(r: JSONObject?) {
        if (r == null) return
        Plugins.bump()
        r.optString("toast").takeIf { it.isNotEmpty() }?.let { nav.toast = it }
        when (val op = r.optString("open")) {
            "coverflow" -> nav.push(Page.CoverFlow)
            "settings" -> nav.push(Page.Settings)
            else -> if (op.startsWith("plugin:")) nav.push(Page.PluginPage(op.removePrefix("plugin:")))
        }
        if (r.optBoolean("refresh", true)) seq++
    }

    LaunchedEffect(pid, seq, Plugins.rev) {
        loading = tree == null
        runCatching { Plugins.renderPage(pid, buildCtx()) }
            .onSuccess { tree = it; err = if (it == null) "插件未实现 render(ctx) 接口" else null }
            .onFailure { err = it.message ?: it.toString() }
        loading = false
    }

    val dispatch: (String, JSONObject?) -> Unit = { act, payload ->
        scope.launch {
            runCatching { Plugins.dispatchPageAction(pid, act, payload) }
                .onSuccess { applyHostResult(it) }
                .onFailure { nav.toast = "执行失败：${it.message}" }
        }
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T("LOADING PLUGIN UI…", 12, p.s, mono = true) }
        err != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { T(err ?: "", 13, p.bad, lines = 4) }
        tree != null -> LazyColumn(Modifier.fillMaxSize().widthIn(max = 900.dp), contentPadding = PaddingValues(18.dp)) {
            item { PluginNodeView(tree!!, dispatch) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PluginNodeView(node: JSONObject, dispatch: (String, JSONObject?) -> Unit) {
    val p = c
    val type = node.optString("type", "column").lowercase()
    val children = node.optJSONArray("children") ?: JSONArray()
    val pad = node.optInt("pad", 0).coerceIn(0, 48).dp
    val gap = node.optInt("gap", 8).coerceIn(0, 32).dp
    val act = node.optString("action").takeIf { it.isNotEmpty() }
    val payload = node.optJSONObject("payload")
    val clickMod = if (act != null) Modifier.press { dispatch(act, payload) } else Modifier
    when (type) {
        "column" -> Column(Modifier.fillMaxWidth().then(clickMod).padding(pad), verticalArrangement = Arrangement.spacedBy(gap)) {
            for (i in 0 until children.length()) children.optJSONObject(i)?.let { PluginNodeView(it, dispatch) }
        }
        "flow" -> FlowRow(
            Modifier.fillMaxWidth().then(clickMod).padding(pad),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            for (i in 0 until children.length()) children.optJSONObject(i)?.let { PluginNodeView(it, dispatch) }
        }
        "row" -> {
            val hasGrow = (0 until children.length()).any { children.optJSONObject(it)?.optBoolean("grow") == true }
            val wrap = if (node.has("wrap")) node.optBoolean("wrap") else !hasGrow
            if (wrap) {
                FlowRow(
                    Modifier.fillMaxWidth().then(clickMod).padding(pad),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    for (i in 0 until children.length()) children.optJSONObject(i)?.let { PluginNodeView(it, dispatch) }
                }
            } else {
                Row(Modifier.fillMaxWidth().then(clickMod).padding(pad), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0 until children.length()) children.optJSONObject(i)?.let { Box(if (it.optBoolean("grow")) Modifier.weight(1f) else Modifier) { PluginNodeView(it, dispatch) } }
                }
            }
        }
        "card" -> Column(Modifier.fillMaxWidth().cascadeEnter().background(p.sheet.copy(alpha = 0.85f)).nbf(1.dp, p.l2).then(clickMod).padding(if (pad.value > 0) pad else 14.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            for (i in 0 until children.length()) children.optJSONObject(i)?.let { PluginNodeView(it, dispatch) }
        }
        "tag" -> Tag(node.optString("text"))
        "text" -> T(
            node.optString("text"),
            node.optInt("size", 14).coerceIn(10, 36),
            when (node.optString("tone")) { "primary" -> p.p; "sub" -> p.s; "bad" -> p.bad; else -> p.k },
            node.optInt("weight", 500).coerceIn(400, 900),
            lines = node.optInt("lines", 10).coerceIn(1, 30),
            mono = node.optBoolean("mono", false),
            lh = 1.45f,
        )
        "button" -> {
            val primary = node.optBoolean("primary", false)
            Box(Modifier.press { act?.let { dispatch(it, payload) } }
                .then(if (primary) Modifier.background(p.p) else Modifier.nbf(1.dp, p.k))
                .padding(horizontal = 14.dp, vertical = 8.dp)) {
                T(node.optString("text", "按钮"), 12, if (primary) Color.White else p.k, 700, lines = 2, lh = 1.3f)
            }
        }
        "cover" -> {
            val u = node.optString("url").takeIf { it.isNotEmpty() }
            val sz = node.optInt("size", 64).coerceIn(28, 280).dp
            val title = node.optString("title")
            val rk = node.optString("revealKey").takeIf { it.isNotEmpty() }
            if (rk != null) CoverRevealSwitch(u, sz, title, revealKey = rk, forceCfg = if (node.optBoolean("geomPreview")) MotionEffects.geom ?: GeomRevealConfig() else null)
            else Cover(u, sz, title, border = true)
        }
        "divider" -> Rule()
        "spacer" -> Spacer(Modifier.height(node.optInt("size", 12).coerceIn(2, 64).dp))
    }
}
