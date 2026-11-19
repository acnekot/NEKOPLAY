package cc.catsuki.nekoplay

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自定义背景：一张图片铺满屏幕（裁切填充），可调模糊、透明度、缩放和位置。
 * 位置用「相对屏幕尺寸的比例」保存，换分辨率也不会跑偏。
 */
object Bg {
    /** 显示用的图（已经按模糊值处理好）；src 是原图 */
    var image by mutableStateOf<ImageBitmap?>(null)
    private var src: android.graphics.Bitmap? = null
    private var job: kotlinx.coroutines.Job? = null
    private val bgScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    var blur by mutableFloatStateOf(0f)      // 0..30 dp
    var alpha by mutableFloatStateOf(.6f)    // 0..1
    var scale by mutableFloatStateOf(1f)     // 1..4
    var ox by mutableFloatStateOf(0f)        // 相对宽度
    var oy by mutableFloatStateOf(0f)        // 相对高度
    val on get() = image != null
    private lateinit var file: File
    private lateinit var prefs: android.content.SharedPreferences

    fun init(c: Context) {
        file = File(c.filesDir, "bg.jpg")
        prefs = c.getSharedPreferences("bg", 0)
        blur = prefs.getFloat("blur", 0f); alpha = prefs.getFloat("alpha", .6f); scale = prefs.getFloat("scale", 1f)
        ox = prefs.getFloat("ox", 0f); oy = prefs.getFloat("oy", 0f)
        if (file.exists()) runCatching { src = decode(file.path); image = src?.asImageBitmap(); if (blur > .5f) reblur() }
    }

    /**
     * 模糊在 CPU 上预先做好一次（缩小后做三遍盒式模糊，效果接近高斯），
     * 不用每帧全屏 RenderEffect：更省电，预览和各页面看到的也完全一致，低版本安卓也能用。
     */
    fun reblur() {
        val s0 = src ?: return
        job?.cancel()
        val b = blur
        job = bgScope.launch {
            kotlinx.coroutines.delay(60)
            val out = withContext(Dispatchers.Default) { if (b < .5f) s0 else blurred(s0, b) }
            image = out.asImageBitmap()
        }
    }

    private fun blurred(s0: android.graphics.Bitmap, b: Float): android.graphics.Bitmap {
        val w = 360; val h = (s0.height * w / s0.width.toFloat()).toInt().coerceAtLeast(1)
        val sm = android.graphics.Bitmap.createScaledBitmap(s0, w, h, true)
        val px = IntArray(w * h); sm.getPixels(px, 0, w, 0, 0, w, h)
        val r = (b * .6f).toInt().coerceAtLeast(1)
        val tmp = IntArray(w * h)
        repeat(3) { boxPass(px, tmp, w, h, r, true); boxPass(tmp, px, w, h, r, false) }
        return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }

    /** 一遍盒式模糊（水平或垂直），滑动窗口求和，O(像素数) */
    private fun boxPass(inp: IntArray, out: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val n = if (horiz) w else h; val lines = if (horiz) h else w
        val div = 2 * r + 1
        for (l in 0 until lines) {
            fun at(i: Int) = inp[if (horiz) l * w + i.coerceIn(0, n - 1) else i.coerceIn(0, n - 1) * w + l]
            var ra = 0; var rr = 0; var rg = 0; var rb = 0
            for (i in -r..r) { val c = at(i); ra += c ushr 24; rr += (c shr 16) and 255; rg += (c shr 8) and 255; rb += c and 255 }
            for (i in 0 until n) {
                out[if (horiz) l * w + i else i * w + l] = ((ra / div) shl 24) or ((rr / div) shl 16) or ((rg / div) shl 8) or (rb / div)
                val a = at(i + r + 1); val d = at(i - r)
                ra += (a ushr 24) - (d ushr 24); rr += ((a shr 16) and 255) - ((d shr 16) and 255)
                rg += ((a shr 8) and 255) - ((d shr 8) and 255); rb += (a and 255) - (d and 255)
            }
        }
    }

    fun save() { prefs.edit().putFloat("blur", blur).putFloat("alpha", alpha).putFloat("scale", scale).putFloat("ox", ox).putFloat("oy", oy).apply() }
    fun resetTransform() { scale = 1f; ox = 0f; oy = 0f; save() }

    /** 长边缩到 1600 以内再存，省内存 */
    private fun decode(path: String): android.graphics.Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 1600) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    suspend fun set(c: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val tmp = File(c.cacheDir, "bg_in")
        c.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        val bm = decode(tmp.path) ?: throw Exception("读不了这张图片")
        file.outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.delete()
        withContext(Dispatchers.Main) { src = bm; image = bm.asImageBitmap(); resetTransform(); reblur() }
    }

    fun clear() { job?.cancel(); image = null; src = null; runCatching { file.delete() } }
}

/** 背景图层：放在页面最底下。base 是图片下面的底色（图片半透明时透出来） */
@Composable fun BgLayer(base: Color? = null) {
    val img = Bg.image ?: return
    Box(Modifier.fillMaxSize().then(if (base != null) Modifier.background(base) else Modifier).clipToBounds()) {
        Image(img, null, Modifier.fillMaxSize().graphicsLayer {
            scaleX = Bg.scale; scaleY = Bg.scale
            translationX = Bg.ox * size.width; translationY = Bg.oy * size.height
            alpha = Bg.alpha
        }, contentScale = ContentScale.Crop)
    }
}

/** 方形滑块条（BenchLab 风格） */
@Composable fun NSlider(value: Float, range: ClosedFloatingPointRange<Float>, onDone: () -> Unit = {}, onChange: (Float) -> Unit) {
    val p = c
    val f = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(32.dp).pointerInput(range) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull() ?: continue
                if (ch.pressed) {
                    val x = (ch.position.x / size.width).coerceIn(0f, 1f)
                    onChange(range.start + x * (range.endInclusive - range.start)); ch.consume()
                } else if (ch.previousPressed) onDone()
            }
        }
    }.drawBehind {
        val y = size.height / 2; val h = 4.dp.toPx(); val t = 14.dp.toPx()
        drawRect(p.l3, Offset(0f, y - h / 2), Size(size.width, h))
        drawRect(p.p, Offset(0f, y - h / 2), Size(size.width * f, h))
        drawRect(p.p, Offset((size.width * f - t / 2).coerceIn(0f, size.width - t), y - t / 2), Size(t, t))
    })
}

@Composable fun BgEditorPage() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = c
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { try { Bg.set(ctx, uri); nav.toast = "背景已更换" } catch (e: Exception) { nav.toast = "设置失败：${e.message}" } }
    }
    fun choose() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    val cfg = LocalConfiguration.current
    val ratio = cfg.screenWidthDp.toFloat() / cfg.screenHeightDp.coerceAtLeast(1)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 24.dp)) {
        item {
            Column {
                Tag("BACKGROUND"); Spacer(Modifier.height(4.dp))
                T("自定义背景", Fs.HERO, w = 800, lh = 1.3f)
                T(if (Bg.on) "在预览里双指缩放、单指拖动调整位置" else "选一张图片作为全局背景", 13, p.s)
                Spacer(Modifier.height(16.dp))
                // 预览：按手机屏幕比例缩小，手势直接改背景
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth(.62f).aspectRatio(ratio).clipToBounds().background(p.page).pointerInput(Bg.on) {
                        if (!Bg.on) return@pointerInput
                        detectTransformGestures { _, pan, zoom, _ ->
                            Bg.scale = (Bg.scale * zoom).coerceIn(1f, 4f)
                            Bg.ox = (Bg.ox + pan.x / size.width).coerceIn(-1.5f, 1.5f)
                            Bg.oy = (Bg.oy + pan.y / size.height).coerceIn(-1.5f, 1.5f)
                            Bg.save()
                        }
                    }) {
                        BgLayer()
                        // 示意的界面元素，方便看清字在背景上的效果
                        Column(Modifier.padding(10.dp)) {
                            T("NEKOPLAY", 13, w = 900); Spacer(Modifier.height(8.dp))
                            Box(Modifier.size(48.dp).background(p.ph).nb(1.dp, p.k)); Spacer(Modifier.height(6.dp))
                            T("歌曲标题", 12, w = 800); T("歌手名", 11, p.s)
                        }
                        if (!Bg.on) Box(Modifier.matchParentSize().press { choose() }, contentAlignment = Alignment.Center) { T("点击选择图片", 13, p.s, 700) }
                        // 边框画在最上层，避免被图片盖住
                        Box(Modifier.matchParentSize().border(2.dp, p.k))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row {
                    PrimaryBar(if (Bg.on) "更换图片" else "选择图片", NIcons.plus, Modifier.weight(1f)) { choose() }
                    if (Bg.on) { Spacer(Modifier.width(10.dp)); OutlineBtn("移除", pad = 14.dp, color = p.bad) { Bg.clear(); nav.toast = "已移除背景" } }
                }
            }
        }
        if (Bg.on) item {
            Column(Modifier.padding(top = 22.dp)) {
                SliderRow("模糊", "%.0f".format(Bg.blur), Bg.blur, 0f..30f) { Bg.blur = it; Bg.reblur() }
                SliderRow("透明度", "%.0f%%".format(Bg.alpha * 100), Bg.alpha, .05f..1f) { Bg.alpha = it }
                SliderRow("大小", "%.2fx".format(Bg.scale), Bg.scale, 1f..4f) { Bg.scale = it }
                SliderRow("水平位置", "%+.0f%%".format(Bg.ox * 100), Bg.ox, -1f..1f) { Bg.ox = it }
                SliderRow("垂直位置", "%+.0f%%".format(Bg.oy * 100), Bg.oy, -1f..1f) { Bg.oy = it }
                Spacer(Modifier.height(10.dp))
                OutlineBtn("重置位置和大小") { Bg.resetTransform() }
            }
        }
    }
}

@Composable private fun SliderRow(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val p = c
    Column(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { T(label, 16, w = 600, modifier = Modifier.weight(1f)); T(value, 13, p.p, 600, mono = true) }
        NSlider(v, range, onDone = { Bg.save() }, onChange = onChange)
    }
}
