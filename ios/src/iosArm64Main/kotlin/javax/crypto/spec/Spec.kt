package javax.crypto.spec

class SecretKeySpec(private val k: ByteArray, @Suppress("UNUSED_PARAMETER") alg: String) : javax.crypto.Key { override val encoded get() = k }
class IvParameterSpec(val iv: ByteArray)
