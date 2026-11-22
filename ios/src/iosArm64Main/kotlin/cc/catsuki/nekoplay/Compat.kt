package cc.catsuki.nekoplay


import cc.catsuki.nekoplay.core.*
import cc.catsuki.nekoplay.data.*
import cc.catsuki.nekoplay.player.*
import cc.catsuki.nekoplay.ui.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO as KIO
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRecursiveLock
import platform.Foundation.timeIntervalSince1970
import kotlin.time.TimeSource

// iOS 上没有的 JVM 小零件：同名补上，共用代码不用改

val Dispatchers.IO: CoroutineDispatcher get() = Dispatchers.KIO

object System {
    fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
    private val start = TimeSource.Monotonic.markNow()
    fun nanoTime(): Long = start.elapsedNow().inWholeNanoseconds
    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class) fun gc() { kotlin.native.runtime.GC.collect() }
    fun getProperty(k: String): String? = null
}

object Runtime {
    private val rt = RT()
    fun getRuntime() = rt
    class RT {
        fun availableProcessors() = NSProcessInfo.processInfo.activeProcessorCount.toInt()
        fun totalMemory(): Long = 0
        fun freeMemory(): Long = 0
        fun maxMemory(): Long = NSProcessInfo.processInfo.physicalMemory.toLong()
    }
}

object Integer {
    fun toHexString(i: Int): String = i.toUInt().toString(16)
    fun parseInt(s: String) = s.toInt()
    const val MAX_VALUE = Int.MAX_VALUE
}

object Charsets { val UTF_8 = Charset("UTF-8") }
class Charset(val name: String)
fun String.toByteArray(@Suppress("UNUSED_PARAMETER") cs: Charset = Charsets.UTF_8): ByteArray = encodeToByteArray()
fun ByteArray.toString(@Suppress("UNUSED_PARAMETER") cs: Charset): String = decodeToString()

class JavaClass(val simpleName: String)
val Any.javaClass: JavaClass get() = JavaClass(this::class.simpleName ?: "Object")

@Target(AnnotationTarget.FUNCTION) annotation class Synchronized
typealias Volatile = kotlin.concurrent.Volatile

private val bigLock = NSRecursiveLock()
inline fun <R> synchronized(@Suppress("UNUSED_PARAMETER") lock: Any, block: () -> R): R { lockIt(); try { return block() } finally { unlockIt() } }
@PublishedApi internal fun lockIt() = bigLock.lock()
@PublishedApi internal fun unlockIt() = bigLock.unlock()

/** Java 风格的 String.format：支持 %s %d %f %x %c %% 以及宽度、补零、精度、+ 号、千分位 */
fun String.format(vararg args: Any?): String {
    val sb = StringBuilder(); var i = 0; var ai = 0
    while (i < length) {
        val c = this[i]
        if (c != '%') { sb.append(c); i++; continue }
        i++
        if (i >= length) break
        if (this[i] == '%') { sb.append('%'); i++; continue }
        if (this[i] == 'n') { sb.append('\n'); i++; continue }
        var flags = ""
        while (i < length && this[i] in "-+0 ,#") { flags += this[i]; i++ }
        var width = 0
        while (i < length && this[i].isDigit()) { width = width * 10 + (this[i] - '0'); i++ }
        var prec = -1
        if (i < length && this[i] == '.') { i++; prec = 0; while (i < length && this[i].isDigit()) { prec = prec * 10 + (this[i] - '0'); i++ } }
        val conv = this[i]; i++
        val a = args.getOrNull(ai++)
        var s = when (conv) {
            'd' -> {
                val v = (a as? Number)?.toLong() ?: 0L
                var t = kotlin.math.abs(v).toString()
                if (',' in flags) t = t.reversed().chunked(3).joinToString(",").reversed()
                (if (v < 0) "-" else if ('+' in flags) "+" else if (' ' in flags) " " else "") + t
            }
            'f', 'e', 'g' -> {
                val v = (a as? Number)?.toDouble() ?: 0.0
                val p = if (prec < 0) 6 else prec
                var t = fixed(kotlin.math.abs(v), p)
                if (',' in flags) { val ip = t.substringBefore('.'); val fp = t.substringAfter('.', ""); t = ip.reversed().chunked(3).joinToString(",").reversed() + if (fp.isNotEmpty()) ".$fp" else "" }
                val neg = v < 0 || (v == 0.0 && 1.0 / v < 0)
                (if (neg) "-" else if ('+' in flags) "+" else if (' ' in flags) " " else "") + t
            }
            'x', 'X' -> ((a as? Number)?.toLong() ?: 0L).let { if (it < 0) (it.toULong()).toString(16) else it.toString(16) }.let { if (conv == 'X') it.uppercase() else it }
            'c' -> a.toString()
            'b' -> (a != null && a != false).toString()
            else -> a.toString().let { if (prec >= 0) it.take(prec) else it }
        }
        if (s.length < width) {
            s = when {
                '-' in flags -> s.padEnd(width)
                '0' in flags && conv in "dfxX" -> { val sign = if (s.startsWith("-") || s.startsWith("+")) s.take(1) else ""; sign + s.drop(sign.length).padStart(width - sign.length, '0') }
                else -> s.padStart(width)
            }
        }
        sb.append(s)
    }
    return sb.toString()
}

// 和 Java 一样：按十进制最短表示四舍五入（HALF_UP）
private fun fixed(v: Double, p: Int): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return "Infinity"
    val str = v.toString()
    if ('E' in str || 'e' in str) {
        var m = 1.0; repeat(p) { m *= 10 }
        val n = kotlin.math.floor(v * m + 0.5)
        if (n >= 9.0e18) return str
        val nl = n.toLong(); if (p == 0) return nl.toString()
        val ml = m.toLong(); return (nl / ml).toString() + "." + (nl % ml).toString().padStart(p, '0')
    }
    val ip = str.substringBefore('.'); var fp = str.substringAfter('.', "")
    fp = fp.padEnd(p + 1, '0')
    val digits = (ip + fp.take(p)).toCharArray()
    if (fp[p] >= '5') {
        var i = digits.size - 1; var carry = true
        while (carry && i >= 0) { if (digits[i] == '9') { digits[i] = '0'; i-- } else { digits[i] = digits[i] + 1; carry = false } }
        val all = (if (carry) "1" else "") + digits.concatToString()
        val il = all.length - p
        return if (p == 0) all else all.substring(0, il) + "." + all.substring(il)
    }
    val all = digits.concatToString(); val il = all.length - p
    return if (p == 0) all else all.substring(0, il) + "." + all.substring(il)
}
