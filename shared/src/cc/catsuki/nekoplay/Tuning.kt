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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

private data class CurvePreset(val name: String, val x1: Float, val y1: Float, val x2: Float, val y2: Float)
private val CURVE_PRESETS = listOf(
    CurvePreset("osu! 丝滑", 0.16f, 1.00f, 0.30f, 1.00f),
    CurvePreset("Apple 柔和", 0.25f, 0.10f, 0.25f, 1.00f),
    CurvePreset("Q 弹过冲", 0.34f, 1.42f, 0.64f, 1.00f),
    CurvePreset("Hydrogen", 0.40f, 0.00f, 0.12f, 1.00f),
    CurvePreset("Material", 0.20f, 0.00f, 0.00f, 1.00f),
)

/** 动效：可视化贝塞尔曲线拖拽编辑器 + 弹簧物理 + 各场景时长全自定义 */
@Composable fun TuningPage() {
    val nav = LocalNav.current
    val p = c
    val copy = rememberCopy()
    val scope = rememberCoroutineScope()
    val preview = remember { Animatable(0f) }
    var previewForward by remember { mutableStateOf(true) }

    fun playPreview() {
        scope.launch {
            val target = if (previewForward) 1f else 0f
            previewForward = !previewForward
            preview.animateTo(target, spF)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 48.dp)) {
        item { Column { Tag("MOTION"); Spacer(Modifier.height(4.dp)); T("动效与曲线", Fs.HERO, w = 800, lh = 1.3f); Spacer(Modifier.height(12.dp)); Rule() } }

        item {
            SetLine("动效形式", "弹簧带物理回弹，缓动先快后慢，osu! 极速起手丝滑无回弹，自定义曲线可直接在下方可视化拖拽控制点") {
                ChipRow(AnimStyle.labels, AnimStyle.style) { AnimStyle.set(it); playPreview() }
            }
        }

        item {
            SetLine("可视化曲线编辑器", "拖动蓝色 P1 与粉色 P2 控制点实时调整 cubic-bezier 曲线（纵轴支持拉出 0~1 范围产生过冲回弹）") {
                BezierCurveEditor(
                    x1 = AnimStyle.x1, y1 = AnimStyle.y1, x2 = AnimStyle.x2, y2 = AnimStyle.y2,
                    onChange = { a, b, c2, d -> AnimStyle.setCurve(a, b, c2, d, switchCustom = true) },
                    onDragEnd = { playPreview() },
                )
                Spacer(Modifier.height(10.dp))
                T(
                    "cubic-bezier(%.2f, %.2f, %.2f, %.2f)".format(AnimStyle.x1, AnimStyle.y1, AnimStyle.x2, AnimStyle.y2),
                    12, p.p, 700, mono = true,
                )
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
                // 实时试播轨道
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BoxWithConstraints(Modifier.weight(1f).height(34.dp).background(p.sheet).nbf(1.dp, p.l2).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                        val travel = (maxWidth - 26.dp).coerceAtLeast(1.dp)
                        Box(Modifier.offset(x = travel * preview.value.coerceIn(-0.08f, 1.08f)).size(22.dp).background(p.p))
                    }
                    Box(Modifier.press { playPreview() }.background(p.p).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        T("试播动效", 12, Color.White, 700)
                    }
                }
            }
        }

        item {
            SetLine("全局速度倍率", "整体加快或放慢所有页面切换、播放页、队列与弹窗动画") {
                ChipRow(Anim.labels, Anim.level) { Anim.set(it); playPreview() }
            }
        }

        item {
            SetLine("空间微交互（与曲线解耦）", "悬停位移、底色高亮、按压缩放与切页微缩放均独立控制，任意曲线模式下都能自由开关和调参") {
                ParamSlider("侧栏 / 列表悬停位移", MotionEffects.hoverShiftDp, 0f..12f, if (MotionEffects.hoverShiftDp < 0.5f) "关" else "${MotionEffects.hoverShiftDp.toInt()} dp", onDone = { MotionEffects.save() }) {
                    MotionEffects.hoverShiftDp = it.roundToInt().toFloat()
                }
                Spacer(Modifier.height(10.dp))
                ParamSlider("按压缩放幅度（1.00 = 关闭）", MotionEffects.pressScale, 0.92f..1.00f, "%.3f".format(MotionEffects.pressScale), onDone = { MotionEffects.save() }) {
                    MotionEffects.pressScale = (it * 200f).roundToInt() / 200f
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow("悬停底色高亮", "鼠标滑过侧边栏导航或歌单项时显示柔和底色", MotionEffects.hoverTint) { MotionEffects.hoverTint = it; MotionEffects.save() }
                SwitchRow("播放页 / 切页微缩放", "展开或收起播放页时叠加 0.96 → 1.00 的空间缩放过渡", MotionEffects.pageScale) { MotionEffects.pageScale = it; MotionEffects.save() }
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
                        nav.input = InputSpec("导入动效 JSON", "粘贴导出的动效配置 JSON（nekoplay-motion-v1）") { raw ->
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
    var dragging by remember { mutableIntStateOf(0) } // 1 = P1, 2 = P2

    Box(Modifier.fillMaxWidth().height(230.dp).background(p.sheet).nbf(1.dp, p.k)) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            val padX = 28.dp.toPx()
            val padY = 44.dp.toPx()
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
            val padY = 44.dp.toPx()
            val pw = (size.width - padX * 2).coerceAtLeast(1f)
            val ph = (size.height - padY * 2).coerceAtLeast(1f)
            fun pt(nx: Float, ny: Float) = Offset(padX + nx * pw, size.height - padY - ny * ph)

            // 网格与 0..1 基准框
            val gridCol = p.l2
            for (g in 0..4) {
                val f = g / 4f
                drawLine(gridCol, pt(f, 0f), pt(f, 1f), strokeWidth = 1f)
                drawLine(gridCol, pt(0f, f), pt(1f, f), strokeWidth = 1f)
            }
            // 对角线参考（线性）
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            drawLine(p.l3, pt(0f, 0f), pt(1f, 1f), strokeWidth = 1.5f, pathEffect = dash)

            val p0 = pt(0f, 0f)
            val p1 = pt(x1, y1)
            val p2 = pt(x2, y2)
            val p3 = pt(1f, 1f)

            // 控制柄切线
            drawLine(c1Color.copy(alpha = 0.55f), p0, p1, strokeWidth = 2.dp.toPx())
            drawLine(c2Color.copy(alpha = 0.55f), p3, p2, strokeWidth = 2.dp.toPx())

            // 三次贝塞尔曲线本体
            val path = Path().apply {
                moveTo(p0.x, p0.y)
                cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
            }
            drawPath(path, color = p.k, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))

            // 端点与可拖拽控制点
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
    Column(Modifier.fillMaxWidth().bottomLine().padding(vertical = 12.dp)) {
        T(title, 16); T(sub, 13, c.s, lines = 2); Spacer(Modifier.height(10.dp)); content()
    }
