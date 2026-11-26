package cc.catsuki.nekoplay.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.catsuki.nekoplay.ActionRow
import cc.catsuki.nekoplay.Engine
import cc.catsuki.nekoplay.Fs
import cc.catsuki.nekoplay.LocalMusic
import cc.catsuki.nekoplay.LocalNav
import cc.catsuki.nekoplay.NIcons
import cc.catsuki.nekoplay.OutlineBtn
import cc.catsuki.nekoplay.Plat
import cc.catsuki.nekoplay.PrimaryBar
import cc.catsuki.nekoplay.Rule
import cc.catsuki.nekoplay.T
import cc.catsuki.nekoplay.Tag
import cc.catsuki.nekoplay.TrackRow
import cc.catsuki.nekoplay.c
import cc.catsuki.nekoplay.tr
import cc.catsuki.nekoplay.trKnown
import cc.catsuki.nekoplay.two
import kotlinx.coroutines.launch

@Composable fun LocalMusicPage() {
    val nav = LocalNav.current
    val p = c
    val list by LocalMusic.list.collectAsState()
    val st by Engine.state.collectAsState()
    val scope = rememberCoroutineScope()
    val (granted, ask) = Plat.impl.audioPermission()
    LaunchedEffect(granted) { if (granted && list.isEmpty() && !LocalMusic.scanning) LocalMusic.scan() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item {
            Column {
                Tag("LOCAL"); Spacer(Modifier.height(4.dp))
                T(tr("本地音乐"), Fs.HERO, w = 800, lh = 1.3f)
                T(tr(if (Plat.isDesktop) "扫描「音乐」文件夹里的歌（mp3 / flac / m4a / ogg / wav …）" else "读取手机媒体库里的歌，同名 .lrc 会当作歌词"), 13, p.s, lines = 2)
                T(trKnown("${two(list.size)} 首"), 12, p.s, mono = true)
                Spacer(Modifier.height(14.dp))
                ActionRow {
                    if (!granted) PrimaryBar("允许读取音乐", NIcons.chevronRight, Modifier.weight(1f)) { ask() }
                    else {
                        if (list.isNotEmpty()) { PrimaryBar("播放", NIcons.play, Modifier.weight(1f)) { Engine.playList(list, 0) }; Spacer(Modifier.width(10.dp)) }
                        OutlineBtn(if (LocalMusic.scanning) "扫描中…" else "重新扫描", pad = 14.dp) { if (!LocalMusic.scanning) scope.launch { runCatching { LocalMusic.scan() }.onFailure { nav.toast = "扫描失败：${it.message}" }; nav.toast = "找到 ${LocalMusic.list.value.size} 首" } }
                    }
                }
                Spacer(Modifier.height(12.dp)); Rule()
                if (granted && list.isEmpty() && !LocalMusic.scanning) T(tr("没有找到本地音乐"), 13, p.s, modifier = Modifier.padding(vertical = 28.dp))
            }
        }
        itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
            TrackRow(t, st.current?.id == t.id, t.src?.substringAfterLast('.')?.uppercase()?.take(4)) { Engine.playList(list, i) }
        }
    }
}
