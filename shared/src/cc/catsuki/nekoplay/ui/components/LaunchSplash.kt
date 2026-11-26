package cc.catsuki.nekoplay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 移动端冷启动封面转场状态（每次进程启动播放一次，桌面端与「关闭动效」模式自动跳过） */
object LaunchSplashState {
    var done by mutableStateOf(false)
    fun shouldPlay(): Boolean = !done && !Plat.isDesktop && !AnimStyle.off
}

private val SplashEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val IconTileBg = Color(0xFFE6E4DC)
private val IconCatInk = Color(0xFF111318)

/**
 * 手机冷启动封面动效：从 NEKOPLAY 猫耳应用图标柔和眨眼后展开为视口，平滑过渡到主界面。
 * 全程纯 Canvas + graphicsLayer 绘制阶段驱动，0 重组，播放结束后自动卸载零常驻开销；轻触可立即跳过。
 */
@Composable fun MobileLaunchCover(reveal: Animatable<Float, *>, onDone: () -> Unit) {
    val p = c
    val scope = rememberCoroutineScope()
    val intro = remember { Animatable(0f) }
    val blink = remember { Animatable(1f) }
    val morph = remember { Animatable(0f) }
    val catPath = remember { Path() }
    val clipIconPath = remember { Path() }
    var skipped by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!LaunchSplashState.shouldPlay()) {
            reveal.snapTo(1f)
            LaunchSplashState.done = true
            onDone()
            return@LaunchedEffect
        }
        val speed = Anim.f.coerceIn(0.85f, 1.5f)
        // 阶段 1：图标轻弹落位 + 猫眼轻眨一下，让静态启动图标「活」起来
        launch { intro.animateTo(1f, tween((170 / speed).toInt().coerceAtLeast(90), easing = SplashEase)) }
        delay((85 / speed).toLong())
        blink.animateTo(0.10f, tween((65 / speed).toInt().coerceAtLeast(40)))
        blink.animateTo(1f, tween((85 / speed).toInt().coerceAtLeast(50), easing = SplashEase))
        delay((55 / speed).toLong())
        // 阶段 2：图标圆角方框向外展开为视口，同步拉起下方主界面
        val morphMs = (460 / speed).toInt().coerceIn(260, 560)
        val j1 = launch { morph.animateTo(1f, tween(morphMs, easing = SplashEase)) }
        val j2 = launch { reveal.animateTo(1f, tween(morphMs, easing = SplashEase)) }
        j1.join(); j2.join()
        LaunchSplashState.done = true
        onDone()
    }

    val wordStyle = remember(p.k, Typo.family) {
        TextStyle(
            color = p.k,
            fontSize = 14.sp,
            fontWeight = FontWeight(800),
            fontFamily = Mono,
            letterSpacing = 3.2.sp,
        )
    }

    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        detectTapGestures {
            if (skipped) return@detectTapGestures
            skipped = true
            scope.launch {
                val j1 = launch { morph.animateTo(1f, tween(180, easing = SplashEase)) }
                val j2 = launch { reveal.animateTo(1f, tween(180, easing = SplashEase)) }
                j1.join(); j2.join()
                LaunchSplashState.done = true
                onDone()
            }
        }
    }) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val w = size.width
            val h = size.height
            val m = morph.value
            val i = intro.value
            val eyeScaleY = blink.value

            // 1. 全屏主题底幕（随图标视口展开淡出）
            val veilAlpha = (1f - ((m - 0.14f) / 0.72f).coerceIn(0f, 1f))
            if (veilAlpha > 0.004f) drawRect(p.page.copy(alpha = veilAlpha))

            // 2. 图标方框从居中 108dp 平滑延展至全屏视口
            val baseSide = 108.dp.toPx() * (0.92f + 0.08f * i)
            val expandT = (m * 1.12f).coerceIn(0f, 1f)
            val curW = baseSide + (w - baseSide) * expandT
            val curH = baseSide + (h - baseSide) * expandT
            val left = (w - curW) * 0.5f
            val top = (h - curH) * 0.5f
            val corner = 24.dp.toPx() * (1f - expandT)

            // 展开过程中将图标方框掏空为透明视口，直接透出下方正在缩放落位的主界面
            if (m > 0.02f) {
                val clearAlpha = ((m - 0.02f) / 0.36f).coerceIn(0f, 1f)
                drawRoundRect(
                    color = Color.Black.copy(alpha = clearAlpha),
                    topLeft = Offset(left, top),
                    size = Size(curW, curH),
                    cornerRadius = CornerRadius(corner, corner),
                    blendMode = BlendMode.DstOut,
                )
            }

            // 3. 标志性 NEKOPLAY 几何猫耳图标（展开前半程柔和放大并淡入主界面）
            val iconAlpha = (1f - (m / 0.44f).coerceIn(0f, 1f))
            if (iconAlpha > 0.004f) {
                val iconSide = baseSide * (1f + 0.22f * m)
                val ix = (w - iconSide) * 0.5f
                val iy = (h - iconSide) * 0.5f
                val ir = 24.dp.toPx() * (1f + 0.12f * m)

                clipIconPath.reset()
                clipIconPath.addRoundRect(RoundRect(Rect(ix, iy, ix + iconSide, iy + iconSide), CornerRadius(ir, ir)))
                clipPath(clipIconPath) {
                    drawRect(IconTileBg.copy(alpha = iconAlpha), Offset(ix, iy), Size(iconSide, iconSide))

                    // 精确还原应用图标里的几何黑猫剪影（右下探头 + 双尖耳）
                    catPath.reset()
                    catPath.moveTo(ix + iconSide * 0.36f, iy + iconSide * 1.02f)
                    catPath.lineTo(ix + iconSide * 0.36f, iy + iconSide * 0.37f)
                    catPath.lineTo(ix + iconSide * 0.555f, iy + iconSide * 0.56f)
                    catPath.lineTo(ix + iconSide * 0.735f, iy + iconSide * 0.56f)
                    catPath.lineTo(ix + iconSide * 0.93f, iy + iconSide * 0.37f)
                    catPath.lineTo(ix + iconSide * 0.93f, iy + iconSide * 1.02f)
                    catPath.close()
                    drawPath(catPath, IconCatInk.copy(alpha = iconAlpha))

                    // 双方形白眼（眨眼时纵向压扁）
                    val ew = iconSide * 0.074f
                    val eh = (iconSide * 0.098f * eyeScaleY).coerceAtLeast(1.5f)
                    val ey = iy + iconSide * 0.735f - eh * 0.5f
                    val eyeColor = Color.White.copy(alpha = iconAlpha)
                    drawRect(eyeColor, Offset(ix + iconSide * 0.52f - ew * 0.5f, ey), Size(ew, eh))
                    drawRect(eyeColor, Offset(ix + iconSide * 0.77f - ew * 0.5f, ey), Size(ew, eh))
                }
                // 图标外轮廓细线
                drawRoundRect(
                    color = IconCatInk.copy(alpha = 0.14f * iconAlpha),
                    topLeft = Offset(ix, iy),
                    size = Size(iconSide, iconSide),
                    cornerRadius = CornerRadius(ir, ir),
                    style = Stroke(1.2.dp.toPx()),
                )
            }

            // 4. 展开视口边缘一圈利落主色框线（贴合 NEKOPLAY 几何硬朗视觉，触边时自然消散）
            val frameAlpha = if (m in 0.02f..0.92f) (kotlin.math.sin(((m - 0.02f) / 0.90f) * kotlin.math.PI).toFloat() * 0.65f) else 0f
            if (frameAlpha > 0.005f) {
                drawRoundRect(
                    color = p.p.copy(alpha = frameAlpha),
                    topLeft = Offset(left, top),
                    size = Size(curW, curH),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }

        // 图标下方品牌字标：随图标显现并上浮淡出
        BasicText(
            text = "NEKOPLAY",
            style = wordStyle,
            modifier = Modifier.align(Alignment.Center).graphicsLayer {
                val i = intro.value
                val m = morph.value
                val fadeOut = (1f - (m / 0.34f).coerceIn(0f, 1f))
                alpha = i * fadeOut * 0.86f
                translationY = 80.dp.toPx() + (1f - i) * 10.dp.toPx() - m * 22.dp.toPx()
            },
        )
    }
}
