package org.mewx.wenku8_flutter

import java.io.File
import java.io.InputStream

/** Bounded ZIP staging. Entry paths are labels only, never output filenames. */
internal class BookArchiveImport(private val root: File) {
    companion object {
        const val MAX_ARCHIVE = 512L * 1024 * 1024
        val extensions = setOf(
            "txt", "epub", "zip", "mobi", "azw", "azw3", "prc", "pdf", "fb2",
            "docx", "doc", "rtf", "html", "htm", "xhtml", "md", "markdown",
        )
        fun extension(name: String) = name.substringAfterLast('.').lowercase()
        fun supported(name: String) = extension(name) in extensions
        fun limit(name: String) = when (extension(name)) {
            "epub", "pdf", "docx", "zip" -> MAX_ARCHIVE
            "mobi", "azw", "azw3", "prc" -> 256L * 1024 * 1024
            "html", "htm", "xhtml", "md", "markdown" -> 32L * 1024 * 1024
            else -> 128L * 1024 * 1024
        }
    }

    fun prepare(name: String, file: File) = prepare(name, BookDocumentInput(file))

    fun prepare(name: String, input: BookDocumentInput): List<Map<String, String>> {
        require(supported(name)) { "请选择支持的电子书或文档" }
        require(input.length() in 1..limit(name)) { "书籍为空或超过大小限制" }
        root.mkdirs()
        if (extension(name) != "zip") {
            return listOf(input.inputStream().use { prepareOne(name, it) })
        }

        val created = mutableListOf<File>()
        try {
            var total = 0L
            input.zip().use { zip ->
                require(zip.entries.size <= 12000) { "ZIP 文件过多" }
                val entries = zip.entries.filter { !it.isDirectory && supported(it.name) && extension(it.name) != "zip" }
                require(entries.size <= 200) { "一次最多导入 200 本书" }
                val byName = zip.entries.associateBy { it.name.replace('\\', '/') }
                fun resource(parent: String, source: String): InputStream? {
                    if (source.startsWith('/') || source.contains(':') || source.contains('\\')) return null
                    val base = parent.substringBeforeLast('/', "")
                    val relative = "$base/$source".trimStart('/')
                    val parts = ArrayDeque<String>()
                    for (part in relative.split('/')) {
                        when (part) {
                            "." -> Unit
                            ".." -> if (parts.isEmpty()) return null else parts.removeLast()
                            else -> parts.addLast(part)
                        }
                    }
                    return byName[parts.joinToString("/")]?.let { entry ->
                        if (entry.size > 8L * 1024 * 1024) return null
                        zip.getInputStream(entry)
                    }
                }
                return entries.map { entry ->
                    val relative = entry.name.replace('\\', '/')
                    val display = relative.substringAfterLast('/')
                    try {
                        zip.getInputStream(entry).use { data ->
                            prepareOne(display, data, { count ->
                                total += count
                                require(total <= 1024L * 1024 * 1024) { "ZIP 展开后超过 1 GiB" }
                            }) { source -> resource(relative, source) }
                        }.also { created += File(it.getValue("path")) }
                    } catch (error: Exception) {
                        if (total > 1024L * 1024 * 1024) throw error
                        mapOf("name" to display, "error" to (error.message ?: "文档未能解析"))
                    }
                }.also { require(it.isNotEmpty()) { "ZIP 中未找到支持的电子书或文档" } }
            }
        } catch (error: Exception) {
            created.forEach(File::delete)
            throw error
        }
    }

    private fun prepareOne(name: String, data: InputStream, onBytes: (Int) -> Unit = {},
                           resource: ((String) -> InputStream?)? = null): Map<String, String> {
        val target = File.createTempFile("prepared-", ".source", root)
        try {
            if (extension(name) in setOf("txt", "epub")) {
                copy(data, target, limit(name), onBytes)
                return mapOf("name" to name, "path" to target.path)
            }
            val work = java.nio.file.Files.createTempDirectory(root.toPath(), "book-convert-").toFile()
            try {
                val source = File(work, "source")
                copy(data, source, limit(name), onBytes)
                BookDocumentConversion(work).convert(name, source, target, resource)
                return mapOf("name" to "${name.substringBeforeLast('.')}.epub", "path" to target.path)
            } finally {
                work.deleteRecursively()
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }
    private fun copy(input: InputStream, output: File, maximum: Long, onBytes: (Int) -> Unit = {}) {
        output.outputStream().use { sink ->
            val buffer = ByteArray(65536); var size = 0L
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                onBytes(count)
                size += count; require(size <= maximum) { "文件展开后超过大小限制" }
                sink.write(buffer, 0, count)
            }
        }
        require(output.length() > 0) { "文件为空" }
    }
}
