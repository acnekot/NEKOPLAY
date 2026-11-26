package cc.catsuki.nekoplay

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

private data class CurvePreset(val name: String, val x1: Float, val y1: Float, val x2: Float, val y2: Float)
private val CURVE_PRESETS = listOf(
    CurvePreset("长留尾缓出", 0.08f, 1.00f, 0.18f, 1.00f),
    CurvePreset("Apple 柔和", 0.25f, 0.10f, 0.25f, 1.00f),
    CurvePreset("Q 弹过冲", 0.34f, 1.42f, 0.64f, 1.00f),
    CurvePreset("Hydrogen", 0.40f, 0.00f, 0.12f, 1.00f),
    CurvePreset("Material", 0.20f, 0.00f, 0.00f, 1.00f),
)

/** 动效设置：分为「简单配置」（默认纯净基础动效 + 插件入口）与「高级自定义」（贝塞尔曲线 / 物理参数 / 时长 / 微交互） */
@Composable fun TuningPage() {
    val nav = LocalNav.current
    val p = c
    val copy = rememberCopy()
    val scope = rememberCoroutineScope()
    val preview = remember { Animatable(0f) }
    var previewForward by remember { mutableStateOf(true) }
    var previewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var tab by remember { mutableIntStateOf(0) } // 0 简单配置 / 1 高级自定义
    var lastOnStyle by remember { mutableIntStateOf(if (AnimStyle.style == 3) 0 else AnimStyle.style) }

    fun playPreview() {
        previewJob?.cancel()
        val target = if (previewForward) 1f else 0f
        previewForward = !previewForward
        previewJob = scope.launch { preview.animateTo(target, spF) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 48.dp)) {
        item {
            Column {
                Tag("MOTION"); Spacer(Modifier.height(4.dp))
                T("动效与曲线", Fs.HERO, w = 800, lh = 1.3f)
                Spacer(Modifier.height(12.dp))
                ChipRow(listOf("简单配置", "高级自定义"), tab) { tab = it }
                Spacer(Modifier.height(12.dp)); Rule()
            }
        }

        // 顶部外置动效插件状态卡（简单与高级视图均可见，清晰标明插件接管边界）
        item {
            val mp = AnimStyle.pluginProfile
            if (mp != null) {
                Column(Modifier.fillMaxWidth().padding(top = 14.dp).background(p.p.copy(alpha = 0.10f)).nbf(1.dp, p.p.copy(alpha = 0.45f)).padding(14.dp)) {
                    T("MOTION PLUGIN ACTIVE · 外置动效插件已接管", 10, p.p, 800, mono = true)
                    Spacer(Modifier.height(4.dp))
                    T(mp.displayName, 16, p.k, 800)
                    T("特色缓动曲线与列表瀑布入场已由该插件独立驱动（仍受全局动效开关与速度倍率控制）", 12, p.s, lines = 2)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (Plugins.get(mp.pluginId)?.has("render") == true) Box(Modifier.press { nav.push(Page.PluginPage(mp.pluginId)) }.background(p.p).padding(horizontal = 12.dp, vertical = 6.dp)) { T("插件设置", 11, Color.White, 700) }
                        Box(Modifier.press { Plugins.set(mp.pluginId, false); playPreview() }.nbf(1.dp, p.k).padding(horizontal = 12.dp, vertical = 6.dp)) { T("停用并切回默认", 11, p.k, 600) }
                        Box(Modifier.press { playPreview() }.nbf(1.dp, p.l2).padding(horizontal = 12.dp, vertical = 6.dp)) { T("试播", 11, p.k, 600) }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).press { nav.push(Page.PluginMgr) }.background(p.sheet).nbf(1.dp, p.l2).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("主软件默认仅保留简洁基础动效", 13, p.k, 700)
                        T("瀑布入场、长留尾缓动等特色动效已剥离为独立动效插件，需要时到插件中心按需安装", 11, p.s, lines = 2)
                    }
                    Spacer(Modifier.width(8.dp))
                    T("插件中心 →", 11, p.p, 700)
                }
            }
        }

        if (tab == 0) {
            item {
                SwitchRow("启用界面动效", "关闭后页面切换、弹窗与交互立即响应，零动画耗时", AnimStyle.style != 3) { on ->
                    if (on) AnimStyle.set(if (lastOnStyle == 3) 0 else lastOnStyle)
                    else { if (AnimStyle.style != 3) lastOnStyle = AnimStyle.style; AnimStyle.set(3) }
                    playPreview()
                }
            }
            if (AnimStyle.style != 3) {
                item {
                    val simpleLabels = listOf("默认弹簧", "柔和缓动", "线性")
                    val curIdx = if (AnimStyle.style in 0..2) AnimStyle.style else -1
                    SetLine(
                        "基础动效风格",
                        if (AnimStyle.pluginProfile != null) "当前正由外置动效插件接管；停用插件后将使用此处基础风格"
                        else if (AnimStyle.style == 4) "当前正使用「高级自定义」曲线；点击下方选项可切回简洁内置风格"
                        else "轻盈干净的内置基础动效，不附加列表瀑布入场等重动画",
                    ) {
                        ChipRow(simpleLabels, curIdx) { lastOnStyle = it; AnimStyle.set(it); playPreview() }
                    }
                }
                item {
                    SetLine("全局速度倍率", "整体加快或放慢所有页面切换、播放页与弹窗动画") {
                        ChipRow(Anim.labels, Anim.level) { Anim.set(it); playPreview() }
                    }
                }
                item {
                    SetLine("动效预览", "点击试播感受当前节奏（含外置动效插件）") {
                        PreviewTrack(preview.value) { playPreview() }
                    }
                }
            }
        } else {
            item {
                SetLine("内置动效引擎", "完整模式：弹簧 / 缓动 / 线性 / 关闭 / 自定义贝塞尔曲线") {
                    ChipRow(AnimStyle.labels, AnimStyle.style) { if (it != 3) lastOnStyle = it; AnimStyle.set(it); playPreview() }
                }
            }
            item {
                SetLine("可视化曲线编辑器", "拖动蓝色 P1 与粉色 P2 控制点实时调整 cubic-bezier 曲线（纵轴支持拉出 0~1 产生过冲回弹）") {
                    BezierCurveEditor(
                        x1 = AnimStyle.x1, y1 = AnimStyle.y1, x2 = AnimStyle.x2, y2 = AnimStyle.y2,
                        onChange = { a, b, c2, d -> AnimStyle.setCurve(a, b, c2, d, switchCustom = true, persist = false) },
                        onDragEnd = { AnimStyle.save(); playPreview() },
                    )
                    Spacer(Modifier.height(10.dp))
                    T("cubic-bezier(%.2f, %.2f, %.2f, %.2f)".format(AnimStyle.x1, AnimStyle.y1, AnimStyle.x2, AnimStyle.y2), 12, p.p, 700, mono = true)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CURVE_PRESETS.forEach { pr ->
                            Box(Modifier.press { AnimStyle.setCurve(pr.x1, pr.y1, pr.x2, pr.y2, switchCustom = true); playPreview() }
                                .nbf(1.dp, p.l2).padding(horizontal = 10.dp, vertical = 5.dp)) {
                                T(pr.name, 11, p.k, 600)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    PreviewTrack(preview.value) { playPreview() }
                }
            }
            item {
                SetLine("空间微交互", "悬停位移、底色高亮、按压缩放与切页微缩放（瀑布入场已剥离至外置动效插件）") {
                    ParamSlider("侧栏 / 列表悬停位移", MotionEffects.hoverShiftDp, 0f..12f, if (MotionEffects.hoverShiftDp < 0.5f) "关" else "${MotionEffects.hoverShiftDp.toInt()} dp", onDone = { MotionEffects.save() }) {
                        MotionEffects.hoverShiftDp = it.roundToInt().toFloat()
                    }
                    Spacer(Modifier.height(10.dp))
                    ParamSlider("按压缩放幅度（1.00 = 关闭）", MotionEffects.pressScale, 0.92f..1.00f, "%.3f".format(MotionEffects.pressScale), onDone = { MotionEffects.save() }) {
                        MotionEffects.pressScale = (it * 200f).roundToInt() / 200f
                    }
                    Spacer(Modifier.height(8.dp))
                    SwitchRow("悬停底色高亮", "鼠标滑过侧边栏导航或歌单项时显示柔和底色", MotionEffects.hoverTint) { MotionEffects.hoverTint = it; MotionEffects.save() }
                    SwitchRow("播放页 / 切页微缩放", "展开或收起播放页时叠加空间缩放过渡", MotionEffects.pageScale) { MotionEffects.pageScale = it; MotionEffects.save() }
                }
            }
            item {
                SetLine("弹簧物理参数", "用于「弹簧」模式与播放页展开等物理动效") {
                    ParamSlider("阻尼 Damping（越小越弹）", AnimStyle.springDamping, 0.35f..1.0f, "%.2f".format(AnimStyle.springDamping), onDone = { AnimStyle.save(); playPreview() }) {
                        AnimStyle.springDamping = (it * 100f).roundToInt() / 100f
                    }
                    Spacer(Modifier.height(10.dp))
                    ParamSlider("刚度 Stiffness（越大越快）", AnimStyle.springStiffness, 100f..1200f, "${AnimStyle.springStiffness.toInt()}", onDone = { AnimStyle.save(); playPreview() }) {
                        AnimStyle.springStiffness = (it / 20f).roundToInt() * 20f
                    }
                }
            }
            item {
                SetLine("各场景动画时长（ms）", "精细控制微交互、弹窗、切页与封面渐变的基准时长") {
                    ParamSlider("微交互（按钮 / 开关）", AnimStyle.quickBaseMs.toFloat(), 40f..400f, "${AnimStyle.quickBaseMs} ms", onDone = { AnimStyle.save() }) {
                        AnimStyle.quickBaseMs = (it / 10f).roundToInt() * 10
                    }
                    Spacer(Modifier.height(10.dp))
                    ParamSlider("弹窗 / 菜单 / 面板", AnimStyle.standardBaseMs.toFloat(), 80f..600f, "${AnimStyle.standardBaseMs} ms", onDone = { AnimStyle.save() }) {
                        AnimStyle.standardBaseMs = (it / 10f).roundToInt() * 10
                    }
                    Spacer(Modifier.height(10.dp))
                    ParamSlider("页面切换 / 播放页", AnimStyle.pageBaseMs.toFloat(), 100f..800f, "${AnimStyle.pageBaseMs} ms", onDone = { AnimStyle.save(); playPreview() }) {
                        AnimStyle.pageBaseMs = (it / 10f).roundToInt() * 10
                    }
                    Spacer(Modifier.height(10.dp))
                    ParamSlider("封面 / 背景交叉淡化", AnimStyle.crossfadeBaseMs.toFloat(), 100f..1000f, "${AnimStyle.crossfadeBaseMs} ms", onDone = { AnimStyle.save() }) {
                        AnimStyle.crossfadeBaseMs = (it / 20f).roundToInt() * 20
                    }
                }
            }
            item {
                SetLine("导入 / 导出动效配置", "复制或粘贴完整的曲线与时长 JSON，方便跨设备同步或分享") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.press { copy(AnimStyle.exportJson(), "动效配置 JSON") }.nbf(1.dp, p.k).padding(horizontal = 12.dp, vertical = 7.dp)) {
                            T("导出 JSON", 12, p.k, 700)
                        }
                        Box(Modifier.press {
                            nav.input = InputSpec("导入动效 JSON", "粘贴导出的动效配置 JSON（nekoplay-motion-v2）") { raw ->
                                runCatching { AnimStyle.importJson(raw) }.onFailure { nav.toast = "JSON 格式有误：${it.message}"; return@InputSpec }
                                nav.toast = "已导入动效配置"; playPreview()
                            }
                        }.background(p.k).padding(horizontal = 12.dp, vertical = 7.dp)) {
                            T("导入 JSON", 12, p.tagFg, 700)
                        }
                        Spacer(Modifier.weight(1f))
                        LinkBtn("恢复默认", 12) { AnimStyle.reset(); nav.toast = "已恢复默认动效"; playPreview() }
                    }
                }
            }
        }
    }
}

@Composable private fun PreviewTrack(progress: Float, onPlay: () -> Unit) {
    val p = c
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        var trackPx by remember { mutableFloatStateOf(0f) }
        Box(Modifier.weight(1f).height(34.dp).background(p.sheet).nbf(1.dp, p.l2)
            .onSizeChanged { trackPx = it.width.toFloat() }
            .padding(horizontal = 24.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.size(22.dp).graphicsLayer {
                val travel = (trackPx - 48.dp.toPx() - 22.dp.toPx()).coerceAtLeast(1f)
                translationX = travel * progress
            }.background(p.p))
        }
        Box(Modifier.press(onClick = onPlay).background(p.p).padding(horizontal = 14.dp, vertical = 8.dp)) {
            T("试播动效", 12, Color.White, 700)
        }
    }
}

@Composable private fun ParamSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onDone: () -> Unit, onChange: (Float) -> Unit) {
    val p = c
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(label, 12, p.s, modifier = Modifier.weight(1f))
            T(display, 12, p.p, 700, mono = true)
        }
        NSlider(value, range, onDone = onDone, onChange = onChange)
    }
}

/** 可视化拖拽 Cubic-Bezier 编辑器：x ∈ [0, 1]，y ∈ [-0.45, 1.45]（留出上下过冲区） */
@Composable private fun BezierCurveEditor(
    x1: Float, y1: Float, x2: Float, y2: Float,
    onChange: (Float, Float, Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val p = c
    val c1Color = p.p
    val c2Color = Color(0xFFFF4D6D)
    val latest by rememberUpdatedState(listOf(x1, y1, x2, y2))
    var dragging by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxWidth().height(220.dp).background(p.sheet).nbf(1.dp, p.k)) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            val padX = 28.dp.toPx()
            val padY = 42.dp.toPx()
            fun plotW() = (size.width - padX * 2).coerceAtLeast(1f)
            fun plotH() = (size.height - padY * 2).coerceAtLeast(1f)
            fun toPx(nx: Float, ny: Float) = Offset(padX + nx * plotW(), size.height - padY - ny * plotH())
            fun fromPx(pos: Offset): Pair<Float, Float> {
                val nx = ((pos.x - padX) / plotW()).coerceIn(0f, 1f)
                val ny = ((size.height - padY - pos.y) / plotH()).coerceIn(-0.45f, 1.45f)
                return ((nx * 100f).roundToInt() / 100f) to ((ny * 100f).roundToInt() / 100f)
            }
            detectDragGestures(
                onDragStart = { pos ->
                    val (ax, ay, bx, by) = latest
                    val d1 = hypot(pos.x - toPx(ax, ay).x, pos.y - toPx(ax, ay).y)
                    val d2 = hypot(pos.x - toPx(bx, by).x, pos.y - toPx(bx, by).y)
                    dragging = if (d1 <= d2) 1 else 2
                },
                onDragEnd = { dragging = 0; onDragEnd() },
                onDragCancel = { dragging = 0 },
            ) { change, _ ->
                change.consume()
                val (nx, ny) = fromPx(change.position)
                val (ax, ay, bx, by) = latest
                if (dragging == 1) onChange(nx, ny, bx, by)
                else if (dragging == 2) onChange(ax, ay, nx, ny)
            }
        }) {
            val padX = 28.dp.toPx()
            val padY = 42.dp.toPx()
            val pw = (size.width - padX * 2).coerceAtLeast(1f)
            val ph = (size.height - padY * 2).coerceAtLeast(1f)
            fun pt(nx: Float, ny: Float) = Offset(padX + nx * pw, size.height - padY - ny * ph)

            val gridCol = p.l2
            for (g in 0..4) {
                val f = g / 4f
                drawLine(gridCol, pt(f, 0f), pt(f, 1f), strokeWidth = 1f)
                drawLine(gridCol, pt(0f, f), pt(1f, f), strokeWidth = 1f)
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            drawLine(p.l3, pt(0f, 0f), pt(1f, 1f), strokeWidth = 1.5f, pathEffect = dash)

            val p0 = pt(0f, 0f); val p1 = pt(x1, y1); val p2 = pt(x2, y2); val p3 = pt(1f, 1f)
            drawLine(c1Color.copy(alpha = 0.55f), p0, p1, strokeWidth = 2.dp.toPx())
            drawLine(c2Color.copy(alpha = 0.55f), p3, p2, strokeWidth = 2.dp.toPx())

            val path = Path().apply { moveTo(p0.x, p0.y); cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y) }
            drawPath(path, color = p.k, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))

            drawCircle(p.k, radius = 4.dp.toPx(), center = p0)
            drawCircle(p.k, radius = 4.dp.toPx(), center = p3)
            drawCircle(c1Color, radius = if (dragging == 1) 10.dp.toPx() else 8.dp.toPx(), center = p1)
            drawCircle(Color.White, radius = 3.dp.toPx(), center = p1)
            drawCircle(c2Color, radius = if (dragging == 2) 10.dp.toPx() else 8.dp.toPx(), center = p2)
            drawCircle(Color.White, radius = 3.dp.toPx(), center = p2)
        }
    }
}

/** 快捷键一览（桌面） */
@Composable fun KeysPage() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        item { Column { Tag("KEYBOARD"); Spacer(Modifier.height(4.dp)); T("快捷键", Fs.HERO, w = 800, lh = 1.3f); Spacer(Modifier.height(12.dp)); Rule() } }
        items(Keys.HELP.size) { i -> val (k, d) = Keys.HELP[i]
            Row(Modifier.fillMaxWidth().bottomLine().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(150.dp)) { Tag(k) }; T(d, 14, lines = 2)
            }
        }
    }
}

/** 设置里带控件的一行：标题 + 说明 + 下面放滑条 / 选项 */
@Composable fun SetLine(title: String, sub: String, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().cascadeEnter().bottomLine().padding(vertical = 12.dp)) {
        T(title, 16); T(sub, 13, c.s, lines = 2); Spacer(Modifier.height(10.dp)); content()
    }
