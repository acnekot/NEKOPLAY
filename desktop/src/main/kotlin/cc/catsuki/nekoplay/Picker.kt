package cc.catsuki.nekoplay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val IMG_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/** 缩略图缓存（最多 300 张，最久没用的先丢） */
private val thumbs = object : LinkedHashMap<String, ImageBitmap>(64, .75f, true) {
    override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
}
private val thumbIO = Dispatchers.IO.limitedParallelism(4)

private fun thumb(f: File): ImageBitmap? = runCatching {
    val img = org.jetbrains.skia.Image.makeFromEncoded(f.readBytes())
    val m = maxOf(img.width, img.height); val s = 220f / m
    val w = (img.width * s).toInt().coerceAtLeast(1); val h = (img.height * s).toInt().coerceAtLeast(1)
    val surf = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
    surf.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(img.width.toFloat(), img.height.toFloat()), org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
        org.jetbrains.skia.SamplingMode.LINEAR, null, true)
    surf.makeImageSnapshot().toComposeImageBitmap()
}.getOrNull()

/** 常用位置：图片、桌面、下载、主目录，加上各个磁盘 */
private fun places(): List<Pair<String, File>> {
    val home = File(System.getProperty("user.home"))
    val l = ArrayList<Pair<String, File>>()
    listOf("图片" to listOf("Pictures", "图片", "圖片"), "桌面" to listOf("Desktop", "桌面"), "下载" to listOf("Downloads", "下载", "下載")).forEach { (n, ds) ->
        ds.map { File(home, it) }.firstOrNull { it.isDirectory }?.let { l += n to it }
    }
    l += "主目录" to home
    File.listRoots().forEach { l += (it.path.trimEnd('\\', '/').ifEmpty { "/" }) to it }
    return l
}

/**
 * 应用内的图片选择器：左边常用位置，上面路径和返回上级，中间缩略图网格（文件夹在前），右边大图预览。
 * 单击选中、双击直接用；只显示图片和文件夹。
 */
@Composable fun ImagePickerDialog(onDismiss: () -> Unit, onPick: (File) -> Unit) {
    val p = c
    val ui = Plat.prefs("ui")
    var dir by remember { mutableStateOf(ui.getString("pickDir", null)?.let { File(it) }?.takeIf { it.isDirectory } ?: places().first().second) }
    var sel by remember { mutableStateOf<File?>(null) }
    val entries by produceState<List<File>>(emptyList(), dir) {
        value = withContext(Dispatchers.IO) {
            (dir.listFiles() ?: emptyArray()).filter { !it.isHidden && (it.isDirectory || it.extension.lowercase() in IMG_EXT) }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }
    fun open(f: File) { if (f.isDirectory) { dir = f; sel = null } else { ui.edit().putString("pickDir", f.parent).apply(); onPick(f) } }
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures { onDismiss() } }, contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxSize(.86f).widthIn(max = 1100.dp).background(p.sheet).border(1.dp, p.k).pointerInput(Unit) { detectTapGestures { } }) {
                // 顶部：标题 + 路径 + 上一级
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("选择图片", 17, w = 800)
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.size(36.dp).press { dir.parentFile?.let { dir = it; sel = null } }, contentAlignment = Alignment.Center) { NIcon(NIcons.back, 16.dp) }
                    T(dir.absolutePath, 13, p.s, mono = true, modifier = Modifier.weight(1f))
                    Box(Modifier.size(40.dp).press { onDismiss() }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 13.dp) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.l2))
                Row(Modifier.weight(1f)) {
                    // 左：常用位置
                    LazyColumn(Modifier.width(170.dp).fillMaxHeight().background(p.k.copy(alpha = .04f)).padding(vertical = 8.dp)) {
                        items(places()) { (n, f) ->
                            val on = dir.absolutePath == f.absolutePath
                            Box(Modifier.fillMaxWidth().height(40.dp).press { dir = f; sel = null }.background(if (on) p.p else Color.Transparent).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                                T(n, 14, if (on) Color.White else p.k, if (on) 700 else 400)
                            }
                        }
                    }
                    // 中：缩略图
                    LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(entries, key = { it.absolutePath }) { f -> PickCell(f, sel == f, { sel = f; if (f.isDirectory) open(f) }) { open(f) } }
                    }
                    // 右：预览 + 按钮
                    Column(Modifier.width(280.dp).fillMaxHeight().padding(14.dp)) {
                        val s = sel
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(p.ph).border(1.dp, p.l2), contentAlignment = Alignment.Center) {
                            if (s != null && s.isFile) {
                                val big by produceState<ImageBitmap?>(null, s) { value = withContext(thumbIO) { thumbs[s.path] ?: thumb(s) } }
                                big?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            } else T("单击选中预览\n双击直接使用", 13, p.s, lines = 2, align = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        Spacer(Modifier.height(10.dp))
                        if (s != null && s.isFile) { T(s.name, 14, w = 700, lines = 2); T("%.1f MB".format(s.length() / 1048576.0), 12, p.s, mono = true) }
                        Spacer(Modifier.weight(1f))
                        Row {
                            OutlineBtn("取消", pad = 14.dp) { onDismiss() }
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f).height(48.dp).press(.96f) { if (s != null && s.isFile) open(s) }.background(if (s != null && s.isFile) p.p else p.l3), contentAlignment = Alignment.Center) {
                                T("使用这张", 15, Color.White, 700)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PickCell(f: File, selected: Boolean, onClick: () -> Unit, onDouble: () -> Unit) {
    val p = c
    Column(Modifier.pointerInput(f) { detectTapGestures(onTap = { onClick() }, onDoubleTap = { onDouble() }) }
        .background(if (selected) p.hl else Color.Transparent).border(if (selected) 2.dp else 0.dp, if (selected) p.p else Color.Transparent).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(p.ph), contentAlignment = Alignment.Center) {
            if (f.isDirectory) NIcon(NIcons.folder, 40.dp, p.s)
            else {
                val img by produceState(thumbs[f.path], f) { if (value == null) value = withContext(thumbIO) { thumb(f) }?.also { synchronized(thumbs) { thumbs[f.path] = it } } }
                img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
        Spacer(Modifier.height(4.dp))
        T(f.name, 12, p.k, if (f.isDirectory) 700 else 400)
    }
}
