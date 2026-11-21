package cc.catsuki.nekoplay

/** 纯 Kotlin 的 MD5（iOS 也能用），插件的 nk.md5 走这里 */
object Hash {
    private val S = intArrayOf(7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21)
    private val K = IntArray(64) { (kotlin.math.abs(kotlin.math.sin((it + 1).toDouble())) * 4294967296.0).toLong().toInt() }
    fun md5(s: String): String = md5(s.encodeToByteArray())
    fun md5(input: ByteArray): String {
        val bitLen = input.size.toLong() * 8
        val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
        val msg = ByteArray(input.size + 1 + padLen + 8)
        input.copyInto(msg); msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) msg[msg.size - 8 + i] = (bitLen ushr (8 * i)).toByte()
        var a0 = 0x67452301; var b0 = 0xefcdab89.toInt(); var c0 = 0x98badcfe.toInt(); var d0 = 0x10325476
        val m = IntArray(16)
        for (off in msg.indices step 64) {
            for (i in 0 until 16) m[i] = (msg[off + i * 4].toInt() and 0xff) or ((msg[off + i * 4 + 1].toInt() and 0xff) shl 8) or ((msg[off + i * 4 + 2].toInt() and 0xff) shl 16) or ((msg[off + i * 4 + 3].toInt() and 0xff) shl 24)
            var a = a0; var b = b0; var c = c0; var d = d0
            for (i in 0 until 64) {
                var f: Int; val g: Int
                when (i / 16) {
                    0 -> { f = (b and c) or (b.inv() and d); g = i }
                    1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                f += a + K[i] + m[g]
                a = d; d = c; c = b
                b += (f shl S[i]) or (f ushr (32 - S[i]))
            }
            a0 += a; b0 += b; c0 += c; d0 += d
        }
        return buildString { for (v in intArrayOf(a0, b0, c0, d0)) for (i in 0 until 4) { val x = (v ushr (8 * i)) and 0xff; append("0123456789abcdef"[x shr 4]); append("0123456789abcdef"[x and 15]) } }
    }
}
