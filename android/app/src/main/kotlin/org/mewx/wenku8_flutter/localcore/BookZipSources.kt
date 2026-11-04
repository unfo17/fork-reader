package org.mewx.wenku8_flutter

import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Paths
import java.util.zip.ZipInputStream

/** Reopenable bounded members keep ZIP originals linked without extracting unrelated resources. */
internal object BookZipSources {
    data class Entry(val name: String, val size: Long)
    data class Inspection(val charset: String, val entries: List<Entry>)
    const val MAX_EXPANDED = 1024L * 1024 * 1024
    fun safeName(raw: String): String {
        val normalized = raw.replace('\\', '/')
        require(normalized.isNotEmpty() && !normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized) && '\u0000' !in normalized && normalized.split('/').none { it == ".." }) { "压缩包文件路径无效" }
        return normalized.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/").ifEmpty { "." }
    }
    fun inspect(open: () -> InputStream): Inspection {
        fun read(charset: Charset): Inspection = ZipInputStream(open(), charset).use { zip ->
            val entries = mutableListOf<Entry>()
            val names = mutableSetOf<String>()
            val bytes = ByteArray(65536)
            var total = 0L
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 12000) { "压缩包文件过多" }
                if (charset == Charsets.UTF_8 && '\uFFFD' in entry.name) throw IllegalArgumentException("MALFORMED ZIP filename")
                val name = safeName(entry.name)
                require(names.add(name)) { "压缩包包含重复路径" }
                var size = 0L
                while (true) { val n = zip.read(bytes); if (n < 0) break; total += n; size += n; require(total <= MAX_EXPANDED) { "压缩包展开内容超过 1GiB" } }
                if (!entry.isDirectory && BookArchiveImport.supported(name)) {
                    require(size in 1..BookArchiveImport.limit(name)) { "压缩包中的书籍为空或超过大小限制" }
                    entries += Entry(name, size)
                }
                zip.closeEntry()
            }
            Inspection(charset.name(), entries)
        }
        return try { read(Charsets.UTF_8) }
        catch (failure: IllegalArgumentException) {
            if (failure.cause !is java.nio.charset.CharacterCodingException && !failure.message.orEmpty().contains("MALFORMED", true)) throw failure
            read(Charset.forName("GB18030"))
        }
    }
    fun member(archive: BookDocumentInput, name: String, charset: String, expectedSize: Long? = null): InputStream {
        val wanted = safeName(name)
        val zip = ZipInputStream(archive.inputStream(), Charset.forName(charset))
        try {
            val buffer = ByteArray(65536)
            var expanded = 0L
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: error("压缩包中的原书已缺失，请重新关联")
                require(++count <= 12000) { "压缩包文件过多" }
                val found = safeName(entry.name)
                if (!entry.isDirectory && found == wanted) {
                    var read = 0L
                    return object : FilterInputStream(zip) {
                        override fun read(): Int { val byte = ByteArray(1); return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255 }
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            val n = `in`.read(bytes, offset, length)
                            if (n > 0) { read += n; require(read <= (expectedSize ?: BookArchiveImport.MAX_ARCHIVE)) { "压缩包资源超过大小限制或已变化" } }
                            if (n < 0 && expectedSize != null) require(read == expectedSize) { "压缩包原书大小已变化，请重新关联" }
                            return n
                        }
                    }
                }
                while (true) { val n = zip.read(buffer); if (n < 0) break; expanded += n; require(expanded <= MAX_EXPANDED) { "压缩包展开内容过大" } }
                zip.closeEntry()
            }
        } catch (error: Exception) { zip.close(); throw error }
    }
    fun document(archive: BookDocumentInput, entry: Entry, charset: String): BookDocumentInput =
        BookDocumentInput(entry.size, { member(archive, entry.name, charset, entry.size) }, related = { relative ->
            val uri = runCatching { java.net.URI(relative) }.getOrNull()
            if (uri == null || uri.isAbsolute || uri.path.isNullOrEmpty()) null else {
                val base = entry.name.substringBeforeLast('/', "")
                val path = Paths.get(base).resolve(uri.path).normalize().toString().replace('\\', '/')
                if (path.startsWith("../") || path.startsWith('/')) null
                else runCatching { member(archive, path, charset) }.getOrNull()
            }
        })
}
