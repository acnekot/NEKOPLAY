package java.net

import platform.Foundation.NSURL

class URI(s0: String?) {
    private val s: String = s0 ?: throw NullPointerException()
    override fun toString() = s
    val scheme: String? get() = s.substringBefore(':', "").ifEmpty { null }
    internal fun filePath(): String = NSURL.URLWithString(s)?.path ?: s.removePrefix("file://")
}
