package javax.crypto

// iOS 版 AES（纯 Kotlin，K/N 没有 CommonCrypto 绑定）：AES-128/192/256，CBC / ECB，PKCS5 填充

interface Key { val encoded: ByteArray }

class Cipher private constructor(private val ecb: Boolean, private val pad: Boolean) {
    private var enc = true
    private lateinit var w: IntArray
    private var rounds = 10
    private var iv: ByteArray? = null

    fun init(mode: Int, k: Key, params: Any? = null) {
        enc = mode == ENCRYPT_MODE
        val key = k.encoded
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES 密钥长度不对：${key.size}" }
        w = expand(key); rounds = key.size / 4 + 6
        iv = (params as? javax.crypto.spec.IvParameterSpec)?.iv?.copyOf()
    }

    fun doFinal(input: ByteArray): ByteArray = if (enc) encrypt(input) else decrypt(input)

    private fun encrypt(input: ByteArray): ByteArray {
        val data = if (pad) { val p = 16 - input.size % 16; input + ByteArray(p) { p.toByte() } } else input
        require(data.size % 16 == 0) { "数据长度不是 16 的倍数" }
        val out = ByteArray(data.size); var prev = iv ?: ByteArray(16)
        for (o in data.indices step 16) {
            val b = data.copyOfRange(o, o + 16)
            if (!ecb) for (i in 0 until 16) b[i] = (b[i].toInt() xor prev[i].toInt()).toByte()
            val c = block(b, true); c.copyInto(out, o); prev = c
        }
        return out
    }

    private fun decrypt(data: ByteArray): ByteArray {
        require(data.size % 16 == 0) { "数据长度不是 16 的倍数" }
        val out = ByteArray(data.size); var prev = iv ?: ByteArray(16)
        for (o in data.indices step 16) {
            val c = data.copyOfRange(o, o + 16)
            val p = block(c, false)
            if (!ecb) for (i in 0 until 16) p[i] = (p[i].toInt() xor prev[i].toInt()).toByte()
            p.copyInto(out, o); prev = c
        }
        if (!pad) return out
        val n = out.last().toInt() and 0xff
        require(n in 1..16) { "填充不对" }
        return out.copyOf(out.size - n)
    }

    private fun block(inp: ByteArray, e: Boolean): ByteArray {
        val s = IntArray(16) { inp[it].toInt() and 0xff }
        fun addKey(r: Int) { for (c in 0 until 4) { val k = w[r * 4 + c]; for (i in 0 until 4) s[c * 4 + i] = s[c * 4 + i] xor ((k ushr (24 - 8 * i)) and 0xff) } }
        if (e) {
            addKey(0)
            for (r in 1..rounds) {
                for (i in 0 until 16) s[i] = SBOX[s[i]]
                shift(s, false)
                if (r != rounds) mix(s, false)
                addKey(r)
            }
        } else {
            addKey(rounds)
            for (r in rounds - 1 downTo 0) {
                shift(s, true)
                for (i in 0 until 16) s[i] = INV[s[i]]
                addKey(r)
                if (r != 0) mix(s, true)
            }
        }
        return ByteArray(16) { s[it].toByte() }
    }

    private fun shift(s: IntArray, inv: Boolean) {
        val t = s.copyOf()
        for (c in 0 until 4) for (r in 1 until 4) {
            val src = if (inv) (c - r + 4) % 4 else (c + r) % 4
            s[c * 4 + r] = t[src * 4 + r]
        }
    }

    private fun mix(s: IntArray, inv: Boolean) {
        for (c in 0 until 4) {
            val a0 = s[c * 4]; val a1 = s[c * 4 + 1]; val a2 = s[c * 4 + 2]; val a3 = s[c * 4 + 3]
            if (!inv) {
                s[c * 4] = mul(a0, 2) xor mul(a1, 3) xor a2 xor a3
                s[c * 4 + 1] = a0 xor mul(a1, 2) xor mul(a2, 3) xor a3
                s[c * 4 + 2] = a0 xor a1 xor mul(a2, 2) xor mul(a3, 3)
                s[c * 4 + 3] = mul(a0, 3) xor a1 xor a2 xor mul(a3, 2)
            } else {
                s[c * 4] = mul(a0, 14) xor mul(a1, 11) xor mul(a2, 13) xor mul(a3, 9)
                s[c * 4 + 1] = mul(a0, 9) xor mul(a1, 14) xor mul(a2, 11) xor mul(a3, 13)
                s[c * 4 + 2] = mul(a0, 13) xor mul(a1, 9) xor mul(a2, 14) xor mul(a3, 11)
                s[c * 4 + 3] = mul(a0, 11) xor mul(a1, 13) xor mul(a2, 9) xor mul(a3, 14)
            }
        }
    }

    companion object {
        const val ENCRYPT_MODE = 1
        const val DECRYPT_MODE = 2
        fun getInstance(t: String): Cipher {
            val u = t.uppercase()
            require(u.startsWith("AES")) { "只支持 AES：$t" }
            return Cipher(u.contains("ECB"), !u.contains("NOPADDING"))
        }

        private fun mul(a0: Int, b0: Int): Int {
            var a = a0; var b = b0; var p = 0
            while (b != 0) { if (b and 1 != 0) p = p xor a; a = a shl 1; if (a and 0x100 != 0) a = a xor 0x11b; b = b shr 1 }
            return p
        }

        private val SBOX = IntArray(256)
        private val INV = IntArray(256)
        init {
            // 由有限域求逆 + 仿射变换生成 S 盒
            var p = 1; var q = 1
            do {
                p = p xor (p shl 1) xor (if (p and 0x80 != 0) 0x1b else 0); p = p and 0xff
                q = q xor (q shl 1); q = q xor (q shl 2); q = q xor (q shl 4); q = q and 0xff
                if (q and 0x80 != 0) q = q xor 0x09
                val x = q xor rotl(q, 1) xor rotl(q, 2) xor rotl(q, 3) xor rotl(q, 4)
                SBOX[p] = (x xor 0x63) and 0xff
            } while (p != 1)
            SBOX[0] = 0x63
            for (i in 0 until 256) INV[SBOX[i]] = i
        }
        private fun rotl(x: Int, s: Int) = ((x shl s) or (x ushr (8 - s))) and 0xff

        private fun expand(key: ByteArray): IntArray {
            val nk = key.size / 4; val nr = nk + 6
            val w = IntArray(4 * (nr + 1))
            for (i in 0 until nk) w[i] = ((key[4 * i].toInt() and 0xff) shl 24) or ((key[4 * i + 1].toInt() and 0xff) shl 16) or ((key[4 * i + 2].toInt() and 0xff) shl 8) or (key[4 * i + 3].toInt() and 0xff)
            var rcon = 1
            for (i in nk until w.size) {
                var t = w[i - 1]
                if (i % nk == 0) {
                    t = (t shl 8) or (t ushr 24)
                    t = sub(t) xor (rcon shl 24)
                    rcon = mul(rcon, 2)
                } else if (nk > 6 && i % nk == 4) t = sub(t)
                w[i] = w[i - nk] xor t
            }
            return w
        }
        private fun sub(t: Int) = (SBOX[(t ushr 24) and 0xff] shl 24) or (SBOX[(t ushr 16) and 0xff] shl 16) or (SBOX[(t ushr 8) and 0xff] shl 8) or SBOX[t and 0xff]
    }
}
