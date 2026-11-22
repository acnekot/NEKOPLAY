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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import cc.catsuki.nekoplay.core.*
import cc.catsuki.nekoplay.data.*
import cc.catsuki.nekoplay.player.*
import cc.catsuki.nekoplay.ui.*
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

/** 剪贴板：资源管理器里「复制」的文件，或者复制的路径文字 */
private fun clipFile(): File? = runCatching {
    val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
    if (cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.javaFileListFlavor))
        (cb.getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().firstOrNull()
    else (cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String)?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }?.let { File(it) }
}.getOrNull()?.takeIf { it.exists() }

private fun copyText(s: String) = runCatching {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(s), null)
}

/** 自己加的快捷位置（左栏「收藏」） */
private object Pins {
    var list by mutableStateOf(load())
    private fun load() = Plat.prefs("ui").getString("pickPins", null)?.split("\n")?.filter { it.isNotBlank() && File(it).isDirectory } ?: emptyList()
    private fun save() = Plat.prefs("ui").edit().putString("pickPins", list.joinToString("\n")).apply()
    fun add(f: File) { if (f.absolutePath !in list) { list = list + f.absolutePath; save() } }
    fun remove(p: String) { list = list - p; save() }
}

/**
 * 应用内的图片选择器：左边常用位置，上面路径和返回上级，中间缩略图网格（文件夹在前），右边大图预览。
 * 单击选中、双击直接用；只显示图片和文件夹。
 */
/** 选文件夹：用同一个选择器的「文件夹模式」，Plat.pickFolder 会把请求放到这里，主窗口里的 FolderPickHost 负责显示 */
object FolderPick { var req by mutableStateOf<Pair<String?, (String?) -> Unit>?>(null) }

@Composable fun FolderPickHost() {
    val r = FolderPick.req ?: return
    ImagePickerDialog(onDismiss = { FolderPick.req = null; r.second(null) }, folderMode = true, start = r.first?.let { File(it) }) { f -> FolderPick.req = null; r.second(f.absolutePath) }
}

@Composable fun ImagePickerDialog(onDismiss: () -> Unit, folderMode: Boolean = false, start: File? = null, onPick: (File) -> Unit) {
    val p = c
    val ui = Plat.prefs("ui")
    var dir by remember { mutableStateOf(start?.takeIf { it.isDirectory } ?: ui.getString("pickDir", null)?.let { File(it) }?.takeIf { it.isDirectory } ?: places().first().second) }
    var sel by remember { mutableStateOf<File?>(null) }
    val entries by produceState<List<File>>(emptyList(), dir) {
        value = withContext(Dispatchers.IO) {
            (dir.listFiles() ?: emptyArray()).filter { !it.isHidden && (it.isDirectory || (!folderMode && it.extension.lowercase() in IMG_EXT)) }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }
    fun open(f: File) { if (f.isDirectory) { dir = f; sel = null } else { ui.edit().putString("pickDir", f.parent).apply(); onPick(f) } }
    // 文件夹模式：选当前打开的文件夹
    fun useDir() = onPick(dir)
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(1600); toast = null } }
    /** 跳到一个路径：文件夹就打开，图片就跳到它所在的文件夹并选中 */
    fun goTo(f: File?) {
        when {
            f == null -> toast = "剪贴板里没有文件或路径"
            f.isDirectory -> { dir = f; sel = null }
            folderMode -> { f.parentFile?.let { dir = it } }
            f.isFile && f.extension.lowercase() in IMG_EXT -> { f.parentFile?.let { dir = it }; sel = f }
            else -> toast = "不是图片：${f.name}"
        }
    }
    fun copy() { val t = sel?.absolutePath ?: dir.absolutePath; copyText(t); toast = "已复制路径" }
    var pathText by remember(dir) { mutableStateOf(dir.absolutePath) }
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures { onDismiss() } }, contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize(.86f).widthIn(max = 1100.dp)) {
            Column(Modifier.fillMaxSize().background(p.sheet).border(1.dp, p.k).pointerInput(Unit) { detectTapGestures { } }
                // Ctrl+C 复制选中图片（没选就是当前文件夹）的路径；Ctrl+V 粘贴资源管理器里复制的文件或路径
                .onPreviewKeyEvent { e ->
                    val ctrl = e.isCtrlPressed || e.isMetaPressed
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || !ctrl) false
                    else when (e.key) {
                        androidx.compose.ui.input.key.Key.C -> { copy(); true }
                        androidx.compose.ui.input.key.Key.V -> { goTo(clipFile()); true }
                        else -> false
                    }
                }) {
                // 顶部：标题 + 路径 + 上一级
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(if (folderMode) "选择文件夹" else "选择图片", 17, w = 800)
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.size(36.dp).press { dir.parentFile?.let { dir = it; sel = null } }, contentAlignment = Alignment.Center) { NIcon(NIcons.back, 16.dp) }
                    // 路径可以直接改、粘贴，回车跳过去
                    androidx.compose.foundation.text.BasicTextField(pathText, { pathText = it },
                        Modifier.weight(1f).onPreviewKeyEvent { e ->
                            if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Enter) { goTo(File(pathText.trim().trim('"')).takeIf { it.exists() }); true } else false
                        }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = p.s, fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp), fontFamily = Mono),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(p.p))
                    LinkBtn("复制路径") { copy() }
                    LinkBtn("粘贴") { goTo(clipFile()) }
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
                        // 收藏的快捷位置：可以把当前文件夹加进来，× 删除
                        item { Box(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp)) { Tag("PINNED") } }
                        items(Pins.list) { path ->
                            val f = File(path); val on = dir.absolutePath == path
                            Row(Modifier.fillMaxWidth().height(40.dp).press { dir = f; sel = null }.background(if (on) p.p else Color.Transparent).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                T(f.name.ifEmpty { path }, 14, if (on) Color.White else p.k, if (on) 700 else 400, modifier = Modifier.weight(1f))
                                Box(Modifier.size(36.dp).press { Pins.remove(path) }, contentAlignment = Alignment.Center) { NIcon(NIcons.close, 10.dp, if (on) Color.White else p.s) }
                            }
                        }
                        item {
                            Box(Modifier.fillMaxWidth().height(40.dp).press { Pins.add(dir); toast = "已加入收藏" }.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                                T("＋ 收藏当前文件夹", 13, p.p, 700)
                            }
                        }
                    }
                    // 中：缩略图
                    LazyVerticalGrid(GridCells.Adaptive(132.dp), Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(entries, key = { it.absolutePath }) { f -> PickCell(f, sel == f, { sel = f; if (f.isDirectory) open(f) }) { open(f) } }
                    }
                    // 右：预览 + 按钮
                    if (folderMode) Column(Modifier.width(280.dp).fillMaxHeight().padding(14.dp)) {
                        NIcon(NIcons.folder, 40.dp, p.s); Spacer(Modifier.height(10.dp))
                        T(dir.name.ifEmpty { dir.absolutePath }, 16, w = 700, lines = 2)
                        T(dir.absolutePath, 12, p.s, mono = true, lines = 4)
                        Spacer(Modifier.height(8.dp))
                        T("双击进入文件夹，打开想要的那一个后点下面的按钮", 12, p.s, lines = 3, lh = 1.4f)
                        Spacer(Modifier.weight(1f))
                        Row {
                            OutlineBtn("取消", pad = 14.dp) { onDismiss() }
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f).height(48.dp).press(.96f) { useDir() }.background(p.p), contentAlignment = Alignment.Center) { T("选这个文件夹", 15, Color.White, 700) }
                        }
                    } else Column(Modifier.width(280.dp).fillMaxHeight().padding(14.dp)) {
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
            toast?.let { m -> Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).background(p.k).padding(horizontal = 14.dp, vertical = 9.dp)) { T(m, 13, p.tagFg) } }
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
