package cc.catsuki.nekoplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 详细调整：动效、歌词、进度条、背景下的栏位 */
@Composable fun TuningPage() {
    val p = c
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item { Column { Tag("DETAILS"); Spacer(Modifier.height(4.dp)); T("详细调整", Fs.HERO, w = 800, lh = 1.3f); Spacer(Modifier.height(12.dp)); Rule() } }
        item { Sec("MOTION · 动效") }
        item { Line("动效形式", "弹簧有一点物理回弹，缓动先快后慢，线性匀速，关闭则瞬间切换") { ChipRow(AnimStyle.labels, AnimStyle.style) { AnimStyle.set(it) } } }
        item { Line("动效速度", "页面切换、播放页、队列、弹窗的动画快慢") { ChipRow(Anim.labels, Anim.level) { Anim.level = it; Plat.prefs("ui").edit().putInt("anim", it).apply() } } }
        item { Sec("LYRICS · 歌词") }
        item {
            Line("歌词字号", "歌词页、播放页右栏") {
                T("今も頭上を回って輝く", LyricPref.size.toFloat(), p.k, 800)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { NSlider(LyricPref.size.toFloat(), 16f..40f, onDone = { LyricPref.set(LyricPref.size) }) { LyricPref.size = it.toInt() } }
                    Spacer(Modifier.width(10.dp)); T("${LyricPref.size}", 14, p.p, 700, mono = true)
                }
            }
        }
        item { SwitchRow("逐字高亮", "有逐字歌词的歌，当前句一个字一个字亮起来", UiPref.karaoke) { UiPref.karaoke = it; UiPref.save() } }
        item { SwitchRow("远处歌词模糊", "离当前句较远的行轻微模糊（更有层次，略耗性能）", UiPref.lyricBlur) { UiPref.lyricBlur = it; UiPref.save() } }
        item { Sec("PLAYBACK · 播放") }
        item { SwitchRow("进度条显示缓存", "进度条上用深一点的颜色标出已经下载好的部分", UiPref.showBuffer) { UiPref.showBuffer = it; UiPref.save() } }
        item { Sec("PERFORMANCE · 性能") }
        if (Plat.isDesktop) item { SwitchRow("后台留存", "关闭窗口时缩到系统托盘继续播放；托盘图标可以播放 / 暂停、切歌、退出", UiPref.tray) { UiPref.tray = it; UiPref.save() } }
        item { SwitchRow("省内存模式", "图片缓存变小、头像不播放动画、逐字高亮降到约 20 帧${if (Plat.isDesktop) "、最小化时释放内存" else ""}；图片缓存大小重启后生效", UiPref.lowMem) { UiPref.lowMem = it; UiPref.save() } }
        if (Plat.isDesktop) {
            item { Sec("KEYBOARD · 快捷键") }
            items(Keys.HELP.size) { i -> val (k, d) = Keys.HELP[i]
                Row(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(150.dp)) { Tag(k) }; T(d, 14, lines = 2)
                }
            }
        }
        item { Sec("BACKGROUND · 背景") }
        item {
            Line("栏位不透明度", "设置了自定义背景时，侧边栏、顶栏、播放条的不透明度") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { NSlider(UiPref.barAlpha, 0f..1f, onDone = { UiPref.save() }) { UiPref.barAlpha = it } }
                    Spacer(Modifier.width(10.dp)); T("%.0f%%".format(UiPref.barAlpha * 100), 14, p.p, 700, mono = true)
                }
            }
        }
    }
}

@Composable private fun Sec(t: String) = Box(Modifier.padding(top = 22.dp, bottom = 6.dp)) { TagRow(t, 0.dp) }

@Composable private fun Line(title: String, sub: String, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().bottomLine().padding(vertical = 12.dp)) {
        T(title, 16); T(sub, 13, c.s, lines = 2); Spacer(Modifier.height(10.dp)); content()
    }
