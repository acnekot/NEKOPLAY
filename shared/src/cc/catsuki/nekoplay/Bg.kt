package cc.catsuki.nekoplay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
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
    private var src by mutableStateOf<ImageBitmap?>(null)
    private var job: kotlinx.coroutines.Job? = null
    private val bgScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    var blur by mutableFloatStateOf(0f)      // 0..30 dp
    var alpha by mutableFloatStateOf(.6f)    // 0..1
    var scale by mutableFloatStateOf(1f)     // 1..4
    var ox by mutableFloatStateOf(0f)        // 相对宽度
    var oy by mutableFloatStateOf(0f)        // 相对高度
    val on get() = Features.on("bg") && image != null
    private lateinit var file: File
    private lateinit var prefs: KV

    fun init() {
        file = File(Plat.impl.filesDir, "bg.jpg")
        prefs = Plat.prefs("bg")
        blur = prefs.getFloat("blur", 0f); alpha = prefs.getFloat("alpha", .6f); scale = prefs.getFloat("scale", 1f)
        ox = prefs.getFloat("ox", 0f); oy = prefs.getFloat("oy", 0f)
        if (file.exists()) runCatching { src = Plat.impl.decodeImage(file.path, 1600); image = src; if (blur > .5f) reblur() }
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
            image = out
        }
    }

    fun blurred(s0: ImageBitmap, b: Float): ImageBitmap {
        val w = 360; val h = (s0.height * w / s0.width.toFloat()).toInt().coerceAtLeast(1)
        val px = Plat.impl.pixels(Plat.impl.scaled(s0, w, h))
        val r = (b * .6f).toInt().coerceAtLeast(1)
        val tmp = IntArray(w * h)
        repeat(3) { boxPass(px, tmp, w, h, r, true); boxPass(tmp, px, w, h, r, false) }
        return Plat.impl.fromPixels(px, w, h)
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

    /** 存一份（长边缩到 1600 以内），再按当前模糊值处理 */
    suspend fun set(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val tmp = File(Plat.impl.cacheDir, "bg_in")
        tmp.writeBytes(bytes)
        val bm = Plat.impl.decodeImage(tmp.path, 1600) ?: throw Exception("读不了这张图片")
        Plat.impl.saveJpeg(bm, file)
        tmp.delete()
        withContext(Dispatchers.Main) { src = bm; image = bm; resetTransform(); reblur() }
    }

    /** 字节 → 图片（长边 1600 以内），不保存 */
    suspend fun decodeBytes(bytes: ByteArray): ImageBitmap = withContext(Dispatchers.IO) {
        val tmp = File(Plat.impl.cacheDir, "bg_in"); tmp.writeBytes(bytes)
        (Plat.impl.decodeImage(tmp.path, 1600) ?: throw Exception("读不了这张图片")).also { tmp.delete() }
    }
    val source get() = src

    /** 把编辑页的草稿应用成正式背景 */
    suspend fun apply(d: BgDraft) {
        if (d.src == null) { clear(); return }
        if (d.newImage) withContext(Dispatchers.IO) { Plat.impl.saveJpeg(d.src!!, file) }
        src = d.src; image = d.shown ?: d.src
        blur = d.blur; alpha = d.alpha; scale = d.scale; ox = d.ox; oy = d.oy
        save()
    }

    fun clear() { job?.cancel(); image = null; src = null; runCatching { file.delete() } }
}

/** 背景图层：放在页面最底下。base 是图片下面的底色（图片半透明时透出来）；draft 不为空时画草稿（编辑页预览用） */
@Composable fun BgLayer(base: Color? = null, draft: BgDraft? = null) {
    val img = (if (draft != null) draft.shown ?: draft.src else if (Features.on("bg")) Bg.image else null) ?: return
    val sc = draft?.scale ?: Bg.scale; val x = draft?.ox ?: Bg.ox; val y = draft?.oy ?: Bg.oy; val a = draft?.alpha ?: Bg.alpha
    Box(Modifier.fillMaxSize().then(if (base != null) Modifier.background(base) else Modifier).clipToBounds()) {
        Image(img, null, Modifier.fillMaxSize().graphicsLayer {
            scaleX = sc; scaleY = sc
            translationX = x * size.width; translationY = y * size.height
            alpha = a
        }, contentScale = ContentScale.Crop)
    }
}

/** 背景编辑草稿：只在预览里生效，点「应用」才写进正式背景 */
class BgDraft {
    var src by mutableStateOf(Bg.source)
    var shown by mutableStateOf(Bg.image)
    var blur by mutableFloatStateOf(Bg.blur)
    var alpha by mutableFloatStateOf(Bg.alpha)
    var scale by mutableFloatStateOf(Bg.scale)
    var ox by mutableFloatStateOf(Bg.ox)
    var oy by mutableFloatStateOf(Bg.oy)
    var newImage = false
    val changed get() = newImage || src != Bg.source || blur != Bg.blur || alpha != Bg.alpha || scale != Bg.scale || ox != Bg.ox || oy != Bg.oy
}

/** 方形滑块条（BenchLab 风格） */
/**
 * 设置里的滑条：手指要按住约 0.3 秒才开始拖（防止滑动页面时误触），鼠标按下立刻生效；
 * 激活时轻震一下、线变粗、方块放大，进度用 150ms 匀速追上手指（和播放进度条一样的拖尾）。
 */
@Composable fun NSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onDone: () -> Unit = {},
    stableDrag: Boolean = false,
    onChange: (Float) -> Unit,
) {
    val p = c
    val span = range.endInclusive - range.start
    val f = ((value - range.start) / span).coerceIn(0f, 1f)
    var active by remember { mutableStateOf(false) }
    var hold by remember { mutableStateOf(false) }        // 正在按住等待激活
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val lin = androidx.compose.animation.core.LinearEasing
    val fill = androidx.compose.animation.core.animateFloatAsState(f, androidx.compose.animation.core.tween(if (stableDrag && active) 0 else 150, easing = lin), label = "f")
    val th = androidx.compose.animation.core.animateFloatAsState(if (active) 1f else if (hold) .8f else .6f, androidx.compose.animation.core.tween(150, easing = lin), label = "t")
    val hh = androidx.compose.animation.core.animateFloatAsState(if (active) 6f else 4f, androidx.compose.animation.core.tween(150, easing = lin), label = "h")
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val cb by rememberUpdatedState(onChange); val done by rememberUpdatedState(onDone)
    Box(Modifier.fillMaxWidth().height(32.dp).then(if (stableDrag) Modifier.onPlaced { coords = it } else Modifier).pointerInput(range, stableDrag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val mouse = down.type == androidx.compose.ui.input.pointer.PointerType.Mouse
            var ok = mouse
            if (!mouse) {
                hold = true
                // 0.3 秒内松手或者明显移动 = 不是要拖滑条（交给页面滚动）
                val r = withTimeoutOrNull(300) {
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                        if (!ch.pressed) return@withTimeoutOrNull false
                        if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                hold = false
                ok = r == null
                if (ok) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
            if (!ok) return@awaitEachGesture
            active = true
            // 实时缩放滑块启用 stableDrag：按下瞬间锁定物理轨道起点与宽度，拖动手势期间不受整屏缩放反馈干扰
            val lc0 = if (stableDrag) coords?.takeIf { it.isAttached } else null
            val rootLeft = lc0?.positionInRoot()?.x ?: 0f
            val rootW = if (lc0 != null) (lc0.size.width * UiScalePreview.gpuRatio()).coerceAtLeast(1f) else size.width.toFloat().coerceAtLeast(1f)
            fun fracOf(pos: Offset): Float {
                val curLc = if (stableDrag) coords?.takeIf { it.isAttached } else null
                return if (lc0 != null && curLc != null) ((curLc.localToRoot(pos).x - rootLeft) / rootW).coerceIn(0f, 1f)
                else (pos.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
            }
            cb(range.start + fracOf(down.position) * span); down.consume()
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                cb(range.start + fracOf(ch.position) * span); ch.consume()
            }
            active = false; done()
        }
    }.drawBehind {
        val y = size.height / 2; val h = hh.value.dp.toPx(); val t = 14.dp.toPx() * th.value; val fv = if (stableDrag && active) f else fill.value
        drawRect(p.l3, Offset(0f, y - h / 2), Size(size.width, h))
        drawRect(p.p, Offset(0f, y - h / 2), Size(size.width * fv, h))
        drawRect(p.p, Offset((size.width * fv - t / 2).coerceIn(0f, (size.width - t).coerceAtLeast(0f)), y - t / 2), Size(t, t))
    })
}

@Composable fun BgEditorPage() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val p = c
    var d by remember { mutableStateOf(BgDraft()) }
    val dr = d
    val pick = Plat.impl.imagePicker { bytes ->
        scope.launch {
            try { val img = Bg.decodeBytes(bytes); dr.src = img; dr.shown = img; dr.newImage = true; dr.scale = 1f; dr.ox = 0f; dr.oy = 0f }
            catch (e: Exception) { nav.toast = "读取失败：${e.message}" }
        }
    }
    // 模糊在后台按草稿重新算（停手 120ms 后）
    LaunchedEffect(dr.src, dr.blur) {
        val s0 = dr.src ?: return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        dr.shown = withContext(Dispatchers.Default) { if (dr.blur < .5f) s0 else Bg.blurred(s0, dr.blur) }
    }
    val winInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    val ratio by remember(winInfo) { derivedStateOf {
        val sz = winInfo.containerSize
        ((sz.width.toFloat() / sz.height.coerceAtLeast(1)) * 20f).toInt() / 20f
    } }
    val has = dr.src != null
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 24.dp)) {
        item {
            Column {
                Tag("BACKGROUND"); Spacer(Modifier.height(4.dp))
                T("自定义背景", Fs.HERO, w = 800, lh = 1.3f)
                T(if (has) "先在预览里调好（可双指缩放、拖动），满意了再点「应用」" else "选一张图片作为全局背景", 13, p.s, lines = 2)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth(if (Screen.wide) .42f else .62f).aspectRatio(ratio).clipToBounds().backdrop().pointerInput(has) {
                        if (!has) return@pointerInput
                        detectTransformGestures { _, pan, zoom, _ ->
                            dr.scale = (dr.scale * zoom).coerceIn(1f, 4f)
                            dr.ox = (dr.ox + pan.x / size.width).coerceIn(-1.5f, 1.5f)
                            dr.oy = (dr.oy + pan.y / size.height).coerceIn(-1.5f, 1.5f)
                        }
                    }) {
                        BgLayer(draft = dr)
                        PreviewSkeleton(has)
                        if (!has) Box(Modifier.matchParentSize().press { pick() }, contentAlignment = Alignment.Center) { Box(Modifier.background(p.k).padding(horizontal = 12.dp, vertical = 7.dp)) { T("点击选择图片", 13, p.page, 700) } }
                        Box(Modifier.matchParentSize().border(2.dp, p.k))
                        if (dr.changed) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).background(p.p).padding(horizontal = 6.dp, vertical = 2.dp)) { T("预览中", 11, Color.White, 700) }
                    }
                }
                Spacer(Modifier.height(18.dp))
                ActionRow {
                    OutlineBtn(if (has) "换一张" else "选择图片", pad = 14.dp) { pick() }
                    if (has) { Spacer(Modifier.width(10.dp)); OutlineBtn("移除", pad = 14.dp, color = p.bad) { dr.src = null; dr.shown = null } }
                    Spacer(Modifier.weight(1f))
                    if (dr.changed) { OutlineBtn("还原", pad = 14.dp) { d = BgDraft() }; Spacer(Modifier.width(10.dp)) }
                    Box(Modifier.press(.96f, true) {
                        if (!dr.changed) return@press
                        scope.launch { runCatching { Bg.apply(dr) }.onSuccess { d = BgDraft(); nav.toast = "背景已应用" }.onFailure { nav.toast = "应用失败：${it.message}" } }
                    }.height(48.dp).background(if (dr.changed) p.p else p.l3).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) { T("应用", 15, Color.White, 700) }
                }
            }
        }
        if (has) item {
            Column(Modifier.padding(top = 22.dp)) {
                SliderRow("模糊", "%.0f".format(dr.blur), dr.blur, 0f..30f) { dr.blur = it }
                SliderRow("透明度", "%.0f%%".format(dr.alpha * 100), dr.alpha, .05f..1f) { dr.alpha = it }
                SliderRow("大小", "%.2fx".format(dr.scale), dr.scale, 1f..4f) { dr.scale = it }
                SliderRow("水平位置", "%+.0f%%".format(dr.ox * 100), dr.ox, -1f..1f) { dr.ox = it }
                SliderRow("垂直位置", "%+.0f%%".format(dr.oy * 100), dr.oy, -1f..1f) { dr.oy = it }
                Spacer(Modifier.height(10.dp))
                ActionRow { Spacer(Modifier.weight(1f)); OutlineBtn("重置位置和大小", pad = 14.dp) { dr.scale = 1f; dr.ox = 0f; dr.oy = 0f } }
            }
        }
    }
}

@Composable private fun SliderRow(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val p = c
    Column(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { T(label, 16, w = 600, modifier = Modifier.weight(1f)); T(value, 13, p.p, 600, mono = true) }
        NSlider(v, range, onChange = onChange)
    }
}

/**
 * 预览框里的「预载框架」：按真实首页的布局画一套骨架（顶栏、标签、推荐卡、歌曲行、底部播放条），
 * 栏位用和真实界面一样的半透明底，内容块用淡色占位，这样能直接看出背景放上去后整体的样子。
 */
@Composable private fun PreviewSkeleton(has: Boolean) {
    val p = c
    val bar = if (has) p.bar.copy(alpha = UiPref.barAlpha) else p.bar
    val blk = p.k.copy(alpha = .10f)   // 占位块
    val txt = p.k.copy(alpha = .22f)   // 占位文字条
    val line = if (Look.flat) Color.Transparent else p.k.copy(alpha = .35f)
    @Composable fun Bar(w: Float, h: Dp = 6.dp, col: Color = txt) = Box(Modifier.fillMaxWidth(w).height(h).background(col))
    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Row(Modifier.fillMaxWidth().background(bar).drawBehind { drawRect(line, Offset(0f, size.height - 1f), Size(size.width, 1f)) }.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            T("NEKOPLAY", 10, w = 900, modifier = Modifier.weight(1f))
            Box(Modifier.size(9.dp).border(1.dp, txt)); Spacer(Modifier.width(7.dp))
            Box(Modifier.size(14.dp).background(blk))
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
            // 标签
            Row(Modifier.padding(vertical = 7.dp)) {
                Box(Modifier.width(26.dp).height(9.dp).background(p.p.copy(alpha = .85f))); Spacer(Modifier.width(5.dp))
                repeat(3) { Box(Modifier.width(22.dp).height(9.dp).background(blk)); Spacer(Modifier.width(5.dp)) }
            }
            // 每日推荐卡
            Box(Modifier.fillMaxWidth().aspectRatio(2.1f).background(blk).border(1.dp, line)) {
                Column(Modifier.align(Alignment.BottomStart).padding(6.dp)) { Bar(.45f, 7.dp, p.k.copy(alpha = .4f)); Spacer(Modifier.height(4.dp)); Bar(.28f) }
            }
            Spacer(Modifier.height(7.dp))
            // 两张小卡
            Row {
                repeat(2) { i ->
                    Column(Modifier.weight(1f)) { Box(Modifier.fillMaxWidth().aspectRatio(1f).background(blk)); Spacer(Modifier.height(4.dp)); Bar(.7f); Spacer(Modifier.height(3.dp)); Bar(.4f, 5.dp) }
                    if (i == 0) Spacer(Modifier.width(7.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            // 歌曲行
            repeat(4) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).background(blk)); Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) { Bar(.62f); Spacer(Modifier.height(3.dp)); Bar(.36f, 5.dp) }
                    Box(Modifier.width(14.dp).height(5.dp).background(txt))
                }
            }
        }
        // 底部播放条
        Column(Modifier.fillMaxWidth().background(bar)) {
            Row(Modifier.fillMaxWidth().height(2.dp)) { Box(Modifier.weight(.3f).fillMaxHeight().background(p.p)); Box(Modifier.weight(.7f).fillMaxHeight().background(txt)) }
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).background(blk)); Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) { Bar(.5f, 6.dp, p.k.copy(alpha = .4f)); Spacer(Modifier.height(3.dp)); Bar(.3f, 5.dp) }
                Box(Modifier.size(8.dp).background(txt)); Spacer(Modifier.width(7.dp)); Box(Modifier.size(8.dp).background(txt))
            }
        }
    }
}
