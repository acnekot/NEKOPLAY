package java.io

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.*

// iOS 版的 java.io.File：只实现共用代码用到的部分

open class IOException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
class FileNotFoundException(message: String?) : IOException(message)

class File(path: String) {
    val path: String = path.trimEnd('/').ifEmpty { "/" }
    constructor(parent: File?, child: String) : this(if (parent == null) child else parent.path.trimEnd('/') + "/" + child)
    constructor(parent: String?, child: String) : this(if (parent == null) child else parent.trimEnd('/') + "/" + child)
    constructor(uri: java.net.URI) : this(uri.filePath())

    val name: String get() = path.substringAfterLast('/')
    val nameWithoutExtension: String get() = name.substringBeforeLast('.')
    val extension: String get() = name.substringAfterLast('.', "")
    val absolutePath: String get() = path
    val canonicalPath: String get() = path
    val absoluteFile: File get() = this
    val parent: String? get() = path.substringBeforeLast('/', "").ifEmpty { null }
    val parentFile: File? get() = parent?.let { File(it) }

    private val fm get() = NSFileManager.defaultManager

    fun exists(): Boolean = fm.fileExistsAtPath(path)
    val isDirectory: Boolean get() = memScoped {
        val d = alloc<BooleanVar>(); fm.fileExistsAtPath(path, d.ptr) && d.value
    }
    val isFile: Boolean get() = exists() && !isDirectory
    fun isDirectory() = isDirectory
    fun canWrite(): Boolean = fm.isWritableFileAtPath(path)
    fun canRead(): Boolean = fm.isReadableFileAtPath(path)
    fun mkdirs(): Boolean = fm.createDirectoryAtPath(path, true, null, null)
    fun mkdir(): Boolean = fm.createDirectoryAtPath(path, false, null, null)
    fun delete(): Boolean = fm.removeItemAtPath(path, null)
    fun deleteRecursively(): Boolean = delete()
    fun renameTo(dest: File): Boolean { if (dest.exists()) dest.delete(); return fm.moveItemAtPath(path, dest.path, null) }
    fun length(): Long = (fm.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
    fun lastModified(): Long = ((fm.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970 ?: 0.0).times(1000).toLong()
    fun list(): Array<String>? = fm.contentsOfDirectoryAtPath(path, null)?.map { it as String }?.toTypedArray()
    fun listFiles(): Array<File>? = list()?.map { File(this, it) }?.toTypedArray()
    fun listFiles(f: (File) -> Boolean): Array<File>? = listFiles()?.filter(f)?.toTypedArray()
    fun walk(): Sequence<File> = sequence {
        yield(this@File)
        if (isDirectory) listFiles()?.forEach { yieldAll(it.walk()) }
    }

    fun readBytes(): ByteArray {
        val d = NSData.dataWithContentsOfFile(path) ?: throw FileNotFoundException("$path (No such file or directory)")
        return d.toByteArray()
    }
    fun readText(): String = readBytes().decodeToString()
    fun readLines(): List<String> = readText().lines()
    fun writeBytes(b: ByteArray) {
        parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (!b.toNSData().writeToFile(path, true)) throw IOException("写入失败：$path")
    }
    fun writeText(s: String) = writeBytes(s.encodeToByteArray())
    fun appendText(s: String) = appendBytes(s.encodeToByteArray())
    fun appendBytes(b: ByteArray) { FileOutputStream(this, true).use { it.write(b) } }
    fun outputStream(): FileOutputStream = FileOutputStream(this)
    fun inputStream(): InputStream = FileInputStream(this)
    fun toURI(): java.net.URI = java.net.URI("file://" + path)
    fun copyTo(target: File, overwrite: Boolean = false): File {
        if (target.exists()) { if (!overwrite) throw IOException("目标已存在：${target.path}"); target.delete() }
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (!fm.copyItemAtPath(path, target.path, null)) throw IOException("复制失败：$path")
        return target
    }
    fun createNewFile(): Boolean = if (exists()) false else { writeBytes(ByteArray(0)); true }

    override fun toString() = path
    override fun equals(other: Any?) = other is File && other.path == path
    override fun hashCode() = path.hashCode()
}

fun NSData.toByteArray(): ByteArray {
    val n = length.toInt(); if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

abstract class InputStream : AutoCloseable {
    abstract fun read(b: ByteArray, off: Int, len: Int): Int
    fun read(b: ByteArray): Int = read(b, 0, b.size)
    fun readBytes(): ByteArray {
        val out = ArrayList<ByteArray>(); var total = 0; val buf = ByteArray(65536)
        while (true) { val n = read(buf); if (n < 0) break; out += buf.copyOf(n); total += n }
        val r = ByteArray(total); var p = 0; for (c in out) { c.copyInto(r, p); p += c.size }
        return r
    }
    override fun close() {}
}

class ByteArrayInputStream(private val data: ByteArray) : InputStream() {
    private var pos = 0
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(len, data.size - pos); data.copyInto(b, off, pos, pos + n); pos += n; return n
    }
}

class FileInputStream(f: File) : InputStream() {
    private val fp: CPointer<FILE> = fopen(f.path, "rb") ?: throw FileNotFoundException(f.path)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = b.usePinned { fread(it.addressOf(off), 1u, len.toULong(), fp).toInt() }
        return if (n <= 0) -1 else n
    }
    override fun close() { fclose(fp) }
}

abstract class OutputStream : AutoCloseable {
    abstract fun write(b: ByteArray, off: Int, len: Int)
    fun write(b: ByteArray) = write(b, 0, b.size)
    open fun flush() {}
    override fun close() {}
}

class FileOutputStream(f: File, append: Boolean = false) : OutputStream() {
    private val fp: CPointer<FILE>
    init {
        f.parentFile?.let { if (!it.exists()) it.mkdirs() }
        fp = fopen(f.path, if (append) "ab" else "wb") ?: throw FileNotFoundException(f.path)
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        val n = b.usePinned { fwrite(it.addressOf(off), 1u, len.toULong(), fp).toInt() }
        if (n != len) throw IOException("写入失败")
    }
    override fun flush() { fflush(fp) }
    override fun close() { fclose(fp) }
}
