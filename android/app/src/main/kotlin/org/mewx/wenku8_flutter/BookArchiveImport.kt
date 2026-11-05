package org.mewx.wenku8_flutter

import java.io.File
import java.io.InputStream

/** Bounded ZIP staging. Entry paths are labels only, never output filenames. */
internal class BookArchiveImport(private val root: File) {
    companion object {
        const val MAX_ARCHIVE = 512L * 1024 * 1024
        fun supported(name: String) = name.substringAfterLast('.').lowercase() in setOf("txt", "epub", "zip")
        fun limit(name: String) = if (name.endsWith(".txt", true)) 128L * 1024 * 1024 else MAX_ARCHIVE
    }
    fun prepare(name: String, file: File) = prepare(name, BookDocumentInput(file))
    fun prepare(name: String, input: BookDocumentInput): List<Map<String, String>> {
        require(supported(name)) { "预览版支持 EPUB、TXT 和 ZIP，请先转换其他文档" }
        require(input.length() in 1..limit(name)) { "书籍为空或超过大小限制" }
        root.mkdirs()
        if (!name.endsWith(".zip", true)) {
            val target = File.createTempFile("prepared-", ".source", root)
            try { input.inputStream().use { copy(it, target, limit(name)) } }
            catch (error: Exception) { target.delete(); throw error }
            return listOf(mapOf("name" to name, "path" to target.path))
        }
        val created = mutableListOf<File>()
        try {
            val result = mutableListOf<Map<String, String>>()
            var total = 0L
            input.zip().use { zip ->
                require(zip.entries.size <= 12000) { "ZIP 文件过多" }
                for (entry in zip.entries) {
                    if (entry.isDirectory) continue
                    val display = entry.name.replace('\\', '/').substringAfterLast('/')
                    if (!(display.endsWith(".txt", true) || display.endsWith(".epub", true))) continue
                    require(result.size < 256) { "一次最多导入 256 本书" }
                    val target = File.createTempFile("prepared-", ".source", root)
                    created += target
                    try {
                        zip.getInputStream(entry).use { copy(it, target, minOf(limit(display), 1024L * 1024 * 1024 - total)) }
                        total += target.length()
                        result += mapOf("name" to display, "path" to target.path)
                    } catch (error: Exception) {
                        target.delete()
                        result += mapOf("name" to display, "error" to (error.message ?: "压缩文件损坏"))
                    }
                }
            }
            require(result.isNotEmpty()) { "ZIP 中未找到 TXT 或 EPUB" }
            return result
        } catch (error: Exception) { created.forEach { it.delete() }; throw error }
    }
    private fun copy(input: InputStream, output: File, maximum: Long) {
        output.outputStream().use { sink ->
            val buffer = ByteArray(65536); var size = 0L
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                size += count; require(size <= maximum) { "文件展开后超过大小限制" }
                sink.write(buffer, 0, count)
            }
        }
        require(output.length() > 0) { "文件为空" }
    }
}
