package cc.catsuki.nekoplay

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 标准数学解析缓动库（零 GC 60/120fps，供内置动效与 @type motion 插件精确声明各类曲线） */
object MotionEasings {
    val OutQuad = Easing { t -> val u = 1f - t; 1f - u * u }
    val OutCubic = Easing { t -> val u = 1f - t; 1f - u * u * u }
    val OutQuart = Easing { t -> val u = 1f - t; 1f - u * u * u * u }
    val OutQuint = Easing { t -> val u = 1f - t; 1f - u * u * u * u * u }
    val OutPow10 = Easing { t -> val u = (1.0 - t.toDouble()); (1.0 - u.pow(10.0)).toFloat() }
    /** 归一化 OutExpo / OutExpoTail：前 20% 时间瞬间冲出 80%+，后 80% 形成超长丝滑留尾且末帧 0 跳变 */
    private val expo10Norm = 1.0 / (1.0 - 2.0.pow(-10.0))
    private val expo12Norm = 1.0 / (1.0 - 2.0.pow(-12.0))
    val OutExpo = Easing { t -> if (t >= 1f) 1f else if (t <= 0f) 0f else ((1.0 - 2.0.pow(-10.0 * t)) * expo10Norm).toFloat() }
    val OutExpoTail = Easing { t -> if (t >= 1f) 1f else if (t <= 0f) 0f else ((1.0 - 2.0.pow(-12.0 * t)) * expo12Norm).toFloat() }
    val OutCirc = Easing { t -> val u = t - 1f; sqrt((1f - u * u).coerceAtLeast(0f)) }
    val OutBack = Easing { t -> val u = t - 1f; 1f + 2.70158f * u * u * u + 1.70158f * u * u }
    val OutElasticHalf = Easing { t ->
        if (t <= 0f) 0f else if (t >= 1f) 1f
        else (2.0.pow(-10.0 * t) * sin((0.5 * t - 0.075) * (2.0 * PI) / 0.3) + 1.0).toFloat()
    }
    val OutElasticQuarter = Easing { t ->
        if (t <= 0f) 0f else if (t >= 1f) 1f
        else (2.0.pow(-10.0 * t) * sin((0.25 * t - 0.075) * (2.0 * PI) / 0.3) + 1.0).toFloat()
    }
    val InOutCubic = Easing { t -> if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f }
    val InOutQuint = Easing { t -> if (t < 0.5f) 16f * t * t * t * t * t else 1f - (-2f * t + 2f).let { it * it * it * it * it } / 2f }

    fun byName(name: String, fallback: Easing): Easing = when (name.lowercase()) {
        "linear" -> LinearEasing
        "outquad" -> OutQuad
        "outcubic" -> OutCubic
        "outquart" -> OutQuart
        "outquint" -> OutQuint
        "outpow10" -> OutPow10
        "outexpo" -> OutExpo
        "outexpotail" -> OutExpoTail
        "outcirc" -> OutCirc
        "outback" -> OutBack
        "outelastichalf" -> OutElasticHalf
        "outelasticquarter" -> OutElasticQuarter
        "inoutcubic" -> InOutCubic
        "inoutquint" -> InOutQuint
        "fastoutslowin" -> FastOutSlowInEasing
        "decelout" -> DecelOut
        else -> fallback
    }
}

/** 64 位双精度三次贝塞尔缓动（牛顿迭代 + 二分兜底，精度 1e-7）：消除极端控制点末帧跳变顿挫 */
data class SmoothBezierEasing(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Easing {
    private val ax = x1.toDouble().coerceIn(0.0, 1.0); private val ay = y1.toDouble()
    private val bx = x2.toDouble().coerceIn(0.0, 1.0); private val by = y2.toDouble()
    private val cx = 3.0 * ax; private val bxC = 3.0 * (bx - ax) - cx; private val axC = 1.0 - cx - bxC
    private val cy = 3.0 * ay; private val byC = 3.0 * (by - ay) - cy; private val ayC = 1.0 - cy - byC
    private fun sampleX(t: Double) = ((axC * t + bxC) * t + cx) * t
    private fun sampleY(t: Double) = ((ayC * t + byC) * t + cy) * t
    private fun sampleDerivX(t: Double) = (3.0 * axC * t + 2.0 * bxC) * t + cx
    override fun transform(fraction: Float): Float {
        if (fraction <= 0f) return 0f; if (fraction >= 1f) return 1f
        val x = fraction.toDouble(); var t = x
        for (i in 0 until 8) {
            val err = sampleX(t) - x; if (kotlin.math.abs(err) < 1e-7) return sampleY(t).toFloat()
            val d = sampleDerivX(t); if (kotlin.math.abs(d) < 1e-7) break
            t = (t - err / d).coerceIn(0.0, 1.0)
        }
        var lo = 0.0; var hi = 1.0; t = x
        repeat(24) {
            val cur = sampleX(t); if (kotlin.math.abs(cur - x) < 1e-7) return sampleY(t).toFloat()
            if (cur < x) lo = t else hi = t; t = 0.5 * (lo + hi)
        }
        return sampleY(t).toFloat()
    }
}

val DecelOut = SmoothBezierEasing(0f, 0f, 0.2f, 1f)
