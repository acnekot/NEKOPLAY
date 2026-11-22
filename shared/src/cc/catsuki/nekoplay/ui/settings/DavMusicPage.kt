package cc.catsuki.nekoplay.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsuki.nekoplay.ActionRow
import cc.catsuki.nekoplay.DavMusic
import cc.catsuki.nekoplay.Engine
import cc.catsuki.nekoplay.Fs
import cc.catsuki.nekoplay.IconCover
import cc.catsuki.nekoplay.LinkBtn
import cc.catsuki.nekoplay.ListRow
import cc.catsuki.nekoplay.LocalNav
import cc.catsuki.nekoplay.Mono
import cc.catsuki.nekoplay.NIcons
import cc.catsuki.nekoplay.PrimaryBar
import cc.catsuki.nekoplay.Rule
import cc.catsuki.nekoplay.T
import cc.catsuki.nekoplay.Tag
import cc.catsuki.nekoplay.TrackRow
import cc.catsuki.nekoplay.c
import cc.catsuki.nekoplay.nbf

/** 曲库 → WebDAV 音乐：没配置时先填地址；配好后逐级浏览文件夹 */
@Composable fun DavMusicPage() {
    val nav = LocalNav.current
    val p = c
    val st by Engine.state.collectAsState()
    var editing by remember { mutableStateOf(!DavMusic.ready) }
    var path by remember { mutableStateOf(DavMusic.root) }
    var list by remember { mutableStateOf<List<DavMusic.Entry>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path, editing) {
        if (editing) return@LaunchedEffect
        list = null; err = null
        try { list = DavMusic.list(path) } catch (e: Exception) { err = e.message; list = emptyList() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("WEBDAV"); Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    T("WebDAV 音乐", Fs.HERO, w = 800, lh = 1.3f, modifier = Modifier.weight(1f))
                    if (!editing) LinkBtn("设置", 14) { editing = true }
                }
                T("直接播放网盘里的歌，文件名「歌手 - 歌名」会自动拆开，同名 .lrc 当作歌词", 13, p.s, lines = 2, lh = 1.4f)
                Spacer(Modifier.height(12.dp)); Rule()
            }
        }
        if (editing) item {
            var u by remember { mutableStateOf(DavMusic.url) }
            var n by remember { mutableStateOf(DavMusic.user) }
            var pw by remember { mutableStateOf(DavMusic.pass) }
            @Composable fun Field(label: String, v: String, hint: String, secret: Boolean = false, set: (String) -> Unit) {
                Spacer(Modifier.height(14.dp)); T(label, 13, p.s); Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(44.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (v.isEmpty()) T(hint, 13, p.s, mono = true)
                    BasicTextField(v, set, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = Fs.SUB.sp, fontFamily = Mono), cursorBrush = SolidColor(p.p),
                        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
                }
            }
            Column {
                Field("地址（音乐所在的文件夹）", u, "https://pan.example.com/dav/音乐") { u = it }
                Field("用户名", n, "可以留空") { n = it }
                Field("密码", pw, "可以留空", secret = true) { pw = it }
                Spacer(Modifier.height(16.dp))
                PrimaryBar("保存并打开", NIcons.chevronRight, Modifier.fillMaxWidth()) {
                    if (!u.trim().startsWith("http")) { nav.toast = "地址要以 http:// 或 https:// 开头"; return@PrimaryBar }
                    DavMusic.save(u, n, pw); path = DavMusic.root; editing = false
                }
            }
        }
        if (!editing) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(DavMusic.dec(path), 12, p.s, mono = true, lines = 2, modifier = Modifier.weight(1f))
                    if (path != DavMusic.root) LinkBtn("上一级", 13) { path = path.trimEnd('/').substringBeforeLast('/') + "/" }
                }
            }
            val l = list
            when {
                l == null -> item { T("LOADING", 12, p.s, mono = true) }
                err != null -> item { T("打不开：$err", 13, p.s, lines = 3) }
                l.isEmpty() -> item { T("这个文件夹里没有音乐", 13, p.s) }
                else -> {
                    val songs = l.filter { !it.dir }.map { DavMusic.trackOf(it) }
                    if (songs.isNotEmpty()) item {
                        ActionRow { PrimaryBar("播放这个文件夹", NIcons.play, Modifier.weight(1f)) { Engine.playList(songs, 0) } }
                        Spacer(Modifier.height(8.dp))
                    }
                    items(l.filter { it.dir }, key = { "d" + it.path }) { e -> ListRow({ IconCover(NIcons.disc, 56.dp) }, e.name, "文件夹") { path = e.path } }
                    items(songs.indices.toList(), key = { songs[it].id }) { i ->
                        val t = songs[i]
                        TrackRow(t, st.current?.id == t.id, t.src?.substringAfterLast('.')?.uppercase()?.take(4)) { Engine.playList(songs, i) }
                    }
                }
            }
        }
    }
}
